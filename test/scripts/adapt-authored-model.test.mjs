import assert from "node:assert/strict";
import test from "node:test";
import fs from "node:fs";
import { Matrix4, Vector3 } from "three";
import { parseGlb } from "../../scripts/audit-model.mjs";
import { adaptModel, sourcePath, keyMapping } from "../../scripts/adapt-authored-model.mjs";
import { readArray, keycapGeometry, grainMaps } from "../../scripts/model/refine-surfaces.mjs";

const source = fs.readFileSync(sourcePath);
const result = adaptModel(source);
const before = parseGlb(source);
const after = parseGlb(result.output);
function worldMatrices(gltf) {
  const records = new Map();
  function visit(index, parent) {
    const node = gltf.nodes[index];
    const local = node.matrix ? new Matrix4().fromArray(node.matrix)
      : new Matrix4().makeTranslation(...(node.translation ?? [0, 0, 0]));
    const world = parent.clone().multiply(local);
    records.set(node.name, { node, matrix: world });
    for (const child of node.children ?? []) visit(child, world);
  }
  for (const i of gltf.scenes[gltf.scene ?? 0].nodes) visit(i, new Matrix4());
  return records;
}
const worlds = worldMatrices(after.gltf);

test("refinement retains the source BIN, original material/image definitions and physical transforms", () => {
  assert.ok(after.binary.subarray(0, before.binary.length).equals(before.binary));
  assert.deepEqual(after.gltf.materials.slice(0, before.gltf.materials.length), before.gltf.materials);
  assert.deepEqual(after.gltf.images.slice(0, before.gltf.images.length), before.gltf.images);
  for (const [name, { matrix }] of worldMatrices(before.gltf)) {
    if (name.includes("Display_photo_graphic")) continue;
    const record = [...worlds.values()].find(r => r.node.extras?.sourceNode === name && r.node.mesh !== undefined);
    assert.ok(record, name);
    matrix.elements.forEach((x, i) => assert.ok(Math.abs(x - record.matrix.elements[i]) < 1e-12, name));
  }
});

test("every switch reference maps to the documented left/right physical index", () => {
  assert.equal(Object.keys(keyMapping).length, 36);
  assert.equal(keyMapping.L_SW2_Keycap, "key_00");
  assert.equal(keyMapping.R_SW2_Keycap, "key_09");
  assert.equal(keyMapping.L_SW19_Keycap, "key_30");
  assert.equal(keyMapping.L_SW21_Keycap, "key_32");
  assert.equal(keyMapping.R_SW21_Keycap, "key_33");
  assert.equal(keyMapping.R_SW19_Keycap, "key_35");
  for (const [sourceName, target] of Object.entries(keyMapping)) {
    const record = worlds.get(target);
    const position = new Vector3().setFromMatrixPosition(record.matrix);
    assert.equal(after.gltf.nodes[record.node.children[0]].name, sourceName);
    assert.ok(sourceName.startsWith("L_") ? position.x < 0 : position.x > 0);
    assert.ok(Math.abs(position.y - 0.012) < 1e-8);
    // Parent is already metre/Y-up: local travel is exactly 2.4 mm downward.
    const travel = new Vector3(0, -0.0024, 0).applyMatrix4(record.matrix).sub(position);
    assert.ok(travel.distanceTo(new Vector3(0, -0.0024, 0)) < 1e-10);
  }
});

test("live display anchors replace only the two photographs at the original surface", () => {
  for (const [side, data] of Object.entries(result.report.displays)) {
    assert.equal(worlds.has(data.source), false);
    const record = worlds.get(`display_screen_${side}`);
    assert.equal(record.node.mesh, undefined);
    const surface = new Vector3(0, 0.000345, 0).applyMatrix4(record.matrix);
    assert.ok(surface.distanceTo(new Vector3(...data.surfaceWorldMetres)) < 1e-10);
    assert.ok(Math.abs(data.surfaceSizeMm[0] - 10.9) < 1e-5);
    assert.ok(Math.abs(data.surfaceSizeMm[1] - 25.5) < 1e-5);
    assert.ok(record.matrix.determinant() > 0, "screen must not be mirrored");
  }
  assert.equal(result.audit.triangles.effective, 973424);
  assert.equal(result.audit.triangles.degeneratesEffective, 0);
});

test("keycaps have the specified dish, pinched corners, finite smooth normals and unchanged placement", () => {
  const config = result.report.refinement.settings.keycap;
  const geometry = keycapGeometry(config, [0, 0], 0), p = geometry.attributes.position;
  assert.ok(Math.abs(p.getZ(p.count - 1) - (15.45 - config.dishDepth)) < 0.00001);
  const rim = Array.from({ length: 64 }, (_, i) => [p.getX(4 * 64 + i), p.getY(4 * 64 + i), p.getZ(4 * 64 + i)]);
  assert.ok(Math.abs(Math.max(...rim.map(v => v[2])) - 15.45) < 0.00001);
  const square = keycapGeometry({ ...config, cornerInset: 0 }, [0, 0], 0).attributes.position;
  assert.ok(rim.some((v, i) => Math.hypot(v[0], v[1]) < Math.hypot(square.getX(256 + i), square.getY(256 + i)) - 0.1));
  for (const item of result.report.refinement.refinements.filter(r => r.role === "keycap")) {
    const expected = item.node.includes("SW20") ? 15 : item.node.includes("SW21") ? 30 : 0;
    assert.ok(Math.abs(Math.abs(item.angle * 180 / Math.PI) - expected) < 0.0001);
    const node = worlds.get(item.node).node, primitive = after.gltf.meshes[node.mesh].primitives[0];
    const normals = readArray(after.gltf, after.binary, primitive.attributes.NORMAL);
    for (let i = 0; i < normals.length; i += 3) assert.ok(Math.abs(Math.hypot(...normals.slice(i, i + 3)) - 1) < 0.00001);
  }
});

test("shafts traverse the existing glass holes and standoffs meet the glass underside", () => {
  assert.equal(result.report.refinement.shafts.length, 4);
  for (const shaft of result.report.refinement.shafts) {
    assert.ok(shaft.bottom < shaft.glassBottom);
    assert.ok(shaft.top > shaft.glassTop && shaft.top > shaft.headBottom);
    assert.equal(shaft.radius, 1);
    const side = shaft.node[0], index = shaft.node.split("_").at(-1);
    const stand = worlds.get(`${side}_Cover_standoff_${index}`).node;
    const standBounds = after.gltf.accessors[after.gltf.meshes[stand.mesh].primitives[0].attributes.POSITION];
    assert.ok(Math.abs(standBounds.max[2] - shaft.glassBottom) < 0.00001);
    const glass = worlds.get(`${side}_Acrylic_cover_estimated_1.5mm`).node;
    const positions = readArray(after.gltf, after.binary, after.gltf.meshes[glass.mesh].primitives[0].attributes.POSITION);
    let radius = Infinity;
    for (let i = 0; i < positions.length; i += 3) radius = Math.min(radius, Math.hypot(positions[i] - shaft.center[0], positions[i + 1] - shaft.center[1]));
    assert.ok(Math.abs(radius - 1.2) < 0.00001 && radius > shaft.radius);
  }
});

test("case topology is preserved, metals are PBR conductors, and grain maps are self-contained", () => {
  const mats = after.gltf.materials;
  const cap = mats.find(m => m.name === "PRETYPIST charcoal keycaps").pbrMetallicRoughness;
  const casing = mats.find(m => m.name === "PRETYPIST textured black case");
  assert.ok(casing.pbrMetallicRoughness.baseColorFactor[0] < cap.baseColorFactor[0]);
  assert.ok(casing.pbrMetallicRoughness.roughnessFactor < cap.roughnessFactor);
  for (const name of ["PRETYPIST machined stainless steel", "PRETYPIST brushed USB shell"]) {
    const material = mats.find(m => m.name === name);
    assert.equal(material.pbrMetallicRoughness.metallicFactor, 1);
    assert.ok(material.normalTexture);
  }
  for (const original of before.gltf.nodes.filter(n => n.name.includes("_Case_"))) {
    const node = [...worlds.values()].find(r => r.node.extras?.sourceNode === original.name).node;
    const a = before.gltf.meshes[original.mesh].primitives[0], b = after.gltf.meshes[node.mesh].primitives[0];
    assert.equal(a.attributes.POSITION, b.attributes.POSITION);
    assert.equal(a.indices, b.indices);
    assert.equal(after.gltf.accessors[b.attributes.TEXCOORD_0].normalized, true);
  }
  for (const image of after.gltf.images.slice(before.gltf.images.length)) {
    assert.equal(image.uri, undefined);
    const view = after.gltf.bufferViews[image.bufferView];
    assert.equal(after.binary.subarray(view.byteOffset + 1, view.byteOffset + 4).toString(), "PNG");
  }
  assert.deepEqual(grainMaps("case"), grainMaps("case"));
  assert.notDeepEqual(grainMaps("case").normal, grainMaps("steel").normal);
});

test("adaptation is deterministic and rejects unreviewed replacement source data", () => {
  assert.ok(adaptModel(source).output.equals(result.output));
  assert.throws(() => adaptModel(Buffer.from("unexpected")), /source changed/);
  assert.equal(result.report.editableQuadRatio, null);
});
