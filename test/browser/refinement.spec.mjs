import { test, expect } from "@playwright/test";
import { mkdir } from "node:fs/promises";

const directory = "target/ui-review";
const returning = '{:version 1 :status :complete :step :complete :phase :complete :gesture-index 3 :records [] :started-at 1 :completed-at 2 :interrupted? false}';

async function seed(page, onboarding = true) {
  await page.addInitScript(({ onboarding, returning }) => {
    localStorage.setItem("pretypist/settings", '{:version 2 :language :en :mode :flow :appearance :light :reduced-motion? true}');
    if (onboarding) localStorage.setItem("pretypist/onboarding", returning);
  }, { onboarding, returning });
  await mkdir(directory, { recursive: true });
}

async function sceneBelow(page, selector) {
  // Measure both boxes in the same layout snapshot: the scene animates while
  // browser-input focus and live metrics can trigger a trainer render.
  await expect.poll(() => page.evaluate(selector => {
    const content = document.querySelector(selector).getBoundingClientRect();
    const scene = document.querySelector(".scene-stage").getBoundingClientRect();
    return scene.y - content.y - content.height;
  }, selector)).toBeGreaterThanOrEqual(-1);
  await expect(page.locator("canvas.keyboard-canvas")).toBeVisible();
  await expect(page.locator(".is-unavailable")).toHaveCount(0);
}

test("refined training UI: protected scene, one navigation, readable prose and accessible disclosures", async ({ page }) => {
  await seed(page);
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start session", exact: true })).toBeVisible();
  await expect(page.locator("#header-mode")).toHaveCount(1);
  await expect(page.locator(".mode-picker")).toHaveCount(0);
  await sceneBelow(page, ".trainer-main");
  const canvasHandle = await page.locator("canvas.keyboard-canvas").elementHandle();
  await page.screenshot({ path: `${directory}/flow-light.png` });

  await page.getByRole("button", { name: "Open settings" }).click();
  await expect(page.locator("#manual-difficulty")).toBeHidden();
  await expect(page.locator("#ambient-intensity")).toBeHidden();
  await expect(page.locator("#language, #mode")).toHaveCount(0);
  await page.getByRole("button", { name: "Adjust difficulty" }).click();
  await expect(page.locator("#manual-difficulty")).toBeVisible();
  await page.getByRole("button", { name: "Adjust difficulty" }).click();
  await page.getByRole("button", { name: "Lighting & legends" }).click();
  await expect(page.locator("#ambient-intensity")).toBeVisible();
  await page.getByRole("button", { name: "Lighting & legends" }).click();
  await page.getByRole("button", { name: "Close", exact: true }).focus();
  await page.keyboard.press("Shift+Tab");
  await expect(page.getByRole("button", { name: "Reset progress", exact: true })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("button", { name: "Close", exact: true })).toBeFocused();
  await page.screenshot({ path: `${directory}/settings-light.png` });
  await page.locator("#appearance").selectOption("dark");
  await page.keyboard.press("Escape");
  await expect(page.locator("#settings-button")).toBeFocused();

  await page.locator("#header-mode").selectOption("layers");
  await expect(page.getByRole("tab", { name: "Explore", exact: true })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { name: "Base", exact: true })).toHaveCount(0);
  await sceneBelow(page, ".trainer-main");
  expect(await canvasHandle.evaluate(node => node === document.querySelector("canvas.keyboard-canvas"))).toBe(true);
  await page.screenshot({ path: `${directory}/layers-dark.png` });
  await page.locator("#header-mode").selectOption("flow");
  await page.getByRole("button", { name: "Start session", exact: true }).click();
  const text = await page.locator(".prose-line").getAttribute("aria-label");
  await expect(page.locator(".is-current-word")).toHaveCount(text.indexOf(" "));
  await page.keyboard.insertText(text.slice(0, text.indexOf(" ") + 1));
  await expect(page.locator(".character.is-complete")).toHaveCount(text.indexOf(" ") + 1);
  await expect(page.locator(".is-current-word")).toHaveCount(text.split(" ")[1].length);
  const colors = await page.evaluate(() => ({
    current: getComputedStyle(document.querySelector(".character.is-current")).color,
    word: getComputedStyle(document.querySelectorAll(".is-current-word")[1] ?? document.querySelector(".is-current-word")).color,
    upcoming: getComputedStyle(document.querySelector(".is-upcoming:not(.is-current-word)")).color,
  }));
  expect(colors.current).toBe(colors.word);
  expect(colors.upcoming).not.toBe(colors.word);
  await sceneBelow(page, ".trainer-main");
  await page.screenshot({ path: `${directory}/practice-dark.png` });

  await page.locator("#header-mode").selectOption("flow");
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    await sceneBelow(page, ".trainer-main");
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const settings = await page.locator("#settings-button").boundingBox();
    expect(settings.x + settings.width).toBeLessThanOrEqual(width);
    await page.screenshot({ path: `${directory}/${width === 390 ? "trainer-narrow" : "trainer-320"}.png` });
  }
});

test("landing and onboarding stay readable across themes and narrow viewports", async ({ page }) => {
  await seed(page, false);
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start with Modified Engram", exact: true })).toBeEnabled();
  const copy = await page.locator("#landing-read-ahead .landing-copy").boundingBox();
  const scene = await page.locator(".scene-stage").boundingBox();
  expect(scene.x).toBeGreaterThan(copy.x + copy.width);
  await page.screenshot({ path: `${directory}/landing-light.png` });

  for (const viewport of [{ width: 390, height: 844 }, { width: 320, height: 640 }]) {
    await page.setViewportSize(viewport);
    await page.getByRole("link", { name: "Go to chapter 5", exact: true }).click();
    await expect(page.locator(".landing-surface")).toHaveClass(/is-chapter-begin/);
    await expect(page.getByRole("button", { name: "Start with Modified Engram", exact: true })).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `${directory}/landing-${viewport.width}.png` });
  }
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.getByRole("button", { name: "Start with Modified Engram", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Meet your keyboard before the clock." })).toBeVisible();
  await sceneBelow(page, ".onboarding-main");
  await expect(page.getByText("Browser key", { exact: true })).toBeHidden();
  expect(await page.locator(".scene-stage").evaluate(node => node.getBoundingClientRect().bottom)).toBeLessThanOrEqual(721);
  await page.screenshot({ path: `${directory}/onboarding-light.png` });
  await page.getByRole("button", { name: "What can the browser see?" }).click();
  await expect(page.getByText("Browser key", { exact: true })).toBeVisible();
  await sceneBelow(page, ".onboarding-main");
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "What can the browser see?" }).click();
  await sceneBelow(page, ".onboarding-main");
  await page.screenshot({ path: `${directory}/onboarding-narrow.png`, fullPage: true });
});
