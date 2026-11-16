package app.tablesignal.web;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The durable source journal. Call from the enclosing venue transaction. */
@Repository
class EventRepository {
  private final JdbcTemplate db;

  EventRepository(JdbcTemplate db) {
    this.db = db;
  }

  List<Map<String, Object>> find(LedgerService.Input event) {
    return db.queryForList(
        "select * from events where venue_id=? and source=? and event_id=?",
        event.venueId(),
        event.source(),
        event.eventId());
  }

  List<Map<String, Object>> history(String visit) {
    return db.queryForList("select * from events where visit_id=? order by id", visit);
  }

  List<Map<String, Object>> recent(int limit) {
    return db.queryForList(
        "select * from events order by received_at desc,id desc limit ?",
        Math.max(1, Math.min(limit, 200)));
  }

  void append(LedgerService.Input e, Instant received) {
    db.update(
        "insert into"
            + " events(venue_id,source,event_id,table_id,visit_id,type,occurred_at,received_at,party_size,seating_note)"
            + " values(?,?,?,?,?,?,?,?,?,?)",
        e.venueId(),
        e.source(),
        e.eventId(),
        e.tableId(),
        e.visitId(),
        e.type(),
        Timestamp.from(e.occurredAt()),
        Timestamp.from(received),
        e.effectivePartySize(),
        e.effectiveNote());
  }
}
