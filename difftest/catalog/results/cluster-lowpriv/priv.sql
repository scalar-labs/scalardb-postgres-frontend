\db+
\dp items
\dp sales.orders
\du
SELECT current_user, session_user;
SELECT has_table_privilege('items', 'SELECT') AS items_sel, has_table_privilege('items', 'INSERT') AS items_ins, has_table_privilege('sales.orders', 'SELECT') AS orders_sel, has_schema_privilege('sales', 'USAGE') AS sales_usage, has_database_privilege('audit', 'CONNECT') AS db_connect;
SELECT has_table_privilege('carol', 'items', 'SELECT') AS c_items, has_table_privilege('carol', 'sales.orders', 'SELECT') AS c_orders;
SELECT rolname, rolsuper, rolcanlogin FROM pg_roles WHERE rolname IN ('carol', 'postgres', 'admin', 'alice', 'bob', 'scalardb', 'scalardb_postgres_frontend') ORDER BY 1;
SELECT relname, relacl FROM pg_class WHERE relname IN ('items', 'orders') ORDER BY 1;
SELECT table_schema, table_name, privilege_type, grantee FROM information_schema.table_privileges WHERE table_name IN ('items', 'orders') ORDER BY 1, 2, 4, 3;
SELECT grantee, table_name, privilege_type FROM information_schema.role_table_grants WHERE table_name IN ('items', 'orders') ORDER BY 1, 2, 3;
SELECT nspname FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' ORDER BY 1;
SELECT datname FROM pg_database ORDER BY 1;
