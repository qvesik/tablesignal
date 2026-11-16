CREATE TABLE demo_cursor (
    id   smallint PRIMARY KEY CHECK (id = 1),
    step smallint NOT NULL CHECK (step BETWEEN 0 AND 4)
);

INSERT INTO demo_cursor (id, step) VALUES (1, 0);
