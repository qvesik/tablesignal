import { icon, hydrateIcons } from "./icons.js";
import { request } from "./api.js";

const $ = (id) => document.getElementById(id);
const store = {
  read(key, fallback) {
    try {
      return JSON.parse(localStorage.getItem("tablesignal." + key)) ?? fallback;
    } catch {
      return fallback;
    }
  },
  write(key, value) {
    try {
      localStorage.setItem("tablesignal." + key, JSON.stringify(value));
    } catch {}
  },
};
const state = {
  floor: null,
  view: "floor",
  selected: 12,
  historical: null,
  detailTab: "overview",
  area: "all",
  filter: "all",
  layout: store.read("layout", "grid"),
  busy: false,
  loading: false,
  connected: false,
  historyState: "all",
  historySearch: "",
  historySort: "newest",
  historyPage: 0,
  activitySource: "all",
  activity: [],
  queue: [],
  version: 0,
  readEpoch: 0,
  zoneCache: null,
  detailCache: null,
  historyCache: null,
  activityCache: null,
  pendingPos: new Map(),
  errorNetwork: false,
  toastTimer: null,
  dialogAction: null,
};
const scenarios = [
  [
    "19:00",
    "Guests take their seats",
    "A new visit opens at table 12. The first-order timer starts. The POS is offline.",
  ],
  [
    "19:08",
    "A confirmation is still missing",
    "The offline POS recorded an order at 19:04. The floor does not know that yet, so one alert opens.",
  ],
  [
    "19:10",
    "The delayed order arrives",
    "The POS reconnects. Four minutes to the first order; six minutes in delivery. The alert is resolved with its reason.",
  ],
  [
    "19:11",
    "A safe repeat delivery",
    "The same event is delivered again. No extra order, alert or metric change.",
  ],
  [
    "19:15",
    "The visit is closed",
    "Table 12 becomes available. Its complete visit and event history stay accessible.",
  ],
  [
    "19:16",
    "A new visit, at the same table",
    "New guests are seated with their own visit ID and first-order timer.",
  ],
  [
    "19:17",
    "An old event finds the right visit",
    "A late event belongs to the previous visit. The current guests are still waiting for their own order.",
  ],
];
const statusClass = (status) =>
  ({
    Free: "free",
    Seated: "seated",
    "Order confirmed": "confirmed",
    "Awaiting POS confirmation": "alert",
    Completed: "completed",
  })[status] || "free";
const statusShort = (status) =>
  status === "Awaiting POS confirmation" ? "Awaiting POS confirmation" : status;
const titles = {
  floor: "Floor overview",
  history: "Visit history",
  activity: "Activity log",
  studio: "Demo studio",
};
function el(tag, text, cls) {
  const n = document.createElement(tag);
  if (text !== undefined && text !== null) n.textContent = String(text);
  if (cls) n.className = cls;
  return n;
}
function format(value, options) {
  return value
    ? new Intl.DateTimeFormat("en-GB", {
        timeZone: "Europe/Tirane",
        ...options,
      }).format(new Date(value))
    : "—";
}
const time = (value) => format(value, { hour: "2-digit", minute: "2-digit" });
const seconds = (value) =>
  format(value, { hour: "2-digit", minute: "2-digit", second: "2-digit" });
const date = (value) =>
  format(value, { day: "numeric", month: "short", year: "numeric" });
const fullTime = (value) =>
  value ? `${date(value)} · ${seconds(value)}` : "—";
const minutes = (value) =>
  value == null ? "—" : `${Number(value.toFixed(1))} min`;
const elapsed = (start, end) =>
  start && end ? Math.max(0, (new Date(end) - new Date(start)) / 60000) : null;
function pill(status) {
  const cls = statusClass(status);
  const n = el(
    "span",
    statusShort(status),
    "pill " +
      {
        free: "neutral",
        seated: "purple",
        confirmed: "green",
        alert: "amber",
        completed: "neutral",
      }[cls],
  );
  return n;
}
function addButton(label, action, classes = "button secondary", symbol) {
  const b = el("button", undefined, classes);
  b.type = "button";
  if (symbol) b.append(icon(symbol));
  b.append(document.createTextNode(label));
  b.onclick = action;
  return b;
}
function toast(message) {
  clearTimeout(state.toastTimer);
  $("toast").replaceChildren(icon("check"), el("span", message));
  $("toast").hidden = false;
  state.toastTimer = setTimeout(() => ($("toast").hidden = true), 4500);
}
function problem(error) {
  state.errorNetwork = !error.status;
  $("problem-text").textContent = error.message;
  $("problem").hidden = false;
}
function setConnection(ok) {
  state.connected = ok;
  $("connection").classList.toggle("stale", !ok);
  $("connection").replaceChildren(
    el("i"),
    document.createTextNode(
      ok ? "Connected to server" : "Connection interrupted",
    ),
  );
}
function applyPreferences() {
  document.body.classList.toggle("compact", store.read("compact", false));
  document.body.classList.toggle(
    "high-contrast",
    store.read("contrast", false),
  );
}
function busyControls() {
  for (const field of document.querySelectorAll(
    "#action-dialog input,#action-dialog select,#action-dialog textarea,#action-dialog [data-close-dialog]",
  ))
    field.disabled = state.busy;
  for (const b of document.querySelectorAll(
    "[data-mutation],#seat-top,#sample-service,#sample-studio,#reset,#advance,#minute,#pos-connection,#pos-record,#pos-retry,[data-minutes]",
  )) {
    b.disabled = state.busy || !state.floor;
    if (b.id === "advance")
      b.disabled ||= state.floor?.step >= 6 || state.floor?.step === -2;
    if (b.id === "seat-top")
      b.disabled ||= !state.floor?.tables.some((t) => !t.visit);
    if (b.id === "pos-record") b.disabled ||= !$("pos-table").value;
  }
}
async function load({ silent = false } = {}) {
  if (state.loading || state.busy) return;
  state.loading = true;
  $("refresh").disabled = true;
  $("refresh").classList.add("is-syncing");
  $("refresh").setAttribute("aria-busy", "true");
  const epoch = state.readEpoch;
  try {
    const nextFloor = await request("/api/floor");
    if (epoch !== state.readEpoch) return;
    state.floor = nextFloor;
    setConnection(true);
    if (!silent || state.errorNetwork) $("problem").hidden = true;
    render();
    await loadAuxiliary();
  } catch (error) {
    setConnection(false);
    problem(error);
  } finally {
    state.loading = false;
    $("refresh").disabled = false;
    $("refresh").classList.remove("is-syncing");
    $("refresh").removeAttribute("aria-busy");
    busyControls();
  }
}
async function loadAuxiliary() {
  const version = state.version;
  try {
    if (state.view === "activity") {
      const rows = await request("/api/activity?limit=100");
      if (version === state.version) {
        state.activity = rows;
        renderActivity();
      }
    }
    if (state.view === "studio" && state.floor?.localDemo) {
      const rows = await request("/api/demo/pos/queue");
      if (version === state.version) {
        state.queue = rows;
        renderQueue();
      }
    }
  } catch (error) {
    problem(error);
  }
}
async function mutate(path, body, message) {
  if (state.busy) return false;
  state.busy = true;
  state.readEpoch++;
  busyControls();
  try {
    const result = await request(path, body);
    state.floor = result.tables ? result : await request("/api/floor");
    setConnection(true);
    $("problem").hidden = true;
    render();
    await loadAuxiliary();
    if (message) toast(message);
    return true;
  } catch (error) {
    problem(error);
    if (!error.status) setConnection(false);
    throw error;
  } finally {
    state.busy = false;
    busyControls();
  }
}
function setView(view) {
  if (
    !titles[view] ||
    (view === "studio" && state.floor && !state.floor.localDemo)
  )
    view = "floor";
  state.view = view;
  state.version++;
  state.detailCache = null;
  for (const [name, title] of Object.entries(titles)) {
    $("view-" + name).hidden = name !== view;
    for (const a of document.querySelectorAll(
      `[data-view="${name}"],.mobile-navigation a[href="#${name}"]`,
    )) {
      a.classList.toggle("active", name === view);
      if (name === view) a.setAttribute("aria-current", "page");
      else a.removeAttribute("aria-current");
    }
  }
  $("breadcrumb-label").textContent = "Workspace / " + titles[view];
  document.title = titles[view] + " · TableSignal";
  if (state.floor) {
    if (view === "floor") renderDetail();
    if (view === "history") renderHistory();
    if (view === "studio") renderStudio();
    loadAuxiliary();
  }
}
function render() {
  const f = state.floor;
  if (!f) return;
  const activeKey = document.activeElement?.dataset?.focusKey;
  $("today").textContent = date(f.clock);
  $("service-date").textContent = format(f.clock, {
    day: "numeric",
    month: "long",
  }).toUpperCase();
  $("occupied").textContent = f.metrics.occupied;
  $("alerts").textContent = f.metrics.alerts;
  $("average").textContent = minutes(f.metrics.avgWaitMinutes);
  $("guests").textContent = f.metrics.guests ?? 0;
  $("occupancy-ring").style.setProperty(
    "--percent",
    `${(f.metrics.occupied / 12) * 100}%`,
  );
  $("occupancy-caption").textContent =
    `${Math.round((f.metrics.occupied / 12) * 100)}% of your floor is occupied`;
  $("alert-caption").textContent = f.metrics.alerts
    ? "Awaiting a POS confirmation"
    : "No open POS alerts";
  $("available-count").textContent = 12 - f.metrics.occupied;
  $("history-count").textContent = f.visits.length;
  for (const id of [
    "studio-nav",
    "mobile-studio",
    "demo-strip",
    "sample-service",
  ])
    $(id).hidden = !f.localDemo;
  $("last-updated").textContent =
    "Last synced " +
    new Date().toLocaleTimeString("en-GB", {
      timeZone: "Europe/Tirane",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  $("step-text").textContent = scenarios[f.step]
    ? scenarios[f.step][2]
    : "A guided scenario with synthetic data and a controllable clock.";
  renderZones();
  if (state.view === "floor") renderDetail();
  if (state.view === "history") renderHistory();
  if (state.view === "studio") renderStudio();
  if (activeKey)
    document
      .querySelector(`[data-focus-key="${activeKey}"]`)
      ?.focus({ preventScroll: true });
  busyControls();
}
function renderZones() {
  const signature = JSON.stringify([
    state.selected,
    state.historical,
    state.area,
    state.filter,
    state.layout,
    state.floor.tables,
  ]);
  if (signature === state.zoneCache) return;
  state.zoneCache = signature;
  const f = state.floor,
    zones = $("zones");
  zones.classList.toggle("is-list", state.layout === "list");
  const result = document.createDocumentFragment();
  let count = 0;
  for (const zone of ["Dining room", "Terrace"]) {
    if (state.area !== "all" && state.area !== zone) continue;
    const tables = f.tables.filter(
      (t) =>
        t.zone === zone &&
        (state.filter === "all" || t.status === state.filter),
    );
    if (!tables.length) continue;
    count += tables.length;
    const section = el("section", undefined, "zone");
    section.dataset.zone = zone;
    const heading = el("div", undefined, "zone-heading");
    const label = el("h3", undefined, "zone-label");
    label.append(
      icon(zone === "Terrace" ? "terrace" : "room"),
      el("span", zone),
    );
    heading.append(
      label,
      el(
        "span",
        `${tables.filter((t) => t.visit).length} occupied · ${tables.length} tables`,
      ),
    );
    const grid = el("div", undefined, "table-grid");
    for (const t of tables) {
      const tile = el(
        "button",
        undefined,
        "table-tile is-" + statusClass(t.status),
      );
      tile.type = "button";
      tile.dataset.table = t.number;
      tile.dataset.focusKey = "table-" + t.number;
      tile.setAttribute(
        "aria-pressed",
        String(state.selected === t.number && !state.historical),
      );
      tile.setAttribute(
        "aria-label",
        `Table ${t.number}, ${t.zone}, ${t.status}${t.waitMinutes == null ? "" : ", " + minutes(t.waitMinutes)}. Capacity ${t.capacity}.`,
      );
      tile.setAttribute("aria-controls", "detail-panel");
      const top = el("span", undefined, "tile-top");
      top.append(
        el(
          "span",
          `${t.visit ? t.visit.party_size : t.capacity} ${t.visit ? "guests" : "seats"}`,
        ),
        el("i", undefined, "dot " + statusClass(t.status)),
      );
      const furniture = el("span", undefined, "table-furniture " + t.shape);
      furniture.setAttribute("aria-hidden", "true");
      furniture.append(
        el("b", String(t.number).padStart(2, "0")),
        el("small", "TABLE"),
      );
      for (const pos of [
        "top",
        "bottom",
        ...(t.capacity > 2 ? ["left", "right"] : []),
        ...(t.capacity > 4 ? ["extra-left", "extra-right"] : []),
      ])
        furniture.append(el("i", undefined, "chair " + pos));
      tile.append(
        top,
        furniture,
        el("span", t.status, "tile-status"),
        el(
          "span",
          t.visit
            ? t.status === "Order confirmed"
              ? `${minutes(t.waitMinutes)} to order`
              : `${Math.floor(t.waitMinutes)} min`
            : "Available",
          "tile-time",
        ),
      );
      tile.onclick = () => selectTable(t.number);
      tile.onkeydown = (event) => tableKeys(event, tile, grid);
      grid.append(tile);
    }
    section.append(heading, grid);
    result.append(section);
  }
  zones.replaceChildren(result);
  if (!count)
    zones.append(
      empty(
        "No tables match this view",
        "Choose another area or status to see your tables.",
        "search",
      ),
    );
  for (const mode of ["grid", "list"])
    $(mode).setAttribute("aria-pressed", String(state.layout === mode));
}
function tableKeys(event, tile, grid) {
  const keys = [
    "ArrowLeft",
    "ArrowRight",
    "ArrowUp",
    "ArrowDown",
    "Home",
    "End",
  ];
  if (!keys.includes(event.key)) return;
  event.preventDefault();
  const buttons = [...grid.querySelectorAll("[data-table]")],
    index = buttons.indexOf(tile);
  const cols = getComputedStyle(grid).gridTemplateColumns.split(" ").length;
  const move = {
    ArrowLeft: -1,
    ArrowRight: 1,
    ArrowUp: -cols,
    ArrowDown: cols,
  };
  const next =
    event.key === "Home"
      ? 0
      : event.key === "End"
        ? buttons.length - 1
        : Math.max(0, Math.min(buttons.length - 1, index + move[event.key]));
  buttons[next].focus();
}
function selectTable(number, visitId = null) {
  state.selected = number;
  state.historical = visitId;
  state.detailTab = "overview";
  renderZones();
  renderDetail();
  if (matchMedia("(max-width:650px)").matches)
    $("detail-panel").scrollIntoView({
      behavior: matchMedia("(prefers-reduced-motion:reduce)").matches
        ? "auto"
        : "smooth",
      block: "start",
    });
}
function empty(title, copy, symbol = "history") {
  const n = el("div", undefined, "empty-state");
  n.append(icon(symbol), el("strong", title), el("p", copy));
  return n;
}
function visitState(v) {
  return v.closed_at
    ? "Completed"
    : v.first_order
      ? "Order confirmed"
      : v.alert_opened && !v.alert_resolved
        ? "Awaiting POS confirmation"
        : "Seated";
}
function recordVisit(id) {
  const v = state.floor.visits.find((v) => v.visit_id === id);
  if (!v) return;
  state.area = "all";
  state.filter = "all";
  $("floor-filter").value = "all";
  for (const button of $("area-tabs").children)
    button.setAttribute("aria-pressed", String(button.dataset.area === "all"));
  location.hash = "floor";
  setView("floor");
  selectTable(v.table_id, v.closed_at ? id : null);
}
async function renderDetail() {
  const focusToRestore = document.activeElement?.dataset?.focusKey;
  let renderId;
  const t = state.floor.tables.find((t) => t.number === state.selected);
  if (!t) return;
  const v = state.historical
    ? state.floor.visits.find((v) => v.visit_id === state.historical)
    : t.visit;
  const signature = JSON.stringify([
    state.selected,
    state.historical,
    state.detailTab,
    v,
    state.floor.revision,
    Math.floor(new Date(state.floor.clock).getTime() / 60000),
  ]);
  if (signature === state.detailCache) return;
  state.detailCache = signature;
  renderId = ++state.version;
  const box = $("visit");
  const header = el("div", undefined, "detail-header");
  const top = el("div", undefined, "detail-header-top");
  top.append(
    el("span", t.zone.toUpperCase()),
    icon(t.zone === "Terrace" ? "terrace" : "room"),
  );
  const h = el("h2", "Table " + String(t.number).padStart(2, "0"));
  h.append(el("span", `${t.capacity} seats`));
  header.append(top, h, pill(v ? visitState(v) : "Free"));
  box.replaceChildren(header);
  if (!v) {
    const content = el("div", undefined, "detail-content");
    const ready = el("div", undefined, "empty-table");
    const art = el("div", undefined, "empty-art");
    art.append(icon("seat"));
    ready.append(
      art,
      el("h3", "Ready for new guests"),
      el("p", "Start a visit to track seating and the first POS order."),
    );
    const b = addButton(
      "Seat guests",
      () => openSeat(t.number),
      "button primary full",
      "plus",
    );
    b.dataset.mutation = "";
    b.dataset.focusKey = "visit-new";
    ready.append(b);
    content.append(ready);
    box.append(content);
    appendRecent(box, t);
    busyControls();
    return;
  }
  const tabs = el("div", undefined, "detail-tabs");
  tabs.setAttribute("role", "tablist");
  tabs.setAttribute("aria-label", "Visit details");
  for (const [key, label] of [
    ["overview", "Overview"],
    ["timeline", "Timeline"],
  ]) {
    const b = addButton(
      label,
      () => {
        state.detailTab = key;
        renderDetail();
      },
      "",
    );
    b.setAttribute("role", "tab");
    b.setAttribute("aria-selected", String(state.detailTab === key));
    b.tabIndex = state.detailTab === key ? 0 : -1;
    b.onkeydown = (event) => {
      if (["ArrowLeft", "ArrowRight"].includes(event.key)) {
        event.preventDefault();
        state.detailTab =
          state.detailTab === "overview" ? "timeline" : "overview";
        renderDetail();
        document
          .querySelector(`[data-focus-key="detail-${state.detailTab}"]`)
          ?.focus();
      }
    };
    b.dataset.focusKey = "detail-" + key;
    tabs.append(b);
  }
  box.append(tabs);
  const content = el("div", undefined, "detail-content");
  content.setAttribute("role", "tabpanel");
  content.setAttribute(
    "aria-label",
    state.detailTab === "overview" ? "Visit overview" : "Visit timeline",
  );
  box.append(content);
  if (state.historical) {
    content.append(
      addButton(
        "Back to current table",
        () => selectTable(t.number),
        "text-button",
        "chevron-left",
      ),
    );
  }
  const pending = el("div", undefined, "loading-state visit-loading");
  pending.append(
    el("span", undefined, "loader"),
    el("span", "Loading visit history…"),
  );
  content.append(pending);
  let history;
  try {
    history = await request("/api/visits/" + encodeURIComponent(v.visit_id));
  } catch (error) {
    if (renderId === state.version) {
      state.detailCache = null;
      pending.remove();
      content.append(empty("History is unavailable", error.message, "alert"));
    }
    return;
  }
  if (renderId !== state.version) return;
  pending.remove();
  if (state.detailTab === "timeline") renderTimeline(content, history);
  else renderVisitOverview(content, t, history);
  content.append(el("p", "Visit " + v.visit_id, "detail-visit-id"));
  appendRecent(box, t, v.visit_id);
  if (focusToRestore && document.activeElement === document.body)
    document
      .querySelector(`[data-focus-key="${focusToRestore}"]`)
      ?.focus({ preventScroll: true });
  busyControls();
}
function renderVisitOverview(content, t, history) {
  const v = history.visit,
    order = history.events.find(
      (e) =>
        e.type === "ORDER_RECORDED" &&
        new Date(e.occurred_at).getTime() === new Date(v.first_order).getTime(),
    );
  const wait = v.first_order
    ? elapsed(v.seated_at, v.first_order)
    : v.closed_at
      ? null
      : elapsed(v.seated_at, state.floor.clock);
  const delivery = order ? elapsed(order.occurred_at, order.received_at) : null;
  const pair = el("div", undefined, "detail-stat-pair");
  for (const [label, value, caption] of [
    [
      v.first_order ? "Time to first order" : "Awaiting first order",
      wait,
      v.first_order
        ? "From occurrence time"
        : v.closed_at
          ? "No confirmed order"
          : "Since guests were seated",
    ],
    ["Delivery delay", delivery, "For the first POS order"],
  ]) {
    const stat = el("div", undefined, "detail-stat");
    const strong = el("strong", value == null ? "—" : Number(value.toFixed(1)));
    if (value != null) strong.append(el("span", "min"));
    stat.append(el("small", label), strong, el("p", caption));
    pair.append(stat);
  }
  content.append(pair);
  const facts = el("dl", undefined, "visit-facts");
  for (const [label, value, symbol] of [
    ["Guests seated", time(v.seated_at), "seat"],
    [
      "First order",
      v.first_order ? time(v.first_order) : "Not confirmed",
      "receipt",
    ],
    ["Party size", `${v.party_size} guests`, "guests"],
    ...(v.closed_at ? [["Visit closed", time(v.closed_at), "leave"]] : []),
  ]) {
    const dt = el("dt");
    dt.append(icon(symbol), document.createTextNode(label));
    facts.append(dt, el("dd", value));
  }
  content.append(facts);
  if (v.seating_note) {
    const note = el("p", undefined, "visit-note");
    note.append(
      el("strong", "SEATING NOTE"),
      document.createTextNode(v.seating_note),
    );
    content.append(note);
  }
  if (v.alert_opened) {
    const n = el(
      "div",
      undefined,
      "alert-explanation" + (v.alert_resolved ? " resolved" : ""),
    );
    const strong = el("strong");
    strong.append(
      icon(v.alert_resolved ? "check" : "alert"),
      document.createTextNode(
        v.alert_resolved ? "Alert resolved" : "Awaiting POS confirmation",
      ),
    );
    n.append(
      strong,
      el(
        "p",
        v.alert_resolved
          ? v.resolution
          : "No order has been confirmed by the POS after eight minutes. Check the source before drawing conclusions about service.",
      ),
      el(
        "p",
        `Opened ${time(v.alert_opened)}${v.alert_resolved ? " · Resolved " + time(v.alert_resolved) : ""}`,
      ),
    );
    content.append(n);
  }
  if (!v.closed_at) {
    const actions = el("div", undefined, "detail-actions");
    if (state.floor.localDemo) {
      const orderBtn = addButton(
        state.floor.posOnline
          ? "Register POS order"
          : "Queue offline POS order",
        () => registerPos(t.number, v.visit_id),
        "button primary full",
        "receipt",
      );
      orderBtn.dataset.mutation = "";
      orderBtn.dataset.focusKey = "visit-order";
      actions.append(orderBtn);
    }
    const close = addButton(
      "Close visit",
      () => openClose(v),
      "button danger full",
      "leave",
    );
    close.dataset.mutation = "";
    close.dataset.focusKey = "visit-close";
    actions.append(close);
    content.append(actions);
  }
  const note = el("p", undefined, "detail-footnote");
  note.append(
    icon("info"),
    document.createTextNode(
      "POS confirms order registration. It does not establish when a waiter approached.",
    ),
  );
  content.append(note);
}
function renderTimeline(content, h) {
  const list = el("ol", undefined, "timeline");
  const entries = h.events.map((e) => ({
    at: e.received_at,
    type: e.type,
    event: e,
  }));
  if (h.visit.alert_opened)
    entries.push({ at: h.visit.alert_opened, type: "ALERT_OPENED" });
  if (h.visit.alert_resolved)
    entries.push({ at: h.visit.alert_resolved, type: "ALERT_RESOLVED" });
  entries.sort((a, b) => new Date(a.at) - new Date(b.at));
  for (const item of entries) {
    const row = el(
      "li",
      undefined,
      "timeline-item" +
        (item.type === "ALERT_OPENED"
          ? " is-alert"
          : item.type === "ORDER_RECORDED"
            ? " is-order"
            : ""),
    );
    row.append(el("i", undefined, "timeline-marker"));
    const labels = {
      SEATED: "Guests seated",
      ORDER_RECORDED: "POS order received",
      CLOSED: "Visit closed",
      ALERT_OPENED: "Alert opened",
      ALERT_RESOLVED: "Alert resolved",
    };
    row.append(el("strong", labels[item.type]));
    if (item.event) {
      const e = item.event;
      row.append(
        el("p", `Occurred ${fullTime(e.occurred_at)}`),
        el("p", `Received ${fullTime(e.received_at)}`),
      );
      const src = el("div", undefined, "timeline-source");
      src.append(el("span", e.source + " ·"), el("code", e.event_id));
      row.append(src);
      if (e.type === "ORDER_RECORDED")
        row.append(
          el(
            "span",
            "Delivery delay " + minutes(elapsed(e.occurred_at, e.received_at)),
            "delivery-badge",
          ),
        );
    } else {
      row.append(
        el("p", fullTime(item.at)),
        el(
          "p",
          item.type === "ALERT_RESOLVED"
            ? h.visit.resolution
            : "No first order known at observation time.",
        ),
      );
    }
    list.append(row);
  }
  content.append(list);
}
function appendRecent(box, t, exclude) {
  const rows = state.floor.visits
    .filter(
      (v) => v.table_id === t.number && v.closed_at && v.visit_id !== exclude,
    )
    .reverse()
    .slice(0, 3);
  if (!rows.length) return;
  const section = el("div", undefined, "recent-visits");
  section.append(el("h3", "Completed visits"));
  for (const v of rows) {
    const b = addButton(
      "",
      () => {
        state.historical = v.visit_id;
        state.detailTab = "timeline";
        renderDetail();
      },
      "recent-visit",
    );
    b.append(
      el("span", `${date(v.seated_at)} · ${time(v.seated_at)}`),
      el(
        "span",
        v.first_order
          ? minutes(elapsed(v.seated_at, v.first_order))
          : "No order",
      ),
      icon("chevron-right"),
    );
    section.append(b);
  }
  box.append(section);
}
function filteredVisits() {
  const q = state.historySearch.trim().toLowerCase();
  return state.floor.visits
    .filter(
      (v) =>
        (state.historyState === "all" ||
          (state.historyState === "closed") === Boolean(v.closed_at)) &&
        (!q ||
          [
            String(v.table_id),
            `table ${v.table_id}`,
            v.visit_id,
            v.seating_note,
            visitState(v),
          ].some((x) =>
            String(x || "")
              .toLowerCase()
              .includes(q),
          )),
    )
    .sort(
      (a, b) =>
        (new Date(b.seated_at) - new Date(a.seated_at)) *
        (state.historySort === "newest" ? 1 : -1),
    );
}
function renderHistory() {
  const signature = JSON.stringify([
    state.floor.visits,
    state.historyState,
    state.historySearch,
    state.historySort,
    state.historyPage,
  ]);
  if (signature === state.historyCache) return;
  state.historyCache = signature;
  const rows = filteredVisits(),
    pageSize = 10,
    totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
  state.historyPage = Math.min(state.historyPage, totalPages - 1);
  const body = $("history-rows");
  const cards = $("history-cards");
  cards.replaceChildren();
  body.replaceChildren();
  for (const v of rows.slice(
    state.historyPage * pageSize,
    (state.historyPage + 1) * pageSize,
  )) {
    const tr = el("tr");
    const cell = el("td");
    const open = addButton(
      "",
      () => recordVisit(v.visit_id),
      "record-table-button",
    );
    open.setAttribute(
      "aria-label",
      `Open visit for Table ${v.table_id}, seated ${fullTime(v.seated_at)}`,
    );
    const meta = el("span");
    meta.append(
      el("strong", `Table ${String(v.table_id).padStart(2, "0")}`),
      el(
        "small",
        state.floor.tables.find((t) => t.number === v.table_id)?.zone,
      ),
    );
    open.append(
      el("span", String(v.table_id).padStart(2, "0"), "record-table-number"),
      meta,
    );
    cell.append(open);
    tr.append(
      cell,
      el("td", `${v.party_size} guests`),
      el("td", fullTime(v.seated_at)),
      el(
        "td",
        v.first_order ? minutes(elapsed(v.seated_at, v.first_order)) : "—",
      ),
      el("td", v.closed_at ? time(v.closed_at) : "—"),
    );
    const status = el("td");
    status.append(pill(visitState(v)));
    const action = el("td");
    const b = addButton(
      "",
      () => recordVisit(v.visit_id),
      "icon-button",
      "chevron-right",
    );
    b.setAttribute("aria-label", "Open visit " + v.visit_id);
    action.append(b);
    tr.append(status, action);
    body.append(tr);
    const card = el("article", undefined, "history-card");
    const top = el("div");
    const title = el("strong", `Table ${String(v.table_id).padStart(2, "0")}`);
    top.append(title, pill(visitState(v)));
    const facts = el("p", `${fullTime(v.seated_at)} · ${v.party_size} guests`);
    const bottom = el("div");
    bottom.append(
      el(
        "span",
        v.first_order
          ? "First order in " + minutes(elapsed(v.seated_at, v.first_order))
          : "No confirmed order",
      ),
      addButton(
        "Open visit",
        () => recordVisit(v.visit_id),
        "text-button",
        "arrow",
      ),
    );
    card.append(top, facts, bottom);
    cards.append(card);
  }
  $("history-empty").hidden = rows.length > 0;
  $("history-empty").replaceChildren(
    empty(
      "No visits in this view",
      state.historySearch
        ? "Try another table number or clear your search."
        : "Visits will appear here as guests are seated.",
      "history",
    ),
  );
  $("history-summary").textContent = rows.length
    ? `${state.historyPage * pageSize + 1}–${Math.min((state.historyPage + 1) * pageSize, rows.length)} of ${rows.length} visits`
    : "0 visits";
  $("history-page").textContent = `${state.historyPage + 1} / ${totalPages}`;
  $("history-prev").disabled = state.historyPage === 0;
  $("history-next").disabled = state.historyPage >= totalPages - 1;
  $("export-history").disabled = !rows.length;
}
function exportHistory() {
  const rows = filteredVisits();
  const columns = [
    "Table",
    "Visit ID",
    "Zone",
    "Party size",
    "Seated (Europe/Tirane)",
    "First order (Europe/Tirane)",
    "First-order wait (min)",
    "Closed (Europe/Tirane)",
    "Status",
    "Seating note",
  ];
  const safe = (value) => {
    let text = String(value ?? "");
    if (/^[\s]*[=+@-]/.test(text)) text = "'" + text;
    return '"' + text.replaceAll('"', '""') + '"';
  };
  const data = rows.map((v) => [
    v.table_id,
    v.visit_id,
    state.floor.tables.find((t) => t.number === v.table_id)?.zone,
    v.party_size,
    fullTime(v.seated_at),
    v.first_order ? fullTime(v.first_order) : "",
    v.first_order ? elapsed(v.seated_at, v.first_order) : "",
    v.closed_at ? fullTime(v.closed_at) : "",
    visitState(v),
    v.seating_note,
  ]);
  const blob = new Blob(
    [
      "\uFEFF" +
        [columns, ...data].map((row) => row.map(safe).join(",")).join("\r\n"),
    ],
    { type: "text/csv;charset=utf-8" },
  );
  const url = URL.createObjectURL(blob),
    a = el("a");
  a.href = url;
  a.download = "TableSignal-visits.csv";
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  toast(`${rows.length} ${rows.length === 1 ? "visit" : "visits"} exported`);
}
function renderActivity() {
  const signature = JSON.stringify([state.activity, state.activitySource]);
  if (signature === state.activityCache) return;
  state.activityCache = signature;
  const rows = state.activity.filter(
    (e) => state.activitySource === "all" || e.source === state.activitySource,
  );
  const list = $("activity-list");
  list.replaceChildren();
  if (!rows.length) {
    list.append(
      empty(
        "No accepted events yet",
        "Seat guests or register a POS order to see the durable source journal.",
        "activity",
      ),
    );
    return;
  }
  for (const e of rows) {
    const row = el("article", undefined, "activity-row");
    const mark = el(
      "span",
      undefined,
      "activity-icon" + (e.type === "ORDER_RECORDED" ? " order" : ""),
    );
    mark.append(
      icon(
        e.type === "ORDER_RECORDED"
          ? "receipt"
          : e.type === "SEATED"
            ? "seat"
            : "leave",
      ),
    );
    const desc = el("div");
    desc.append(
      el(
        "strong",
        `${{ SEATED: "Guests seated", ORDER_RECORDED: "POS order confirmed", CLOSED: "Visit closed" }[e.type]} · Table ${e.table_id}`,
      ),
      el("small", `${e.source} · ${e.event_id}`),
    );
    desc.append(
      el(
        "small",
        `Occurred ${fullTime(e.occurred_at)} · Received ${seconds(e.received_at)}`,
        "activity-mobile-times",
      ),
    );
    const occurred = el("div", undefined, "activity-time");
    occurred.append(
      el("span", "OCCURRED"),
      document.createTextNode(fullTime(e.occurred_at)),
    );
    const received = el("div", undefined, "activity-time");
    received.append(
      el("span", "RECEIVED"),
      document.createTextNode(fullTime(e.received_at)),
    );
    const b = addButton(
      "",
      () => recordVisit(e.visit_id),
      "icon-button",
      "chevron-right",
    );
    b.setAttribute("aria-label", "Open visit " + e.visit_id);
    row.append(
      mark,
      desc,
      occurred,
      received,
      el(
        "span",
        minutes(elapsed(e.occurred_at, e.received_at)),
        "activity-delay",
      ),
      b,
    );
    list.append(row);
  }
}
function renderStudio() {
  const f = state.floor;
  $("clock").textContent = time(f.clock);
  $("clock-date").textContent = date(f.clock);
  $("pos-pill").textContent = f.posOnline ? "Online" : "Offline";
  $("pos-pill").className = "pill " + (f.posOnline ? "green" : "amber");
  $("pos-status").textContent = f.posOnline
    ? "Orders are delivered immediately to the floor."
    : `Orders are stored locally. ${f.pendingOrders} queued for delivery.`;
  $("pos-connection").textContent = f.posOnline
    ? "Disconnect POS"
    : "Reconnect POS";
  const table = $("pos-table"),
    previous = table.value;
  table.replaceChildren();
  const open = f.tables.filter((t) => t.visit);
  if (!open.length) {
    const o = el("option", "No open visits");
    o.value = "";
    table.append(o);
  } else
    for (const t of open) {
      const o = el("option", `Table ${t.number} · ${t.zone}`);
      o.value = t.number;
      table.append(o);
    }
  if (open.some((t) => String(t.number) === previous)) table.value = previous;
  else if (open.some((t) => t.number === state.selected))
    table.value = state.selected;
  const list = $("scenario-steps");
  list.replaceChildren();
  scenarios.forEach(([when, title, copy], index) => {
    const item = el(
      "li",
      undefined,
      "scenario-step" +
        (index === f.step ? " current" : index < f.step ? " completed" : ""),
    );
    const n = el("span", undefined, "step-number");
    if (index < f.step) n.append(icon("check"));
    else n.textContent = String(index + 1).padStart(2, "0");
    const text = el("div", undefined, "step-content");
    text.append(
      el("small", when + " · EUROPE/TIRANE"),
      el("strong", title),
      el("p", copy),
    );
    item.append(n, text);
    list.append(item);
  });
  $("scenario-progress").textContent =
    f.step === -2
      ? "Manual mode · Restart to use guided steps"
      : f.step >= 0
        ? `Step ${Math.min(f.step + 1, 7)} of 7`
        : "Start or load the guided scenario";
  $("advance").disabled = state.busy || f.step >= 6 || f.step === -2;
  renderQueue();
  busyControls();
}
function renderQueue() {
  const box = $("pos-queue");
  box.replaceChildren();
  const rows = state.queue.filter((row) => !row.delivered);
  if (!rows.length) return;
  box.append(el("p", `${rows.length} pending orders`, "queue-heading"));
  for (const q of rows) {
    const n = el("div", undefined, "queue-item");
    n.append(
      el("span", `Table ${q.table_id}`),
      el("span", "Recorded " + time(q.occurred_at)),
    );
    box.append(n);
  }
}
function actionDialog({
  title,
  eyebrow = "NEW VISIT",
  body,
  confirm = "Confirm",
  danger = false,
  run,
}) {
  const dialog = $("action-dialog");
  $("dialog-title").textContent = title;
  $("dialog-eyebrow").textContent = eyebrow;
  $("dialog-body").replaceChildren(body);
  $("dialog-confirm").textContent = confirm;
  $("dialog-confirm").className =
    "button " + (danger ? "danger-solid" : "primary");
  $("dialog-confirm").disabled = false;
  $("dialog-error").hidden = true;
  state.dialogAction = run;
  dialog.showModal();
}
function field(label, id, input, hint) {
  const n = el("div", undefined, "form-field");
  const name = el("label", label, "field-label");
  name.htmlFor = id;
  input.id = id;
  n.append(name, input);
  if (hint) n.append(el("div", hint, "field-hint"));
  return n;
}
function sourceEndpoint() {
  return state.floor.localDemo ? "/api/demo/events" : "/api/events";
}

function sourceEvent(type, table, visit, extras = {}) {
  return {
    venueId: "durres-demo",
    source: "hostess",
    eventId: crypto.randomUUID(),
    tableId: table,
    visitId: visit || crypto.randomUUID(),
    type,
    occurredAt: state.floor.clock,
    ...extras,
  };
}
function openSeat(preferred) {
  if (!state.floor || state.busy) return;
  const free = state.floor.tables.filter((t) => !t.visit);
  if (!free.length) {
    toast("All tables are occupied");
    return;
  }
  const body = el("div");
  const select = el("select");
  for (const t of free) {
    const o = el(
      "option",
      `Table ${t.number} · ${t.zone} · ${t.capacity} seats`,
    );
    o.value = t.number;
    select.append(o);
  }
  select.value = free.some((t) => t.number === preferred)
    ? preferred
    : free[0].number;
  const preview = el("div", undefined, "seating-preview");
  const number = el("span");
  const description = el("div");
  const name = el("strong"),
    sub = el("small");
  description.append(name, sub);
  preview.append(number, description);
  const party = el("input");
  party.type = "number";
  party.required = true;
  party.min = 1;
  party.value = 2;
  const note = el("textarea");
  note.maxLength = 240;
  note.rows = 3;
  note.placeholder =
    "e.g. High chair requested. Keep notes free of personal information.";
  let pending, signature;
  const update = () => {
    const t = free.find((t) => t.number === Number(select.value));
    number.textContent = String(t.number).padStart(2, "0");
    name.textContent = t.zone;
    sub.textContent = `Up to ${t.capacity} guests · Seating at ${time(state.floor.clock)}`;
    party.max = t.capacity;
    if (Number(party.value) > t.capacity) party.value = t.capacity;
  };
  select.onchange = update;
  update();
  body.append(
    preview,
    field("Table", "seat-table", select),
    field(
      "Number of guests",
      "seat-party",
      party,
      "A separate visit keeps each group’s order history independent.",
    ),
    field(
      "Seating note (optional)",
      "seat-note",
      note,
      "240 characters maximum. Synthetic operational notes only.",
    ),
  );
  actionDialog({
    title: "Make room for a new visit",
    body,
    confirm: "Seat guests",
    run: async () => {
      const values = {
        table: Number(select.value),
        party: Number(party.value),
        note: note.value.trim(),
      };
      const next = JSON.stringify(values);
      if (next !== signature) {
        pending = sourceEvent("SEATED", values.table, null, {
          partySize: values.party,
          seatingNote: values.note,
        });
        signature = next;
      }
      if (
        await mutate(
          sourceEndpoint(),
          pending,
          `Guests seated at table ${values.table}`,
        )
      ) {
        state.selected = values.table;
        state.historical = null;
        state.detailTab = "overview";
        selectTable(values.table);
        return true;
      }
    },
  });
}
function openClose(v) {
  const body = el("div");
  body.append(
    el(
      "p",
      `Close the current visit for table ${v.table_id}? The table will become available for another group.`,
    ),
    el(
      "p",
      "The visit, source events and alert history will remain accessible in Visit history.",
    ),
  );
  const pending = sourceEvent("CLOSED", v.table_id, v.visit_id);
  actionDialog({
    title: "Close this visit?",
    eyebrow: "COMPLETE VISIT",
    body,
    confirm: "Close visit",
    danger: true,
    run: async () =>
      mutate(sourceEndpoint(), pending, `Table ${v.table_id} is now available`),
  });
}
async function registerPos(table, visit) {
  const command = state.pendingPos.get(visit) || {
    tableId: table,
    visitId: visit,
    eventId: crypto.randomUUID(),
    occurredAt: state.floor.clock,
  };
  state.pendingPos.set(visit, command);
  try {
    if (
      await mutate(
        "/api/demo/pos/order",
        command,
        state.floor.posOnline
          ? `POS order registered for table ${table}`
          : `Order queued for table ${table}`,
      )
    )
      state.pendingPos.delete(visit);
  } catch (error) {
    if (error.status) state.pendingPos.delete(visit);
  }
}
function openReset() {
  const body = el("div");
  body.append(
    el("p", "Restart the guided table 12 scenario at 19:00?"),
    el(
      "p",
      "This removes all synthetic visits, accepted events and queued POS orders in this local workspace.",
    ),
  );
  actionDialog({
    title: "Restart the demo scenario?",
    eyebrow: "LOCAL DATA",
    body,
    confirm: "Restart scenario",
    danger: true,
    run: async () => {
      const ok = await mutate(
        "/api/demo/reset",
        {},
        "Scenario restarted at 19:00",
      );
      if (ok) {
        state.selected = 12;
        state.historical = null;
        state.queue = [];
        render();
      }
      return ok;
    },
  });
}
function openShowcase() {
  const body = el("div");
  body.append(
    el(
      "p",
      "Load a synthetic service with occupied, available and confirmed tables, seating notes and completed visit history?",
    ),
    el(
      "p",
      "The sample replaces the current local data and keeps table 12 ready for the delayed-order walkthrough.",
    ),
  );
  actionDialog({
    title: "Load a sample service?",
    eyebrow: "LOCAL DATA",
    body,
    confirm: "Load sample service",
    run: async () => {
      const ok = await mutate(
        "/api/demo/showcase",
        {},
        "Sample service loaded",
      );
      if (ok) {
        state.selected = 12;
        state.historical = null;
        state.detailTab = "overview";
        location.hash = "floor";
        render();
      }
      return ok;
    },
  });
}
function openHelp() {
  const body = el("div");
  body.append(
    el(
      "p",
      "TableSignal connects seating with POS order registration. It never infers when a waiter approached a table.",
    ),
  );
  for (const [label, key] of [
    ["Floor overview", "F"],
    ["Visit history", "H"],
    ["Activity log", "A"],
    ["Demo studio (local)", "D"],
    ["Seat guests", "N"],
    ["Help", "?"],
    ["Move between table buttons", "Arrow keys"],
    ["Activate a table / action", "Enter"],
    ["Close a dialog", "Esc"],
  ]) {
    const row = el("div", undefined, "help-row");
    row.append(el("span", label), el("kbd", key));
    body.append(row);
  }
  body.append(
    el(
      "p",
      "The floor refreshes every three seconds. Times are shown in Europe/Tirane. All server timestamps are UTC.",
    ),
  );
  $("info-title").textContent = "A little help, when you need it";
  $("info-body").replaceChildren(body);
  $("info-dialog").showModal();
}
function openPreferences() {
  const body = el("div");
  body.append(
    el("p", "These display preferences are saved only in this browser."),
  );
  for (const [key, label] of [
    ["compact", "Compact table spacing"],
    ["contrast", "Stronger secondary text contrast"],
  ]) {
    const row = el("label", undefined, "preference-row");
    const input = el("input");
    input.type = "checkbox";
    input.checked = store.read(key, false);
    input.onchange = () => {
      store.write(key, input.checked);
      applyPreferences();
    };
    row.append(el("span", label), input);
    body.append(row);
  }
  body.append(
    el(
      "p",
      "Timezone: Europe/Tirane. POS alert threshold: 8 minutes. Both are fixed for this local release.",
    ),
  );
  $("info-title").textContent = "Make the workspace yours";
  $("info-body").replaceChildren(body);
  $("info-dialog").showModal();
}
$("action-form").onsubmit = async (event) => {
  event.preventDefault();
  if (state.busy || !state.dialogAction) return;
  $("dialog-confirm").disabled = true;
  $("dialog-error").hidden = true;
  try {
    if (await state.dialogAction()) $("action-dialog").close();
  } catch (error) {
    $("dialog-error").textContent = error.message;
    $("dialog-error").hidden = false;
  } finally {
    $("dialog-confirm").disabled = false;
  }
};
for (const b of document.querySelectorAll("[data-close-dialog]"))
  b.onclick = () => {
    if (!state.busy) $("action-dialog").close();
  };
$("action-dialog").addEventListener("cancel", (e) => {
  if (state.busy) e.preventDefault();
});
$("action-dialog").addEventListener("close", () => {
  state.dialogAction = null;
});
$("info-close").onclick = () => $("info-dialog").close();
$("seat-top").onclick = () => openSeat(state.selected);
$("help").onclick = openHelp;
$("preferences").onclick = openPreferences;
$("sample-service").onclick = openShowcase;
$("sample-studio").onclick = openShowcase;
$("reset").onclick = openReset;
for (const id of ["refresh", "retry-load"]) $(id).onclick = () => load();
for (const mode of ["grid", "list"])
  $(mode).onclick = () => {
    state.layout = mode;
    store.write("layout", mode);
    renderZones();
  };
for (const b of $("area-tabs").querySelectorAll("[data-area]"))
  b.onclick = () => {
    state.area = b.dataset.area;
    for (const x of $("area-tabs").children)
      x.setAttribute("aria-pressed", String(x === b));
    renderZones();
  };
$("floor-filter").onchange = (event) => {
  state.filter = event.target.value;
  renderZones();
};
for (const b of $("history-tabs").querySelectorAll("[data-state]"))
  b.onclick = () => {
    state.historyState = b.dataset.state;
    state.historyPage = 0;
    for (const x of $("history-tabs").children)
      x.setAttribute("aria-pressed", String(x === b));
    renderHistory();
  };
$("history-search").oninput = (e) => {
  state.historySearch = e.target.value;
  state.historyPage = 0;
  renderHistory();
};
$("history-sort").onchange = (e) => {
  state.historySort = e.target.value;
  state.historyPage = 0;
  renderHistory();
};
$("history-prev").onclick = () => {
  state.historyPage--;
  renderHistory();
};
$("history-next").onclick = () => {
  state.historyPage++;
  renderHistory();
};
$("export-history").onclick = exportHistory;
for (const b of $("activity-tabs").querySelectorAll("[data-source]"))
  b.onclick = () => {
    state.activitySource = b.dataset.source;
    for (const x of $("activity-tabs").children)
      x.setAttribute("aria-pressed", String(x === b));
    renderActivity();
  };
$("reload-activity").onclick = () => loadAuxiliary();
$("advance").onclick = async () => {
  try {
    await mutate("/api/demo/next", {}, "Scenario advanced");
  } catch {}
};
for (const b of document.querySelectorAll("[data-minutes]"))
  b.onclick = async () => {
    try {
      await mutate(
        "/api/demo/clock",
        {
          instant: new Date(
            new Date(state.floor.clock).getTime() +
              Number(b.dataset.minutes) * 60000,
          ).toISOString(),
        },
        `Clock advanced by ${b.dataset.minutes} minutes`,
      );
    } catch {}
  };
$("pos-connection").onclick = async () => {
  const action = state.floor.posOnline ? "disconnect" : "reconnect";
  try {
    await mutate(
      "/api/demo/pos/" + action,
      {},
      action === "disconnect"
        ? "POS is offline. Orders will be queued."
        : "POS reconnected. Pending orders delivered.",
    );
  } catch {}
};
$("pos-retry").onclick = async () => {
  try {
    await mutate(
      "/api/demo/pos/retry",
      {},
      "Last order delivered again safely",
    );
  } catch {}
};
$("pos-record").onclick = () => {
  const t = state.floor.tables.find(
    (t) => t.number === Number($("pos-table").value),
  );
  if (t?.visit) registerPos(t.number, t.visit.visit_id);
};
window.addEventListener("hashchange", () => {
  const next = location.hash.slice(1) || "floor";
  if (next !== state.view) setView(next);
  window.scrollTo({ top: 0 });
});
window.addEventListener("keydown", (event) => {
  if (
    event.ctrlKey ||
    event.metaKey ||
    event.altKey ||
    event.repeat ||
    document.querySelector("dialog[open]") ||
    event.target.closest("input,select,textarea,[contenteditable]")
  )
    return;
  const key = event.key.toLowerCase();
  const routes = { f: "floor", h: "history", a: "activity", d: "studio" };
  if (routes[key]) {
    event.preventDefault();
    if (routes[key] !== "studio" || state.floor?.localDemo)
      location.hash = routes[key];
  } else if (key === "n") {
    event.preventDefault();
    openSeat(state.selected);
  } else if (event.key === "?") {
    event.preventDefault();
    openHelp();
  }
});
applyPreferences();
hydrateIcons();
setView(location.hash.slice(1) || "floor");
busyControls();
load();
setInterval(() => {
  if (document.visibilityState === "visible") load({ silent: true });
}, 3000);
