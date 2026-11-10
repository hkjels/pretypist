import { test, expect } from "@playwright/test";
import { build } from "esbuild";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { adaptModel, sourcePath, outputPath } from "../../scripts/adapt-authored-model.mjs";
import { surfaceViewer } from "./helpers/surface-review.mjs";

test("compact model preserves visible surfaces in eight matched close-up views", async ({ page }) => {
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("console", message => { if (message.type() === "error") errors.push(message.text()); });
  const full = adaptModel(await readFile(sourcePath)).output;
  const compact = await readFile(outputPath);
  const bundle = await build({ stdin: { contents: surfaceViewer, resolveDir: process.cwd(), sourcefile: "surface-review.js" },
    bundle: true, write: false, format: "esm", platform: "browser" });
  let model = full, beforeImage;
  await page.route("**/surface-model.glb", route => route.fulfill({ contentType: "model/gltf-binary", body: model }));
  await page.route("**/surface-review.js", route => route.fulfill({ contentType: "text/javascript", body: Buffer.from(bundle.outputFiles[0].contents) }));
  await page.route("**/before.png", route => route.fulfill({ contentType: "image/png", body: beforeImage }));
  await page.route("**/surface-review", route => route.fulfill({ contentType: "text/html", body:
    '<!doctype html><html><body style="margin:0;background:#14151a"><script type="module" src="/surface-review.js"></script></body></html>' }));
  await page.setViewportSize({ width: 1100, height: 800 });
  const directory = "target/model-review/optimization";
  await mkdir(directory, { recursive: true });
  const views = ["full", "caps", "fasteners", "usb", "front", "rear", "underside", "thumb"];
  const before = new Map(), metrics = {};
  for (const [name, data] of [["before", full], ["after", compact]]) {
    model = data;
    await page.goto("/surface-review");
    await expect(page.locator("body")).toHaveAttribute("data-ready", "true");
    const materials = await page.evaluate(() => window.surfaceReview.audit());
    expect(materials.shafts).toBe(4); expect(materials.detailed).toBeGreaterThan(10);
    for (const view of views) {
      await page.evaluate(view => window.surfaceReview.show(view), view);
      const screenshot = await page.screenshot({ path: `${directory}/${name}-${view}.png` });
      if (name === "before") { before.set(view, screenshot); continue; }
      beforeImage = before.get(view);
      metrics[view] = await page.evaluate(async () => {
        const image = new Image(); image.src = "/before.png";
        await image.decode();
        const canvas = document.createElement("canvas"); canvas.width = 1100; canvas.height = 800;
        const ctx = canvas.getContext("2d", { willReadFrequently: true });
        ctx.drawImage(image, 0, 0); const a = ctx.getImageData(0, 0, 1100, 800).data;
        ctx.drawImage(document.querySelector("canvas"), 0, 0); const b = ctx.getImageData(0, 0, 1100, 800).data;
        let visible = 0, total = 0, changed = 0;
        for (let i = 0; i < a.length; i += 4) {
          // Evaluate foreground pixels, so the empty background cannot dilute differences.
          if ([0, 1, 2].every(c => Math.abs(a[i+c] - [20,21,26][c]) < 3 && Math.abs(b[i+c] - [20,21,26][c]) < 3)) continue;
          visible++; const difference = [0,1,2].map(c => Math.abs(a[i+c] - b[i+c]));
          total += difference.reduce((x,y) => x+y, 0); if (Math.max(...difference) > 24) changed++;
        }
        return { visiblePixels: visible, meanChannelDifference: total / (visible * 3), changedPixelFraction: changed / visible };
      });
    }
  }
  await writeFile(`${directory}/comparison.json`, JSON.stringify(metrics, null, 2) + "\n");
  for (const [view, metric] of Object.entries(metrics)) {
    expect(metric.visiblePixels, view).toBeGreaterThan(10000);
    // The 50k target accepts small lighting changes at extreme zoom. Keep
    // stricter limits on the normal view, keycap curves and visible hardware.
    const limit = view === "full" ? [1,0.0075] : view === "caps" ? [0.5,0.005]
      : view === "fasteners" ? [0.75,0.006] : [2.55,0.025];
    expect(metric.meanChannelDifference, view).toBeLessThan(limit[0]);
    expect(metric.changedPixelFraction, view).toBeLessThan(limit[1]);
  }
  expect(errors).toEqual([]);
});
