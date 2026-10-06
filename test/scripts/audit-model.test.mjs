import assert from "node:assert/strict";
import test from "node:test";

import {
  DEFAULT_EXPECTED_METADATA,
  auditGlb,
  parseGlb,
} from "../../scripts/audit-model.mjs";

const JSON_CHUNK_TYPE = 0x4e4f534a;
const BIN_CHUNK_TYPE = 0x004e4942;

function pad(buffer, fill) {
  const padding = (4 - (buffer.length % 4)) % 4;
  return padding === 0 ? buffer : Buffer.concat([buffer, Buffer.alloc(padding, fill)]);
}

function floats(values) {
  const buffer = Buffer.alloc(values.length * 4);
  values.forEach((value, index) => buffer.writeFloatLE(value, index * 4));
  return buffer;
}

function uint16s(values) {
  const buffer = Buffer.alloc(values.length * 2);
  values.forEach((value, index) => buffer.writeUInt16LE(value, index * 2));
  return buffer;
}

function encodeGlb(gltf, binary) {
  const json = pad(Buffer.from(JSON.stringify(gltf), "utf8"), 0x20);
  const bin = pad(binary, 0);
  const result = Buffer.alloc(12 + 8 + json.length + 8 + bin.length);
  result.writeUInt32LE(0x46546c67, 0);
  result.writeUInt32LE(2, 4);
  result.writeUInt32LE(result.length, 8);
  result.writeUInt32LE(json.length, 12);
  result.writeUInt32LE(JSON_CHUNK_TYPE, 16);
  json.copy(result, 20);
  const binaryHeader = 20 + json.length;
  result.writeUInt32LE(bin.length, binaryHeader);
  result.writeUInt32LE(BIN_CHUNK_TYPE, binaryHeader + 4);
  bin.copy(result, binaryHeader + 8);
  return result;
}

function modelFixture({
  positions = [0, 0, 0, 1, 0, 0, 0.5, 0, Math.sqrt(3) / 2],
  indices = [0, 1, 2],
  positionViewLength,
  assetExtras,
} = {}) {
  const positionBuffer = floats(positions);
  const indexBuffer = uint16s(indices);
  const indexOffset = positionBuffer.length;
  const binary = Buffer.concat([positionBuffer, indexBuffer]);
  const minimum = [0, 1, 2].map((component) =>
    Math.min(...positions.filter((_, index) => index % 3 === component)),
  );
  const maximum = [0, 1, 2].map((component) =>
    Math.max(...positions.filter((_, index) => index % 3 === component)),
  );
  return encodeGlb(
    {
      asset: { version: "2.0", ...(assetExtras ? { extras: assetExtras } : {}) },
      scene: 0,
      scenes: [{ nodes: [0, 1] }],
      nodes: [
        { name: "case_left", mesh: 0 },
        { name: "case_right", mesh: 0 },
      ],
      meshes: [
        {
          name: "case_shell",
          primitives: [{ attributes: { POSITION: 0 }, indices: 1, mode: 4 }],
        },
      ],
      accessors: [
        {
          bufferView: 0,
          componentType: 5126,
          count: positions.length / 3,
          type: "VEC3",
          min: minimum,
          max: maximum,
        },
        { bufferView: 1, componentType: 5123, count: indices.length, type: "SCALAR" },
      ],
      bufferViews: [
        { buffer: 0, byteOffset: 0, byteLength: positionViewLength ?? positionBuffer.length, target: 34962 },
        { buffer: 0, byteOffset: indexOffset, byteLength: indexBuffer.length, target: 34963 },
      ],
      buffers: [{ byteLength: binary.length }],
    },
    binary,
  );
}

const AUDIT_OPTIONS = {
  requiredNodes: [],
  expectedMetadata: [],
  limits: {
    caseEffectiveTriangles: 2,
    degenerateTriangles: 0,
    aspectRatio: 10,
    skinnyTriangles: 48,
  },
};

test("counts stored mesh triangles separately from effective node instances", () => {
  const model = modelFixture();
  const parsed = parseGlb(model);
  assert.equal(parsed.gltf.asset.version, "2.0");
  assert.equal(parsed.binaryChunkCount, 1);

  const report = auditGlb(model, AUDIT_OPTIONS);
  assert.equal(report.structure.nodeCount, 2);
  assert.equal(report.structure.meshCount, 1);
  assert.equal(report.structure.primitiveCount, 1);
  assert.deepEqual(report.accessors, { valid: 2, invalid: 0 });
  assert.equal(report.triangles.stored, 1);
  assert.equal(report.triangles.effective, 2);
  assert.equal(report.triangles.caseStored, 1);
  assert.equal(report.triangles.caseEffective, 2);
  assert.equal(report.triangles.degeneratesStored, 0);
  assert.ok(Math.abs(report.triangles.aspect.max - 2 / Math.sqrt(3)) < 1e-6);
  assert.equal(report.ok, true);
});

test("allows skinny triangles up to the configured count budget", () => {
  const report = auditGlb(modelFixture(), {
    ...AUDIT_OPTIONS,
    limits: {
      ...AUDIT_OPTIONS.limits,
      aspectRatio: 1,
      skinnyTriangles: 1,
    },
  });
  assert.equal(report.triangles.aboveAspectLimitStored, 1);
  assert.equal(report.triangles.skinnyTriangleLimit, 1);
  assert.equal(report.findings.some((finding) => finding.code === "quality.aspect"), false);
  assert.equal(report.ok, true);
});

test("reports degenerates, skinny triangles, and case-budget violations as enforceable findings", () => {
  const report = auditGlb(modelFixture({ indices: [0, 1, 2, 0, 0, 1] }), {
    ...AUDIT_OPTIONS,
    limits: {
      caseEffectiveTriangles: 1,
      degenerateTriangles: 0,
      aspectRatio: 1,
      skinnyTriangles: 0,
    },
  });
  const codes = report.findings.map((finding) => finding.code);
  assert.ok(codes.includes("budget.case-triangles"));
  assert.ok(codes.includes("quality.degenerate"));
  assert.ok(codes.includes("quality.aspect"));
  assert.equal(report.triangles.degeneratesStored, 1);
  assert.equal(report.triangles.degeneratesEffective, 2);
  assert.equal(report.ok, false);
});

test("detects an accessor that extends beyond its bufferView", () => {
  const report = auditGlb(modelFixture({ positionViewLength: 8 }), AUDIT_OPTIONS);
  assert.equal(report.accessors.invalid, 1);
  assert.ok(report.findings.some((finding) => finding.code === "accessor.range"));
  assert.equal(report.ok, false);
});

test("cross-checks declared render-mesh case triangles against GLB geometry", () => {
  const report = auditGlb(
    modelFixture({ assetExtras: { renderMeshCaseTriangles: 2 } }),
    AUDIT_OPTIONS,
  );
  assert.ok(report.findings.some((finding) => finding.code === "metadata.case-triangles"));
  assert.equal(report.ok, false);
});

test("enforces the recorded STEP topology boundary", () => {
  const topologyMetadata = DEFAULT_EXPECTED_METADATA.filter(({ path }) =>
    ["renderMeshBoundaryEdges", "renderMeshNonManifoldEdges"].includes(path.at(-1)),
  );
  const report = auditGlb(
    modelFixture({
      assetExtras: {
        renderMeshBoundaryEdges: { top: 41, bottom: 35 },
        renderMeshNonManifoldEdges: { top: 0, bottom: 1 },
      },
    }),
    { ...AUDIT_OPTIONS, expectedMetadata: topologyMetadata },
  );

  assert.equal(report.metadata.expected, 2);
  assert.equal(report.metadata.valid, 0);
  assert.equal(report.metadata.checks.filter((check) => !check.valid).length, 2);
  assert.equal(
    report.findings.filter((finding) => finding.code === "metadata.expected").length,
    2,
  );
  assert.equal(report.ok, false);
});

test("rejects a malformed GLB container before attempting geometry analysis", () => {
  assert.throws(() => parseGlb(Buffer.alloc(20)), /Invalid GLB magic/u);
});
