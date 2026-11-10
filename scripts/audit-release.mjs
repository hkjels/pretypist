#!/usr/bin/env node

/**
 * Fast, offline checks for the browser shell and authored public assets.
 * Package structure and GLB topology are enforced by their purpose-built
 * companion audits in the `audit:release` npm command.
 */

import { readFile, stat } from "node:fs/promises";

const files = Object.freeze({
  html: "resources/public/index.html",
  css: "resources/public/css/app.css",
  javascript: "resources/public/js/app.js",
});

const budgets = Object.freeze({
  html: 8 * 1024,
  css: 64 * 1024,
  javascript: 2 * 1024 * 1024,
});

const [html, css, manifest] = await Promise.all([
  readFile(files.html, "utf8"),
  readFile(files.css, "utf8"),
  readFile("resources/public/manifest.webmanifest", "utf8").then(JSON.parse),
]);
const sizes = Object.fromEntries(
  await Promise.all(
    Object.entries(files).map(async ([name, filepath]) => [name, (await stat(filepath)).size]),
  ),
);

const failures = [];
for (const [name, maximum] of Object.entries(budgets)) {
  if (sizes[name] > maximum) {
    failures.push(`${name} is ${sizes[name] - maximum} bytes over its ${maximum}-byte budget`);
  }
}

const deployment = new URL("https://example.invalid/pretypist/");
function deployedReference(reference, source) {
  if (typeof reference !== "string" || !reference.trim()
      || /^(?:[a-z][a-z0-9+.-]*:|[\\/])/i.test(reference.trim())) return null;
  const url = new URL(reference, new URL(source, deployment));
  return url.origin === deployment.origin && url.pathname.startsWith(deployment.pathname) ? url.href : null;
}
const references = [...html.matchAll(/\b(?:src|href)="([^"]+)"/g)].map(match => match[1]);
const cssURLs = [...css.matchAll(/url\(\s*(?:"([^"]*)"|'([^']*)'|([^)]*))\s*\)/gi)]
  .map(([, double, single, bare]) => double ?? single ?? bare.trim());
for (const [source, urls] of [
  ["index.html", references],
  ["css/app.css", cssURLs.filter(url => !url.startsWith("data:"))],
  ["manifest.webmanifest", manifest.icons.map(icon => icon.src)],
]) {
  for (const url of urls) {
    if (!deployedReference(url, source)) failures.push(`${source} has a URL outside the app folder: ${url}`);
  }
}
if (/@import\b/i.test(css)) failures.push("app.css contains an @import");
for (const member of ["start_url", "scope"]) {
  if (deployedReference(manifest[member], "manifest.webmanifest") !== deployment.href) {
    failures.push(`manifest ${member} must resolve to the app folder`);
  }
}
if (Object.hasOwn(manifest, "id")) failures.push("manifest id must inherit the deployed start_url");

const csp = html.match(/http-equiv="Content-Security-Policy"\s+content="([^"]+)"/i)?.[1] ?? "";
for (const directive of [
  "default-src 'self'",
  "script-src 'self'",
  // GLTFLoader fetches embedded images through local object URLs.
  "connect-src 'self' blob:",
  "object-src 'none'",
  "base-uri 'none'",
]) {
  if (!csp.split(";").map(value => value.trim()).includes(directive)) failures.push(`Content Security Policy is missing: ${directive}`);
}
if (/script-src[^;]*'unsafe-(?:inline|eval)'/i.test(csp)) {
  failures.push("Content Security Policy permits inline or evaluated scripts");
}

for (const [name, maximum] of Object.entries(budgets)) {
  console.log(`${name}: ${sizes[name]} / ${maximum} bytes`);
}
console.log(`local runtime references: ${references.join(", ")}`);
console.log(`content security policy: ${csp ? "present" : "missing"}`);

if (failures.length > 0) {
  throw new Error(`Release audit failed: ${failures.join("; ")}`);
}
