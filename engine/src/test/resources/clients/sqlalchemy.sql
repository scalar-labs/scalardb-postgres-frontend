-- SQLAlchemy 2.0.54 + psycopg 3.2.13: every Inspector method (difftest/catalog/inspect_all.py), captured from PostgreSQL 16's statement log, parameters inlined
-- Database audit with difftest/catalog/schema.sql. postgres16: PostgreSQL 16.15's answer (rows, digest; difftest/catalog/replay.py); expect: the frontend's. Relation OIDs are PostgreSQL 16's, listed in oids.
-- source: postgres16
-- oids: public.items=16385 public.items_name_idx=16392 public.items_pkey=16390 sales.orders=16394 sales.orders_pkey=16397
-- [sqlalchemy]
-- postgres16: 1 9680a47b2e16
-- expect: 1  # the frontend's version string
select pg_catalog.version()
-----
-- [sqlalchemy]
-- postgres16: 1 efa1f375d761
-- expect: same
select current_schema()
-----
-- [sqlalchemy]
-- postgres16: 3 6625f3b23143
-- expect: same
SELECT pg_catalog.pg_namespace.nspname 
FROM pg_catalog.pg_namespace 
WHERE (pg_catalog.pg_namespace.nspname NOT LIKE 'pg/_'::VARCHAR || '%' ESCAPE '/') ORDER BY pg_catalog.pg_namespace.nspname
-----
-- [sqlalchemy]
-- postgres16: 1 5f3c4f8580d3
-- expect: same
SELECT pg_catalog.pg_class.relname 
FROM pg_catalog.pg_class JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['r'::VARCHAR, 'p'::VARCHAR]) AND pg_catalog.pg_class.relpersistence != 't'::VARCHAR AND pg_catalog.pg_table_is_visible(pg_catalog.pg_class.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR
-----
-- [sqlalchemy]
-- postgres16: 1 1c168adb00d2
-- expect: same
SELECT pg_catalog.pg_class.relname 
FROM pg_catalog.pg_class JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['r'::VARCHAR, 'p'::VARCHAR]) AND pg_catalog.pg_class.relpersistence != 't'::VARCHAR AND pg_catalog.pg_namespace.nspname = 'sales'::VARCHAR
-----
-- [sqlalchemy]
-- postgres16: 1 5f3c4f8580d3
-- expect: same
SELECT pg_catalog.pg_class.relname 
FROM pg_catalog.pg_class JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relname = 'items'::VARCHAR AND pg_catalog.pg_class.relkind = ANY (ARRAY['r'::VARCHAR, 'p'::VARCHAR, 'f'::VARCHAR, 'v'::VARCHAR, 'm'::VARCHAR]) AND pg_catalog.pg_table_is_visible(pg_catalog.pg_class.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR
-----
-- [sqlalchemy]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT pg_catalog.pg_class.relname 
FROM pg_catalog.pg_class JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['v'::VARCHAR]) AND pg_catalog.pg_class.relpersistence != 't'::VARCHAR AND pg_catalog.pg_table_is_visible(pg_catalog.pg_class.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR
-----
-- [sqlalchemy]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT pg_catalog.pg_class.relname 
FROM pg_catalog.pg_class JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['m'::VARCHAR]) AND pg_catalog.pg_class.relpersistence != 't'::VARCHAR AND pg_catalog.pg_table_is_visible(pg_catalog.pg_class.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR
-----
-- [sqlalchemy]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT pg_catalog.pg_class.relname 
FROM pg_catalog.pg_class JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['S'::VARCHAR]) AND pg_catalog.pg_table_is_visible(pg_catalog.pg_class.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR
-----
-- [sqlalchemy]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT pg_catalog.pg_type.typname AS name, pg_catalog.pg_type_is_visible(pg_catalog.pg_type.oid) AS visible, pg_catalog.pg_namespace.nspname AS schema, lbl_agg.labels AS labels 
FROM pg_catalog.pg_type JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_type.typnamespace LEFT OUTER JOIN (SELECT pg_catalog.pg_enum.enumtypid AS enumtypid, array_agg(CAST(pg_catalog.pg_enum.enumlabel AS TEXT) ORDER BY pg_catalog.pg_enum.enumsortorder) AS labels 
FROM pg_catalog.pg_enum GROUP BY pg_catalog.pg_enum.enumtypid) AS lbl_agg ON pg_catalog.pg_type.oid = lbl_agg.enumtypid 
WHERE pg_catalog.pg_type.typtype = 'e'::VARCHAR AND pg_catalog.pg_type_is_visible(pg_catalog.pg_type.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR ORDER BY pg_catalog.pg_namespace.nspname, pg_catalog.pg_type.typname
-----
-- [sqlalchemy]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT pg_catalog.pg_type.typname AS name, pg_catalog.format_type(pg_catalog.pg_type.typbasetype, pg_catalog.pg_type.typtypmod) AS attype, NOT pg_catalog.pg_type.typnotnull AS nullable, pg_catalog.pg_type.typdefault AS "default", pg_catalog.pg_type_is_visible(pg_catalog.pg_type.oid) AS visible, pg_catalog.pg_namespace.nspname AS schema, domain_constraints.condefs, domain_constraints.connames, pg_catalog.pg_collation.collname 
FROM pg_catalog.pg_type JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_type.typnamespace LEFT OUTER JOIN pg_catalog.pg_collation ON pg_catalog.pg_type.typcollation = pg_catalog.pg_collation.oid LEFT OUTER JOIN (SELECT pg_catalog.pg_constraint.contypid AS contypid, array_agg(pg_catalog.pg_get_constraintdef(pg_catalog.pg_constraint.oid, 't')) AS condefs, array_agg(CAST(pg_catalog.pg_constraint.conname AS TEXT)) AS connames 
FROM pg_catalog.pg_constraint 
WHERE pg_catalog.pg_constraint.contypid != '0'::INTEGER GROUP BY pg_catalog.pg_constraint.contypid) AS domain_constraints ON pg_catalog.pg_type.oid = domain_constraints.contypid 
WHERE pg_catalog.pg_type.typtype = 'd'::VARCHAR AND pg_catalog.pg_type_is_visible(pg_catalog.pg_type.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR ORDER BY pg_catalog.pg_namespace.nspname, pg_catalog.pg_type.typname
-----
-- [sqlalchemy]
-- postgres16: 5 41ebdb16eeb0
-- expect: 5  # ScalarDB's types and the scalardb owner in the test schema
SELECT pg_catalog.pg_attribute.attname AS name, pg_catalog.format_type(pg_catalog.pg_attribute.atttypid, pg_catalog.pg_attribute.atttypmod) AS format_type, (SELECT pg_catalog.pg_get_expr(pg_catalog.pg_attrdef.adbin, pg_catalog.pg_attrdef.adrelid) AS pg_get_expr_1 
FROM pg_catalog.pg_attrdef 
WHERE pg_catalog.pg_attrdef.adrelid = pg_catalog.pg_attribute.attrelid AND pg_catalog.pg_attrdef.adnum = pg_catalog.pg_attribute.attnum AND pg_catalog.pg_attribute.atthasdef) AS "default", pg_catalog.pg_attribute.attnotnull AS not_null, pg_catalog.pg_class.relname AS table_name, pg_catalog.pg_description.description AS comment, pg_catalog.pg_attribute.attgenerated AS generated, (SELECT json_build_object('always'::VARCHAR, pg_catalog.pg_attribute.attidentity = 'a'::VARCHAR, 'start'::VARCHAR, pg_catalog.pg_sequence.seqstart, 'increment'::VARCHAR, pg_catalog.pg_sequence.seqincrement, 'minvalue'::VARCHAR, pg_catalog.pg_sequence.seqmin, 'maxvalue'::VARCHAR, pg_catalog.pg_sequence.seqmax, 'cache'::VARCHAR, pg_catalog.pg_sequence.seqcache, 'cycle'::VARCHAR, pg_catalog.pg_sequence.seqcycle) AS json_build_object_1 
FROM pg_catalog.pg_sequence 
WHERE pg_catalog.pg_attribute.attidentity != ''::VARCHAR AND pg_catalog.pg_sequence.seqrelid = CAST(CAST(pg_catalog.pg_get_serial_sequence(CAST(CAST(pg_catalog.pg_attribute.attrelid AS REGCLASS) AS TEXT), pg_catalog.pg_attribute.attname) AS REGCLASS) AS OID)) AS identity_options, CASE WHEN (pg_catalog.pg_attribute.attcollation != '0'::INTEGER AND (SELECT pg_catalog.pg_type.typcollation 
FROM pg_catalog.pg_type 
WHERE pg_catalog.pg_type.oid = pg_catalog.pg_attribute.atttypid) != pg_catalog.pg_attribute.attcollation) THEN (SELECT pg_catalog.pg_collation.collname 
FROM pg_catalog.pg_collation 
WHERE pg_catalog.pg_collation.oid = pg_catalog.pg_attribute.attcollation) ELSE NULL END AS collation 
FROM pg_catalog.pg_class LEFT OUTER JOIN pg_catalog.pg_attribute ON pg_catalog.pg_class.oid = pg_catalog.pg_attribute.attrelid AND pg_catalog.pg_attribute.attnum > '0'::SMALLINT AND NOT pg_catalog.pg_attribute.attisdropped LEFT OUTER JOIN pg_catalog.pg_description ON pg_catalog.pg_description.objoid = pg_catalog.pg_attribute.attrelid AND pg_catalog.pg_description.objsubid = pg_catalog.pg_attribute.attnum JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['r'::VARCHAR, 'p'::VARCHAR, 'f'::VARCHAR, 'v'::VARCHAR, 'm'::VARCHAR]) AND pg_catalog.pg_table_is_visible(pg_catalog.pg_class.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR AND pg_catalog.pg_class.relname IN ('items'::VARCHAR) ORDER BY pg_catalog.pg_class.relname, pg_catalog.pg_attribute.attnum
-----
-- [sqlalchemy]
-- postgres16: 5 aece35db11a6
-- expect: same
SELECT pg_catalog.pg_type.typname AS name, pg_catalog.format_type(pg_catalog.pg_type.typbasetype, pg_catalog.pg_type.typtypmod) AS attype, NOT pg_catalog.pg_type.typnotnull AS nullable, pg_catalog.pg_type.typdefault AS "default", pg_catalog.pg_type_is_visible(pg_catalog.pg_type.oid) AS visible, pg_catalog.pg_namespace.nspname AS schema, domain_constraints.condefs, domain_constraints.connames, pg_catalog.pg_collation.collname 
FROM pg_catalog.pg_type JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_type.typnamespace LEFT OUTER JOIN pg_catalog.pg_collation ON pg_catalog.pg_type.typcollation = pg_catalog.pg_collation.oid LEFT OUTER JOIN (SELECT pg_catalog.pg_constraint.contypid AS contypid, array_agg(pg_catalog.pg_get_constraintdef(pg_catalog.pg_constraint.oid, 't')) AS condefs, array_agg(CAST(pg_catalog.pg_constraint.conname AS TEXT)) AS connames 
FROM pg_catalog.pg_constraint 
WHERE pg_catalog.pg_constraint.contypid != '0'::INTEGER GROUP BY pg_catalog.pg_constraint.contypid) AS domain_constraints ON pg_catalog.pg_type.oid = domain_constraints.contypid 
WHERE pg_catalog.pg_type.typtype = 'd'::VARCHAR ORDER BY pg_catalog.pg_namespace.nspname, pg_catalog.pg_type.typname
-----
-- [sqlalchemy]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT pg_catalog.pg_type.typname AS name, pg_catalog.pg_type_is_visible(pg_catalog.pg_type.oid) AS visible, pg_catalog.pg_namespace.nspname AS schema, lbl_agg.labels AS labels 
FROM pg_catalog.pg_type JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_type.typnamespace LEFT OUTER JOIN (SELECT pg_catalog.pg_enum.enumtypid AS enumtypid, array_agg(CAST(pg_catalog.pg_enum.enumlabel AS TEXT) ORDER BY pg_catalog.pg_enum.enumsortorder) AS labels 
FROM pg_catalog.pg_enum GROUP BY pg_catalog.pg_enum.enumtypid) AS lbl_agg ON pg_catalog.pg_type.oid = lbl_agg.enumtypid 
WHERE pg_catalog.pg_type.typtype = 'e'::VARCHAR ORDER BY pg_catalog.pg_namespace.nspname, pg_catalog.pg_type.typname
-----
-- [sqlalchemy]
-- postgres16: 1 4e95ff0ee5ef
-- expect: same
SELECT pg_catalog.pg_class.oid, pg_catalog.pg_class.relname 
FROM pg_catalog.pg_class JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['r'::VARCHAR, 'p'::VARCHAR, 'f'::VARCHAR, 'v'::VARCHAR, 'm'::VARCHAR]) AND pg_catalog.pg_table_is_visible(pg_catalog.pg_class.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR AND pg_catalog.pg_class.relname IN ('items'::VARCHAR)
-----
-- [sqlalchemy]
-- postgres16: 1 de2a56147a06
-- expect: same
SELECT attr.conrelid, array_agg(CAST(attr.attname AS TEXT) ORDER BY attr.ord) AS cols, attr.conname, min(attr.description) AS description, min(attr.indnkeyatts) AS indnkeyatts, bool_and(attr.indnullsnotdistinct) AS indnullsnotdistinct 
FROM (SELECT con.conrelid AS conrelid, con.conname AS conname, con.description AS description, con.ord AS ord, con.indnkeyatts AS indnkeyatts, con.indnullsnotdistinct AS indnullsnotdistinct, pg_catalog.pg_attribute.attname AS attname 
FROM pg_catalog.pg_attribute JOIN (SELECT pg_catalog.pg_constraint.conrelid AS conrelid, pg_catalog.pg_constraint.conname AS conname, unnest(pg_catalog.pg_index.indkey) AS attnum, generate_subscripts(pg_catalog.pg_index.indkey, '1'::INTEGER) AS ord, pg_catalog.pg_index.indnkeyatts AS indnkeyatts, pg_catalog.pg_index.indnullsnotdistinct AS indnullsnotdistinct, pg_catalog.pg_description.description AS description 
FROM pg_catalog.pg_constraint JOIN pg_catalog.pg_index ON pg_catalog.pg_constraint.conindid = pg_catalog.pg_index.indexrelid LEFT OUTER JOIN pg_catalog.pg_description ON pg_catalog.pg_description.objoid = pg_catalog.pg_constraint.oid 
WHERE pg_catalog.pg_constraint.contype = 'p'::VARCHAR AND pg_catalog.pg_constraint.conrelid IN ('16385')) AS con ON pg_catalog.pg_attribute.attnum = con.attnum AND pg_catalog.pg_attribute.attrelid = con.conrelid 
WHERE con.conrelid IN ('16385')) AS attr GROUP BY attr.conrelid, attr.conname ORDER BY attr.conrelid, attr.conname
-----
-- [sqlalchemy]
-- postgres16: 1 fb26936c5dbc
-- expect: 1  # the test server's OIDs
SELECT pg_catalog.pg_class.oid, pg_catalog.pg_class.relname 
FROM pg_catalog.pg_class JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['r'::VARCHAR, 'p'::VARCHAR, 'f'::VARCHAR, 'v'::VARCHAR, 'm'::VARCHAR]) AND pg_catalog.pg_namespace.nspname = 'sales'::VARCHAR AND pg_catalog.pg_class.relname IN ('orders'::VARCHAR)
-----
-- [sqlalchemy]
-- postgres16: 1 c34e729b26a5
-- expect: 1  # ScalarDB's types and the scalardb owner in the test schema
SELECT attr.conrelid, array_agg(CAST(attr.attname AS TEXT) ORDER BY attr.ord) AS cols, attr.conname, min(attr.description) AS description, min(attr.indnkeyatts) AS indnkeyatts, bool_and(attr.indnullsnotdistinct) AS indnullsnotdistinct 
FROM (SELECT con.conrelid AS conrelid, con.conname AS conname, con.description AS description, con.ord AS ord, con.indnkeyatts AS indnkeyatts, con.indnullsnotdistinct AS indnullsnotdistinct, pg_catalog.pg_attribute.attname AS attname 
FROM pg_catalog.pg_attribute JOIN (SELECT pg_catalog.pg_constraint.conrelid AS conrelid, pg_catalog.pg_constraint.conname AS conname, unnest(pg_catalog.pg_index.indkey) AS attnum, generate_subscripts(pg_catalog.pg_index.indkey, '1'::INTEGER) AS ord, pg_catalog.pg_index.indnkeyatts AS indnkeyatts, pg_catalog.pg_index.indnullsnotdistinct AS indnullsnotdistinct, pg_catalog.pg_description.description AS description 
FROM pg_catalog.pg_constraint JOIN pg_catalog.pg_index ON pg_catalog.pg_constraint.conindid = pg_catalog.pg_index.indexrelid LEFT OUTER JOIN pg_catalog.pg_description ON pg_catalog.pg_description.objoid = pg_catalog.pg_constraint.oid 
WHERE pg_catalog.pg_constraint.contype = 'p'::VARCHAR AND pg_catalog.pg_constraint.conrelid IN ('16394')) AS con ON pg_catalog.pg_attribute.attnum = con.attnum AND pg_catalog.pg_attribute.attrelid = con.conrelid 
WHERE con.conrelid IN ('16394')) AS attr GROUP BY attr.conrelid, attr.conname ORDER BY attr.conrelid, attr.conname
-----
-- [sqlalchemy]
-- postgres16: 1 d58edc45cc67
-- expect: same
SELECT pg_catalog.pg_class.relname, pg_catalog.pg_constraint.conname, CASE WHEN (pg_catalog.pg_constraint.oid IS NOT NULL) THEN pg_catalog.pg_get_constraintdef(pg_catalog.pg_constraint.oid, 't') END AS anon_1, nsp_ref.nspname, pg_catalog.pg_description.description 
FROM pg_catalog.pg_class LEFT OUTER JOIN pg_catalog.pg_constraint ON pg_catalog.pg_class.oid = pg_catalog.pg_constraint.conrelid AND pg_catalog.pg_constraint.contype = 'f'::VARCHAR LEFT OUTER JOIN pg_catalog.pg_class AS cls_ref ON cls_ref.oid = pg_catalog.pg_constraint.confrelid LEFT OUTER JOIN pg_catalog.pg_namespace AS nsp_ref ON cls_ref.relnamespace = nsp_ref.oid LEFT OUTER JOIN pg_catalog.pg_description ON pg_catalog.pg_description.objoid = pg_catalog.pg_constraint.oid JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['r'::VARCHAR, 'p'::VARCHAR, 'f'::VARCHAR, 'v'::VARCHAR, 'm'::VARCHAR]) AND pg_catalog.pg_namespace.nspname = 'sales'::VARCHAR AND pg_catalog.pg_class.relname IN ('orders'::VARCHAR) ORDER BY pg_catalog.pg_class.relname, pg_catalog.pg_constraint.conname
-----
-- [sqlalchemy]
-- postgres16: 1 c1fa8e8ce7e5
-- expect: 1  # ScalarDB's types and the scalardb owner in the test schema
SELECT pg_catalog.pg_index.indrelid, pg_catalog.pg_class.relname, pg_catalog.pg_index.indisunique, pg_catalog.pg_constraint.conrelid IS NOT NULL AS has_constraint, pg_catalog.pg_index.indoption, pg_catalog.pg_class.reloptions, pg_catalog.pg_am.amname, CASE WHEN (pg_catalog.pg_index.indpred IS NOT NULL) THEN pg_catalog.pg_get_expr(pg_catalog.pg_index.indpred, pg_catalog.pg_index.indrelid) END AS filter_definition, pg_catalog.pg_index.indnkeyatts, pg_catalog.pg_index.indnullsnotdistinct, idx_cols.elements, idx_cols.elements_is_expr, idx_cols.elements_opclass, idx_cols.elements_opdefault 
FROM pg_catalog.pg_index JOIN pg_catalog.pg_class ON pg_catalog.pg_index.indexrelid = pg_catalog.pg_class.oid JOIN pg_catalog.pg_am ON pg_catalog.pg_class.relam = pg_catalog.pg_am.oid LEFT OUTER JOIN (SELECT idx_attr.indexrelid AS indexrelid, min(idx_attr.indrelid) AS min_1, array_agg(idx_attr.element ORDER BY idx_attr.ord) AS elements, array_agg(idx_attr.is_expr ORDER BY idx_attr.ord) AS elements_is_expr, array_agg(idx_attr.opcname ORDER BY idx_attr.ord) AS elements_opclass, array_agg(idx_attr.opcdefault ORDER BY idx_attr.ord) AS elements_opdefault 
FROM (SELECT idx.indexrelid AS indexrelid, idx.indrelid AS indrelid, idx.ord AS ord, CASE WHEN (idx.attnum = '0'::INTEGER) THEN pg_catalog.pg_get_indexdef(idx.indexrelid, idx.ord + '1'::INTEGER, 't') ELSE CAST(pg_catalog.pg_attribute.attname AS TEXT) END AS element, idx.attnum = '0'::INTEGER AS is_expr, pg_catalog.pg_opclass.opcname AS opcname, pg_catalog.pg_opclass.opcdefault AS opcdefault 
FROM (SELECT pg_catalog.pg_index.indexrelid AS indexrelid, pg_catalog.pg_index.indrelid AS indrelid, unnest(pg_catalog.pg_index.indkey) AS attnum, unnest(pg_catalog.pg_index.indclass) AS att_opclass, generate_subscripts(pg_catalog.pg_index.indkey, '1'::INTEGER) AS ord 
FROM pg_catalog.pg_index 
WHERE NOT pg_catalog.pg_index.indisprimary AND pg_catalog.pg_index.indrelid IN ('16385')) AS idx LEFT OUTER JOIN pg_catalog.pg_attribute ON pg_catalog.pg_attribute.attnum = idx.attnum AND pg_catalog.pg_attribute.attrelid = idx.indrelid LEFT OUTER JOIN pg_catalog.pg_opclass ON pg_catalog.pg_opclass.oid = idx.att_opclass 
WHERE idx.indrelid IN ('16385')) AS idx_attr GROUP BY idx_attr.indexrelid) AS idx_cols ON pg_catalog.pg_index.indexrelid = idx_cols.indexrelid LEFT OUTER JOIN pg_catalog.pg_constraint ON pg_catalog.pg_index.indrelid = pg_catalog.pg_constraint.conrelid AND pg_catalog.pg_index.indexrelid = pg_catalog.pg_constraint.conindid AND pg_catalog.pg_constraint.contype = ANY (ARRAY['p'::VARCHAR, 'u'::VARCHAR, 'x'::VARCHAR]) 
WHERE pg_catalog.pg_index.indrelid IN ('16385') AND NOT pg_catalog.pg_index.indisprimary ORDER BY pg_catalog.pg_index.indrelid, pg_catalog.pg_class.relname
-----
-- [sqlalchemy]
-- postgres16: 1 6f704eb3b2c8
-- expect: same
SELECT pg_catalog.pg_class.relname, pg_catalog.pg_constraint.conname, CASE WHEN (pg_catalog.pg_constraint.oid IS NOT NULL) THEN pg_catalog.pg_get_constraintdef(pg_catalog.pg_constraint.oid, 't') END AS anon_1, pg_catalog.pg_description.description 
FROM pg_catalog.pg_class LEFT OUTER JOIN pg_catalog.pg_constraint ON pg_catalog.pg_class.oid = pg_catalog.pg_constraint.conrelid AND pg_catalog.pg_constraint.contype = 'c'::VARCHAR LEFT OUTER JOIN pg_catalog.pg_description ON pg_catalog.pg_description.objoid = pg_catalog.pg_constraint.oid JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['r'::VARCHAR, 'p'::VARCHAR, 'f'::VARCHAR, 'v'::VARCHAR, 'm'::VARCHAR]) AND pg_catalog.pg_table_is_visible(pg_catalog.pg_class.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR AND pg_catalog.pg_class.relname IN ('items'::VARCHAR) ORDER BY pg_catalog.pg_class.relname, pg_catalog.pg_constraint.conname
-----
-- [sqlalchemy]
-- postgres16: 1 30a0f15c918c
-- expect: 1  # the frontend describes a table by its keys
SELECT pg_catalog.pg_class.relname, pg_catalog.pg_description.description 
FROM pg_catalog.pg_class LEFT OUTER JOIN pg_catalog.pg_description ON pg_catalog.pg_class.oid = pg_catalog.pg_description.objoid AND pg_catalog.pg_description.objsubid = '0'::INTEGER AND pg_catalog.pg_description.classoid = CAST('pg_class' AS REGCLASS) JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['r'::VARCHAR, 'p'::VARCHAR, 'f'::VARCHAR, 'v'::VARCHAR, 'm'::VARCHAR]) AND pg_catalog.pg_table_is_visible(pg_catalog.pg_class.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR AND pg_catalog.pg_class.relname IN ('items'::VARCHAR)
-----
-- [sqlalchemy]
-- postgres16: 5 41ebdb16eeb0
-- expect: 5  # ScalarDB's types and the scalardb owner in the test schema
SELECT pg_catalog.pg_attribute.attname AS name, pg_catalog.format_type(pg_catalog.pg_attribute.atttypid, pg_catalog.pg_attribute.atttypmod) AS format_type, (SELECT pg_catalog.pg_get_expr(pg_catalog.pg_attrdef.adbin, pg_catalog.pg_attrdef.adrelid) AS pg_get_expr_1 
FROM pg_catalog.pg_attrdef 
WHERE pg_catalog.pg_attrdef.adrelid = pg_catalog.pg_attribute.attrelid AND pg_catalog.pg_attrdef.adnum = pg_catalog.pg_attribute.attnum AND pg_catalog.pg_attribute.atthasdef) AS "default", pg_catalog.pg_attribute.attnotnull AS not_null, pg_catalog.pg_class.relname AS table_name, pg_catalog.pg_description.description AS comment, pg_catalog.pg_attribute.attgenerated AS generated, (SELECT json_build_object('always'::VARCHAR, pg_catalog.pg_attribute.attidentity = 'a'::VARCHAR, 'start'::VARCHAR, pg_catalog.pg_sequence.seqstart, 'increment'::VARCHAR, pg_catalog.pg_sequence.seqincrement, 'minvalue'::VARCHAR, pg_catalog.pg_sequence.seqmin, 'maxvalue'::VARCHAR, pg_catalog.pg_sequence.seqmax, 'cache'::VARCHAR, pg_catalog.pg_sequence.seqcache, 'cycle'::VARCHAR, pg_catalog.pg_sequence.seqcycle) AS json_build_object_1 
FROM pg_catalog.pg_sequence 
WHERE pg_catalog.pg_attribute.attidentity != ''::VARCHAR AND pg_catalog.pg_sequence.seqrelid = CAST(CAST(pg_catalog.pg_get_serial_sequence(CAST(CAST(pg_catalog.pg_attribute.attrelid AS REGCLASS) AS TEXT), pg_catalog.pg_attribute.attname) AS REGCLASS) AS OID)) AS identity_options, CASE WHEN (pg_catalog.pg_attribute.attcollation != '0'::INTEGER AND (SELECT pg_catalog.pg_type.typcollation 
FROM pg_catalog.pg_type 
WHERE pg_catalog.pg_type.oid = pg_catalog.pg_attribute.atttypid) != pg_catalog.pg_attribute.attcollation) THEN (SELECT pg_catalog.pg_collation.collname 
FROM pg_catalog.pg_collation 
WHERE pg_catalog.pg_collation.oid = pg_catalog.pg_attribute.attcollation) ELSE NULL END AS collation 
FROM pg_catalog.pg_class LEFT OUTER JOIN pg_catalog.pg_attribute ON pg_catalog.pg_class.oid = pg_catalog.pg_attribute.attrelid AND pg_catalog.pg_attribute.attnum > '0'::SMALLINT AND NOT pg_catalog.pg_attribute.attisdropped LEFT OUTER JOIN pg_catalog.pg_description ON pg_catalog.pg_description.objoid = pg_catalog.pg_attribute.attrelid AND pg_catalog.pg_description.objsubid = pg_catalog.pg_attribute.attnum JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['r'::VARCHAR, 'p'::VARCHAR, 'f'::VARCHAR]) AND pg_catalog.pg_class.relpersistence != 't'::VARCHAR AND pg_catalog.pg_table_is_visible(pg_catalog.pg_class.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR ORDER BY pg_catalog.pg_class.relname, pg_catalog.pg_attribute.attnum
-----
-- [sqlalchemy]
-- postgres16: 1 4e95ff0ee5ef
-- expect: same
SELECT pg_catalog.pg_class.oid, pg_catalog.pg_class.relname 
FROM pg_catalog.pg_class JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace 
WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['r'::VARCHAR, 'p'::VARCHAR, 'f'::VARCHAR]) AND pg_catalog.pg_class.relpersistence != 't'::VARCHAR AND pg_catalog.pg_table_is_visible(pg_catalog.pg_class.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog'::VARCHAR
-----
