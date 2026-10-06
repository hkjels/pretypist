import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  ART_HEIGHT,
  ART_SOURCE_PATH,
  ART_WIDTH,
  BIT_ORDER,
  DATA_SIZE_BYTES,
  PIXEL_BYTES,
  ROW_STRIDE_BYTES,
  extractNiceViewArt,
  generateDisplayAssets,
  renderNiceViewNamespace,
} from "../../scripts/generate-zmk-display-assets.mjs";
import {
  DEFAULT_DISPLAY_DIRECTORY,
  KNOWN_REVISIONS,
} from "../../scripts/verify-zmk-display.mjs";

const VENDOR_ROOT = path.join(DEFAULT_DISPLAY_DIRECTORY, "vendor/zmk-v0.3");
const ART_PATH = path.join(VENDOR_ROOT, ...ART_SOURCE_PATH.split("/"));

function readArtSource() {
  return fs.readFileSync(ART_PATH, "utf8");
}

function temporaryOutput(context) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "pretypist-nice-view-art-"));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  return path.join(directory, "generated", "nice_view_art.cljs");
}

test("extracts the exact ZMK v0.3 indexed one-bit art without its palette", () => {
  const art = extractNiceViewArt(readArtSource());

  assert.equal(art.width, ART_WIDTH);
  assert.equal(art.height, ART_HEIGHT);
  assert.equal(art.rowStrideBytes, ROW_STRIDE_BYTES);
  assert.equal(art.pixelBytes, PIXEL_BYTES);
  assert.equal(art.dataSizeBytes, DATA_SIZE_BYTES);
  assert.equal(art.bitOrder, BIT_ORDER);
  assert.equal(ART_WIDTH, 140);
  assert.equal(ART_HEIGHT, 68);
  assert.equal(ROW_STRIDE_BYTES, 18);
  assert.equal(PIXEL_BYTES, 1224);
  assert.equal(DATA_SIZE_BYTES, 1232);

  assert.equal(art.bitmaps.get("balloon").pixels.length, 1224);
  assert.equal(art.bitmaps.get("mountain").pixels.length, 1224);
  assert.equal(
    art.bitmaps.get("balloon").sha256,
    "c1319e926e813d44839e1e62d66a5b1bf55cfadade9dc79beacce73b38789492",
  );
  assert.equal(
    art.bitmaps.get("mountain").sha256,
    "a43c48276824205483b0d3d3db56e8524304148096cbf4870d3751c0c0992ac3",
  );

  for (const bitmap of art.bitmaps.values()) {
    assert.deepEqual([...bitmap.normalPalette], [0, 0, 0, 255, 255, 255, 255, 255]);
    assert.deepEqual([...bitmap.invertedPalette], [255, 255, 255, 255, 0, 0, 0, 255]);
    for (let row = 0; row < ART_HEIGHT; row += 1) {
      assert.equal(bitmap.pixels[row * ROW_STRIDE_BYTES + 17] & 0x0f, 0);
    }
  }
});

test("renders deterministic CLJS with compact hex strings and provenance", () => {
  const art = extractNiceViewArt(readArtSource());
  const provenance = KNOWN_REVISIONS.get("v0.3");
  const first = renderNiceViewNamespace(art, provenance);
  const second = renderNiceViewNamespace(art, provenance);

  assert.equal(first, second);
  assert.match(first, /^\(ns pretypist\.scene\.generated\.nice-view-art\)$/mu);
  assert.match(first, /^\(def width 140\)$/mu);
  assert.match(first, /^\(def height 68\)$/mu);
  assert.match(first, /^\(def row-stride-bytes 18\)$/mu);
  assert.match(first, /^\(def bit-order :msb-first\)$/mu);
  assert.match(first, /:balloon \(str/u);
  assert.match(first, /:mountain \(str/u);
  assert.doesNotMatch(first, /0x[0-9a-f]/u);
  assert.match(first, /edf5c0814fd3ea202e43aad2d68fd32e882a518c/u);
});

test("writes deterministically and check mode detects stale generated output", (context) => {
  const output = temporaryOutput(context);

  const first = generateDisplayAssets({ output });
  const firstBytes = fs.readFileSync(output);
  const second = generateDisplayAssets({ output });
  const secondBytes = fs.readFileSync(output);
  assert.equal(first.ok, true);
  assert.equal(second.ok, true);
  assert.deepEqual(firstBytes, secondBytes);
  assert.equal(generateDisplayAssets({ output, checkOnly: true }).ok, true);

  fs.appendFileSync(output, "; stale\n");
  assert.throws(
    () => generateDisplayAssets({ output, checkOnly: true }),
    /Generated nice!view namespace is stale/u,
  );
});

test("rejects descriptor drift before extracting pixels", () => {
  const changed = readArtSource().replace(".header.w = 140,", ".header.w = 139,");
  assert.throws(
    () => extractNiceViewArt(changed),
    /balloon descriptor is 139x68\/1232 bytes; expected 140x68\/1232/u,
  );
});

test("rejects an altered or ambiguous LVGL palette", () => {
  const changed = readArtSource().replace(
    "0xff, 0xff, 0xff, 0xff, /*Color of index 0*/",
    "0xfe, 0xff, 0xff, 0xff, /*Color of index 0*/",
  );
  assert.throws(
    () => extractNiceViewArt(changed),
    /balloon inverted palette is not the reviewed two-entry RGBA palette/u,
  );
});
