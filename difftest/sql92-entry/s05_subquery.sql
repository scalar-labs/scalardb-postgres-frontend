-- S5 subqueries (7.11 scalar subquery / 8.x correlated)
-- @s05_7_11_scalar_in_where
SELECT empname FROM t92_staff WHERE grade = (SELECT MAX(grade) FROM t92_staff);
-- @s05_7_11_scalar_in_where_compare
SELECT pnum FROM t92_proj WHERE budget > (SELECT AVG(budget) FROM t92_proj);
-- @s05_7_11_scalar_correlated
SELECT pnum FROM t92_works w WHERE hours = (SELECT MAX(hours) FROM t92_works w2 WHERE w2.pnum = w.pnum);
-- @s05_7_11_scalar_empty_is_null
SELECT empname FROM t92_staff WHERE grade = (SELECT grade FROM t92_staff WHERE empnum = 'E9');
-- @s05_7_11_scalar_more_than_one_row
SELECT empname FROM t92_staff WHERE grade = (SELECT grade FROM t92_staff WHERE city = 'Deale');
-- @s05_7_11_scalar_more_than_one_row_unreached
SELECT empname FROM t92_staff WHERE empnum = 'E9' AND grade = (SELECT grade FROM t92_staff);
-- @s05_7_11_scalar_more_than_one_column
SELECT empname FROM t92_staff WHERE grade = (SELECT grade, empnum FROM t92_staff WHERE empnum = 'E1');
-- @s05_corr_in
SELECT empname FROM t92_staff s WHERE 'Deale' IN (SELECT p.city FROM t92_works w, t92_proj p WHERE w.empnum = s.empnum AND w.pnum = p.pnum);
-- @s05_corr_outer_ref_in_inner_select
SELECT pnum FROM t92_proj p WHERE EXISTS (SELECT p.budget FROM t92_works w WHERE w.pnum = p.pnum AND w.hours > 40);
-- @s05_corr_two_levels
SELECT empname FROM t92_staff s WHERE EXISTS (SELECT * FROM t92_works w WHERE w.empnum = s.empnum AND EXISTS (SELECT * FROM t92_proj p WHERE p.pnum = w.pnum AND p.city = s.city));
-- @s05_corr_in_having
SELECT pnum, SUM(hours) FROM t92_works w GROUP BY pnum HAVING SUM(hours) > (SELECT budget / 1000 FROM t92_proj p WHERE p.pnum = w.pnum);
-- @s05_uncorr_in_having
SELECT empnum FROM t92_works GROUP BY empnum HAVING COUNT(*) >= (SELECT COUNT(*) FROM t92_works WHERE empnum = 'E4');
-- @s05_subquery_same_table_shadow
SELECT empname FROM t92_staff WHERE grade > (SELECT MIN(grade) FROM t92_staff WHERE city = 'Vienna');
-- @s05_corr_unqualified_resolves_inner
SELECT empname FROM t92_staff WHERE EXISTS (SELECT * FROM t92_works WHERE t92_works.empnum = t92_staff.empnum AND hours > 70);
-- @s05_corr_with_group_by_in_sub
SELECT empname FROM t92_staff s WHERE 2 < (SELECT COUNT(*) FROM t92_works w WHERE w.empnum = s.empnum);
-- @s05_in_subquery_with_null
SELECT id FROM t92_nulls WHERE a IN (SELECT b FROM t92_nulls);
-- @s05_not_in_subquery_with_null
SELECT id FROM t92_nulls WHERE a NOT IN (SELECT b FROM t92_nulls);
-- @s05_not_in_subquery_no_null
SELECT id FROM t92_nulls WHERE a NOT IN (SELECT b FROM t92_nulls WHERE b IS NOT NULL);
