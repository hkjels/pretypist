#!/usr/bin/env node

/**
 * Inspect a self-contained GLB without loading WebGL or adding build dependencies.
 *
 * The default mode is diagnostic: findings are printed but do not fail the
 * command. Pass --enforce to turn contract, quality, and budget findings into
 * a non-zero exit status. This lets the existing starter model remain
 * inspectable while providing a gate for a production replacement.
 */

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(SCRIPT_DIRECTORY, "..");
const DEFAULT_MODEL = path.join(
  PROJECT_ROOT,
  "resources/layout-sources/modified-engram/model.glb",
);

const GLB_MAGIC = 0x46546c67;
const GLB_VERSION = 2;
const JSON_CHUNK_TYPE = 0x4e4f534a;
const BIN_CHUNK_TYPE = 0x004e4942;
const TRIANGLES = 4;
const TRIANGLE_STRIP = 5;
const TRIANGLE_FAN = 6;
const FLOAT = 5126;
const UNSIGNED_BYTE = 5121;
const UNSIGNED_SHORT = 5123;
const UNSIGNED_INT = 5125;

const COMPONENTS_BY_TYPE = {
  SCALAR: 1,
  VEC2: 2,
  VEC3: 3,
  VEC4: 4,
  MAT2: 4,
  MAT3: 9,
  MAT4: 16,
};

const COMPONENT_TYPES = {
  5120: { bytes: 1, name: "BYTE", read: (view, offset) => view.getInt8(offset) },
  5121: { bytes: 1, name: "UNSIGNED_BYTE", read: (view, offset) => view.getUint8(offset) },
  5122: { bytes: 2, name: "SHORT", read: (view, offset) => view.getInt16(offset, true) },
  5123: { bytes: 2, name: "UNSIGNED_SHORT", read: (view, offset) => view.getUint16(offset, true) },
  5125: { bytes: 4, name: "UNSIGNED_INT", read: (view, offset) => view.getUint32(offset, true) },
  5126: { bytes: 4, name: "FLOAT", read: (view, offset) => view.getFloat32(offset, true) },
};

export const DEFAULT_REQUIRED_NODES = [
  "keyboard",
  "half_left",
  "half_right",
  "case_left",
  "case_right",
  ...Array.from({ length: 36 }, (_, index) => `key_${String(index).padStart(2, "0")}`),
];

export const DEFAULT_LIMITS = Object.freeze({
  // Effective triangles reflect both rendered halves, even when they share a mesh.
  caseEffectiveTriangles: 75_000,
  degenerateTriangles: 0,
  aspectRatio: 1_000,
  skinnyTriangles: 48,
});

const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const EXPECTED_SOURCE_COMMIT = "273676d11b06785fb5a1a94860a39fc36c38baba";

export const DEFAULT_EXPECTED_METADATA = [
  {
    path: ["asset", "version"],
    description: "glTF asset version 2.0",
    validate: (value) => value === "2.0",
  },
  {
    path: ["asset", "generator"],
    description: "a non-empty asset generator",
    validate: (value) => typeof value === "string" && value.trim().length > 0,
  },
  {
    path: ["asset", "extras", "units"],
    description: 'asset.extras.units = "metres"',
    validate: (value) => value === "metres",
  },
  {
    path: ["asset", "extras", "upAxis"],
    description: 'asset.extras.upAxis = "Y"',
    validate: (value) => value === "Y",
  },
  {
    path: ["asset", "extras", "sourceRepository"],
    description: "the upstream Chocofi repository URL",
    validate: (value) => value === "https://github.com/pashutk/chocofi",
  },
  {
    path: ["asset", "extras", "sourceCommit"],
    description: `the pinned Chocofi commit ${EXPECTED_SOURCE_COMMIT}`,
    validate: (value) => value === EXPECTED_SOURCE_COMMIT,
  },
  {
    path: ["asset", "extras", "sourceLicense"],
    description: 'asset.extras.sourceLicense = "CERN-OHL-P-2.0"',
    validate: (value) => value === "CERN-OHL-P-2.0",
  },
  ...[
    "sourceTopStepSha256",
    "sourceBottomStepSha256",
    "sourcePcbSha256",
    "renderMeshTopSha256",
    "renderMeshBottomSha256",
  ].map((name) => ({
    path: ["asset", "extras", name],
    description: `a lowercase SHA-256 value at asset.extras.${name}`,
    validate: (value) => typeof value === "string" && SHA256_PATTERN.test(value),
  })),
  {
    path: ["asset", "extras", "renderMeshGenerator"],
    description: "a non-empty deterministic render-mesh generator identifier",
    validate: (value) => typeof value === "string" && value.trim().length > 0,
  },
  {
    path: ["asset", "extras", "renderMeshLinearDeflectionMillimetres"],
    description: "a positive render-mesh linear deflection",
    validate: (value) => Number.isFinite(value) && value > 0,
  },
  {
    path: ["asset", "extras", "renderMeshAngularDeflectionDegrees"],
    description: "a positive render-mesh angular deflection",
    validate: (value) => Number.isFinite(value) && value > 0,
  },
  {
    path: ["asset", "extras", "renderMeshCaseTriangles"],
    description: "a non-negative stored case triangle count",
    validate: (value) => Number.isInteger(value) && value >= 0,
  },
  {
    path: ["asset", "extras", "renderMeshBoundaryEdges"],
    description: "bounded top/bottom STEP render-mesh boundary-edge counts",
    validate: (value) =>
      value !== null &&
      typeof value === "object" &&
      Number.isInteger(value.top) &&
      Number.isInteger(value.bottom) &&
      value.top >= 0 &&
      value.bottom >= 0 &&
      value.top <= 40 &&
      value.bottom <= 40,
  },
  {
    path: ["asset", "extras", "renderMeshNonManifoldEdges"],
    description: "zero non-manifold edges in both STEP render meshes",
    validate: (value) => value?.top === 0 && value?.bottom === 0,
  },
  {
    path: ["asset", "extras", "normalCreaseAngleDegrees"],
    description: "a positive case-normal crease angle",
    validate: (value) => Number.isFinite(value) && value > 0,
  },
];

function valueAtPath(object, segments) {
  return segments.reduce(
    (value, segment) => (value !== null && value !== undefined ? value[segment] : undefined),
    object,
  );
}

function byteLengthForAccessor(type, componentBytes) {
  const components = COMPONENTS_BY_TYPE[type];
  if (!components) return undefined;

  // glTF aligns each MAT2/MAT3 column to four bytes when components are one
  // or two bytes wide. Vectors and MAT4 are tightly packed.
  if (type === "MAT2" && componentBytes === 1) return 8;
  if (type === "MAT3" && componentBytes === 1) return 12;
  if (type === "MAT3" && componentBytes === 2) return 24;
  return components * componentBytes;
}

function formatPath(segments) {
  return segments.reduce(
    (result, segment) =>
      typeof segment === "number" ? `${result}[${segment}]` : `${result}${result ? "." : ""}${segment}`,
    "",
  );
}

function parseNonNegativeInteger(value, option) {
  if (!/^\d+$/.test(value ?? "")) throw new Error(`${option} requires a non-negative integer`);
  return Number(value);
}

function parsePositiveNumber(value, option) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) throw new Error(`${option} requires a positive number`);
  return parsed;
}

function parseArguments(arguments_) {
  const options = {
    input: DEFAULT_MODEL,
    enforce: false,
    json: false,
    limits: { ...DEFAULT_LIMITS },
  };
  let positionalInput = false;

  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index];
    if (argument === "--input") {
      const value = arguments_[index + 1];
      if (!value) throw new Error("--input requires a GLB path");
      options.input = path.resolve(value);
      positionalInput = true;
      index += 1;
    } else if (argument === "--enforce" || argument === "--strict") {
      options.enforce = true;
    } else if (argument === "--json") {
      options.json = true;
    } else if (argument === "--case-triangle-budget") {
      options.limits.caseEffectiveTriangles = parseNonNegativeInteger(
        arguments_[index + 1],
        argument,
      );
      index += 1;
    } else if (argument === "--max-degenerate-triangles") {
      options.limits.degenerateTriangles = parseNonNegativeInteger(
        arguments_[index + 1],
        argument,
      );
      index += 1;
    } else if (argument === "--max-aspect-ratio") {
      options.limits.aspectRatio = parsePositiveNumber(arguments_[index + 1], argument);
      index += 1;
    } else if (argument === "--max-skinny-triangles") {
      options.limits.skinnyTriangles = parseNonNegativeInteger(arguments_[index + 1], argument);
      index += 1;
    } else if (argument === "--help" || argument === "-h") {
      process.stdout.write(
        [
          "Usage: node scripts/audit-model.mjs [MODEL.glb] [options]",
          "",
          "Options:",
          "  --input FILE                     GLB to inspect (alternative to positional path)",
          "  --enforce, --strict              Exit non-zero when audit findings exist",
          "  --json                           Emit the complete machine-readable report",
          `  --case-triangle-budget N         Effective case limit (default ${DEFAULT_LIMITS.caseEffectiveTriangles})`,
          `  --max-degenerate-triangles N     Stored triangle limit (default ${DEFAULT_LIMITS.degenerateTriangles})`,
          `  --max-aspect-ratio N             Skinny-triangle threshold (default ${DEFAULT_LIMITS.aspectRatio})`,
          `  --max-skinny-triangles N         Count allowed above threshold (default ${DEFAULT_LIMITS.skinnyTriangles})`,
          "  -h, --help                       Show this help",
          "",
          "Report-only mode always exits zero if the GLB can be parsed. Use --enforce in CI",
          "or after installing a production model.",
          "",
        ].join("\n"),
      );
      process.exit(0);
    } else if (argument.startsWith("-")) {
      throw new Error(`Unknown option: ${argument}`);
    } else if (positionalInput) {
      throw new Error(`Unexpected second GLB path: ${argument}`);
    } else {
      options.input = path.resolve(argument);
      positionalInput = true;
    }
  }

  return options;
}

/** Parse the container and return its glTF JSON plus the embedded BIN chunk. */
export function parseGlb(glb) {
  if (!Buffer.isBuffer(glb)) throw new TypeError("parseGlb expects a Buffer");
  if (glb.length < 20) throw new Error("GLB is shorter than its header and first chunk");
  if (glb.readUInt32LE(0) !== GLB_MAGIC) throw new Error("Invalid GLB magic");
  if (glb.readUInt32LE(4) !== GLB_VERSION) throw new Error("GLB is not version 2");
  if (glb.readUInt32LE(8) !== glb.length) throw new Error("GLB length header does not match its file size");

  const chunks = [];
  let offset = 12;
  while (offset < glb.length) {
    if (offset + 8 > glb.length) throw new Error(`GLB chunk header at byte ${offset} is truncated`);
    const byteLength = glb.readUInt32LE(offset);
    const type = glb.readUInt32LE(offset + 4);
    const start = offset + 8;
    const end = start + byteLength;
    if (end > glb.length) throw new Error(`GLB chunk at byte ${offset} extends past end of file`);
    chunks.push({ type, data: glb.subarray(start, end) });
    offset = end;
  }
  if (offset !== glb.length) throw new Error("GLB chunks do not exactly fill the declared file length");
  if (chunks[0]?.type !== JSON_CHUNK_TYPE) throw new Error("The first GLB chunk is not JSON");

  let gltf;
  try {
    gltf = JSON.parse(chunks[0].data.toString("utf8").replace(/[\u0000\u0020]+$/u, ""));
  } catch (error) {
    throw new Error(`GLB contains invalid glTF JSON: ${error.message}`);
  }

  const binaryChunks = chunks.filter((chunk) => chunk.type === BIN_CHUNK_TYPE);
  return {
    gltf,
    binary: binaryChunks[0]?.data ?? Buffer.alloc(0),
    chunkCount: chunks.length,
    binaryChunkCount: binaryChunks.length,
  };
}

function percentile(sortedValues, fraction) {
  if (sortedValues.length === 0) return null;
  const index = Math.min(sortedValues.length - 1, Math.ceil(sortedValues.length * fraction) - 1);
  return sortedValues[Math.max(0, index)];
}

function aspectSummary(values) {
  const sorted = [...values].sort((left, right) => left - right);
  return {
    samples: sorted.length,
    p50: percentile(sorted, 0.5),
    p95: percentile(sorted, 0.95),
    p99: percentile(sorted, 0.99),
    max: sorted.at(-1) ?? null,
  };
}

function topologyTriangleCount(mode, elementCount) {
  if (mode === TRIANGLES) return Math.floor(elementCount / 3);
  if (mode === TRIANGLE_STRIP || mode === TRIANGLE_FAN) return Math.max(0, elementCount - 2);
  return 0;
}

function triangleIndices(mode, triangle, readIndex) {
  if (mode === TRIANGLES) {
    return [readIndex(triangle * 3), readIndex(triangle * 3 + 1), readIndex(triangle * 3 + 2)];
  }
  if (mode === TRIANGLE_FAN) {
    return [readIndex(0), readIndex(triangle + 1), readIndex(triangle + 2)];
  }
  const a = readIndex(triangle);
  const b = readIndex(triangle + 1);
  const c = readIndex(triangle + 2);
  return triangle % 2 === 0 ? [a, b, c] : [b, a, c];
}

function addFinding(report, code, message, location) {
  const key = `${code}\0${location ?? ""}\0${message}`;
  if (report._findingKeys.has(key)) return;
  report._findingKeys.add(key);
  report.findings.push({ code, message, ...(location ? { location } : {}) });
}

function validateBinaryLayout(report, gltf, binary, binaryChunkCount) {
  const buffers = Array.isArray(gltf.buffers) ? gltf.buffers : [];
  if (!Array.isArray(gltf.buffers)) {
    addFinding(report, "buffers.missing", "glTF must declare a buffers array", "buffers");
  }
  if (buffers.length !== 1) {
    addFinding(
      report,
      "buffers.count",
      `Expected one embedded buffer, found ${buffers.length}`,
      "buffers",
    );
  }
  if (binaryChunkCount !== 1) {
    addFinding(
      report,
      "glb.bin-count",
      `Expected one GLB BIN chunk, found ${binaryChunkCount}`,
    );
  }
  buffers.forEach((buffer, index) => {
    if (typeof buffer?.uri === "string") {
      addFinding(report, "buffers.external", "External buffer URIs are not self-contained", `buffers[${index}].uri`);
    }
    if (!Number.isInteger(buffer?.byteLength) || buffer.byteLength < 0) {
      addFinding(report, "buffers.byte-length", "Buffer byteLength must be a non-negative integer", `buffers[${index}].byteLength`);
    } else if (index === 0 && (binary.length < buffer.byteLength || binary.length - buffer.byteLength > 3)) {
      addFinding(
        report,
        "buffers.bin-length",
        `BIN chunk has ${binary.length} bytes but buffer declares ${buffer.byteLength}`,
        `buffers[${index}].byteLength`,
      );
    }
  });

  const bufferViews = Array.isArray(gltf.bufferViews) ? gltf.bufferViews : [];
  if (gltf.bufferViews !== undefined && !Array.isArray(gltf.bufferViews)) {
    addFinding(report, "buffer-views.type", "bufferViews must be an array", "bufferViews");
  }
  const validBufferViews = new Set();
  bufferViews.forEach((bufferView, index) => {
    const location = `bufferViews[${index}]`;
    let valid = true;
    if (!Number.isInteger(bufferView?.buffer) || !buffers[bufferView.buffer]) {
      addFinding(report, "buffer-view.buffer", "bufferView references an unknown buffer", `${location}.buffer`);
      valid = false;
    } else if (bufferView.buffer !== 0) {
      addFinding(report, "buffer-view.external", "Only the embedded GLB buffer can be audited", `${location}.buffer`);
      valid = false;
    }
    const byteOffset = bufferView?.byteOffset ?? 0;
    if (!Number.isInteger(byteOffset) || byteOffset < 0) {
      addFinding(report, "buffer-view.offset", "bufferView byteOffset must be a non-negative integer", `${location}.byteOffset`);
      valid = false;
    }
    if (!Number.isInteger(bufferView?.byteLength) || bufferView.byteLength < 1) {
      addFinding(report, "buffer-view.length", "bufferView byteLength must be a positive integer", `${location}.byteLength`);
      valid = false;
    }
    if (
      valid &&
      bufferView.buffer === 0 &&
      byteOffset + bufferView.byteLength > (buffers[0]?.byteLength ?? binary.length)
    ) {
      addFinding(report, "buffer-view.range", "bufferView extends past its buffer", location);
      valid = false;
    }
    if (valid && bufferView.buffer === 0 && byteOffset + bufferView.byteLength > binary.length) {
      addFinding(report, "buffer-view.bin-range", "bufferView extends past the embedded BIN chunk", location);
      valid = false;
    }
    if (
      bufferView?.byteStride !== undefined &&
      (!Number.isInteger(bufferView.byteStride) || bufferView.byteStride < 4 || bufferView.byteStride > 252)
    ) {
      addFinding(report, "buffer-view.stride", "bufferView byteStride must be an integer from 4 through 252", `${location}.byteStride`);
      valid = false;
    }
    if (bufferView?.target !== undefined && ![34962, 34963].includes(bufferView.target)) {
      addFinding(report, "buffer-view.target", "bufferView target must be ARRAY_BUFFER or ELEMENT_ARRAY_BUFFER", `${location}.target`);
      valid = false;
    }
    if (valid) validBufferViews.add(index);
  });

  return { buffers, bufferViews, validBufferViews };
}

function validateAccessors(report, gltf, layout) {
  const accessors = Array.isArray(gltf.accessors) ? gltf.accessors : [];
  if (!Array.isArray(gltf.accessors)) {
    addFinding(report, "accessors.missing", "glTF must declare an accessors array", "accessors");
  }
  const validAccessors = new Set();

  function validateSparse(accessor, accessorIndex, elementBytes, componentBytes) {
    const sparse = accessor.sparse;
    if (sparse === undefined) return true;
    const base = `accessors[${accessorIndex}].sparse`;
    let valid = true;
    if (!Number.isInteger(sparse?.count) || sparse.count < 1 || sparse.count > accessor.count) {
      addFinding(report, "accessor.sparse-count", "Sparse count must be positive and no greater than accessor count", `${base}.count`);
      valid = false;
    }
    const sparseIndexType = COMPONENT_TYPES[sparse?.indices?.componentType];
    if (![UNSIGNED_BYTE, UNSIGNED_SHORT, UNSIGNED_INT].includes(sparse?.indices?.componentType)) {
      addFinding(report, "accessor.sparse-index-type", "Sparse indices must use an unsigned integer component type", `${base}.indices.componentType`);
      valid = false;
    }
    const indexView = sparse?.indices?.bufferView;
    const valueView = sparse?.values?.bufferView;
    if (!Number.isInteger(indexView) || !layout.validBufferViews.has(indexView)) {
      addFinding(report, "accessor.sparse-index-view", "Sparse indices reference an invalid bufferView", `${base}.indices.bufferView`);
      valid = false;
    }
    if (!Number.isInteger(valueView) || !layout.validBufferViews.has(valueView)) {
      addFinding(report, "accessor.sparse-value-view", "Sparse values reference an invalid bufferView", `${base}.values.bufferView`);
      valid = false;
    }
    if (!valid) return false;

    const indexOffset = sparse.indices.byteOffset ?? 0;
    const valueOffset = sparse.values.byteOffset ?? 0;
    if (!Number.isInteger(indexOffset) || indexOffset < 0 || indexOffset % sparseIndexType.bytes !== 0) {
      addFinding(report, "accessor.sparse-index-offset", "Sparse index byteOffset is invalid or misaligned", `${base}.indices.byteOffset`);
      valid = false;
    }
    if (!Number.isInteger(valueOffset) || valueOffset < 0 || valueOffset % componentBytes !== 0) {
      addFinding(report, "accessor.sparse-value-offset", "Sparse value byteOffset is invalid or misaligned", `${base}.values.byteOffset`);
      valid = false;
    }
    const indexLength = layout.bufferViews[indexView].byteLength;
    const valueLength = layout.bufferViews[valueView].byteLength;
    if (valid && indexOffset + sparse.count * sparseIndexType.bytes > indexLength) {
      addFinding(report, "accessor.sparse-index-range", "Sparse indices extend past their bufferView", `${base}.indices`);
      valid = false;
    }
    if (valid && valueOffset + sparse.count * elementBytes > valueLength) {
      addFinding(report, "accessor.sparse-value-range", "Sparse values extend past their bufferView", `${base}.values`);
      valid = false;
    }
    return valid;
  }

  accessors.forEach((accessor, index) => {
    const location = `accessors[${index}]`;
    let valid = true;
    const component = COMPONENT_TYPES[accessor?.componentType];
    const components = COMPONENTS_BY_TYPE[accessor?.type];
    if (!component) {
      addFinding(report, "accessor.component-type", "Accessor has an unsupported componentType", `${location}.componentType`);
      valid = false;
    }
    if (!components) {
      addFinding(report, "accessor.type", "Accessor type is not a valid glTF element type", `${location}.type`);
      valid = false;
    }
    if (!Number.isInteger(accessor?.count) || accessor.count < 1) {
      addFinding(report, "accessor.count", "Accessor count must be a positive integer", `${location}.count`);
      valid = false;
    }
    if (accessor?.normalized !== undefined && typeof accessor.normalized !== "boolean") {
      addFinding(report, "accessor.normalized", "Accessor normalized must be boolean", `${location}.normalized`);
      valid = false;
    }
    if (accessor?.normalized === true && accessor.componentType === FLOAT) {
      addFinding(report, "accessor.float-normalized", "FLOAT accessors cannot be normalized", `${location}.normalized`);
      valid = false;
    }
    for (const property of ["min", "max"]) {
      if (accessor?.[property] !== undefined) {
        if (
          !Array.isArray(accessor[property]) ||
          accessor[property].length !== components ||
          !accessor[property].every(Number.isFinite)
        ) {
          addFinding(report, `accessor.${property}`, `Accessor ${property} must contain one finite value per component`, `${location}.${property}`);
          valid = false;
        }
      }
    }
    if (Array.isArray(accessor?.min) && Array.isArray(accessor?.max) && accessor.min.length === accessor.max.length) {
      if (accessor.min.some((value, componentIndex) => value > accessor.max[componentIndex])) {
        addFinding(report, "accessor.bounds", "Accessor min exceeds max", location);
        valid = false;
      }
    }
    if (!component || !components || !Number.isInteger(accessor?.count) || accessor.count < 1) return;

    const elementBytes = byteLengthForAccessor(accessor.type, component.bytes);
    const accessorOffset = accessor.byteOffset ?? 0;
    if (!Number.isInteger(accessorOffset) || accessorOffset < 0 || accessorOffset % component.bytes !== 0) {
      addFinding(report, "accessor.offset", "Accessor byteOffset is invalid or misaligned", `${location}.byteOffset`);
      valid = false;
    }
    if (accessor.bufferView !== undefined) {
      if (!Number.isInteger(accessor.bufferView) || !layout.validBufferViews.has(accessor.bufferView)) {
        addFinding(report, "accessor.buffer-view", "Accessor references an invalid bufferView", `${location}.bufferView`);
        valid = false;
      } else {
        const bufferView = layout.bufferViews[accessor.bufferView];
        const stride = bufferView.byteStride ?? elementBytes;
        const absoluteOffset = (bufferView.byteOffset ?? 0) + accessorOffset;
        if (absoluteOffset % component.bytes !== 0) {
          addFinding(report, "accessor.absolute-offset", "Accessor start is not aligned to its component size", location);
          valid = false;
        }
        if (stride < elementBytes || stride % component.bytes !== 0) {
          addFinding(report, "accessor.stride", `Accessor requires ${elementBytes} bytes but bufferView stride is ${stride}`, location);
          valid = false;
        }
        const required = accessorOffset + (accessor.count - 1) * stride + elementBytes;
        if (required > bufferView.byteLength) {
          addFinding(report, "accessor.range", `Accessor requires ${required} bytes inside a ${bufferView.byteLength}-byte bufferView`, location);
          valid = false;
        }
      }
    } else if (accessor.sparse === undefined) {
      addFinding(report, "accessor.storage", "Accessor has neither a bufferView nor sparse values", location);
      valid = false;
    }

    if (!validateSparse(accessor, index, elementBytes, component.bytes)) valid = false;
    if (valid) validAccessors.add(index);
  });

  return { accessors, validAccessors };
}

function makeAccessorReader(gltf, binary, layout, accessorState, accessorIndex) {
  if (!accessorState.validAccessors.has(accessorIndex)) return null;
  const accessor = accessorState.accessors[accessorIndex];
  const component = COMPONENT_TYPES[accessor.componentType];
  const components = COMPONENTS_BY_TYPE[accessor.type];
  const elementBytes = byteLengthForAccessor(accessor.type, component.bytes);
  const dataView = new DataView(binary.buffer, binary.byteOffset, binary.byteLength);
  let baseOffset = 0;
  let stride = elementBytes;
  if (accessor.bufferView !== undefined) {
    const bufferView = layout.bufferViews[accessor.bufferView];
    baseOffset = (bufferView.byteOffset ?? 0) + (accessor.byteOffset ?? 0);
    stride = bufferView.byteStride ?? elementBytes;
  }

  const sparseValues = new Map();
  if (accessor.sparse) {
    const sparse = accessor.sparse;
    const indexComponent = COMPONENT_TYPES[sparse.indices.componentType];
    const indexView = layout.bufferViews[sparse.indices.bufferView];
    const valueView = layout.bufferViews[sparse.values.bufferView];
    const sparseIndexOffset = (indexView.byteOffset ?? 0) + (sparse.indices.byteOffset ?? 0);
    const sparseValueOffset = (valueView.byteOffset ?? 0) + (sparse.values.byteOffset ?? 0);
    for (let sparseIndex = 0; sparseIndex < sparse.count; sparseIndex += 1) {
      const target = indexComponent.read(
        dataView,
        sparseIndexOffset + sparseIndex * indexComponent.bytes,
      );
      const values = Array.from({ length: components }, (_, componentIndex) =>
        component.read(
          dataView,
          sparseValueOffset + sparseIndex * elementBytes + componentIndex * component.bytes,
        ),
      );
      sparseValues.set(target, values);
    }
  }

  return {
    componentType: accessor.componentType,
    components,
    count: accessor.count,
    type: accessor.type,
    get(elementIndex, componentIndex = 0) {
      const sparse = sparseValues.get(elementIndex);
      if (sparse) return sparse[componentIndex];
      if (accessor.bufferView === undefined) return 0;
      return component.read(
        dataView,
        baseOffset + elementIndex * stride + componentIndex * component.bytes,
      );
    },
  };
}

function inspectNodeGraph(report, gltf) {
  const nodes = Array.isArray(gltf.nodes) ? gltf.nodes : [];
  const meshes = Array.isArray(gltf.meshes) ? gltf.meshes : [];
  if (!Array.isArray(gltf.nodes)) addFinding(report, "nodes.missing", "glTF must declare a nodes array", "nodes");
  if (!Array.isArray(gltf.meshes)) addFinding(report, "meshes.missing", "glTF must declare a meshes array", "meshes");

  const parents = Array.from({ length: nodes.length }, () => []);
  nodes.forEach((node, nodeIndex) => {
    if (node?.mesh !== undefined && (!Number.isInteger(node.mesh) || !meshes[node.mesh])) {
      addFinding(report, "node.mesh", "Node references an unknown mesh", `nodes[${nodeIndex}].mesh`);
    }
    if (node?.children !== undefined && !Array.isArray(node.children)) {
      addFinding(report, "node.children", "Node children must be an array", `nodes[${nodeIndex}].children`);
      return;
    }
    for (const [childOffset, child] of (node?.children ?? []).entries()) {
      if (!Number.isInteger(child) || !nodes[child]) {
        addFinding(report, "node.child", `Node references unknown child ${String(child)}`, `nodes[${nodeIndex}].children[${childOffset}]`);
      } else {
        parents[child].push(nodeIndex);
      }
    }
  });
  parents.forEach((nodeParents, nodeIndex) => {
    if (nodeParents.length > 1) {
      addFinding(report, "node.multiple-parents", `Node has ${nodeParents.length} parents`, `nodes[${nodeIndex}]`);
    }
  });

  const scenes = Array.isArray(gltf.scenes) ? gltf.scenes : [];
  if (!Array.isArray(gltf.scenes) || scenes.length === 0) {
    addFinding(report, "scenes.missing", "glTF must declare at least one scene", "scenes");
  }
  if (!Number.isInteger(gltf.scene) || !scenes[gltf.scene]) {
    addFinding(report, "scene.default", "glTF must select a valid default scene", "scene");
  }

  const meshUses = Array(meshes.length).fill(0);
  const caseMeshUses = Array(meshes.length).fill(0);
  const reachable = new Set();
  const roots = scenes[gltf.scene]?.nodes ?? [];
  if (!Array.isArray(roots)) {
    addFinding(report, "scene.nodes", "Default scene nodes must be an array", `scenes[${gltf.scene}].nodes`);
  }
  let occurrenceCount = 0;
  function visit(nodeIndex, ancestors) {
    if (!Number.isInteger(nodeIndex) || !nodes[nodeIndex]) return;
    if (ancestors.has(nodeIndex)) {
      addFinding(report, "node.cycle", `Node hierarchy contains a cycle through node ${nodeIndex}`, `nodes[${nodeIndex}]`);
      return;
    }
    occurrenceCount += 1;
    if (occurrenceCount > 1_000_000) {
      addFinding(report, "node.occurrences", "Node hierarchy expands beyond one million occurrences", "nodes");
      return;
    }
    reachable.add(nodeIndex);
    const node = nodes[nodeIndex];
    if (Number.isInteger(node.mesh) && meshes[node.mesh]) {
      meshUses[node.mesh] += 1;
      const shadowCaster = /^shadow_caster(?:_|$)/u.test(node.name ?? "");
      if (
        !shadowCaster &&
        (/^case(?:_|$)/u.test(node.name ?? "") || /^chocofi_case(?:_|$)/u.test(meshes[node.mesh]?.name ?? ""))
      ) {
        caseMeshUses[node.mesh] += 1;
      }
    }
    const nextAncestors = new Set(ancestors);
    nextAncestors.add(nodeIndex);
    for (const child of node.children ?? []) visit(child, nextAncestors);
  }
  for (const [rootOffset, root] of (Array.isArray(roots) ? roots : []).entries()) {
    if (!Number.isInteger(root) || !nodes[root]) {
      addFinding(report, "scene.root", `Default scene references unknown root ${String(root)}`, `scenes[${gltf.scene}].nodes[${rootOffset}]`);
    } else {
      visit(root, new Set());
    }
  }

  return { nodes, meshes, meshUses, caseMeshUses, reachable, occurrenceCount };
}

function validateContract(report, gltf, graph, requiredNodes) {
  const names = new Map();
  graph.nodes.forEach((node, index) => {
    if (typeof node?.name !== "string" || node.name.length === 0) return;
    const matches = names.get(node.name) ?? [];
    matches.push(index);
    names.set(node.name, matches);
  });

  for (const name of requiredNodes) {
    const matches = names.get(name) ?? [];
    if (matches.length === 0) {
      addFinding(report, "contract.node-missing", `Required node is missing: ${name}`, "nodes");
    } else if (matches.length > 1) {
      addFinding(report, "contract.node-duplicate", `Required node name is not unique: ${name}`, "nodes");
    } else if (!graph.reachable.has(matches[0])) {
      addFinding(report, "contract.node-unreachable", `Required node is outside the default scene: ${name}`, `nodes[${matches[0]}]`);
    }
  }

  const keyboardIndex = names.get("keyboard")?.length === 1 ? names.get("keyboard")[0] : undefined;
  if (keyboardIndex !== undefined) {
    const descendants = new Set();
    function collect(index) {
      if (descendants.has(index) || !graph.nodes[index]) return;
      descendants.add(index);
      for (const child of graph.nodes[index].children ?? []) collect(child);
    }
    collect(keyboardIndex);
    for (const name of requiredNodes.filter((candidate) => candidate !== "keyboard")) {
      const index = names.get(name)?.length === 1 ? names.get(name)[0] : undefined;
      if (index !== undefined && !descendants.has(index)) {
        addFinding(report, "contract.outside-root", `${name} is not below the keyboard root`, `nodes[${index}]`);
      }
    }
  }

  const keyNodePattern = /^key_(\d\d)$/u;
  const keyNames = graph.nodes
    .map((node) => node?.name)
    .filter((name) => typeof name === "string" && keyNodePattern.test(name));
  const expectedKeyNames = requiredNodes.filter((name) => keyNodePattern.test(name));
  report.contract = {
    requiredNodes: requiredNodes.length,
    foundRequiredNodes: requiredNodes.filter((name) => names.get(name)?.length === 1).length,
    keyNodes: keyNames.length,
    expectedKeyNodes: expectedKeyNames.length,
  };

  function hasRenderableDescendant(start) {
    const seen = new Set();
    function inspect(index) {
      if (seen.has(index) || !graph.nodes[index]) return false;
      seen.add(index);
      const node = graph.nodes[index];
      if (Number.isInteger(node.mesh) && graph.meshes[node.mesh]?.primitives?.some(
        (primitive) => Number.isInteger(primitive?.attributes?.POSITION),
      )) return true;
      return (node.children ?? []).some(inspect);
    }
    return inspect(start);
  }
  const renderableContractNodes = [
    ...expectedKeyNames,
    ...["case_left", "case_right"].filter((name) => requiredNodes.includes(name)),
  ];
  for (const name of renderableContractNodes) {
    const index = names.get(name)?.length === 1 ? names.get(name)[0] : undefined;
    if (index !== undefined && !hasRenderableDescendant(index)) {
      addFinding(report, "contract.not-renderable", `${name} has no renderable descendant`, `nodes[${index}]`);
    }
  }
}

function validateMetadata(report, gltf, expectations) {
  const checks = expectations.map((expectation) => {
    const value = valueAtPath(gltf, expectation.path);
    const valid = expectation.validate(value);
    if (!valid) {
      addFinding(
        report,
        "metadata.expected",
        `Expected ${expectation.description}; found ${value === undefined ? "missing" : JSON.stringify(value)}`,
        formatPath(expectation.path),
      );
    }
    return { path: formatPath(expectation.path), description: expectation.description, value, valid };
  });
  report.metadata = {
    valid: checks.filter((check) => check.valid).length,
    expected: checks.length,
    checks,
  };
}

function inspectMeshes(report, gltf, binary, layout, accessorState, graph, limits) {
  const meshReports = [];
  const allAspects = [];
  let storedTriangles = 0;
  let effectiveTriangles = 0;
  let storedDegenerates = 0;
  let effectiveDegenerates = 0;
  let storedAboveAspectLimit = 0;
  let effectiveAboveAspectLimit = 0;
  let primitiveCount = 0;

  graph.meshes.forEach((mesh, meshIndex) => {
    const meshAspects = [];
    let triangles = 0;
    let degenerates = 0;
    let aboveAspectLimit = 0;
    const primitives = Array.isArray(mesh?.primitives) ? mesh.primitives : [];
    if (!Array.isArray(mesh?.primitives) || primitives.length === 0) {
      addFinding(report, "mesh.primitives", "Mesh must contain at least one primitive", `meshes[${meshIndex}].primitives`);
    }
    primitiveCount += primitives.length;

    primitives.forEach((primitive, primitiveIndex) => {
      const location = `meshes[${meshIndex}].primitives[${primitiveIndex}]`;
      const mode = primitive?.mode ?? TRIANGLES;
      if (![0, 1, 2, 3, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN].includes(mode)) {
        addFinding(report, "primitive.mode", `Unknown primitive mode ${String(mode)}`, `${location}.mode`);
        return;
      }
      if (primitive?.extensions?.KHR_draco_mesh_compression) {
        addFinding(report, "primitive.draco", "Draco-compressed geometry cannot be audited without a decoder", location);
        return;
      }
      const positionIndex = primitive?.attributes?.POSITION;
      if (!Number.isInteger(positionIndex)) {
        addFinding(report, "primitive.position", "Primitive has no POSITION accessor", `${location}.attributes.POSITION`);
        return;
      }
      const positions = makeAccessorReader(gltf, binary, layout, accessorState, positionIndex);
      if (!positions) return;
      if (positions.type !== "VEC3" || positions.componentType !== FLOAT) {
        addFinding(report, "primitive.position-format", "POSITION must be a FLOAT VEC3 accessor", `${location}.attributes.POSITION`);
        return;
      }
      const positionAccessor = accessorState.accessors[positionIndex];
      if (!Array.isArray(positionAccessor.min) || !Array.isArray(positionAccessor.max)) {
        addFinding(report, "primitive.position-bounds", "POSITION accessor must declare min and max bounds", `${location}.attributes.POSITION`);
      }

      let indices;
      if (primitive.indices === undefined) {
        indices = { count: positions.count, get: (index) => index };
      } else {
        indices = makeAccessorReader(gltf, binary, layout, accessorState, primitive.indices);
        if (!indices) return;
        if (indices.type !== "SCALAR" || ![UNSIGNED_BYTE, UNSIGNED_SHORT, UNSIGNED_INT].includes(indices.componentType)) {
          addFinding(report, "primitive.index-format", "Indices must be an unsigned SCALAR accessor", `${location}.indices`);
          return;
        }
      }
      if (mode === TRIANGLES && indices.count % 3 !== 0) {
        addFinding(report, "primitive.triangle-count", "TRIANGLES index/vertex count is not divisible by three", location);
      }
      const primitiveTriangles = topologyTriangleCount(mode, indices.count);
      triangles += primitiveTriangles;
      if (![TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN].includes(mode)) return;

      let invalidIndices = 0;
      for (let triangle = 0; triangle < primitiveTriangles; triangle += 1) {
        const vertexIndices = triangleIndices(mode, triangle, (index) => indices.get(index));
        if (vertexIndices.some((index) => !Number.isInteger(index) || index < 0 || index >= positions.count)) {
          invalidIndices += 1;
          continue;
        }
        const vertices = vertexIndices.map((index) => [
          positions.get(index, 0),
          positions.get(index, 1),
          positions.get(index, 2),
        ]);
        if (vertices.flat().some((value) => !Number.isFinite(value))) {
          invalidIndices += 1;
          continue;
        }
        const edgesSquared = [
          squaredDistance(vertices[0], vertices[1]),
          squaredDistance(vertices[1], vertices[2]),
          squaredDistance(vertices[2], vertices[0]),
        ];
        const longestSquared = Math.max(...edgesSquared);
        const ab = subtract(vertices[1], vertices[0]);
        const ac = subtract(vertices[2], vertices[0]);
        const twiceArea = vectorLength(cross(ab, ac));
        if (
          new Set(vertexIndices).size < 3 ||
          longestSquared === 0 ||
          twiceArea <= longestSquared * 1e-12
        ) {
          degenerates += 1;
          continue;
        }
        // Longest edge divided by its corresponding altitude. An equilateral
        // triangle is ~1.155; larger values mean a skinnier triangle.
        const aspect = longestSquared / twiceArea;
        meshAspects.push(aspect);
        allAspects.push(aspect);
        if (aspect > limits.aspectRatio) aboveAspectLimit += 1;
      }
      if (invalidIndices > 0) {
        addFinding(report, "primitive.indices", `${invalidIndices} triangle(s) contain invalid indices or non-finite positions`, location);
      }
    });

    const uses = graph.meshUses[meshIndex] ?? 0;
    const caseUses = graph.caseMeshUses[meshIndex] ?? 0;
    const effective = triangles * uses;
    const effectiveDegenerateCount = degenerates * uses;
    const effectiveAboveAspect = aboveAspectLimit * uses;
    storedTriangles += triangles;
    effectiveTriangles += effective;
    storedDegenerates += degenerates;
    effectiveDegenerates += effectiveDegenerateCount;
    storedAboveAspectLimit += aboveAspectLimit;
    effectiveAboveAspectLimit += effectiveAboveAspect;
    meshReports.push({
      index: meshIndex,
      name: mesh?.name ?? `(mesh ${meshIndex})`,
      primitives: primitives.length,
      uses,
      caseUses,
      storedTriangles: triangles,
      effectiveTriangles: effective,
      degenerates,
      effectiveDegenerates: effectiveDegenerateCount,
      aboveAspectLimit,
      effectiveAboveAspectLimit: effectiveAboveAspect,
      aspect: aspectSummary(meshAspects),
    });
  });

  const caseMeshes = meshReports.filter((mesh) => mesh.caseUses > 0);
  const caseStoredTriangles = caseMeshes.reduce((sum, mesh) => sum + mesh.storedTriangles, 0);
  const caseEffectiveTriangles = caseMeshes.reduce(
    (sum, mesh) => sum + mesh.storedTriangles * mesh.caseUses,
    0,
  );
  report.structure.primitiveCount = primitiveCount;
  report.triangles = {
    stored: storedTriangles,
    effective: effectiveTriangles,
    caseStored: caseStoredTriangles,
    caseEffective: caseEffectiveTriangles,
    caseBudget: limits.caseEffectiveTriangles,
    degeneratesStored: storedDegenerates,
    degeneratesEffective: effectiveDegenerates,
    degenerateLimit: limits.degenerateTriangles,
    aboveAspectLimitStored: storedAboveAspectLimit,
    aboveAspectLimitEffective: effectiveAboveAspectLimit,
    aspectLimit: limits.aspectRatio,
    skinnyTriangleLimit: limits.skinnyTriangles,
    aspect: aspectSummary(allAspects),
  };
  report.meshes = meshReports;

  const declaredCaseTriangles = gltf.asset?.extras?.renderMeshCaseTriangles;
  if (Number.isInteger(declaredCaseTriangles) && declaredCaseTriangles !== caseStoredTriangles) {
    addFinding(
      report,
      "metadata.case-triangles",
      `asset.extras.renderMeshCaseTriangles is ${declaredCaseTriangles}, but the GLB stores ${caseStoredTriangles} visible case triangles`,
      "asset.extras.renderMeshCaseTriangles",
    );
  }

  if (caseEffectiveTriangles > limits.caseEffectiveTriangles) {
    addFinding(
      report,
      "budget.case-triangles",
      `Effective case geometry has ${caseEffectiveTriangles} triangles; budget is ${limits.caseEffectiveTriangles}`,
      "meshes",
    );
  }
  if (storedDegenerates > limits.degenerateTriangles) {
    addFinding(
      report,
      "quality.degenerate",
      `Stored geometry has ${storedDegenerates} degenerate triangles; limit is ${limits.degenerateTriangles}`,
      "meshes",
    );
  }
  if (storedAboveAspectLimit > limits.skinnyTriangles) {
    addFinding(
      report,
      "quality.aspect",
      `${storedAboveAspectLimit} stored triangle(s) exceed aspect ratio ${limits.aspectRatio}; budget is ${limits.skinnyTriangles}`,
      "meshes",
    );
  }
}

function subtract(left, right) {
  return [left[0] - right[0], left[1] - right[1], left[2] - right[2]];
}

function squaredDistance(left, right) {
  const difference = subtract(left, right);
  return difference[0] ** 2 + difference[1] ** 2 + difference[2] ** 2;
}

function cross(left, right) {
  return [
    left[1] * right[2] - left[2] * right[1],
    left[2] * right[0] - left[0] * right[2],
    left[0] * right[1] - left[1] * right[0],
  ];
}

function vectorLength(vector) {
  return Math.hypot(vector[0], vector[1], vector[2]);
}

/** Return a serializable audit report for a complete GLB buffer. */
export function auditGlb(
  glb,
  {
    limits = DEFAULT_LIMITS,
    requiredNodes = DEFAULT_REQUIRED_NODES,
    expectedMetadata = DEFAULT_EXPECTED_METADATA,
  } = {},
) {
  const resolvedLimits = { ...DEFAULT_LIMITS, ...limits };
  const parsed = parseGlb(glb);
  const gltf = parsed.gltf;
  const report = {
    formatVersion: 1,
    fileBytes: glb.length,
    structure: {
      chunkCount: parsed.chunkCount,
      binaryChunkCount: parsed.binaryChunkCount,
      nodeCount: Array.isArray(gltf.nodes) ? gltf.nodes.length : 0,
      meshCount: Array.isArray(gltf.meshes) ? gltf.meshes.length : 0,
      accessorCount: Array.isArray(gltf.accessors) ? gltf.accessors.length : 0,
      bufferViewCount: Array.isArray(gltf.bufferViews) ? gltf.bufferViews.length : 0,
      materialCount: Array.isArray(gltf.materials) ? gltf.materials.length : 0,
      primitiveCount: 0,
    },
    accessors: { valid: 0, invalid: 0 },
    findings: [],
    _findingKeys: new Set(),
  };

  if ((gltf.images ?? []).some((image) => typeof image?.uri === "string")) {
    addFinding(report, "images.external", "External image URIs are not self-contained", "images");
  }
  if ((gltf.extensionsUsed ?? []).some((extension) => extension === "EXT_meshopt_compression")) {
    addFinding(report, "compression.meshopt", "Meshopt-compressed accessors cannot be audited without a decoder", "extensionsUsed");
  }

  const layout = validateBinaryLayout(report, gltf, parsed.binary, parsed.binaryChunkCount);
  const accessorState = validateAccessors(report, gltf, layout);
  report.accessors.valid = accessorState.validAccessors.size;
  report.accessors.invalid = report.structure.accessorCount - accessorState.validAccessors.size;
  const graph = inspectNodeGraph(report, gltf);
  report.structure.reachableNodeCount = graph.reachable.size;
  report.structure.nodeOccurrences = graph.occurrenceCount;
  validateContract(report, gltf, graph, requiredNodes);
  validateMetadata(report, gltf, expectedMetadata);
  inspectMeshes(report, gltf, parsed.binary, layout, accessorState, graph, resolvedLimits);

  delete report._findingKeys;
  report.ok = report.findings.length === 0;
  return report;
}

function formatInteger(value) {
  return new Intl.NumberFormat("en-US").format(value);
}

function formatAspect(value) {
  if (value === null) return "n/a";
  if (!Number.isFinite(value)) return "infinite";
  if (value >= 100) return value.toFixed(0);
  if (value >= 10) return value.toFixed(1);
  return value.toFixed(2);
}

/** Render a compact, deterministic human-readable report. */
export function formatReport(report, { input = "model.glb", enforce = false } = {}) {
  const relativeInput = path.relative(PROJECT_ROOT, input);
  const triangle = report.triangles;
  const lines = [
    `PRETYPIST GLB audit: ${relativeInput.startsWith("..") ? input : relativeInput}`,
    `Mode: ${enforce ? "enforce" : "report only (findings do not fail this command)"}`,
    `File: ${formatInteger(report.fileBytes)} bytes, ${report.structure.chunkCount} chunks`,
    `Structure: ${report.structure.nodeCount} nodes (${report.structure.reachableNodeCount} reachable), ` +
      `${report.structure.meshCount} meshes, ${report.structure.primitiveCount} primitives, ` +
      `${report.structure.accessorCount} accessors, ${report.structure.materialCount} materials`,
    `Accessors: ${report.accessors.valid} valid, ${report.accessors.invalid} invalid`,
    `Contract: ${report.contract.foundRequiredNodes}/${report.contract.requiredNodes} required nodes; ` +
      `${report.contract.keyNodes}/${report.contract.expectedKeyNodes} key nodes`,
    `Metadata: ${report.metadata.valid}/${report.metadata.expected} expected fields valid`,
    `Triangles: ${formatInteger(triangle.stored)} stored; ${formatInteger(triangle.effective)} effective after node instancing`,
    `Visible case: ${formatInteger(triangle.caseStored)} stored; ${formatInteger(triangle.caseEffective)} effective; ` +
      `budget ${formatInteger(triangle.caseBudget)}`,
    `Quality: ${formatInteger(triangle.degeneratesStored)} stored degenerates ` +
      `(${formatInteger(triangle.degeneratesEffective)} effective); ` +
      `${formatInteger(triangle.aboveAspectLimitStored)} above aspect ${formatAspect(triangle.aspectLimit)} ` +
      `(budget ${formatInteger(triangle.skinnyTriangleLimit)})`,
    `Aspect (stored, non-degenerate): p50 ${formatAspect(triangle.aspect.p50)}, ` +
      `p95 ${formatAspect(triangle.aspect.p95)}, p99 ${formatAspect(triangle.aspect.p99)}, ` +
      `max ${formatAspect(triangle.aspect.max)}`,
    "",
    "Mesh contribution (sorted by effective triangles):",
  ];

  const sortedMeshes = [...report.meshes].sort(
    (left, right) => right.effectiveTriangles - left.effectiveTriangles || left.index - right.index,
  );
  for (const mesh of sortedMeshes) {
    lines.push(
      `  ${mesh.name}: ${formatInteger(mesh.storedTriangles)} stored × ${mesh.uses} use(s) = ` +
        `${formatInteger(mesh.effectiveTriangles)} effective; degenerate ${formatInteger(mesh.degenerates)}; ` +
        `aspect p95 ${formatAspect(mesh.aspect.p95)}, max ${formatAspect(mesh.aspect.max)}`,
    );
  }

  lines.push("", `Findings: ${report.findings.length}`);
  if (report.findings.length === 0) {
    lines.push("  none");
  } else {
    report.findings.forEach((finding) => {
      lines.push(`  [${finding.code}] ${finding.message}${finding.location ? ` (${finding.location})` : ""}`);
    });
  }
  if (!enforce && report.findings.length > 0) {
    lines.push("", "Report-only audit completed. Re-run with --enforce to make these findings fail.");
  }
  return `${lines.join("\n")}\n`;
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  if (!fs.existsSync(options.input) || !fs.statSync(options.input).isFile()) {
    throw new Error(`GLB file does not exist: ${options.input}`);
  }
  const report = auditGlb(fs.readFileSync(options.input), { limits: options.limits });
  if (options.json) {
    process.stdout.write(`${JSON.stringify({ input: options.input, mode: options.enforce ? "enforce" : "report", ...report }, null, 2)}\n`);
  } else {
    process.stdout.write(formatReport(report, options));
  }
  if (options.enforce && !report.ok) process.exitCode = 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`Model audit failed: ${error.message}\n`);
    process.exitCode = 1;
  }
}
