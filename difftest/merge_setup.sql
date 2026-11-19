-- Tables for cases_merge.sql, loaded on both sides (native_c, and sdb through the frontend).
-- mt: the usual target. ms: sources, keyed by sid so that one id can appear twice:
--   sid 1-4   ids 1, 2 (v NULL), 6, 7: two match mt, two do not
--   sid 5     id NULL: matches nothing, and an insert of it violates the key's NOT NULL
--   sid 11-12 id 3 twice: one target row matched by two source rows
--   sid 13-14 id 8 twice: two inserts of one new key
-- mk: a target with a two-column key. me: an empty target.
DROP TABLE IF EXISTS mt;
DROP TABLE IF EXISTS ms;
DROP TABLE IF EXISTS mk;
DROP TABLE IF EXISTS me;
CREATE TABLE mt (id int PRIMARY KEY, v int, note text);
CREATE TABLE ms (sid int PRIMARY KEY, id int, v int, note text);
CREATE TABLE mk (a int, b int, v int, PRIMARY KEY (a, b));
CREATE TABLE me (id int PRIMARY KEY, v int, note text);
INSERT INTO mt VALUES (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three'), (4, 4, 'four'), (5, 5, 'five');
INSERT INTO ms VALUES
  (1, 1, 10, 's1'), (2, 2, NULL, 's2'), (3, 6, 60, 's3'), (4, 7, 70, 's4'), (5, NULL, 0, 's5'),
  (11, 3, 31, 's11'), (12, 3, 32, 's12'), (13, 8, 80, 's13'), (14, 8, 81, 's14');
INSERT INTO mk VALUES (1, 1, 11), (1, 2, 12), (2, 1, 21);
