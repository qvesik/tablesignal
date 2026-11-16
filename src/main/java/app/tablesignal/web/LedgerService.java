package app.tablesignal.web;

import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL is the authority. A venue row lock serializes commands and observations. */
@Service
public class LedgerService {
  public record Input(
      String venueId,
      String source,
      String eventId,
      int tableId,
      String visitId,
      String type,
      Instant occurredAt,
      Integer partySize,
      String seatingNote) {
    public Input(
        String venueId,
        String source,
        String eventId,
        int tableId,
        String visitId,
        String type,
        Instant occurredAt) {
      this(venueId, source, eventId, tableId, visitId, type, occurredAt, null, null);
    }

    public Integer effectivePartySize() {
      return "SEATED".equals(type) ? partySize == null ? 2 : partySize : null;
    }

    public String effectiveNote() {
      return seatingNote == null ? "" : seatingNote.strip();
    }
  }

  private final JdbcTemplate db;
  private final EventRepository events;
  private final boolean demo;
  private final Clock clock;

  public LedgerService(JdbcTemplate db, Environment env, Clock clock, EventRepository events) {
    this.db = db;
    this.events = events;
    demo = env.matchesProfiles("local");
    this.clock = clock;
  }

  public Instant now() {
    return demo
        ? db.queryForObject(
            "select instant from demo_clock where id=1", (r, n) -> r.getTimestamp(1).toInstant())
        : clock.instant();
  }

  private void lock() {
    db.queryForObject(
        "select venue_id from floor_lock where venue_id='durres-demo' for update", String.class);
  }

  private static Instant at(Map<String, Object> row, String key) {
    Object x = row.get(key);
    return x == null ? null : ((java.sql.Timestamp) x).toInstant();
  }

  private static java.sql.Timestamp ts(Instant x) {
    return x == null ? null : java.sql.Timestamp.from(x);
  }

  private static void require(boolean ok, String text) {
    if (!ok) throw new IllegalArgumentException(text);
  }

  @Transactional
  public Map<String, Object> receive(Input e) {
    lock();
    return accept(e);
  }

  private Map<String, Object> accept(Input e) {
    require(
        e != null && "durres-demo".equals(e.venueId()) && e.tableId() > 0 && e.tableId() <= 12,
        "Unknown venue or table");
    require(
        e.source() != null
            && e.source().matches("[A-Za-z0-9_-]{1,80}")
            && e.eventId() != null
            && e.eventId().matches("[A-Za-z0-9_-]{1,100}")
            && e.visitId() != null
            && e.visitId().matches("[A-Za-z0-9_-]{1,100}"),
        "Invalid identifiers");
    require(
        Set.of("SEATED", "ORDER_RECORDED", "CLOSED").contains(e.type() == null ? "" : e.type()),
        "Unknown event type");
    require(e.effectiveNote().length() <= 240, "Seating note must be at most 240 characters");
    require(
        e.type().equals("SEATED") || (e.partySize() == null && e.effectiveNote().isEmpty()),
        "Party details belong to seating events only");
    var existing = events.find(e);
    if (!existing.isEmpty()) {
      var x = existing.getFirst();
      require(
          e.visitId().equals(x.get("visit_id"))
              && e.tableId() == ((Number) x.get("table_id")).intValue()
              && e.type().equals(x.get("type"))
              && Objects.equals(e.occurredAt(), at(x, "occurred_at"))
              && Objects.equals(e.effectivePartySize(), x.get("party_size"))
              && e.effectiveNote().equals(x.get("seating_note")),
          "Event key has a different payload");
      return Map.of("result", "DUPLICATE");
    }
    Instant received = now();
    require(
        e.occurredAt() != null
            && !e.occurredAt().isAfter(received)
            && !e.occurredAt().isBefore(Instant.parse("2000-01-01T00:00:00Z"))
            && e.occurredAt().getNano() % 1000 == 0,
        "Event time must be UTC, microsecond precision, year 2000 or later and not in the future");
    alerts(received);
    var found = db.queryForList("select * from visits where visit_id=?", e.visitId());
    if (e.type().equals("SEATED")) {
      require(found.isEmpty(), "Visit ID already used");
      int capacity =
          db.queryForObject(
              "select capacity from restaurant_tables where table_id=?",
              Integer.class,
              e.tableId());
      require(
          e.effectivePartySize() > 0 && e.effectivePartySize() <= capacity,
          "Party size must be between 1 and this table's capacity (" + capacity + ")");
      require(
          db.queryForObject(
                  "select count(*) from visits where venue_id=? and table_id=? and (closed_at is"
                      + " null or closed_at>?)",
                  Long.class,
                  e.venueId(),
                  e.tableId(),
                  ts(e.occurredAt()))
              == 0,
          "Table occupied or seating overlaps previous visit");
      db.update(
          "insert into visits(visit_id,venue_id,table_id,seated_at,party_size,seating_note)"
              + " values(?,?,?,?,?,?)",
          e.visitId(),
          e.venueId(),
          e.tableId(),
          ts(e.occurredAt()),
          e.effectivePartySize(),
          e.effectiveNote());
    } else {
      require(!found.isEmpty(), "Unknown visit; deliver seating first");
      var v = found.getFirst();
      require(
          e.venueId().equals(v.get("venue_id"))
              && e.tableId() == ((Number) v.get("table_id")).intValue(),
          "Visit/table mismatch");
      require(!e.occurredAt().isBefore(at(v, "seated_at")), "Event precedes seating");
      if (e.type().equals("CLOSED")) {
        require(
            at(v, "closed_at") == null
                && (at(v, "latest_order") == null
                    || !e.occurredAt().isBefore(at(v, "latest_order"))),
            "Already closed or close precedes an order");
        db.update(
            "update visits set closed_at=? where visit_id=?", ts(e.occurredAt()), e.visitId());
        resolve(e.visitId(), received, "Visit closed");
      } else {
        require(
            at(v, "closed_at") == null || !e.occurredAt().isAfter(at(v, "closed_at")),
            "Order occurred after close");
        db.update(
            "update visits set"
                + " first_order=least(first_order,?),latest_order=greatest(latest_order,?) where"
                + " visit_id=?",
            ts(e.occurredAt()),
            ts(e.occurredAt()),
            e.visitId());
        resolve(
            e.visitId(),
            received,
            e.occurredAt().isBefore(at(v, "seated_at").plusSeconds(480))
                ? "Delayed POS delivery: order recorded before alert threshold"
                : "POS confirmed: actual first-order threshold reached");
      }
    }
    events.append(e, received);
    return Map.of("result", "RECORDED");
  }

  private void resolve(String id, Instant now, String reason) {
    db.update(
        "update visits set alert_resolved=?,resolution=? where visit_id=? and alert_opened is not"
            + " null and alert_resolved is null",
        ts(now),
        reason,
        id);
  }

  private void alerts(Instant now) {
    db.update(
        "update visits set alert_opened=? where closed_at is null and first_order is null and"
            + " alert_opened is null and seated_at<=?",
        ts(now),
        ts(now.minusSeconds(480)));
  }

  @Transactional
  public Map<String, Object> floor() {
    lock();
    Instant n = now();
    alerts(n);
    var visits = db.queryForList("select * from visits order by seated_at,visit_id");
    var tables = new ArrayList<Map<String, Object>>();
    var configuration = db.queryForList("select * from restaurant_tables order by table_id");
    for (var config : configuration) {
      final int num = ((Number) config.get("table_id")).intValue();
      var current =
          visits.stream()
              .filter(
                  v -> ((Number) v.get("table_id")).intValue() == num && v.get("closed_at") == null)
              .findFirst();
      var t = new LinkedHashMap<String, Object>();
      t.put("number", num);
      t.put("zone", config.get("zone"));
      t.put("capacity", config.get("capacity"));
      t.put("shape", config.get("shape"));
      t.put("visit", current.orElse(null));
      t.put(
          "status",
          current
              .map(
                  v ->
                      v.get("first_order") != null
                          ? "Order confirmed"
                          : v.get("alert_opened") != null ? "Awaiting POS confirmation" : "Seated")
              .orElse("Free"));
      t.put(
          "waitMinutes",
          current
              .map(
                  v ->
                      Math.max(
                          0,
                          Duration.between(
                                  at(v, "seated_at"),
                                  at(v, "first_order") == null ? n : at(v, "first_order"))
                              .toMinutes()))
              .orElse(null));
      tables.add(t);
    }
    var waits =
        visits.stream()
            .filter(v -> v.get("first_order") != null)
            .mapToLong(v -> Duration.between(at(v, "seated_at"), at(v, "first_order")).toSeconds())
            .average();
    var metrics = new LinkedHashMap<String, Object>();
    metrics.put("occupied", tables.stream().filter(t -> t.get("visit") != null).count());
    metrics.put(
        "alerts",
        visits.stream()
            .filter(v -> v.get("alert_opened") != null && v.get("alert_resolved") == null)
            .count());
    metrics.put("avgWaitMinutes", waits.isPresent() ? waits.getAsDouble() / 60 : null);
    metrics.put(
        "guests",
        visits.stream()
            .filter(v -> v.get("closed_at") == null)
            .mapToInt(v -> ((Number) v.get("party_size")).intValue())
            .sum());
    return Map.of(
        "revision",
        db.queryForObject("select coalesce(max(id),0) from events", Long.class),
        "posOnline",
        db.queryForObject("select pos_online from demo_clock where id=1", Boolean.class),
        "pendingOrders",
        db.queryForObject(
            "select count(*) from demo_pos_outbox where not delivered", Integer.class),
        "clock",
        n.toString(),
        "tables",
        tables,
        "visits",
        visits,
        "metrics",
        metrics,
        "localDemo",
        demo,
        "step",
        db.queryForObject("select step from demo_clock where id=1", Integer.class));
  }

  @Transactional
  public Map<String, Object> history(String id) {
    lock();
    var rows = db.queryForList("select * from visits where visit_id=?", id);
    require(!rows.isEmpty(), "Unknown visit");
    return Map.of("visit", rows.getFirst(), "events", events.history(id));
  }
}
