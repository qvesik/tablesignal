# TableSignal 0.3.1

A working local Java application for a restaurant floor manager. See twelve tables at **Adriatic Table**, a fictional restaurant in Durrës; seat guests, follow POS confirmation, close visits and inspect the durable history.

The interface is a responsive browser client served by the Java application. It uses no frontend framework, CDN, external fonts, paid services or real POS connection. Everything in the sample service is synthetic.

## Start the application

With Docker Engine and Compose v2 installed, extract the archive and run from `tablesignal`:

```sh
docker compose up --build
```

Or use `./start.sh` on Linux/macOS and `start.cmd` on Windows. Open **http://127.0.0.1:8080** after startup.

The first local launch populates a sample service: eight occupied tables, completed history, party sizes, seating notes and a queued POS order for Table 12. Subsequent restarts preserve the current state. The app and development database port are bound to localhost. No publication occurs.

Stop with Ctrl+C or `docker compose down`. The named `tablesignal-data` volume survives. **`docker compose down -v` deletes it.** In the UI, restarting the scenario or loading the sample service also replaces the synthetic data, with a confirmation dialog.

Compose uses PostgreSQL 16. The application container connects to `db:5432`; the IDE development port is `127.0.0.1:5439`. Override `TABLESIGNAL_DB_PORT` if necessary. Default credentials are `tablesignal` / `local-demo-only`; override `TABLESIGNAL_DB_PASSWORD` before creating a new volume.

## Work in IntelliJ IDEA

Use a full **JDK 21**, import `pom.xml`, and let IntelliJ resolve the Maven dependencies. The repository includes Maven Wrapper 3.3.2 pinned to Maven 3.9.11; a separate Maven install is optional.

Start only the database:

```sh
docker compose up -d db
```

Run `app.tablesignal.web.TableSignalApplication` in the IDE with:

```text
SPRING_PROFILES_ACTIVE=local
TABLESIGNAL_DB_URL=jdbc:postgresql://localhost:5439/tablesignal
TABLESIGNAL_DB_USER=tablesignal
TABLESIGNAL_DB_PASSWORD=local-demo-only
```

Or export those variables and run `./mvnw spring-boot:run` (`mvnw.cmd` on Windows). An existing local PostgreSQL installation also works: create the database/user and set its JDBC URL instead. The application's default JDBC URL outside Compose remains `localhost:5432/tablesignal`.

## Run the prebuilt JAR

`TableSignal_0.3.1.jar` is delivered alongside the source ZIP. It includes the browser interface and Java runtime dependencies; it still needs **Java 21 and a running PostgreSQL database**. With the database and environment variables from the IDE section configured:

```sh
java -jar TableSignal_0.3.1.jar
```

No Maven build is needed for this option. The JAR does not bundle a JRE or a PostgreSQL server.

## The workspace

| Screen | What works |
| --- | --- |
| Floor overview | Dining room and terrace, 12 table buttons, capacities, party sizes, text statuses, timers, area/status filters, map/list switch and live polling |
| Visit details | Current or completed visit, seating note, first-order wait, delivery delay, preserved alert reason and source timeline with both timestamps |
| Seat / close | Validated party-size form, optional operational note, one active visit per table, confirmation before closing |
| Visit history | Search table/visit/note, open/completed filters, sorting, pagination, details and filtered CSV export |
| Activity log | Latest 100 accepted journal entries, source filters, occurrence and delivery times, visit links |
| Demo studio | Guided seven-step scenario, controllable clock, POS disconnect/reconnect, durable offline queue and safe last-event retry |
| Preferences / help | Browser-local compact spacing and stronger text contrast, keyboard shortcuts and timezone information |

On narrow screens the floor becomes a vertical list, visit history becomes cards, and navigation moves to the bottom. Table buttons support arrows, Home and End. Keyboard shortcuts: **F** floor, **H** history, **A** activity, **D** local demo, **N** seating, **?** help. Editable fields and dialogs do not intercept these shortcuts. Display preferences and layout are saved only in this browser.

CSV export contains the filtered visit set, uses Europe/Tirane timestamps and quotes fields; values beginning with formula characters are escaped. Notes are rendered as text, never HTML. Source history and summaries remain in PostgreSQL, not browser storage.

## Five-minute IDE demonstration

1. **0:00–1:00 — Working product.** Open the sample floor. Select occupied and free tables, switch areas and show the seating dialog. Open Visit history and search a seating note. Show the raw source Activity log.
2. **1:00–2:00 — Begin the controlled scenario.** Open Demo studio and **Restart scenario**. Confirm replacement of synthetic data. At **19:00**, guests are seated at Table 12. Next reaches **19:08**: exactly one `Awaiting POS confirmation` alert. The offline POS recorded an order at **19:04**, but the floor has not received it.
3. **2:00–3:00 — Reconciliation.** Next reaches **19:10** and reconnects POS. Open Table 12: **four minutes to first order**, **six minutes delivery delay**, original alert opened at 19:08 and resolved at 19:10 with a late-delivery explanation. Timeline shows both clocks.
4. **3:00–4:00 — Retry and isolation.** Next at **19:11** repeats the same event without another journal row. Next closes at **19:15**, then seats new guests at **19:16**, then delivers an old-visit event at **19:17**. The current visit remains Seated; old history stays accessible.
5. **4:00–5:00 — Explain the Java.** Open `LedgerService`, `EventRepository`, migrations V2/V4 and `PostgresIntegrationTest`. Explain transaction/venue locking and event identity. Restart the app without resetting and show retained history.

Use **Load sample service** afterward to restore the populated workspace. Manual clock changes, manual POS connection controls, and manual UI events/orders affecting Table 12 pause the guided sequence: **Next step is disabled** until Restart. This prevents a fixed scenario event from being delivered into a visit already changed or closed manually. Other tables can still be operated independently. Manual events use the same transactional ingestion handler; a rejected command never changes the demo mode.

A POS event proves order registration, **not the time a waiter approached**.

## Tests and build

```sh
./scripts/test-domain.sh       # original eight offline Java scenarios
./mvnw test                   # JUnit + a real PostgreSQL process + real HTTP
./mvnw clean verify           # tests, executable JAR and real child-JVM release check
```

First runs need network access for the free dependencies. Integration tests use Zonky's **real PostgreSQL 14.15 executable**, not H2 or a repository mock; run as a non-root user on a supported native platform. Compose selects PostgreSQL 16; the container combination remains a separate check. Docker's root build skips tests; the non-root host test run is the acceptance check.

`test` runs 22 domain/PostgreSQL tests. `verify` additionally runs `PackagedJarIT`: it starts the actual built JAR in a child JVM, checks packaged assets/cache headers, reconciles the delayed order and verifies history after restarting that JVM against the same real PostgreSQL database.

See **VERIFICATION.md** for checks actually performed and remaining limits. Browser acceptance uses the shared cases in `scripts/ui-acceptance.mjs`. All seven phases were executed automatically through the supported Work Browser driver (`ui-cua-adapter.mjs`). The standalone Playwright launcher runs those same cases in a normal local browser environment:

```sh
npm install --no-save --package-lock=false playwright@1.56.1
npx playwright install chromium
node scripts/ui-smoke.cjs
```

This smoke script resets synthetic data and runs against `http://127.0.0.1:8080`; set `TABLESIGNAL_BASE_URL` to override. Node and Playwright are not application runtime dependencies. Browser screenshots, if enabled by the script, go under `target/`.

## API

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/floor` | UTC clock, tables/capacities, visit summaries, metrics and revision |
| GET | `/api/visits/{id}` | Projection and source journal for one visit |
| GET | `/api/activity?limit=100` | Latest accepted events, bounded to 1–200 |
| POST | `/api/events` | Durable ingestion: `RECORDED` or `DUPLICATE` |
| POST | `/api/demo/events` | Manual local UI ingestion; pauses the guide when Table 12 changes |
| POST | `/api/demo/reset` | Reset to guided 19:00 seating; local only |
| POST | `/api/demo/showcase` | Replace data with sample service; local only |
| POST | `/api/demo/next` | Advance guided sequence; local only |
| POST | `/api/demo/clock` | Advance manual UTC clock; local only |
| GET | `/api/demo/pos/queue` | Inspect simulator outbox; local only |
| POST | `/api/demo/pos/order` | Register/queue a simulated order; local only |
| POST | `/api/demo/pos/disconnect` | Stop simulated delivery; local only |
| POST | `/api/demo/pos/reconnect` | Deliver queued orders through ingestion; local only |
| POST | `/api/demo/pos/retry` | Repeat last delivered POS event; local only |

```sh
curl -X POST http://127.0.0.1:8080/api/demo/reset
curl -X POST http://127.0.0.1:8080/api/events \
 -H 'Content-Type: application/json' \
 -d '{"venueId":"durres-demo","source":"hostess","eventId":"seat-7","tableId":7,"visitId":"visit-7","type":"SEATED","occurredAt":"2026-11-16T18:00:00Z","partySize":5,"seatingNote":"Synthetic group; high chair requested"}'
curl http://127.0.0.1:8080/api/visits/visit-7
curl -X POST http://127.0.0.1:8080/api/demo/clock \
 -H 'Content-Type: application/json' -d '{"instant":"2026-11-16T18:04:00Z"}'
curl -X POST http://127.0.0.1:8080/api/demo/pos/order \
 -H 'Content-Type: application/json' \
 -d '{"tableId":7,"visitId":"visit-7","eventId":"pos-7","occurredAt":"2026-11-16T18:04:00Z"}'
curl -X POST http://127.0.0.1:8080/api/demo/clock \
 -H 'Content-Type: application/json' -d '{"instant":"2026-11-16T18:10:00Z"}'
curl -X POST http://127.0.0.1:8080/api/demo/pos/reconnect
```

Source events contain `venueId`, `source`, `eventId`, `tableId`, `visitId`, `type`, `occurredAt`. Seating may also contain `partySize` (default 2) and `seatingNote` (default empty). Party size is 1 to the table's capacity; notes are at most 240 Java characters and are stripped of surrounding whitespace. Party details belong only to `SEATED` and are part of canonical event identity. IDs use letters, digits, `_` and `-`; source ≤80, event/visit ≤100.

The server alone assigns `receivedAt`. Unknown JSON fields and repeated JSON object keys are rejected with 400, as are malformed bodies/timestamps. Semantic conflicts return 409 with an explanation. Source retries must keep the same key and canonical payload. For the simulator, supplying `eventId` and `occurredAt` also makes a queue command safe to retry after transport failure; the UI retains that command until its outcome is known.

## Java and data model

```text
web / FloorController             REST reads and source event ingestion
web / LedgerService               Transactional event policy, projection, summaries
web / EventRepository             Accepted source journal and event reads
web / LocalDemoController         Local-profile REST controls
web / DemoService                 Local-profile clock, outbox, guided and sample sources
web / DemoBootstrap               Seed only a never-used local database
domain / FloorLedger              Original offline reference model and eight scenarios
static / app.js, api.js, icons.js  Browser state, HTTP client, semantic rendering
```

The original standalone preview was superseded so the interface has one supported PostgreSQL-backed runtime. `DemoService` is absent without `local`; it calls the same transactional `LedgerService.receive` as external source HTTP. The local UI uses `/api/demo/events` so accepted manual fixture changes can pause the guide atomically. Outside local mode the UI uses `/api/events` directly. The production UTC `Clock` is injected. In local mode, observation time comes from the durable synthetic clock. API responses and PostgreSQL instants use UTC; formatting happens in Europe/Tirane in the UI.

| Table | Role |
| --- | --- |
| `floor_lock` | One row for the venue; serializes ingestion and observations across clients |
| `restaurant_tables` | Twelve seeded capacities, shapes and area names |
| `events` | Append-only accepted source journal; unique `(venue_id,source,event_id)` |
| `visits` | Transactional projection: seating, party/note, earliest/latest order, close and alert timestamps/reason |
| `demo_clock` | Persistent local clock, guided/manual step and connection flag |
| `demo_pos_outbox` | Durable POS orders not yet accepted by the floor, plus delivery marker |
| `demo_cursor` | Retained V1 migration compatibility; unused |

A partial unique index permits only one open visit per table. A venue row lock deliberately favors straightforward correctness over throughput for twelve tables. Journal append, projection changes and related alert changes commit together or roll back together. Simulator outbox delivery joins that transaction. Identical concurrent deliveries yield one journal entry; reusing a key with changed canonical fields returns 409. Receipt time is excluded from source identity.

Policy: event occurrence must be year 2000 or later, not later than the observation clock, with at most microsecond precision. Orders before seating or after close by occurrence time are rejected. Unknown visits must be seated first; the source retries dependent events afterward. Closing must be at or after seating and every known order. A distinct second close conflicts; an identical close retry is safe. New seating cannot overlap an earlier visit.

The earliest valid order occurrence determines first-order wait. A late order for a closed visit can correct that visit and cumulative statistics, without confirming the current visit at the same table. Negative intervals never enter metrics. A warning is persisted once when a request observes eight minutes with no known first order. Resolution preserves when it opened, when it resolved and the reason known then. Later corrections preserve that original explanation as an audit fact.

## Current scope

One venue, one local operator, cumulative confirmed-visit averages. There is no authentication, shift comparison, kitchen workflow, multi-venue management, camera ingestion, notification service or real POS integration. These are subsequent phases. Visit summaries currently load without server pagination; the history screen paginates the loaded records. Alerts are observed through polling/commands, not an unattended worker. Use a separate database for system-clock mode rather than mixing it with synthetic future-dated demo visits.

Maven Wrapper scripts are generated Apache Maven components with their original license headers. Application dependencies and their versions are declared in `pom.xml`. No paid infrastructure is required.

## Isolated Compose acceptance

With Docker Compose v2 and Python 3 installed:

```sh
./scripts/verify-compose.sh
```

This builds a separate `tablesignal-acceptance` project on localhost ports **18080** (app) and **15439** (database). It replaces only that project's synthetic data, verifies the delayed order, concurrent deliveries/seat conflicts, and restarts both app and PostgreSQL containers to compare retained history. It stops its containers afterward and preserves its named volume. Use `TABLESIGNAL_CHECK_KEEP_RUNNING=1` to leave them running. Override the project/ports using `TABLESIGNAL_CHECK_PROJECT`, `TABLESIGNAL_CHECK_HTTP_PORT` and `TABLESIGNAL_CHECK_DB_PORT`.

Docker is unavailable in the delivery environment, so no successful container execution is claimed. The script's HTTP-only cases were executed successfully against a disposable real PostgreSQL 14.15 instance. To run those against another disposable local app, without pretending to test containers:

```sh
TABLESIGNAL_BASE_URL=http://127.0.0.1:8080 python3 scripts/verify_compose_http.py --http-only
```

The selected app must use the local profile and disposable synthetic data. JSON results go to `target/http-acceptance.json` or `target/compose-acceptance.json`. Python and Node are development verification tools, not application runtime dependencies.

The HTML references versioned UI assets, and the server requires static-asset revalidation. This prevents a browser from running an old client after the JAR is replaced.
