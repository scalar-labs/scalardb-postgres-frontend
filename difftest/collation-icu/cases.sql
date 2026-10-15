-- @order_by ordered
SELECT id FROM people ORDER BY name, id;
-- @order_by_desc_limit ordered
SELECT id FROM people ORDER BY name DESC, id LIMIT 3;
-- @eq_index
SELECT id FROM people WHERE name = 'ALICE';
-- @eq_in_memory
SELECT id FROM people WHERE name || '' = 'ALICE';
-- @range
SELECT id FROM people WHERE name < 'B';
-- @range_in_memory
SELECT id FROM people WHERE name || '' < 'B';
-- @in_list
SELECT id FROM people WHERE name IN ('BOB', 'Carol');
-- @in_subquery
SELECT id FROM people WHERE name IN (SELECT 'BOB');
-- @not_in_subquery
SELECT id FROM people WHERE name NOT IN (SELECT 'BOB');
-- @case_when
SELECT id FROM people WHERE CASE WHEN name = 'CAROL' THEN true END;
-- @nullif
SELECT count(NULLIF(name, 'BOB')) FROM people;
-- @group_by
SELECT count(*) FROM (SELECT name FROM people GROUP BY name) s;
-- @group_by_count
SELECT upper(name), count(*) FROM people GROUP BY name;
-- @count_distinct
SELECT count(DISTINCT name), count(DISTINCT city) FROM people;
-- @distinct
SELECT count(*) FROM (SELECT DISTINCT city FROM people) s;
-- @min_max
SELECT upper(min(name)), upper(max(name)) FROM people;
-- @self_join
SELECT a.id, b.id FROM people a JOIN people b ON a.name = b.name AND a.id < b.id;
-- @union
SELECT count(*) FROM (SELECT name FROM people UNION SELECT 'CAROL') s;
-- @except
SELECT count(*) FROM (SELECT city FROM people EXCEPT SELECT 'TOKYO') s;
-- @intersect
SELECT count(*) FROM (SELECT city FROM people INTERSECT SELECT 'PARIS') s;
-- @window_partition
SELECT id, count(*) OVER (PARTITION BY name) FROM people;
-- @key_get
SELECT n FROM tags WHERE tag = 'RED';
-- @key_in
SELECT n FROM tags WHERE tag IN ('RED', 'BLUE');
-- @key_duplicate
INSERT INTO tags VALUES ('Red', 9);
-- @key_duplicate_in_tx
BEGIN;
INSERT INTO tags VALUES ('BLUE', 9);
COMMIT;
-- @key_update
BEGIN;
UPDATE tags SET n = 10 WHERE tag = 'BLUE';
SELECT tag, n FROM tags WHERE tag = 'blue';
ROLLBACK;
-- @clustering_order ordered
SELECT k FROM cl WHERE p = 1 ORDER BY k;
-- @clustering_range
SELECT k FROM cl WHERE p = 1 AND k > 'B';
-- @like_refused
SELECT id FROM people WHERE name LIKE 'a%';
-- @ilike_refused
SELECT id FROM people WHERE name ILIKE 'a%';
-- @regex_refused
SELECT id FROM people WHERE upper(name) ~ 'A';
-- @similar_refused
SELECT id FROM people WHERE name SIMILAR TO 'a%';
-- @strpos_refused
SELECT strpos(name, 'b') FROM people;
-- @replace_refused
SELECT replace(name, 'b', 'x') FROM people;
-- @like_cast_refused
SELECT id FROM people WHERE name::text LIKE 'a%';
-- @like_literal
SELECT 'abc' LIKE 'a%';
-- @like_on_number
SELECT id FROM people WHERE id::text LIKE '1%';
-- @like_on_catalog
SELECT count(*) FROM pg_catalog.pg_class WHERE relname LIKE 'people%';
-- @collate_c_refused
SELECT id FROM people ORDER BY name COLLATE "C", id;
-- @literals_compare
SELECT 'alice' < 'BOB';
