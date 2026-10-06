// The vector is the editable source. PNG exports are shipped with the app;
// only regenerating the artwork requires librsvg's rsvg-convert executable.
import { readFile, writeFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";

const directory = new URL("../resources/public/icons/", import.meta.url);
const mark = await readFile(new URL("mark.svg", directory), "utf8");
const artwork = mark.replace(/<svg[^>]*>/, "").replace(/<\/svg>\s*$/, "");
const icon = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <defs><radialGradient id="wash" cx="70%" cy="20%" r="90%">
    <stop stop-color="#26233f"/><stop offset="1" stop-color="#090a0f"/>
  </radialGradient></defs>
  <rect width="512" height="512" fill="#090a0f"/>
  <rect width="512" height="512" fill="url(#wash)"/>
  <g transform="translate(64 64) scale(6)">${artwork}</g>
</svg>\n`;
await writeFile(new URL("app.svg", directory), icon);
for (const [name, size] of [["icon-192.png", 192], ["icon-512.png", 512],
                          ["icon-maskable-512.png", 512], ["apple-touch-icon.png", 180]]) {
  execFileSync("rsvg-convert", ["--width", String(size), "--height", String(size),
    "--output", new URL(name, directory).pathname, new URL("app.svg", directory).pathname]);
}
execFileSync("rsvg-convert", ["--width", "32", "--height", "32", "--output",
  new URL("favicon-32.png", directory).pathname, new URL("app.svg", directory).pathname]);
console.log("Exported PRETYPIST app icons from icons/mark.svg");
