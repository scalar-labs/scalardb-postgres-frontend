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
-- @refuse_limit_negative
SELECT id FROM emp LIMIT -1;
-- @refuse_offset_negative
SELECT id FROM emp OFFSET -1;
-- @refuse_nul_in_string
SELECT count(*) FROM emp WHERE name = E'a\000b';
-- @refuse_date_before_year_1000
BEGIN; INSERT INTO emp (id, name, hired) VALUES (-100, 'probe', '0999-12-31'); ROLLBACK;
-- @refuse_varchar_length_over_max
BEGIN; CREATE TABLE probe_vc (id INT PRIMARY KEY, v VARCHAR(10485761)); ROLLBACK;
-- @refuse_char_zero_length
BEGIN; CREATE TABLE probe_c0 (id INT PRIMARY KEY, v CHAR(0)); ROLLBACK;
-- @refuse_numeric_precision_over_max
BEGIN; CREATE TABLE probe_n (id INT PRIMARY KEY, v NUMERIC(1001)); ROLLBACK;
-- @refuse_table_name_over_63
BEGIN; CREATE TABLE t_dd_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx (id INT PRIMARY KEY); ROLLBACK;
-- @refuse_column_name_over_56
BEGIN; CREATE TABLE probe_col (id INT PRIMARY KEY, c_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx INT); ROLLBACK;
-- @notice_timestamp_precision
CREATE TABLE IF NOT EXISTS probe_ts (id INT PRIMARY KEY, at TIMESTAMP); DROP TABLE probe_ts;
-- @accept_timestamp3_without_notice
CREATE TABLE IF NOT EXISTS probe_ts3 (id INT PRIMARY KEY, at TIMESTAMP(3)); DROP TABLE probe_ts3;
-- @refuse_drop_schema_not_empty
CREATE SCHEMA IF NOT EXISTS probe_ne; CREATE TABLE IF NOT EXISTS probe_ne.t (id INT PRIMARY KEY); DROP SCHEMA probe_ne;
-- @drop_schema_after_its_table
DROP TABLE probe_ne.t; DROP SCHEMA probe_ne;
-- @session_search_path_plan_cache
CREATE SCHEMA sp_other; CREATE TABLE sp_other.emp (id INT, name TEXT, PRIMARY KEY (id)); SELECT count(*) FROM emp; SET search_path TO sp_other; SELECT count(*) FROM emp; RESET search_path; SELECT count(*) FROM emp; DROP TABLE sp_other.emp; DROP SCHEMA sp_other;
