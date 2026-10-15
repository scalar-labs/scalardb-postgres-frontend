-- On the frontend's backing PostgreSQL, after schema.sql: ScalarDB creates text columns (varchar for
-- keys and indexed columns) with the database's default collation, which cannot be
-- nondeterministic, so each one is altered, as ScalarDB's own collation integration tests do
CREATE COLLATION IF NOT EXISTS ci (provider = icu, locale = 'und-u-ks-level2', deterministic = false);
DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT a.attrelid::regclass AS rel, a.attname, format_type(a.atttypid, a.atttypmod) AS type
           FROM pg_attribute a JOIN pg_class r ON r.oid = a.attrelid
           JOIN pg_namespace n ON n.oid = r.relnamespace
           WHERE n.nspname = 'sdb' AND r.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped
             AND a.atttypid IN ('text'::regtype, 'varchar'::regtype) LOOP
    EXECUTE format('ALTER TABLE %s ALTER COLUMN %I TYPE %s COLLATE ci', c.rel, c.attname, c.type);
  END LOOP;
END $$;
