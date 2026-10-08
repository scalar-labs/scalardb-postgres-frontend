-- S4 predicates (8.2 comparison / 8.3 between / 8.4 in / 8.5 like / 8.6 null / 8.7 quantified / 8.8 exists / 8.12 search condition)
-- @s04_8_2_eq_text
SELECT empnum FROM t92_staff WHERE city = 'Deale';
-- @s04_8_2_ne
SELECT empnum FROM t92_staff WHERE city <> 'Deale';
-- @s04_8_2_lt_le_gt_ge
SELECT empnum FROM t92_staff WHERE grade < 13 AND grade >= 10 AND grade <= 12 AND grade > 9;
-- @s04_8_2_text_ordering
SELECT empname FROM t92_staff WHERE empname > 'Betty';
-- @s04_8_2_text_vs_case
SELECT empname FROM t92_staff WHERE empname > 'b';
-- @s04_8_2_text_lt_lowercase_collation
SELECT empname FROM t92_staff WHERE empname < 'a';
-- @s04_8_3_between_text_mixed_case
SELECT empname FROM t92_staff WHERE empname BETWEEN 'A' AND 'a';
-- @s04_8_2_column_vs_column
SELECT pnum FROM t92_proj WHERE pname < ptype;
-- @s04_8_2_int_vs_decimal_literal
SELECT empnum FROM t92_staff WHERE grade > 12.5;
-- @s04_8_2_trailing_space_varchar
SELECT empnum FROM t92_staff WHERE city = 'Deale ';
-- @s04_8_3_between
SELECT pnum FROM t92_proj WHERE budget BETWEEN 10000 AND 30000;
-- @s04_8_3_not_between
SELECT pnum FROM t92_proj WHERE budget NOT BETWEEN 10000 AND 30000;
-- @s04_8_3_between_reversed_bounds
SELECT pnum FROM t92_proj WHERE budget BETWEEN 30000 AND 10000;
-- @s04_8_3_between_text
SELECT empname FROM t92_staff WHERE empname BETWEEN 'B' AND 'D';
-- @s04_8_3_between_pk
SELECT empname FROM t92_staff WHERE empnum BETWEEN 'E2' AND 'E4';
-- @s04_8_4_in_list
SELECT empname FROM t92_staff WHERE city IN ('Deale', 'Akron');
-- @s04_8_4_not_in_list
SELECT empname FROM t92_staff WHERE city NOT IN ('Deale', 'Akron');
-- @s04_8_4_in_list_pk
SELECT empname FROM t92_staff WHERE empnum IN ('E1', 'E5', 'E9');
-- @s04_8_4_in_single
SELECT empname FROM t92_staff WHERE grade IN (13);
-- @s04_8_4_in_subquery
SELECT empname FROM t92_staff WHERE empnum IN (SELECT empnum FROM t92_works WHERE pnum = 'P2');
-- @s04_8_4_not_in_subquery
SELECT empname FROM t92_staff WHERE empnum NOT IN (SELECT empnum FROM t92_works);
-- @s04_8_4_in_list_with_expr
SELECT pnum FROM t92_proj WHERE budget IN (5000 * 2, 10000 + 10000);
-- @s04_8_5_like_percent
SELECT empname FROM t92_staff WHERE empname LIKE 'C%';
-- @s04_8_5_like_underscore
SELECT empname FROM t92_staff WHERE empname LIKE '_d';
-- @s04_8_5_like_middle
SELECT pname FROM t92_proj WHERE pname LIKE '%S%';
-- @s04_8_5_not_like
SELECT empname FROM t92_staff WHERE empname NOT LIKE '%e%';
-- @s04_8_5_like_case_sensitive
SELECT empname FROM t92_staff WHERE empname LIKE 'a%';
-- @s04_8_5_like_no_wildcard
SELECT empname FROM t92_staff WHERE empname LIKE 'Ed';
-- @s04_8_5_like_escape
SELECT x FROM (SELECT 'a%b' AS x UNION ALL SELECT 'axb') t WHERE x LIKE 'a!%b' ESCAPE '!';
-- @s04_8_5_like_escape_table
SELECT empname FROM t92_staff WHERE empname LIKE '%!_%' ESCAPE '!';
-- @s04_8_5_like_escape_underscore_table
SELECT pname FROM t92_proj WHERE ptype LIKE 'D\_sign' ESCAPE '\';
-- @s04_8_5_like_bad_escape
SELECT empname FROM t92_staff WHERE empname LIKE 'A%' ESCAPE 'xx';
-- @s04_8_5_like_null_pattern
SELECT id FROM t92_nulls WHERE s LIKE NULL;
-- @s04_8_5_like_column_pattern
SELECT a.empname FROM t92_staff a, t92_staff b WHERE a.empname LIKE b.empname;
-- @s04_8_6_is_null
SELECT id FROM t92_nulls WHERE a IS NULL;
-- @s04_8_6_is_not_null
SELECT id FROM t92_nulls WHERE b IS NOT NULL;
-- @s04_8_6_is_null_text
SELECT id FROM t92_nulls WHERE s IS NULL;
-- @s04_8_7_gt_all
SELECT pnum FROM t92_proj WHERE budget > ALL (SELECT budget FROM t92_proj WHERE city = 'Vienna');
-- @s04_8_7_gt_any
SELECT pnum FROM t92_proj WHERE budget > ANY (SELECT budget FROM t92_proj WHERE city = 'Vienna');
-- @s04_8_7_eq_some
SELECT empname FROM t92_staff WHERE grade = SOME (SELECT grade FROM t92_staff WHERE city = 'Akron');
-- @s04_8_7_all_empty
SELECT empnum FROM t92_staff WHERE grade > ALL (SELECT grade FROM t92_staff WHERE city = 'Nowhere');
-- @s04_8_7_any_empty
SELECT empnum FROM t92_staff WHERE grade > ANY (SELECT grade FROM t92_staff WHERE city = 'Nowhere');
-- @s04_8_7_all_with_null
SELECT id FROM t92_nulls WHERE a < ALL (SELECT b FROM t92_nulls WHERE id IN (1, 2));
-- @s04_8_7_ne_all
SELECT empnum FROM t92_staff WHERE city <> ALL (SELECT city FROM t92_proj);
-- @s04_8_8_exists
SELECT empname FROM t92_staff s WHERE EXISTS (SELECT * FROM t92_works w WHERE w.empnum = s.empnum AND w.hours = 80);
-- @s04_8_8_not_exists
SELECT empname FROM t92_staff s WHERE NOT EXISTS (SELECT * FROM t92_works w WHERE w.empnum = s.empnum);
-- @s04_8_8_exists_uncorrelated
SELECT empname FROM t92_staff WHERE EXISTS (SELECT * FROM t92_proj WHERE budget > 40000);
-- @s04_8_8_exists_uncorrelated_false
SELECT empname FROM t92_staff WHERE EXISTS (SELECT * FROM t92_proj WHERE budget > 400000);
-- @s04_8_8_relational_division
SELECT empname FROM t92_staff s WHERE NOT EXISTS (SELECT * FROM t92_proj p WHERE NOT EXISTS (SELECT * FROM t92_works w WHERE w.empnum = s.empnum AND w.pnum = p.pnum));
-- @s04_8_12_and_or_precedence
SELECT empnum FROM t92_staff WHERE city = 'Akron' OR city = 'Deale' AND grade = 13;
-- @s04_8_12_parens
SELECT empnum FROM t92_staff WHERE (city = 'Akron' OR city = 'Deale') AND grade = 13;
-- @s04_8_12_not
SELECT empnum FROM t92_staff WHERE NOT (grade = 12);
-- @s04_8_12_not_precedence
SELECT empnum FROM t92_staff WHERE NOT grade = 12 AND city = 'Vienna';
-- @s04_8_12_or_on_pk_and_other
SELECT empnum FROM t92_staff WHERE empnum = 'E1' OR grade = 13;
-- @s04_8_12_or_across_tables
SELECT s.empnum, w.pnum FROM t92_staff s, t92_works w WHERE s.empnum = w.empnum AND (s.city = 'Akron' OR w.hours = 80);
-- @s04_8_2_cmp_type_error
SELECT empnum FROM t92_staff WHERE grade = 'abc';
