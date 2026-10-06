#!/usr/bin/env node

/**
 * Generate the exact stock ZMK v0.3 nice!view peripheral bitmaps for CLJS.
 *
 * The source C is never compiled or executed. Before any data is extracted,
 * verify-zmk-display checks every vendored source file against the reviewed
 * byte length and SHA-256 allowlist. This script then accepts only the two
 * expected LVGL INDEXED_1BIT declarations and emits their packed pixel bytes.
 */

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

import {
  DEFAULT_DISPLAY_DIRECTORY,
  KNOWN_REVISIONS,
  verifyDisplayEvidence,
} from "./verify-zmk-display.mjs";

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(SCRIPT_DIRECTORY, "..");

export const DEFAULT_OUTPUT = path.join(
  PROJECT_ROOT,
  "src/pretypist/scene/generated/nice_view_art.cljs",
);

export const ART_SOURCE_PATH = "app/boards/shields/nice_view/widgets/art.c";
export const ART_NAMES = ["balloon", "mountain"];
export const ART_WIDTH = 140;
export const ART_HEIGHT = 68;
export const PALETTE_BYTES = 8;
export const DATA_SIZE_BYTES = 1232;
export const ROW_STRIDE_BYTES = Math.ceil(ART_WIDTH / 8);
export const PIXEL_BYTES = ROW_STRIDE_BYTES * ART_HEIGHT;
export const BIT_ORDER = "msb-first";

const NORMAL_PALETTE = Buffer.from([
  0x00, 0x00, 0x00, 0xff,
  0xff, 0xff, 0xff, 0xff,
]);
const INVERTED_PALETTE = Buffer.from([
  0xff, 0xff, 0xff, 0xff,
  0x00, 0x00, 0x00, 0xff,
]);

function sha256(buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex");
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
}

function exactlyOneMatch(source, expression, description) {
  const matches = [...source.matchAll(expression)];
  if (matches.length !== 1) {
    throw new Error(`Expected exactly one ${description}; found ${matches.length}`);
  }
  return matches[0];
}

function parseHexBytes(source, description) {
  const withoutComments = source
    .replace(/\/\*[\s\S]*?\*\//gu, "")
    .replace(/\/\/[^\r\n]*/gu, "");
  const literals = [...withoutComments.matchAll(/0x([0-9a-fA-F]{2})/gu)];
  const residual = withoutComments
    .replace(/0x[0-9a-fA-F]{2}/gu, "")
    .replace(/[\s,]/gu, "");
  if (residual.length > 0) {
    throw new Error(`${description} contains unsupported C tokens: ${JSON.stringify(residual.slice(0, 32))}`);
  }
  return Buffer.from(literals.map((match) => Number.parseInt(match[1], 16)));
}

function descriptorFor(source, name) {
  const escapedName = escapeRegExp(name);
  const match = exactlyOneMatch(
    source,
    new RegExp(`const\\s+lv_img_dsc_t\\s+${escapedName}\\s*=\\s*\\{([\\s\\S]*?)\\};`, "gu"),
    `${name} image descriptor`,
  );
  const body = match[1];
  const integerField = (field) => {
    const fieldMatch = body.match(new RegExp(`\\.${escapeRegExp(field)}\\s*=\\s*(\\d+)\\s*,`, "u"));
    if (!fieldMatch) throw new Error(`${name} descriptor is missing .${field}`);
    return Number(fieldMatch[1]);
  };

  if (!/\.header\.cf\s*=\s*LV_IMG_CF_INDEXED_1BIT\s*,/u.test(body)) {
    throw new Error(`${name} must use LV_IMG_CF_INDEXED_1BIT`);
  }
  if (!new RegExp(`\\.data\\s*=\\s*${escapedName}_map\\s*,`, "u").test(body)) {
    throw new Error(`${name} descriptor does not reference ${name}_map`);
  }
  return {
    width: integerField("header.w"),
    height: integerField("header.h"),
    dataSize: integerField("data_size"),
  };
}

function bitmapFor(source, name) {
  const escapedName = escapeRegExp(name);
  const match = exactlyOneMatch(
    source,
    new RegExp(
      `(?:^|\\n)const\\s+[^{;]*?\\b${escapedName}_map\\s*\\[\\s*\\]\\s*=\\s*\\{\\s*` +
        `#if\\s+CONFIG_NICE_VIEW_WIDGET_INVERTED\\s*([\\s\\S]*?)` +
        `#else\\s*([\\s\\S]*?)#endif\\s*([\\s\\S]*?)\\}\\s*;`,
      "gu",
    ),
    `${name}_map declaration`,
  );

  const invertedPalette = parseHexBytes(match[1], `${name} inverted palette`);
  const normalPalette = parseHexBytes(match[2], `${name} normal palette`);
  const pixels = parseHexBytes(match[3], `${name} pixel payload`);

  if (!invertedPalette.equals(INVERTED_PALETTE)) {
    throw new Error(`${name} inverted palette is not the reviewed two-entry RGBA palette`);
  }
  if (!normalPalette.equals(NORMAL_PALETTE)) {
    throw new Error(`${name} normal palette is not the reviewed two-entry RGBA palette`);
  }
  if (pixels.length !== PIXEL_BYTES) {
    throw new Error(`${name} has ${pixels.length} packed pixel bytes; expected ${PIXEL_BYTES}`);
  }

  // LVGL packs INDEXED_1BIT pixels most-significant bit first. At 140 pixels,
  // the lower nibble in the final byte of every 18-byte row is padding.
  const usedBitsInLastByte = ART_WIDTH % 8;
  const paddingMask = usedBitsInLastByte === 0 ? 0 : (1 << (8 - usedBitsInLastByte)) - 1;
  for (let row = 0; row < ART_HEIGHT; row += 1) {
    const lastByte = pixels[row * ROW_STRIDE_BYTES + ROW_STRIDE_BYTES - 1];
    if ((lastByte & paddingMask) !== 0) {
      throw new Error(`${name} row ${row} has non-zero low-bit row padding`);
    }
  }

  return {
    pixels,
    sha256: sha256(pixels),
    normalPalette,
    invertedPalette,
  };
}

/** Parse and validate the two exact LVGL bitmap declarations from art.c text. */
export function extractNiceViewArt(source) {
  if (typeof source !== "string") throw new TypeError("art.c source must be a string");
  if (PALETTE_BYTES + PIXEL_BYTES !== DATA_SIZE_BYTES) {
    throw new Error("Generator constants do not agree with the LVGL data size");
  }

  const bitmaps = new Map();
  for (const name of ART_NAMES) {
    const descriptor = descriptorFor(source, name);
    if (
      descriptor.width !== ART_WIDTH ||
      descriptor.height !== ART_HEIGHT ||
      descriptor.dataSize !== DATA_SIZE_BYTES
    ) {
      throw new Error(
        `${name} descriptor is ${descriptor.width}x${descriptor.height}/${descriptor.dataSize} bytes; ` +
          `expected ${ART_WIDTH}x${ART_HEIGHT}/${DATA_SIZE_BYTES}`,
      );
    }
    bitmaps.set(name, bitmapFor(source, name));
  }

  return {
    width: ART_WIDTH,
    height: ART_HEIGHT,
    rowStrideBytes: ROW_STRIDE_BYTES,
    pixelBytes: PIXEL_BYTES,
    paletteBytes: PALETTE_BYTES,
    dataSizeBytes: DATA_SIZE_BYTES,
    bitOrder: BIT_ORDER,
    bitmaps,
  };
}

function cljsString(value) {
  return JSON.stringify(value);
}

function wrappedHexExpression(buffer) {
  const hex = buffer.toString("hex");
  const chunks = [];
  for (let offset = 0; offset < hex.length; offset += 96) {
    chunks.push(hex.slice(offset, offset + 96));
  }
  if (chunks.length === 1) return cljsString(chunks[0]);
  return `(str\n${chunks.map((chunk) => `    ${cljsString(chunk)}`).join("\n")})`;
}

/** Render the generated namespace. The output is stable for identical input. */
export function renderNiceViewNamespace(art, provenance) {
  const artSource = provenance.files.find((entry) => entry.path === ART_SOURCE_PATH);
  if (!artSource) throw new Error(`Verified provenance does not include ${ART_SOURCE_PATH}`);

  return `${[
    ";; This file is generated by scripts/generate-zmk-display-assets.mjs.",
    ";; Do not edit it by hand; edit the pinned evidence or generator instead.",
    "(ns pretypist.scene.generated.nice-view-art)",
    "",
    `(def source-repository ${cljsString(provenance.upstream.repository)})`,
    `(def source-revision ${cljsString(provenance.upstream.tag)})`,
    `(def source-commit ${cljsString(provenance.upstream.commit)})`,
    `(def source-license ${cljsString(provenance.upstream.license)})`,
    `(def source-file ${cljsString(ART_SOURCE_PATH)})`,
    `(def source-sha256 ${cljsString(artSource.sha256)})`,
    "",
    `(def width ${art.width})`,
    `(def height ${art.height})`,
    `(def row-stride-bytes ${art.rowStrideBytes})`,
    `(def pixel-bytes ${art.pixelBytes})`,
    `(def palette-bytes ${art.paletteBytes})`,
    `(def data-size-bytes ${art.dataSizeBytes})`,
    `(def bit-order :${art.bitOrder})`,
    "",
    "(def bitmap-sha256",
    "  {:balloon",
    `   ${cljsString(art.bitmaps.get("balloon").sha256)}`,
    "   :mountain",
    `   ${cljsString(art.bitmaps.get("mountain").sha256)}})`,
    "",
    "(def bitmaps",
    `  {:balloon ${wrappedHexExpression(art.bitmaps.get("balloon").pixels)}`,
    `   :mountain ${wrappedHexExpression(art.bitmaps.get("mountain").pixels)}})`,
    "",
  ].join("\n")}\n`;
}

/** Verify the evidence, generate the namespace, and optionally check it in place. */
export function generateDisplayAssets({
  displayDirectory = DEFAULT_DISPLAY_DIRECTORY,
  output = DEFAULT_OUTPUT,
  revision = "v0.3",
  checkOnly = false,
} = {}) {
  const report = verifyDisplayEvidence({ displayDirectory, revision });
  const provenance = KNOWN_REVISIONS.get(revision);
  if (!provenance || provenance.upstream.commit !== report.commit) {
    throw new Error("Verified ZMK report and provenance revision disagree");
  }
  const sourcePath = path.join(report.vendorRoot, ...ART_SOURCE_PATH.split("/"));
  const art = extractNiceViewArt(fs.readFileSync(sourcePath, "utf8"));
  const generated = renderNiceViewNamespace(art, provenance);
  const resolvedOutput = path.resolve(output);

  if (checkOnly) {
    if (!fs.existsSync(resolvedOutput)) {
      throw new Error(`Generated nice!view namespace is missing: ${resolvedOutput}`);
    }
    const existing = fs.readFileSync(resolvedOutput, "utf8");
    if (existing !== generated) {
      throw new Error(
        `Generated nice!view namespace is stale: run node scripts/generate-zmk-display-assets.mjs`,
      );
    }
  } else {
    fs.mkdirSync(path.dirname(resolvedOutput), { recursive: true });
    const existing = fs.existsSync(resolvedOutput) ? fs.readFileSync(resolvedOutput, "utf8") : undefined;
    if (existing !== generated) fs.writeFileSync(resolvedOutput, generated, "utf8");
  }

  return {
    output: resolvedOutput,
    revision,
    commit: report.commit,
    bytes: Buffer.byteLength(generated),
    bitmapBytes: PIXEL_BYTES,
    checkOnly,
    ok: true,
  };
}

function parseArguments(arguments_) {
  const options = {
    displayDirectory: DEFAULT_DISPLAY_DIRECTORY,
    output: DEFAULT_OUTPUT,
    revision: "v0.3",
    checkOnly: false,
  };
  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index];
    if (argument === "--check") {
      options.checkOnly = true;
    } else if (argument === "--display-directory") {
      if (!arguments_[index + 1]) throw new Error("--display-directory requires a path");
      options.displayDirectory = path.resolve(arguments_[index + 1]);
      index += 1;
    } else if (argument === "--output") {
      if (!arguments_[index + 1]) throw new Error("--output requires a path");
      options.output = path.resolve(arguments_[index + 1]);
      index += 1;
    } else if (argument === "--revision") {
      if (!arguments_[index + 1]) throw new Error("--revision requires a tag");
      options.revision = arguments_[index + 1];
      index += 1;
    } else if (argument === "--help" || argument === "-h") {
      process.stdout.write(
        [
          "Usage: node scripts/generate-zmk-display-assets.mjs [options]",
          "",
          "Options:",
          "  --check                    Fail if the committed generated namespace is stale",
          "  --display-directory DIR    Alternate pinned evidence directory",
          "  --output FILE              Alternate generated namespace path",
          "  --revision TAG             Reviewed ZMK revision (default v0.3)",
          "  -h, --help                  Show this help",
          "",
        ].join("\n"),
      );
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }
  return options;
}

function main() {
  const result = generateDisplayAssets(parseArguments(process.argv.slice(2)));
  const verb = result.checkOnly ? "Verified" : "Generated";
  process.stdout.write(
    `${verb} exact ZMK ${result.revision} nice!view art: 2 × ${result.bitmapBytes} packed bytes -> ${result.output}\n`,
  );
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`nice!view art generation failed: ${error.message}\n`);
    process.exitCode = 1;
  }
}
