-- MERGE (docs/plans/2026-11-19-001-feat-merge-plan.md). Load merge_setup.sql on both sides first and
-- run with STOP=0: each case runs in BEGIN ... ROLLBACK, so the tables stay as loaded.
-- Phase 1: matched UPDATE/DELETE/DO NOTHING, not matched INSERT, AND conditions, sources, parameters.
-- @merge_basic
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v, note = s.note
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, s.v, s.note);
SELECT * FROM mt;
ROLLBACK;
-- @merge_matched_delete
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN DELETE;
SELECT * FROM mt;
ROLLBACK;
-- @merge_conditions_in_order
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4 OR sid = 11) s ON t.id = s.id
  WHEN MATCHED AND s.v IS NULL THEN DELETE
  WHEN MATCHED AND s.v > 20 THEN UPDATE SET note = 'big'
  WHEN MATCHED THEN UPDATE SET note = 'other'
  WHEN NOT MATCHED AND s.v > 65 THEN INSERT (id, v, note) VALUES (s.id, s.v, 'inserted');
SELECT * FROM mt;
ROLLBACK;
-- @merge_do_nothing
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN DO NOTHING
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, s.v, s.note);
SELECT * FROM mt;
ROLLBACK;
-- @merge_not_matched_do_nothing
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET note = 'hit'
  WHEN NOT MATCHED THEN DO NOTHING;
SELECT * FROM mt;
ROLLBACK;
-- @merge_no_clause_applies
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED AND false THEN DELETE
  WHEN NOT MATCHED AND s.v < 0 THEN INSERT (id) VALUES (s.id);
SELECT * FROM mt;
ROLLBACK;
-- @merge_insert_without_columns
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (3, 4)) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT VALUES (s.id, s.v, s.note);
SELECT * FROM mt;
ROLLBACK;
-- @merge_insert_some_columns
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (3, 4)) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id);
SELECT * FROM mt;
ROLLBACK;
-- @merge_insert_default_keyword
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, DEFAULT, 'd');
SELECT * FROM mt;
ROLLBACK;
-- @merge_insert_default_values
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT DEFAULT VALUES;
ROLLBACK;
-- @merge_update_set_default
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = DEFAULT;
SELECT * FROM mt;
ROLLBACK;
-- @merge_update_reads_both_sides
BEGIN;
MERGE INTO mt AS t USING (SELECT * FROM ms WHERE sid <= 4) AS s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = t.v + coalesce(s.v, 100), note = t.note || '/' || s.note;
SELECT * FROM mt;
ROLLBACK;
-- @merge_target_without_alias
BEGIN;
MERGE INTO mt USING (SELECT * FROM ms WHERE sid <= 4) s ON mt.id = s.id
  WHEN MATCHED THEN UPDATE SET v = mt.v * 10;
SELECT * FROM mt;
ROLLBACK;
-- @merge_source_table_directly
BEGIN;
MERGE INTO mt t USING me s ON t.id = s.id
  WHEN MATCHED THEN DELETE;
SELECT count(*) FROM mt;
ROLLBACK;
-- @merge_on_extra_condition
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id AND s.v > 5
  WHEN MATCHED THEN UPDATE SET note = 'matched'
  WHEN NOT MATCHED AND s.id <= 5 THEN INSERT (id) VALUES (s.id + 100);
SELECT * FROM mt;
ROLLBACK;
-- @merge_on_non_key
BEGIN;
MERGE INTO mt t USING (VALUES (2, 'two'), (9, 'nine')) AS s(v, name) ON t.v = s.v
  WHEN MATCHED THEN UPDATE SET note = s.name || '!'
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.v * 10, s.v, s.name);
SELECT * FROM mt;
ROLLBACK;
-- @merge_values_source
BEGIN;
MERGE INTO mt t USING (VALUES (1, 100), (9, 900)) AS s(id, v) ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v);
SELECT * FROM mt;
ROLLBACK;
-- @merge_aggregate_source
BEGIN;
MERGE INTO mt t USING (SELECT id, sum(v) AS v, count(*) AS n FROM ms WHERE id IS NOT NULL GROUP BY id) s
  ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v, note = 'n=' || s.n
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, s.v, 'n=' || s.n);
SELECT * FROM mt;
ROLLBACK;
-- @merge_with_cte
BEGIN;
WITH s AS (SELECT id, v FROM ms WHERE sid IN (1, 3))
MERGE INTO mt t USING s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v);
SELECT * FROM mt;
ROLLBACK;
-- @merge_joined_source
BEGIN;
MERGE INTO mt t USING (SELECT s.id, s.v, k.v AS kv FROM ms s JOIN mk k ON k.a = s.id AND k.b = 1) s
  ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.kv;
SELECT * FROM mt;
ROLLBACK;
-- @merge_empty_source
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE false) s ON t.id = s.id
  WHEN MATCHED THEN DELETE
  WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id);
SELECT count(*) FROM mt;
ROLLBACK;
-- @merge_empty_target
BEGIN;
MERGE INTO me t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN DELETE
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, s.v, s.note);
SELECT * FROM me;
ROLLBACK;
-- @merge_composite_key_full
BEGIN;
MERGE INTO mk t USING (VALUES (1, 2, 120), (2, 2, 220)) AS s(a, b, v) ON t.a = s.a AND t.b = s.b
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (a, b, v) VALUES (s.a, s.b, s.v);
SELECT * FROM mk;
ROLLBACK;
-- @merge_composite_key_partial
BEGIN;
MERGE INTO mk t USING (VALUES (1, 5)) AS s(a, d) ON t.a = s.a
  WHEN MATCHED THEN UPDATE SET v = t.v + s.d;
SELECT * FROM mk;
ROLLBACK;
-- @merge_two_in_one_transaction
BEGIN;
MERGE INTO mt t USING (VALUES (9, 90)) AS s(id, v) ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v);
MERGE INTO mt t USING (VALUES (9, 1)) AS s(id, d) ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = t.v + s.d
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, -1);
SELECT * FROM mt WHERE id = 9;
ROLLBACK;
-- @merge_rolled_back
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN DELETE;
ROLLBACK;
SELECT * FROM mt;
-- @merge_autocommit_no_rows
MERGE INTO mt t USING (SELECT * FROM ms WHERE false) s ON t.id = s.id
  WHEN MATCHED THEN DELETE;
-- @merge_update_key
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (1, 2)) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET id = t.id + 100;
SELECT * FROM mt;
ROLLBACK;
-- @merge_update_key_collision
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET id = 2;
ROLLBACK;
-- Errors
-- @merge_twice_matched_update
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (11, 12)) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v;
ROLLBACK;
-- @merge_twice_matched_delete
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (11, 12)) s ON t.id = s.id
  WHEN MATCHED THEN DELETE;
ROLLBACK;
-- @merge_twice_matched_do_nothing
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (11, 12)) s ON t.id = s.id
  WHEN MATCHED THEN DO NOTHING;
SELECT * FROM mt WHERE id = 3;
ROLLBACK;
-- @merge_twice_matched_update_then_nothing
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (11, 12)) s ON t.id = s.id
  WHEN MATCHED AND s.v = 31 THEN UPDATE SET v = s.v
  WHEN MATCHED THEN DO NOTHING;
SELECT * FROM mt WHERE id = 3;
ROLLBACK;
-- @merge_twice_matched_by_source_table
BEGIN;
MERGE INTO mt t USING ms s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v;
ROLLBACK;
-- @merge_duplicate_insert
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (13, 14)) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v);
ROLLBACK;
-- @merge_insert_existing_key
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id + 100
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v);
ROLLBACK;
-- @merge_insert_null_key
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 5) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v);
ROLLBACK;
-- @merge_null_join_key_skipped
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 5) s ON t.id = s.id
  WHEN NOT MATCHED AND s.id IS NOT NULL THEN INSERT (id, v) VALUES (s.id, s.v);
SELECT count(*) FROM mt;
ROLLBACK;
-- @merge_update_key_to_null
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET id = NULL;
ROLLBACK;
-- @merge_unknown_target
BEGIN;
MERGE INTO nosuch t USING ms s ON t.id = s.id WHEN MATCHED THEN DELETE;
ROLLBACK;
-- @merge_unknown_set_column
BEGIN;
MERGE INTO mt t USING ms s ON t.id = s.id WHEN MATCHED THEN UPDATE SET nosuch = 1;
ROLLBACK;
-- @merge_qualified_set_column
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET t.v = 1;
ROLLBACK;
-- @merge_ambiguous_column
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = v + 1;
ROLLBACK;
-- @merge_type_mismatch
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = 'x';
ROLLBACK;
-- @merge_insert_too_few_values
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id);
ROLLBACK;
-- @merge_matched_insert
BEGIN;
MERGE INTO mt t USING ms s ON t.id = s.id WHEN MATCHED THEN INSERT (id) VALUES (s.id);
ROLLBACK;
-- @merge_not_matched_update
BEGIN;
MERGE INTO mt t USING ms s ON t.id = s.id WHEN NOT MATCHED THEN UPDATE SET v = 1;
ROLLBACK;
-- @merge_insert_target_column_reference
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, t.v);
ROLLBACK;
-- Parameters (extended protocol)
-- @merge_bind_values
BEGIN;
MERGE INTO mt t USING (VALUES ($1::int, $2::int)) AS s(id, v) ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v) \bind 1 111 \g
MERGE INTO mt t USING (VALUES ($1::int, $2::int)) AS s(id, v) ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v) \bind 9 999 \g
SELECT * FROM mt;
ROLLBACK;
-- @merge_bind_untyped
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED AND s.v > $1 THEN UPDATE SET note = $2
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, $1, $2) \bind 5 bound \g
SELECT * FROM mt;
ROLLBACK;
-- Phase 2 (PostgreSQL 17): recorded now, expected FE_ERR until then
-- @merge_not_matched_by_source
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET note = 'both'
  WHEN NOT MATCHED BY SOURCE AND t.id > 3 THEN DELETE
  WHEN NOT MATCHED BY SOURCE THEN UPDATE SET note = 'target only'
  WHEN NOT MATCHED BY TARGET THEN INSERT (id, v, note) VALUES (s.id, s.v, 'source only');
SELECT * FROM mt;
ROLLBACK;
-- @merge_returning
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED AND s.v IS NULL THEN DELETE
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v)
  RETURNING merge_action(), s.sid, t.*;
ROLLBACK;
