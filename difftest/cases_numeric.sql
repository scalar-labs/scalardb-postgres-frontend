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
SELECT column_name, data_type, numeric_precision, numeric_scale FROM information_schema.columns WHERE table_name = 'nm' AND column_name <> 'k' ORDER BY ordinal_position;
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
