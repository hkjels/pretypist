#!/usr/bin/env node

/**
 * Verify the vendored ZMK nice!view v0.3 authoring evidence.
 *
 * This tool is deliberately read-only. It never compiles or executes the C
 * sources and never rewrites application data. The stock widget source is
 * treated as untrusted text: bytes are checked against a single known ZMK
 * revision, then a small allowlist of visual constants is extracted.
 */

import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { isDeepStrictEqual } from "node:util";
import { fileURLToPath } from "node:url";

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(SCRIPT_DIRECTORY, "..");
export const DEFAULT_DISPLAY_DIRECTORY = path.join(
  PROJECT_ROOT,
  "resources/layout-sources/modified-engram/display",
);
const PROVENANCE_FILENAME = "provenance.json";
const MAX_PROVENANCE_BYTES = 64 * 1024;

const ZMK_V03_FILES = [
  {
    path: "LICENSE",
    bytes: 1077,
    sha256: "05e785a4c222b9213b452f1a2cd8b3f8ce95a56c1b77a7ca8525d0f7eb7f77b1",
  },
  {
    path: "app/boards/shields/nice_view/nice_view.overlay",
    bytes: 364,
    sha256: "39a817d626476202fb711fec1174480944bdfea9aaca0b88d633bedc4ac3e9bf",
  },
  {
    path: "app/boards/shields/nice_view/widgets/art.c",
    bytes: 17558,
    sha256: "e80761d7485091c01c3ec50eff1909787841fb4319dfc3936ef8db8bf7c1f537",
  },
  {
    path: "app/boards/shields/nice_view/widgets/bolt.c",
    bytes: 1342,
    sha256: "08ac2f43ebaa6268fd8ec593088f0a40a3fa9680be694de1f17d3b39238faf7e",
  },
  {
    path: "app/boards/shields/nice_view/widgets/peripheral_status.c",
    bytes: 4448,
    sha256: "ef338cec4f678204bfaf9f991912dc7be1ea9a7f62977c477fe8cc90733d3176",
  },
  {
    path: "app/boards/shields/nice_view/widgets/peripheral_status.h",
    bytes: 465,
    sha256: "dad22d31a7ece3f902e3665921264128c3d68f7fffa3d765ef772fc2a06d4883",
  },
  {
    path: "app/boards/shields/nice_view/widgets/status.c",
    bytes: 12950,
    sha256: "ef1fe00650dec58be351642bb7eaeddf370fb93c380930918da11ba0f20b3d90",
  },
  {
    path: "app/boards/shields/nice_view/widgets/status.h",
    bytes: 563,
    sha256: "82755d28d7c099239c40242c3c3dd1aa7233d962fa049379a2417e3ace1fa3dd",
  },
  {
    path: "app/boards/shields/nice_view/widgets/util.c",
    bytes: 2220,
    sha256: "acfe5b323a990a2330a5d6c5fb6315416f22842f9c636d7b140f9e3bf41152b5",
  },
  {
    path: "app/boards/shields/nice_view/widgets/util.h",
    bytes: 1696,
    sha256: "45d9667119029a82d721527fbd12e79eedbd23c3048bb8c4e0af056c63384c38",
  },
];

export const ZMK_V03_SEMANTICS = {
  panelCompatible: "sharp,ls0xx",
  panelPixels: { width: 160, height: 68 },
  spiMaxFrequencyHz: 1_000_000,
  centralWidgetPixels: { width: 160, height: 68 },
  canvasSizePixels: 68,
  canvasRotationTenthsDegrees: 900,
  centralCanvasPlacements: [
    { canvas: "top", align: "LV_ALIGN_TOP_RIGHT", x: 0, y: 0 },
    { canvas: "middle", align: "LV_ALIGN_TOP_LEFT", x: 24, y: 0 },
    { canvas: "bottom", align: "LV_ALIGN_TOP_LEFT", x: -44, y: 0 },
  ],
  profileCount: 5,
  profileCentersPixels: [
    [13, 13],
    [55, 13],
    [34, 34],
    [13, 55],
    [55, 55],
  ],
  wpmFramePixels: {
    outer: [0, 21, 68, 42],
    inner: [1, 22, 66, 40],
  },
  layerLabelPixels: { x: 0, y: 5, width: 68 },
  batteryFramePixels: {
    outer: [0, 2, 29, 12],
    inner: [1, 3, 27, 10],
    terminalOuter: [30, 5, 3, 6],
    terminalInner: [31, 6, 1, 4],
  },
  chargingIconPixels: { width: 11, height: 18, dataBytes: 70 },
  rotatedCanvasCount: 3,
  statusCanvasBufferCount: 3,
  peripheralWidgetPixels: { width: 160, height: 68 },
  peripheralCanvasPlacement: { canvas: "top", align: "LV_ALIGN_TOP_RIGHT", x: 0, y: 0 },
  peripheralArtPlacement: { align: "LV_ALIGN_TOP_LEFT", x: 0, y: 0 },
  peripheralArtPixels: {
    balloon: { width: 140, height: 68, dataBytes: 1232 },
    mountain: { width: 140, height: 68, dataBytes: 1232 },
  },
  peripheralArtSelection: "random-low-bit:balloon-or-mountain",
  peripheralCanvasBufferCount: 1,
};

const ZMK_V03 = {
  schemaVersion: 1,
  artifact: "stock-zmk-nice-view-authoring-evidence",
  vendorRoot: "vendor/zmk-v0.3",
  upstream: {
    repository: "https://github.com/zmkfirmware/zmk",
    tag: "v0.3",
    tagObject: "3cf9006a9377f21c99e944e29e2a507d8c3e9069",
    commit: "edf5c0814fd3ea202e43aad2d68fd32e882a518c",
    license: "MIT",
  },
  files: ZMK_V03_FILES,
  semantics: ZMK_V03_SEMANTICS,
};

export const KNOWN_REVISIONS = new Map([["v0.3", ZMK_V03]]);

function sha256(buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex");
}

function parseArguments(arguments_) {
  const options = {
    displayDirectory: DEFAULT_DISPLAY_DIRECTORY,
    revision: "v0.3",
    zmkRoot: undefined,
    json: false,
  };

  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index];
    if (argument === "--check") {
      // Verification is always read-only; this flag makes that intent explicit
      // and mirrors the repository's other deterministic asset tools.
      continue;
    }
    if (argument === "--zmk-root") {
      const value = arguments_[index + 1];
      if (!value) throw new Error("--zmk-root requires a directory");
      options.zmkRoot = path.resolve(value);
      index += 1;
      continue;
    }
    if (argument === "--display-directory") {
      const value = arguments_[index + 1];
      if (!value) throw new Error("--display-directory requires a directory");
      options.displayDirectory = path.resolve(value);
      index += 1;
      continue;
    }
    if (argument === "--revision") {
      const value = arguments_[index + 1];
      if (!value) throw new Error("--revision requires a tag");
      options.revision = value;
      index += 1;
      continue;
    }
    if (argument === "--json") {
      options.json = true;
      continue;
    }
    if (argument === "--help" || argument === "-h") {
      process.stdout.write(
        [
          "Usage: node scripts/verify-zmk-display.mjs [options]",
          "",
          "Options:",
          "  --check                    Verify without writing (the default and only mode)",
          "  --zmk-root DIR             Also compare an explicit ZMK checkout",
          "  --display-directory DIR    Alternate committed evidence directory (tests/tools)",
          "  --revision TAG             Known revision to verify (default v0.3)",
          "  --json                      Emit a machine-readable report",
          "  -h, --help                  Show this help",
          "",
          "The verifier never compiles C, copies files, or updates layout.edn.",
          "",
        ].join("\n"),
      );
      process.exit(0);
    }
    throw new Error(`Unknown option: ${argument}`);
  }
  return options;
}

function assertKnownRevision(revision) {
  const known = KNOWN_REVISIONS.get(revision);
  if (!known) {
    throw new Error(
      `Unknown ZMK nice!view revision ${JSON.stringify(revision)}; allowed revisions: ${[
        ...KNOWN_REVISIONS.keys(),
      ].join(", ")}`,
    );
  }
  return known;
}

function resolveContainedFile(root, relativePath) {
  if (path.isAbsolute(relativePath) || relativePath.split("/").includes("..")) {
    throw new Error(`Non-allowlisted source path: ${relativePath}`);
  }
  const resolvedRoot = fs.realpathSync(root);
  const candidate = path.resolve(resolvedRoot, ...relativePath.split("/"));
  if (!candidate.startsWith(`${resolvedRoot}${path.sep}`)) {
    throw new Error(`Source path escapes its root: ${relativePath}`);
  }
  const stat = fs.lstatSync(candidate);
  if (stat.isSymbolicLink()) throw new Error(`Source file must not be a symbolic link: ${relativePath}`);
  if (!stat.isFile()) throw new Error(`Source path is not a regular file: ${relativePath}`);
  const real = fs.realpathSync(candidate);
  if (!real.startsWith(`${resolvedRoot}${path.sep}`)) {
    throw new Error(`Source file resolves outside its root: ${relativePath}`);
  }
  return real;
}

function readProvenance(displayDirectory) {
  const filename = path.join(displayDirectory, PROVENANCE_FILENAME);
  const stat = fs.lstatSync(filename);
  if (stat.isSymbolicLink() || !stat.isFile()) {
    throw new Error(`${PROVENANCE_FILENAME} must be a regular file, not a symlink`);
  }
  if (stat.size > MAX_PROVENANCE_BYTES) {
    throw new Error(`${PROVENANCE_FILENAME} exceeds ${MAX_PROVENANCE_BYTES} bytes`);
  }
  const contents = fs.readFileSync(filename);
  let provenance;
  try {
    provenance = JSON.parse(contents.toString("utf8"));
  } catch (error) {
    throw new Error(`${PROVENANCE_FILENAME} is not valid JSON: ${error.message}`);
  }
  return { provenance, sha256: sha256(contents) };
}

function captureInteger(source, expression, description) {
  const match = source.match(expression);
  if (!match) throw new Error(`Missing nice!view semantic marker: ${description}`);
  return Number(match[1]);
}

function captureRect(source, expression, description) {
  const match = source.match(expression);
  if (!match) throw new Error(`Missing nice!view semantic marker: ${description}`);
  return match.slice(1).map(Number);
}

function capturePlacement(source, canvas) {
  const expression = new RegExp(
    `lv_obj_align\\(\\s*${canvas}\\s*,\\s*(LV_ALIGN_[A-Z_]+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)`,
    "u",
  );
  const match = source.match(expression);
  if (!match) throw new Error(`Missing nice!view semantic marker: ${canvas} canvas placement`);
  return { canvas, align: match[1], x: Number(match[2]), y: Number(match[3]) };
}

/** Extract the stock visual constants without preprocessing or executing C. */
export function extractDisplaySemantics(files) {
  const get = (relativePath) => {
    const source = files.get(relativePath);
    if (typeof source !== "string") throw new Error(`Semantic source is unavailable: ${relativePath}`);
    return source;
  };
  const overlay = get("app/boards/shields/nice_view/nice_view.overlay");
  const art = get("app/boards/shields/nice_view/widgets/art.c");
  const bolt = get("app/boards/shields/nice_view/widgets/bolt.c");
  const peripheral = get("app/boards/shields/nice_view/widgets/peripheral_status.c");
  const peripheralHeader = get("app/boards/shields/nice_view/widgets/peripheral_status.h");
  const status = get("app/boards/shields/nice_view/widgets/status.c");
  const statusHeader = get("app/boards/shields/nice_view/widgets/status.h");
  const util = get("app/boards/shields/nice_view/widgets/util.c");
  const utilHeader = get("app/boards/shields/nice_view/widgets/util.h");

  for (const [filename, source] of files) {
    if (filename === "LICENSE") continue;
    if (!/SPDX-License-Identifier:\s*MIT/u.test(source)) {
      throw new Error(`Vendored source lacks its MIT SPDX marker: ${filename}`);
    }
  }
  if (!/MIT License/u.test(get("LICENSE")) || !/Copyright \(c\) 2020 The ZMK Contributors/u.test(get("LICENSE"))) {
    throw new Error("Vendored ZMK LICENSE does not contain the expected MIT notice");
  }
  const compatible = overlay.match(/compatible\s*=\s*"([^"]+)"\s*;/u)?.[1];
  if (compatible !== "sharp,ls0xx") {
    throw new Error("Missing nice!view semantic marker: Sharp LS0XX display binding");
  }
  if (!/lv_img_set_src\(art,\s*random\s*\?\s*&balloon\s*:\s*&mountain\);/u.test(peripheral)) {
    throw new Error("Missing nice!view semantic marker: random balloon/mountain peripheral art");
  }

  function imageDescriptor(name) {
    const block = art.match(new RegExp(`const\\s+lv_img_dsc_t\\s+${name}\\s*=\\s*\\{([\\s\\S]*?)\\};`, "u"))?.[1];
    if (!block) throw new Error(`Missing nice!view semantic marker: ${name} image descriptor`);
    return {
      width: captureInteger(block, /\.header\.w\s*=\s*(\d+)\s*,/u, `${name} width`),
      height: captureInteger(block, /\.header\.h\s*=\s*(\d+)\s*,/u, `${name} height`),
      dataBytes: captureInteger(block, /\.data_size\s*=\s*(\d+)\s*,/u, `${name} byte count`),
    };
  }

  const profileBlock = status.match(
    /int\s+circle_offsets\s*\[NICEVIEW_PROFILE_COUNT\]\[2\]\s*=\s*\{([\s\S]*?)\};/u,
  );
  if (!profileBlock) throw new Error("Missing nice!view semantic marker: profile center table");
  const profileCentersPixels = [...profileBlock[1].matchAll(/\{\s*(-?\d+)\s*,\s*(-?\d+)\s*\}/gu)].map(
    (match) => [Number(match[1]), Number(match[2])],
  );
  const layerLabel = status.match(
    /lv_canvas_draw_text\(canvas,\s*(-?\d+),\s*(-?\d+),\s*(\d+),\s*&label_dsc,\s*state->layer_label\)/u,
  );
  if (!layerLabel) throw new Error("Missing nice!view semantic marker: layer label placement");

  return {
    panelCompatible: compatible,
    panelPixels: {
      width: captureInteger(overlay, /\bwidth\s*=\s*<(\d+)>\s*;/u, "panel width"),
      height: captureInteger(overlay, /\bheight\s*=\s*<(\d+)>\s*;/u, "panel height"),
    },
    spiMaxFrequencyHz: captureInteger(
      overlay,
      /spi-max-frequency\s*=\s*<(\d+)>\s*;/u,
      "SPI maximum frequency",
    ),
    centralWidgetPixels: {
      width: captureInteger(status, /lv_obj_set_size\(widget->obj,\s*(\d+)\s*,\s*\d+\s*\)/u, "central widget width"),
      height: captureInteger(status, /lv_obj_set_size\(widget->obj,\s*\d+\s*,\s*(\d+)\s*\)/u, "central widget height"),
    },
    canvasSizePixels: captureInteger(utilHeader, /#define\s+CANVAS_SIZE\s+(\d+)/u, "canvas size"),
    canvasRotationTenthsDegrees: captureInteger(
      util,
      /lv_canvas_transform\(canvas,\s*&img,\s*(\d+)\s*,/u,
      "canvas rotation",
    ),
    centralCanvasPlacements: ["top", "middle", "bottom"].map((canvas) =>
      capturePlacement(status, canvas),
    ),
    profileCount: captureInteger(
      utilHeader,
      /#define\s+NICEVIEW_PROFILE_COUNT\s+(\d+)/u,
      "Bluetooth profile count",
    ),
    profileCentersPixels,
    wpmFramePixels: {
      outer: captureRect(
        status,
        /lv_canvas_draw_rect\(canvas,\s*(-?\d+),\s*(-?\d+),\s*(\d+),\s*(\d+),\s*&rect_white_dsc\);/u,
        "WPM outer frame",
      ),
      inner: captureRect(
        status,
        /lv_canvas_draw_rect\(canvas,\s*(-?\d+),\s*(-?\d+),\s*(\d+),\s*(\d+),\s*&rect_black_dsc\);/u,
        "WPM inner frame",
      ),
    },
    layerLabelPixels: {
      x: Number(layerLabel[1]),
      y: Number(layerLabel[2]),
      width: Number(layerLabel[3]),
    },
    batteryFramePixels: {
      outer: captureRect(
        util,
        /lv_canvas_draw_rect\(canvas,\s*(-?\d+),\s*(-?\d+),\s*(\d+),\s*(\d+),\s*&rect_white_dsc\);/u,
        "battery outer frame",
      ),
      inner: captureRect(
        util,
        /lv_canvas_draw_rect\(canvas,\s*(-?\d+),\s*(-?\d+),\s*(\d+),\s*(\d+),\s*&rect_black_dsc\);/u,
        "battery inner frame",
      ),
      terminalOuter: captureRect(
        util,
        /lv_canvas_draw_rect\(canvas,\s*(30),\s*(5),\s*(3),\s*(6),\s*&rect_white_dsc\);/u,
        "battery terminal outer frame",
      ),
      terminalInner: captureRect(
        util,
        /lv_canvas_draw_rect\(canvas,\s*(31),\s*(6),\s*(1),\s*(4),\s*&rect_black_dsc\);/u,
        "battery terminal inner frame",
      ),
    },
    chargingIconPixels: {
      width: captureInteger(bolt, /\.header\.w\s*=\s*(\d+)\s*,/u, "charging icon width"),
      height: captureInteger(bolt, /\.header\.h\s*=\s*(\d+)\s*,/u, "charging icon height"),
      dataBytes: captureInteger(bolt, /\.data_size\s*=\s*(\d+)\s*,/u, "charging icon byte count"),
    },
    rotatedCanvasCount: (status.match(/\brotate_canvas\(canvas,\s*cbuf(?:2|3)?\);/gu) ?? []).length,
    statusCanvasBufferCount: (
      statusHeader.match(/lv_color_t\s+cbuf(?:2|3)?\[CANVAS_SIZE\s*\*\s*CANVAS_SIZE\];/gu) ?? []
    ).length,
    peripheralWidgetPixels: {
      width: captureInteger(
        peripheral,
        /lv_obj_set_size\(widget->obj,\s*(\d+)\s*,\s*\d+\s*\)/u,
        "peripheral widget width",
      ),
      height: captureInteger(
        peripheral,
        /lv_obj_set_size\(widget->obj,\s*\d+\s*,\s*(\d+)\s*\)/u,
        "peripheral widget height",
      ),
    },
    peripheralCanvasPlacement: capturePlacement(peripheral, "top"),
    peripheralArtPlacement: (() => {
      const match = peripheral.match(
        /lv_obj_align\(\s*art\s*,\s*(LV_ALIGN_[A-Z_]+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\)/u,
      );
      if (!match) throw new Error("Missing nice!view semantic marker: peripheral art placement");
      return { align: match[1], x: Number(match[2]), y: Number(match[3]) };
    })(),
    peripheralArtPixels: {
      balloon: imageDescriptor("balloon"),
      mountain: imageDescriptor("mountain"),
    },
    peripheralArtSelection: "random-low-bit:balloon-or-mountain",
    peripheralCanvasBufferCount: (
      peripheralHeader.match(/lv_color_t\s+cbuf\[CANVAS_SIZE\s*\*\s*CANVAS_SIZE\];/gu) ?? []
    ).length,
  };
}

function verifyManifest(displayDirectory, known) {
  const { provenance, sha256: provenanceSha256 } = readProvenance(displayDirectory);
  if (!isDeepStrictEqual(provenance, known)) {
    const revision = provenance?.upstream?.tag;
    if (!KNOWN_REVISIONS.has(revision)) {
      throw new Error(`provenance.json names unknown ZMK revision ${JSON.stringify(revision)}`);
    }
    throw new Error("provenance.json does not exactly match the reviewed v0.3 allowlist");
  }
  return { provenance, provenanceSha256 };
}

/** Verify only exact allowlisted files below a source or vendor root. */
export function verifySourceDirectory(root, known = ZMK_V03) {
  if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
    throw new Error(`ZMK evidence root is not a directory: ${root}`);
  }
  const sourceText = new Map();
  const files = [];
  for (const expected of known.files) {
    const filename = resolveContainedFile(root, expected.path);
    const contents = fs.readFileSync(filename);
    const actual = { path: expected.path, bytes: contents.length, sha256: sha256(contents) };
    if (actual.bytes !== expected.bytes) {
      throw new Error(`${expected.path} has ${actual.bytes} bytes; expected ${expected.bytes}`);
    }
    if (actual.sha256 !== expected.sha256) {
      throw new Error(`${expected.path} SHA-256 is ${actual.sha256}; expected ${expected.sha256}`);
    }
    sourceText.set(expected.path, contents.toString("utf8"));
    files.push(actual);
  }
  const semantics = extractDisplaySemantics(sourceText);
  if (!isDeepStrictEqual(semantics, known.semantics)) {
    throw new Error("Vendored bytes passed their hashes but extracted nice!view semantics are unexpected");
  }
  return { root: fs.realpathSync(root), files, semantics };
}

function verifyCheckoutRevision(zmkRoot, known) {
  const result = spawnSync("git", ["-C", zmkRoot, "rev-parse", "HEAD"], {
    encoding: "utf8",
    shell: false,
  });
  if (result.status !== 0) {
    const detail = (result.stderr || result.stdout || "git revision unavailable").trim();
    throw new Error(`--zmk-root must be a Git checkout at ${known.upstream.tag}: ${detail}`);
  }
  const commit = result.stdout.trim();
  if (commit !== known.upstream.commit) {
    throw new Error(
      `Unknown ZMK checkout revision ${commit}; ${known.upstream.tag} resolves to ${known.upstream.commit}`,
    );
  }
  return commit;
}

/** Verify committed evidence and, optionally, an explicit upstream checkout. */
export function verifyDisplayEvidence({
  displayDirectory = DEFAULT_DISPLAY_DIRECTORY,
  revision = "v0.3",
  zmkRoot,
} = {}) {
  const known = assertKnownRevision(revision);
  const resolvedDisplayDirectory = fs.realpathSync(displayDirectory);
  const { provenanceSha256 } = verifyManifest(resolvedDisplayDirectory, known);
  const vendorRoot = path.resolve(resolvedDisplayDirectory, ...known.vendorRoot.split("/"));
  if (!vendorRoot.startsWith(`${resolvedDisplayDirectory}${path.sep}`)) {
    throw new Error("Configured vendor root escapes the display evidence directory");
  }
  const vendor = verifySourceDirectory(vendorRoot, known);
  let checkout;
  if (zmkRoot) {
    const resolvedZmkRoot = fs.realpathSync(zmkRoot);
    const commit = verifyCheckoutRevision(resolvedZmkRoot, known);
    checkout = { commit, ...verifySourceDirectory(resolvedZmkRoot, known) };
  }
  return {
    formatVersion: 1,
    revision,
    commit: known.upstream.commit,
    repository: known.upstream.repository,
    license: known.upstream.license,
    provenanceSha256,
    filesVerified: vendor.files.length,
    semantics: vendor.semantics,
    vendorRoot: vendor.root,
    ...(checkout ? { checkoutRoot: checkout.root, checkoutCommit: checkout.commit } : {}),
    ok: true,
  };
}

function formatReport(report) {
  const lines = [
    `Verified stock ZMK nice!view ${report.revision} authoring evidence`,
    `Commit: ${report.commit}`,
    `License: ${report.license}`,
    `Vendored files: ${report.filesVerified} exact hash matches`,
    `Panel: ${report.semantics.panelPixels.width} × ${report.semantics.panelPixels.height} px; ` +
      `${report.semantics.profileCount} profiles; ${report.semantics.canvasSizePixels}px rotated canvases`,
    `Canvas placements: ${report.semantics.centralCanvasPlacements
      .map((placement) => `${placement.canvas}(${placement.x},${placement.y})`)
      .join(", ")}`,
  ];
  if (report.checkoutRoot) lines.push(`Explicit checkout also matched: ${report.checkoutRoot}`);
  lines.push("No C source was compiled or executed; no files were changed.");
  return `${lines.join("\n")}\n`;
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const report = verifyDisplayEvidence(options);
  process.stdout.write(options.json ? `${JSON.stringify(report, null, 2)}\n` : formatReport(report));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`ZMK display verification failed: ${error.message}\n`);
    process.exitCode = 1;
  }
}
