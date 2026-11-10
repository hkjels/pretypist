import { test, expect } from "@playwright/test";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";

const returning = '{:version 1 :status :complete :step :complete :phase :complete :gesture-index 3 :records [] :started-at 1 :completed-at 2 :interrupted? false}';
const appPath = "/pretypist/";
const cacheName = (scope, version) => `pretypist-offline:${encodeURIComponent(scope)}:browser-test-${version}`;
let server, origin, projectVersion, failingPath, serveRoot;
const assets = new Map();

test.beforeAll(async () => {
  const worker = await readFile("resources/public/sw.js", "utf8");
  const paths = JSON.parse(worker.match(/const \{ version, assets \} = (.*);/)[1]).assets;
  for (const path of paths) {
    assets.set(path, await readFile(`resources/public/${path === "./" ? "index.html" : path}`));
  }
  assets.set("sw.js", worker);
  server = createServer((request, response) => {
    const pathname = new URL(request.url, "http://localhost").pathname;
    const path = pathname.startsWith(appPath) ? pathname.slice(appPath.length) : serveRoot ? pathname.slice(1) : null;
    const shell = path === "" || path === "index.html";
    const content = assets.get(shell ? "./" : path);
    if (!content) return response.writeHead(404).end("Not found");
    if (pathname === failingPath) return response.writeHead(503).end("Unavailable");
    const types = { js: "text/javascript", css: "text/css", webmanifest: "application/manifest+json",
      png: "image/png", svg: "image/svg+xml", edn: "application/edn" };
    response.writeHead(200, { "content-type": shell ? "text/html" : types[path.split(".").at(-1)] || "application/octet-stream", "cache-control": "no-store" });
    response.end(path === "sw.js"
      ? content.replace(/"version":"[^"]+"/, `"version":"browser-test-${pathname.startsWith(appPath) ? projectVersion : 1}"`)
      : content);
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
});

test.afterAll(async () => { await new Promise(resolve => server.close(resolve)); });

test.beforeEach(async ({ page }) => {
  projectVersion = 1;
  failingPath = null;
  serveRoot = false;
  await page.addInitScript(returning => {
    if (!localStorage.getItem("pretypist/settings")) {
      localStorage.setItem("pretypist/settings", '{:version 2 :language :en :mode :flow :appearance :dark :reduced-motion? true}');
      localStorage.setItem("pretypist/onboarding", returning);
    }
  }, returning);
});

async function ready(page, scope = appPath) {
  await page.goto(`${origin}${scope}`);
  await expect(page.getByRole("button", { name: "Start session", exact: true })).toBeVisible();
  await page.waitForFunction(url => navigator.serviceWorker.controller?.scriptURL === url, `${origin}${scope}sw.js`);
}

test("manifest, icons and a completed session offer installation", async ({ page, request }) => {
  const manifestURL = `${origin}${appPath}manifest.webmanifest`;
  const response = await request.get(manifestURL);
  expect(response.headers()["content-type"]).toContain("application/manifest+json");
  const manifest = await response.json();
  expect(new URL(manifest.start_url, manifestURL).href).toBe(`${origin}${appPath}`);
  expect(new URL(manifest.scope, manifestURL).href).toBe(`${origin}${appPath}`);
  expect(manifest.id).toBeUndefined(); // The installed identity inherits the scoped start URL.
  expect(manifest.display).toBe("standalone");
  expect(manifest.icons.some(icon => icon.purpose === "maskable")).toBe(true);
  for (const icon of manifest.icons) {
    const image = await request.get(new URL(icon.src, manifestURL).href);
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
  // Explicit index URLs, queries and fragments use the same cached release shell.
  await page.goto(`${origin}${appPath}index.html?offline=1#landing-begin`);
  await expect(page.getByRole("button", { name: "Continue", exact: true })).toBeVisible();
  expect(await page.evaluate(() => fetch("layouts/one-piece-example.pretypist").then(response => response.ok))).toBe(true);
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
  projectVersion = 2;
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
  expect(await page.evaluate(() => caches.keys())).toEqual([cacheName(appPath, 2)]);
  await expect(other.locator(".character.is-complete")).toHaveCount(4);
  await expect(other.locator(".prose-line")).toHaveAttribute("aria-label", otherText);
});

test("a failed offline download keeps the previous release usable", async ({ page, context }) => {
  await ready(page);
  projectVersion = 3;
  failingPath = `${appPath}corpora/en.edn`;
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
  expect(await page.evaluate(() => caches.keys())).toEqual([cacheName(appPath, 1)]);
  await context.setOffline(true);
  await page.reload();
  await page.getByRole("button", { name: "Start session", exact: true }).click();
  await page.keyboard.insertText(await page.locator(".prose-line").getAttribute("aria-label"));
  await expect(page.locator(".completion-panel")).toBeVisible();
});

test("root and project-path installs keep separate offline releases", async ({ page, context }) => {
  serveRoot = true;
  await ready(page, "/");
  const project = await context.newPage();
  await ready(project);
  expect(await project.evaluate(async () => (await navigator.serviceWorker.getRegistration()).scope)).toBe(`${origin}${appPath}`);
  expect(await project.evaluate(async () => (await caches.keys()).sort())).toEqual([cacheName("/", 1), cacheName(appPath, 1)].sort());

  projectVersion = 2;
  await project.evaluate(async () => { await (await navigator.serviceWorker.getRegistration()).update(); });
  await project.waitForFunction(async () => (await navigator.serviceWorker.getRegistration()).waiting);
  await project.getByRole("button", { name: "Open settings" }).click();
  await Promise.all([
    project.waitForEvent("domcontentloaded"),
    project.getByRole("button", { name: "Update Pretypist" }).click(),
  ]);
  expect(await project.evaluate(async () => (await caches.keys()).sort())).toEqual([cacheName("/", 1), cacheName(appPath, 2)].sort());

  await context.setOffline(true);
  await page.reload();
  await expect(page.getByRole("button", { name: "Start session", exact: true })).toBeVisible();
  await project.reload();
  await expect(project.getByRole("button", { name: "Start session", exact: true })).toBeVisible();
});
