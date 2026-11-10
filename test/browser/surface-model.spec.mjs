import { test, expect } from "@playwright/test";
import { build } from "esbuild";
import { readFile, mkdir } from "node:fs/promises";
import { surfaceViewer as viewer } from "./helpers/surface-review.mjs";

test("refined GLB surfaces render with embedded PBR maps and glass fasteners", async ({ page }) => {
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("console", message => { if (message.type() === "error") errors.push(message.text()); });
  const bundle = await build({ stdin: { contents: viewer, resolveDir: process.cwd(), sourcefile: "surface-review.js" },
    bundle: true, write: false, format: "esm", platform: "browser" });
  const refined = await readFile("resources/layout-sources/modified-engram/model.glb");
  const original = await readFile("resources/layout-sources/modified-engram/authored/model.glb");
  let model = original;
  await page.route("**/surface-model.glb", route => route.fulfill({contentType:"model/gltf-binary",body:model}));
  await page.route("**/surface-review.js", route => route.fulfill({contentType:"text/javascript",body:Buffer.from(bundle.outputFiles[0].contents)}));
  await page.route("**/surface-review", route => route.fulfill({contentType:"text/html",body:
    '<!doctype html><html><body style="margin:0;background:#14151a"><script type="module" src="/surface-review.js"></script></body></html>'}));
  await page.setViewportSize({width:1100,height:800});
  await mkdir("target/model-review/surface-refinements", {recursive:true});
  for (const [name, data] of [["before",original],["after",refined]]) {
    model = data;
    await page.goto("/surface-review");
    await expect(page.locator("body")).toHaveAttribute("data-ready", "true");
    if (name === "after") {
      const audit = await page.evaluate(() => window.surfaceReview.audit());
      expect(audit.shafts).toBe(4); expect(audit.detailed).toBeGreaterThan(10); expect(audit.conductors).toBeGreaterThanOrEqual(10);
    }
    for (const view of ["full","caps","fasteners","usb"]) {
      await page.evaluate(view => window.surfaceReview.show(view), view);
      await page.screenshot({path:`target/model-review/surface-refinements/${name}-${view}.png`});
    }
  }
  expect(errors).toEqual([]);
});
