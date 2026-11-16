CREATE TABLE floor_lock (venue_id text PRIMARY KEY);
INSERT INTO floor_lock VALUES ('durres-demo');
CREATE TABLE visits (
 visit_id text PRIMARY KEY, venue_id text NOT NULL REFERENCES floor_lock, table_id integer NOT NULL CHECK(table_id BETWEEN 1 AND 12),
 seated_at timestamptz NOT NULL, first_order timestamptz, latest_order timestamptz, closed_at timestamptz,
 alert_opened timestamptz, alert_resolved timestamptz, resolution text,
 CHECK(first_order IS NULL OR first_order >= seated_at), CHECK(closed_at IS NULL OR closed_at >= seated_at));
CREATE UNIQUE INDEX one_open_visit ON visits(venue_id,table_id) WHERE closed_at IS NULL;
CREATE TABLE events (
 id bigserial PRIMARY KEY, venue_id text NOT NULL, source text NOT NULL, event_id text NOT NULL,
 table_id integer NOT NULL, visit_id text NOT NULL REFERENCES visits, type text NOT NULL,
 occurred_at timestamptz NOT NULL, received_at timestamptz NOT NULL,
 UNIQUE(venue_id,source,event_id));
CREATE INDEX events_visit ON events(visit_id,id);
CREATE TABLE demo_clock(id integer PRIMARY KEY CHECK(id=1), instant timestamptz NOT NULL, step integer NOT NULL);
INSERT INTO demo_clock VALUES(1,'2026-11-16T18:00:00Z',-1);
