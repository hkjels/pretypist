import { test, expect } from "@playwright/test";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";

const returning = '{:version 1 :status :complete :step :complete :phase :complete :gesture-index 3 :records [] :started-at 1 :completed-at 2 :interrupted? false}';
let server, origin, version, failingPath;
const assets = new Map();

test.beforeAll(async () => {
  const worker = await readFile("resources/public/sw.js", "utf8");
  const paths = JSON.parse(worker.match(/const \{ version, assets \} = (.*);/)[1]).assets;
  for (const path of paths) {
    assets.set(path, await readFile(`resources/public/${path === "/" ? "index.html" : path.slice(1)}`));
  }
  assets.set("/sw.js", worker);
  server = createServer((request, response) => {
    const path = new URL(request.url, "http://localhost").pathname;
    const content = assets.get(path);
    if (!content || path === failingPath) return response.writeHead(503).end("Unavailable");
    const types = { js: "text/javascript", css: "text/css", webmanifest: "application/manifest+json",
      png: "image/png", svg: "image/svg+xml", edn: "application/edn" };
    response.writeHead(200, { "content-type": path === "/" ? "text/html" : types[path.split(".").at(-1)] || "application/octet-stream", "cache-control": "no-store" });
    response.end(path === "/sw.js"
      ? content.replace(/"version":"[^"]+"/, `"version":"browser-test-${version}"`)
      : content);
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
});

test.afterAll(async () => { await new Promise(resolve => server.close(resolve)); });

test.beforeEach(async ({ page }) => {
  version = 1;
  failingPath = null;
  await page.addInitScript(returning => {
    if (!localStorage.getItem("pretypist/settings")) {
      localStorage.setItem("pretypist/settings", '{:version 2 :language :en :mode :flow :appearance :dark :reduced-motion? true}');
      localStorage.setItem("pretypist/onboarding", returning);
    }
  }, returning);
});

async function ready(page) {
  await page.goto(origin);
  await expect(page.getByRole("button", { name: "Start session", exact: true })).toBeVisible();
  await page.waitForFunction(() => navigator.serviceWorker.controller);
}

test("manifest, icons and a completed session offer installation", async ({ page, request }) => {
  const response = await request.get(`${origin}/manifest.webmanifest`);
  expect(response.headers()["content-type"]).toContain("application/manifest+json");
  const manifest = await response.json();
  expect(manifest.display).toBe("standalone");
  expect(manifest.icons.some(icon => icon.purpose === "maskable")).toBe(true);
  for (const icon of manifest.icons) {
    const image = await request.get(`${origin}${icon.src}`);
    expect(image.headers()["content-type"]).toBe("image/png");
    const pixels = await image.body();
    expect(`${pixels.readUInt32BE(16)}x${pixels.readUInt32BE(20)}`).toBe(icon.sizes);
  }
  await ready(page);
  await page.evaluate(() => {
    window.installCalls = 0;
    const event = new Event("beforeinstallprompt", { cancelable: true });
    event.prompt = () => {
      window.installCalls++;
      window.dispatchEvent(new Event("appinstalled"));
      return Promise.resolve();
    };
    event.userChoice = Promise.resolve({ outcome: "accepted" });
    window.dispatchEvent(event);
  });
  await expect(page.getByRole("button", { name: "Install Pretypist" })).toHaveCount(0);
  await page.getByRole("button", { name: "Start session", exact: true }).click();
  const text = await page.locator(".prose-line").getAttribute("aria-label");
  await page.keyboard.insertText(text);
  await expect(page.getByRole("button", { name: "Install Pretypist" })).toBeVisible();
  await page.getByRole("button", { name: "Install Pretypist" }).click();
  expect(await page.evaluate(() => window.installCalls)).toBe(1);
});

test("offline reload preserves progress, both corpora and the keyboard", async ({ page, context }) => {
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await ready(page);
  await page.getByRole("button", { name: "Start session", exact: true }).click();
  await page.keyboard.insertText(await page.locator(".prose-line").getAttribute("aria-label"));
  await expect(page.locator(".completion-panel")).toBeVisible();
  const progress = await page.evaluate(() => localStorage.getItem("pretypist/progress"));
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByRole("button", { name: "Continue", exact: true })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("pretypist/progress"))).toBe(progress);
  await page.getByRole("button", { name: "Open settings" }).click();
  await expect(page.getByText("Available offline. Your practice stays on this device.")).toBeVisible();
  await page.keyboard.press("Escape");
  await page.locator("#header-language").selectOption("nb");
  await page.getByRole("button", { name: "Fortsett", exact: true }).click();
  await page.keyboard.insertText(await page.locator(".prose-line").getAttribute("aria-label"));
  await expect(page.locator(".completion-panel")).toBeVisible();
  await expect(page.locator("canvas.keyboard-canvas")).toBeVisible();
  await expect(page.locator(".is-unavailable")).toHaveCount(0);
  expect(errors).toEqual([]);
});

test("an update waits through active and paused typing, then reloads on request", async ({ page, context }) => {
  await ready(page);
  const other = await context.newPage();
  await ready(other);
  await other.getByRole("button", { name: "Start session", exact: true }).click();
  const otherText = await other.locator(".prose-line").getAttribute("aria-label");
  await other.keyboard.insertText(otherText.slice(0, 4));
  await page.getByRole("button", { name: "Start session", exact: true }).click();
  const text = await page.locator(".prose-line").getAttribute("aria-label");
  await page.keyboard.insertText(text.slice(0, 3));
  version = 2;
  await page.evaluate(async () => { await (await navigator.serviceWorker.getRegistration()).update(); });
  await page.waitForFunction(async () => (await navigator.serviceWorker.getRegistration()).waiting);
  await expect(page.locator(".character.is-complete")).toHaveCount(3);
  await page.getByRole("button", { name: "Open settings" }).click();
  await expect(page.getByText("An update is ready after this session.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Update Pretypist" })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await page.keyboard.insertText(text.slice(3));
  await expect(page.getByRole("button", { name: "Update Pretypist" })).toBeVisible();
  const progress = await page.evaluate(() => localStorage.getItem("pretypist/progress"));
  await Promise.all([
    page.waitForEvent("domcontentloaded"),
    page.getByRole("button", { name: "Update Pretypist" }).click(),
  ]);
  await expect(page.getByRole("button", { name: "Continue", exact: true })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("pretypist/progress"))).toBe(progress);
  expect(await page.evaluate(() => caches.keys())).toEqual(["pretypist-offline-browser-test-2"]);
  await expect(other.locator(".character.is-complete")).toHaveCount(4);
  await expect(other.locator(".prose-line")).toHaveAttribute("aria-label", otherText);
});

test("a failed offline download keeps the previous release usable", async ({ page, context }) => {
  await ready(page);
  version = 3;
  failingPath = "/corpora/en.edn";
  await page.evaluate(async () => {
    const registration = await navigator.serviceWorker.getRegistration();
    const failure = new Promise(resolve => registration.addEventListener("updatefound", () => {
      const worker = registration.installing;
      worker.addEventListener("statechange", () => {
        if (worker.state === "redundant") resolve();
      });
    }, { once: true }));
    await registration.update();
    await failure;
  });
  expect(await page.evaluate(() => caches.keys())).toEqual(["pretypist-offline-browser-test-1"]);
  await context.setOffline(true);
  await page.reload();
  await page.getByRole("button", { name: "Start session", exact: true }).click();
  await page.keyboard.insertText(await page.locator(".prose-line").getAttribute("aria-label"));
  await expect(page.locator(".completion-panel")).toBeVisible();
});
