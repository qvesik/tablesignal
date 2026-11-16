import assert from "node:assert/strict";

/** Shared UI cases. Drivers provide browser actions; all assertions read rendered DOM. */
export function createAcceptance(ui) {
  const results = [];
  const note = '=Synthetic UI check, "quoted" <b>literal text</b>';
  async function go(view) {
    const narrow = await ui.dom(() => innerWidth <= 650);
    await ui
      .loc(
        narrow
          ? `#mobile-navigation a[href="#${view}"]`
          : `#navigation [data-view="${view}"]`,
      )
      .click();
    await ui.loc(`#view-${view}`).waitFor();
  }
  async function studio() {
    await go("studio");
  }
  async function floor() {
    await go("floor");
    const overview = ui.role("tab", { name: "Overview", exact: true });
    if (await overview.count()) await overview.click();
  }
  async function at(value) {
    await ui.waitText("#clock", value);
  }
  async function next(value) {
    await studio();
    await ui.loc("#advance").click();
    await at(value);
  }
  async function reset() {
    await studio();
    await ui.loc("#reset").click();
    await ui.loc("#dialog-confirm").click();
    await ui.loc("#action-dialog").waitFor({ state: "hidden" });
    await at("19:00");
  }
  async function visit(label) {
    await ui.loc("#visit").getByText(label, { exact: true }).waitFor();
  }
  const phases = [
    [
      "guided-reconciliation",
      async () => {
        await floor();
        await ui.loc("#occupied").filter({ hasNotText: "—" }).waitFor();
        await reset();
        await next("19:08");
        await floor();
        assert.equal(await ui.loc("#alerts").textContent(), "1");
        await next("19:10");
        await floor();
        await visit("Time to first order");
        const stats = await ui.loc(".detail-stat strong").allTextContents();
        assert.match(stats[0], /^4\s*min$/);
        assert.match(stats[1], /^6\s*min$/);
        await ui.role("tab", { name: "Timeline", exact: true }).click();
        await ui.loc(".timeline-item").first().waitFor();
        const timeline = await ui.loc(".timeline").innerText();
        assert.match(timeline, /19:04:00/);
        assert.match(timeline, /19:10:00/);
        assert.match(timeline, /Delayed POS delivery/);
        await next("19:11");
        await go("activity");
        await ui.loc(".activity-row").first().waitFor();
        assert.equal(await ui.loc(".activity-row").count(), 2);
        assert.equal(
          await ui
            .loc(".activity-row")
            .filter({ hasText: "POS order confirmed" })
            .count(),
          1,
        );
        await next("19:15");
        await floor();
        await ui
          .loc("#visit")
          .getByRole("button", { name: "Seat guests", exact: true })
          .waitFor();
        await next("19:16");
        await next("19:17");
        await floor();
        await visit("Awaiting first order");
        assert.match(await ui.loc("#visit").innerText(), /Not confirmed/);
        assert.match(
          await ui.loc(".recent-visits").innerText(),
          /Completed visits/i,
        );
      },
    ],
    [
      "manual-host-and-order",
      async () => {
        await ui.loc('[data-table="1"]').click();
        await ui
          .loc("#visit")
          .getByRole("button", { name: "Seat guests", exact: true })
          .click();
        await ui.label("Number of guests", { exact: true }).fill("2");
        await ui.label("Seating note (optional)", { exact: true }).fill(note);
        await ui.loc("#dialog-confirm").click();
        await ui.loc("#action-dialog").waitFor({ state: "hidden" });
        await ui.loc(".visit-note").waitFor();
        assert.ok((await ui.loc(".visit-note").innerText()).includes(note));
        assert.equal(await ui.loc("#visit b").count(), 0);
        await ui
          .role("button", { name: "Register POS order", exact: true })
          .click();
        await visit("Time to first order");
        await ui.role("button", { name: "Close visit", exact: true }).click();
        await ui.loc("#dialog-confirm").click();
        await ui.loc("#action-dialog").waitFor({ state: "hidden" });
        await ui
          .loc("#visit")
          .getByRole("button", { name: "Seat guests", exact: true })
          .waitFor();
      },
    ],
    [
      "history-and-csv",
      async () => {
        await go("history");
        await ui
          .label("Search visits", { exact: true })
          .fill("Synthetic UI check");
        await ui.waitText("#history-summary", "1 of 1 visits");
        const csv = await ui.download(() => ui.loc("#export-history").click());
        assert.match(csv, /""quoted""/);
        assert.match(csv, /<b>literal text<\/b>/);
        assert.ok(csv.includes("\"'=Synthetic UI check"));
        assert.equal(csv.trim().split(/\r?\n/).length, 2);
      },
    ],
    [
      "narrow-screen-and-keys",
      async () => {
        await ui.viewport("narrow");
        assert.equal(await ui.loc("#history-cards").isVisible(), true);
        assert.equal(
          await ui.dom(() => document.documentElement.scrollWidth > innerWidth),
          false,
        );
        await floor();
        assert.equal(
          await ui.dom(
            () =>
              getComputedStyle(
                document.querySelector(".table-grid"),
              ).gridTemplateColumns.split(" ").length,
          ),
          1,
        );
        assert.equal(
          await ui.dom(() => document.documentElement.scrollWidth > innerWidth),
          false,
        );
        await ui.loc('[data-table="1"]').press("ArrowDown");
        assert.equal(
          await ui.dom(() => document.activeElement.dataset.table),
          "2",
        );
        await ui.screenshot("ui-narrow.jpg");
        await ui.viewport("wide");
      },
    ],
    [
      "help-and-preferences",
      async () => {
        await ui.loc('[data-table="2"]').press("Shift+/");
        await ui.loc("#info-dialog").waitFor();
        await ui.loc("#info-close").press("Escape");
        await ui.loc("#info-dialog").waitFor({ state: "hidden" });
        await ui.loc("#preferences").click();
        await ui.label("Compact table spacing", { exact: true }).check();
        await ui.loc("#info-close").click();
        await ui.reload();
        assert.equal(
          await ui.dom(() => document.body.classList.contains("compact")),
          true,
        );
        await ui.loc("#preferences").click();
        await ui.label("Compact table spacing", { exact: true }).uncheck();
        await ui.loc("#info-close").click();
      },
    ],
    [
      "manual-clock-and-restoration",
      async () => {
        await reset();
        await ui.loc("#minute").click();
        await at("19:01");
        assert.match(
          await ui.loc("#scenario-progress").innerText(),
          /Manual mode/,
        );
        assert.equal(await ui.loc("#advance").isEnabled(), false);
        await ui.loc("#sample-studio").click();
        await ui.loc("#dialog-confirm").click();
        await ui.loc("#action-dialog").waitFor({ state: "hidden" });
        await ui.waitText("#occupied", "8");
        await floor();
        await ui.loc('[data-table="12"]').click();
        await visit("Awaiting first order");
        await ui.screenshot("ui-desktop.jpg");
      },
    ],
    [
      "manual-fixture-pauses-guided",
      async () => {
        await reset();
        await floor();
        await visit("Awaiting first order");
        await ui.role("button", { name: "Close visit", exact: true }).click();
        await ui.loc("#dialog-confirm").click();
        await ui.loc("#action-dialog").waitFor({ state: "hidden" });
        await studio();
        assert.equal(
          await ui.loc("#advance").isEnabled(),
          false,
          "Manual fixture closure must pause the guided scenario",
        );
        assert.match(
          await ui.loc("#scenario-progress").innerText(),
          /Manual mode/,
        );
        await reset();
        await ui.loc("#pos-connection").click();
        await ui.waitText("#scenario-progress", "Manual mode");
        assert.equal(await ui.loc("#advance").isEnabled(), false);
        await ui.loc("#sample-studio").click();
        await ui.loc("#dialog-confirm").click();
        await ui.loc("#action-dialog").waitFor({ state: "hidden" });
        await ui.waitText("#occupied", "8");
        await floor();
        await ui.loc('[data-table="12"]').click();
        await visit("Awaiting first order");
        await ui.screenshot("ui-desktop.jpg");
      },
    ],
  ];
  async function runPhase(index) {
    const [name, run] = phases[index];
    const started = Date.now();
    try {
      await run();
      const result = {
        name,
        status: "passed",
        durationMs: Date.now() - started,
      };
      results.push(result);
      return result;
    } catch (error) {
      const result = {
        name,
        status: "failed",
        durationMs: Date.now() - started,
        error: error.message,
      };
      results.push(result);
      await ui.screenshot("ui-failure.jpg");
      throw error;
    }
  }
  return { phaseNames: phases.map(([name]) => name), results, runPhase };
}
