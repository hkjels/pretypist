import { createReadStream, existsSync, statSync } from "node:fs";
import { createServer } from "node:http";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../resources/public/", import.meta.url));
const port = Number(process.argv[2] || 4173);
const base = process.argv[3] ?? "/";
if (!/^\/(?:[a-z0-9_-]+\/)*$/i.test(base)) throw new Error("Use a base path such as /pretypist/.");
const mime = {
  ".css": "text/css; charset=utf-8",
  ".edn": "application/edn; charset=utf-8",
  ".glb": "model/gltf-binary",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".map": "application/json",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".pretypist": "application/vnd.pretypist+zip",
  ".webp": "image/webp",
};

createServer((request, response) => {
  const { pathname, search } = new URL(request.url, "http://localhost");
  if (base !== "/" && pathname === base.slice(0, -1)) {
    response.writeHead(308, { location: base + search }).end();
    return;
  }
  if (!pathname.startsWith(base)) {
    response.writeHead(404).end("Not found");
    return;
  }
  let rawPath;
  try {
    rawPath = `/${decodeURIComponent(pathname.slice(base.length))}`;
  } catch {
    response.writeHead(400).end("Invalid path");
    return;
  }
  const safePath = normalize(rawPath).replace(/^(\.\.(\/|\\|$))+/, "");
  let file = join(root, safePath === "/" ? "index.html" : safePath);
  if (!file.startsWith(root)) {
    response.writeHead(403).end("Forbidden");
    return;
  }
  if (existsSync(file) && statSync(file).isDirectory()) file = join(file, "index.html");
  if (!existsSync(file)) {
    response.writeHead(404).end("Not found");
    return;
  }
  response.writeHead(200, {
    "content-type": mime[extname(file)] || "application/octet-stream",
    "cache-control": "no-store",
  });
  createReadStream(file).pipe(response);
}).listen(port, "127.0.0.1", () => {
  process.stdout.write(`PRETYPIST preview: http://127.0.0.1:${port}${base}\n`);
});
