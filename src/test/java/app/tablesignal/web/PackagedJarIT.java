package app.tablesignal.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Verify the actual packaged release, in child JVMs, against a real database. */
class PackagedJarIT {
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
  private final ObjectMapper json = new ObjectMapper();
  private Process application;
  private String base;

  private void start(String jdbc, Path jar, Path log) throws Exception {
    int port;
    try (var available = new ServerSocket(0)) {
      port = available.getLocalPort();
    }
    base = "http://127.0.0.1:" + port;
    application =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-jar",
                jar.toString(),
                "--spring.profiles.active=local",
                "--server.port=" + port,
                "--spring.datasource.url=" + jdbc,
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .start();
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      if (!application.isAlive()) fail("Packaged application exited; inspect " + log);
      try {
        var floor = get("/api/floor");
        // Avoid racing the initial local bootstrap after the web port is bound.
        if (((Number) floor.get("step")).intValue() >= 0) return;
      } catch (Exception ignored) {
        /* expected until the web server is ready */
      }
      Thread.sleep(100);
    }
    fail("Packaged application did not become ready; inspect " + log);
  }

  private void stop() throws Exception {
    if (application == null) return;
    application.destroy();
    if (!application.waitFor(10, TimeUnit.SECONDS)) {
      application.destroyForcibly();
      assertTrue(application.waitFor(10, TimeUnit.SECONDS), "Child JVM must stop");
    }
  }

  private HttpResponse<String> response(String path, boolean post) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3));
    if (post)
      request
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{}"));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private Map<?, ?> get(String path) throws Exception {
    var result = response(path, false);
    if (result.statusCode() != 200)
      throw new IllegalStateException("HTTP " + result.statusCode() + ": " + result.body());
    return json.readValue(result.body(), Map.class);
  }

  private Map<?, ?> post(String path) throws Exception {
    var result = response(path, true);
    assertEquals(200, result.statusCode(), result.body());
    return json.readValue(result.body(), Map.class);
  }

  @Test
  void executableServesAssetsAndRetainsHistoryAfterJvmRestart() throws Exception {
    Path jar =
        Path.of(System.getProperty("tablesignal.jar", "target/tablesignal.jar")).toAbsolutePath();
    assertTrue(
        Files.isRegularFile(jar),
        "Build the executable JAR before running this test: ./mvnw package");
    Path log = jar.getParent().resolve("packaged-jar-smoke.log");
    try (var pg =
        EmbeddedPostgres.builder().setServerConfig("unix_socket_directories", "").start()) {
      try {
        String jdbc = pg.getJdbcUrl("postgres", "postgres");
        start(jdbc, jar, log);
        for (String asset :
            List.of(
                "/",
                "/app.js?v=release-check",
                "/api.js",
                "/icons.js",
                "/style.css",
                "/favicon.svg")) {
          var result = response(asset, false);
          assertEquals(200, result.statusCode(), "Missing packaged asset " + asset);
          assertFalse(result.body().isBlank());
          if (asset.startsWith("/app.js"))
            assertTrue(
                result.headers().firstValue("Cache-Control").orElse("").contains("no-cache"));
        }
        assertTrue(
            response("/", false).body().contains("/app.js?v="), "Release assets must be versioned");
        post("/api/demo/reset");
        var warning = post("/api/demo/next");
        assertEquals(1, ((Number) ((Map<?, ?>) warning.get("metrics")).get("alerts")).intValue());
        post("/api/demo/next");
        var before = get("/api/visits/demo-visit");
        var events = (List<?>) before.get("events");
        assertEquals(2, events.size());
        var order = (Map<?, ?>) events.get(1);
        assertEquals(
            360,
            Duration.between(
                    Instant.parse(order.get("occurred_at").toString()),
                    Instant.parse(order.get("received_at").toString()))
                .toSeconds());
        var visit = (Map<?, ?>) before.get("visit");
        assertEquals(
            240,
            Duration.between(
                    Instant.parse(visit.get("seated_at").toString()),
                    Instant.parse(visit.get("first_order").toString()))
                .toSeconds());
        post("/api/demo/next");
        assertEquals(
            before,
            get("/api/visits/demo-visit"),
            "Retry must not alter the journal or alert history");
        stop();
        start(jdbc, jar, log);
        assertEquals(
            before, get("/api/visits/demo-visit"), "History must survive a real JVM restart");
        post("/api/demo/next");
        post("/api/demo/next");
        post("/api/demo/next");
        var table = (Map<?, ?>) ((List<?>) get("/api/floor").get("tables")).get(11);
        assertEquals("Seated", table.get("status"));
        assertNull(((Map<?, ?>) table.get("visit")).get("first_order"));
      } finally {
        stop();
      }
    }
  }
}
