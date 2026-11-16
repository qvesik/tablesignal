package app.tablesignal.web;

import static org.junit.jupiter.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Real PostgreSQL process, real HTTP, no H2 or repository mocks. */
class PostgresIntegrationTest {
  static EmbeddedPostgres pg;
  ConfigurableApplicationContext app;
  RestClient http;

  @BeforeAll
  static void postgres() throws Exception {
    pg = EmbeddedPostgres.builder().setServerConfig("unix_socket_directories", "").start();
  }

  @AfterAll
  static void stop() throws Exception {
    pg.close();
  }

  void start() {
    app =
        new SpringApplicationBuilder(TableSignalApplication.class)
            .profiles("local")
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + pg.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=");
    int port =
        ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) app)
            .getWebServer()
            .getPort();
    http = RestClient.create("http://127.0.0.1:" + port);
  }

  @BeforeEach
  void setup() {
    start();
    post("/api/demo/reset", Map.of());
  }

  @AfterEach
  void close() {
    app.close();
  }

  Map<?, ?> post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(Map.class);
  }

  Map<?, ?> get(String path) {
    return http.get().uri(path).retrieve().body(Map.class);
  }

  LedgerService.Input event(String id, String visit, int table, String type, String time) {
    return new LedgerService.Input(
        "durres-demo", "test", id, table, visit, type, Instant.parse(time));
  }

  @Test
  void delayedDeliveryRetryAndRestart() {
    post("/api/demo/next", Map.of());
    var before = get("/api/visits/demo-visit");
    assertNotNull(((Map<?, ?>) before.get("visit")).get("alert_opened"));
    post("/api/demo/next", Map.of());
    var after = get("/api/visits/demo-visit");
    var v = (Map<?, ?>) after.get("visit");
    assertEquals("2026-11-16T18:04:00.000+00:00", v.get("first_order"));
    assertNotNull(v.get("alert_resolved"));
    var events = (List<?>) after.get("events");
    assertEquals(2, events.size());
    var order = (Map<?, ?>) events.get(1);
    assertEquals(
        360,
        java.time.Duration.between(
                Instant.parse(order.get("occurred_at").toString()),
                Instant.parse(order.get("received_at").toString()))
            .toSeconds());
    post("/api/demo/next", Map.of());
    assertEquals(2, ((List<?>) get("/api/visits/demo-visit").get("events")).size());
    app.close();
    start();
    assertEquals(after, get("/api/visits/demo-visit"));
    post("/api/demo/next", Map.of());
    post("/api/demo/next", Map.of());
    post("/api/demo/next", Map.of());
    var floor = get("/api/floor");
    var t = (Map<?, ?>) ((List<?>) floor.get("tables")).get(11);
    assertEquals("Seated", t.get("status"));
    assertEquals(1, ((Number) t.get("waitMinutes")).intValue());
    // A new, late order for the previous visit also leaves the new visit alone.
    post(
        "/api/events",
        event("late-old", "demo-visit", 12, "ORDER_RECORDED", "2026-11-16T18:03:00Z"));
    assertEquals(
        "Seated", ((Map<?, ?>) ((List<?>) get("/api/floor").get("tables")).get(11)).get("status"));
  }

  List<Object> concurrent(Object a, Object b) throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      List<Future<Object>> jobs = new ArrayList<>();
      for (Object body : List.of(a, b))
        jobs.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    return post("/api/events", body).get("result");
                  } catch (HttpClientErrorException e) {
                    return e.getStatusCode().value();
                  }
                }));
      gate.countDown();
      return List.of(jobs.get(0).get(20, TimeUnit.SECONDS), jobs.get(1).get(20, TimeUnit.SECONDS));
    }
  }

  @Test
  void concurrentDuplicatesConflictsAndSeats() throws Exception {
    var seat = event("seat-a", "visit-a", 1, "SEATED", "2026-11-16T18:00:00Z");
    var results = concurrent(seat, seat);
    assertTrue(results.containsAll(List.of("RECORDED", "DUPLICATE")));
    assertEquals(
        409,
        assertThrows(
                HttpClientErrorException.class,
                () ->
                    post(
                        "/api/events",
                        event("seat-a", "different", 1, "SEATED", "2026-11-16T18:00:00Z")))
            .getStatusCode()
            .value());
    var seats =
        concurrent(
            event("s2", "v2", 2, "SEATED", "2026-11-16T18:00:00Z"),
            event("s3", "v3", 2, "SEATED", "2026-11-16T18:00:00Z"));
    assertTrue(seats.containsAll(List.of("RECORDED", 409)));
    var order = event("parallel-order", "visit-a", 1, "ORDER_RECORDED", "2026-11-16T18:00:00Z");
    assertTrue(concurrent(order, order).containsAll(List.of("RECORDED", "DUPLICATE")));
    assertEquals(
        4,
        app.getBean(JdbcTemplate.class)
            .queryForObject("select count(*) from events", Integer.class));
  }

  @Test
  void offlinePosQueueSurvivesRestart() {
    post("/api/events", event("manual-seat", "manual-visit", 1, "SEATED", "2026-11-16T18:00:00Z"));
    post("/api/demo/clock", Map.of("instant", "2026-11-16T18:04:00Z"));
    var queued = post("/api/demo/pos/order", Map.of("tableId", 1, "visitId", "manual-visit"));
    assertEquals(1, ((Number) queued.get("pendingOrders")).intValue());
    assertEquals(1, ((List<?>) get("/api/visits/manual-visit").get("events")).size());
    post("/api/demo/clock", Map.of("instant", "2026-11-16T18:08:00Z"));
    app.close();
    start();
    assertEquals(1, ((Number) get("/api/floor").get("pendingOrders")).intValue());
    post("/api/demo/clock", Map.of("instant", "2026-11-16T18:10:00Z"));
    var delivered = post("/api/demo/pos/reconnect", Map.of());
    assertEquals(0, ((Number) delivered.get("pendingOrders")).intValue());
    assertEquals(
        4.0, ((Number) ((Map<?, ?>) delivered.get("metrics")).get("avgWaitMinutes")).doubleValue());
    var history = get("/api/visits/manual-visit");
    assertTrue(
        ((Map<?, ?>) history.get("visit"))
            .get("resolution")
            .toString()
            .contains("Delayed POS delivery"));
    post("/api/demo/pos/retry", Map.of());
    assertEquals(history, get("/api/visits/manual-visit"));
  }

  @Test
  void manualClockOpensOneAlertAtBoundary() {
    var before = post("/api/demo/clock", Map.of("instant", "2026-11-16T18:07:00Z"));
    assertEquals(0, ((Number) ((Map<?, ?>) before.get("metrics")).get("alerts")).intValue());
    var boundary = post("/api/demo/clock", Map.of("instant", "2026-11-16T18:08:00Z"));
    assertEquals(1, ((Number) ((Map<?, ?>) boundary.get("metrics")).get("alerts")).intValue());
    var again = get("/api/floor");
    assertEquals(1, ((Number) ((Map<?, ?>) again.get("metrics")).get("alerts")).intValue());
    assertThrows(
        HttpClientErrorException.class,
        () -> post("/api/demo/clock", Map.of("instant", "2026-11-16T18:07:00Z")));
  }

  @Test
  void partyDetailsAreDurableAndPartOfEventIdentity() {
    var e =
        new LedgerService.Input(
            "durres-demo",
            "hostess",
            "party-seat",
            7,
            "party-visit",
            "SEATED",
            Instant.parse("2026-11-16T18:00:00Z"),
            5,
            "Synthetic note <b>literal text</b>");
    assertEquals("RECORDED", post("/api/events", e).get("result"));
    assertEquals("DUPLICATE", post("/api/events", e).get("result"));
    var changed =
        new LedgerService.Input(
            e.venueId(),
            e.source(),
            e.eventId(),
            e.tableId(),
            e.visitId(),
            e.type(),
            e.occurredAt(),
            4,
            e.seatingNote());
    assertThrows(HttpClientErrorException.class, () -> post("/api/events", changed));
    app.close();
    start();
    var v = (Map<?, ?>) get("/api/visits/party-visit").get("visit");
    assertEquals(5, ((Number) v.get("party_size")).intValue());
    assertEquals(e.seatingNote(), v.get("seating_note"));
    var tooLarge =
        new LedgerService.Input(
            "durres-demo",
            "hostess",
            "large",
            1,
            "large-party",
            "SEATED",
            Instant.parse("2026-11-16T18:00:00Z"),
            3,
            "");
    assertThrows(HttpClientErrorException.class, () -> post("/api/events", tooLarge));
  }

  @Test
  void clientKeyMakesPosQueueRetrySafeAfterClockAdvanceAndClose() {
    post("/api/events", event("pos-seat", "pos-visit", 1, "SEATED", "2026-11-16T18:00:00Z"));
    var command =
        Map.of(
            "tableId",
            1,
            "visitId",
            "pos-visit",
            "eventId",
            "client-key",
            "occurredAt",
            "2026-11-16T18:00:00Z");
    post("/api/demo/pos/order", command);
    post("/api/demo/clock", Map.of("instant", "2026-11-16T18:04:00Z"));
    assertEquals(
        1, ((Number) post("/api/demo/pos/order", command).get("pendingOrders")).intValue());
    post("/api/demo/pos/reconnect", Map.of());
    post("/api/events", event("pos-close", "pos-visit", 1, "CLOSED", "2026-11-16T18:04:00Z"));
    var before = get("/api/visits/pos-visit");
    post("/api/demo/pos/order", command);
    assertEquals(before, get("/api/visits/pos-visit"));
    assertThrows(
        HttpClientErrorException.class,
        () ->
            post(
                "/api/demo/pos/order",
                Map.of(
                    "tableId",
                    1,
                    "visitId",
                    "pos-visit",
                    "eventId",
                    "client-key",
                    "occurredAt",
                    "2026-11-16T18:01:00Z")));
  }

  @Test
  void manualClockCannotBeRewoundByGuidedNext() {
    post("/api/demo/clock", Map.of("instant", "2026-11-16T18:20:00Z"));
    assertThrows(HttpClientErrorException.class, () -> post("/api/demo/next", Map.of()));
    assertEquals("2026-11-16T18:20:00Z", get("/api/floor").get("clock"));
    post("/api/demo/reset", Map.of());
    assertEquals("2026-11-16T18:00:00Z", get("/api/floor").get("clock"));
  }

  @Test
  void showcaseAndRecentJournalUsePersistedHandlers() {
    var floor = post("/api/demo/showcase", Map.of());
    var metrics = (Map<?, ?>) floor.get("metrics");
    assertEquals(8, ((Number) metrics.get("occupied")).intValue());
    assertEquals(22, ((Number) metrics.get("guests")).intValue());
    var table7 = (Map<?, ?>) ((List<?>) floor.get("tables")).get(6);
    assertEquals(6, ((Number) table7.get("capacity")).intValue());
    var rows = http.get().uri("/api/activity?limit=3").retrieve().body(List.class);
    assertEquals(3, rows.size());
    assertEquals(
        1,
        ((List<?>) get("/api/visits/sample-visit-3").get("events"))
            .stream().filter(x -> ((Map<?, ?>) x).get("type").equals("CLOSED")).count());
  }

  @Test
  void simulatorRoutesAndBootstrapAreAbsentWithoutLocalProfile() {
    app.close();
    app =
        new SpringApplicationBuilder(TableSignalApplication.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + pg.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=");
    int port =
        ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) app)
            .getWebServer()
            .getPort();
    http = RestClient.create("http://127.0.0.1:" + port);
    assertEquals(false, get("/api/floor").get("localDemo"));
    for (String path :
        List.of(
            "/api/demo/reset",
            "/api/demo/showcase",
            "/api/demo/clock",
            "/api/demo/pos/order",
            "/api/demo/events"))
      assertEquals(
          404,
          assertThrows(HttpClientErrorException.class, () -> post(path, Map.of()))
              .getStatusCode()
              .value());
  }

  @Test
  void sourceCannotSupplyReceiptTimeOrAmbiguousJson() {
    var supplied = new java.util.LinkedHashMap<String, Object>();
    supplied.put("venueId", "durres-demo");
    supplied.put("source", "pos");
    supplied.put("eventId", "unknown-property");
    supplied.put("tableId", 12);
    supplied.put("visitId", "demo-visit");
    supplied.put("type", "ORDER_RECORDED");
    supplied.put("occurredAt", "2026-11-16T18:00:00Z");
    supplied.put("receivedAt", "2026-11-16T18:00:00Z");
    assertEquals(
        400,
        assertThrows(HttpClientErrorException.class, () -> post("/api/events", supplied))
            .getStatusCode()
            .value());
    String repeated =
        "{\"venueId\":\"durres-demo\",\"source\":\"pos\",\"eventId\":\"a\",\"eventId\":\"b\"}";
    assertEquals(
        400,
        assertThrows(
                HttpClientErrorException.class,
                () ->
                    http.post()
                        .uri("/api/events")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(repeated)
                        .retrieve()
                        .body(Map.class))
            .getStatusCode()
            .value());
    assertEquals(1, ((List<?>) get("/api/visits/demo-visit").get("events")).size());
  }

  @Test
  void manualFixtureEventsPauseGuidedWithoutBypassingIngestion() {
    var close = event("manual-fixture-close", "demo-visit", 12, "CLOSED", "2026-11-16T18:00:00Z");
    assertEquals("RECORDED", post("/api/demo/events", close).get("result"));
    assertEquals("DUPLICATE", post("/api/demo/events", close).get("result"));
    assertEquals(-2, ((Number) get("/api/floor").get("step")).intValue());
    assertThrows(HttpClientErrorException.class, () -> post("/api/demo/next", Map.of()));
    assertEquals(2, ((List<?>) get("/api/visits/demo-visit").get("events")).size());
    post("/api/demo/reset", Map.of());
    assertThrows(
        HttpClientErrorException.class,
        () ->
            post(
                "/api/demo/events",
                event("invalid-close", "demo-visit", 12, "CLOSED", "2026-11-16T18:01:00Z")));
    assertEquals(0, ((Number) get("/api/floor").get("step")).intValue());
    post(
        "/api/demo/events",
        event("other-table", "other-visit", 1, "SEATED", "2026-11-16T18:00:00Z"));
    assertEquals(0, ((Number) get("/api/floor").get("step")).intValue());
  }

  @Test
  void manualPosChangesPauseGuidedTimeline() {
    var connection = post("/api/demo/pos/reconnect", Map.of());
    assertEquals(-2, ((Number) connection.get("step")).intValue());
    post("/api/demo/reset", Map.of());
    var queued =
        post(
            "/api/demo/pos/order",
            Map.of(
                "tableId",
                12,
                "visitId",
                "demo-visit",
                "eventId",
                "manual-fixture-order",
                "occurredAt",
                "2026-11-16T18:00:00Z"));
    assertEquals(-2, ((Number) queued.get("step")).intValue());
    assertEquals(1, ((Number) queued.get("pendingOrders")).intValue());
    assertThrows(HttpClientErrorException.class, () -> post("/api/demo/next", Map.of()));
    post("/api/demo/pos/reconnect", Map.of());
    assertEquals(2, ((List<?>) get("/api/visits/demo-visit").get("events")).size());
  }

  @Test
  void staticAssetsMustBeRevalidatedAfterAnUpgrade() {
    var response = http.get().uri("/app.js?v=0.3.1").retrieve().toEntity(String.class);
    assertNotNull(response.getHeaders().getCacheControl());
    assertTrue(response.getHeaders().getCacheControl().contains("no-cache"));
    assertNotNull(response.getBody());
    assertFalse(response.getBody().isBlank());
  }

  @Test
  void invalidTimesAndEarliestOrder() {
    post("/api/events", event("s", "v", 1, "SEATED", "2026-11-16T17:50:00Z"));
    for (var e :
        List.of(
            event("future", "v", 1, "ORDER_RECORDED", "2026-11-16T18:01:00Z"),
            event("early", "v", 1, "ORDER_RECORDED", "2026-11-16T17:49:00Z"),
            event("unknown", "absent", 1, "ORDER_RECORDED", "2026-11-16T17:55:00Z")))
      assertThrows(HttpClientErrorException.class, () -> post("/api/events", e));
    post("/api/events", event("o1", "v", 1, "ORDER_RECORDED", "2026-11-16T17:57:00Z"));
    post("/api/events", event("o2", "v", 1, "ORDER_RECORDED", "2026-11-16T17:54:00Z"));
    post("/api/events", event("c", "v", 1, "CLOSED", "2026-11-16T17:58:00Z"));
    assertThrows(
        HttpClientErrorException.class,
        () ->
            post("/api/events", event("after", "v", 1, "ORDER_RECORDED", "2026-11-16T17:59:00Z")));
    assertEquals(
        "2026-11-16T17:54:00.000+00:00",
        ((Map<?, ?>) get("/api/visits/v").get("visit")).get("first_order"));
  }
}
