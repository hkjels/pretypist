#!/usr/bin/env node

/**
 * Attach PRETYPIST's provenance and editable-topology contract to the GLB
 * exported by Blender, then verify the checked-in result.
 *
 * Blender/glTF necessarily triangulates the transport mesh. The accompanying
 * model.blend and model-topology.json are therefore the authoritative sources
 * for the editable quad ratio.
 */

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { auditGlb, parseGlb } from "./audit-model.mjs";

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(SCRIPT_DIRECTORY, "..");
const SOURCE_DIRECTORY = path.join(
  PROJECT_ROOT,
  "resources/layout-sources/modified-engram/legacy",
);
const INPUT = path.join(PROJECT_ROOT, "target/blender/model-unstamped.glb");
const PROVENANCE_REFERENCE = path.join(PROJECT_ROOT, "target/blender/model-reference.glb");
const OUTPUT = path.join(SOURCE_DIRECTORY, "model.glb");
const TOPOLOGY = path.join(SOURCE_DIRECTORY, "model-topology.json");
const JSON_CHUNK_TYPE = 0x4e4f534a;
const BIN_CHUNK_TYPE = 0x004e4942;

function readTopology() {
  const topology = JSON.parse(fs.readFileSync(TOPOLOGY, "utf8"));
  if (!(topology.quadRatio >= 0.95)) {
    throw new Error(`Editable quad ratio ${topology.quadRatio} is below 0.95`);
  }
  if (topology.polygons?.triangles !== 0) {
    throw new Error(`Editable model contains ${topology.polygons?.triangles} triangle(s)`);
  }
  if ((topology.requiredNodes?.missing ?? []).length > 0) {
    throw new Error(`Editable model is missing nodes: ${topology.requiredNodes.missing.join(", ")}`);
  }
  return topology;
}

function padded(buffer, byte = 0x20) {
  const padding = (4 - (buffer.length % 4)) % 4;
  return padding === 0 ? buffer : Buffer.concat([buffer, Buffer.alloc(padding, byte)]);
}

function encodeGlb(gltf, binary) {
  const json = padded(Buffer.from(JSON.stringify(gltf), "utf8"), 0x20);
  const bin = padded(binary, 0);
  const output = Buffer.alloc(12 + 8 + json.length + 8 + bin.length);
  output.writeUInt32LE(0x46546c67, 0);
  output.writeUInt32LE(2, 4);
  output.writeUInt32LE(output.length, 8);
  output.writeUInt32LE(json.length, 12);
  output.writeUInt32LE(JSON_CHUNK_TYPE, 16);
  json.copy(output, 20);
  const binaryHeader = 20 + json.length;
  output.writeUInt32LE(bin.length, binaryHeader);
  output.writeUInt32LE(BIN_CHUNK_TYPE, binaryHeader + 4);
  bin.copy(output, binaryHeader + 8);
  return output;
}

function validateFinal(model, topology) {
  const report = auditGlb(model);
  if (!report.ok) {
    throw new Error(
      `Stamped Blender model failed audit:\n${report.findings
        .map((finding) => `- ${finding.message}`)
        .join("\n")}`,
    );
  }
  const { gltf } = parseGlb(model);
  const extras = gltf.asset?.extras ?? {};
  if (extras.editableQuadRatio !== topology.quadRatio) {
    throw new Error("GLB editable quad ratio does not match model-topology.json");
  }
  if (extras.editablePolygons !== topology.polygons.total) {
    throw new Error("GLB editable polygon count does not match model-topology.json");
  }
  return report;
}

function stamp() {
  const topology = readTopology();
  const source = fs.readFileSync(INPUT);
  const sourceAudit = auditGlb(source, { expectedMetadata: [] });
  if (!sourceAudit.ok) {
    throw new Error(
      `Unstamped Blender export failed geometry audit:\n${sourceAudit.findings
        .map((finding) => `- ${finding.message}`)
        .join("\n")}`,
    );
  }
  const parsed = parseGlb(source);
  const provenance = parseGlb(fs.readFileSync(PROVENANCE_REFERENCE)).gltf.asset.extras;
  parsed.gltf.asset = {
    ...parsed.gltf.asset,
    generator: "Khronos glTF Blender I/O 5.2 + PRETYPIST Blender MCP quad authoring r1",
    extras: {
      ...provenance,
      description:
        "Photo-guided Chocofi v1 reconstruction with quad-authored case, dished blank keycaps, nice!nano controllers, nice!view glass covers, fasteners, connectors, and wiring",
      renderMeshGenerator: "scripts/blender/build_modified_engram.py via Blender MCP",
      renderMeshCaseTriangles: sourceAudit.triangles.caseStored,
      renderMeshBoundaryEdges: { top: 0, bottom: 0 },
      renderMeshNonManifoldEdges: { top: 0, bottom: 0 },
      normalCreaseAngleDegrees: 30,
      blenderVersion: "5.2.0 LTS",
      blenderSource: "model.blend",
      editableTopologyReport: "model-topology.json",
      editableQuadRatio: topology.quadRatio,
      editablePolygons: topology.polygons.total,
      editableTriangles: topology.polygons.triangles,
      transportTopology: "glTF 2.0 triangulated",
      photoGuidedMaterials: true,
    },
  };
  const stamped = encodeGlb(parsed.gltf, parsed.binary);
  validateFinal(stamped, topology);
  fs.writeFileSync(OUTPUT, stamped);
  return { report: validateFinal(stamped, topology), topology };
}

const checkOnly = process.argv.slice(2).includes("--check");
if (process.argv.slice(2).some((argument) => argument !== "--check")) {
  throw new Error("Usage: node scripts/stamp-blender-model.mjs [--check]");
}

const topology = readTopology();
const result = checkOnly
  ? { report: validateFinal(fs.readFileSync(OUTPUT), topology), topology }
  : stamp();
process.stdout.write(
  `${checkOnly ? "Verified" : "Stamped"} ${path.relative(PROJECT_ROOT, OUTPUT)}: ` +
    `${result.topology.polygons.total.toLocaleString("en-US")} editable polygons, ` +
    `${(result.topology.quadRatio * 100).toFixed(2)}% quads; ` +
    `${result.report.triangles.effective.toLocaleString("en-US")} effective transport triangles\n`,
);
