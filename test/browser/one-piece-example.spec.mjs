import { expect, test } from "@playwright/test";
import { mkdir } from "node:fs/promises";

test("the one-piece example imports, skips gestures, and keeps prose practice available", async ({ page }) => {
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
  await page.goto("/");
  await page.getByRole("link", { name: "Skip to begin" }).click();
  await page.getByRole("button", { name: "Start with Modified Engram" }).click();

  const archive = await page.request.get("/layouts/one-piece-example.pretypist");
  expect(archive.ok()).toBe(true);
  await page.locator("#layout-package-input").setInputFiles({
    name: "one-piece-example.pretypist",
    mimeType: "application/vnd.pretypist+zip",
    buffer: await archive.body(),
  });
  await expect(page.getByRole("heading", { name: "One-Piece Example" })).toBeVisible();
  await page.getByRole("button", { name: "Install layout" }).click();
  const example = page.getByRole("button", { name: "One-Piece Example", exact: true });
  await expect(example).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Begin calibration" }).click();

  const typePassage = async () => {
    const text = await page.locator(".prose-line").getAttribute("aria-label");
    await page.locator("#typing-input").fill(text);
  };
  await expect(page.getByText("Calibration 1 of 2", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Write the first passage" }).click();
  await typePassage();
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await expect(page.getByText("Calibration 2 of 2", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Practice visible phrases" }).click();
  await typePassage();
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await expect(page.getByText("Calibration complete", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Show the first gesture" })).toHaveCount(0);
  await page.getByRole("button", { name: "Enter PRETYPIST" }).click();

  const mode = page.locator("#header-mode");
  await expect(mode.locator("option")).toHaveText(["Flow", "Horizon", "Chunks"]);
  await expect(page.locator(".webgl-fallback")).toHaveCount(0);
  await mkdir("target/ui-review", { recursive: true });
  await page.screenshot({ path: "target/ui-review/one-piece-example.png" });

  const settings = page.getByRole("button", { name: "Open settings" });
  await settings.click();
  await page.getByRole("button", { name: /Modified Engram/ }).click();
  await expect(mode.locator('option[value="layers"]')).toHaveCount(1);
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await mode.selectOption("layers");
  await settings.click();
  await example.click();
  await expect(mode.locator('option[value="layers"]')).toHaveCount(0);
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await expect(mode).toHaveValue("flow");

  await page.reload();
  await expect(mode.locator("option")).toHaveText(["Flow", "Horizon", "Chunks"]);
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  const firstCharacter = (await page.locator(".prose-line").getAttribute("aria-label"))[0];
  await page.keyboard.type(firstCharacter);
  await expect(page.locator(".prose-line .character.is-complete")).toHaveText(firstCharacter);
  expect(errors).toEqual([]);
});
