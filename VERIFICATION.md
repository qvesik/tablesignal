# Verification — TableSignal 0.3.1

Checked on 12 November 2026. This is a working local application with a real browser interface and PostgreSQL-backed operations. No GitHub publication, public deployment, real POS integration or paid service was used.

## Automated checks actually passed

| Check | Observed result |
| --- | --- |
| Original baseline | 8 offline domain scenarios passed before changes |
| Java build | Java 21 clean Maven verification and executable Spring Boot JAR |
| Clean source extraction | The source ZIP was extracted to a separate directory; its Maven Wrapper completed all 23 tests and built the executable JAR |
| JUnit | **23 tests: 8 domain + 14 PostgreSQL/HTTP + 1 packaged-JAR integration test; zero failures, errors or skips** |
| PostgreSQL | Real PostgreSQL 14.15 process; Flyway V1–V4 migrated an empty database |
| Concurrent duplicate delivery | Seating and order tested with two simultaneous HTTP clients; one RECORDED, one DUPLICATE |
| Key reuse | Different canonical payload returns 409; no second accepted row |
| Concurrent seating | One open visit accepted; competing seating conflicts |
| Delayed first order | 19:08 warning; 19:10 receipt of 19:04 event; four-minute wait and six-minute delivery delay |
| Earliest order | Earlier valid occurrence received after another order corrects the projection |
| Old visit isolation | Late events update their own visit; current guests still await their own order |
| Invalid events | Future/before-seating/after-close times and unknown visits rejected |
| Party and note | Capacity validated, metadata persisted through restart, changed metadata conflicts on the same event key |
| Offline POS | Durable queue survives application restart; reconnect delivers once; stable client key remains safe after clock change and close |
| Clock / manual-operation rule | Manual clock changes and manual changes to Table 12 pause the guide; rejected commands and changes to other tables preserve the guide |
| Browser upgrade cache | Versioned asset URLs and Cache-Control: no-cache prevent an old client surviving a release upgrade |
| Local profile | Simulator routes absent outside local; no sample bootstrap in system-clock mode |
| JSON authority | Client-supplied receivedAt and repeated JSON object keys rejected with 400 |
| Sample service and journal | Eight occupied tables, 22 guests, capacities and bounded recent-event query checked through HTTP |
| JavaScript / shell syntax | App modules and optional UI script passed Node syntax checks; POSIX launch/test/wrapper scripts passed `sh -n` |
| Maven Wrapper | Downloaded and reported pinned Maven 3.9.11 successfully |

Surefire and Failsafe summaries are included in `verification/`. `test` runs 22 checks; `verify` adds the packaged-JAR test.

## Automated browser acceptance (0.3.1)

All **seven shared acceptance phases** in `scripts/ui-acceptance.mjs` passed through `scripts/ui-cua-adapter.mjs` against the actual Java application and PostgreSQL. Results, timestamps and the tested suite SHA-256 are recorded in `verification/ui-acceptance.json`. The phases cover guided reconciliation and old-visit isolation; manual seating/offline POS/closure; history and CSV; narrow layout and keys; help and saved preferences; clock restoration; and pausing the guide after a manual Table 12 closure.

The narrow check used browser zoom at approximately **340 CSS pixels**, with a one-column floor, history cards and no horizontal document overflow. It was not a physical-device test.

Five additional HTTP-only checks passed using `scripts/verify_compose_http.py --http-only` against a separate disposable application and real PostgreSQL 14.15. They checked readiness, 4/6-minute reconciliation and retry, old-visit isolation, concurrent delivery/seating/key conflicts and manual-mode behavior. See `verification/http-acceptance.txt`. Container restart checks are deliberately skipped in this mode.

This patch fixes two observed defects: manual Table 12 operations could leave guided Next enabled, and an upgraded page could retain a cached old JavaScript client. Both have regression coverage.

## Earlier browser checks retained from 0.3.0

The Java application was run with PostgreSQL and opened in Chromium. These were real interactions with the application, not rendered mockups:

* Seat a group at Table 9 with party size and a synthetic seating note; verify the note in the visit.
* Queue an offline POS order, reconnect and inspect the accepted order and both timestamps.
* Close the visit through the confirmation dialog; locate it by note in Visit history.
* Download the filtered CSV; verify one data row, the header and the seating note in the downloaded file.
* Run the entire guided Table 12 sequence through the UI: one alert at 19:08, 4/6 minute reconciliation at 19:10, one journal order after retry at 19:11, closure, new seating and an old-visit delivery that leaves the new visit Seated.
* Open Overview and Timeline, inspect alert opening/resolution and the source event journal.
* Advance the clock manually; verify Next is disabled and the manual-mode explanation appears.
* Check floor/history shortcuts, arrow-key table focus, help and preference dialogs; save compact spacing, reload and verify it persists, then restore normal spacing.
* Inspect a **449 CSS-pixel viewport using browser zoom**: floor is one column, history uses cards, and document width equals scroll width. An overflow caused by a visually hidden table header was found and fixed. This is a responsive browser check, not a test on physical mobile hardware.
* Interrupt the server, observe Connection interrupted, restart and verify successful reconnection and warning recovery.

## Persistence beyond the test context

The visible QA server used a PostgreSQL data directory with cleanup disabled. The application was stopped; the PostgreSQL PID file disappeared while its data files remained. Both were restarted from that directory. The complete visible Table 12 visit text before/after compared equal, including first order, delay and preserved alert reason. This checks real application and database process restart, not only Spring context restart.

The repeatable `PackagedJarIT` test of the freshly rebuilt executable JAR also launched the built application in a child JVM, reconciled the delayed order through HTTP, restarted the JVM against the same database and compared the complete returned history. Packaged HTML, CSS, JavaScript modules and favicon were also served successfully. The repeatable context/outbox restart cases remain in JUnit.

## Environment-specific build detail

Linux amd64, Java 21.0.13, Maven 3.9.11 and real embedded PostgreSQL 14.15. The provided environment's Java installation lacks `ct.sym`, so Maven verification used:

```sh
./mvnw -Dmaven.compiler.release= -Dmaven.compiler.source=21 -Dmaven.compiler.target=21 clean verify
```

Normal full JDK 21 installations support the shorter README commands. Dependency proxy settings were supplied externally and are not included in the source.

## Remaining limits / not claimed

* **Docker Compose container startup/build:** Docker is absent in the execution environment. Compose and Dockerfile are supplied; no successful container run is claimed.
* **PostgreSQL 16 container combination:** actual integration tests use real PostgreSQL 14.15; Compose selects 16. The SQL uses compatible features, but the precise container combination remains untested here.
* **Windows:** `start.cmd` and `mvnw.cmd` are supplied but were not executed on Windows.
* **Standalone Playwright launcher:** syntax checked but not executed here. Its seven shared test phases were executed end-to-end through the shipped CUA adapter. Acceptance scripts deliberately replace synthetic data.
* **Compose acceptance script:** supplied as `scripts/verify-compose.sh`, with an isolated project, alternate ports and restart checks. Its container path was not executed because Docker is absent; only its HTTP-only path passed here.
* **Physical devices / full accessibility audit:** no physical tablet/phone or assistive-technology certification is claimed. Responsive and keyboard checks cover the flows above.
* One synthetic venue, no authentication or real POS/cameras. Shift comparisons, kitchen workflow and multi-venue operation remain future phases. Statistics are cumulative; floor summaries are not server-paginated. Alerts are observed through polling/commands.
