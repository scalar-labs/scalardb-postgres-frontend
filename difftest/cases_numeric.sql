-- @num_all ordered
SELECT * FROM nm ORDER BY k;
-- @num_gt_unrepresentable
SELECT k FROM nm WHERE a > 1.005;
-- @num_ge
SELECT k FROM nm WHERE a >= 1.01;
-- @num_lt_unrepresentable
SELECT k FROM nm WHERE a < 1.005;
-- @num_le_unrepresentable
SELECT k FROM nm WHERE a <= 1.005;
-- @num_eq_unrepresentable
SELECT k FROM nm WHERE a = 1.005;
-- @num_ne_unrepresentable
SELECT k FROM nm WHERE a <> 1.005;
-- @num_eq
SELECT k FROM nm WHERE a = 19.91;
-- @num_out_of_range
SELECT k FROM nm WHERE a < 1e20 AND a > -1e20;
-- @num_between
SELECT k FROM nm WHERE a BETWEEN 0 AND 20;
-- @num_arith ordered
SELECT k, a * 2 AS d, a + 0.001 AS e, a / 3 AS q, -a AS n FROM nm ORDER BY k;
-- @num_agg
SELECT sum(a), avg(a), max(a), min(c), count(b) FROM nm;
-- @num_order ordered
SELECT k, a FROM nm ORDER BY a NULLS FIRST, k;
-- @num_text ordered
SELECT k, a::text AS t, b::text AS u, c::text AS v FROM nm ORDER BY k;
-- @num_key_range
SELECT v FROM nk WHERE k = 1 AND p > 1.005;
-- @num_key_eq
SELECT v FROM nk WHERE k = 1 AND p = 2.5;
-- @num_key_eq_unrepresentable
SELECT v FROM nk WHERE k = 1 AND p = 2.505;
-- @num_key_desc ordered
SELECT p, v FROM nk WHERE k = 1 ORDER BY p DESC;
-- @num_join ordered
SELECT nk.v, nm.k FROM nk JOIN nm ON nm.a = nk.p ORDER BY 1, 2;
-- @num_overflow
INSERT INTO nm VALUES (9, 123456789, 1, 0);
-- @num_overflow_scale
INSERT INTO nm VALUES (9, 1, 1, 1);
-- @num_insert_round ordered
BEGIN;
INSERT INTO nm VALUES (10, '1.005'::float8, 9999.5, -0.00005);
SELECT * FROM nm WHERE k = 10;
ROLLBACK;
-- @num_update_returning
BEGIN;
UPDATE nm SET a = a * 100 WHERE k = 1 RETURNING k, a;
ROLLBACK;
-- @num_upsert_excluded
BEGIN;
INSERT INTO nm VALUES (4, 1.01, 7, 0.1) ON CONFLICT (k) DO UPDATE SET a = EXCLUDED.a * 100 RETURNING a;
ROLLBACK;
-- @num_delete_unrepresentable
BEGIN;
DELETE FROM nk WHERE k = 1 AND p = 1.005;
ROLLBACK;
-- @num_delete_key
BEGIN;
DELETE FROM nk WHERE k = 1 AND p = 1;
SELECT v FROM nk WHERE k = 2;
ROLLBACK;
-- @num_format_type ordered
SELECT attname, format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = 'nm'::regclass AND attnum > 0 ORDER BY attnum;
-- @num_information_schema ordered
SELECT column_name, data_type, numeric_precision, numeric_precision_radix, numeric_scale FROM information_schema.columns WHERE table_name = 'nm' AND column_name <> 'k' ORDER BY ordinal_position;
-- @num_float_eq
SELECT k FROM nm WHERE a = 0.1::float8 + 0.91::float8;
-- @num_float_eq_exact
SELECT k FROM nm WHERE a = 1.01::float8;
-- @num_float_gt
SELECT k FROM nm WHERE a > 1.01::float8;
-- @num_float_key
SELECT v FROM nk WHERE k = 1 AND p >= 2.5::float8;
-- @num_case_null ordered
SELECT k, (CASE WHEN a IS NULL THEN 3 ELSE a END) / 2 AS h FROM nm ORDER BY k;
-- @num_union_typmod ordered
SELECT a FROM nm UNION ALL SELECT p FROM nk ORDER BY 1 NULLS FIRST;
-- @numt_all ordered
SELECT * FROM nt ORDER BY k;
-- @numt_gt
SELECT k FROM nt WHERE b > 5;
-- @numt_eq
SELECT k FROM nt WHERE b = 1.1;
-- @numt_eq_text
SELECT k FROM nt WHERE b = '1.10';
-- @numt_eq_int
SELECT k FROM nt WHERE b = 9;
-- @numt_order ordered
SELECT k, b FROM nt ORDER BY b NULLS FIRST, k;
-- @numt_order_desc ordered
SELECT k, c FROM nt ORDER BY c DESC NULLS LAST, k;
-- @numt_group ordered
SELECT b, count(*) FROM nt WHERE k IN (3, 7) GROUP BY b;
-- @numt_distinct
SELECT DISTINCT b FROM nt WHERE k IN (3, 7);
-- @numt_arith ordered
SELECT k, b * 2 AS d, b + c AS s, c / 3 AS q FROM nt WHERE k <> 4 AND k <> 5 ORDER BY k;
-- @numt_agg
SELECT sum(c), avg(c), max(c), min(c), count(b) FROM nt;
-- @numt_agg_special
SELECT max(b), min(b), sum(b) FROM nt;
-- @numt_text ordered
SELECT k, b::text AS t, c::text AS u FROM nt ORDER BY k;
-- @numt_join ordered
SELECT nt.k, nm.k FROM nt JOIN nm ON nm.a = nt.c ORDER BY 1, 2;
-- @numt_like
SELECT k FROM nt WHERE b LIKE '1%';
-- @numt_overflow
INSERT INTO nt VALUES (9, 1, 1e20);
-- @numt_overflow_format
INSERT INTO nt VALUES (9, '1e-16384', 1);
-- @numt_insert ordered
BEGIN;
INSERT INTO nt VALUES (10, 1.500, 3.14159265358979323846264338327950288), (11, '-inf', '12'), (12, ' 1e3 ', '1.5e1');
SELECT * FROM nt WHERE k = 10;
SELECT * FROM nt WHERE k = 11;
SELECT * FROM nt WHERE k = 12;
ROLLBACK;
-- @numt_update_returning
BEGIN;
UPDATE nt SET b = b * 3, c = c / 7 WHERE k = 2 RETURNING b, c;
ROLLBACK;
-- @numt_delete_where
BEGIN;
DELETE FROM nt WHERE b = 1.1;
SELECT k FROM nt WHERE k = 3;
SELECT k FROM nt WHERE k = 7;
SELECT k FROM nt WHERE k = 1;
ROLLBACK;
-- @numt_format_type ordered
SELECT attname, format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = 'nt'::regclass AND attnum > 0 ORDER BY attnum;
-- @numt_information_schema ordered
SELECT column_name, data_type, numeric_precision, numeric_precision_radix, numeric_scale FROM information_schema.columns WHERE table_name = 'nt' AND column_name <> 'k' ORDER BY ordinal_position;
-- @numt_like_cte
WITH x AS (SELECT b FROM nt) SELECT b FROM x WHERE b LIKE '1%';
-- @numt_like_typed
SELECT k FROM nt WHERE b LIKE '1%'::varchar;
-- @numt_like_session
SELECT count(*) FROM nt WHERE current_user LIKE 'post%';
