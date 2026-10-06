#!/usr/bin/env node

/**
 * Build a deterministic .pretypist archive using only Node.js built-ins.
 *
 * ZIP entries have a fixed timestamp, stable order, stable permissions, no
 * comments, and deterministic raw-DEFLATE settings. Running this script twice
 * over identical sources therefore produces byte-for-byte identical output.
 */

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(SCRIPT_DIRECTORY, "..");
const DEFAULT_SOURCE = path.join(PROJECT_ROOT, "resources/layout-sources/modified-engram");
const DEFAULT_OUTPUT = path.join(
  PROJECT_ROOT,
  "resources/public/layouts/modified-engram.pretypist",
);

const REQUIRED_FILES = ["layout.edn", "model.glb"];
const OPTIONAL_FILES = ["preview.webp", "README.md"];
const CANONICAL_FILES = [...REQUIRED_FILES, ...OPTIONAL_FILES];
const FIXED_DOS_DATE = 0x0021; // 1980-01-01
const FIXED_DOS_TIME = 0;
const MAX_LAYOUT_BYTES = 1024 * 1024;
const MAX_MODEL_BYTES = 30 * 1024 * 1024;
const MAX_UNCOMPRESSED_ARCHIVE_BYTES = 60 * 1024 * 1024;
const MAX_COMPRESSED_ARCHIVE_BYTES = 40 * 1024 * 1024;
const GLB_MAGIC = 0x46546c67;
const GLB_VERSION = 2;
const JSON_CHUNK_TYPE = 0x4e4f534a;
const BIN_CHUNK_TYPE = 0x004e4942;

function parseArguments(arguments_) {
  const options = { source: DEFAULT_SOURCE, output: DEFAULT_OUTPUT, checkOnly: false };

  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index];
    if (argument === "--source") {
      const value = arguments_[index + 1];
      if (!value) throw new Error("--source requires a directory path");
      options.source = path.resolve(value);
      index += 1;
    } else if (argument === "--output") {
      const value = arguments_[index + 1];
      if (!value) throw new Error("--output requires a file path");
      options.output = path.resolve(value);
      index += 1;
    } else if (argument === "--check") {
      options.checkOnly = true;
    } else if (argument === "--help" || argument === "-h") {
      process.stdout.write(
        "Usage: node scripts/package-layout.mjs [--source DIR] [--output FILE] [--check]\n",
      );
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }

  return options;
}

function makeCrc32Table() {
  const table = new Uint32Array(256);
  for (let index = 0; index < table.length; index += 1) {
    let value = index;
    for (let bit = 0; bit < 8; bit += 1) {
      value = (value & 1) !== 0 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
    }
    table[index] = value >>> 0;
  }
  return table;
}

const CRC32_TABLE = makeCrc32Table();

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc = CRC32_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function validateSelfContainedModel(model) {
  if (model.length < 28) throw new Error("model.glb is shorter than a complete GLB header");
  if (model.readUInt32LE(0) !== GLB_MAGIC) throw new Error("model.glb has invalid GLB magic");
  if (model.readUInt32LE(4) !== GLB_VERSION) throw new Error("model.glb is not GLB version 2");
  if (model.readUInt32LE(8) !== model.length) {
    throw new Error("model.glb length header does not match its file size");
  }
  const jsonLength = model.readUInt32LE(12);
  const jsonEnd = 20 + jsonLength;
  if (model.readUInt32LE(16) !== JSON_CHUNK_TYPE || jsonEnd + 8 > model.length) {
    throw new Error("model.glb does not begin with a complete JSON chunk");
  }
  if (model.readUInt32LE(jsonEnd + 4) !== BIN_CHUNK_TYPE) {
    throw new Error("model.glb does not contain an embedded binary chunk");
  }
  const binaryLength = model.readUInt32LE(jsonEnd);
  if (jsonEnd + 8 + binaryLength !== model.length) {
    throw new Error("model.glb binary chunk length does not match its file size");
  }

  let gltf;
  try {
    gltf = JSON.parse(model.subarray(20, jsonEnd).toString("utf8").trim());
  } catch (error) {
    throw new Error(`model.glb contains invalid glTF JSON: ${error.message}`);
  }
  if (gltf.asset?.version !== "2.0") throw new Error("model.glb does not declare glTF 2.0");
  if (!Array.isArray(gltf.buffers) || gltf.buffers.length !== 1) {
    throw new Error("model.glb must contain exactly one embedded binary buffer");
  }
  if (gltf.buffers.some((buffer) => "uri" in buffer)) {
    throw new Error("model.glb contains an external buffer URI");
  }
  if ((gltf.images ?? []).some((image) => "uri" in image)) {
    throw new Error("model.glb contains an image URI; textures must use embedded buffer views");
  }
}

function validateSources(sourceDirectory) {
  if (!fs.existsSync(sourceDirectory) || !fs.statSync(sourceDirectory).isDirectory()) {
    throw new Error(`Layout source directory does not exist: ${sourceDirectory}`);
  }

  for (const filename of REQUIRED_FILES) {
    const filepath = path.join(sourceDirectory, filename);
    if (!fs.existsSync(filepath) || !fs.statSync(filepath).isFile()) {
      throw new Error(`Required layout package source is missing: ${filename}`);
    }
  }

  const layoutSize = fs.statSync(path.join(sourceDirectory, "layout.edn")).size;
  const modelSize = fs.statSync(path.join(sourceDirectory, "model.glb")).size;
  if (layoutSize > MAX_LAYOUT_BYTES) {
    throw new Error(`layout.edn exceeds the 1 MiB package limit (${layoutSize} bytes)`);
  }
  if (modelSize > MAX_MODEL_BYTES) {
    throw new Error(`model.glb exceeds the 30 MiB package limit (${modelSize} bytes)`);
  }

  const entries = CANONICAL_FILES.filter((filename) =>
    fs.existsSync(path.join(sourceDirectory, filename)),
  ).map((filename) => {
    const filepath = path.join(sourceDirectory, filename);
    const stat = fs.statSync(filepath);
    if (!stat.isFile()) throw new Error(`Package entry is not a regular file: ${filename}`);
    return { filename, contents: fs.readFileSync(filepath) };
  });

  const uncompressedSize = entries.reduce((total, entry) => total + entry.contents.length, 0);
  if (uncompressedSize > MAX_UNCOMPRESSED_ARCHIVE_BYTES) {
    throw new Error(`Package exceeds the 60 MiB uncompressed limit (${uncompressedSize} bytes)`);
  }
  validateSelfContainedModel(entries.find((entry) => entry.filename === "model.glb").contents);
  return entries;
}

function localHeader(entry) {
  const filename = Buffer.from(entry.filename, "utf8");
  const header = Buffer.alloc(30);
  header.writeUInt32LE(0x04034b50, 0);
  header.writeUInt16LE(20, 4); // version needed: ZIP 2.0
  header.writeUInt16LE(0x0800, 6); // UTF-8 names, no data descriptor
  header.writeUInt16LE(entry.method, 8);
  header.writeUInt16LE(FIXED_DOS_TIME, 10);
  header.writeUInt16LE(FIXED_DOS_DATE, 12);
  header.writeUInt32LE(entry.crc, 14);
  header.writeUInt32LE(entry.compressed.length, 18);
  header.writeUInt32LE(entry.contents.length, 22);
  header.writeUInt16LE(filename.length, 26);
  header.writeUInt16LE(0, 28);
  return Buffer.concat([header, filename]);
}

function centralHeader(entry) {
  const filename = Buffer.from(entry.filename, "utf8");
  const header = Buffer.alloc(46);
  header.writeUInt32LE(0x02014b50, 0);
  header.writeUInt16LE(0x0314, 4); // made by Unix, ZIP 2.0
  header.writeUInt16LE(20, 6);
  header.writeUInt16LE(0x0800, 8);
  header.writeUInt16LE(entry.method, 10);
  header.writeUInt16LE(FIXED_DOS_TIME, 12);
  header.writeUInt16LE(FIXED_DOS_DATE, 14);
  header.writeUInt32LE(entry.crc, 16);
  header.writeUInt32LE(entry.compressed.length, 20);
  header.writeUInt32LE(entry.contents.length, 24);
  header.writeUInt16LE(filename.length, 28);
  header.writeUInt16LE(0, 30);
  header.writeUInt16LE(0, 32);
  header.writeUInt16LE(0, 34);
  header.writeUInt16LE(0, 36);
  header.writeUInt32LE((0o100644 << 16) >>> 0, 38);
  header.writeUInt32LE(entry.offset, 42);
  return Buffer.concat([header, filename]);
}

function endOfCentralDirectory(entryCount, centralSize, centralOffset) {
  const record = Buffer.alloc(22);
  record.writeUInt32LE(0x06054b50, 0);
  record.writeUInt16LE(0, 4);
  record.writeUInt16LE(0, 6);
  record.writeUInt16LE(entryCount, 8);
  record.writeUInt16LE(entryCount, 10);
  record.writeUInt32LE(centralSize, 12);
  record.writeUInt32LE(centralOffset, 16);
  record.writeUInt16LE(0, 20);
  return record;
}

function createZip(sourceEntries) {
  const entries = sourceEntries.map((entry) => {
    // DEFLATE text, but store the already compact binary GLB and WebP verbatim.
    const method = /\.(?:glb|webp)$/.test(entry.filename) ? 0 : 8;
    return {
      ...entry,
      method,
      crc: crc32(entry.contents),
      compressed:
        method === 0
          ? entry.contents
          : zlib.deflateRawSync(entry.contents, { level: 9, strategy: zlib.constants.Z_DEFAULT_STRATEGY }),
    };
  });

  const localParts = [];
  let offset = 0;
  for (const entry of entries) {
    entry.offset = offset;
    const header = localHeader(entry);
    localParts.push(header, entry.compressed);
    offset += header.length + entry.compressed.length;
  }

  const centralParts = entries.map(centralHeader);
  const central = Buffer.concat(centralParts);
  return Buffer.concat([
    ...localParts,
    central,
    endOfCentralDirectory(entries.length, central.length, offset),
  ]);
}

function inspectZip(zip) {
  if (zip.length > MAX_COMPRESSED_ARCHIVE_BYTES) {
    throw new Error(`Package exceeds the 40 MiB compressed limit (${zip.length} bytes)`);
  }
  const entries = [];
  let offset = 0;
  while (offset + 4 <= zip.length && zip.readUInt32LE(offset) === 0x04034b50) {
    if (offset + 30 > zip.length) throw new Error("ZIP local header is truncated");
    const flags = zip.readUInt16LE(offset + 6);
    const method = zip.readUInt16LE(offset + 8);
    const expectedCrc = zip.readUInt32LE(offset + 14);
    const compressedSize = zip.readUInt32LE(offset + 18);
    const uncompressedSize = zip.readUInt32LE(offset + 22);
    const filenameLength = zip.readUInt16LE(offset + 26);
    const extraLength = zip.readUInt16LE(offset + 28);
    if ((flags & 0x0008) !== 0) throw new Error("ZIP data descriptors are not supported by --check");
    const nameStart = offset + 30;
    const dataStart = nameStart + filenameLength + extraLength;
    const dataEnd = dataStart + compressedSize;
    if (dataEnd > zip.length) throw new Error("ZIP entry data is truncated");

    const filename = zip.subarray(nameStart, nameStart + filenameLength).toString("utf8");
    if (filename === "layout.edn" && uncompressedSize > MAX_LAYOUT_BYTES) {
      throw new Error(`layout.edn exceeds the 1 MiB package limit (${uncompressedSize} bytes)`);
    }
    if (filename === "model.glb" && uncompressedSize > MAX_MODEL_BYTES) {
      throw new Error(`model.glb exceeds the 30 MiB package limit (${uncompressedSize} bytes)`);
    }
    const compressed = zip.subarray(dataStart, dataEnd);
    const contents =
      method === 0
        ? compressed
        : method === 8
          ? zlib.inflateRawSync(compressed, { maxOutputLength: MAX_UNCOMPRESSED_ARCHIVE_BYTES })
          : (() => {
              throw new Error(`Unsupported ZIP compression method ${method} for ${filename}`);
            })();
    if (contents.length !== uncompressedSize) {
      throw new Error(`Uncompressed size is incorrect for ${filename}`);
    }
    if (crc32(contents) !== expectedCrc) throw new Error(`CRC-32 is incorrect for ${filename}`);
    entries.push({ filename, contents, compressedSize, uncompressedSize });
    offset = dataEnd;
  }
  if (entries.length === 0 || offset + 4 > zip.length || zip.readUInt32LE(offset) !== 0x02014b50) {
    throw new Error("ZIP central directory is missing");
  }
  const totalCompressedSize = entries.reduce((total, entry) => total + entry.compressedSize, 0);
  const totalUncompressedSize = entries.reduce((total, entry) => total + entry.uncompressedSize, 0);
  if (totalCompressedSize > MAX_COMPRESSED_ARCHIVE_BYTES) {
    throw new Error(`Package entries exceed the 40 MiB compressed limit (${totalCompressedSize} bytes)`);
  }
  if (totalUncompressedSize > MAX_UNCOMPRESSED_ARCHIVE_BYTES) {
    throw new Error(`Package exceeds the 60 MiB uncompressed limit (${totalUncompressedSize} bytes)`);
  }
  return entries;
}

const options = parseArguments(process.argv.slice(2));

if (options.checkOnly) {
  const archive = fs.readFileSync(options.output);
  const entries = inspectZip(archive);
  const names = entries.map((entry) => entry.filename);
  const duplicates = names.filter((name, index) => names.indexOf(name) !== index);
  if (duplicates.length > 0) {
    throw new Error(`Archive contains duplicate root files: ${[...new Set(duplicates)].join(", ")}`);
  }
  for (const required of REQUIRED_FILES) {
    if (!names.includes(required)) throw new Error(`Archive is missing required root file: ${required}`);
  }
  const unexpected = names.filter((name) => !CANONICAL_FILES.includes(name));
  if (unexpected.length > 0) {
    throw new Error(`Archive contains unexpected root files: ${unexpected.join(", ")}`);
  }
  if (names.some((name) => name.includes("/") || name.includes("\\"))) {
    throw new Error("Archive contains a non-root or unsafe path");
  }
  validateSelfContainedModel(entries.find((entry) => entry.filename === "model.glb").contents);
  process.stdout.write(
    `Validated ${path.relative(PROJECT_ROOT, options.output)} (${archive.length} bytes; ${names.join(", ")})\n`,
  );
} else {
  const entries = validateSources(options.source);
  const archive = createZip(entries);
  if (archive.length > MAX_COMPRESSED_ARCHIVE_BYTES) {
    throw new Error(`Package exceeds the 40 MiB compressed limit (${archive.length} bytes)`);
  }
  fs.mkdirSync(path.dirname(options.output), { recursive: true });
  fs.writeFileSync(options.output, archive);
  process.stdout.write(
    `Packaged ${path.relative(PROJECT_ROOT, options.output)} (${archive.length} bytes; ` +
      `${entries.map((entry) => entry.filename).join(", ")})\n`,
  );
}
