-- @bind_ts
SELECT proj_id FROM proj WHERE started < $1 \bind '2023-05-01 00:00:00' \g
-- @bind_dml_reuse
BEGIN;
UPDATE emp SET salary = $1 WHERE id = $2 \bind 111 1 \g
UPDATE emp SET salary = $1 WHERE id = $2 \bind 222 2 \g
INSERT INTO dept VALUES ($1, $2, NULL, $3, NULL) \bind 700 'a''b' 1.25 \g
INSERT INTO dept VALUES ($1, $2, NULL, $3, NULL) \bind 701 x 2 \g
COMMIT;
SELECT id, salary FROM emp WHERE id IN (1, 2);
SELECT * FROM dept WHERE id >= 700;
-- @bind_untyped_text_like_number
SELECT $1 || 'x' AS c, length($1) AS l, $1::text AS t \bind '007' \g
SELECT $1 AS b \bind 'true' \g
SELECT $1 + 1 AS n, abs($2) AS a \bind '5' '-5' \g
SELECT name FROM emp WHERE id = $1 \bind '007' \g
-- @bind_untyped_insert_text_column
INSERT INTO emp (id, name, note) VALUES ($1, $2, $3) \bind '-77' '007' '1e3' \g
SELECT id, name, note FROM emp WHERE id = -77;
SELECT id FROM emp WHERE name = $1 \bind '007' \g
DELETE FROM emp WHERE id = -77;
