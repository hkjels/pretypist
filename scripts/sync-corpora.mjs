#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceDirectory = path.join(projectRoot, "resources/corpora");
const publicDirectory = path.join(projectRoot, "resources/public/corpora");

fs.mkdirSync(publicDirectory, { recursive: true });
for (const filename of ["en.edn", "nb.edn", "metadata.edn"]) {
  const source = path.join(sourceDirectory, filename);
  const destination = path.join(publicDirectory, filename);
  if (!fs.existsSync(source)) throw new Error(`Missing corpus source: ${filename}`);
  fs.copyFileSync(source, destination);
}

process.stdout.write("Synced English, Norwegian, and generated corpus metadata into the public build.\n");
