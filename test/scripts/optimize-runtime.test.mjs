import assert from "node:assert/strict";
import test from "node:test";
import fs from "node:fs";
import { parseGlb } from "../../scripts/audit-model.mjs";
import { adaptModel, prepareRuntimeModel, sourcePath, sha256, sourceSha256 } from "../../scripts/adapt-authored-model.mjs";
import { inspectTopology } from "../../scripts/model/optimize-runtime.mjs";
import { readArray } from "../../scripts/model/refine-surfaces.mjs";
import { MeshoptSimplifier } from "three/addons/libs/meshopt_simplifier.module.js";

const source = fs.readFileSync(sourcePath), full = adaptModel(source), compact = prepareRuntimeModel(source);
const before = parseGlb(full.output), after = parseGlb(compact.output);
const isCase = n => /^case_(top|bottom)_(left|right)$/.test(n.name);
const optimized = new Map(compact.report.optimization.meshes.map(m => [m.node, m]));
const array = (model, index) => readArray(model.gltf, model.binary, index);
const primitive = (model, node) => model.gltf.meshes[node.mesh].primitives[0];

function topology(model, p) {
  const positions = array(model, p.attributes.POSITION);
  // Weld positional duplicates used for UV seams and sharp shading creases.
  const remap = MeshoptSimplifier.generatePositionRemap(positions, 3);
  return inspectTopology(new Uint32Array(Array.from(array(model, p.indices), i => remap[i])), positions.length / 3).stats;
}

test("runtime model is deterministic, smaller and audited without altering the authoring source", () => {
  assert.equal(sha256(source), sourceSha256);
  assert.equal(compact.audit.ok, true);
  assert.equal(compact.audit.triangles.degeneratesEffective, 0);
  assert.ok(compact.output.length < 1.5 * 1024 * 1024);
  assert.ok(compact.audit.triangles.effective <= 50000);
  assert.equal(compact.report.optimization.effectiveTriangles, compact.audit.triangles.effective);
  assert.ok(prepareRuntimeModel(source).output.equals(compact.output));
  assert.equal(compact.report.optimization.cases.length, 4);
  for (const mesh of optimized.values()) assert.ok(mesh.reportedErrorMillimetres <= mesh.errorTargetMillimetres, mesh.node);
  assert.equal(sha256(fs.readFileSync(sourcePath)), sourceSha256);
});

test("every node and physical pivot is preserved; hardware, covers and small parts stay exact", () => {
  assert.deepEqual(after.gltf.nodes, before.gltf.nodes);
  assert.deepEqual(after.gltf.scenes, before.gltf.scenes);
  for (const node of before.gltf.nodes.filter(n => n.mesh !== undefined && !optimized.has(n.name))) {
    const original = before.gltf.meshes[node.mesh].primitives, output = after.gltf.meshes[node.mesh].primitives;
    assert.equal(output.length, original.length);
    for (let i = 0; i < original.length; i++) {
      const a = original[i], b = output[i];
      assert.deepEqual(Object.keys(a.attributes), Object.keys(b.attributes), node.name);
      for (const name of Object.keys(a.attributes)) {
        assert.deepEqual(Array.from(readArray(after.gltf, after.binary, b.attributes[name])),
          Array.from(readArray(before.gltf, before.binary, a.attributes[name])), `${node.name}/${name}`);
      }
      assert.deepEqual(Array.from(readArray(after.gltf, after.binary, b.indices)),
        Array.from(readArray(before.gltf, before.binary, a.indices)), node.name);
    }
  }
});

test("all retained vertex attributes are exact source values, with exact physical bounds", () => {
  for (const node of before.gltf.nodes.filter(n => n.mesh !== undefined)) {
    const a = primitive(before, node), b = primitive(after, node);
    const names = Object.keys(a.attributes).filter(name => !(isCase(node) && name === "NORMAL"));
    const original = names.map(name => array(before, a.attributes[name]));
    const output = names.map(name => array(after, b.attributes[name]));
    const widths = names.map((_, i) => original[i].length / before.gltf.accessors[a.attributes.POSITION].count);
    const tuple = (data, i) => data.flatMap((values, j) => Array.from(values.subarray(i * widths[j], (i + 1) * widths[j]))).join(",");
    const originals = new Set();
    for (let i = 0; i < before.gltf.accessors[a.attributes.POSITION].count; i++) originals.add(tuple(original, i));
    for (let i = 0; i < after.gltf.accessors[b.attributes.POSITION].count; i++) assert.ok(originals.has(tuple(output, i)), node.name);
    for (const bound of ["min", "max"]) assert.deepEqual(after.gltf.accessors[b.attributes.POSITION][bound], before.gltf.accessors[a.attributes.POSITION][bound]);
    if (isCase(node)) {
      assert.notEqual(b.attributes.NORMAL, undefined, "reduced case uses source-derived shading normals");
      const normals = array(after, b.attributes.NORMAL);
      assert.equal(normals.length, after.gltf.accessors[b.attributes.POSITION].count * 3);
      for (let i = 0; i < normals.length; i += 3) assert.ok(Math.abs(Math.hypot(...normals.subarray(i, i + 3)) - 1) < 1e-6, node.name);
    }
  }
});

test("keycap dish centres and whole 12-sided glass-rim cross-sections survive reduction", () => {
  assert.equal([...optimized.values()].filter(m => m.role === "keycap").length, 36);
  assert.equal([...optimized.values()].filter(m => m.role === "glassRim").length, 108);
  for (const node of before.gltf.nodes.filter(n => ["keycap", "glassRim"].includes(optimized.get(n.name)?.role))) {
    const a = primitive(before, node), b = primitive(after, node);
    const aPos = array(before, a.attributes.POSITION), bPos = array(after, b.attributes.POSITION);
    const retained = new Set();
    for (let i = 0; i < bPos.length; i += 3) retained.add(Array.from(bPos.subarray(i, i + 3)).join(","));
    if (optimized.get(node.name).role === "keycap") {
      const bounds = before.gltf.accessors[a.attributes.POSITION], center = [0, 1].map(c => (bounds.min[c] + bounds.max[c]) / 2);
      let centres = 0;
      for (let i = 0; i < aPos.length; i += 3) if (Math.hypot(aPos[i] - center[0], aPos[i+1] - center[1]) < 0.00002 && aPos[i+2] > bounds.min[2] + 2) {
        centres++;
        assert.ok(retained.has(Array.from(aPos.subarray(i, i + 3)).join(",")), node.name);
      }
      assert.ok(centres > 0, `${node.name} has a protected dish centre`);
    } else {
      assert.equal(after.gltf.accessors[b.attributes.POSITION].count, 24, node.name);
      assert.equal(array(after, b.indices).length / 3, 44, node.name);
      for (const start of [0, aPos.length - 36]) for (let i = start; i < start + 36; i += 3) {
        assert.ok(retained.has(Array.from(aPos.subarray(i, i + 3)).join(",")), node.name);
      }
    }
  }
});

function resolvedMaterial(gltf, binary, index) {
  const material = structuredClone(gltf.materials[index]);
  function walk(value) {
    for (const [key, child] of Object.entries(value)) if (child && typeof child === "object") {
      if (key.endsWith("Texture") && child.index !== undefined) {
        const texture = gltf.textures[child.index], image = gltf.images[texture.source], view = gltf.bufferViews[image.bufferView];
        child.index = { texture: texture.name, sampler: gltf.samplers[texture.sampler], mime: image.mimeType,
          imageSha256: sha256(binary.subarray(view.byteOffset ?? 0, (view.byteOffset ?? 0) + view.byteLength)) };
      } else walk(child);
    }
  }
  walk(material); return material;
}

test("all rendered PBR materials, texture pixels, UV transforms and samplers remain identical", () => {
  for (let i = 0; i < before.gltf.meshes.length; i++) for (let j = 0; j < before.gltf.meshes[i].primitives.length; j++) {
    const a = before.gltf.meshes[i].primitives[j], b = after.gltf.meshes[i].primitives[j];
    assert.deepEqual(resolvedMaterial(after.gltf, after.binary, b.material), resolvedMaterial(before.gltf, before.binary, a.material));
  }
});

test("every mesh retains its surface topology, including all 24 holes in each top case", () => {
  for (const node of before.gltf.nodes.filter(n => n.mesh !== undefined)) {
    const original = topology(before, primitive(before, node)), reduced = topology(after, primitive(after, node));
    assert.deepEqual(reduced, original, node.name);
    if (optimized.has(node.name)) {
      assert.equal(reduced.boundaryEdges, 0, node.name);
      assert.equal(reduced.nonManifoldEdges, 0, node.name);
    }
    if (isCase(node)) assert.deepEqual(reduced, { euler: node.name.includes("top") ? -46 : 2, boundaryEdges: 0, nonManifoldEdges: 0 }, node.name);
  }
});
