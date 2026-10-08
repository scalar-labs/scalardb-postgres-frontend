-- S3 query specification (7.4 FROM / 7.6 WHERE / 7.7 GROUP BY / 7.8 HAVING / 7.9 query specification / 7.10 query expression; ORDER BY is 13.1 cursor specification)
-- @s03_7_9_select_star
SELECT * FROM t92_staff;
-- @s03_7_9_select_list
SELECT empname, grade FROM t92_staff;
-- @s03_7_9_qualified_star
SELECT t92_staff.* FROM t92_staff WHERE grade = 12;
-- @s03_7_9_correlation_name
SELECT s.empname FROM t92_staff s WHERE s.city = 'Vienna';
-- @s03_7_9_correlation_name_as
SELECT s.empname FROM t92_staff AS s WHERE s.city = 'Vienna';
-- @s03_7_9_derived_column_as
SELECT empname AS name, grade * 2 AS g2 FROM t92_staff;
-- @s03_7_9_distinct
SELECT DISTINCT city FROM t92_staff;
-- @s03_7_9_all
SELECT ALL city FROM t92_staff;
-- @s03_7_9_distinct_multi
SELECT DISTINCT ptype, city FROM t92_proj;
-- @s03_7_9_literal_only_select
SELECT 1, 'a' FROM t92_staff WHERE empnum = 'E1';
-- @s03_7_4_cross_join_comma
SELECT s.empnum, p.pnum FROM t92_staff s, t92_proj p;
-- @s03_7_4_join_comma_where
SELECT s.empname, w.pnum, w.hours FROM t92_staff s, t92_works w WHERE s.empnum = w.empnum;
-- @s03_7_4_three_way_join
SELECT s.empname, p.pname, w.hours FROM t92_staff s, t92_works w, t92_proj p WHERE s.empnum = w.empnum AND w.pnum = p.pnum AND p.city = 'Deale';
-- @s03_7_4_self_join
SELECT a.empname, b.empname FROM t92_staff a, t92_staff b WHERE a.city = b.city AND a.empnum < b.empnum;
-- @s03_7_4_join_non_equi
SELECT s.empnum, p.pnum FROM t92_staff s, t92_proj p WHERE s.grade * 1000 > p.budget / 3;
-- @s03_7_4_same_table_twice_unqualified_cols
SELECT empname, pnum FROM t92_staff, t92_works WHERE t92_staff.empnum = t92_works.empnum AND hours > 40;
-- @s03_7_7_group_by
SELECT city, COUNT(*) FROM t92_staff GROUP BY city;
-- @s03_7_7_group_by_two_cols
SELECT ptype, city, SUM(budget) FROM t92_proj GROUP BY ptype, city;
-- @s03_7_7_group_by_join
SELECT s.city, SUM(w.hours) FROM t92_staff s, t92_works w WHERE s.empnum = w.empnum GROUP BY s.city;
-- @s03_7_7_group_by_null_group
SELECT a, COUNT(*) FROM t92_nulls GROUP BY a;
-- @s03_7_7_group_by_col_not_selected
SELECT COUNT(*) FROM t92_works GROUP BY pnum;
-- @s03_7_8_having
SELECT pnum, SUM(hours) FROM t92_works GROUP BY pnum HAVING SUM(hours) > 50;
-- @s03_7_8_having_count
SELECT empnum FROM t92_works GROUP BY empnum HAVING COUNT(*) > 2;
-- @s03_7_8_having_without_group_by
SELECT COUNT(*) FROM t92_works HAVING COUNT(*) > 5;
-- @s03_7_8_having_without_group_by_false
SELECT COUNT(*) FROM t92_works HAVING COUNT(*) > 500;
-- @s03_7_8_having_grouping_col
SELECT city, MAX(grade) FROM t92_staff GROUP BY city HAVING city <> 'Akron';
-- @s03_7_9_ungrouped_column_error
SELECT city, empname FROM t92_staff GROUP BY city;
-- @s03_7_10_union
SELECT city FROM t92_staff UNION SELECT city FROM t92_proj;
-- @s03_7_10_union_all
SELECT city FROM t92_staff UNION ALL SELECT city FROM t92_proj;
-- @s03_7_10_union_three
SELECT empnum FROM t92_staff WHERE grade = 10 UNION SELECT empnum FROM t92_staff WHERE grade = 13 UNION SELECT empnum FROM t92_works WHERE hours = 80;
-- @s03_7_10_union_order_by_position ordered
SELECT empnum, grade FROM t92_staff WHERE city = 'Deale' UNION SELECT empnum, grade FROM t92_staff WHERE city = 'Akron' ORDER BY 2 DESC, 1;
-- @s03_7_10_union_column_count_mismatch
SELECT empnum FROM t92_staff UNION SELECT pnum, budget FROM t92_proj;
-- @s03_7_10_union_type_mismatch
SELECT empnum FROM t92_staff UNION SELECT budget FROM t92_proj;
-- @s03_13_1_order_by_col ordered
SELECT empname, grade FROM t92_staff ORDER BY empname;
-- @s03_13_1_order_by_desc ordered
SELECT empname, grade FROM t92_staff ORDER BY grade DESC, empname ASC;
-- @s03_13_1_order_by_position ordered
SELECT empname, grade FROM t92_staff ORDER BY 2, 1;
-- @s03_13_1_order_by_not_selected ordered
SELECT empname FROM t92_staff ORDER BY grade, empname;
-- @s03_13_1_order_by_nulls ordered
SELECT id, a FROM t92_nulls ORDER BY a, id;
-- @s03_13_1_order_by_nulls_desc ordered
SELECT id, a FROM t92_nulls ORDER BY a DESC, id;
-- @s03_13_1_order_by_join ordered
SELECT s.empname, w.pnum, w.hours FROM t92_staff s, t92_works w WHERE s.empnum = w.empnum ORDER BY w.hours DESC, s.empname, w.pnum;
-- @s03_13_1_order_by_aggregate ordered
SELECT pnum, SUM(hours) FROM t92_works GROUP BY pnum ORDER BY 2 DESC, 1;
-- @s03_13_1_order_by_position_out_of_range
SELECT empname FROM t92_staff ORDER BY 3;
-- @s03_7_9_unknown_column_error
SELECT nosuch FROM t92_staff;
-- @s03_7_4_unknown_table_error
SELECT * FROM t92_nosuch;
-- @s03_7_4_ambiguous_column_error
SELECT empnum FROM t92_staff, t92_works;
