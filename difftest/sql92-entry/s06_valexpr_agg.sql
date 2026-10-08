-- S6 value expressions and aggregates (6.5 set function / 6.12 numeric value expression / 5.3 literal)
-- @s06_6_12_arith
SELECT empnum, grade + 1, grade - 1, grade * 2, grade / 2 FROM t92_staff;
-- @s06_6_12_precedence
SELECT 2 + 3 * 4, (2 + 3) * 4, 10 - 2 - 3, 100 / 10 / 5 FROM t92_staff WHERE empnum = 'E1';
-- @s06_6_12_unary_minus
SELECT -grade, - -grade, -(grade - 20) FROM t92_staff WHERE empnum = 'E1';
-- @s06_6_12_int_division_truncates
SELECT 7 / 2, -7 / 2, 7 / -2 FROM t92_staff WHERE empnum = 'E1';
-- @s06_6_12_int_column_division
SELECT empnum, hours / 3 FROM t92_works;
-- @s06_6_12_decimal_literal_arith
SELECT 0.1 + 0.2, 1.10 * 3, 10.0 / 4 FROM t92_staff WHERE empnum = 'E1';
-- @s06_6_12_decimal_literal_eq
SELECT empnum FROM t92_staff WHERE 0.1 + 0.2 = 0.3;
-- @s06_6_12_exact_times_approx
SELECT 1.5E0 * 2, 1E2 + 1 FROM t92_staff WHERE empnum = 'E1';
-- @s06_6_12_div_by_zero
SELECT grade / 0 FROM t92_staff WHERE empnum = 'E1';
-- @s06_6_12_div_by_zero_column
SELECT a / b FROM t92_nulls;
-- @s06_6_12_div_by_zero_unreached
SELECT grade / 0 FROM t92_staff WHERE empnum = 'E9';
-- @s06_6_12_int_overflow
SELECT 2147483647 + 1 FROM t92_staff WHERE empnum = 'E1';
-- @s06_6_12_int_column_overflow
SELECT budget * 100000 FROM t92_proj WHERE pnum = 'P6';
-- @s06_6_12_null_arith
SELECT id, a + b, a * 0, a - a FROM t92_nulls;
-- @s06_6_12_arith_in_where
SELECT pnum FROM t92_proj WHERE budget / 1000 * 2 > 50;
-- @s06_6_12_arith_on_pk_side
SELECT empname FROM t92_staff WHERE grade + 0 = 12;
-- @s06_6_5_count_star
SELECT COUNT(*) FROM t92_works;
-- @s06_6_5_count_col_skips_null
SELECT COUNT(a), COUNT(b), COUNT(s), COUNT(*) FROM t92_nulls;
-- @s06_6_5_count_distinct
SELECT COUNT(DISTINCT city), COUNT(DISTINCT ptype) FROM t92_proj;
-- @s06_6_5_count_distinct_null
SELECT COUNT(DISTINCT a) FROM t92_nulls;
-- @s06_6_5_sum_avg_min_max
SELECT SUM(hours), AVG(hours), MIN(hours), MAX(hours) FROM t92_works;
-- @s06_6_5_avg_int_type
SELECT AVG(grade) FROM t92_staff;
-- @s06_6_5_avg_int_group
SELECT pnum, AVG(hours) FROM t92_works GROUP BY pnum;
-- @s06_6_5_sum_distinct
SELECT SUM(DISTINCT budget), AVG(DISTINCT budget) FROM t92_proj;
-- @s06_6_5_min_max_text
SELECT MIN(empname), MAX(city) FROM t92_staff;
-- @s06_6_5_aggregates_null
SELECT SUM(a), AVG(a), MIN(a), MAX(a), SUM(b), AVG(b) FROM t92_nulls;
-- @s06_6_5_aggregates_empty
SELECT COUNT(*), COUNT(a), SUM(a), AVG(a), MIN(a), MAX(a) FROM t92_nulls WHERE id > 100;
-- @s06_6_5_aggregates_empty_group_by
SELECT a, COUNT(*) FROM t92_nulls WHERE id > 100 GROUP BY a;
-- @s06_6_5_aggregate_of_expr
SELECT SUM(hours * 2), MAX(hours + 1) FROM t92_works;
-- @s06_6_5_expr_of_aggregates
SELECT SUM(hours) / COUNT(*), MAX(hours) - MIN(hours) FROM t92_works;
-- @s06_6_5_aggregate_where_pk
SELECT SUM(hours) FROM t92_works WHERE empnum = 'E1';
-- @s06_6_5_aggregate_in_where_error
SELECT empnum FROM t92_staff WHERE MAX(grade) > 10;
-- @s06_6_5_nested_aggregate_error
SELECT MAX(COUNT(*)) FROM t92_works GROUP BY pnum;
-- @s06_6_5_sum_int_overflow
SELECT SUM(budget * 40000) FROM t92_proj;
-- @s06_5_3_literals
SELECT 12, -3, 1.50, .5, 5., 1E3, 1.5E-2, 'it''s', '' FROM t92_staff WHERE empnum = 'E1';
-- @s06_5_3_string_literal_continuation
SELECT 'ab'
'cd' FROM t92_staff WHERE empnum = 'E1';
-- @s06_user
SELECT USER FROM t92_staff WHERE empnum = 'E1';
