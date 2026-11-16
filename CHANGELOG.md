# Changelog

## 0.3.1 — 12 November 2026

* Pause the guided timeline after manual fixture events, orders or POS connection changes; keep all ingestion on the same transactional handler.
* Fix stale client reuse after an upgrade with versioned asset URLs and cache revalidation.
* Run a shared seven-phase automated UI acceptance suite through the Work Browser adapter; provide the same cases for the Playwright launcher.
* Add isolated Compose acceptance and an explicitly separate HTTP-only mode.
* Add reproducible packaged-JAR verification with a real child-JVM restart.

## 0.3.0 — 12 November 2026

* Replaced the first preview with a complete responsive workspace: floor map/list, visit details and timeline, searchable visit archive, recent source journal and demo studio.
* Added capacities, party sizes and seating notes with migration V4; included them in canonical seating-event identity.
* Added seating/closing dialogs, filtered CSV export, keyboard navigation, browser-local display preferences, loading and connection recovery states.
* Populated a never-used local database with a synthetic sample service. Restarts do not reseed it.
* Isolated simulator code in a local-profile service. Added an explicit manual clock mode to prevent guided-step rewinds.
* Made POS queue commands safe to retry with client-provided identity and occurrence time.
* Added strict JSON field/duplicate-key validation and a bounded source activity API.
* Added Maven Wrapper 3.9.11, local launch scripts and an IDE-accessible localhost database port.
* Expanded acceptance to 19 JUnit tests and manually exercised desktop/narrow-screen browser flows.

## 0.2.0 — 12 November 2026

* Durable PostgreSQL journal and transactional visit projection.
* HTTP ingestion, idempotency/conflicts, concurrency checks, late-order reconciliation and a persistent POS outbox.
* First source release candidate and restart checks.
