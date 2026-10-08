-- S10 identifiers and lexical elements (5.2 token / 5.3 literal / 5.4 names / 5.1 comments)
-- @s10_5_2_keywords_case_insensitive
select EMPNAME from T92_STAFF where EmpNum = 'E1';
-- @s10_5_2_delimited_identifier_lower
SELECT "empname" FROM "t92_staff" WHERE "empnum" = 'E1';
-- @s10_5_2_delimited_identifier_upper_no_match
SELECT "EMPNAME" FROM t92_staff WHERE empnum = 'E1';
-- @s10_5_2_delimited_identifier_table
DROP TABLE IF EXISTS "T92 Mixed"; CREATE TABLE "T92 Mixed" ("Id" INTEGER, "Select" INTEGER, PRIMARY KEY ("Id")); INSERT INTO "T92 Mixed" VALUES (1, 2); SELECT "Id", "Select" FROM "T92 Mixed"; DROP TABLE "T92 Mixed";
-- @s10_5_2_delimited_identifier_case_distinct
DROP TABLE IF EXISTS t92_w10_c05; CREATE TABLE t92_w10_c05 ("a" INTEGER, "A" INTEGER, PRIMARY KEY ("a")); INSERT INTO t92_w10_c05 VALUES (1, 2); SELECT "a", "A" FROM t92_w10_c05; DROP TABLE t92_w10_c05;
-- @s10_5_2_delimited_identifier_embedded_quote
DROP TABLE IF EXISTS t92_w10_c06; CREATE TABLE t92_w10_c06 ("we""ird" INTEGER, PRIMARY KEY ("we""ird")); INSERT INTO t92_w10_c06 VALUES (1); SELECT "we""ird" FROM t92_w10_c06; DROP TABLE t92_w10_c06;
-- @s10_5_2_reserved_word_as_identifier_error
DROP TABLE IF EXISTS t92_w10_c07; CREATE TABLE t92_w10_c07 (select INTEGER, PRIMARY KEY (select)); DROP TABLE IF EXISTS t92_w10_c07;
-- @s10_5_3_string_quote_escape
SELECT 'O''Brien', 'a''''b' FROM t92_staff WHERE empnum = 'E1';
-- @s10_5_3_string_case_sensitive_compare
SELECT empnum FROM t92_staff WHERE city = 'deale';
-- @s10_5_3_exact_numeric_literals
SELECT 007, 1.0, 1.000, 0.5, -0.5 FROM t92_staff WHERE empnum = 'E1';
-- @s10_5_3_approx_numeric_literals
SELECT 1E0, 1.5e1, 2E-1, -3E+2 FROM t92_staff WHERE empnum = 'E1';
-- @s10_5_1_comments
SELECT empname -- trailing comment
FROM t92_staff /* block */ WHERE empnum = 'E1';
-- @s10_5_1_nested_block_comment
SELECT /* outer /* inner */ still comment */ empname FROM t92_staff WHERE empnum = 'E1';
-- @s10_5_4_qualified_by_schema
SELECT empname FROM public.t92_staff WHERE empnum = 'E1';
-- @s10_whitespace_newlines
SELECT
  empname
FROM
  t92_staff
WHERE
  empnum
  =
  'E1';
-- @s10_multiple_statements_one_line
SELECT 1 FROM t92_staff WHERE empnum = 'E1'; SELECT 2 FROM t92_staff WHERE empnum = 'E2';
-- @s10_long_identifier_63
DROP TABLE IF EXISTS t92_abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghi; CREATE TABLE t92_abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghi (id INTEGER, PRIMARY KEY (id)); SELECT COUNT(*) FROM t92_abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghi; DROP TABLE t92_abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghi;
