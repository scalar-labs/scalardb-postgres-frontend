-- @ch_all ordered
SELECT * FROM ch ORDER BY k;
-- @ch_text ordered
SELECT k, c || '|' AS c1, char_length(c) AS n, octet_length(v) AS o, c::text AS t, v || '|' AS v1 FROM ch ORDER BY k;
-- @ch_eq_pad
SELECT k FROM ch WHERE c = 'ab   ';
-- @ch_eq
SELECT k FROM ch WHERE c = 'ab';
-- @ch_range
SELECT k FROM ch WHERE c > 'ab ';
-- @ch_order ordered
SELECT k, c FROM ch ORDER BY c NULLS LAST, k;
-- @ch_like
SELECT k FROM ch WHERE c LIKE 'ab%';
-- @ch_too_long
INSERT INTO ch VALUES (9, 'abcdef', 'x', 1, 'a');
-- @ch_too_long_spaces ordered
BEGIN;
INSERT INTO ch VALUES (9, 'abc     ', 'x       ', 1, 'a  ');
SELECT k, c, v || '|', b || '|' FROM ch WHERE k = 9;
ROLLBACK;
-- @vc_too_long
INSERT INTO ch VALUES (9, 'a', 'abcdefgh', 1, 'a');
-- @vc_eq_long
SELECT k FROM ch WHERE v = 'abcdefghij';
-- @sm_range
INSERT INTO ch VALUES (9, 'a', 'x', 32768, 'a');
-- @sm_range_neg
INSERT INTO ch VALUES (9, 'a', 'x', -32769, 'a');
-- @sm_cmp
SELECT k FROM ch WHERE s = 40000;
-- @sm_cmp_lt
SELECT k FROM ch WHERE s < 40000;
-- @sm_cmp_gt_neg
SELECT k FROM ch WHERE s > -40000;
-- @sm_arith ordered
SELECT k, s + 1 AS p, s * 2 AS d, s / 2 AS h FROM ch WHERE k = 1;
-- @ch_key_eq
SELECT n FROM chk WHERE c = 'ab  ' AND v = 'y';
-- @ch_key_order ordered
SELECT c, v FROM chk ORDER BY c, v;
-- @ch_key_pad
SELECT n FROM chk WHERE c = 'b';
-- @ch_update_returning
BEGIN;
UPDATE ch SET c = 'zz ', v = 'w', s = 5 WHERE k = 1 RETURNING c, v, s;
ROLLBACK;
-- @ch_format_type ordered
SELECT attname, format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = 'ch'::regclass AND attnum > 0 ORDER BY attnum;
-- @ch_information_schema ordered
SELECT column_name, data_type, character_maximum_length FROM information_schema.columns WHERE table_name = 'ch' AND column_name <> 'k' ORDER BY ordinal_position;
-- @ch_like_exact ordered
SELECT k, c LIKE 'ab' AS a, c LIKE 'ab   ' AS b, c LIKE 'ab___' AS u, c NOT LIKE 'abc' AS n FROM ch ORDER BY k;
-- @ch_in_pad ordered
SELECT k FROM ch WHERE c IN ('ab   ', 'abcde ') ORDER BY k;
-- @ch_ne_pad ordered
SELECT k FROM ch WHERE c <> 'ab  ' ORDER BY k;
-- @ch_or_pad ordered
SELECT k FROM ch WHERE c = 'ab   ' OR k = 3 ORDER BY k;
-- @sm_quoted_range
SELECT k FROM ch WHERE s = '40000';
-- @sm_quoted_syntax
SELECT k FROM ch WHERE s < '1.5';
-- @pad_functions
SELECT rpad('ab', 5) || '|' AS r, lpad('7', 3, '0') AS l, rpad('abcdef', 3) AS t, lpad('x', 4, 'ab') AS f, rpad('a', 0) AS z;
-- @ch_max
SELECT '|' || max(c) || '|' AS m FROM ch;
-- @ch_between_pad ordered
SELECT k FROM ch WHERE c BETWEEN 'ab ' AND 'abc ' ORDER BY k;
-- @ch_distinct_pad ordered
SELECT k FROM ch WHERE c IS NOT DISTINCT FROM 'ab ' OR k = 3 ORDER BY k;
-- @ch_case_pad ordered
SELECT k, CASE c WHEN 'ab ' THEN 'two' WHEN 'abcde  ' THEN 'five' ELSE 'other' END AS w FROM ch ORDER BY k;
-- @ch_in_left_pad ordered
SELECT k FROM ch WHERE 'ab  ' IN (c, v) ORDER BY k;
-- @ch_vs_text ordered
SELECT k, c = 'ab '::text AS t, c = 'ab '::varchar AS v FROM ch ORDER BY k;
-- @ch_derived ordered
SELECT k, d.c = 'ab ' AS e, d.c LIKE 'ab' AS l, d.c LIKE 'ab   ' AS p FROM (SELECT k, c FROM ch) d ORDER BY k;
-- @ch_cte_like ordered
WITH x AS (SELECT k, c FROM ch) SELECT k FROM x WHERE c LIKE 'abc  ' ORDER BY k;
-- @pad_numeric_length
SELECT lpad('a', 2.9);
-- @ch_numeric_info ordered
SELECT column_name, numeric_precision, numeric_precision_radix, numeric_scale FROM information_schema.columns WHERE table_name = 'ch' ORDER BY ordinal_position;
-- @int_quoted_beyond_range
SELECT k FROM ch WHERE k = '5000000000';
-- @int_quoted_beyond_range_or
SELECT c FROM chk WHERE n = '40000000000' OR n = 1;
-- @int_number_beyond_range
SELECT k FROM ch WHERE k = 5000000000;
-- @bigint_quoted_beyond_range
SELECT k FROM ch WHERE abs(k::bigint) = '99999999999999999999';
-- @int_quoted_in_memory_beyond_range
SELECT k FROM ch WHERE k + 0 = '5000000000';
-- @sm_quoted_beyond_range_or
SELECT k FROM ch WHERE s = '40000' OR k = 1;
-- @int_input_hex ordered
SELECT k FROM ch WHERE k = '0x4' OR s = '0x7' ORDER BY k;
-- @int_input_underscore ordered
SELECT k FROM ch WHERE s = '32_767' OR k + 0 = '0o_3' ORDER BY k;
-- @int_input_binary ordered
SELECT k FROM ch WHERE s = '-0b1000000000000000' OR coalesce(s, 0) = '0B111' ORDER BY k;
-- @int_input_casts
SELECT '0x10'::int, '1_000'::bigint, '-0b11'::smallint, ' 0o_17 '::int, '-0x80000000'::int;
-- @int_input_hex_beyond_range
SELECT k FROM ch WHERE k = '0x80000000';
-- @int_input_double_underscore
SELECT k FROM ch WHERE k = '1__0';
-- @int_input_fraction
SELECT k FROM ch WHERE k = '1.0';
-- @int_input_exponent
SELECT n FROM chk WHERE n + 0 = '1e3';
