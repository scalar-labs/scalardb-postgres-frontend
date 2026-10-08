-- S7 NULL and three-valued logic (8.12 search condition truth tables, 8.2 comparisons with NULL)
-- @s07_eq_null_literal
SELECT id FROM t92_nulls WHERE a = NULL;
-- @s07_ne_null_literal
SELECT id FROM t92_nulls WHERE a <> NULL;
-- @s07_cmp_null_column
SELECT id FROM t92_nulls WHERE a = b;
-- @s07_cmp_null_column_ne
SELECT id FROM t92_nulls WHERE a <> b;
-- @s07_not_unknown
SELECT id FROM t92_nulls WHERE NOT (a = 1);
-- @s07_and_unknown_false
SELECT id FROM t92_nulls WHERE NOT (a = 1 AND b = 5);
-- @s07_or_unknown_true
SELECT id FROM t92_nulls WHERE a = 1 OR b = 3;
-- @s07_or_unknown_unknown
SELECT id FROM t92_nulls WHERE NOT (a = 1 OR b = 1);
-- @s07_truth_table
SELECT id, a, b FROM t92_nulls WHERE (a > 0) = (b > 0);
-- @s07_null_in_list
SELECT id FROM t92_nulls WHERE a IN (1, NULL);
-- @s07_null_not_in_list
SELECT id FROM t92_nulls WHERE a NOT IN (1, NULL);
-- @s07_null_not_in_list_no_null
SELECT id FROM t92_nulls WHERE a NOT IN (1);
-- @s07_between_null
SELECT id FROM t92_nulls WHERE a BETWEEN 0 AND b;
-- @s07_not_between_null
SELECT id FROM t92_nulls WHERE a NOT BETWEEN b AND 5;
-- @s07_like_null_column
SELECT id FROM t92_nulls WHERE NOT (s LIKE 'x%');
-- @s07_having_unknown
SELECT a, COUNT(*) FROM t92_nulls GROUP BY a HAVING a > 0;
-- @s07_having_not_unknown
SELECT a, COUNT(*) FROM t92_nulls GROUP BY a HAVING NOT (a > 1);
-- @s07_distinct_nulls_one_group
SELECT DISTINCT a FROM t92_nulls;
-- @s07_union_nulls_dedup
SELECT a FROM t92_nulls UNION SELECT b FROM t92_nulls;
-- @s07_join_on_null_no_match
SELECT x.id, y.id FROM t92_nulls x, t92_nulls y WHERE x.a = y.b;
-- @s07_null_literal_select
SELECT NULL, id FROM t92_nulls WHERE id = 1;
-- @s07_is_null_on_expr
SELECT id FROM t92_nulls WHERE a + b IS NULL;
