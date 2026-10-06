#!/usr/bin/env node
/** Integrate the supplied GLB, then apply explicit, reviewable surface refinements. */
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { Box3, Matrix4, Vector3 } from "three";
import { auditGlb, parseGlb, DEFAULT_REQUIRED_NODES, formatReport } from "./audit-model.mjs";
import { refineSurfaces } from "./model/refine-surfaces.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const directory = path.join(root, "resources/layout-sources/modified-engram");
export const sourcePath = path.join(directory, "authored/model.glb");
export const outputPath = path.join(directory, "model.glb");
const reportPath = path.join(directory, "model-adaptation.json");
export const refinementSettings = JSON.parse(fs.readFileSync(path.join(directory, "surface-refinements.json"), "utf8"));
export const sourceSha256 = "01b84edda68fd4b6501bdc0f9d8e2dc9bb7a1bbcdcfd5dd286c437b819b24f08";
export const sha256 = (buffer) => crypto.createHash("sha256").update(buffer).digest("hex");

// SW references agree with the vendored PCB and the original package's mapping.
// The right half is enumerated inside-to-outside, including its three thumbs.
const leftSwitches = [2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20, 21];
const leftIndices = [0, 1, 2, 3, 4, 10, 11, 12, 13, 14, 20, 21, 22, 23, 24, 30, 31, 32];
const rightIndices = [9, 8, 7, 6, 5, 19, 18, 17, 16, 15, 29, 28, 27, 26, 25, 35, 34, 33];
export const keyMapping = Object.fromEntries(["L", "R"].flatMap((side) =>
  leftSwitches.map((sw, i) => [`${side}_SW${sw}_Keycap`, `key_${String((side === "L" ? leftIndices : rightIndices)[i]).padStart(2, "0")}`])));

// These are the measured costs of the user's selected dense CAD model, not
// default allowances for arbitrary imported packages. The pinned source hash,
// original BIN prefix, explicit refinement ledger and deterministic output are checked.
export const authoredLimits = {
  caseEffectiveTriangles: 671344,
  skinnyTriangles: 18408,
  degenerateTriangles: 0,
};
const requiredNodes = [...DEFAULT_REQUIRED_NODES, "display_screen_left", "display_screen_right"];
const metadata = [
  { path: ["asset", "version"], description: "glTF 2.0", validate: x => x === "2.0" },
  { path: ["asset", "extras", "units"], description: "metres", validate: x => x === "metres" },
  { path: ["asset", "extras", "upAxis"], description: "Y-up", validate: x => x === "Y" },
  { path: ["asset", "extras", "sourceSha256"], description: "the supplied source hash", validate: x => x === sourceSha256 },
];

function encodeGlb(gltf, binary) {
  const raw = Buffer.from(JSON.stringify(gltf));
  const json = Buffer.concat([raw, Buffer.alloc((4 - raw.length % 4) % 4, 32)]);
  const output = Buffer.alloc(28 + json.length + binary.length);
  [0x46546c67, 2, output.length, json.length, 0x4e4f534a].forEach((x, i) => output.writeUInt32LE(x, i * 4));
  json.copy(output, 20);
  output.writeUInt32LE(binary.length, 20 + json.length);
  output.writeUInt32LE(0x004e4942, 24 + json.length);
  binary.copy(output, 28 + json.length);
  return output;
}

function worldBounds(gltf, node) {
  const result = new Box3();
  const transform = new Matrix4().fromArray(node.matrix);
  for (const primitive of gltf.meshes[node.mesh].primitives) {
    const position = gltf.accessors[primitive.attributes.POSITION];
    result.union(new Box3(new Vector3(...position.min), new Vector3(...position.max)).applyMatrix4(transform));
  }
  return result;
}

function relativeMatrix(matrix, pivot) {
  return new Matrix4().makeTranslation(-pivot.x, -pivot.y, -pivot.z)
    .multiply(new Matrix4().fromArray(matrix)).toArray();
}

export function adaptModel(source) {
  if (sha256(source) !== sourceSha256) throw new Error("The authored/model.glb source changed; review its mapping and update the pinned source hash before adaptation.");
  const { gltf, binary } = parseGlb(source);
  if (gltf.nodes.some(n => n.children?.length || !n.matrix || n.mesh === undefined)) {
    throw new Error("Expected the reviewed flat, transformed-mesh source hierarchy.");
  }
  const sourceScene = structuredClone(gltf.scenes[gltf.scene ?? 0].extras);
  const sourceNodes = gltf.nodes;
  const sourceMeshes = gltf.meshes;
  const byName = new Map(sourceNodes.map(n => [n.name, n]));
  for (const name of Object.keys(keyMapping)) if (!byName.has(name)) throw new Error(`Missing key: ${name}`);
  const nodes = [{ name: "keyboard", children: [] }];
  const mappings = [];
  const displays = {};
  const meshes = [];
  const meshIndices = new Map();
  function add(node, parent) {
    const i = nodes.push(node) - 1;
    (nodes[parent].children ??= []).push(i);
    return i;
  }
  function meshIndex(index) {
    if (!meshIndices.has(index)) meshIndices.set(index, meshes.push(sourceMeshes[index]) - 1);
    return meshIndices.get(index);
  }
  for (const [prefix, side] of [["L", "left"], ["R", "right"]]) {
    const halfBounds = worldBounds(gltf, byName.get(`${prefix}_Case_top_original_v1`));
    const halfPivot = halfBounds.getCenter(new Vector3());
    halfPivot.y = 0;
    const half = add({ name: `half_${side}`, translation: halfPivot.toArray(), children: [] }, 0);
    const caseNode = add({ name: `case_${side}`, children: [] }, half);
    for (const original of sourceNodes.filter(n => n.name.startsWith(`${prefix}_`))) {
      const sourceName = original.name;
      if (sourceName === `${prefix}_Display_photo_graphic`) {
        // Replace only the static photograph surface with the live status plane.
        // Keep its original pixels in the untouched source/BIN as source evidence.
        const bounds = worldBounds(gltf, original);
        const pivot = bounds.getCenter(new Vector3());
        const size = bounds.getSize(new Vector3());
        displays[side] = { source: sourceName, node: `display_screen_${side}`,
          surfaceSizeMm: [size.x * 1000, size.z * 1000], surfaceWorldMetres: pivot.toArray() };
        pivot.y -= 0.000345; // runtime group surface offset, including separation
        add({ name: `display_screen_${side}`, translation: pivot.sub(halfPivot).toArray(),
          extras: { sourceNode: sourceName } }, half);
        continue;
      }
      const node = { ...original, mesh: meshIndex(original.mesh),
        matrix: relativeMatrix(original.matrix, halfPivot),
        extras: { ...original.extras, sourceNode: sourceName } };
      const keyName = keyMapping[sourceName];
      if (keyName) {
        const box = worldBounds(gltf, original);
        const pivot = box.getCenter(new Vector3());
        pivot.y = box.min.y;
        const key = add({ name: keyName, translation: pivot.clone().sub(halfPivot).toArray(),
          extras: { sourceNode: sourceName }, children: [] }, half);
        node.matrix = relativeMatrix(original.matrix, pivot);
        add(node, key);
        mappings.push({ node: keyName, source: sourceName, pivotMetres: pivot.toArray() });
      } else if (sourceName.includes("_Case_")) {
        node.name = `case_${sourceName.includes("_bottom_") ? "bottom" : "top"}_${side}`;
        add(node, caseNode);
      } else {
        add(node, half);
      }
    }
  }
  gltf.nodes = nodes;
  gltf.meshes = meshes;
  gltf.scenes = [{ name: "Modified Engram", nodes: [0] }];
  gltf.scene = 0;
  const refinement = refineSurfaces(gltf, binary, refinementSettings);
  gltf.asset = { version: "2.0", generator: "PRETYPIST authored-model adapter v2",
    extras: { units: "metres", upAxis: "Y", sourceFile: "authored/model.glb", sourceSha256,
      sourceGenerator: "https://github.com/mikedh/trimesh", sourceScene,
      geometryPolicy: "Original source retained; keycaps, metal normals, case maps and glass fasteners refined by surface-refinements.json",
      editableQuadRatio: null, topologyNote: "Supplied GLB is triangulated; editable quad topology is unavailable." } };
  const output = encodeGlb(gltf, refinement.binary);
  const audit = auditGlb(output, { requiredNodes, expectedMetadata: metadata, limits: authoredLimits });
  if (!audit.ok) throw new Error(audit.findings.map(f => f.message).join("\n"));
  if (audit.triangles.effective !== 973424) throw new Error(`Unexpected refined face count: ${audit.triangles.effective}.`);
  const report = { version: 2, source: "authored/model.glb", sourceSha256,
    outputSha256: sha256(output), editableQuadRatio: null,
    note: "Targeted surface/fastener refinement, not whole-model retopology. Original source retained byte-for-byte.",
    refinement: refinement.report,
    keys: mappings.sort((a,b) => a.node.localeCompare(b.node)), displays,
    sourceBytes: source.length, outputBytes: output.length,
    triangles: audit.triangles, inheritedGeometryLimits: authoredLimits };
  return { output, report, audit };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.some(x => !["--check", "--audit", "--enforce", "--json"].includes(x))) throw new Error("Usage: node scripts/adapt-authored-model.mjs [--check | --audit] [--enforce] [--json]");
  const result = adaptModel(fs.readFileSync(sourcePath));
  if (args.includes("--check") || args.includes("--audit")) {
    if (!fs.readFileSync(outputPath).equals(result.output)) throw new Error("Adapted model is stale; run npm run generate:model.");
    if (fs.readFileSync(reportPath, "utf8") !== JSON.stringify(result.report, null, 2) + "\n") throw new Error("Model adaptation report is stale.");
  } else {
    fs.writeFileSync(outputPath, result.output);
    fs.writeFileSync(reportPath, JSON.stringify(result.report, null, 2) + "\n");
  }
  if (args.includes("--json")) console.log(JSON.stringify(result.report, null, 2));
  else if (args.includes("--audit")) console.log(formatReport(result.audit, { input: outputPath, enforce: true }));
  else console.log(`Verified refined keyboard: 36 mapped keys, 2 live displays, 4 glass fastener shafts (${result.output.length} bytes); original authored source untouched.`);
}
