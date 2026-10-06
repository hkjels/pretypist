import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";

const root = new URL("../resources/public/", import.meta.url);
const assets = ["/", "/css/app.css", "/js/app.js", "/manifest.webmanifest",
  "/corpora/en.edn", "/corpora/nb.edn", "/corpora/metadata.edn",
  "/layouts/modified-engram.pretypist", "/icons/mark.svg", "/icons/app.svg",
  "/icons/favicon-32.png", "/icons/apple-touch-icon.png", "/icons/icon-192.png",
  "/icons/icon-512.png", "/icons/icon-maskable-512.png"];
const template = await readFile(new URL("pwa/service-worker.js", import.meta.url), "utf8");
const hash = createHash("sha256").update(template);
for (const path of assets) {
  hash.update(path).update(await readFile(new URL(path === "/" ? "index.html" : path.slice(1), root)));
}
const version = hash.digest("hex").slice(0, 20);
const source = template.replace("__PRETYPIST_OFFLINE_BUILD__", JSON.stringify({ version, assets }));
if (process.argv.includes("--check")) {
  if (await readFile(new URL("sw.js", root), "utf8") !== source) {
    throw new Error("Offline assets changed. Run npm run build:pwa after bundling the app.");
  }
} else {
  await writeFile(new URL("sw.js", root), source);
}
console.log(`Offline release ${version}: ${assets.length} local assets`);
