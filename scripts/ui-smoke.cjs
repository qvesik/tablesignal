/* Destructive UI acceptance for a disposable local TableSignal workspace. */
const { chromium } = require("playwright");
const fs = require("node:fs/promises");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const base = process.env.TABLESIGNAL_BASE_URL || "http://127.0.0.1:8080";
(async () => {
  await fs.mkdir("target", { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1050 },
    acceptDownloads: true,
  });
  const errors = [];
  page.on("pageerror", (error) => errors.push(String(error)));
  const { createAcceptance } = await import(
    pathToFileURL(path.join(__dirname, "ui-acceptance.mjs")).href
  );
  const driver = {
    loc: (selector) => page.locator(selector),
    role: (...args) => page.getByRole(...args),
    label: (...args) => page.getByLabel(...args),
    dom: (fn, arg) => page.evaluate(fn, arg),
    waitText: (selector, text) =>
      page.locator(selector).filter({ hasText: text }).waitFor(),
    reload: () => page.reload(),
    viewport: (mode) =>
      page.setViewportSize(
        mode === "narrow"
          ? { width: 390, height: 844 }
          : { width: 1440, height: 1050 },
      ),
    screenshot: (name) =>
      page.screenshot({ path: path.join("target", name), fullPage: true }),
    download: async (action) => {
      const pending = page.waitForEvent("download");
      await action();
      return fs.readFile(await (await pending).path(), "utf8");
    },
  };
  const suite = createAcceptance(driver);
  try {
    await page.goto(base);
    for (let i = 0; i < suite.phaseNames.length; i++)
      console.log(await suite.runPhase(i));
    if (errors.length) throw Error(errors.join("\n"));
    console.log("PASS: all UI acceptance phases");
  } finally {
    await fs.writeFile(
      "target/ui-acceptance.json",
      JSON.stringify(
        {
          driver: "playwright",
          base,
          phases: suite.results,
          pageErrors: errors,
        },
        null,
        2,
      ),
    );
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
