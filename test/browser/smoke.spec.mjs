import { expect, test } from "@playwright/test";
import JSZip from "jszip";
import { mkdir, readFile } from "node:fs/promises";
import path from "node:path";

const completedOnboarding =
  '{:version 1 :status :complete :step :complete :phase :complete :gesture-index 3 :records [] :started-at 1 :completed-at 2 :interrupted? false}';

async function useReturningUserState(page) {
  await page.goto("/");
  await page.evaluate((checkpoint) => {
    localStorage.setItem("pretypist/onboarding", checkpoint);
  }, completedOnboarding);
  await page.reload();
}

test("complete PRETYPIST vertical slice", async ({ page }) => {
  const browserErrors = [];
  page.on("pageerror", (error) => browserErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") browserErrors.push(message.text());
  });

  await useReturningUserState(page);
  await expect(page.getByRole("button", { name: "PRETYPIST home" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Start session" })).toBeVisible();
  await expect(page.getByText("Why this passage")).toBeVisible();
  await expect(page.locator("svg.icon:empty")).toHaveCount(0);

  const headerLanguage = page.locator("#header-language");
  const headerMode = page.locator("#header-mode");
  await expect(headerLanguage).toHaveValue("en");
  await expect(headerMode).toHaveValue("flow");
  await headerLanguage.selectOption("nb");
  await expect(page.locator("html")).toHaveAttribute("lang", "nb");
  await expect(headerMode).toHaveAttribute("aria-label", "Treningsmodus");
  await headerLanguage.selectOption("en");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");

  await page.getByRole("button", { name: "Open settings" }).click();
  await expect(page.getByRole("dialog", { name: "Settings" })).toBeVisible();
  await expect(page.getByRole("button", { name: /Modified Engram/ })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Close" }).click();

  await page.getByRole("button", { name: "Start session" }).click();
  const prose = page.locator(".prose-line");
  const text = await prose.getAttribute("aria-label");
  const input = page.locator("#typing-input");
  const firstSpace = text.indexOf(" ");
  await input.pressSequentially(text.slice(0, firstSpace));
  await input.press("Space");
  await expect(page.locator(".character.is-complete")).toHaveCount(firstSpace + 1);
  await expect(page.locator(".character.is-error")).toHaveCount(0);
  await input.pressSequentially(text[firstSpace + 1]);
  await expect(page.locator(".character.is-complete")).toHaveCount(firstSpace + 2);

  await headerMode.selectOption("layers");
  await expect(page.getByRole("tab", { name: "Explore" })).toHaveAttribute("aria-selected", "true");
  const canvas = page.locator("canvas.keyboard-canvas");
  await expect(canvas).toBeVisible();
  await expect(canvas).toHaveClass(/is-orbitable/);
  await expect(canvas).toHaveAttribute("tabindex", "0");
  await page.getByRole("group", { name: "Rotatable keyboard view" }).press("ArrowUp");
  await page.getByRole("group", { name: "Rotatable keyboard view" }).press("ArrowRight");
  await page.getByRole("group", { name: "Rotatable keyboard view" }).press("Home");
  const modelPreviewDirectory = path.join(process.cwd(), "target/model-review");
  await mkdir(modelPreviewDirectory, { recursive: true });
  await page.screenshot({ path: path.join(modelPreviewDirectory, "authored-layers.png") });
  await expect(page.getByRole("button", { name: "SYM" })).toBeVisible();
  await page.getByRole("button", { name: "SYM" }).click();
  await expect(page.getByRole("button", { name: "SYM" })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("tab", { name: "Guided" }).click();
  await expect(canvas).not.toHaveClass(/is-orbitable/);
  await expect(canvas).toHaveAttribute("aria-hidden", "true");

  const replay = page.locator(".gesture-replay-button");
  await expect(replay).toHaveAccessibleName("Replay the inferred gesture for æ");
  await expect(replay).toHaveAttribute(
    "title",
    "Replay timing is illustrative; only emitted browser input is observed.",
  );
  await replay.click();
  await expect(replay).toBeDisabled();
  await expect(replay).toBeEnabled({ timeout: 2_000 });

  await input.fill("æ");
  await page.getByRole("button", { name: "Use “æ” in prose" }).click();
  await expect(page.getByRole("heading", { name: "Place “æ” back into real prose." })).toBeVisible();
  await page.getByRole("button", { name: "Start session" }).click();
  await expect(page.getByText("Transfer focus")).toBeVisible();
  await expect(page.getByText("Fresh prose will repeat “æ” at the same difficulty.")).toBeVisible();
  await expect(page.locator(".prose-line")).toHaveAttribute("aria-label", /æ/);

  await page.getByRole("button", { name: "Open settings" }).click();
  const zip = new JSZip();
  zip.file("layout.edn", "{:this is not valid EDN");
  zip.file("model.glb", Buffer.from("not a glb"));
  const invalidPackage = await zip.generateAsync({ type: "nodebuffer", compression: "STORE" });
  await page.locator("#layout-package-input").setInputFiles({
    name: "invalid.pretypist",
    mimeType: "application/vnd.pretypist+zip",
    buffer: invalidPackage,
  });
  await expect(page.getByRole("alert")).toContainText("could not be installed");
  await expect(page.getByRole("alert")).toContainText("layout.edn");
  expect(browserErrors).toEqual([]);
});

test("package theme preview is validated before installation", async ({ page }) => {
  const browserErrors = [];
  page.on("pageerror", (error) => browserErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") browserErrors.push(message.text());
  });

  await useReturningUserState(page);
  await page.getByRole("button", { name: "Open settings" }).click();

  const sourceDirectory = path.join(
    process.cwd(),
    "resources/layout-sources/modified-engram",
  );
  const sourceLayout = await readFile(path.join(sourceDirectory, "layout.edn"), "utf8");
  const importedLayout = sourceLayout
    .replace(':package/id "modified-engram"', ':package/id "browser-theme-proof"')
    .replace(':package/name "Modified Engram"', ':package/name "Browser Theme Proof"')
    .replace(":package/revision 12", ":package/revision 1");
  const zip = new JSZip();
  zip.file("layout.edn", importedLayout);
  zip.file("model.glb", await readFile(path.join(sourceDirectory, "model.glb")));
  const packageBuffer = await zip.generateAsync({ type: "nodebuffer", compression: "STORE" });

  await page.locator("#layout-package-input").setInputFiles({
    name: "browser-theme-proof.pretypist",
    mimeType: "application/vnd.pretypist+zip",
    buffer: packageBuffer,
  });

  const preview = page.locator(".layout-import-preview");
  await expect(preview.getByRole("heading", { name: "Browser Theme Proof" })).toBeVisible();
  await expect(preview.locator(".theme-preview-card")).toHaveCount(2);
  await expect(preview).toContainText("Package palette");
  await expect(preview).toContainText("Lowest contrast");
  await expect(page.getByRole("button", { name: /Browser Theme Proof/ })).toHaveCount(0);

  const previewDirectory = path.join(process.cwd(), "target/theme-previews");
  await mkdir(previewDirectory, { recursive: true });
  await preview.screenshot({
    path: path.join(previewDirectory, "modified-engram-dark-light.png"),
    animations: "disabled",
  });
  await preview.locator(".theme-preview-card").nth(0).screenshot({
    path: path.join(previewDirectory, "modified-engram-dark.png"),
    animations: "disabled",
  });
  await preview.locator(".theme-preview-card").nth(1).screenshot({
    path: path.join(previewDirectory, "modified-engram-light.png"),
    animations: "disabled",
  });

  await preview.getByRole("button", { name: "Install layout" }).click();
  await expect(
    page.getByRole("button", { name: "Browser Theme Proof", exact: true }),
  ).toBeVisible();
  await page.locator("#appearance").selectOption("light");
  await expect(page.locator(".app-shell")).toHaveAttribute("data-theme", "light");
  await expect
    .poll(() =>
      page.locator(".app-shell").evaluate((node) =>
        getComputedStyle(node).getPropertyValue("--pt-background").trim(),
      ),
    )
    .toBe("#f7f6f2");
  await expect
    .poll(() =>
      page.locator(".settings-drawer").evaluate((node) => ({
        panel: getComputedStyle(node).backgroundColor,
        heading: getComputedStyle(node.querySelector("h2")).color,
      })),
    )
    .toEqual({panel: "rgb(235, 233, 227)", heading: "rgb(17, 18, 22)"});
  await page.getByRole("button", { name: "Close" }).click();
  await page.getByRole("button", { name: "Open settings" }).click();
  await expect(page.locator("#appearance")).toHaveValue("light");
  await expect(page.locator(".app-shell")).toHaveAttribute("data-theme", "light");
  expect(browserErrors).toEqual([]);
});

test("completion analysis can start a same-level consolidation run", async ({ page }) => {
  const browserErrors = [];
  page.on("pageerror", (error) => browserErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") browserErrors.push(message.text());
  });

  await useReturningUserState(page);
  await page.getByRole("button", { name: "Start session" }).click();

  const prose = page.locator(".prose-line");
  const text = await prose.getAttribute("aria-label");
  const input = page.locator("#typing-input");
  await input.press("x");
  await input.press("Backspace");
  await input.fill(text.slice(0, 1));
  await expect(input).toHaveValue("");
  // An intentional real pause creates a stall marker alongside the error and
  // inferred capitalization gesture, without injecting internal app state.
  await page.waitForTimeout(1700);
  await input.fill(text.slice(1));

  const completion = page.getByRole("dialog");
  await expect(completion).toBeVisible();
  await expect(completion.getByRole("heading", { name: "Rhythm through the passage" })).toBeVisible();
  await expect(completion.locator(".chart-line")).toHaveAttribute("points", /,/);
  await expect(completion).toContainText("Incorrect attempts on");
  await expect(completion).toContainText("at the same difficulty");
  await expect(completion).toBeFocused();
  const analysisReplay = completion.locator(".analysis-replay-button");
  await expect(analysisReplay).toBeVisible();
  await expect(analysisReplay).toHaveAttribute(
    "title",
    "Replay timing is illustrative; only emitted browser input is observed.",
  );

  await mkdir("target/ui-review", { recursive: true });
  for (const viewport of [{ width: 1280, height: 720 }, { width: 390, height: 844 }]) {
    await page.setViewportSize(viewport);
    for (const kind of ["stall", "error", "gesture"]) {
      await expect(completion.locator(`.chart-marker.is-${kind}`).first()).toBeVisible();
    }
    const geometry = await completion.evaluate(panel => {
      const header = panel.querySelector(".completion-header").getBoundingClientRect();
      const check = panel.querySelector(".completion-check").getBoundingClientRect();
      const circle = panel.querySelector(".chart-marker.is-stall circle").getBoundingClientRect();
      const error = panel.querySelector(".chart-marker.is-error rect").getBoundingClientRect();
      const triangle = panel.querySelector(".chart-marker.is-gesture path").getBoundingClientRect();
      const colors = ["stall", "error", "gesture"].map(kind => {
        const plot = panel.querySelector(`.chart-marker.is-${kind}`);
        const legend = panel.querySelector(`.chart-key .is-${kind}`);
        return [getComputedStyle(plot).color, getComputedStyle(legend).color];
      });
      return { circle: [circle.width, circle.height], triangle: [triangle.width, triangle.height],
        markerRowGap: circle.top - error.bottom,
        checkRight: header.right - check.right, checkTop: check.top - header.top, colors };
    });
    expect(geometry.circle[0]).toBeCloseTo(6, 1);
    expect(geometry.circle[1]).toBeCloseTo(6, 1);
    expect(geometry.triangle[0]).toBeCloseTo(8, 1);
    expect(geometry.triangle[1] / geometry.triangle[0]).toBeCloseTo(Math.sqrt(3) / 2, 2);
    expect(geometry.markerRowGap).toBeGreaterThan(0);
    expect(geometry.checkRight).toBeCloseTo(0, 1);
    expect(geometry.checkTop).toBeCloseTo(0, 1);
    expect(new Set(geometry.colors.map(pair => pair[0])).size).toBe(3);
    for (const [plot, legend] of geometry.colors) expect(plot).toBe(legend);
    await page.screenshot({ path: `target/ui-review/completion-markers-${viewport.width}.png` });
  }
  await completion.locator(".chart-marker.is-stall").first().focus();
  await expect(completion.locator(".chart-marker.is-stall").first()).toBeFocused();
  await expect(completion.locator(".chart-marker.is-stall").first()).toHaveAttribute("aria-label", /pause/i);
  await page.setViewportSize({ width: 1440, height: 980 });

  await completion.getByRole("button", { name: "Consolidate this" }).click();
  await expect(page.getByText("Consolidation focus")).toBeVisible();
  await expect(page.locator("#typing-input")).toBeEnabled();
  await expect(page.locator("canvas.keyboard-canvas")).toHaveClass(/is-practice/);
  expect(browserErrors).toEqual([]);
});

test("first run calibrates through flow, chunks, gestures, and transfer", async ({ page }) => {
  const browserErrors = [];
  page.on("pageerror", (error) => browserErrors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") browserErrors.push(message.text());
  });

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Read beyond the caret." })).toBeVisible();
  await page.getByRole("combobox", { name: "Language" }).selectOption("nb");
  await expect(page.getByRole("heading", { name: "Les forbi markøren." })).toBeVisible();
  await expect(page.getByText("Rommet blir stille", { exact: true })).toBeVisible();
  await page.getByRole("combobox", { name: "Språk" }).selectOption("en");
  await expect(page.getByRole("heading", { name: "Learn the movement, not just the mark." })).toHaveCount(1);
  await expect(page.getByRole("heading", { name: "Keep the rhythm through every layer." })).toHaveCount(1);
  await expect(page.getByRole("heading", { name: "Bring the layout under your hands." })).toHaveCount(1);
  await expect(page.locator("canvas.keyboard-canvas")).toHaveClass(/is-landing/);
  await expect(page.locator("svg.icon:empty")).toHaveCount(0);

  await page.getByRole("link", { name: "Go to chapter 2" }).click();
  await expect(page.locator(".landing-surface")).toHaveClass(/is-chapter-movement/);
  await page.getByRole("link", { name: "Go to chapter 3" }).click();
  await expect(page.locator(".landing-surface")).toHaveClass(/is-chapter-rhythm/);
  await page.getByRole("link", { name: "Go to chapter 4" }).click();
  await expect(page.locator(".landing-surface")).toHaveClass(/is-chapter-layout/);
  await page.getByRole("link", { name: "Go to chapter 5" }).click();
  await expect(page.locator(".landing-surface")).toHaveClass(/is-chapter-begin/);
  await expect(page.getByRole("button", { name: "Import my layout" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Start with Modified Engram" })).toBeEnabled();
  await page.getByRole("button", { name: "Start with Modified Engram" }).click();

  await expect(page.getByRole("heading", { name: "Meet your keyboard before the clock." })).toBeVisible();
  await expect(page.getByText("Browser key", { exact: true })).toBeHidden();
  await page.getByRole("button", { name: "What can the browser see?" }).click();
  await expect(page.getByText("Browser key", { exact: true })).toBeVisible();
  await expect(page.getByText("Expected firmware gesture", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: /Modified Engram/ })).toHaveAttribute("aria-pressed", "true");

  await page.getByRole("button", { name: "What can the browser see?" }).click();

  await page.getByRole("button", { name: "Begin calibration" }).click();
  await expect(page.getByRole("heading", { name: "Write once without performing." })).toBeVisible();
  await page.getByRole("button", { name: "Write the first passage" }).click();
  let text = await page.locator(".prose-line").getAttribute("aria-label");
  await page.locator("#typing-input").fill(text);
  await expect(page.getByRole("heading", { name: "That is enough to begin." })).toBeVisible();

  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Practice visible phrases" }).click();
  await expect(page.locator(".phrase-chunk").first()).toBeVisible();
  text = await page.locator(".prose-line").getAttribute("aria-label");
  await page.locator("#typing-input").fill(text);
  await expect(page.getByRole("heading", { name: "Here is the shape, not a score." })).toBeVisible();
  await expect(page.locator(".onboarding-chart .chart-line")).toHaveAttribute("points", /,/);

  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Show the first gesture" }).click();
  const input = page.locator("#typing-input");
  await expect(page.getByRole("heading", { name: "Type æ" })).toBeVisible();
  await input.fill("æ");
  await expect(page.getByRole("heading", { name: "Type ø" })).toBeVisible();
  await input.fill("ø");
  await expect(page.getByRole("heading", { name: "Type å" })).toBeVisible();
  await input.fill("å");
  await expect(page.getByRole("heading", { name: "Put æ, ø, and å back into language." })).toBeVisible();

  await page.getByRole("button", { name: "Transfer into prose" }).click();
  text = await page.locator(".prose-line").getAttribute("aria-label");
  expect(text).toContain("æ");
  expect(text).toContain("ø");
  expect(text).toContain("å");
  await page.locator("#typing-input").fill(text);
  await expect(page.getByRole("heading", { name: "The gesture returned to prose." })).toBeVisible();
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByText("Calibration complete")).toBeVisible();
  await page.getByRole("button", { name: "Enter PRETYPIST" }).click();
  await expect(page.getByRole("button", { name: "PRETYPIST home" })).toBeVisible();

  await page.reload();
  await expect(page.getByRole("button", { name: "PRETYPIST home" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Meet your keyboard before the clock." })).toHaveCount(0);
  expect(browserErrors).toEqual([]);
});

test("Unicode input, editing intent, focus, and every practice mode remain usable", async ({ page }) => {
  const browserErrors = [];
  page.on("pageerror", (error) => browserErrors.push(error.message));

  await useReturningUserState(page);
  await page.locator("#header-language").selectOption("nb");
  await page.getByRole("button", { name: "Åpne innstillinger" }).click();
  await page.locator("#run-length").selectOption("long");
  await page.getByRole("button", { name: "Lukk" }).click();
  await page.getByRole("button", { name: "Start økt" }).click();

  const prose = page.locator(".prose-line");
  const text = await prose.getAttribute("aria-label");
  const lowercaseAa = text.indexOf("å");
  const uppercaseAa = text.indexOf("Å");
  const aaIndex = lowercaseAa >= 0 ? lowercaseAa : uppercaseAa;
  const decomposedAa = lowercaseAa >= 0 ? "a\u030a" : "A\u030a";
  expect(aaIndex).toBeGreaterThan(0);
  const input = page.locator("#typing-input");

  // One InputEvent may contain a whole Unicode prefix. A decomposed å then
  // arrives through an IME boundary and must advance exactly once at commit.
  await input.fill(text.slice(0, aaIndex));
  await expect(page.locator(".character.is-complete")).toHaveCount(aaIndex);
  await input.evaluate((node, inputData) => {
    node.dispatchEvent(new CompositionEvent("compositionstart", { bubbles: true, data: inputData }));
    node.dispatchEvent(
      new InputEvent("input", {
        bubbles: true,
        data: inputData,
        inputType: "insertCompositionText",
        isComposing: true,
      }),
    );
    node.dispatchEvent(new CompositionEvent("compositionend", { bubbles: true, data: inputData }));
    node.dispatchEvent(
      new InputEvent("input", {
        bubbles: true,
        data: inputData,
        inputType: "insertCompositionText",
        isComposing: false,
      }),
    );
  }, decomposedAa);
  await expect(page.locator(".character.is-complete")).toHaveCount(aaIndex + 1);

  const cursorBeforePaste = await page.locator(".character.is-complete").count();
  await input.evaluate((node) => {
    node.dispatchEvent(
      new InputEvent("beforeinput", {
        bubbles: true,
        cancelable: true,
        data: "pasted",
        inputType: "insertFromPaste",
      }),
    );
  });
  await expect(page.getByRole("status")).toContainText("Innliming");
  await expect(page.locator(".character.is-complete")).toHaveCount(cursorBeforePaste);

  const browserShortcutWasNotCancelled = await input.evaluate((node) => {
    const event = new KeyboardEvent("keydown", {
      bubbles: true,
      cancelable: true,
      key: "r",
      code: "KeyR",
      ctrlKey: true,
    });
    node.dispatchEvent(event);
    return !event.defaultPrevented;
  });
  expect(browserShortcutWasNotCancelled).toBe(true);

  await input.evaluate((node) => node.blur());
  await expect(page.getByRole("heading", { name: "Fokuset ble flyttet" })).toBeVisible();
  await page.getByRole("button", { name: "Fortsett" }).click();
  await expect(input).toBeFocused();

  await page.locator("#header-mode").selectOption("horizon");
  await page.getByRole("button", { name: "Start økt" }).click();
  await expect(page.locator(".horizon-preview")).toBeVisible();
  await page.locator("#header-mode").selectOption("chunks");
  await page.getByRole("button", { name: "Start økt" }).click();
  await expect(page.locator(".phrase-chunk").first()).toBeVisible();
  await page.locator("#header-mode").selectOption("layers");
  await expect(page.getByRole("tab", { name: "Utforsk" })).toBeVisible();

  await page.getByRole("button", { name: "Åpne innstillinger" }).click();
  await page.locator("#reduced-motion\\?").check();
  await expect(page.locator(".app-shell")).toHaveClass(/is-reduced-motion/);
  await expect(page.locator("button:visible, select:visible, input:visible")).not.toHaveCount(0);
  expect(browserErrors).toEqual([]);
});

test("persisted continuity migrates and typing survives WebGL failure", async ({ page }) => {
  const now = Date.now();
  await page.addInitScript(
    ({ onboarding, now }) => {
      localStorage.setItem("pretypist/onboarding", onboarding);
      localStorage.setItem(
        "pretypist/settings",
        '{:version 1 :language :en :mode :flow :reduced-motion? true}',
      );
      localStorage.setItem(
        "pretypist/recent-summaries",
        `[{ :id "recent" :mode :flow :completed-on ${now - 86_400_000} :wpm 42 :accuracy 0.99 :rhythm-consistency 0.78}]`,
      );
      HTMLCanvasElement.prototype.getContext = () => null;
    },
    { onboarding: completedOnboarding, now },
  );

  await page.goto("/");
  await expect(page.getByText("One focused session this week.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Continue" })).toBeVisible();
  await expect(page.locator(".app-shell")).toHaveClass(/is-reduced-motion/);
  await expect(page.getByText("The keyboard view is unavailable")).toBeVisible();

  await page.getByRole("button", { name: "Continue" }).click();
  const input = page.locator("#typing-input");
  const text = await page.locator(".prose-line").getAttribute("aria-label");
  await input.fill(text.slice(0, 4));
  await expect(page.locator(".character.is-complete")).toHaveCount(4);

  const overflow = await page.evaluate(() => ({
    horizontal: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    unnamedIcons: [...document.querySelectorAll("button svg")].filter(
      (icon) => !icon.closest("button")?.getAttribute("aria-label") && !icon.closest("button")?.textContent?.trim(),
    ).length,
  }));
  expect(overflow.horizontal).toBeLessThanOrEqual(1);
  expect(overflow.unnamedIcons).toBe(0);
});
