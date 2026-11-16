CREATE TABLE restaurant_tables (
 table_id integer PRIMARY KEY CHECK (table_id BETWEEN 1 AND 12),
 zone text NOT NULL, capacity integer NOT NULL CHECK(capacity BETWEEN 1 AND 12),
 shape text NOT NULL CHECK(shape IN ('round','square','long')));
INSERT INTO restaurant_tables VALUES
 (1,'Dining room',2,'round'),(2,'Dining room',2,'round'),(3,'Dining room',4,'square'),
 (4,'Dining room',4,'square'),(5,'Dining room',4,'square'),(6,'Dining room',4,'square'),
 (7,'Dining room',6,'long'),(8,'Dining room',6,'long'),
 (9,'Terrace',2,'round'),(10,'Terrace',2,'round'),(11,'Terrace',4,'square'),(12,'Terrace',4,'square');
ALTER TABLE visits ADD COLUMN party_size integer NOT NULL DEFAULT 2 CHECK(party_size BETWEEN 1 AND 12);
ALTER TABLE visits ADD COLUMN seating_note text NOT NULL DEFAULT '' CHECK(length(seating_note)<=240);
ALTER TABLE events ADD COLUMN party_size integer;
ALTER TABLE events ADD COLUMN seating_note text NOT NULL DEFAULT '';
UPDATE events SET party_size=2 WHERE type='SEATED';
CREATE INDEX events_recent ON events(received_at DESC,id DESC);
