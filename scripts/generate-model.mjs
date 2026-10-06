#!/usr/bin/env node

/**
 * Assemble PRETYPIST's Chocofi model from checked-in, STEP-derived render meshes.
 *
 * The authoritative source case is a left-half B-rep in millimetres/Z-up. The
 * optional tessellate-step.py authoring task creates compact deterministic STLs
 * from that B-rep. This generator applies crease-aware welding, converts them
 * to metres/Y-up, adds PCB-positioned sculpted blank keycaps, switch housings,
 * and wireless electronics, then mirrors the assembly for the right half. The
 * GLB uses only core glTF 2.0 and contains no external resources.
 */

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import {
  CatmullRomCurve3,
  CylinderGeometry,
  TubeGeometry,
  Vector3,
} from "three";
import { STLLoader } from "three/addons/loaders/STLLoader.js";
import {
  CHOCOFI_V1_ELECTRONICS_ANCHORS_MM,
  ELECTRONICS_PLACEMENT,
  JST_XH_STYLE_PLACEMENT,
  NICE_NANO_V2_DIMENSIONS,
  NICE_VIEW_DIMENSIONS,
  assertAssembly,
  createJstXhStyleAssembly,
  createNiceNanoV2Assembly,
  createNiceViewAssembly,
} from "./model/electronics.mjs";

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(SCRIPT_DIRECTORY, "..");
const DEFAULT_OUTPUT = path.join(
  PROJECT_ROOT,
  "resources/layout-sources/modified-engram/model.glb",
);
const SOURCE_DIRECTORY = path.join(
  PROJECT_ROOT,
  "resources/layout-sources/modified-engram/upstream/chocofi-case-v1",
);
const RENDER_MESH_DIRECTORY = path.join(
  PROJECT_ROOT,
  "resources/layout-sources/modified-engram/render-mesh",
);
const TOP_STEP = path.join(SOURCE_DIRECTORY, "top.step");
const BOTTOM_STEP = path.join(SOURCE_DIRECTORY, "bottom.step");
const TOP_STL = path.join(RENDER_MESH_DIRECTORY, "top.stl");
const BOTTOM_STL = path.join(RENDER_MESH_DIRECTORY, "bottom.stl");
const RENDER_MESH_METADATA = path.join(RENDER_MESH_DIRECTORY, "mesh-metadata.json");
const PCB_SOURCE = path.join(SOURCE_DIRECTORY, "chocofi.kicad_pcb");
const UPSTREAM_COMMIT = "273676d11b06785fb5a1a94860a39fc36c38baba";
const EXPECTED_SOURCE_SHA256 = {
  topStep: "73555d15108088b8f885d367077314532b04bc34741c7bbb3726d660a7e60e43",
  bottomStep: "79d5427d1427d596f045d81a9964726fcc30c37b6262e0d14effda59c6b0f7fc",
  pcb: "1e8f65fc5cc3df280c716f6b1d3e730757d445d84b1a809a7a87aea7d593a9fc",
};
const CASE_CREASE_ANGLE_DEGREES = 30;
const CASE_TRIANGLE_BUDGET = 30_000;
const CASE_BOUNDARY_EDGE_BUDGET = 40;

const GLB_MAGIC = 0x46546c67;
const GLB_VERSION = 2;
const JSON_CHUNK_TYPE = 0x4e4f534a;
const BIN_CHUNK_TYPE = 0x004e4942;

const KEY_COUNT = 36;
const METRES_PER_MILLIMETRE = 0.001;
const KEY_BASE_HEIGHT = 0.004;
const SWITCH_BASE_HEIGHT = 0.00355;
const HALF_OFFSET = 0.069;
const HALF_ANGLE_DEGREES = 6;

const COMPONENT_ANCHORS_MM = {
  controller: CHOCOFI_V1_ELECTRONICS_ANCHORS_MM.niceNano,
  displayHeader: CHOCOFI_V1_ELECTRONICS_ANCHORS_MM.niceViewHeader,
  batteryHeader: CHOCOFI_V1_ELECTRONICS_ANCHORS_MM.batteryJ3Pad1,
  powerSwitch: CHOCOFI_V1_ELECTRONICS_ANCHORS_MM.powerSwitch,
  resetSwitch: CHOCOFI_V1_ELECTRONICS_ANCHORS_MM.resetSwitch,
};

const LEFT_KEY_REFERENCES = new Map([
  [0, "SW2"], [1, "SW3"], [2, "SW4"], [3, "SW5"], [4, "SW6"],
  [10, "SW8"], [11, "SW9"], [12, "SW10"], [13, "SW11"], [14, "SW12"],
  [20, "SW14"], [21, "SW15"], [22, "SW16"], [23, "SW17"], [24, "SW18"],
  [30, "SW19"], [31, "SW20"], [32, "SW21"],
]);

function parseArguments(arguments_) {
  const options = { output: DEFAULT_OUTPUT, checkOnly: false };

  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index];
    if (argument === "--output") {
      const value = arguments_[index + 1];
      if (!value) throw new Error("--output requires a file path");
      options.output = path.resolve(value);
      index += 1;
    } else if (argument === "--check") {
      options.checkOnly = true;
    } else if (argument === "--help" || argument === "-h") {
      process.stdout.write(
        "Usage: node scripts/generate-model.mjs [--output FILE] [--check]\n",
      );
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }

  return options;
}

function sha256(buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex");
}

function readRequiredFile(filepath, description) {
  if (!fs.existsSync(filepath)) {
    throw new Error(`Required ${description} is missing: ${path.relative(PROJECT_ROOT, filepath)}`);
  }
  return fs.readFileSync(filepath);
}

function readRenderMeshMetadata() {
  const contents = readRequiredFile(RENDER_MESH_METADATA, "render-mesh metadata");
  let metadata;
  try {
    metadata = JSON.parse(contents.toString("utf8"));
  } catch (error) {
    throw new Error(`Render-mesh metadata is invalid JSON: ${error.message}`);
  }
  if (metadata.formatVersion !== 1 || !metadata.meshes?.top || !metadata.meshes?.bottom) {
    throw new Error("Render-mesh metadata has an unsupported or incomplete format");
  }
  return metadata;
}

function readStl(filepath) {
  const contents = readRequiredFile(filepath, "checked-in STEP-derived STL");
  const arrayBuffer = contents.buffer.slice(
    contents.byteOffset,
    contents.byteOffset + contents.byteLength,
  );
  const geometry = new STLLoader().parse(arrayBuffer);
  const positions = geometry.getAttribute("position")?.array;
  geometry.dispose();
  if (!positions || positions.length === 0 || positions.length % 9 !== 0) {
    throw new Error(`${path.basename(filepath)} does not contain complete STL triangles`);
  }
  return { contents, positions };
}

function sourceBounds(...positionArrays) {
  const minimum = [Infinity, Infinity, Infinity];
  const maximum = [-Infinity, -Infinity, -Infinity];
  for (const positions of positionArrays) {
    for (let offset = 0; offset < positions.length; offset += 3) {
      for (let component = 0; component < 3; component += 1) {
        minimum[component] = Math.min(minimum[component], positions[offset + component]);
        maximum[component] = Math.max(maximum[component], positions[offset + component]);
      }
    }
  }
  return { minimum, maximum };
}

function sourceToModel(x, y, z, center) {
  return [
    (x - center.x) * METRES_PER_MILLIMETRE,
    z * METRES_PER_MILLIMETRE,
    -(y - center.y) * METRES_PER_MILLIMETRE,
  ];
}

function computeVertexNormals(positions, indices) {
  const normals = Array(positions.length).fill(0);
  for (let offset = 0; offset < indices.length; offset += 3) {
    const aIndex = indices[offset] * 3;
    const bIndex = indices[offset + 1] * 3;
    const cIndex = indices[offset + 2] * 3;
    const ab = [
      positions[bIndex] - positions[aIndex],
      positions[bIndex + 1] - positions[aIndex + 1],
      positions[bIndex + 2] - positions[aIndex + 2],
    ];
    const ac = [
      positions[cIndex] - positions[aIndex],
      positions[cIndex + 1] - positions[aIndex + 1],
      positions[cIndex + 2] - positions[aIndex + 2],
    ];
    const faceNormal = cross(ab, ac);
    for (const index of [aIndex, bIndex, cIndex]) {
      normals[index] += faceNormal[0];
      normals[index + 1] += faceNormal[1];
      normals[index + 2] += faceNormal[2];
    }
  }
  for (let offset = 0; offset < normals.length; offset += 3) {
    const length = Math.hypot(normals[offset], normals[offset + 1], normals[offset + 2]);
    if (length > 1e-12) {
      normals[offset] /= length;
      normals[offset + 1] /= length;
      normals[offset + 2] /= length;
    } else {
      normals[offset + 1] = 1;
    }
  }
  return normals;
}

function weldStl(positionArray, center, creaseAngleDegrees = CASE_CREASE_ANGLE_DEGREES) {
  // STL carries one independent vertex triplet per triangle. First weld exact
  // positions for topology, then join only triangle corners connected across
  // an edge whose dihedral is below the crease threshold. This keeps curved
  // CAD faces smooth while preventing vertical walls from polluting the broad
  // top/bottom normals—the source of the old dark rims and shadow-like bands.
  const basePositions = [];
  const triangleBaseIndices = [];
  const vertexIndices = new Map();
  const quantization = 10_000; // 0.1 micrometre in the millimetre STL source.

  for (let offset = 0; offset < positionArray.length; offset += 3) {
    const x = positionArray[offset];
    const y = positionArray[offset + 1];
    const z = positionArray[offset + 2];
    const key = `${Math.round(x * quantization)},${Math.round(y * quantization)},${Math.round(z * quantization)}`;
    let vertexIndex = vertexIndices.get(key);
    if (vertexIndex === undefined) {
      vertexIndex = basePositions.length / 3;
      vertexIndices.set(key, vertexIndex);
      basePositions.push(...sourceToModel(x, y, z, center));
    }
    triangleBaseIndices.push(vertexIndex);
  }

  const faceCount = triangleBaseIndices.length / 3;
  const faceAreaVectors = [];
  const faceNormals = [];
  for (let face = 0; face < faceCount; face += 1) {
    const vertices = triangleBaseIndices.slice(face * 3, face * 3 + 3).map((index) =>
      basePositions.slice(index * 3, index * 3 + 3),
    );
    const areaVector = cross(
      subtractVector(vertices[1], vertices[0]),
      subtractVector(vertices[2], vertices[0]),
    );
    if (Math.hypot(...areaVector) <= 1e-18) {
      throw new Error(`STEP render mesh contains a degenerate triangle at ${face}`);
    }
    faceAreaVectors.push(areaVector);
    faceNormals.push(normalize(areaVector));
  }

  const parent = Array.from({ length: triangleBaseIndices.length }, (_, index) => index);
  function find(index) {
    let root = index;
    while (parent[root] !== root) root = parent[root];
    while (parent[index] !== index) {
      const next = parent[index];
      parent[index] = root;
      index = next;
    }
    return root;
  }
  function union(left, right) {
    const leftRoot = find(left);
    const rightRoot = find(right);
    if (leftRoot !== rightRoot) parent[rightRoot] = leftRoot;
  }

  const edgeFaces = new Map();
  for (let face = 0; face < faceCount; face += 1) {
    for (let corner = 0; corner < 3; corner += 1) {
      const next = (corner + 1) % 3;
      const start = triangleBaseIndices[face * 3 + corner];
      const end = triangleBaseIndices[face * 3 + next];
      const key = start < end ? `${start},${end}` : `${end},${start}`;
      const entries = edgeFaces.get(key) ?? [];
      entries.push({ face, corners: new Map([[start, face * 3 + corner], [end, face * 3 + next]]) });
      edgeFaces.set(key, entries);
    }
  }

  const cosineThreshold = Math.cos((creaseAngleDegrees * Math.PI) / 180);
  const boundaryEdges = [...edgeFaces.values()].filter((entries) => entries.length === 1).length;
  const nonManifoldEdges = [...edgeFaces.values()].filter((entries) => entries.length > 2).length;
  if (nonManifoldEdges > 0) {
    throw new Error(`STEP render mesh contains ${nonManifoldEdges} non-manifold edge(s)`);
  }
  if (boundaryEdges > CASE_BOUNDARY_EDGE_BUDGET) {
    throw new Error(
      `STEP render mesh contains ${boundaryEdges} boundary edges; budget is ${CASE_BOUNDARY_EDGE_BUDGET}`,
    );
  }
  for (const entries of edgeFaces.values()) {
    if (entries.length !== 2) continue;
    const [left, right] = entries;
    const dot = faceNormals[left.face].reduce(
      (sum, value, component) => sum + value * faceNormals[right.face][component],
      0,
    );
    if (dot + 1e-9 < cosineThreshold) continue;
    for (const baseVertex of left.corners.keys()) {
      union(left.corners.get(baseVertex), right.corners.get(baseVertex));
    }
  }

  const positions = [];
  const normalSums = [];
  const indices = [];
  const outputByRoot = new Map();
  for (let corner = 0; corner < triangleBaseIndices.length; corner += 1) {
    const root = find(corner);
    let outputIndex = outputByRoot.get(root);
    if (outputIndex === undefined) {
      outputIndex = positions.length / 3;
      outputByRoot.set(root, outputIndex);
      const baseIndex = triangleBaseIndices[corner];
      positions.push(...basePositions.slice(baseIndex * 3, baseIndex * 3 + 3));
      normalSums.push(0, 0, 0);
    }
    indices.push(outputIndex);
    const faceNormal = faceAreaVectors[Math.floor(corner / 3)];
    normalSums[outputIndex * 3] += faceNormal[0];
    normalSums[outputIndex * 3 + 1] += faceNormal[1];
    normalSums[outputIndex * 3 + 2] += faceNormal[2];
  }

  const normals = [];
  for (let offset = 0; offset < normalSums.length; offset += 3) {
    normals.push(...normalize(normalSums.slice(offset, offset + 3)));
  }
  return {
    positions,
    normals,
    indices,
    statistics: {
      sourceTriangles: faceCount,
      weldedPositions: basePositions.length / 3,
      creaseVertices: positions.length / 3,
      creaseAngleDegrees,
      boundaryEdges,
      nonManifoldEdges,
    },
  };
}

function countParentheses(line) {
  let depth = 0;
  let quoted = false;
  let escaped = false;
  for (const character of line) {
    if (escaped) {
      escaped = false;
    } else if (character === "\\" && quoted) {
      escaped = true;
    } else if (character === '"') {
      quoted = !quoted;
    } else if (!quoted && character === "(") {
      depth += 1;
    } else if (!quoted && character === ")") {
      depth -= 1;
    }
  }
  return depth;
}

function parseSwitchPositions(filepath) {
  if (!fs.existsSync(filepath)) {
    throw new Error(`Required copied PCB source is missing: ${path.relative(PROJECT_ROOT, filepath)}`);
  }
  const source = fs.readFileSync(filepath, "utf8");
  const switches = new Map();
  let block = [];
  let depth = 0;

  for (const line of source.split(/\r?\n/)) {
    if (block.length === 0) {
      if (!/^\s*\(footprint "keyswitches:Kailh_socket_PG1350_reversible"/.test(line)) {
        continue;
      }
      block = [line];
      depth = countParentheses(line);
    } else {
      block.push(line);
      depth += countParentheses(line);
    }

    if (block.length > 0 && depth === 0) {
      const text = block.join("\n");
      const reference = text.match(/\(fp_text reference "(SW\d+)"/i)?.[1];
      const at = text.match(/^\s*\(at\s+(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)(?:\s+(-?\d+(?:\.\d+)?))?\)/m);
      if (!reference || !at) throw new Error("Could not read a Choc switch footprint from the copied PCB");
      if (switches.has(reference)) throw new Error(`Duplicate switch reference in copied PCB: ${reference}`);
      switches.set(reference, {
        x: Number(at[1]),
        y: Number(at[2]),
        rotation: Number(at[3] ?? 0),
      });
      block = [];
    }
  }

  if (switches.size !== 18) {
    throw new Error(`Expected 18 Choc switch footprints in copied PCB, found ${switches.size}`);
  }
  for (const reference of LEFT_KEY_REFERENCES.values()) {
    if (!switches.has(reference)) throw new Error(`Copied PCB is missing ${reference}`);
  }
  return { source, switches };
}

function normalizeKeyRotation(degrees) {
  let result = degrees;
  while (result > 90) result -= 180;
  while (result <= -90) result += 180;
  return result;
}

function mirroredLeftIndex(index) {
  if (index >= 5 && index <= 9) return 9 - index;
  if (index >= 15 && index <= 19) return 29 - index;
  if (index >= 25 && index <= 29) return 49 - index;
  if (index >= 33 && index <= 35) return 65 - index;
  return index;
}

function verifyKeyOpenings(positionArray, switches) {
  for (const reference of LEFT_KEY_REFERENCES.values()) {
    const position = switches.get(reference);
    let nearest = Infinity;
    for (let offset = 0; offset < positionArray.length; offset += 3) {
      nearest = Math.min(
        nearest,
        Math.hypot(positionArray[offset] - position.x, positionArray[offset + 1] + position.y),
      );
    }
    if (nearest < 7 || nearest > 10) {
      throw new Error(
        `${reference} does not align with a case opening (nearest wall ${nearest.toFixed(3)} mm)`,
      );
    }
  }
}

function subtractVector(left, right) {
  return [left[0] - right[0], left[1] - right[1], left[2] - right[2]];
}

function cross(left, right) {
  return [
    left[1] * right[2] - left[2] * right[1],
    left[2] * right[0] - left[0] * right[2],
    left[0] * right[1] - left[1] * right[0],
  ];
}

function normalize(vector) {
  const length = Math.hypot(vector[0], vector[1], vector[2]);
  if (length === 0) throw new Error("Cannot normalize a zero-length vector");
  return vector.map((component) => component / length);
}

function emptyGeometry() {
  return { positions: [], normals: [], indices: [] };
}

function addQuad(geometry, a, b, c, d) {
  const offset = geometry.positions.length / 3;
  const normal = normalize(cross(subtractVector(b, a), subtractVector(c, a)));

  for (const vertex of [a, b, c, d]) {
    geometry.positions.push(...vertex);
    geometry.normals.push(...normal);
  }
  geometry.indices.push(
    offset,
    offset + 1,
    offset + 2,
    offset,
    offset + 2,
    offset + 3,
  );
}

function addTaperedBox(
  geometry,
  {
    center = [0, 0, 0],
    bottomSize,
    topSize,
    yBottom,
    yTop,
  },
) {
  const [cx, , cz] = center;
  const bottomX = bottomSize[0] / 2;
  const bottomZ = bottomSize[1] / 2;
  const topX = topSize[0] / 2;
  const topZ = topSize[1] / 2;

  const bottom = {
    nw: [cx - bottomX, yBottom, cz - bottomZ],
    ne: [cx + bottomX, yBottom, cz - bottomZ],
    se: [cx + bottomX, yBottom, cz + bottomZ],
    sw: [cx - bottomX, yBottom, cz + bottomZ],
  };
  const top = {
    nw: [cx - topX, yTop, cz - topZ],
    ne: [cx + topX, yTop, cz - topZ],
    se: [cx + topX, yTop, cz + topZ],
    sw: [cx - topX, yTop, cz + topZ],
  };

  addQuad(geometry, top.nw, top.sw, top.se, top.ne); // top (+Y)
  addQuad(geometry, bottom.sw, bottom.nw, bottom.ne, bottom.se); // bottom (-Y)
  addQuad(geometry, bottom.sw, bottom.se, top.se, top.sw); // front (+Z)
  addQuad(geometry, bottom.ne, bottom.nw, top.nw, top.ne); // rear (-Z)
  addQuad(geometry, bottom.se, bottom.ne, top.ne, top.se); // right (+X)
  addQuad(geometry, bottom.nw, bottom.sw, top.sw, top.nw); // left (-X)
}

function roundedRectanglePoints(halfX, halfZ, radius, segmentsPerCorner, y) {
  if (radius <= 0 || radius > Math.min(halfX, halfZ)) {
    throw new Error("Rounded rectangle radius must fit inside its half extents");
  }
  const corners = [
    { center: [halfX - radius, -halfZ + radius], start: 0 },
    { center: [-halfX + radius, -halfZ + radius], start: -Math.PI / 2 },
    { center: [-halfX + radius, halfZ - radius], start: -Math.PI },
    { center: [halfX - radius, halfZ - radius], start: (-3 * Math.PI) / 2 },
  ];
  return corners.flatMap(({ center, start }) =>
    Array.from({ length: segmentsPerCorner }, (_, segment) => {
      const angle = start - (segment * Math.PI) / (2 * segmentsPerCorner);
      return [
        center[0] + Math.cos(angle) * radius,
        y,
        center[1] + Math.sin(angle) * radius,
      ];
    }),
  );
}

function appendRing(geometry, specification, segmentsPerCorner) {
  const start = geometry.positions.length / 3;
  const points = roundedRectanglePoints(
    specification.halfX,
    specification.halfZ,
    specification.radius,
    segmentsPerCorner,
    specification.y,
  );
  for (const point of points) geometry.positions.push(...point);
  return { start, count: points.length };
}

function connectRings(geometry, lower, upper) {
  if (lower.count !== upper.count) throw new Error("Profile rings must have equal vertex counts");
  for (let index = 0; index < lower.count; index += 1) {
    const next = (index + 1) % lower.count;
    const a = lower.start + index;
    const b = lower.start + next;
    const c = upper.start + next;
    const d = upper.start + index;
    geometry.indices.push(a, b, c, a, c, d);
  }
}

function capRing(geometry, ring, y, upward) {
  const center = geometry.positions.length / 3;
  geometry.positions.push(0, y, 0);
  for (let index = 0; index < ring.count; index += 1) {
    const next = (index + 1) % ring.count;
    if (upward) {
      geometry.indices.push(ring.start + index, ring.start + next, center);
    } else {
      geometry.indices.push(center, ring.start + next, ring.start + index);
    }
  }
}

function finishProceduralGeometry(geometry) {
  geometry.normals = computeVertexNormals(geometry.positions, geometry.indices);
  return geometry;
}

function createKeycapGeometry() {
  // A restrained low-profile cap: rounded footprint, a short tapered wall,
  // a small chamfer, and a 0.55 mm spherical-looking dish. Dimensions are
  // visual proxies based on the reference photo, not manufacturing CAD.
  const segmentsPerCorner = 8;
  const profile = [
    { halfX: 0.0087, halfZ: 0.0083, radius: 0.001, y: 0 },
    { halfX: 0.00815, halfZ: 0.00775, radius: 0.0013, y: 0.00365 },
    { halfX: 0.00785, halfZ: 0.00745, radius: 0.0015, y: 0.0042 },
    { halfX: 0.00755, halfZ: 0.00715, radius: 0.0016, y: 0.0044 },
    { halfX: 0.00544, halfZ: 0.00515, radius: 0.00115, y: 0.00425 },
    { halfX: 0.00317, halfZ: 0.003, radius: 0.00067, y: 0.004 },
  ];
  const geometry = emptyGeometry();
  const rings = profile.map((specification) =>
    appendRing(geometry, specification, segmentsPerCorner),
  );
  for (let index = 0; index < rings.length - 1; index += 1) {
    connectRings(geometry, rings[index], rings[index + 1]);
  }
  capRing(geometry, rings.at(-1), 0.00385, true);

  // Duplicate the lower ring so the underside remains flat without averaging
  // its normals into the visible side wall.
  const bottom = appendRing(geometry, profile[0], segmentsPerCorner);
  capRing(geometry, bottom, 0, false);
  finishProceduralGeometry(geometry);
  // Planar UVs are sufficient for subtle, non-directional moulded-plastic
  // microdetail. They add less than 2 KiB because all 36 caps instance this
  // one mesh, and keep the case CAD free of unnecessary texture coordinates.
  geometry.uvs = [];
  for (let offset = 0; offset < geometry.positions.length; offset += 3) {
    geometry.uvs.push(
      (geometry.positions[offset] + 0.0087) / 0.0174,
      (geometry.positions[offset + 2] + 0.0083) / 0.0166,
    );
  }
  return geometry;
}

function createRoundedSlabGeometry(width, depth, height, radius = 0.0006) {
  const geometry = emptyGeometry();
  const specification = { halfX: width / 2, halfZ: depth / 2, radius };
  const bottom = appendRing(geometry, { ...specification, y: 0 }, 4);
  const top = appendRing(geometry, { ...specification, y: height }, 4);
  connectRings(geometry, bottom, top);
  // Separate cap rings keep the broad board/display faces optically flat;
  // sharing them with the walls would average the normals into visible fans.
  const topCap = appendRing(geometry, { ...specification, y: height }, 4);
  capRing(geometry, topCap, height, true);
  const bottomCap = appendRing(geometry, { ...specification, y: 0 }, 4);
  capRing(geometry, bottomCap, 0, false);
  return finishProceduralGeometry(geometry);
}

function threeGeometryData(geometry) {
  const position = geometry.getAttribute("position");
  if (!position) throw new Error("Three.js geometry has no position attribute");
  if (!geometry.getAttribute("normal")) geometry.computeVertexNormals();
  const normal = geometry.getAttribute("normal");
  const indices = geometry.index
    ? Array.from(geometry.index.array)
    : Array.from({ length: position.count }, (_, index) => index);
  const result = {
    positions: Array.from(position.array),
    normals: Array.from(normal.array),
    indices,
  };
  geometry.dispose();
  return result;
}

function createWireGeometry(points, radius = 0.00043) {
  const curve = new CatmullRomCurve3(
    points.map(([x, y, z]) => new Vector3(x, y, z)),
    false,
    "centripetal",
  );
  return threeGeometryData(new TubeGeometry(curve, 28, radius, 8, false));
}

function geometryBounds(geometry) {
  return componentMinMax(geometry.positions, 3);
}

function validateProceduralGeometry(name, geometry) {
  if (geometry.positions.length === 0 || geometry.positions.length % 3 !== 0) {
    throw new Error(`${name} has an invalid position buffer`);
  }
  if (geometry.normals.length !== geometry.positions.length) {
    throw new Error(`${name} has a mismatched normal buffer`);
  }
  if (geometry.indices.length === 0 || geometry.indices.length % 3 !== 0) {
    throw new Error(`${name} has an invalid triangle index buffer`);
  }
  if (geometry.uvs && geometry.uvs.length !== (geometry.positions.length / 3) * 2) {
    throw new Error(`${name} has a mismatched texture-coordinate buffer`);
  }
  if (![...geometry.positions, ...geometry.normals].every(Number.isFinite)) {
    throw new Error(`${name} contains a non-finite vertex component`);
  }
  for (let offset = 0; offset < geometry.indices.length; offset += 3) {
    const vertices = geometry.indices.slice(offset, offset + 3).map((index) =>
      geometry.positions.slice(index * 3, index * 3 + 3),
    );
    const areaVector = cross(
      subtractVector(vertices[1], vertices[0]),
      subtractVector(vertices[2], vertices[0]),
    );
    if (Math.hypot(...areaVector) < 1e-12) {
      throw new Error(`${name} contains a degenerate triangle at index ${offset / 3}`);
    }
  }
}

function validateKeycapProfile(geometry) {
  validateProceduralGeometry("Keycap", geometry);
  const bounds = geometryBounds(geometry);
  const dimensions = bounds.maximum.map((value, index) => value - bounds.minimum[index]);
  const expected = [0.0174, 0.0044, 0.0166];
  expected.forEach((value, index) => {
    if (Math.abs(dimensions[index] - value) > 1e-9) {
      throw new Error(`Keycap dimension ${index} is ${dimensions[index]}, expected ${value}`);
    }
  });
  const centerY = geometry.positions.reduce((result, value, offset) => {
    if (offset % 3 !== 1) return result;
    const x = geometry.positions[offset - 1];
    const z = geometry.positions[offset + 1];
    return Math.hypot(x, z) < 1e-9 ? Math.max(result, value) : result;
  }, -Infinity);
  const dishDepth = bounds.maximum[1] - centerY;
  if (dishDepth < 0.0005 || dishDepth > 0.0006) {
    throw new Error(`Keycap dish depth is ${(dishDepth * 1000).toFixed(3)} mm`);
  }
}

function componentAnchor(center, name) {
  const [x, y] = COMPONENT_ANCHORS_MM[name];
  return [
    (x - center.x) * METRES_PER_MILLIMETRE,
    (y + center.y) * METRES_PER_MILLIMETRE,
  ];
}

function addAssemblyMeshes(builder, namespace, assembly, materialByRole) {
  assertAssembly(assembly, namespace);
  const meshes = new Map();
  for (const part of assembly.parts) {
    const key = `${part.geometry}|${part.materialRole}`;
    if (!meshes.has(key)) {
      const material = materialByRole[part.materialRole];
      if (!Number.isInteger(material)) {
        throw new Error(
          `${namespace}.${part.geometry} has no glTF material for role ${part.materialRole}`,
        );
      }
      meshes.set(
        key,
        builder.addMesh(
          `${namespace}_${part.geometry}_${part.materialRole}`,
          assembly.geometries[part.geometry],
          material,
        ),
      );
    }
  }
  return meshes;
}

function translated(origin, offset) {
  return origin.map((value, axis) => value + offset[axis]);
}

function millimetres(values) {
  return values.map((value) => value / METRES_PER_MILLIMETRE);
}

function float32Buffer(values) {
  const result = Buffer.alloc(values.length * 4);
  values.forEach((value, index) => result.writeFloatLE(value, index * 4));
  return result;
}

function uint16Buffer(values) {
  const result = Buffer.alloc(values.length * 2);
  values.forEach((value, index) => result.writeUInt16LE(value, index * 2));
  return result;
}

function uint32Buffer(values) {
  const result = Buffer.alloc(values.length * 4);
  values.forEach((value, index) => result.writeUInt32LE(value, index * 4));
  return result;
}

function scalarMinMax(values) {
  let minimum = Infinity;
  let maximum = -Infinity;
  for (const value of values) {
    minimum = Math.min(minimum, value);
    maximum = Math.max(maximum, value);
  }
  return { minimum, maximum };
}

function componentMinMax(values, componentCount) {
  const minimum = Array(componentCount).fill(Number.POSITIVE_INFINITY);
  const maximum = Array(componentCount).fill(Number.NEGATIVE_INFINITY);

  for (let index = 0; index < values.length; index += componentCount) {
    for (let component = 0; component < componentCount; component += 1) {
      minimum[component] = Math.min(minimum[component], values[index + component]);
      maximum[component] = Math.max(maximum[component], values[index + component]);
    }
  }
  return { minimum, maximum };
}

function createGltfBuilder() {
  const binaryParts = [];
  let byteLength = 0;
  const bufferViews = [];
  const accessors = [];
  const meshes = [];

  function appendBuffer(buffer, target) {
    const paddingLength = (4 - (byteLength % 4)) % 4;
    if (paddingLength > 0) {
      binaryParts.push(Buffer.alloc(paddingLength));
      byteLength += paddingLength;
    }

    const bufferView = bufferViews.length;
    bufferViews.push({ buffer: 0, byteOffset: byteLength, byteLength: buffer.length, target });
    binaryParts.push(buffer);
    byteLength += buffer.length;
    return bufferView;
  }

  function addAccessor(definition) {
    const accessor = accessors.length;
    accessors.push(definition);
    return accessor;
  }

  function addMesh(name, geometry, material, extras) {
    const positions = float32Buffer(geometry.positions);
    const normals = float32Buffer(geometry.normals);
    const positionBounds = componentMinMax(geometry.positions, 3);
    const indexBounds = scalarMinMax(geometry.indices);
    const wideIndices = indexBounds.maximum > 65_535;
    const indices = wideIndices
      ? uint32Buffer(geometry.indices)
      : uint16Buffer(geometry.indices);

    const positionAccessor = addAccessor({
      bufferView: appendBuffer(positions, 34962),
      byteOffset: 0,
      componentType: 5126,
      count: geometry.positions.length / 3,
      type: "VEC3",
      min: positionBounds.minimum,
      max: positionBounds.maximum,
    });
    const normalAccessor = addAccessor({
      bufferView: appendBuffer(normals, 34962),
      byteOffset: 0,
      componentType: 5126,
      count: geometry.normals.length / 3,
      type: "VEC3",
    });
    const uvAccessor = geometry.uvs
      ? addAccessor({
          bufferView: appendBuffer(float32Buffer(geometry.uvs), 34962),
          byteOffset: 0,
          componentType: 5126,
          count: geometry.uvs.length / 2,
          type: "VEC2",
        })
      : null;
    const indexAccessor = addAccessor({
      bufferView: appendBuffer(indices, 34963),
      byteOffset: 0,
      componentType: wideIndices ? 5125 : 5123,
      count: geometry.indices.length,
      type: "SCALAR",
      min: [indexBounds.minimum],
      max: [indexBounds.maximum],
    });

    const mesh = meshes.length;
    meshes.push({
      name,
      ...(extras ? { extras } : {}),
      primitives: [
        {
          attributes: {
            POSITION: positionAccessor,
            NORMAL: normalAccessor,
            ...(uvAccessor === null ? {} : { TEXCOORD_0: uvAccessor }),
          },
          indices: indexAccessor,
          material,
          mode: 4,
        },
      ],
    });
    return mesh;
  }

  return {
    addMesh,
    finish() {
      const paddingLength = (4 - (byteLength % 4)) % 4;
      if (paddingLength > 0) {
        binaryParts.push(Buffer.alloc(paddingLength));
        byteLength += paddingLength;
      }
      return {
        binary: Buffer.concat(binaryParts, byteLength),
        bufferViews,
        accessors,
        meshes,
      };
    },
  };
}

function degreesToYQuaternion(degrees) {
  const radians = (degrees * Math.PI) / 180;
  return [0, Math.sin(radians / 2), 0, Math.cos(radians / 2)];
}

function keyMetadata(index) {
  if (index <= 4) return { hand: "left", row: 0, column: index };
  if (index <= 9) return { hand: "right", row: 0, column: index - 5 };
  if (index <= 14) return { hand: "left", row: 1, column: index - 10 };
  if (index <= 19) return { hand: "right", row: 1, column: index - 15 };
  if (index <= 24) return { hand: "left", row: 2, column: index - 20 };
  if (index <= 29) return { hand: "right", row: 2, column: index - 25 };
  if (index <= 32) return { hand: "left", row: 3, column: index - 30, thumb: true };
  return { hand: "right", row: 3, column: index - 33, thumb: true };
}

function keyTransform(index, switches, center) {
  const metadata = keyMetadata(index);
  const leftIndex = mirroredLeftIndex(index);
  const reference = LEFT_KEY_REFERENCES.get(leftIndex);
  const source = switches.get(reference);
  const rightHand = metadata.hand === "right";
  const leftX = (source.x - center.x) * METRES_PER_MILLIMETRE;
  const z = (source.y + center.y) * METRES_PER_MILLIMETRE;
  const leftRotation = normalizeKeyRotation(source.rotation);
  const rotation = rightHand ? -leftRotation : leftRotation;
  const transform = {
    translation: [rightHand ? -leftX : leftX, KEY_BASE_HEIGHT, z],
  };
  if (Math.abs(rotation) > 0.001) transform.rotation = degreesToYQuaternion(rotation);
  return { transform, reference };
}

function createModel() {
  const topSource = readStl(TOP_STL);
  const bottomSource = readStl(BOTTOM_STL);
  const renderMeshMetadata = readRenderMeshMetadata();
  const topStep = readRequiredFile(TOP_STEP, "pinned top STEP source");
  const bottomStep = readRequiredFile(BOTTOM_STEP, "pinned bottom STEP source");
  const { source: pcbSource, switches } = parseSwitchPositions(PCB_SOURCE);
  const sourceHashes = {
    topStep: sha256(topStep),
    bottomStep: sha256(bottomStep),
    pcb: sha256(Buffer.from(pcbSource, "utf8")),
  };
  for (const [name, expected] of Object.entries(EXPECTED_SOURCE_SHA256)) {
    if (sourceHashes[name] !== expected) {
      throw new Error(
        `Copied upstream ${name} source does not match pinned commit ${UPSTREAM_COMMIT}: ` +
          `expected ${expected}, got ${sourceHashes[name]}`,
      );
    }
  }
  for (const [name, source] of [["top", topSource], ["bottom", bottomSource]]) {
    const expectedHash = renderMeshMetadata.meshes[name].outputSha256;
    const actualHash = sha256(source.contents);
    if (actualHash !== expectedHash) {
      throw new Error(
        `${name}.stl does not match mesh-metadata.json; run the documented STEP tessellation task`,
      );
    }
    const triangles = source.positions.length / 9;
    if (triangles !== renderMeshMetadata.meshes[name].triangles) {
      throw new Error(`${name}.stl triangle count does not match mesh-metadata.json`);
    }
  }
  const caseTriangleCount = topSource.positions.length / 9 + bottomSource.positions.length / 9;
  if (caseTriangleCount > CASE_TRIANGLE_BUDGET) {
    throw new Error(
      `STEP render meshes contain ${caseTriangleCount} triangles; budget is ${CASE_TRIANGLE_BUDGET}`,
    );
  }
  const bounds = sourceBounds(topSource.positions, bottomSource.positions);
  const center = {
    x: (bounds.minimum[0] + bounds.maximum[0]) / 2,
    y: (bounds.minimum[1] + bounds.maximum[1]) / 2,
  };
  verifyKeyOpenings(topSource.positions, switches);

  const builder = createGltfBuilder();
  const material = {
    case: 0,
    keycap: 1,
    pcb: 2,
    screen: 3,
    keyswitch: 4,
    contact: 5,
    wireRed: 6,
    wireBlack: 7,
    connector: 8,
    metal: 9,
    amber: 10,
    component: 11,
    screw: 12,
  };
  const topCaseGeometry = weldStl(topSource.positions, center);
  const bottomCaseGeometry = weldStl(bottomSource.positions, center);
  const topCaseMesh = builder.addMesh(
    "chocofi_case_top",
    topCaseGeometry,
    material.case,
    topCaseGeometry.statistics,
  );
  const bottomCaseMesh = builder.addMesh(
    "chocofi_case_bottom",
    bottomCaseGeometry,
    material.case,
    bottomCaseGeometry.statistics,
  );
  const keycapGeometry = createKeycapGeometry();
  validateKeycapProfile(keycapGeometry);
  const keycapMesh = builder.addMesh(
    "blank_dished_choc_keycap",
    keycapGeometry,
    material.keycap,
    {
      widthMm: 17.4,
      depthMm: 16.6,
      heightMm: 4.4,
      dishDepthMm: 0.55,
      profile: "rounded-chamfered-concave-visual-proxy",
    },
  );
  const keyswitchMesh = builder.addMesh(
    "choc_switch_housing",
    createRoundedSlabGeometry(0.0149, 0.0149, 0.00115, 0.00072),
    material.keyswitch,
  );
  const materialByElectronicsRole = {
    pcb: material.pcb,
    screen: material.screen,
    "screen-bezel": material.metal,
    metal: material.metal,
    "metal-dark": material.screw,
    gold: material.contact,
    component: material.component,
    "component-marking": material.connector,
    "passive-dark": material.component,
    "passive-ceramic": material.connector,
    "led-blue": material.screen,
    "led-amber": material.amber,
    "connector-ivory": material.connector,
    "connector-shadow": material.keyswitch,
    "flex-amber": material.amber,
  };
  const nanoAssembly = createNiceNanoV2Assembly();
  const displayAssembly = createNiceViewAssembly();
  const batteryAssembly = createJstXhStyleAssembly();
  const nanoMeshes = addAssemblyMeshes(
    builder,
    "nice_nano_v2",
    nanoAssembly,
    materialByElectronicsRole,
  );
  const displayMeshes = addAssemblyMeshes(
    builder,
    "nice_view",
    displayAssembly,
    materialByElectronicsRole,
  );
  const batteryMeshes = addAssemblyMeshes(
    builder,
    "jst_xh_style",
    batteryAssembly,
    materialByElectronicsRole,
  );
  const powerSwitchMesh = builder.addMesh(
    "power_switch_body",
    createRoundedSlabGeometry(0.0062, 0.003, 0.0013, 0.00036),
    material.metal,
  );
  const powerSliderMesh = builder.addMesh(
    "power_switch_slider",
    createRoundedSlabGeometry(0.003, 0.0017, 0.00095, 0.00022),
    material.amber,
  );
  const resetSwitchMesh = builder.addMesh(
    "reset_switch",
    createRoundedSlabGeometry(0.0028, 0.0028, 0.00115, 0.00042),
    material.component,
  );
  const screwMesh = builder.addMesh(
    "case_screw_head",
    threeGeometryData(new CylinderGeometry(0.00135, 0.00135, 0.00052, 16, 1, false)),
    material.screw,
  );

  const [controllerX, controllerZ] = componentAnchor(center, "controller");
  const [batteryX, batteryZ] = componentAnchor(center, "batteryHeader");
  const [powerX, powerZ] = componentAnchor(center, "powerSwitch");
  const [resetX, resetZ] = componentAnchor(center, "resetSwitch");
  const batteryOrigin = [
    batteryX,
    ELECTRONICS_PLACEMENT.chocofiSceneY.jstHeaderBottom,
    batteryZ,
  ];
  const redWireStart = translated(batteryOrigin, batteryAssembly.wireExitOffsets.red);
  const blackWireStart = translated(batteryOrigin, batteryAssembly.wireExitOffsets.black);
  const redWireMesh = builder.addMesh(
    "battery_wire_red",
    createWireGeometry([
      redWireStart,
      [controllerX + 0.0093, 0.0091, batteryZ - 0.004],
      [controllerX + 0.0107, 0.00955, controllerZ - 0.005],
      [controllerX + 0.0104, 0.0097, controllerZ - 0.013],
      [controllerX + 0.0077, 0.00935, controllerZ - 0.017],
    ]),
    material.wireRed,
  );
  const blackWireMesh = builder.addMesh(
    "battery_wire_black",
    createWireGeometry([
      blackWireStart,
      [controllerX + 0.0082, 0.0087, batteryZ - 0.0025],
      [controllerX + 0.0097, 0.00915, controllerZ - 0.004],
      [controllerX + 0.0094, 0.0093, controllerZ - 0.012],
      [controllerX + 0.0067, 0.009, controllerZ - 0.017],
    ]),
    material.wireBlack,
  );
  const geometry = builder.finish();

  const nodes = [
    { name: "keyboard", children: [1, 2] },
    {
      name: "half_left",
      children: [3],
      translation: [-HALF_OFFSET, 0, 0.001],
      rotation: degreesToYQuaternion(HALF_ANGLE_DEGREES),
    },
    {
      name: "half_right",
      children: [4],
      translation: [HALF_OFFSET, 0, 0.001],
      rotation: degreesToYQuaternion(-HALF_ANGLE_DEGREES),
    },
    { name: "case_left", children: [] },
    { name: "case_right", children: [], scale: [-1, 1, 1] },
  ];

  function appendNode(parent, node) {
    const index = nodes.length;
    nodes.push(node);
    nodes[parent].children ??= [];
    nodes[parent].children.push(index);
    return index;
  }

  const screwPositions = [
    [-0.037589, -0.022224],
    [-0.037589, -0.005206],
    [0.016382, -0.019176],
    [-0.008637, 0.015114],
    [0.038734, 0.007875],
    [0.034416, 0.021337],
    [0.052831, 0.0174],
  ];

  function electronicsPartNodeName(namespace, part, suffix) {
    const exactNames = {
      "nano:usb_shell": `metal_usb_${suffix}`,
      "nano:usb_tongue": `accent_usb_${suffix}`,
      "nano:radio_module": `component_radio_${suffix}`,
      "display:screen": `display_screen_${suffix}`,
    };
    if (exactNames[`${namespace}:${part.name}`]) return exactNames[`${namespace}:${part.name}`];
    const rolePrefix = {
      pcb: "pcb",
      screen: "display_screen",
      "screen-bezel": "metal",
      metal: "metal",
      "metal-dark": "metal",
      gold: "contact",
      component: "component",
      "component-marking": "component_marking",
      "passive-dark": "component",
      "passive-ceramic": "component_ceramic",
      "led-blue": "accent_led_blue",
      "led-amber": "accent_led_amber",
      "connector-ivory": "connector",
      "connector-shadow": "component",
      "flex-amber": "accent",
    }[part.materialRole];
    return `${rolePrefix}_${namespace}_${part.name}_${suffix}`;
  }

  function appendElectronicsAssembly(root, namespace, suffix, assembly, meshes, options = {}) {
    const contactParent = options.contactParent;
    for (const part of assembly.parts) {
      const parent = contactParent && part.name.startsWith("pad_") ? contactParent : root;
      appendNode(parent, {
        name: electronicsPartNodeName(namespace, part, suffix),
        mesh: meshes.get(`${part.geometry}|${part.materialRole}`),
        translation: part.translation,
      });
    }
  }

  function appendHalfAssembly(parent, suffix) {
    appendNode(parent, { name: `case_top_${suffix}`, mesh: topCaseMesh });
    appendNode(parent, { name: `case_bottom_${suffix}`, mesh: bottomCaseMesh });
    // Reuse the real backplate silhouette for the depth-only shadow pass. The
    // scene controller recognizes this name, replaces its material, and keeps
    // the visible case meshes out of the expensive shadow traversal.
    appendNode(parent, {
      name: `shadow_caster_${suffix}`,
      mesh: bottomCaseMesh,
      extras: { pretypistShadowCaster: true },
    });
    const nanoRoot = appendNode(parent, {
      name: `pcb_controller_${suffix}`,
      children: [],
      translation: [
        controllerX,
        ELECTRONICS_PLACEMENT.chocofiSceneY.niceNanoBoardBottom,
        controllerZ,
      ],
    });
    const rails = appendNode(nanoRoot, { name: `contact_rails_${suffix}`, children: [] });
    appendElectronicsAssembly(nanoRoot, "nano", suffix, nanoAssembly, nanoMeshes, {
      contactParent: rails,
    });

    const displayRoot = appendNode(parent, {
      name: `display_${suffix}`,
      children: [],
      translation: [
        controllerX,
        ELECTRONICS_PLACEMENT.chocofiSceneY.niceViewBoardBottom,
        controllerZ,
      ],
    });
    appendElectronicsAssembly(
      displayRoot,
      "display",
      suffix,
      displayAssembly,
      displayMeshes,
    );

    appendNode(parent, {
      name: `wire_red_${suffix}`,
      mesh: redWireMesh,
    });
    appendNode(parent, {
      name: `wire_black_${suffix}`,
      mesh: blackWireMesh,
    });
    const batteryRoot = appendNode(parent, {
      name: `connector_battery_${suffix}`,
      children: [],
      translation: batteryOrigin,
    });
    appendElectronicsAssembly(
      batteryRoot,
      "battery",
      suffix,
      batteryAssembly,
      batteryMeshes,
    );
    appendNode(parent, {
      name: `metal_power_switch_${suffix}`,
      mesh: powerSwitchMesh,
      translation: [powerX, 0.00465, powerZ],
    });
    appendNode(parent, {
      name: `accent_power_slider_${suffix}`,
      mesh: powerSliderMesh,
      translation: [powerX + 0.0008, 0.00595, powerZ],
    });
    appendNode(parent, {
      name: `component_reset_${suffix}`,
      mesh: resetSwitchMesh,
      translation: [resetX, 0.0045, resetZ],
    });

    const screws = appendNode(parent, { name: `screws_${suffix}`, children: [] });
    screwPositions.forEach(([x, z], index) => {
      appendNode(screws, {
        name: `screw_${suffix}_${String(index).padStart(2, "0")}`,
        mesh: screwMesh,
        translation: [x, 0.00385, z],
      });
    });
  }

  appendHalfAssembly(3, "left");
  appendHalfAssembly(4, "right");

  for (let index = 0; index < KEY_COUNT; index += 1) {
    const metadata = keyMetadata(index);
    const { transform, reference } = keyTransform(index, switches, center);
    const half = metadata.hand === "left" ? 1 : 2;
    appendNode(half, {
      name: `keyswitch_${String(index).padStart(2, "0")}`,
      mesh: keyswitchMesh,
      ...transform,
      translation: [transform.translation[0], SWITCH_BASE_HEIGHT, transform.translation[2]],
    });
    appendNode(half, {
      name: `key_${String(index).padStart(2, "0")}`,
      mesh: keycapMesh,
      ...transform,
      extras: {
        pretypistKeyIndex: index,
        hand: metadata.hand,
        row: metadata.row,
        column: metadata.column,
        sourceSwitch: reference,
      },
    });
  }

  const gltf = {
    asset: {
      version: "2.0",
      generator: "PRETYPIST deterministic Chocofi STEP render assembler v4",
      extras: {
        units: "metres",
        upAxis: "Y",
        description:
          "Chocofi v1 STEP-derived case with 36 dished blank Choc keycaps and mirrored wireless electronics",
        sourceRepository: "https://github.com/pashutk/chocofi",
        sourceCommit: UPSTREAM_COMMIT,
        sourceLicense: "CERN-OHL-P-2.0",
        sourceTopStepSha256: sourceHashes.topStep,
        sourceBottomStepSha256: sourceHashes.bottomStep,
        sourcePcbSha256: sourceHashes.pcb,
        renderMeshGenerator: renderMeshMetadata.generator,
        renderMeshLinearDeflectionMillimetres: renderMeshMetadata.linearDeflectionMm,
        renderMeshAngularDeflectionDegrees: renderMeshMetadata.angularDeflectionDegrees,
        renderMeshTopSha256: renderMeshMetadata.meshes.top.outputSha256,
        renderMeshBottomSha256: renderMeshMetadata.meshes.bottom.outputSha256,
        renderMeshCaseTriangles: caseTriangleCount,
        renderMeshBoundaryEdges: {
          top: topCaseGeometry.statistics.boundaryEdges,
          bottom: bottomCaseGeometry.statistics.boundaryEdges,
        },
        renderMeshNonManifoldEdges: {
          top: topCaseGeometry.statistics.nonManifoldEdges,
          bottom: bottomCaseGeometry.statistics.nonManifoldEdges,
        },
        normalCreaseAngleDegrees: CASE_CREASE_ANGLE_DEGREES,
        sourceBoundsMillimetres: {
          minimum: bounds.minimum,
          maximum: bounds.maximum,
        },
        visualProxyDimensionsMillimetres: {
          keycap: [17.4, 4.4, 16.6],
          keycapDishDepth: 0.55,
          controllerBoard: millimetres([
            NICE_NANO_V2_DIMENSIONS.board.width,
            NICE_NANO_V2_DIMENSIONS.board.thickness,
            NICE_NANO_V2_DIMENSIONS.board.depth,
          ]),
          controllerUsbC: millimetres([
            NICE_NANO_V2_DIMENSIONS.usbC.width,
            NICE_NANO_V2_DIMENSIONS.usbC.height,
            NICE_NANO_V2_DIMENSIONS.usbC.depth,
          ]),
          displayBoard: millimetres([
            NICE_VIEW_DIMENSIONS.board.width,
            NICE_VIEW_DIMENSIONS.board.thickness,
            NICE_VIEW_DIMENSIONS.board.depth,
          ]),
          displayActiveSurface: millimetres([
            NICE_VIEW_DIMENSIONS.activeSurface.width,
            NICE_VIEW_DIMENSIONS.activeSurface.thickness,
            NICE_VIEW_DIMENSIONS.activeSurface.depth,
          ]),
          batteryHeaderNominal: millimetres(
            JST_XH_STYLE_PLACEMENT.headerBody.nominalDimensions,
          ),
          batteryMatedPlug: millimetres(JST_XH_STYLE_PLACEMENT.matedPlug.dimensions),
          insulatedWireDiameter: 0.86,
        },
        componentAnchorsMillimetres: COMPONENT_ANCHORS_MM,
      },
    },
    scene: 0,
    scenes: [{ name: "PRETYPIST", nodes: [0] }],
    nodes,
    meshes: geometry.meshes,
    materials: [
      {
        name: "case_charcoal",
        pbrMetallicRoughness: {
          baseColorFactor: [0.0144, 0.0152, 0.0194, 1],
          metallicFactor: 0,
          roughnessFactor: 0.84,
        },
      },
      {
        name: "blank_keycap_charcoal",
        pbrMetallicRoughness: {
          baseColorFactor: [0.0185, 0.0194, 0.0232, 1],
          metallicFactor: 0,
          roughnessFactor: 0.68,
        },
      },
      {
        name: "wireless_pcb_black",
        pbrMetallicRoughness: {
          baseColorFactor: [0.0056, 0.0185, 0.0137, 1],
          metallicFactor: 0,
          roughnessFactor: 0.74,
        },
      },
      {
        name: "nice_view_smoked_glass",
        emissiveFactor: [0.012, 0.016, 0.019],
        pbrMetallicRoughness: {
          baseColorFactor: [0.065, 0.074, 0.078, 1],
          metallicFactor: 0.08,
          roughnessFactor: 0.3,
        },
      },
      {
        name: "choc_switch_black",
        pbrMetallicRoughness: {
          baseColorFactor: [0.0086, 0.0091, 0.011, 1],
          metallicFactor: 0,
          roughnessFactor: 0.72,
        },
      },
      {
        name: "plated_contact_gold",
        pbrMetallicRoughness: {
          baseColorFactor: [0.55, 0.38, 0.12, 1],
          metallicFactor: 0.82,
          roughnessFactor: 0.28,
        },
      },
      {
        name: "insulated_wire_red",
        pbrMetallicRoughness: {
          baseColorFactor: [0.36, 0.025, 0.028, 1],
          metallicFactor: 0,
          roughnessFactor: 0.52,
        },
      },
      {
        name: "insulated_wire_black",
        pbrMetallicRoughness: {
          baseColorFactor: [0.0056, 0.006, 0.008, 1],
          metallicFactor: 0,
          roughnessFactor: 0.62,
        },
      },
      {
        name: "connector_ivory",
        pbrMetallicRoughness: {
          baseColorFactor: [0.402, 0.381, 0.328, 1],
          metallicFactor: 0,
          roughnessFactor: 0.76,
        },
      },
      {
        name: "connector_metal",
        pbrMetallicRoughness: {
          baseColorFactor: [0.184, 0.195, 0.198, 1],
          metallicFactor: 0.72,
          roughnessFactor: 0.4,
        },
      },
      {
        name: "connector_amber",
        pbrMetallicRoughness: {
          baseColorFactor: [0.55, 0.24, 0.035, 1],
          metallicFactor: 0.08,
          roughnessFactor: 0.44,
        },
      },
      {
        name: "controller_component_dark",
        pbrMetallicRoughness: {
          baseColorFactor: [0.022, 0.024, 0.028, 1],
          metallicFactor: 0.02,
          roughnessFactor: 0.58,
        },
      },
      {
        name: "case_screw_black",
        pbrMetallicRoughness: {
          baseColorFactor: [0.025, 0.026, 0.027, 1],
          metallicFactor: 0.62,
          roughnessFactor: 0.42,
        },
      },
    ],
    accessors: geometry.accessors,
    bufferViews: geometry.bufferViews,
    buffers: [{ byteLength: geometry.binary.length }],
  };

  return encodeGlb(gltf, geometry.binary);
}

function padBuffer(buffer, fillByte) {
  const paddingLength = (4 - (buffer.length % 4)) % 4;
  return paddingLength === 0
    ? buffer
    : Buffer.concat([buffer, Buffer.alloc(paddingLength, fillByte)]);
}

function encodeGlb(gltf, binary) {
  const json = padBuffer(Buffer.from(JSON.stringify(gltf), "utf8"), 0x20);
  const binaryChunk = padBuffer(binary, 0);
  const totalLength = 12 + 8 + json.length + 8 + binaryChunk.length;
  const output = Buffer.alloc(totalLength);

  output.writeUInt32LE(GLB_MAGIC, 0);
  output.writeUInt32LE(GLB_VERSION, 4);
  output.writeUInt32LE(totalLength, 8);
  output.writeUInt32LE(json.length, 12);
  output.writeUInt32LE(JSON_CHUNK_TYPE, 16);
  json.copy(output, 20);
  const binaryHeader = 20 + json.length;
  output.writeUInt32LE(binaryChunk.length, binaryHeader);
  output.writeUInt32LE(BIN_CHUNK_TYPE, binaryHeader + 4);
  binaryChunk.copy(output, binaryHeader + 8);
  return output;
}

function decodeJsonChunk(glb) {
  if (glb.length < 20) throw new Error("GLB is shorter than its header");
  if (glb.readUInt32LE(0) !== GLB_MAGIC) throw new Error("Invalid GLB magic");
  if (glb.readUInt32LE(4) !== GLB_VERSION) throw new Error("GLB is not version 2");
  if (glb.readUInt32LE(8) !== glb.length) throw new Error("GLB length header is incorrect");
  const jsonLength = glb.readUInt32LE(12);
  if (glb.readUInt32LE(16) !== JSON_CHUNK_TYPE) throw new Error("First GLB chunk is not JSON");
  const jsonEnd = 20 + jsonLength;
  if (jsonEnd > glb.length) throw new Error("GLB JSON chunk is truncated");
  if (jsonEnd + 8 > glb.length) throw new Error("GLB binary chunk is missing");
  const binaryLength = glb.readUInt32LE(jsonEnd);
  if (glb.readUInt32LE(jsonEnd + 4) !== BIN_CHUNK_TYPE) {
    throw new Error("Second GLB chunk is not binary data");
  }
  if (jsonEnd + 8 + binaryLength !== glb.length) {
    throw new Error("GLB binary chunk length is incorrect");
  }
  return JSON.parse(glb.subarray(20, jsonEnd).toString("utf8").trim());
}

function validateModel(glb) {
  const gltf = decodeJsonChunk(glb);
  if (gltf.asset?.version !== "2.0") throw new Error("GLB JSON does not declare glTF 2.0");
  if (!Array.isArray(gltf.nodes)) throw new Error("GLB does not contain a nodes array");
  if (!Array.isArray(gltf.meshes)) throw new Error("GLB does not contain a meshes array");
  if (!Array.isArray(gltf.scenes) || !Number.isInteger(gltf.scene)) {
    throw new Error("GLB does not declare a default scene");
  }
  if (!gltf.scenes[gltf.scene]) throw new Error("GLB default scene index is invalid");

  const names = gltf.nodes.map((node) => node.name);
  const assemblyNodes = ["left", "right"].flatMap((suffix) => [
    `case_top_${suffix}`,
    `case_bottom_${suffix}`,
    `shadow_caster_${suffix}`,
    `pcb_controller_${suffix}`,
    `pcb_nano_board_${suffix}`,
    `display_${suffix}`,
    `pcb_display_board_${suffix}`,
    `display_screen_${suffix}`,
    `wire_red_${suffix}`,
    `wire_black_${suffix}`,
    `connector_battery_${suffix}`,
    `connector_battery_board_header_${suffix}`,
    `connector_battery_mated_plug_${suffix}`,
    `metal_usb_${suffix}`,
    `accent_usb_${suffix}`,
    `metal_power_switch_${suffix}`,
    `accent_power_slider_${suffix}`,
    `component_reset_${suffix}`,
    `component_radio_${suffix}`,
    `contact_rails_${suffix}`,
    `screws_${suffix}`,
  ]);
  const required = [
    "keyboard",
    "half_left",
    "half_right",
    "case_left",
    "case_right",
    ...assemblyNodes,
  ];
  for (let index = 0; index < KEY_COUNT; index += 1) {
    required.push(`key_${String(index).padStart(2, "0")}`);
    required.push(`keyswitch_${String(index).padStart(2, "0")}`);
  }

  const missing = required.filter((name) => !names.includes(name));
  if (missing.length > 0) throw new Error(`Generated GLB is missing nodes: ${missing.join(", ")}`);
  const duplicateNames = names.filter(
    (name, index) => typeof name === "string" && name.length > 0 && names.indexOf(name) !== index,
  );
  if (duplicateNames.length > 0) {
    throw new Error(`Generated GLB has duplicate node names: ${[...new Set(duplicateNames)].join(", ")}`);
  }
  const keyNodes = names.filter((name) => typeof name === "string" && name.startsWith("key_"));
  if (keyNodes.length !== KEY_COUNT) {
    throw new Error(`Expected ${KEY_COUNT} key nodes, found ${keyNodes.length}`);
  }
  const unexpectedKeyNames = keyNodes.filter((name) => !/^key_(?:0\d|[12]\d|3[0-5])$/.test(name));
  if (unexpectedKeyNames.length > 0) {
    throw new Error(`Generated GLB has unexpected key node names: ${unexpectedKeyNames.join(", ")}`);
  }
  const switchNodes = names.filter(
    (name) => typeof name === "string" && /^keyswitch_(?:0\d|[12]\d|3[0-5])$/.test(name),
  );
  if (switchNodes.length !== KEY_COUNT) {
    throw new Error(`Expected ${KEY_COUNT} stationary switch housings, found ${switchNodes.length}`);
  }

  const nameToIndex = new Map(names.map((name, index) => [name, index]));
  for (const [index, node] of gltf.nodes.entries()) {
    for (const child of node.children ?? []) {
      if (!Number.isInteger(child) || child < 0 || child >= gltf.nodes.length) {
        throw new Error(`Node ${node.name ?? index} has an invalid child index: ${child}`);
      }
    }
    if ("mesh" in node && (!Number.isInteger(node.mesh) || !gltf.meshes[node.mesh])) {
      throw new Error(`Node ${node.name ?? index} has an invalid mesh index`);
    }
  }

  function descendants(rootIndex) {
    const result = new Set();
    const visiting = new Set();
    function visit(index) {
      if (visiting.has(index)) throw new Error(`GLB node hierarchy contains a cycle at ${names[index]}`);
      if (result.has(index)) return;
      visiting.add(index);
      result.add(index);
      for (const child of gltf.nodes[index].children ?? []) visit(child);
      visiting.delete(index);
    }
    visit(rootIndex);
    return result;
  }

  function nodeIsRenderable(nodeIndex) {
    return [...descendants(nodeIndex)].some((index) => {
      const meshIndex = gltf.nodes[index].mesh;
      const mesh = Number.isInteger(meshIndex) ? gltf.meshes[meshIndex] : undefined;
      return (
        Array.isArray(mesh?.primitives) &&
        mesh.primitives.some((primitive) => {
          const positionAccessor = primitive?.attributes?.POSITION;
          return Number.isInteger(positionAccessor) && gltf.accessors?.[positionAccessor];
        })
      );
    });
  }

  const sceneRoots = gltf.scenes[gltf.scene].nodes ?? [];
  for (const root of sceneRoots) {
    if (!Number.isInteger(root) || root < 0 || root >= gltf.nodes.length) {
      throw new Error(`Default scene has an invalid root node index: ${root}`);
    }
  }
  const sceneNodes = new Set(sceneRoots.flatMap((root) => [...descendants(root)]));
  for (const name of required) {
    if (!sceneNodes.has(nameToIndex.get(name))) {
      throw new Error(`${name} is not reachable from the default GLB scene`);
    }
  }
  const keyboardNodes = descendants(nameToIndex.get("keyboard"));
  for (const name of required.slice(1)) {
    if (!keyboardNodes.has(nameToIndex.get(name))) {
      throw new Error(`${name} is not contained by the keyboard root node`);
    }
  }

  for (let index = 0; index < KEY_COUNT; index += 1) {
    const keyName = `key_${String(index).padStart(2, "0")}`;
    if (!nodeIsRenderable(nameToIndex.get(keyName))) {
      throw new Error(`${keyName} does not contain a renderable mesh primitive`);
    }
    const switchName = `keyswitch_${String(index).padStart(2, "0")}`;
    if (!nodeIsRenderable(nameToIndex.get(switchName))) {
      throw new Error(`${switchName} does not contain a renderable mesh primitive`);
    }
  }
  for (const caseName of ["case_left", "case_right"]) {
    if (!nodeIsRenderable(nameToIndex.get(caseName))) {
      throw new Error(`${caseName} does not contain a renderable mesh primitive`);
    }
  }
  for (const name of assemblyNodes) {
    if (!nodeIsRenderable(nameToIndex.get(name))) {
      throw new Error(`${name} does not contain a renderable mesh primitive`);
    }
  }

  const keycapMesh = gltf.meshes.find((mesh) => mesh.name === "blank_dished_choc_keycap");
  const keycapProfile = keycapMesh?.extras;
  if (
    keycapProfile?.widthMm !== 17.4 ||
    keycapProfile?.depthMm !== 16.6 ||
    keycapProfile?.heightMm !== 4.4 ||
    keycapProfile?.dishDepthMm !== 0.55
  ) {
    throw new Error("Generated keycap mesh does not declare the expected 17.4 × 16.6 × 4.4 mm profile");
  }

  const redWire = gltf.nodes[nameToIndex.get("wire_red_left")];
  const blackWire = gltf.nodes[nameToIndex.get("wire_black_left")];
  const redMaterial = gltf.meshes[redWire.mesh]?.primitives?.[0]?.material;
  const blackMaterial = gltf.meshes[blackWire.mesh]?.primitives?.[0]?.material;
  if (!Number.isInteger(redMaterial) || !Number.isInteger(blackMaterial) || redMaterial === blackMaterial) {
    throw new Error("Red and black wire meshes must retain distinct materials");
  }

  const rightCase = gltf.nodes[nameToIndex.get("case_right")];
  if (JSON.stringify(rightCase.scale) !== JSON.stringify([-1, 1, 1])) {
    throw new Error("case_right must mirror the complete source assembly across local X");
  }
  for (let rightIndex = 0; rightIndex < KEY_COUNT; rightIndex += 1) {
    if (keyMetadata(rightIndex).hand !== "right") continue;
    const leftIndex = mirroredLeftIndex(rightIndex);
    const left = gltf.nodes[nameToIndex.get(`key_${String(leftIndex).padStart(2, "0")}`)];
    const right = gltf.nodes[nameToIndex.get(`key_${String(rightIndex).padStart(2, "0")}`)];
    const mirroredTranslation = [-left.translation[0], left.translation[1], left.translation[2]];
    if (right.translation.some((value, component) => value !== mirroredTranslation[component])) {
      throw new Error(`key_${rightIndex} is not an exact positional mirror of key_${leftIndex}`);
    }
    if (left.extras?.sourceSwitch !== right.extras?.sourceSwitch) {
      throw new Error(`key_${rightIndex} does not retain key_${leftIndex}'s PCB switch reference`);
    }
  }

  const provenance = gltf.asset?.extras;
  if (provenance?.sourceCommit !== UPSTREAM_COMMIT) {
    throw new Error("Generated GLB does not identify the pinned upstream Chocofi commit");
  }
  if (
    provenance.sourceTopStepSha256 !== EXPECTED_SOURCE_SHA256.topStep ||
    provenance.sourceBottomStepSha256 !== EXPECTED_SOURCE_SHA256.bottomStep ||
    provenance.sourcePcbSha256 !== EXPECTED_SOURCE_SHA256.pcb
  ) {
    throw new Error("Generated GLB source hashes do not match the pinned upstream files");
  }
  if (
    provenance.renderMeshCaseTriangles > CASE_TRIANGLE_BUDGET ||
    provenance.renderMeshBoundaryEdges?.top > CASE_BOUNDARY_EDGE_BUDGET ||
    provenance.renderMeshBoundaryEdges?.bottom > CASE_BOUNDARY_EDGE_BUDGET ||
    provenance.renderMeshNonManifoldEdges?.top !== 0 ||
    provenance.renderMeshNonManifoldEdges?.bottom !== 0 ||
    provenance.normalCreaseAngleDegrees !== CASE_CREASE_ANGLE_DEGREES
  ) {
    throw new Error("Generated GLB does not meet the case mesh or crease-normal contract");
  }

  if (!Array.isArray(gltf.buffers) || gltf.buffers.length !== 1) {
    throw new Error("Generated GLB must contain exactly one embedded binary buffer");
  }
  if (gltf.buffers.some((buffer) => "uri" in buffer)) {
    throw new Error("Generated GLB contains an external buffer URI");
  }
  if ((gltf.images ?? []).some((image) => "uri" in image)) {
    throw new Error("Generated GLB contains an external image URI");
  }
  return gltf;
}

const options = parseArguments(process.argv.slice(2));
const model = options.checkOnly ? fs.readFileSync(options.output) : createModel();
const gltf = validateModel(model);

if (!options.checkOnly) {
  fs.mkdirSync(path.dirname(options.output), { recursive: true });
  fs.writeFileSync(options.output, model);
}

process.stdout.write(
  `${options.checkOnly ? "Validated" : "Generated"} ${path.relative(PROJECT_ROOT, options.output)} ` +
    `(${model.length} bytes, ${gltf.nodes.filter((node) => /^key_\d\d$/.test(node.name)).length} keys)\n`,
);
