package app.tablesignal.web;

import app.tablesignal.web.LedgerService.Input;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Synthetic sources, clock and replay. This entire bean is absent outside the local profile. */
@Service
@Profile("local")
class DemoService {
  private final JdbcTemplate db;
  private final LedgerService ledger;

  DemoService(JdbcTemplate db, LedgerService ledger) {
    this.db = db;
    this.ledger = ledger;
  }

  private void lock() {
    db.queryForObject(
        "select venue_id from floor_lock where venue_id='durres-demo' for update", String.class);
  }

  private Instant now() {
    return ledger.now();
  }

  private static java.sql.Timestamp ts(Instant value) {
    return value == null ? null : java.sql.Timestamp.from(value);
  }

  private static Instant at(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? null : ((java.sql.Timestamp) value).toInstant();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  /** Manual UI commands share ingestion but invalidate the fixed Table 12 narrative. */
  @Transactional
  public Map<String, Object> receiveManual(Input input) {
    lock();
    var result = ledger.receive(input);
    if (input.tableId() == 12 && "RECORDED".equals(result.get("result"))) pauseGuided();
    return result;
  }

  private void pauseGuided() {
    db.update("update demo_clock set step=-2 where id=1 and step>=0");
  }

  @Transactional
  public Map<String, Object> posOrder(int table, String visit, String eventId, Instant occurred) {

    lock();
    require(
        visit != null && visit.matches("[A-Za-z0-9_-]{1,100}") && table >= 1 && table <= 12,
        "Valid visit and table are required");
    String id = eventId == null ? UUID.randomUUID().toString() : eventId;
    require(id.matches("[A-Za-z0-9_-]{1,100}"), "Invalid POS event ID");
    Instant recorded = occurred == null ? now() : occurred;
    var prior = db.queryForList("select * from demo_pos_outbox where event_id=?", id);
    if (!prior.isEmpty()) {
      var q = prior.getFirst();
      require(
          visit.equals(q.get("visit_id"))
              && table == ((Number) q.get("table_id")).intValue()
              && recorded.equals(at(q, "occurred_at")),
          "POS event key has a different payload");
      flushPos();
      return ledger.floor();
    }
    var rows =
        db.queryForList(
            "select * from visits where visit_id=? and table_id=? and closed_at is null",
            visit,
            table);
    require(!rows.isEmpty(), "Select an open visit");
    require(
        !recorded.isBefore(at(rows.getFirst(), "seated_at"))
            && !recorded.isAfter(now())
            && recorded.getNano() % 1000 == 0,
        "POS order time must be inside the open visit, not in the future, with microsecond"
            + " precision");
    queueOrder(id, visit, table, recorded);
    flushPos();
    if (table == 12) pauseGuided();
    return ledger.floor();
  }

  @Transactional
  public java.util.List<java.util.Map<String, Object>> posQueue() {

    lock();
    return db.queryForList(
        "select * from demo_pos_outbox order by occurred_at desc,event_id desc limit 100");
  }

  private void queueOrder(String event, String visit, int table, Instant occurred) {
    db.update(
        "insert into demo_pos_outbox(event_id,visit_id,table_id,occurred_at) values(?,?,?,?) on"
            + " conflict do nothing",
        event,
        visit,
        table,
        ts(occurred));
  }

  private Input queuedInput(Map<String, Object> row) {
    return new Input(
        "durres-demo",
        "pos",
        row.get("event_id").toString(),
        ((Number) row.get("table_id")).intValue(),
        row.get("visit_id").toString(),
        "ORDER_RECORDED",
        at(row, "occurred_at"));
  }

  private void flushPos() {
    if (!db.queryForObject("select pos_online from demo_clock where id=1", Boolean.class)) return;
    for (var row :
        db.queryForList(
            "select * from demo_pos_outbox where not delivered order by occurred_at,event_id")) {
      ledger.receive(queuedInput(row));
      db.update("update demo_pos_outbox set delivered=true where event_id=?", row.get("event_id"));
    }
  }

  @Transactional
  public Map<String, Object> posConnection(String action) {

    lock();
    require(Set.of("disconnect", "reconnect", "retry").contains(action), "Unknown POS action");
    if (action.equals("retry")) {
      require(
          db.queryForObject("select pos_online from demo_clock where id=1", Boolean.class),
          "POS is offline");
      var rows =
          db.queryForList(
              "select * from demo_pos_outbox where delivered order by occurred_at desc,event_id"
                  + " desc limit 1");
      require(!rows.isEmpty(), "No delivered POS event to retry");
      ledger.receive(queuedInput(rows.getFirst()));
    } else {
      db.update("update demo_clock set pos_online=? where id=1", action.equals("reconnect"));
      flushPos();
      pauseGuided();
    }
    return ledger.floor();
  }

  @Transactional
  public Map<String, Object> advanceClock(Instant instant) {

    lock();
    require(
        instant != null && !instant.isBefore(now()) && instant.getNano() % 1000 == 0,
        "Advance the local clock only; use Reset to rewind");
    db.update("update demo_clock set instant=?,step=-2 where id=1", ts(instant));
    return ledger.floor();
  }

  @Transactional
  public Map<String, Object> showcase() {

    lock();
    demo("reset");
    demo("next");
    sampleSeat(1, "17:55", 2, "Window seats requested");
    sampleOrder(1, "18:00");
    sampleSeat(2, "18:03", 2, "");
    sampleSeat(4, "17:58", 3, "High chair requested");
    sampleSeat(5, "17:50", 4, "");
    sampleOrder(5, "17:53");
    sampleSeat(6, "18:05", 2, "Quiet table preferred");
    sampleSeat(8, "17:52", 5, "Group celebrating a birthday");
    sampleOrder(8, "17:58");
    sampleSeat(10, "18:04", 2, "");
    sampleSeat(3, "17:10", 4, "Earlier synthetic visit");
    sampleOrder(3, "17:15");
    ledger.receive(
        new Input(
            "durres-demo",
            "hostess",
            "sample-close-3",
            3,
            "sample-visit-3",
            "CLOSED",
            sampleTime("17:48")));
    return ledger.floor();
  }

  private static Instant sampleTime(String hhmm) {
    return Instant.parse("2026-11-16T" + hhmm + ":00Z");
  }

  private void sampleSeat(int table, String time, int party, String note) {
    ledger.receive(
        new Input(
            "durres-demo",
            "hostess",
            "sample-seat-" + table,
            table,
            "sample-visit-" + table,
            "SEATED",
            sampleTime(time),
            party,
            note));
  }

  private void sampleOrder(int table, String time) {
    ledger.receive(
        new Input(
            "durres-demo",
            "pos",
            "sample-order-" + table,
            table,
            "sample-visit-" + table,
            "ORDER_RECORDED",
            sampleTime(time)));
  }

  @Transactional
  public Map<String, Object> demo(String action) {

    lock();
    int step = db.queryForObject("select step from demo_clock where id=1", Integer.class);
    if (action.equals("reset")) {
      db.update("delete from demo_pos_outbox");
      db.update("update demo_clock set pos_online=false where id=1");
      db.update("delete from events");
      db.update("delete from visits");
      step = -1;
    } else {
      require(action.equals("next"), "Unknown demo action");
      require(step >= -1, "Manual mode: restart the guided scenario before using Next step");
    }
    step = Math.min(step + 1, 6);
    String[] times = {"18:00", "18:08", "18:10", "18:11", "18:15", "18:16", "18:17"};
    db.update(
        "update demo_clock set instant=?,step=? where id=1",
        ts(Instant.parse("2026-11-16T" + times[step] + ":00Z")),
        step);
    if (step == 0)
      ledger.receive(
          new Input("durres-demo", "hostess", "demo-seat", 12, "demo-visit", "SEATED", now()));
    if (step == 1)
      queueOrder("demo-order", "demo-visit", 12, Instant.parse("2026-11-16T18:04:00Z"));
    if (step == 2) {
      db.update("update demo_clock set pos_online=true where id=1");
      flushPos();
    }
    if (step == 3 || step == 6)
      ledger.receive(
          new Input(
              "durres-demo",
              "pos",
              step == 6 ? "demo-old-late" : "demo-order",
              12,
              "demo-visit",
              "ORDER_RECORDED",
              Instant.parse("2026-11-16T18:04:00Z")));
    if (step == 4)
      ledger.receive(
          new Input("durres-demo", "hostess", "demo-close", 12, "demo-visit", "CLOSED", now()));
    if (step == 5)
      ledger.receive(
          new Input("durres-demo", "hostess", "demo-seat-2", 12, "demo-visit-2", "SEATED", now()));
    return ledger.floor();
  }
}
