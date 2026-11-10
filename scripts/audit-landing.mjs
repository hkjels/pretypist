import { readFile, stat } from "node:fs/promises";
import { gzipSync } from "node:zlib";

export const budgets = Object.freeze({
  javascriptGzipBytes: 420 * 1024,
  // Revision 14 caps the complete model at 50k triangles and bakes case normals.
  modelBytes: 1.5 * 1024 * 1024,
  packageBytes: 0.75 * 1024 * 1024,
});

const artifacts = {
  javascript: "resources/public/js/app.js",
  model: "resources/layout-sources/modified-engram/model.glb",
  package: "resources/public/layouts/modified-engram.pretypist",
};

const javascript = await readFile(artifacts.javascript);
const measured = {
  javascriptGzipBytes: gzipSync(javascript, { level: 9 }).byteLength,
  modelBytes: (await stat(artifacts.model)).size,
  packageBytes: (await stat(artifacts.package)).size,
};

const failures = Object.entries(budgets).filter(
  ([name, maximum]) => measured[name] > maximum,
);

for (const [name, maximum] of Object.entries(budgets)) {
  const actual = measured[name];
  console.log(`${name}: ${actual} / ${maximum} bytes`);
}

if (failures.length > 0) {
  const summary = failures
    .map(([name, maximum]) => `${name} is ${measured[name] - maximum} bytes over budget`)
    .join("; ");
  throw new Error(`Landing performance budget exceeded: ${summary}`);
}
