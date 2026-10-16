-- Types ScalarDB does not have: NUMERIC(p,s), NUMERIC, CHAR(n), VARCHAR(n) and SMALLINT, with psql.
-- Paste the statements one at a time, or run the file with psql -f types.sql. It needs no other
-- file, and the statements that fail are meant to: each shows the error PostgreSQL gives. To also see
-- what is stored underneath (section 8), pass the database ScalarDB uses:
--   psql -h localhost -p 15432 -d demo -v backend='host=localhost port=5432 dbname=scalardb user=postgres' -f types.sql
\pset pager off

\echo
\echo '=== 1. Once per deployment: the table the frontend records declared types in'
CREATE FRONTEND METADATA TABLES;

\echo
\echo '=== 2. Declare the types as in PostgreSQL; \\d shows them back'
DROP TABLE IF EXISTS types_demo.invoices;
CREATE SCHEMA IF NOT EXISTS types_demo;
CREATE TABLE types_demo.invoices (
  id       INT PRIMARY KEY,
  amount   NUMERIC(10,2),     -- a ScalarDB BIGINT holding cents
  rate     NUMERIC,           -- ScalarDB TEXT holding the exact decimal
  currency CHAR(3),           -- ScalarDB TEXT, padded back on output
  memo     VARCHAR(10),       -- ScalarDB TEXT, length checked on write
  qty      SMALLINT,          -- ScalarDB INT, range checked on write
  approx   DOUBLE PRECISION   -- a plain ScalarDB DOUBLE, for comparison
);
CREATE TABLE types_demo.invoices (
  id       INT PRIMARY KEY,
  amount   NUMERIC(10,2),
  rate     NUMERIC,
  currency CHAR(3),
  memo     VARCHAR(10),
  qty      SMALLINT,
  approx   DOUBLE PRECISION
);
\d types_demo.invoices

\echo
\echo '=== 3. Values are rounded to the declared scale, exactly'
INSERT INTO types_demo.invoices VALUES (1, 19.995, 0.1, 'JPY', 'first', 3, 0.1), (2, 0.10, 12345678901234567890.123456789, 'EU', 'second', -7, 0.2), (3, 99999999.99, 0.2, 'USD', NULL, 32767, 0.3);
SELECT id, amount, rate, '[' || currency || ']' AS currency, currency AS padded, memo, qty FROM types_demo.invoices ORDER BY id;

\echo
\echo '=== 4. Arithmetic is exact, unlike DOUBLE PRECISION'
SELECT sum(amount) AS amount_sum, sum(rate) AS rate_sum, sum(approx) AS approx_sum FROM types_demo.invoices WHERE id < 3;
SELECT rate + 0.2 AS exact, approx + 0.2 AS approx FROM types_demo.invoices WHERE id = 1;
UPDATE types_demo.invoices SET amount = amount + 0.005 WHERE id = 2;
SELECT id, amount FROM types_demo.invoices WHERE id = 2;

\echo
\echo '=== 5. PostgreSQL errors for values that do not fit (each statement below fails)'
INSERT INTO types_demo.invoices (id, amount) VALUES (10, 100000000.00);
INSERT INTO types_demo.invoices (id, memo) VALUES (11, 'eleven chars');
INSERT INTO types_demo.invoices (id, currency) VALUES (12, 'EURO');
INSERT INTO types_demo.invoices (id, qty) VALUES (13, 32768);

\echo
\echo '=== 6. A NUMERIC(p,s) condition is pushed down to ScalarDB, scaled to the stored integer'
SELECT id, amount FROM types_demo.invoices WHERE amount >= 0.11 ORDER BY amount;
EXPLAIN SELECT id, amount FROM types_demo.invoices WHERE amount >= 0.11;

\echo
\echo '=== 7. NUMERIC(p,s) up to 18 digits can be a key; NUMERIC without a precision cannot (fails)'
DROP TABLE IF EXISTS types_demo.prices;
CREATE TABLE types_demo.prices (price NUMERIC(12,2) PRIMARY KEY, label VARCHAR(20));
INSERT INTO types_demo.prices VALUES (1.5, 'one fifty'), (10, 'ten');
SELECT * FROM types_demo.prices WHERE price = 1.50;
CREATE TABLE types_demo.bad (price NUMERIC PRIMARY KEY);

\echo
\echo '=== 8. Underneath, in the database ScalarDB uses: plain BIGINT, TEXT and INT columns, and one row per declared type'
\if :{?backend}
\connect :backend
SELECT id, amount, rate, currency, memo, qty FROM types_demo.invoices ORDER BY id;
SELECT column_name, sql_type, type_precision, type_scale, type_length, encoding, data_type
  FROM scalardb_postgres_frontend.column_types WHERE full_table_name = 'types_demo.invoices' ORDER BY column_name;
\else
\echo '(skipped: run with -v backend=... as shown at the top of this file)'
\endif
