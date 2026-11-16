import fs from "node:fs/promises";
import path from "node:path";

/**
 * Driver for the supported Work browser API. The caller selects/opens its local
 * application tab and passes that tab's active native browser window.
 * Browser interaction stays on the supplied CUA APIs; evaluate is read-only.
 */
export function createCuaDriver({ tab, nativeWindow, outputDirectory }) {
  const viewports = [];
  return {
    viewports,
    loc: (selector) => tab.playwright.locator(selector),
    role: (...args) => tab.playwright.getByRole(...args),
    label: (...args) => tab.playwright.getByLabel(...args),
    dom: (fn, arg) => tab.playwright.evaluate(fn, arg),
    waitText: (selector, text) =>
      tab.playwright.locator(selector).filter({ hasText: text }).waitFor(),
    reload: () => tab.reload(),
    viewport: async (mode) => {
      await nativeWindow.pressKey("ctrl+0");
      if (mode === "narrow")
        for (let i = 0; i < 8; i++) await nativeWindow.pressKey("ctrl+plus");
      else if (mode !== "wide") throw Error("Unknown viewport mode");
      viewports.push({
        mode,
        ...(await tab.playwright.evaluate(() => ({
          width: innerWidth,
          scrollWidth: document.documentElement.scrollWidth,
        }))),
      });
    },
    screenshot: async (name) => {
      await fs.mkdir(outputDirectory, { recursive: true });
      await fs.writeFile(
        path.join(outputDirectory, name),
        await tab.screenshot({ fullPage: true }),
      );
    },
    download: async (action) => {
      const pending = tab.playwright.waitForEvent("download");
      await action();
      return fs.readFile(await (await pending).path(), "utf8");
    },
  };
}
