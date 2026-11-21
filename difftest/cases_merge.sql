-- MERGE (docs/plans/2026-11-19-001-feat-merge-plan.md). Load merge_setup.sql on both sides first and run
-- STOP=0 SNAP=1 SNAP_TABLES='mt mk me' SNAP_RELOAD=merge_reload.sql python3 run.py cases_merge.sql
-- Each MERGE commits on its own; run.py then compares the tables and reloads them. (Inside BEGIN, the
-- frontend cannot scan rows its transaction wrote, so a SELECT * after a MERGE would fail there.)
-- Phase 1: matched UPDATE/DELETE/DO NOTHING, not matched INSERT, AND conditions, sources, parameters.
-- @merge_basic
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v, note = s.note
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, s.v, s.note);
-- @merge_matched_delete
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN DELETE;
-- @merge_conditions_in_order
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4 OR sid = 11) s ON t.id = s.id
  WHEN MATCHED AND s.v IS NULL THEN DELETE
  WHEN MATCHED AND s.v > 20 THEN UPDATE SET note = 'big'
  WHEN MATCHED THEN UPDATE SET note = 'other'
  WHEN NOT MATCHED AND s.v > 65 THEN INSERT (id, v, note) VALUES (s.id, s.v, 'inserted');
-- @merge_do_nothing
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN DO NOTHING
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, s.v, s.note);
-- @merge_not_matched_do_nothing
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET note = 'hit'
  WHEN NOT MATCHED THEN DO NOTHING;
-- @merge_no_clause_applies
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED AND false THEN DELETE
  WHEN NOT MATCHED AND s.v < 0 THEN INSERT (id) VALUES (s.id);
-- @merge_insert_without_columns
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (3, 4)) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT VALUES (s.id, s.v, s.note);
-- @merge_insert_some_columns
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (3, 4)) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id);
-- @merge_insert_default_keyword
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, DEFAULT, 'd');
-- @merge_insert_default_values
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT DEFAULT VALUES;
-- @merge_update_set_default
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = DEFAULT;
-- @merge_update_reads_both_sides
MERGE INTO mt AS t USING (SELECT * FROM ms WHERE sid <= 4) AS s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = t.v + coalesce(s.v, 100), note = t.note || '/' || s.note;
-- @merge_target_without_alias
MERGE INTO mt USING (SELECT * FROM ms WHERE sid <= 4) s ON mt.id = s.id
  WHEN MATCHED THEN UPDATE SET v = mt.v * 10;
-- @merge_source_table_directly
MERGE INTO mt t USING me s ON t.id = s.id
  WHEN MATCHED THEN DELETE;
-- @merge_on_extra_condition
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id AND s.v > 5
  WHEN MATCHED THEN UPDATE SET note = 'matched'
  WHEN NOT MATCHED AND s.id <= 5 THEN INSERT (id) VALUES (s.id + 100);
-- @merge_on_non_key
MERGE INTO mt t USING (VALUES (2, 'two'), (9, 'nine')) AS s(v, name) ON t.v = s.v
  WHEN MATCHED THEN UPDATE SET note = s.name || '!'
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.v * 10, s.v, s.name);
-- @merge_values_source
MERGE INTO mt t USING (VALUES (1, 100), (9, 900)) AS s(id, v) ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v);
-- @merge_aggregate_source
MERGE INTO mt t USING (SELECT id, sum(v) AS v, count(*) AS n FROM ms WHERE id IS NOT NULL GROUP BY id) s
  ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v, note = 'n=' || s.n
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, s.v, 'n=' || s.n);
-- @merge_with_cte
WITH s AS (SELECT id, v FROM ms WHERE sid IN (1, 3))
MERGE INTO mt t USING s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v);
-- @merge_joined_source
MERGE INTO mt t USING (SELECT s.id, s.v, k.v AS kv FROM ms s JOIN mk k ON k.a = s.id AND k.b = 1) s
  ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.kv;
-- @merge_empty_source
MERGE INTO mt t USING (SELECT * FROM ms WHERE false) s ON t.id = s.id
  WHEN MATCHED THEN DELETE
  WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id);
-- @merge_empty_target
MERGE INTO me t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN DELETE
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, s.v, s.note);
-- @merge_composite_key_full
MERGE INTO mk t USING (VALUES (1, 2, 120), (2, 2, 220)) AS s(a, b, v) ON t.a = s.a AND t.b = s.b
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (a, b, v) VALUES (s.a, s.b, s.v);
-- @merge_composite_key_partial
MERGE INTO mk t USING (VALUES (1, 5)) AS s(a, d) ON t.a = s.a
  WHEN MATCHED THEN UPDATE SET v = t.v + s.d;
-- @merge_subqueries
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (1, 3)) s ON t.id = s.id
  WHEN MATCHED AND EXISTS (SELECT 1 FROM ms x WHERE x.id = s.id) THEN UPDATE SET v = (SELECT max(v) FROM ms)
  WHEN NOT MATCHED AND s.id IN (SELECT id FROM ms WHERE v > 50)
    THEN INSERT (id, v) VALUES (s.id, (SELECT min(v) FROM ms WHERE id = s.id));
-- @merge_two_in_one_transaction
BEGIN;
MERGE INTO mt t USING (VALUES (9, 90)) AS s(id, v) ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v);
MERGE INTO mt t USING (VALUES (9, 1)) AS s(id, d) ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = t.v + s.d
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, -1);
SELECT * FROM mt WHERE id = 9;
COMMIT;
-- @merge_rolled_back
BEGIN;
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN DELETE;
ROLLBACK;
-- @merge_autocommit_no_rows
MERGE INTO mt t USING (SELECT * FROM ms WHERE false) s ON t.id = s.id
  WHEN MATCHED THEN DELETE;
-- @merge_update_key
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (1, 2)) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET id = t.id + 100;
-- @merge_update_key_collision
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET id = 2;
-- @merge_not_matched_sees_only_source
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (1, 3)) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET note = 'm'
  WHEN NOT MATCHED AND v > 0 THEN INSERT (id, v, note) VALUES (id, v, note);
-- @merge_unaliased_source_subquery
MERGE INTO mt t USING (SELECT sid, v AS sv FROM ms WHERE sid IN (1, 3)) ON t.id = sid
  WHEN MATCHED THEN UPDATE SET v = sv
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (sid, sv);
-- @merge_delete_then_insert_same_key
MERGE INTO mt t USING (VALUES (3, 'x'), (103, 'y')) AS s(k, what) ON t.id = s.k
  WHEN MATCHED THEN DELETE
  WHEN NOT MATCHED THEN INSERT (id, note) VALUES (s.k - 100, s.what);
-- @merge_key_moved_away_then_inserted
MERGE INTO mt t USING (VALUES (3, 30, NULL), (NULL, NULL, 3)) AS s(k, nk, ins) ON t.id = s.k
  WHEN MATCHED THEN UPDATE SET id = s.nk
  WHEN NOT MATCHED THEN INSERT (id, note) VALUES (s.ins, 'back');
-- @merge_any_subquery
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED AND s.v = ANY (SELECT v FROM ms WHERE sid < 2) THEN UPDATE SET note = 'any';
-- @merge_text_key_after_delete
MERGE INTO mt t USING (VALUES (3, 'x'), (103, 'y')) AS s(k, w) ON t.id = s.k
  WHEN MATCHED THEN DELETE
  WHEN NOT MATCHED THEN INSERT (id, note) VALUES ('3', s.w);
-- @merge_move_onto_deleted_key
MERGE INTO mt t USING (VALUES (1, NULL), (2, 1)) AS s(k, nk) ON t.id = s.k
  WHEN MATCHED AND s.nk IS NULL THEN DELETE
  WHEN MATCHED THEN UPDATE SET id = s.nk;
-- @merge_unaliased_source_planned_again
SET client_min_messages = warning;
MERGE INTO mt t USING (SELECT sid, v AS sv FROM ms WHERE sid = 1) ON t.id = sid
  WHEN MATCHED THEN UPDATE SET note = 'u';
CREATE TABLE mzz (id int PRIMARY KEY);
DROP TABLE mzz;
MERGE INTO mt t USING (SELECT sid, v AS sv FROM ms WHERE sid = 1) ON t.id = sid
  WHEN MATCHED THEN UPDATE SET note = 'u';
-- @merge_volatile_cte_source
WITH s AS (SELECT id, random() AS r FROM ms WHERE sid = 1)
MERGE INTO mt t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET note = 'v';
-- @merge_quoted_aliases
MERGE INTO mt AS "T" USING (SELECT * FROM ms WHERE sid = 1) AS "S" ON "T".id = "S".id
  WHEN MATCHED THEN UPDATE SET note = "S".note;
-- @merge_comment_before_do_nothing
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN /* skip */ DO NOTHING
  WHEN NOT MATCHED THEN -- add
    INSERT (id) VALUES (s.id);
-- Errors
-- @merge_twice_matched_update
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (11, 12)) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v;
-- @merge_twice_matched_delete
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (11, 12)) s ON t.id = s.id
  WHEN MATCHED THEN DELETE;
-- @merge_twice_matched_do_nothing
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (11, 12)) s ON t.id = s.id
  WHEN MATCHED THEN DO NOTHING;
-- @merge_twice_matched_update_then_nothing
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (11, 12)) s ON t.id = s.id
  WHEN MATCHED AND s.v = 31 THEN UPDATE SET v = s.v
  WHEN MATCHED THEN DO NOTHING;
-- @merge_twice_matched_by_source_table
MERGE INTO mt t USING ms s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v;
-- @merge_duplicate_insert
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (13, 14)) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v);
-- @merge_insert_existing_key
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id + 100
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v);
-- @merge_insert_null_key
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 5) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v);
-- @merge_null_join_key_skipped
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 5) s ON t.id = s.id
  WHEN NOT MATCHED AND s.id IS NOT NULL THEN INSERT (id, v) VALUES (s.id, s.v);
-- @merge_update_key_to_null
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET id = NULL;
-- @merge_unknown_target
MERGE INTO nosuch t USING ms s ON t.id = s.id WHEN MATCHED THEN DELETE;
-- @merge_unknown_set_column
MERGE INTO mt t USING ms s ON t.id = s.id WHEN MATCHED THEN UPDATE SET nosuch = 1;
-- @merge_qualified_set_column
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET t.v = 1;
-- @merge_ambiguous_column
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = v + 1;
-- @merge_type_mismatch
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = 'x';
-- @merge_insert_too_few_values
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id);
-- @merge_matched_insert
MERGE INTO mt t USING ms s ON t.id = s.id WHEN MATCHED THEN INSERT (id) VALUES (s.id);
-- @merge_not_matched_update
MERGE INTO mt t USING ms s ON t.id = s.id WHEN NOT MATCHED THEN UPDATE SET v = 1;
-- @merge_insert_target_column_reference
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, t.v);
-- @merge_insert_onto_moved_key
MERGE INTO mt t USING (VALUES (1, 50, NULL), (NULL, NULL, 50)) AS s(k, nk, ins) ON t.id = s.k
  WHEN MATCHED THEN UPDATE SET id = s.nk
  WHEN NOT MATCHED THEN INSERT (id) VALUES (s.ins);
-- @merge_not_matched_nothing_target_reference
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED AND t.v > 0 THEN DO NOTHING;
-- @merge_not_matched_subquery_target_reference
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, (SELECT max(x.v) FROM ms x WHERE x.id = t.id));
-- @merge_not_matched_target_only_column
MERGE INTO mt t USING (SELECT sid, v FROM ms WHERE sid = 3) s ON t.id = s.sid
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.sid, v, note);
-- @merge_not_matched_upper_case_target_alias
MERGE INTO mt T USING (SELECT * FROM ms WHERE sid = 3) s ON T.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, T.v);
-- @merge_not_matched_subquery_own_alias
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, (SELECT t.nope FROM ms t));
-- @merge_unnamed_subquery_not_referable
MERGE INTO mt t USING (SELECT id FROM ms WHERE sid = 1) ON t.id = unnamed_subquery.id
  WHEN MATCHED THEN DELETE;
-- @merge_unaliased_join_source
MERGE INTO mt t USING (ms s JOIN mk k ON k.a = s.id AND k.b = 1) ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = k.v;
-- @merge_not_matched_target_unknown_column
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, t.nope);
-- @merge_not_matched_target_and_ambiguous_name
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 3) s ON t.id = s.id
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (t.v, id);
-- @merge_unnamed_source_quoted
MERGE INTO mt t USING (SELECT id FROM ms WHERE sid = 1) ON t.id = "unnamed subquery".id
  WHEN MATCHED THEN DELETE;
-- @merge_move_onto_key_deleted_later
MERGE INTO mt t USING (VALUES (2, 1), (1, NULL)) AS s(k, nk) ON t.id = s.k
  WHEN MATCHED AND s.nk IS NULL THEN DELETE
  WHEN MATCHED THEN UPDATE SET id = s.nk;
-- @merge_source_named_like_target
MERGE INTO mt USING ms AS mt ON false WHEN NOT MATCHED THEN DO NOTHING;
-- @merge_aggregate_in_set
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = max(s.v);
-- @merge_read_only
SET default_transaction_read_only = on;
MERGE INTO mt t USING (VALUES (1)) AS s(id) ON t.id = s.id WHEN MATCHED THEN UPDATE SET note = 'ro';
SET default_transaction_read_only = off;
-- Parameters (extended protocol)
-- @merge_bind_values
MERGE INTO mt t USING (VALUES ($1::int, $2::int)) AS s(id, v) ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v) \bind 1 111 \g
MERGE INTO mt t USING (VALUES ($1::int, $2::int)) AS s(id, v) ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v) \bind 9 999 \g
-- @merge_bind_untyped
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED AND s.v > $1 THEN UPDATE SET note = $2
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, $1, $2) \bind 5 bound \g
-- Phase 2 (PostgreSQL 17): WHEN NOT MATCHED BY SOURCE / BY TARGET, RETURNING and merge_action()
-- @merge_not_matched_by_source
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET note = 'both'
  WHEN NOT MATCHED BY SOURCE AND t.id > 3 THEN DELETE
  WHEN NOT MATCHED BY SOURCE THEN UPDATE SET note = 'target only'
  WHEN NOT MATCHED BY TARGET THEN INSERT (id, v, note) VALUES (s.id, s.v, 'source only');
-- @merge_by_source_only
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (1, 2)) s ON t.id = s.id
  WHEN NOT MATCHED BY SOURCE THEN DELETE;
-- @merge_by_source_do_nothing
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (1, 2)) s ON t.id = s.id
  WHEN NOT MATCHED BY SOURCE AND t.v = 3 THEN DO NOTHING
  WHEN NOT MATCHED BY SOURCE THEN UPDATE SET v = t.v * 100;
-- @merge_by_source_empty_source
MERGE INTO mt t USING (SELECT * FROM ms WHERE false) s ON t.id = s.id
  WHEN NOT MATCHED BY SOURCE AND t.id % 2 = 0 THEN DELETE;
-- @merge_by_source_key_update
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (1, 2, 11)) s ON t.id = s.id
  WHEN NOT MATCHED BY SOURCE THEN UPDATE SET id = t.id + 100;
-- @merge_by_source_composite_key
MERGE INTO mk t USING (VALUES (1, 1)) AS s(a, b) ON t.a = s.a AND t.b = s.b
  WHEN MATCHED THEN UPDATE SET v = 0
  WHEN NOT MATCHED BY SOURCE THEN DELETE;
-- @merge_by_source_non_key_on
MERGE INTO mt t USING (VALUES (2), (4)) AS s(v) ON t.v = s.v
  WHEN NOT MATCHED BY SOURCE THEN UPDATE SET note = 'unmatched';
-- @merge_by_source_unaliased_source
MERGE INTO mt t USING (SELECT id FROM ms WHERE sid = 1) ON t.id = id
  WHEN NOT MATCHED BY SOURCE AND t.id = 5 THEN DELETE;
-- @merge_by_source_sees_only_target
MERGE INTO mt t USING (SELECT sid, id AS sidv FROM ms WHERE sid = 1) s ON t.id = s.sidv
  WHEN NOT MATCHED BY SOURCE AND v > 3 THEN UPDATE SET note = note || '!';
-- @merge_by_source_source_reference
MERGE INTO mt t USING ms s ON t.id = s.id WHEN NOT MATCHED BY SOURCE AND s.v > 0 THEN DELETE;
-- @merge_by_source_source_reference_in_set
MERGE INTO mt t USING ms s ON t.id = s.id WHEN NOT MATCHED BY SOURCE THEN UPDATE SET v = s.v;
-- @merge_by_source_insert
MERGE INTO mt t USING ms s ON t.id = s.id WHEN NOT MATCHED BY SOURCE THEN INSERT (id) VALUES (1);
-- @merge_returning
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED AND s.v IS NULL THEN DELETE
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v)
  RETURNING merge_action(), s.sid, t.*;
-- @merge_returning_star
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (1, 3)) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET note = 'u'
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v)
  WHEN NOT MATCHED BY SOURCE AND t.id > 4 THEN DELETE
  RETURNING *;
-- @merge_returning_expressions
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid IN (1, 3)) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = 0
  WHEN NOT MATCHED THEN INSERT (id, v, note) VALUES (s.id, s.v, 'new')
  RETURNING merge_action() AS action, t.v + 1 AS next, s.*, lower(merge_action()) || ':' || t.id AS tag;
-- @merge_returning_do_nothing_not_returned
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid <= 4) s ON t.id = s.id
  WHEN MATCHED THEN DO NOTHING
  WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id)
  RETURNING merge_action(), t.id;
-- @merge_returning_by_source
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN NOT MATCHED BY SOURCE AND t.id IN (2, 3) THEN UPDATE SET v = -t.v
  WHEN NOT MATCHED BY SOURCE AND t.id = 4 THEN DELETE
  RETURNING merge_action(), s.sid, t.id, t.v;
-- @merge_returning_key_move
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET id = 100
  RETURNING merge_action(), t.id, s.id;
-- @merge_returning_bind
MERGE INTO mt t USING (VALUES ($1::int, $2::int)) AS s(id, v) ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v
  WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v)
  RETURNING merge_action(), t.* \bind 9 99 \g
-- @merge_returning_volatile_cte_source
WITH s AS (SELECT random() AS r, 1 AS id) MERGE INTO mt t USING s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = 1 RETURNING s.id;
-- @merge_returning_ambiguous_with_action
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET note = 'x' RETURNING merge_action() || v;
-- @merge_returning_source_row_by_source
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN NOT MATCHED BY SOURCE AND t.id = 5 THEN DELETE
  RETURNING s IS NULL AS no_source, row_to_json(s), t.id;
-- @merge_returning_aggregate
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET note = 'x' RETURNING count(*);
-- @merge_returning_subquery
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET note = 'x' RETURNING (SELECT max(x.v) FROM ms x WHERE x.id = t.id) AS top;
-- @merge_returning_repeated_names
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = 7 RETURNING t.v AS x, s.v AS x;
-- @merge_action_in_update_returning
UPDATE mt SET v = 1 WHERE false RETURNING merge_action();
-- @merge_table_named_merge_action
SET client_min_messages = warning;
DROP TABLE IF EXISTS merge_action;
CREATE TABLE merge_action (id int PRIMARY KEY, v int);
INSERT INTO merge_action (id, v) VALUES (1, 1);
SELECT * FROM merge_action;
DROP TABLE merge_action;
-- @merge_returning_window
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = 1 RETURNING row_number() OVER ();
-- @merge_action_in_source
MERGE INTO mt t USING (SELECT merge_action() AS a, 1 AS id WHERE false) s ON t.id = s.id
  WHEN MATCHED THEN DELETE;
-- @merge_returning_unnamed_source
MERGE INTO mt t USING (SELECT 1 AS sid) ON t.id = sid
  WHEN MATCHED THEN DELETE RETURNING "unnamed subquery".sid;
-- @merge_returning_subquery_of_target
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED THEN UPDATE SET v = s.v RETURNING (SELECT x.v FROM mt x WHERE x.id = t.id) AS old_v;
-- @merge_action_outside_returning
SELECT merge_action();
-- @merge_action_in_condition
MERGE INTO mt t USING (SELECT * FROM ms WHERE sid = 1) s ON t.id = s.id
  WHEN MATCHED AND merge_action() = 'UPDATE' THEN DELETE;
