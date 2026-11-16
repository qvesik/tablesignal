ALTER TABLE demo_clock ADD COLUMN pos_online boolean NOT NULL DEFAULT true;
CREATE TABLE demo_pos_outbox (
 event_id text PRIMARY KEY, visit_id text NOT NULL REFERENCES visits, table_id integer NOT NULL,
 occurred_at timestamptz NOT NULL, delivered boolean NOT NULL DEFAULT false);
