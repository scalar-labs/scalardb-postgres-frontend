-- Session semantics and refusals: SET TIME ZONE, search_path, SET LOCAL, and clauses the frontend
-- refuses rather than silently handling differently. Run with EXPECT=results/baseline/session.txt.
-- @session_timezone_output
SET TIME ZONE 'Asia/Tokyo'; SELECT '2024-01-01 00:00:00+00'::timestamptz, '2024-06-01 12:30:00'::timestamptz, '2024-01-01'::timestamptz::date;
-- @session_timezone_offset_minutes
SET TIME ZONE 'Asia/Kolkata'; SELECT '2024-01-01 00:00:00+00'::timestamptz;
-- @session_timezone_extract
SET TIME ZONE 'America/New_York'; SELECT extract(hour from '2024-07-01 00:00:00+00'::timestamptz), date_trunc('day', '2024-07-01 03:00:00+00'::timestamptz);
-- @session_timezone_compare_timestamp_col
SET TIME ZONE 'Asia/Tokyo'; SELECT count(*) FROM proj WHERE started < '2000-01-01 09:00:00+09'::timestamptz;
-- @session_timezone_interval_sequelize
SET TIME ZONE INTERVAL '+00:00' HOUR TO MINUTE; SELECT '2024-01-01 00:00:00+00'::timestamptz; SET TIME ZONE INTERVAL '+09:00' HOUR TO MINUTE; SELECT '2024-01-01 00:00:00+00'::timestamptz;
-- @session_timezone_hours_and_posix
SET TIME ZONE -3.5; SELECT '2024-01-01 00:00:00+00'::timestamptz; SET TIME ZONE '+05:30'; SELECT '2024-01-01 00:00:00+00'::timestamptz; SET TIME ZONE 'UTC+9'; SELECT '2024-01-01 00:00:00+00'::timestamptz;
-- @session_timezone_invalid
SET TIME ZONE 'Mars/Olympus';
-- @session_set_local_scope
BEGIN; SET LOCAL application_name = 'inside'; SHOW application_name; COMMIT; SHOW application_name;
-- @session_set_local_outside_tx
SET LOCAL application_name = 'outside'; SHOW application_name;
-- @session_search_path_missing
SET search_path TO nosuch; SELECT count(*) FROM emp;
-- @session_search_path_public_last
SET search_path TO nosuch, public; SELECT count(*) FROM emp;
-- @session_limit_all
SELECT count(*) FROM (SELECT id FROM emp LIMIT ALL) x;
-- @session_current_timestamp_precision
SELECT current_timestamp(0)::text !~ '\.', length(localtimestamp(3)::text) BETWEEN 19 AND 23, current_time(0)::text !~ '\.';
-- @session_precision_casts
SELECT '2024-01-01 12:34:56.789+00'::timestamptz(0), '2024-01-01 12:34:56.75'::timestamp(1), '12:34:56.789'::time(0), '2024-01-01 23:59:59.9'::timestamp(0);
-- @session_char_cast_truncation
SELECT 'abcdef'::varchar(3), 'abcdef'::char(2), length('abcdef'::varchar(3));
-- @refuse_tablesample
SELECT count(*) FROM emp TABLESAMPLE SYSTEM (10);
-- @refuse_collate_locale
SELECT name FROM emp WHERE id <= 3 ORDER BY name COLLATE "en_US";
-- @accept_collate_c
SELECT name FROM emp WHERE id <= 3 ORDER BY name COLLATE "C";
-- @refuse_distinct_order_by
SELECT DISTINCT name FROM emp ORDER BY salary;
-- @refuse_select_into
BEGIN; SELECT id INTO emp_copy FROM emp; ROLLBACK;
-- @refuse_temp_table
CREATE TEMP TABLE tmp_probe (id INT PRIMARY KEY);
-- @refuse_modifying_cte
WITH d AS (DELETE FROM emp WHERE id = -1 RETURNING id) SELECT * FROM d;
-- @refuse_expression_index
BEGIN; CREATE INDEX emp_lower_name ON emp (lower(name)); ROLLBACK;
-- @refuse_serializable_on_snapshot
BEGIN ISOLATION LEVEL SERIALIZABLE; SELECT 1; COMMIT;
-- @refuse_aggregate_in_where
SELECT id FROM emp WHERE max(salary) > 1;
-- @refuse_ungrouped_column
SELECT dept_id, name FROM emp GROUP BY dept_id;
-- @refuse_union_type_mismatch
SELECT id FROM emp UNION SELECT name FROM emp;
-- @refuse_update_pk
UPDATE emp SET id = -5 WHERE id = -4;
