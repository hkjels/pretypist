import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  DEFAULT_DISPLAY_DIRECTORY,
  KNOWN_REVISIONS,
  ZMK_V03_SEMANTICS,
  extractDisplaySemantics,
  verifyDisplayEvidence,
  verifySourceDirectory,
} from "../../scripts/verify-zmk-display.mjs";

const VENDOR_ROOT = path.join(DEFAULT_DISPLAY_DIRECTORY, "vendor/zmk-v0.3");

function temporaryDisplayDirectory(context) {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "pretypist-zmk-display-"));
  const displayDirectory = path.join(temporaryRoot, "display");
  fs.cpSync(DEFAULT_DISPLAY_DIRECTORY, displayDirectory, { recursive: true });
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  return displayDirectory;
}

function readVendorText() {
  const files = new Map();
  const revision = KNOWN_REVISIONS.get("v0.3");
  for (const entry of revision.files) {
    files.set(entry.path, fs.readFileSync(path.join(VENDOR_ROOT, ...entry.path.split("/")), "utf8"));
  }
  return files;
}

test("verifies the committed v0.3 evidence and extracted authoring constants", () => {
  const report = verifyDisplayEvidence();
  assert.equal(report.ok, true);
  assert.equal(report.revision, "v0.3");
  assert.equal(report.commit, "edf5c0814fd3ea202e43aad2d68fd32e882a518c");
  assert.equal(report.filesVerified, 10);
  assert.deepEqual(report.semantics, ZMK_V03_SEMANTICS);
  assert.equal(report.semantics.panelPixels.width, 160);
  assert.equal(report.semantics.panelPixels.height, 68);
  assert.equal(report.semantics.profileCount, 5);
  assert.deepEqual(report.semantics.peripheralArtPixels.mountain, {
    width: 140,
    height: 68,
    dataBytes: 1232,
  });
});

test("rejects revisions that have not been explicitly reviewed", () => {
  assert.throws(
    () => verifyDisplayEvidence({ revision: "v0.4" }),
    /Unknown ZMK nice!view revision "v0\.4"/u,
  );
});

test("rejects a provenance manifest changed to an unknown revision", (context) => {
  const displayDirectory = temporaryDisplayDirectory(context);
  const provenancePath = path.join(displayDirectory, "provenance.json");
  const provenance = JSON.parse(fs.readFileSync(provenancePath, "utf8"));
  provenance.upstream.tag = "unreviewed";
  fs.writeFileSync(provenancePath, `${JSON.stringify(provenance, null, 2)}\n`);
  assert.throws(
    () => verifyDisplayEvidence({ displayDirectory }),
    /provenance\.json names unknown ZMK revision "unreviewed"/u,
  );
});

test("rejects a byte change even when visual semantic markers remain", (context) => {
  const displayDirectory = temporaryDisplayDirectory(context);
  const utilHeader = path.join(
    displayDirectory,
    "vendor/zmk-v0.3/app/boards/shields/nice_view/widgets/util.h",
  );
  fs.appendFileSync(utilHeader, "\n");
  assert.throws(
    () => verifyDisplayEvidence({ displayDirectory }),
    /widgets\/util\.h has 1697 bytes; expected 1696/u,
  );
});

test("rejects symlinks in the source allowlist", (context) => {
  const displayDirectory = temporaryDisplayDirectory(context);
  const copiedVendor = path.join(displayDirectory, "vendor/zmk-v0.3");
  const relativeBolt = "app/boards/shields/nice_view/widgets/bolt.c";
  const copiedBolt = path.join(copiedVendor, ...relativeBolt.split("/"));
  fs.unlinkSync(copiedBolt);
  fs.symlinkSync(path.join(VENDOR_ROOT, ...relativeBolt.split("/")), copiedBolt);
  assert.throws(
    () => verifySourceDirectory(copiedVendor),
    /Source file must not be a symbolic link/u,
  );
});

test("requires semantic markers in addition to byte-oriented plumbing", () => {
  const files = readVendorText();
  const utilHeaderPath = "app/boards/shields/nice_view/widgets/util.h";
  files.set(utilHeaderPath, files.get(utilHeaderPath).replace("#define CANVAS_SIZE 68", ""));
  assert.throws(
    () => extractDisplaySemantics(files),
    /Missing nice!view semantic marker: canvas size/u,
  );
});
