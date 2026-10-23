-- pgAdmin 4 9.18: connecting, the dashboard's tabs, the object tree down to a table's columns, constraints and indexes, the table's and a column's Properties, SQL, Statistics, Dependencies and Dependents tabs, and View Data; captured from PostgreSQL 16's statement log, parameters inlined
-- Database audit with difftest/catalog/schema.sql. postgres16: PostgreSQL 16.15's answer (rows, digest; difftest/catalog/replay.py); expect: the frontend's. OIDs are PostgreSQL 16's, listed in oids: pgAdmin writes them bare, and names schemas and the database by OID too.
-- source: postgres16
-- oids: @audit=16384 public=2200 public.items=16385 public.items_name_idx=16392 public.items_pkey=16390 sales=16393 sales.orders=16394 sales.orders_pkey=16397
-- [pgadmin]
-- postgres16: 0
-- expect: ERROR 02000  # the batch ends in SET: no result set for JDBC's executeQuery, as from PostgreSQL
SET DateStyle=ISO; SET client_min_messages=notice; SELECT set_config('bytea_output','hex',false) FROM pg_show_all_settings() WHERE name = 'bytea_output'; SET client_encoding='utf-8';
-----
-- [pgadmin]
-- postgres16: 1 e3b3fa75b7ff
-- expect: same
SELECT
    db.oid as did, db.datname, db.datallowconn,
    pg_encoding_to_char(db.encoding) AS serverencoding,
    has_database_privilege(db.oid, 'CREATE') as cancreate,
    datistemplate
FROM
    pg_catalog.pg_database db
WHERE db.datname = current_database()
-----
-- [pgadmin]
-- postgres16: 1 9685650ea374
-- expect: same
SELECT
             gss_authenticated, encrypted
        FROM
            pg_catalog.pg_stat_gssapi
        WHERE pid = pg_backend_pid()
-----
-- [pgadmin]
-- postgres16: 1 4e057eff3194
-- expect: 0  # the connecting user is not a role without ScalarDB Cluster: every session is the scalardb role
SELECT
            roles.oid as id, roles.rolname as name,
            roles.rolsuper as is_superuser,
            CASE WHEN roles.rolsuper THEN true ELSE roles.rolcreaterole END as
            can_create_role,
            CASE WHEN roles.rolsuper THEN true
            ELSE roles.rolcreatedb END as can_create_db,
            CASE WHEN 'pg_signal_backend'=ANY(ARRAY(WITH RECURSIVE cte AS (
            SELECT pg_roles.oid,pg_roles.rolname FROM pg_catalog.pg_roles
                WHERE pg_roles.oid = roles.oid
            UNION ALL
            SELECT m.roleid,pgr.rolname FROM cte cte_1
                JOIN pg_catalog.pg_auth_members m ON m.member = cte_1.oid
                JOIN pg_catalog.pg_roles pgr ON pgr.oid = m.roleid)
            SELECT rolname  FROM cte)) THEN True
            ELSE False END as can_signal_backend
        FROM
            pg_catalog.pg_roles as roles
        WHERE
            rolname = current_user
-----
-- [pgadmin]
-- postgres16: 1 9685650ea374
-- expect: 0  # the connecting user is not a role without ScalarDB Cluster: every session is the scalardb role
SELECT CASE WHEN usesuper
       THEN pg_catalog.pg_is_in_recovery()
       ELSE FALSE
       END as inrecovery,
       CASE WHEN usesuper AND pg_catalog.pg_is_in_recovery()
       THEN pg_is_wal_replay_paused()
       ELSE FALSE
       END as isreplaypaused
FROM pg_catalog.pg_user WHERE usename=current_user
-----
-- [pgadmin]
-- postgres16: 1 b582d3167c70
-- expect: same
SELECT CASE
    WHEN (SELECT count(extname) FROM pg_catalog.pg_extension WHERE extname='bdr') > 0
    THEN 'pgd'
    WHEN (SELECT COUNT(*) FROM pg_catalog.pg_replication_slots) > 0
    THEN 'log'
    ELSE NULL
END as type;
-----
-- [pgadmin]
-- postgres16: 5 92464dc49e7f
-- expect: 5  # this server's one session; no statistics are gathered, so the counts are null
/*pga4dash*/
SELECT 'session_stats' AS chart_name, pg_catalog.row_to_json(t) AS chart_data
FROM (SELECT
   (SELECT count(*) FROM pg_catalog.pg_stat_activity) AS "Total",
   (SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE state = 'active')  AS "Active",
   (SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE state = 'idle')  AS "Idle"
) t
UNION ALL
SELECT 'tps_stats' AS chart_name, pg_catalog.row_to_json(t) AS chart_data
FROM (SELECT
   (SELECT sum(xact_commit) + sum(xact_rollback) FROM pg_catalog.pg_stat_database) AS "Transactions",
   (SELECT sum(xact_commit) FROM pg_catalog.pg_stat_database) AS "Commits",
   (SELECT sum(xact_rollback) FROM pg_catalog.pg_stat_database) AS "Rollbacks"
) t
UNION ALL
SELECT 'ti_stats' AS chart_name, pg_catalog.row_to_json(t) AS chart_data
FROM (SELECT
   (SELECT sum(tup_inserted) FROM pg_catalog.pg_stat_database) AS "Inserts",
   (SELECT sum(tup_updated) FROM pg_catalog.pg_stat_database) AS "Updates",
   (SELECT sum(tup_deleted) FROM pg_catalog.pg_stat_database) AS "Deletes"
) t
UNION ALL
SELECT 'to_stats' AS chart_name, pg_catalog.row_to_json(t) AS chart_data
FROM (SELECT
   (SELECT sum(tup_fetched) FROM pg_catalog.pg_stat_database) AS "Fetched",
   (SELECT sum(tup_returned) FROM pg_catalog.pg_stat_database) AS "Returned"
) t
UNION ALL
SELECT 'bio_stats' AS chart_name, pg_catalog.row_to_json(t) AS chart_data
FROM (SELECT
   (SELECT sum(blks_read) FROM pg_catalog.pg_stat_database) AS "Reads",
   (SELECT sum(blks_hit) FROM pg_catalog.pg_stat_database) AS "Hits"
) t
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT
    has_table_privilege(
      'pgagent.pga_job', 'INSERT, SELECT, UPDATE'
    ) has_priviledge
WHERE EXISTS(
    SELECT has_schema_privilege('pgagent', 'USAGE')
    WHERE EXISTS(
        SELECT cl.oid FROM pg_catalog.pg_class cl
        LEFT JOIN pg_catalog.pg_namespace ns ON ns.oid=relnamespace
        WHERE relname='pga_job' AND nspname='pgagent'
    )
)
-----
-- [pgadmin]
-- postgres16: 8 673860fa383b
-- expect: 1  # this server's sessions only: no background processes
/*pga4dash*/
SELECT
    pid,
    datname,
    usename,
    application_name,
    client_addr,
    pg_catalog.to_char(backend_start, 'YYYY-MM-DD HH24:MI:SS TZ') AS backend_start,
    state,
    wait_event_type || ': ' || wait_event AS wait_event,
    array_to_string(pg_catalog.pg_blocking_pids(pid), ', ') AS blocking_pids,
    query,
    pg_catalog.to_char(state_change, 'YYYY-MM-DD HH24:MI:SS TZ') AS state_change,
    pg_catalog.to_char(query_start, 'YYYY-MM-DD HH24:MI:SS TZ') AS query_start,
    pg_catalog.to_char(xact_start, 'YYYY-MM-DD HH24:MI:SS TZ') AS xact_start,
    backend_type,
    CASE WHEN state = 'active' THEN ROUND((extract(epoch from now() - query_start) / 60)::numeric, 2) ELSE 0 END AS active_since
FROM
    pg_catalog.pg_stat_activity
ORDER BY pid
-----
-- [pgadmin]
-- postgres16: 5 a9065334923b
-- expect: 0  # no PostgreSQL locks: ScalarDB's transactions are optimistic
/*pga4dash*/
SELECT
    pid,
    locktype,
    datname,
    relation::regclass,
    page,
    tuple,
    virtualxid
    transactionid,
    classid::regclass,
    objid,
    objsubid,
    virtualtransaction,
    mode,
    granted,
    fastpath
FROM
    pg_catalog.pg_locks l
    LEFT OUTER JOIN pg_catalog.pg_database d ON (l.database = d.oid)
ORDER BY
    pid, locktype
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
/*pga4dash*/
SELECT
    gid,
    database,
    owner,
    transaction,
    pg_catalog.to_char(prepared, 'YYYY-MM-DD HH24:MI:SS TZ') AS prepared
FROM
    pg_catalog.pg_prepared_xacts
ORDER BY
    gid, database, owner
-----
-- [pgadmin]
-- postgres16: 364 ab5a1b08fcd2
-- expect: 24  # the frontend's settings
/*pga4dash*/
SELECT
    name,
    category,
    setting,
    unit,
    short_desc
FROM
    pg_catalog.pg_show_all_settings()
ORDER BY
    category
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT 1 FROM pg_catalog.pg_extension WHERE extname = 'system_stats';
-----
-- [pgadmin]
-- postgres16: 4 d93bfab5a88a
-- expect: 2  # databases are ScalarDB namespaces
SELECT
    db.oid as did, db.datname as name, ta.spcname as spcname, db.datallowconn,
    db.datistemplate AS is_template,
    pg_catalog.has_database_privilege(db.oid, 'CREATE') as cancreate, datdba as owner,
    descr.description
FROM
    pg_catalog.pg_database db
    LEFT OUTER JOIN pg_catalog.pg_tablespace ta ON db.dattablespace = ta.oid
    LEFT OUTER JOIN pg_catalog.pg_shdescription descr ON (
        db.oid=descr.objoid AND descr.classoid='pg_database'::regclass
    )
WHERE         db.oid > 16383::OID OR db.datname IN ('postgres', 'edb')

ORDER BY datname;
-----
-- [pgadmin]
-- postgres16: 1 5feceb66ffc8
-- expect: same
SELECT COUNT(*) FROM pg_catalog.pg_extension WHERE extname IN (
                'edb_job_scheduler', 'dbms_scheduler')
-----
-- [pgadmin]
-- postgres16: 5 ad1673412693
-- expect: 5  # this server's one session; no statistics are gathered, so the counts are null
/*pga4dash*/
SELECT 'session_stats' AS chart_name, pg_catalog.row_to_json(t) AS chart_data
FROM (SELECT
   (SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384)) AS "Total",
   (SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE state = 'active' AND datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384))  AS "Active",
   (SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE state = 'idle' AND datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384))  AS "Idle"
) t
UNION ALL
SELECT 'tps_stats' AS chart_name, pg_catalog.row_to_json(t) AS chart_data
FROM (SELECT
   (SELECT sum(xact_commit) + sum(xact_rollback) FROM pg_catalog.pg_stat_database WHERE datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384)) AS "Transactions",
   (SELECT sum(xact_commit) FROM pg_catalog.pg_stat_database WHERE datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384)) AS "Commits",
   (SELECT sum(xact_rollback) FROM pg_catalog.pg_stat_database WHERE datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384)) AS "Rollbacks"
) t
UNION ALL
SELECT 'ti_stats' AS chart_name, pg_catalog.row_to_json(t) AS chart_data
FROM (SELECT
   (SELECT sum(tup_inserted) FROM pg_catalog.pg_stat_database WHERE datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384)) AS "Inserts",
   (SELECT sum(tup_updated) FROM pg_catalog.pg_stat_database WHERE datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384)) AS "Updates",
   (SELECT sum(tup_deleted) FROM pg_catalog.pg_stat_database WHERE datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384)) AS "Deletes"
) t
UNION ALL
SELECT 'to_stats' AS chart_name, pg_catalog.row_to_json(t) AS chart_data
FROM (SELECT
   (SELECT sum(tup_fetched) FROM pg_catalog.pg_stat_database WHERE datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384)) AS "Fetched",
   (SELECT sum(tup_returned) FROM pg_catalog.pg_stat_database WHERE datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384)) AS "Returned"
) t
UNION ALL
SELECT 'bio_stats' AS chart_name, pg_catalog.row_to_json(t) AS chart_data
FROM (SELECT
   (SELECT sum(blks_read) FROM pg_catalog.pg_stat_database WHERE datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384)) AS "Reads",
   (SELECT sum(blks_hit) FROM pg_catalog.pg_stat_database WHERE datname = (SELECT datname FROM pg_catalog.pg_database WHERE oid = 16384)) AS "Hits"
) t
-----
-- [pgadmin]
-- postgres16: 2 c9b48fde28c0
-- expect: 2  # the test server's OIDs; public has no description here
SELECT
    nsp.oid,
    nsp.nspname as name,
    pg_catalog.has_schema_privilege(nsp.oid, 'CREATE') as can_create,
    pg_catalog.has_schema_privilege(nsp.oid, 'USAGE') as has_usage,
    des.description
FROM
    pg_catalog.pg_namespace nsp
    LEFT OUTER JOIN pg_catalog.pg_description des ON
        (des.objoid=nsp.oid AND des.classoid='pg_namespace'::regclass)
WHERE
             nspname NOT LIKE E'pg\\_%' AND
            NOT (
(nsp.nspname = 'pg_catalog' AND EXISTS
        (SELECT 1 FROM pg_catalog.pg_class WHERE relname = 'pg_class' AND
            relnamespace = nsp.oid LIMIT 1)) OR
    (nsp.nspname = 'pgagent' AND EXISTS
        (SELECT 1 FROM pg_catalog.pg_class WHERE relname = 'pga_job' AND
            relnamespace = nsp.oid LIMIT 1)) OR
    (nsp.nspname = 'information_schema' AND EXISTS
        (SELECT 1 FROM pg_catalog.pg_class WHERE relname = 'tables' AND
            relnamespace = nsp.oid LIMIT 1))
    )

    
ORDER BY nspname;
-----
-- [pgadmin]
-- postgres16: 1 25c41ae286d6
-- expect: same
SELECT
    nsp.nspname as schema_name,
    (nsp.nspname = 'pg_catalog' AND EXISTS
        (SELECT 1 FROM pg_catalog.pg_class WHERE relname = 'pg_class' AND
            relnamespace = nsp.oid LIMIT 1)) OR
    (nsp.nspname = 'pgagent' AND EXISTS
        (SELECT 1 FROM pg_catalog.pg_class WHERE relname = 'pga_job' AND
            relnamespace = nsp.oid LIMIT 1)) OR
    (nsp.nspname = 'information_schema' AND EXISTS
        (SELECT 1 FROM pg_catalog.pg_class WHERE relname = 'tables' AND
            relnamespace = nsp.oid LIMIT 1)) AS is_catalog,
    CASE
    WHEN nsp.nspname = ANY('{information_schema}')
        THEN false
    ELSE true END AS db_support
FROM
    pg_catalog.pg_namespace nsp
WHERE
    nsp.oid = 2200::OID;
-----
-- [pgadmin]
-- postgres16: 1 676c44545909
-- expect: 1  # the frontend describes each table's ScalarDB key in pg_description
SELECT rel.oid, rel.relname AS name,
    (SELECT count(*) FROM pg_catalog.pg_trigger WHERE tgrelid=rel.oid AND tgisinternal = FALSE) AS triggercount,
    (SELECT count(*) FROM pg_catalog.pg_trigger WHERE tgrelid=rel.oid AND tgisinternal = FALSE AND tgenabled = 'O') AS has_enable_triggers,
    (CASE WHEN rel.relkind = 'p' THEN true ELSE false END) AS is_partitioned,
    (SELECT count(1) FROM pg_catalog.pg_inherits WHERE inhrelid=rel.oid LIMIT 1) as is_inherits,
    (SELECT count(1) FROM pg_catalog.pg_inherits WHERE inhparent=rel.oid LIMIT 1) as is_inherited,
    des.description
FROM pg_catalog.pg_class rel
    LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=rel.oid AND des.objsubid=0 AND des.classoid='pg_class'::regclass)
    WHERE rel.relkind IN ('r','s','t','p') AND rel.relnamespace = 2200::oid
    AND NOT rel.relispartition
        ORDER BY rel.relname;
-----
-- [pgadmin]
-- postgres16: 1 252f10c83610
-- expect: same
SELECT
    CASE WHEN c.relkind = 'p' THEN True ELSE False END As ptable
FROM
    pg_catalog.pg_class c
WHERE
    c.oid = 16385::oid
-----
-- [pgadmin]
-- postgres16: 1 3e1212c10e4a
-- expect: same
SELECT nsp.nspname AS schema ,rel.relname AS table
FROM pg_catalog.pg_class rel
    JOIN pg_catalog.pg_namespace nsp
    ON rel.relnamespace = nsp.oid::oid
    WHERE rel.oid = 16385::oid
-----
-- [pgadmin]
-- postgres16: 1 38d43a88155a
-- expect: 1  # the test server's OIDs
SELECT DISTINCT ON(cls.relname) cls.oid, cls.relname as name,
(SELECT (CASE WHEN count(i.inhrelid) > 0 THEN true ELSE false END) FROM pg_catalog.pg_inherits i WHERE i.inhrelid = cls.oid) as is_inherited,
CASE WHEN contype IN ('p', 'u', 'x') THEN desp.description ELSE des.description END AS description
FROM pg_catalog.pg_index idx
    JOIN pg_catalog.pg_class cls ON cls.oid=indexrelid
    JOIN pg_catalog.pg_class tab ON tab.oid=indrelid
    LEFT OUTER JOIN pg_catalog.pg_tablespace ta on ta.oid=cls.reltablespace
    JOIN pg_catalog.pg_namespace n ON n.oid=tab.relnamespace
    JOIN pg_catalog.pg_am am ON am.oid=cls.relam
    LEFT JOIN pg_catalog.pg_depend dep ON (dep.classid = cls.tableoid AND dep.objid = cls.oid AND dep.refobjsubid = '0' AND dep.refclassid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='pg_constraint') AND dep.deptype='i')
    LEFT OUTER JOIN pg_catalog.pg_constraint con ON (con.tableoid = dep.refclassid AND con.oid = dep.refobjid)
    LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=cls.oid AND des.classoid='pg_class'::regclass)
    LEFT OUTER JOIN pg_catalog.pg_description desp ON (desp.objoid=con.oid AND desp.objsubid = 0 AND desp.classoid='pg_constraint'::regclass)
WHERE indrelid = 16385::OID
    AND conname is NULL
    ORDER BY cls.relname
-----
-- [pgadmin]
-- postgres16: 1 3e1212c10e4a
-- expect: same
SELECT nsp.nspname AS schema,
    rel.relname AS table
FROM
    pg_catalog.pg_class rel
JOIN pg_catalog.pg_namespace nsp
ON rel.relnamespace = nsp.oid::oid
WHERE rel.oid = 16385::oid
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT ct.oid,
    conname as name,
    NOT convalidated as convalidated,
    description as comment
FROM pg_catalog.pg_constraint ct
LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=ct.oid AND des.classoid='pg_constraint'::regclass)
WHERE contype='f' AND
    conrelid = 16385::oid
ORDER BY conname
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT c.oid, conname as name,
    NOT convalidated as convalidated, conislocal, description as comment
    FROM pg_catalog.pg_constraint c
LEFT OUTER JOIN
    pg_catalog.pg_description des ON (des.objoid=c.oid AND
                           des.classoid='pg_constraint'::regclass)
WHERE contype = 'c'
    AND conrelid = 16385::oid
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT conindid as oid,
    conname as name,
    NOT convalidated as convalidated,
    desp.description AS comment
FROM pg_catalog.pg_constraint ct
LEFT OUTER JOIN pg_catalog.pg_description desp ON (desp.objoid=ct.oid AND desp.objsubid = 0 AND desp.classoid='pg_constraint'::regclass)
WHERE contype='x' AND
    conrelid = 16385::oid
ORDER BY conname
-----
-- [pgadmin]
-- postgres16: 1 312a05fd5190
-- expect: 1  # the test server's OIDs
SELECT cls.oid, cls.relname as name,
    CASE contype
        WHEN 'p' THEN desp.description
        WHEN 'u' THEN desp.description
        WHEN 'x' THEN desp.description
        ELSE des.description
    END AS comment
FROM pg_catalog.pg_index idx
JOIN pg_catalog.pg_class cls ON cls.oid=indexrelid
LEFT JOIN pg_catalog.pg_depend dep ON (dep.classid = cls.tableoid AND
                            dep.objid = cls.oid AND
                            dep.refobjsubid = '0' AND
                            dep.refclassid=(SELECT oid
                                            FROM pg_catalog.pg_class
                                            WHERE relname='pg_constraint') AND
                            dep.deptype='i')
LEFT OUTER JOIN pg_catalog.pg_constraint con ON (con.tableoid = dep.refclassid AND
                                      con.oid = dep.refobjid)
LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=cls.oid AND des.classoid='pg_class'::regclass)
LEFT OUTER JOIN pg_catalog.pg_description desp ON (desp.objoid=con.oid AND desp.objsubid = 0 AND desp.classoid='pg_constraint'::regclass)
WHERE indrelid = 16385::oid
AND contype='p'
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT cls.oid, cls.relname as name,
    CASE contype
        WHEN 'p' THEN desp.description
        WHEN 'u' THEN desp.description
        WHEN 'x' THEN desp.description
        ELSE des.description
    END AS comment
FROM pg_catalog.pg_index idx
JOIN pg_catalog.pg_class cls ON cls.oid=indexrelid
LEFT JOIN pg_catalog.pg_depend dep ON (dep.classid = cls.tableoid AND
                            dep.objid = cls.oid AND
                            dep.refobjsubid = '0' AND
                            dep.refclassid=(SELECT oid
                                            FROM pg_catalog.pg_class
                                            WHERE relname='pg_constraint') AND
                            dep.deptype='i')
LEFT OUTER JOIN pg_catalog.pg_constraint con ON (con.tableoid = dep.refclassid AND
                                      con.oid = dep.refobjid)
LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=cls.oid AND des.classoid='pg_class'::regclass)
LEFT OUTER JOIN pg_catalog.pg_description desp ON (desp.objoid=con.oid AND desp.objsubid = 0 AND desp.classoid='pg_constraint'::regclass)
WHERE indrelid = 16385::oid
AND contype='u'
-----
-- [pgadmin]
-- postgres16: 5 2f128b841694
-- expect: 5  # ScalarDB's types in the test schema
SELECT DISTINCT att.attname as name, att.attnum as OID, pg_catalog.format_type(ty.oid,NULL) AS datatype,
pg_catalog.format_type(ty.oid,att.atttypmod) AS displaytypname,
att.attnotnull as not_null,
CASE WHEN att.atthasdef OR att.attidentity != '' OR ty.typdefault IS NOT NULL THEN True
ELSE False END as has_default_val, des.description, seq.seqtypid
FROM pg_catalog.pg_attribute att
    JOIN pg_catalog.pg_type ty ON ty.oid=atttypid
    JOIN pg_catalog.pg_namespace tn ON tn.oid=ty.typnamespace
    JOIN pg_catalog.pg_class cl ON cl.oid=att.attrelid
    JOIN pg_catalog.pg_namespace na ON na.oid=cl.relnamespace
    LEFT OUTER JOIN pg_catalog.pg_type et ON et.oid=ty.typelem
    LEFT OUTER JOIN pg_catalog.pg_attrdef def ON adrelid=att.attrelid AND adnum=att.attnum
    LEFT OUTER JOIN (pg_catalog.pg_depend JOIN pg_catalog.pg_class cs ON classid='pg_class'::regclass AND objid=cs.oid AND cs.relkind='S') ON refobjid=att.attrelid AND refobjsubid=att.attnum
    LEFT OUTER JOIN pg_catalog.pg_namespace ns ON ns.oid=cs.relnamespace
    LEFT OUTER JOIN pg_catalog.pg_index pi ON pi.indrelid=att.attrelid AND indisprimary
    LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=att.attrelid AND des.objsubid=att.attnum AND des.classoid='pg_class'::regclass)
    LEFT OUTER JOIN pg_catalog.pg_sequence seq ON cs.oid=seq.seqrelid
WHERE

    att.attrelid = 16385::oid
    AND att.attnum > 0
    AND att.attisdropped IS FALSE
ORDER BY att.attnum
-----
-- [pgadmin]
-- postgres16: 5 9ccfb0be63a8
-- expect: 5  # ScalarDB's types in the test schema
WITH INH_TABLES AS
    (SELECT
     at.attname AS name, ph.inhparent AS inheritedid, ph.inhseqno,
     pg_catalog.concat(nmsp_parent.nspname, '.',parent.relname ) AS inheritedfrom
    FROM
        pg_catalog.pg_attribute at
    JOIN
        pg_catalog.pg_inherits ph ON ph.inhparent = at.attrelid AND ph.inhrelid = 33896::oid
    JOIN
        pg_catalog.pg_class parent ON ph.inhparent  = parent.oid
    JOIN
        pg_catalog.pg_namespace nmsp_parent ON nmsp_parent.oid  = parent.relnamespace
    GROUP BY at.attname, ph.inhparent, ph.inhseqno, inheritedfrom
    ORDER BY at.attname, ph.inhparent, ph.inhseqno, inheritedfrom
    )
SELECT DISTINCT ON (att.attnum) att.attname as name, att.atttypid, att.attlen, att.attnum, att.attndims,
att.atttypmod, att.attacl, att.attnotnull, att.attoptions, att.attfdwoptions, att.attstattarget,
att.attstorage, att.attidentity,
pg_catalog.pg_get_expr(def.adbin, def.adrelid) AS defval,
pg_catalog.format_type(ty.oid,NULL) AS typname,
        pg_catalog.format_type(ty.oid,att.atttypmod) AS displaytypname,
pg_catalog.format_type(ty.oid,att.atttypmod) AS cltype,
inh.inheritedfrom,
inh.inheritedid,
        CASE WHEN ty.typelem > 0 THEN ty.typelem ELSE ty.oid END as elemoid,
(SELECT nspname FROM pg_catalog.pg_namespace WHERE oid = ty.typnamespace) as typnspname,
        ty.typstorage AS defaultstorage,
description, pi.indkey,
(SELECT count(1) FROM pg_catalog.pg_type t2 WHERE t2.typname=ty.typname) > 1 AS isdup,
CASE WHEN length(coll.collname::text) > 0 AND length(nspc.nspname::text) > 0  THEN
  pg_catalog.concat(pg_catalog.quote_ident(nspc.nspname),'.',pg_catalog.quote_ident(coll.collname))
ELSE '' END AS collspcname,
EXISTS(SELECT 1 FROM pg_catalog.pg_constraint WHERE conrelid=att.attrelid AND contype='f' AND att.attnum=ANY(conkey)) As is_fk,
(SELECT pg_catalog.array_agg(provider || '=' || label) FROM pg_catalog.pg_seclabels sl1 WHERE sl1.objoid=att.attrelid AND sl1.objsubid=att.attnum) AS seclabels,
(CASE WHEN (att.attnum < 1) THEN true ElSE false END) AS is_sys_column,
(CASE WHEN (att.attidentity in ('a', 'd')) THEN 'i' WHEN (att.attgenerated in ('s')) THEN 'g' ELSE 'n' END) AS colconstype,
(CASE WHEN (att.attgenerated in ('s')) THEN pg_catalog.pg_get_expr(def.adbin, def.adrelid) END) AS genexpr, tab.relname as relname,
(CASE WHEN tab.relkind = 'v' THEN true ELSE false END) AS is_view_only,
(CASE WHEN att.attcompression = 'p' THEN 'pglz' WHEN att.attcompression = 'l' THEN 'lz4' END) AS attcompression,
(SELECT dsd.refobjid
 FROM pg_catalog.pg_depend dsd
   JOIN pg_catalog.pg_class dsc ON dsd.refclassid='pg_catalog.pg_class'::regclass
     AND dsd.refobjid=dsc.oid AND dsc.relkind='S'
 WHERE dsd.classid='pg_catalog.pg_attrdef'::regclass
   AND dsd.objid=def.oid AND dsd.deptype='n'
 ORDER BY dsd.refobjid
 LIMIT 1) AS defseqrelid,
seq.*
FROM pg_catalog.pg_attribute att
  JOIN pg_catalog.pg_type ty ON ty.oid=atttypid
  LEFT OUTER JOIN pg_catalog.pg_attrdef def ON adrelid=att.attrelid AND adnum=att.attnum
  LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=att.attrelid AND des.objsubid=att.attnum AND des.classoid='pg_class'::regclass)
  LEFT OUTER JOIN (pg_catalog.pg_depend dep JOIN pg_catalog.pg_class cs ON dep.classid='pg_class'::regclass AND dep.objid=cs.oid AND cs.relkind='S') ON dep.refobjid=att.attrelid AND dep.refobjsubid=att.attnum
  LEFT OUTER JOIN pg_catalog.pg_index pi ON pi.indrelid=att.attrelid AND indisprimary
  LEFT OUTER JOIN pg_catalog.pg_collation coll ON att.attcollation=coll.oid
  LEFT OUTER JOIN pg_catalog.pg_namespace nspc ON coll.collnamespace=nspc.oid
  LEFT OUTER JOIN pg_catalog.pg_sequence seq ON cs.oid=seq.seqrelid
  LEFT OUTER JOIN pg_catalog.pg_class tab on tab.oid = att.attrelid
  LEFT OUTER join INH_TABLES as INH ON att.attname = INH.name
WHERE att.attrelid = 16385::oid
    AND att.attnum > 0
    AND att.attisdropped IS FALSE
    ORDER BY att.attnum;
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT pg_catalog.array_to_string(pg_catalog.array_agg(inhrelname), ', ') inhrelname, attrname
FROM
 (SELECT
   inhparent::regclass AS inhrelname,
   a.attname AS attrname
  FROM pg_catalog.pg_inherits i
  LEFT JOIN pg_catalog.pg_attribute a ON
   (attrelid = inhparent AND attnum > 0)
  WHERE inhrelid = 16385::oid
  ORDER BY inhseqno
 ) a
GROUP BY attrname;
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT 'attacl' as deftype,
    COALESCE(gt.rolname, 'PUBLIC') grantee,
    g.rolname grantor,
    pg_catalog.array_agg(privilege_type order by privilege_type) as privileges,
    pg_catalog.array_agg(is_grantable) as grantable
FROM
  (SELECT
    d.grantee, d.grantor, d.is_grantable,
    CASE d.privilege_type
        WHEN 'CONNECT' THEN 'c'
        WHEN 'CREATE' THEN 'C'
        WHEN 'DELETE' THEN 'd'
        WHEN 'EXECUTE' THEN 'X'
        WHEN 'INSERT' THEN 'a'
        WHEN 'REFERENCES' THEN 'x'
        WHEN 'SELECT' THEN 'r'
        WHEN 'TEMPORARY' THEN 'T'
        WHEN 'TRIGGER' THEN 't'
        WHEN 'TRUNCATE' THEN 'D'
        WHEN 'UPDATE' THEN 'w'
        WHEN 'USAGE' THEN 'U'
        ELSE 'UNKNOWN'
    END AS privilege_type
  FROM
    (SELECT attacl
        FROM pg_catalog.pg_attribute att
        WHERE att.attrelid = 16385::oid
        AND att.attnum = 1::int
    ) acl,
    (SELECT (d).grantee AS grantee, (d).grantor AS grantor, (d).is_grantable
        AS is_grantable, (d).privilege_type AS privilege_type FROM (SELECT
        pg_catalog.aclexplode(attacl) as d FROM pg_catalog.pg_attribute att
        WHERE att.attrelid = 16385::oid
        AND att.attnum = 1::int) a) d
    ) d
  LEFT JOIN pg_catalog.pg_roles g ON (d.grantor = g.oid)
  LEFT JOIN pg_catalog.pg_roles gt ON (d.grantee = gt.oid)
GROUP BY g.rolname, gt.rolname
ORDER BY grantee
-----
-- [pgadmin]
-- postgres16: 19 92e7043ccf22
-- expect: 12  # only the casts the frontend implements
SELECT tt.oid, pg_catalog.format_type(tt.oid,NULL) AS typname
FROM pg_catalog.pg_type tt
    JOIN pg_catalog.pg_cast pc ON tt.oid=pc.casttarget
    WHERE pc.castsource= 23
    AND pc.castcontext IN ('i', 'a')
UNION
SELECT tt.oid, pg_catalog.format_type(tt.oid,NULL) AS typname
FROM pg_catalog.pg_type tt
WHERE tt.typbasetype = 23
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT 'attacl' as deftype,
    COALESCE(gt.rolname, 'PUBLIC') grantee,
    g.rolname grantor,
    pg_catalog.array_agg(privilege_type order by privilege_type) as privileges,
    pg_catalog.array_agg(is_grantable) as grantable
FROM
  (SELECT
    d.grantee, d.grantor, d.is_grantable,
    CASE d.privilege_type
        WHEN 'CONNECT' THEN 'c'
        WHEN 'CREATE' THEN 'C'
        WHEN 'DELETE' THEN 'd'
        WHEN 'EXECUTE' THEN 'X'
        WHEN 'INSERT' THEN 'a'
        WHEN 'REFERENCES' THEN 'x'
        WHEN 'SELECT' THEN 'r'
        WHEN 'TEMPORARY' THEN 'T'
        WHEN 'TRIGGER' THEN 't'
        WHEN 'TRUNCATE' THEN 'D'
        WHEN 'UPDATE' THEN 'w'
        WHEN 'USAGE' THEN 'U'
        ELSE 'UNKNOWN'
    END AS privilege_type
  FROM
    (SELECT attacl
        FROM pg_catalog.pg_attribute att
        WHERE att.attrelid = 16385::oid
        AND att.attnum = 2::int
    ) acl,
    (SELECT (d).grantee AS grantee, (d).grantor AS grantor, (d).is_grantable
        AS is_grantable, (d).privilege_type AS privilege_type FROM (SELECT
        pg_catalog.aclexplode(attacl) as d FROM pg_catalog.pg_attribute att
        WHERE att.attrelid = 16385::oid
        AND att.attnum = 2::int) a) d
    ) d
  LEFT JOIN pg_catalog.pg_roles g ON (d.grantor = g.oid)
  LEFT JOIN pg_catalog.pg_roles gt ON (d.grantee = gt.oid)
GROUP BY g.rolname, gt.rolname
ORDER BY grantee
-----
-- [pgadmin]
-- postgres16: 8 8be075989ee9
-- expect: 7  # only the casts the frontend implements
SELECT tt.oid, pg_catalog.format_type(tt.oid,NULL) AS typname
FROM pg_catalog.pg_type tt
    JOIN pg_catalog.pg_cast pc ON tt.oid=pc.casttarget
    WHERE pc.castsource= 1043
    AND pc.castcontext IN ('i', 'a')
UNION
SELECT tt.oid, pg_catalog.format_type(tt.oid,NULL) AS typname
FROM pg_catalog.pg_type tt
WHERE tt.typbasetype = 1043
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT 'attacl' as deftype,
    COALESCE(gt.rolname, 'PUBLIC') grantee,
    g.rolname grantor,
    pg_catalog.array_agg(privilege_type order by privilege_type) as privileges,
    pg_catalog.array_agg(is_grantable) as grantable
FROM
  (SELECT
    d.grantee, d.grantor, d.is_grantable,
    CASE d.privilege_type
        WHEN 'CONNECT' THEN 'c'
        WHEN 'CREATE' THEN 'C'
        WHEN 'DELETE' THEN 'd'
        WHEN 'EXECUTE' THEN 'X'
        WHEN 'INSERT' THEN 'a'
        WHEN 'REFERENCES' THEN 'x'
        WHEN 'SELECT' THEN 'r'
        WHEN 'TEMPORARY' THEN 'T'
        WHEN 'TRIGGER' THEN 't'
        WHEN 'TRUNCATE' THEN 'D'
        WHEN 'UPDATE' THEN 'w'
        WHEN 'USAGE' THEN 'U'
        ELSE 'UNKNOWN'
    END AS privilege_type
  FROM
    (SELECT attacl
        FROM pg_catalog.pg_attribute att
        WHERE att.attrelid = 16385::oid
        AND att.attnum = 3::int
    ) acl,
    (SELECT (d).grantee AS grantee, (d).grantor AS grantor, (d).is_grantable
        AS is_grantable, (d).privilege_type AS privilege_type FROM (SELECT
        pg_catalog.aclexplode(attacl) as d FROM pg_catalog.pg_attribute att
        WHERE att.attrelid = 16385::oid
        AND att.attnum = 3::int) a) d
    ) d
  LEFT JOIN pg_catalog.pg_roles g ON (d.grantor = g.oid)
  LEFT JOIN pg_catalog.pg_roles gt ON (d.grantee = gt.oid)
GROUP BY g.rolname, gt.rolname
ORDER BY grantee
-----
-- [pgadmin]
-- postgres16: 7 0257106ed6d5
-- expect: 6  # only the casts the frontend implements
SELECT tt.oid, pg_catalog.format_type(tt.oid,NULL) AS typname
FROM pg_catalog.pg_type tt
    JOIN pg_catalog.pg_cast pc ON tt.oid=pc.casttarget
    WHERE pc.castsource= 1700
    AND pc.castcontext IN ('i', 'a')
UNION
SELECT tt.oid, pg_catalog.format_type(tt.oid,NULL) AS typname
FROM pg_catalog.pg_type tt
WHERE tt.typbasetype = 1700
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT 'attacl' as deftype,
    COALESCE(gt.rolname, 'PUBLIC') grantee,
    g.rolname grantor,
    pg_catalog.array_agg(privilege_type order by privilege_type) as privileges,
    pg_catalog.array_agg(is_grantable) as grantable
FROM
  (SELECT
    d.grantee, d.grantor, d.is_grantable,
    CASE d.privilege_type
        WHEN 'CONNECT' THEN 'c'
        WHEN 'CREATE' THEN 'C'
        WHEN 'DELETE' THEN 'd'
        WHEN 'EXECUTE' THEN 'X'
        WHEN 'INSERT' THEN 'a'
        WHEN 'REFERENCES' THEN 'x'
        WHEN 'SELECT' THEN 'r'
        WHEN 'TEMPORARY' THEN 'T'
        WHEN 'TRIGGER' THEN 't'
        WHEN 'TRUNCATE' THEN 'D'
        WHEN 'UPDATE' THEN 'w'
        WHEN 'USAGE' THEN 'U'
        ELSE 'UNKNOWN'
    END AS privilege_type
  FROM
    (SELECT attacl
        FROM pg_catalog.pg_attribute att
        WHERE att.attrelid = 16385::oid
        AND att.attnum = 4::int
    ) acl,
    (SELECT (d).grantee AS grantee, (d).grantor AS grantor, (d).is_grantable
        AS is_grantable, (d).privilege_type AS privilege_type FROM (SELECT
        pg_catalog.aclexplode(attacl) as d FROM pg_catalog.pg_attribute att
        WHERE att.attrelid = 16385::oid
        AND att.attnum = 4::int) a) d
    ) d
  LEFT JOIN pg_catalog.pg_roles g ON (d.grantor = g.oid)
  LEFT JOIN pg_catalog.pg_roles gt ON (d.grantee = gt.oid)
GROUP BY g.rolname, gt.rolname
ORDER BY grantee
-----
-- [pgadmin]
-- postgres16: 17 e04bb16c4818
-- expect: 11  # only the casts the frontend implements
SELECT tt.oid, pg_catalog.format_type(tt.oid,NULL) AS typname
FROM pg_catalog.pg_type tt
    JOIN pg_catalog.pg_cast pc ON tt.oid=pc.casttarget
    WHERE pc.castsource= 21
    AND pc.castcontext IN ('i', 'a')
UNION
SELECT tt.oid, pg_catalog.format_type(tt.oid,NULL) AS typname
FROM pg_catalog.pg_type tt
WHERE tt.typbasetype = 21
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT 'attacl' as deftype,
    COALESCE(gt.rolname, 'PUBLIC') grantee,
    g.rolname grantor,
    pg_catalog.array_agg(privilege_type order by privilege_type) as privileges,
    pg_catalog.array_agg(is_grantable) as grantable
FROM
  (SELECT
    d.grantee, d.grantor, d.is_grantable,
    CASE d.privilege_type
        WHEN 'CONNECT' THEN 'c'
        WHEN 'CREATE' THEN 'C'
        WHEN 'DELETE' THEN 'd'
        WHEN 'EXECUTE' THEN 'X'
        WHEN 'INSERT' THEN 'a'
        WHEN 'REFERENCES' THEN 'x'
        WHEN 'SELECT' THEN 'r'
        WHEN 'TEMPORARY' THEN 'T'
        WHEN 'TRIGGER' THEN 't'
        WHEN 'TRUNCATE' THEN 'D'
        WHEN 'UPDATE' THEN 'w'
        WHEN 'USAGE' THEN 'U'
        ELSE 'UNKNOWN'
    END AS privilege_type
  FROM
    (SELECT attacl
        FROM pg_catalog.pg_attribute att
        WHERE att.attrelid = 16385::oid
        AND att.attnum = 5::int
    ) acl,
    (SELECT (d).grantee AS grantee, (d).grantor AS grantor, (d).is_grantable
        AS is_grantable, (d).privilege_type AS privilege_type FROM (SELECT
        pg_catalog.aclexplode(attacl) as d FROM pg_catalog.pg_attribute att
        WHERE att.attrelid = 16385::oid
        AND att.attnum = 5::int) a) d
    ) d
  LEFT JOIN pg_catalog.pg_roles g ON (d.grantor = g.oid)
  LEFT JOIN pg_catalog.pg_roles gt ON (d.grantee = gt.oid)
GROUP BY g.rolname, gt.rolname
ORDER BY grantee
-----
-- [pgadmin]
-- postgres16: 5 f7609fd6c766
-- expect: same
SELECT tt.oid, pg_catalog.format_type(tt.oid,NULL) AS typname
FROM pg_catalog.pg_type tt
    JOIN pg_catalog.pg_cast pc ON tt.oid=pc.casttarget
    WHERE pc.castsource= 25
    AND pc.castcontext IN ('i', 'a')
UNION
SELECT tt.oid, pg_catalog.format_type(tt.oid,NULL) AS typname
FROM pg_catalog.pg_type tt
WHERE tt.typbasetype = 25
-----
-- [pgadmin]
-- postgres16: 1 45e1287abd33
-- expect: 1  # owned by scalardb, the ScalarDB key's description, no TOAST table, no default_table_access_method setting
SELECT rel.oid, rel.relname AS name, rel.reltablespace AS spcoid,rel.relacl AS relacl_str,
  (CASE WHEN length(spc.spcname::text) > 0 OR rel.relkind = 'p' THEN spc.spcname ELSE
    (SELECT sp.spcname FROM pg_catalog.pg_database dtb
    JOIN pg_catalog.pg_tablespace sp ON dtb.dattablespace=sp.oid
    WHERE dtb.oid = 16384::oid)
  END) as spcname,
  (CASE rel.relreplident
          WHEN 'd' THEN 'default'
          WHEN 'n' THEN 'nothing'
          WHEN 'f' THEN 'full'
          WHEN 'i' THEN 'index'
  END) as replica_identity,
  (select nspname FROM pg_catalog.pg_namespace WHERE oid = 2200::oid ) as schema,
  pg_catalog.pg_get_userbyid(rel.relowner) AS relowner, rel.relkind,
  (CASE WHEN rel.relkind = 'p' THEN true ELSE false END) AS is_partitioned,
  rel.relhassubclass, rel.reltuples::bigint, des.description, con.conname, con.conkey,
EXISTS(select 1 FROM pg_catalog.pg_trigger
JOIN pg_catalog.pg_proc pt ON pt.oid=tgfoid AND pt.proname='logtrigger'
JOIN pg_catalog.pg_proc pc ON pc.pronamespace=pt.pronamespace AND pc.proname='slonyversion'
WHERE tgrelid=rel.oid) AS isrepl,
(SELECT count(*) FROM pg_catalog.pg_trigger WHERE tgrelid=rel.oid AND tgisinternal = FALSE) AS triggercount,
(SELECT ARRAY(SELECT CASE WHEN (nspname NOT LIKE 'pg\_%') THEN
            pg_catalog.quote_ident(nspname)||'.'||pg_catalog.quote_ident(c.relname)
            ELSE pg_catalog.quote_ident(c.relname) END AS inherited_tables
    FROM pg_catalog.pg_inherits i
    JOIN pg_catalog.pg_class c ON c.oid = i.inhparent
    JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
    WHERE i.inhrelid = rel.oid ORDER BY inhseqno)) AS coll_inherits,
  (SELECT count(*)
FROM pg_catalog.pg_inherits i
      JOIN pg_catalog.pg_class c ON c.oid = i.inhparent
      JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
WHERE i.inhrelid = rel.oid) AS inherited_tables_cnt,
(CASE WHEN rel.relpersistence = 'u' THEN true ELSE false END) AS relpersistence,
(SELECT st.setting from pg_catalog.pg_show_all_settings() st WHERE st.name = 'default_table_access_method') as default_amname,
substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'fillfactor=([0-9]*)') AS fillfactor,
substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'parallel_workers=([0-9]*)') AS parallel_workers,
substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'toast_tuple_target=([0-9]*)') AS toast_tuple_target,
(substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'autovacuum_enabled=([a-z|0-9]*)'))::BOOL AS autovacuum_enabled,
substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'autovacuum_vacuum_threshold=([0-9]*)') AS autovacuum_vacuum_threshold,
substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'autovacuum_vacuum_scale_factor=([0-9]*[.]?[0-9]*)') AS autovacuum_vacuum_scale_factor,
substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'autovacuum_analyze_threshold=([0-9]*)') AS autovacuum_analyze_threshold,
substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'autovacuum_analyze_scale_factor=([0-9]*[.]?[0-9]*)') AS autovacuum_analyze_scale_factor,
substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'autovacuum_vacuum_cost_delay=([0-9]*)') AS autovacuum_vacuum_cost_delay,
substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'autovacuum_vacuum_cost_limit=([0-9]*)') AS autovacuum_vacuum_cost_limit,
substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'autovacuum_freeze_min_age=([0-9]*)') AS autovacuum_freeze_min_age,
substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'autovacuum_freeze_max_age=([0-9]*)') AS autovacuum_freeze_max_age,
substring(pg_catalog.array_to_string(rel.reloptions, ',') FROM 'autovacuum_freeze_table_age=([0-9]*)') AS autovacuum_freeze_table_age,
(substring(pg_catalog.array_to_string(tst.reloptions, ',') FROM 'autovacuum_enabled=([a-z|0-9]*)'))::BOOL AS toast_autovacuum_enabled,
substring(pg_catalog.array_to_string(tst.reloptions, ',') FROM 'autovacuum_vacuum_threshold=([0-9]*)') AS toast_autovacuum_vacuum_threshold,
substring(pg_catalog.array_to_string(tst.reloptions, ',') FROM 'autovacuum_vacuum_scale_factor=([0-9]*[.]?[0-9]*)') AS toast_autovacuum_vacuum_scale_factor,
substring(pg_catalog.array_to_string(tst.reloptions, ',') FROM 'autovacuum_analyze_threshold=([0-9]*)') AS toast_autovacuum_analyze_threshold,
substring(pg_catalog.array_to_string(tst.reloptions, ',') FROM 'autovacuum_analyze_scale_factor=([0-9]*[.]?[0-9]*)') AS toast_autovacuum_analyze_scale_factor,
substring(pg_catalog.array_to_string(tst.reloptions, ',') FROM 'autovacuum_vacuum_cost_delay=([0-9]*)') AS toast_autovacuum_vacuum_cost_delay,
substring(pg_catalog.array_to_string(tst.reloptions, ',') FROM 'autovacuum_vacuum_cost_limit=([0-9]*)') AS toast_autovacuum_vacuum_cost_limit,
substring(pg_catalog.array_to_string(tst.reloptions, ',') FROM 'autovacuum_freeze_min_age=([0-9]*)') AS toast_autovacuum_freeze_min_age,
substring(pg_catalog.array_to_string(tst.reloptions, ',') FROM 'autovacuum_freeze_max_age=([0-9]*)') AS toast_autovacuum_freeze_max_age,
substring(pg_catalog.array_to_string(tst.reloptions, ',') FROM 'autovacuum_freeze_table_age=([0-9]*)') AS toast_autovacuum_freeze_table_age,
rel.reloptions AS reloptions, tst.reloptions AS toast_reloptions, rel.reloftype, am.amname,
CASE WHEN typ.typname IS NOT NULL THEN (select pg_catalog.quote_ident(nspname) FROM pg_catalog.pg_namespace WHERE oid = 2200::oid )||'.'||pg_catalog.quote_ident(typ.typname) ELSE typ.typname END AS typname,
typ.typrelid AS typoid, rel.relrowsecurity as rlspolicy, rel.relforcerowsecurity as forcerlspolicy,
(CASE WHEN rel.reltoastrelid = 0 THEN false ELSE true END) AS hastoasttable,
(SELECT pg_catalog.array_agg(provider || '=' || label) FROM pg_catalog.pg_seclabels sl1 WHERE sl1.objoid=rel.oid AND sl1.objsubid=0) AS seclabels,
(CASE WHEN rel.oid <= 16383::oid THEN true ElSE false END) AS is_sys_table
    , (CASE WHEN rel.relkind = 'p' THEN pg_catalog.pg_get_partkeydef(16385::oid) ELSE '' END) AS partition_scheme FROM pg_catalog.pg_class rel
  LEFT OUTER JOIN pg_catalog.pg_tablespace spc on spc.oid=rel.reltablespace
  LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=rel.oid AND des.objsubid=0 AND des.classoid='pg_class'::regclass)
  LEFT OUTER JOIN pg_catalog.pg_constraint con ON con.conrelid=rel.oid AND con.contype='p'
  LEFT OUTER JOIN pg_catalog.pg_class tst ON tst.oid = rel.reltoastrelid
  LEFT JOIN pg_catalog.pg_type typ ON rel.reloftype=typ.oid
  LEFT OUTER JOIN pg_catalog.pg_am am ON am.oid = rel.relam
WHERE rel.relkind IN ('r','s','t','p') AND rel.relnamespace = 2200::oid
AND NOT rel.relispartition
  AND rel.oid = 16385::oid ORDER BY rel.relname;
-----
-- [pgadmin]
-- postgres16: 15 89d43e6a882a
-- expect: 1  # the scalardb role; PostgreSQL's built-in roles are not here
SELECT
    r.oid, r.rolname, r.rolcanlogin, r.rolsuper, d.description
FROM pg_catalog.pg_roles r
    LEFT JOIN pg_catalog.pg_shdescription d
    ON d.objoid = r.oid AND d.classoid = 'pg_catalog.pg_authid'::regclass
ORDER BY r.rolcanlogin, r.rolname
-----
-- [pgadmin]
-- postgres16: 1 210429377401
-- expect: same
SELECT oid, amname
FROM pg_catalog.pg_am WHERE amtype = 't';
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT c.oid,
  pg_catalog.quote_ident(n.nspname)||'.'||pg_catalog.quote_ident(c.relname) AS typname
  FROM pg_catalog.pg_namespace n, pg_catalog.pg_class c
WHERE c.relkind = 'c' AND c.relnamespace=n.oid
AND n.nspname NOT LIKE 'pg\_%' AND n.nspname NOT IN ('information_schema') 
ORDER BY typname;
-----
-- [pgadmin]
-- postgres16: 2 c51d078a4cdf
-- expect: same
SELECT
    ts.oid AS oid, spcname AS name, spcowner as owner,
    pg_catalog.shobj_description(oid, 'pg_tablespace') AS description
FROM
    pg_catalog.pg_tablespace ts
ORDER BY name;
-----
-- [pgadmin]
-- postgres16: 1 e8aa5a0bc8fe
-- expect: 0  # relacl is null: the owner's privileges, granted to no one else
SELECT 'relacl' as deftype, COALESCE(gt.rolname, 'PUBLIC') grantee, g.rolname grantor,
    pg_catalog.array_agg(privilege_type) as privileges, pg_catalog.array_agg(is_grantable) as grantable
FROM
  (SELECT
    d.grantee, d.grantor, d.is_grantable,
    CASE d.privilege_type
WHEN 'CONNECT' THEN 'c'
WHEN 'CREATE' THEN 'C'
WHEN 'DELETE' THEN 'd'
WHEN 'EXECUTE' THEN 'X'
WHEN 'INSERT' THEN 'a'
WHEN 'MAINTAIN' THEN 'm'
WHEN 'REFERENCES' THEN 'x'
WHEN 'SELECT' THEN 'r'
WHEN 'TEMPORARY' THEN 'T'
WHEN 'TRIGGER' THEN 't'
WHEN 'TRUNCATE' THEN 'D'
WHEN 'UPDATE' THEN 'w'
WHEN 'USAGE' THEN 'U'
ELSE 'UNKNOWN'
END AS privilege_type
  FROM
    (SELECT rel.relacl
        FROM pg_catalog.pg_class rel
          LEFT OUTER JOIN pg_catalog.pg_tablespace spc on spc.oid=rel.reltablespace
          LEFT OUTER JOIN pg_catalog.pg_constraint con ON con.conrelid=rel.oid AND con.contype='p'
          LEFT OUTER JOIN pg_catalog.pg_class tst ON tst.oid = rel.reltoastrelid
          LEFT JOIN pg_catalog.pg_type typ ON rel.reloftype=typ.oid
        WHERE rel.relkind IN ('r','s','t','p') AND rel.relnamespace = 2200::oid
            AND rel.oid = 16385::oid
    ) acl,
    (SELECT (d).grantee AS grantee, (d).grantor AS grantor, (d).is_grantable
        AS is_grantable, (d).privilege_type AS privilege_type FROM (SELECT
        aclexplode(rel.relacl) as d
        FROM pg_catalog.pg_class rel
          LEFT OUTER JOIN pg_catalog.pg_tablespace spc on spc.oid=rel.reltablespace
          LEFT OUTER JOIN pg_catalog.pg_constraint con ON con.conrelid=rel.oid AND con.contype='p'
          LEFT OUTER JOIN pg_catalog.pg_class tst ON tst.oid = rel.reltoastrelid
          LEFT JOIN pg_catalog.pg_type typ ON rel.reloftype=typ.oid
        WHERE rel.relkind IN ('r','s','t','p') AND rel.relnamespace = 2200::oid
            AND rel.oid = 16385::oid
        ) a ORDER BY privilege_type) d
    ) d
  LEFT JOIN pg_catalog.pg_roles g ON (d.grantor = g.oid)
  LEFT JOIN pg_catalog.pg_roles gt ON (d.grantee = gt.oid)
GROUP BY g.rolname, gt.rolname
ORDER BY grantee
-----
-- [pgadmin]
-- postgres16: 9 9afea7b8db65
-- expect: 0  # no autovacuum settings: the frontend does not vacuum
SELECT name, setting::numeric AS setting FROM pg_catalog.pg_show_all_settings() WHERE name IN('autovacuum_vacuum_threshold','autovacuum_analyze_threshold','autovacuum_vacuum_scale_factor','autovacuum_analyze_scale_factor','autovacuum_vacuum_cost_delay','autovacuum_vacuum_cost_limit','autovacuum_freeze_max_age','vacuum_freeze_min_age','vacuum_freeze_table_age') ORDER BY name
-----
-- [pgadmin]
-- postgres16: 7 c473450525ab
-- expect: 0  # no autovacuum settings: the frontend does not vacuum
SELECT name, setting::numeric AS setting FROM pg_catalog.pg_show_all_settings() WHERE name IN('autovacuum_vacuum_threshold','autovacuum_vacuum_scale_factor','autovacuum_vacuum_cost_delay','autovacuum_vacuum_cost_limit','autovacuum_freeze_max_age','vacuum_freeze_min_age','vacuum_freeze_table_age') ORDER BY name
-----
-- [pgadmin]
-- postgres16: 5 89f5dbac8454
-- expect: 5  # only the casts the frontend implements
SELECT t.main_oid, pg_catalog.ARRAY_AGG(t.typname) as edit_types
FROM
(SELECT pc.castsource AS main_oid, pg_catalog.format_type(tt.oid,NULL) AS typname
FROM pg_catalog.pg_type tt
    JOIN pg_catalog.pg_cast pc ON tt.oid=pc.casttarget
    WHERE pc.castsource IN (23,1043,1700,21,25)
    AND pc.castcontext IN ('i', 'a')
UNION
SELECT tt.typbasetype AS main_oid, pg_catalog.format_type(tt.oid,NULL) AS typname
FROM pg_catalog.pg_type tt
WHERE tt.typbasetype  IN (23,1043,1700,21,25)
) t
GROUP BY t.main_oid;
-----
-- [pgadmin]
-- postgres16: 1 b3b6e3795d29
-- expect: 1  # the test server's OIDs
SELECT cls.oid,
    cls.relname as name,
    indnkeyatts as col_count,
    indnullsnotdistinct,
    CASE WHEN length(spcname::text) > 0 THEN spcname ELSE
        (SELECT sp.spcname FROM pg_catalog.pg_database dtb
        JOIN pg_catalog.pg_tablespace sp ON dtb.dattablespace=sp.oid
        WHERE dtb.oid = 16384::oid)
    END as spcname,
    CASE contype
        WHEN 'p' THEN desp.description
        WHEN 'u' THEN desp.description
        WHEN 'x' THEN desp.description
        ELSE des.description
    END AS comment,
    condeferrable,
    condeferred,
    conislocal,
    substring(pg_catalog.array_to_string(cls.reloptions, ',') from 'fillfactor=([0-9]*)') AS fillfactor
FROM pg_catalog.pg_index idx
JOIN pg_catalog.pg_class cls ON cls.oid=indexrelid
LEFT OUTER JOIN pg_catalog.pg_tablespace ta on ta.oid=cls.reltablespace
LEFT JOIN pg_catalog.pg_depend dep ON (dep.classid = cls.tableoid AND dep.objid = cls.oid AND dep.refobjsubid = '0' AND dep.refclassid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='pg_constraint') AND dep.deptype='i')
LEFT OUTER JOIN pg_catalog.pg_constraint con ON (con.tableoid = dep.refclassid AND con.oid = dep.refobjid)
LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=cls.oid AND des.classoid='pg_class'::regclass)
LEFT OUTER JOIN pg_catalog.pg_description desp ON (desp.objoid=con.oid AND desp.objsubid = 0 AND desp.classoid='pg_constraint'::regclass)
WHERE indrelid = 16385::oid
AND contype='p'
ORDER BY cls.relname
-----
-- [pgadmin]
-- postgres16: 1 7c29bda18adb
-- expect: same
SELECT * FROM (
SELECT  pg_catalog.pg_get_indexdef(16390 , 1 , true) AS column, 0 AS dummy
) tmp
ORDER BY dummy
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT a.attname as colname
FROM (
    SELECT
      i.indnkeyatts,
      i.indrelid,
      pg_catalog.unnest(indkey) AS table_colnum,
      pg_catalog.unnest(ARRAY(SELECT pg_catalog.generate_series(1, i.indnatts) AS n)) attnum
    FROM
      pg_catalog.pg_index i
    WHERE i.indexrelid = 16390::OID
) i JOIN pg_catalog.pg_attribute a
ON (a.attrelid = i.indrelid AND i.table_colnum = a.attnum)
WHERE i.attnum > i.indnkeyatts
ORDER BY i.attnum
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT cls.oid,
    cls.relname as name,
    indnkeyatts as col_count,
    indnullsnotdistinct,
    CASE WHEN length(spcname::text) > 0 THEN spcname ELSE
        (SELECT sp.spcname FROM pg_catalog.pg_database dtb
        JOIN pg_catalog.pg_tablespace sp ON dtb.dattablespace=sp.oid
        WHERE dtb.oid = 16384::oid)
    END as spcname,
    CASE contype
        WHEN 'p' THEN desp.description
        WHEN 'u' THEN desp.description
        WHEN 'x' THEN desp.description
        ELSE des.description
    END AS comment,
    condeferrable,
    condeferred,
    conislocal,
    substring(pg_catalog.array_to_string(cls.reloptions, ',') from 'fillfactor=([0-9]*)') AS fillfactor
FROM pg_catalog.pg_index idx
JOIN pg_catalog.pg_class cls ON cls.oid=indexrelid
LEFT OUTER JOIN pg_catalog.pg_tablespace ta on ta.oid=cls.reltablespace
LEFT JOIN pg_catalog.pg_depend dep ON (dep.classid = cls.tableoid AND dep.objid = cls.oid AND dep.refobjsubid = '0' AND dep.refclassid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='pg_constraint') AND dep.deptype='i')
LEFT OUTER JOIN pg_catalog.pg_constraint con ON (con.tableoid = dep.refclassid AND con.oid = dep.refobjid)
LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=cls.oid AND des.classoid='pg_class'::regclass)
LEFT OUTER JOIN pg_catalog.pg_description desp ON (desp.objoid=con.oid AND desp.objsubid = 0 AND desp.classoid='pg_constraint'::regclass)
WHERE indrelid = 16385::oid
AND contype='u'
ORDER BY cls.relname
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT ct.oid,
      conname as name,
      condeferrable,
      condeferred,
      confupdtype,
      confdeltype,
      CASE confmatchtype
        WHEN 's' THEN FALSE
        WHEN 'f' THEN TRUE
      END AS confmatchtype,
      conkey,
      confkey,
      confrelid,
      nl.nspname as fknsp,
      cl.relname as fktab,
      nr.oid as refnspoid,
      nr.nspname as refnsp,
      cr.relname as reftab,
      description as comment,
      convalidated,
      conislocal
FROM pg_catalog.pg_constraint ct
JOIN pg_catalog.pg_class cl ON cl.oid=conrelid
JOIN pg_catalog.pg_namespace nl ON nl.oid=cl.relnamespace
JOIN pg_catalog.pg_class cr ON cr.oid=confrelid
JOIN pg_catalog.pg_namespace nr ON nr.oid=cr.relnamespace
LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=ct.oid AND des.classoid='pg_constraint'::regclass)
WHERE contype='f' AND
conrelid = 16385::oid
ORDER BY conname
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT c.oid, conname as name, relname, nspname, description as comment,
       pg_catalog.pg_get_expr(conbin, conrelid, true) as consrc,
       connoinherit, NOT convalidated as convalidated, conislocal
    FROM pg_catalog.pg_constraint c
    JOIN pg_catalog.pg_class cl ON cl.oid=conrelid
    JOIN pg_catalog.pg_namespace nl ON nl.oid=relnamespace
LEFT OUTER JOIN
    pg_catalog.pg_description des ON (des.objoid=c.oid AND
                           des.classoid='pg_constraint'::regclass)
WHERE contype = 'c'
    AND conrelid = 16385::oid
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT cls.oid,
    cls.relname as name,
    indnkeyatts as col_count,
    amname,
    CASE WHEN length(spcname::text) > 0 THEN spcname ELSE
        (SELECT sp.spcname FROM pg_catalog.pg_database dtb
        JOIN pg_catalog.pg_tablespace sp ON dtb.dattablespace=sp.oid
        WHERE dtb.oid = 16384::oid)
    END as spcname,
    CASE contype
        WHEN 'p' THEN desp.description
        WHEN 'u' THEN desp.description
        WHEN 'x' THEN desp.description
        ELSE des.description
    END AS comment,
    condeferrable,
    condeferred,
    substring(pg_catalog.array_to_string(cls.reloptions, ',') from 'fillfactor=([0-9]*)') AS fillfactor,
    pg_catalog.pg_get_expr(idx.indpred, idx.indrelid, true) AS indconstraint
FROM pg_catalog.pg_index idx
JOIN pg_catalog.pg_class cls ON cls.oid=indexrelid
LEFT OUTER JOIN pg_catalog.pg_tablespace ta on ta.oid=cls.reltablespace
JOIN pg_catalog.pg_am am ON am.oid=cls.relam
LEFT JOIN pg_catalog.pg_depend dep ON (dep.classid = cls.tableoid AND dep.objid = cls.oid AND dep.refobjsubid = '0' AND dep.refclassid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='pg_constraint') AND dep.deptype='i')
LEFT OUTER JOIN pg_catalog.pg_constraint con ON (con.tableoid = dep.refclassid AND con.oid = dep.refobjid)
LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=cls.oid AND des.classoid='pg_class'::regclass)
LEFT OUTER JOIN pg_catalog.pg_description desp ON (desp.objoid=con.oid AND desp.objsubid = 0 AND desp.classoid='pg_constraint'::regclass)
WHERE indrelid = 16385::oid
AND contype='x'
ORDER BY cls.relname
-----
-- [pgadmin]
-- postgres16: 1 dd0c73b0a8fe
-- expect: 1  # the test server's OIDs
SELECT DISTINCT ON (cls.relname)
  cls.oid,
  cls.relname AS name,
  idx.indrelid,
  idx.indkey,
  idx.indisclustered,
  idx.indisvalid,
  idx.indisunique,
  idx.indisprimary,
  n.nspname,
  idx.indnatts,
  COALESCE(cls.reltablespace, 0) AS spcoid,
  idx.indnullsnotdistinct,
  COALESCE(
    CASE
          WHEN (cls.reltablespace <> 0 OR cls.relkind = 'I') THEN ta.spcname
          ELSE (
              SELECT sp.spcname
              FROM pg_catalog.pg_database dtb
              JOIN pg_catalog.pg_tablespace sp ON dtb.dattablespace = sp.oid
              WHERE dtb.oid = 16384::oid
              LIMIT 1
          )
      END, 'pg_default'
  ) AS spcname,
  con.conname,
  tab.relname AS tabname,
  idx.indclass,
  con.oid AS conoid,
  CASE
      WHEN con.contype IN ('p', 'u', 'x') THEN desp.description
      ELSE des.description
  END AS description,
  (
      SELECT array_agg(DISTINCT e.extname)
      FROM pg_catalog.pg_depend d
      JOIN pg_catalog.pg_extension e ON d.refobjid = e.oid
      WHERE d.objid = cls.oid
  ) AS dependsonextensions,
  pg_catalog.pg_get_expr(idx.indpred, idx.indrelid, true) AS indconstraint,
  con.contype,
  con.condeferrable,
  con.condeferred,
  am.amname,
  EXISTS (
      SELECT 1 FROM pg_catalog.pg_inherits inh WHERE inh.inhrelid = cls.oid
  ) AS is_inherited,
  -- Options (with defaults per index type)
    substring(array_to_string(cls.reloptions, ',') FROM 'fillfactor=([0-9]*)')::int AS fillfactor,
    substring(array_to_string(cls.reloptions, ',') FROM 'deduplicate_items=([a-z]*)')::boolean AS deduplicate_items,
    substring(array_to_string(cls.reloptions, ',') FROM 'gin_pending_list_limit=([0-9]*)')::int AS gin_pending_list_limit,
    substring(array_to_string(cls.reloptions, ',') FROM 'pages_per_range=([0-9]*)')::int AS pages_per_range,
    substring(array_to_string(cls.reloptions, ',') FROM 'buffering=([a-z]*)') AS buffering,
    substring(array_to_string(cls.reloptions, ',') FROM 'fastupdate=([a-z]*)')::boolean AS fastupdate,
    substring(array_to_string(cls.reloptions, ',') FROM 'autosummarize=([a-z]*)')::boolean AS autosummarize,
    substring(array_to_string(cls.reloptions, ',') FROM 'lists=([0-9]*)')::int AS lists
            , (cls.oid <= 16383::oid) AS is_sys_idx
    FROM pg_catalog.pg_index idx
    JOIN pg_catalog.pg_class cls ON cls.oid = idx.indexrelid
    JOIN pg_catalog.pg_class tab ON tab.oid = idx.indrelid
    LEFT JOIN pg_catalog.pg_tablespace ta ON ta.oid = cls.reltablespace
    JOIN pg_catalog.pg_namespace n ON n.oid = tab.relnamespace
    JOIN pg_catalog.pg_am am ON am.oid = cls.relam
    LEFT JOIN pg_catalog.pg_depend dep ON dep.classid = cls.tableoid
        AND dep.objid = cls.oid
        AND dep.refobjsubid = 0
        AND dep.refclassid = (SELECT oid FROM pg_catalog.pg_class WHERE relname = 'pg_constraint')
        AND dep.deptype = 'i'
    LEFT JOIN pg_catalog.pg_constraint con ON con.tableoid = dep.refclassid AND con.oid = dep.refobjid
    LEFT JOIN pg_catalog.pg_description des ON des.objoid = cls.oid AND des.classoid = 'pg_class'::regclass
    LEFT JOIN pg_catalog.pg_description desp ON desp.objoid = con.oid AND desp.objsubid = 0 AND desp.classoid = 'pg_constraint'::regclass
WHERE idx.indrelid = 16385::OID
        AND con.conname IS NULL
            AND cls.oid = 16392::OID
    ORDER BY cls.relname;
-----
-- [pgadmin]
-- postgres16: 1 47dd998d61fd
-- expect: 1  # the test server's OIDs
SELECT
    i.indexrelid,
    CASE i.indoption[i.attnum - 1]
    WHEN 0 THEN ARRAY['ASC', 'NULLS LAST']
    WHEN 1 THEN ARRAY['DESC', 'NULLS LAST']
    WHEN 2 THEN ARRAY['ASC', 'NULLS FIRST']
    WHEN 3 THEN ARRAY['DESC', 'NULLS FIRST']
    ELSE ARRAY['UNKNOWN OPTION' || i.indoption[i.attnum - 1]::text, '']
    END::text[] AS options,
    i.attnum,
    pg_catalog.pg_get_indexdef(i.indexrelid, i.attnum, true) as attdef,
    CASE WHEN pg_catalog.pg_get_indexdef(i.indexrelid, i.attnum, true) = a.attname THEN FALSE ELSE TRUE END AS is_exp,
    a.attstattarget as statistics,
    CASE WHEN (o.opcdefault = FALSE) THEN o.opcname ELSE null END AS opcname,
    op.oprname AS oprname,
CASE WHEN length(nspc.nspname::text) > 0 AND length(coll.collname::text) > 0  THEN
  pg_catalog.concat(pg_catalog.quote_ident(nspc.nspname), '.', pg_catalog.quote_ident(coll.collname))
ELSE '' END AS collnspname
FROM (
      SELECT
          indexrelid, i.indoption, i.indclass,
          pg_catalog.unnest(ARRAY(SELECT pg_catalog.generate_series(1, i.indnkeyatts) AS n)) AS attnum
      FROM
          pg_catalog.pg_index i
      WHERE i.indexrelid = 16392::OID
) i
    LEFT JOIN pg_catalog.pg_opclass o ON (o.oid = i.indclass[i.attnum - 1])
    LEFT OUTER JOIN pg_catalog.pg_constraint c ON (c.conindid = i.indexrelid)
    LEFT OUTER JOIN pg_catalog.pg_operator op ON (op.oid = c.conexclop[i.attnum])
    LEFT JOIN pg_catalog.pg_attribute a ON (a.attrelid = i.indexrelid AND a.attnum = i.attnum)
    LEFT OUTER JOIN pg_catalog.pg_collation coll ON a.attcollation=coll.oid
    LEFT OUTER JOIN pg_catalog.pg_namespace nspc ON coll.collnamespace=nspc.oid
ORDER BY i.attnum;
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT a.attname as colname
FROM (
    SELECT
      i.indnkeyatts,
      i.indrelid,
      pg_catalog.unnest(indkey) AS table_colnum,
      pg_catalog.unnest(ARRAY(SELECT pg_catalog.generate_series(1, i.indnatts) AS n)) attnum
    FROM
      pg_catalog.pg_index i
    WHERE i.indexrelid = 16392::OID
) i JOIN pg_catalog.pg_attribute a
ON (a.attrelid = i.indrelid AND i.table_colnum = a.attnum)
WHERE i.attnum > i.indnkeyatts
ORDER BY i.attnum
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT
    pl.oid AS oid,
    pl.polname AS name
FROM
    pg_catalog.pg_policy pl
WHERE
    pl.polrelid	 = 16385
ORDER BY
    pl.polname;
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT t.oid, t.tgname as name, t.tgenabled AS is_enable_trigger, des.description
FROM pg_catalog.pg_trigger t
    LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=t.oid AND des.classoid='pg_trigger'::regclass)
WHERE NOT tgisinternal
    AND tgrelid = 16385::OID
    ORDER BY tgname;
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT
    rw.oid AS oid,
    rw.rulename AS name,
    CASE WHEN rw.ev_enabled != 'D' THEN True ELSE False END AS enabled,
    rw.ev_enabled AS is_enable_rule,
    description AS comment
FROM
    pg_catalog.pg_rewrite rw
    LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=rw.oid AND des.classoid='pg_rewrite'::regclass)
WHERE
    rw.ev_class = 16385
ORDER BY
    rw.rulename
-----
-- [pgadmin]
-- postgres16: 1 efa1f375d761
-- expect: same
SELECT nsp.nspname FROM pg_catalog.pg_namespace nsp WHERE nsp.oid = 2200;
-----
-- [pgadmin]
-- postgres16: 1 252f10c83610
-- expect: same
SELECT CASE WHEN (SELECT(count(extname) > 0) AS is_pgstattuple
            FROM pg_catalog.pg_extension WHERE extname = 'pgstattuple')
        THEN (SELECT pg_catalog.has_table_privilege(current_user, 16385,
            'SELECT')) ELSE FALSE END
-----
-- [pgadmin]
-- postgres16: 1 5f3c4f8580d3
-- expect: same
SELECT
    rel.relname AS name
FROM
    pg_catalog.pg_class rel
WHERE
    rel.relkind IN ('r','s','t','p')
    AND rel.relnamespace = 2200::oid
    AND rel.oid = 16385::oid;
-----
-- [pgadmin]
-- postgres16: 1 384f7c49c174
-- expect: 1  # no statistics are gathered
SELECT
    seq_scan AS "Sequential scans",
    seq_tup_read AS "Sequential tuples read",
    idx_scan AS "Index scans",
    idx_tup_fetch AS "Index tuples fetched",
    n_tup_ins AS "Tuples inserted",
    n_tup_upd AS "Tuples updated",
    n_tup_del AS "Tuples deleted",
    n_tup_hot_upd AS "Tuples HOT updated",
    n_live_tup AS "Live tuples",
    n_dead_tup AS "Dead tuples",
    heap_blks_read AS "Heap blocks read",
    heap_blks_hit AS "Heap blocks hit",
    idx_blks_read AS "Index blocks read",
    idx_blks_hit AS "Index blocks hit",
    toast_blks_read AS "Toast blocks read",
    toast_blks_hit AS "Toast blocks hit",
    tidx_blks_read AS "Toast index blocks read",
    tidx_blks_hit AS "Toast index blocks hit",
    last_vacuum AS "Last vacuum",
    last_autovacuum AS "Last autovacuum",
    last_analyze AS "Last analyze",
    last_autoanalyze AS "Last autoanalyze",
    last_seq_scan AS "Last sequential scan",
    pg_catalog.pg_relation_size(stat.relid) AS "Table size",
    CASE WHEN cl.reltoastrelid = 0 THEN NULL ELSE pg_catalog.pg_size_pretty(pg_catalog.pg_relation_size(cl.reltoastrelid)
        + COALESCE((SELECT SUM(pg_catalog.pg_relation_size(indexrelid))
                        FROM pg_catalog.pg_index WHERE indrelid=cl.reltoastrelid)::int8, 0))
        END AS "Toast table size",
    COALESCE((SELECT SUM(pg_catalog.pg_relation_size(indexrelid))
                                FROM pg_catalog.pg_index WHERE indrelid=stat.relid)::int8, 0)
        AS "Indexes size"
FROM
    pg_catalog.pg_stat_all_tables stat
JOIN
    pg_catalog.pg_statio_all_tables statio ON stat.relid = statio.relid
JOIN
    pg_catalog.pg_class cl ON cl.oid=stat.relid
WHERE
    stat.relid = 16385::oid
-----
-- [pgadmin]
-- postgres16: 1 247ecb02dc81
-- expect: 1  # the test server's OIDs
SELECT DISTINCT dep.deptype, dep.refclassid, dep.refobjid, cl.relkind, ad.adbin, pg_catalog.pg_get_expr(ad.adbin, ad.adrelid) as adsrc,
    CASE WHEN cl.relkind IS NOT NULL THEN CASE WHEN cl.relkind = 'r' THEN cl.relkind::text || COALESCE(dep.refobjsubid::text, '') ELSE cl.relkind::text END
        WHEN tg.oid IS NOT NULL THEN 'Tr'::text
        WHEN ty.oid IS NOT NULL THEN CASE WHEN ty.typtype = 'd' THEN 'd'::text ELSE 'Ty'::text END
        WHEN ns.oid IS NOT NULL THEN 'n'::text
        WHEN pr.oid IS NOT NULL AND (prtyp.typname = 'trigger' OR prtyp.typname = 'event_trigger') THEN 'Pt'::text
        WHEN pr.oid IS NOT NULL THEN CASE WHEN pr.prokind = 'p' THEN 'Pp'::text ELSE 'Pf'::text END
        WHEN la.oid IS NOT NULL THEN 'l'::text
        WHEN rw.oid IS NOT NULL THEN 'Rl'::text
        WHEN co.oid IS NOT NULL THEN CASE WHEN co.contypid > 0 THEN 'Cd' ELSE 'C'::text || contype::text END
        WHEN ad.oid IS NOT NULL THEN 'A'::text
        WHEN fs.oid IS NOT NULL THEN 'Fs'::text
        WHEN fdw.oid IS NOT NULL THEN 'Fw'::text
        WHEN evt.oid IS NOT NULL THEN 'Et'::text
        WHEN col.oid IS NOT NULL THEN 'Co'::text
        WHEN ftsc.oid IS NOT NULL THEN 'Fc'::text
        WHEN ftsp.oid IS NOT NULL THEN 'Fp'::text
        WHEN ftsd.oid IS NOT NULL THEN 'Fd'::text
        WHEN ftst.oid IS NOT NULL THEN 'Ft'::text
        WHEN ext.oid IS NOT NULL THEN 'Ex'::text
        WHEN pl.oid IS NOT NULL THEN 'Rs'::text
    ELSE ''
    END AS type,
    COALESCE(coc.relname, clrw.relname) AS ownertable,
    CASE WHEN cl.relname IS NOT NULL OR att.attname IS NOT NULL THEN cl.relname || COALESCE('.' || att.attname, '')
    ELSE COALESCE(cl.relname, co.conname, pr.proname, tg.tgname, ty.typname, la.lanname, rw.rulename, ns.nspname,
                  fs.srvname, fdw.fdwname, evt.evtname, col.collname, ftsc.cfgname, ftsd.dictname, ftsp.prsname,
                  ftst.tmplname, ext.extname, pl.polname)
    END AS refname,
    COALESCE(nsc.nspname, nso.nspname, nsp.nspname, nst.nspname, nsrw.nspname, colns.nspname, ftscns.nspname,
        ftsdns.nspname, ftspns.nspname, ftstns.nspname) AS nspname,
    CASE WHEN inhits.inhparent IS NOT NULL THEN '1' ELSE '0' END AS is_inherits,
    CASE WHEN inhed.inhparent IS NOT NULL THEN '1' ELSE '0' END AS is_inherited
FROM pg_catalog.pg_depend dep
LEFT JOIN pg_catalog.pg_class cl ON dep.refobjid=cl.oid
LEFT JOIN pg_catalog.pg_attribute att ON dep.refobjid=att.attrelid AND dep.refobjsubid=att.attnum
LEFT JOIN pg_catalog.pg_namespace nsc ON cl.relnamespace=nsc.oid
LEFT JOIN pg_catalog.pg_proc pr ON dep.refobjid=pr.oid
LEFT JOIN pg_catalog.pg_namespace nsp ON pr.pronamespace=nsp.oid
LEFT JOIN pg_catalog.pg_trigger tg ON dep.refobjid=tg.oid
LEFT JOIN pg_catalog.pg_type ty ON dep.refobjid=ty.oid
LEFT JOIN pg_catalog.pg_namespace nst ON ty.typnamespace=nst.oid
LEFT JOIN pg_catalog.pg_constraint co ON dep.refobjid=co.oid
LEFT JOIN pg_catalog.pg_class coc ON co.conrelid=coc.oid
LEFT JOIN pg_catalog.pg_namespace nso ON co.connamespace=nso.oid
LEFT JOIN pg_catalog.pg_rewrite rw ON dep.refobjid=rw.oid
LEFT JOIN pg_catalog.pg_class clrw ON clrw.oid=rw.ev_class
LEFT JOIN pg_catalog.pg_namespace nsrw ON clrw.relnamespace=nsrw.oid
LEFT JOIN pg_catalog.pg_language la ON dep.refobjid=la.oid
LEFT JOIN pg_catalog.pg_namespace ns ON dep.refobjid=ns.oid
LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid=att.attrelid AND ad.adnum=att.attnum
LEFT JOIN pg_catalog.pg_foreign_server fs ON fs.oid=dep.refobjid
LEFT JOIN pg_catalog.pg_foreign_data_wrapper fdw ON fdw.oid=dep.refobjid
LEFT JOIN pg_catalog.pg_type prtyp ON prtyp.oid = pr.prorettype
LEFT JOIN pg_catalog.pg_inherits inhits ON (inhits.inhrelid=dep.refobjid)
LEFT JOIN pg_catalog.pg_inherits inhed ON (inhed.inhparent=dep.refobjid)
LEFT JOIN pg_catalog.pg_event_trigger evt ON evt.oid=dep.refobjid
LEFT JOIN pg_catalog.pg_collation col ON col.oid=dep.refobjid
LEFT JOIN pg_catalog.pg_namespace colns ON col.collnamespace=colns.oid
LEFT JOIN pg_catalog.pg_ts_config ftsc ON ftsc.oid=dep.refobjid
LEFT JOIN pg_catalog.pg_namespace ftscns ON ftsc.cfgnamespace=ftscns.oid
LEFT JOIN pg_catalog.pg_ts_dict ftsd ON ftsd.oid=dep.refobjid
LEFT JOIN pg_catalog.pg_namespace ftsdns ON ftsd.dictnamespace=ftsdns.oid
LEFT JOIN pg_catalog.pg_ts_parser ftsp ON ftsp.oid=dep.refobjid
LEFT JOIN pg_catalog.pg_namespace ftspns ON ftsp.prsnamespace=ftspns.oid
LEFT JOIN pg_catalog.pg_ts_template ftst ON ftst.oid=dep.refobjid
LEFT JOIN pg_catalog.pg_namespace ftstns ON ftst.tmplnamespace=ftstns.oid
LEFT JOIN pg_catalog.pg_extension ext ON ext.oid=dep.refobjid
LEFT JOIN pg_catalog.pg_policy pl ON pl.oid=dep.refobjid
WHERE dep.objid=16385::oid AND
refclassid IN ( SELECT oid FROM pg_catalog.pg_class WHERE relname IN
   ('pg_class', 'pg_constraint', 'pg_conversion', 'pg_language', 'pg_proc', 'pg_rewrite', 'pg_namespace',
   'pg_trigger', 'pg_type', 'pg_attrdef', 'pg_event_trigger', 'pg_foreign_server', 'pg_foreign_data_wrapper',
   'pg_collation', 'pg_ts_config', 'pg_ts_dict', 'pg_ts_parser', 'pg_ts_template', 'pg_extension', 'pg_policy'))
UNION
SELECT DISTINCT dep.deptype, dep.refclassid, dep.refobjid, cl.relkind, ad.adbin, pg_catalog.pg_get_expr(ad.adbin, ad.adrelid) as adsrc,
    CASE WHEN cl.relkind IS NOT NULL THEN CASE WHEN cl.relkind = 'r' THEN cl.relkind::text || COALESCE(dep.refobjsubid::text, '') ELSE cl.relkind::text END
    ELSE '' END AS type,
NULL AS ownertable,
CASE WHEN cl.relname IS NOT NULL OR att.attname IS NOT NULL THEN cl.relname || COALESCE('.' || att.attname, '')
    ELSE cl.relname END AS refname,
    nsc.nspname AS nspname, '0' AS is_inherits, '0' AS is_inherited
FROM pg_catalog.pg_depend dep
LEFT JOIN pg_catalog.pg_class cl ON dep.refobjid=cl.oid
LEFT JOIN pg_catalog.pg_attribute att ON dep.refobjid=att.attrelid AND dep.refobjsubid=att.attnum
LEFT JOIN pg_catalog.pg_namespace nsc ON cl.relnamespace=nsc.oid
LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid=att.attrelid AND ad.adnum=att.attnum
WHERE dep.objid IN (SELECT oid FROM pg_catalog.pg_rewrite WHERE ev_class=16385) AND cl.relkind not in ('v', 'm')
ORDER BY refclassid, relkind
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT rolname AS refname, refclassid, deptype
FROM pg_catalog.pg_shdepend dep
LEFT JOIN pg_catalog.pg_roles r ON refclassid=1260 AND refobjid=r.oid
WHERE dep.objid=16385::oid
 AND dep.dbid = (SELECT oid FROM pg_catalog.pg_database WHERE datname = 'audit')
ORDER BY 1
-----
-- [pgadmin]
-- postgres16: 4 ae27a88e8b7c
-- expect: 3  # no TOAST table
SELECT DISTINCT dep.deptype, dep.classid, cl.relkind, ad.adbin, pg_get_expr(ad.adbin, ad.adrelid) as adsrc,
    CASE WHEN cl.relkind IS NOT NULL THEN CASE WHEN cl.relkind = 'r' THEN cl.relkind::text || COALESCE(dep.objsubid::text, '') ELSE cl.relkind::text END
        WHEN tg.oid IS NOT NULL THEN 'Tr'::text
        WHEN ty.oid IS NOT NULL THEN CASE WHEN ty.typtype = 'd' THEN 'd'::text ELSE 'Ty'::text END
        WHEN ns.oid IS NOT NULL THEN 'n'::text
        WHEN pr.oid IS NOT NULL AND (prtyp.typname = 'trigger' OR prtyp.typname = 'event_trigger') THEN 'Pt'::text
        WHEN pr.oid IS NOT NULL THEN CASE WHEN pr.prokind = 'p' THEN 'Pp'::text ELSE 'Pf'::text END
        WHEN la.oid IS NOT NULL THEN 'l'::text
        WHEN rw.oid IS NOT NULL THEN 'Rl'::text
        WHEN co.oid IS NOT NULL THEN CASE WHEN co.contypid > 0 THEN 'Cd' ELSE 'C'::text || contype::text END
        WHEN ad.oid IS NOT NULL THEN 'A'::text
        WHEN fs.oid IS NOT NULL THEN 'Fs'::text
        WHEN fdw.oid IS NOT NULL THEN 'Fw'::text
        WHEN evt.oid IS NOT NULL THEN 'Et'::text
        WHEN col.oid IS NOT NULL THEN 'Co'::text
        WHEN ftsc.oid IS NOT NULL THEN 'Fc'::text
        WHEN ftsp.oid IS NOT NULL THEN 'Fp'::text
        WHEN ftsd.oid IS NOT NULL THEN 'Fd'::text
        WHEN ftst.oid IS NOT NULL THEN 'Ft'::text
        WHEN ext.oid IS NOT NULL THEN 'Ex'::text
        WHEN pl.oid IS NOT NULL THEN 'Rs'::text
    ELSE ''
    END AS type,
    COALESCE(coc.relname, clrw.relname) AS ownertable,
    CASE WHEN cl.relname IS NOT NULL AND att.attname IS NOT NULL THEN cl.relname || COALESCE('.' || att.attname, '')
    ELSE COALESCE(cl.relname, co.conname, pr.proname, tg.tgname, ty.typname, la.lanname, rw.rulename, ns.nspname,
                  fs.srvname, fdw.fdwname, evt.evtname, col.collname, ftsc.cfgname, ftsd.dictname, ftsp.prsname,
                  ftst.tmplname, ext.extname, pl.polname)
    END AS refname,
    COALESCE(nsc.nspname, nso.nspname, nsp.nspname, nst.nspname, nsrw.nspname, colns.nspname, ftscns.nspname,
        ftsdns.nspname, ftspns.nspname, ftstns.nspname) AS nspname,
    CASE WHEN inhits.inhparent IS NOT NULL THEN '1' ELSE '0' END AS is_inherits,
    CASE WHEN inhed.inhparent IS NOT NULL THEN '1' ELSE '0' END AS is_inherited
FROM pg_catalog.pg_depend dep
LEFT JOIN pg_catalog.pg_class cl ON dep.objid=cl.oid
LEFT JOIN pg_catalog.pg_attribute att ON dep.objid=att.attrelid AND dep.objsubid=att.attnum
LEFT JOIN pg_catalog.pg_namespace nsc ON cl.relnamespace=nsc.oid
LEFT JOIN pg_catalog.pg_proc pr ON dep.objid=pr.oid
LEFT JOIN pg_catalog.pg_namespace nsp ON pr.pronamespace=nsp.oid
LEFT JOIN pg_catalog.pg_trigger tg ON dep.objid=tg.oid
LEFT JOIN pg_catalog.pg_type ty ON dep.objid=ty.oid
LEFT JOIN pg_catalog.pg_namespace nst ON ty.typnamespace=nst.oid
LEFT JOIN pg_catalog.pg_constraint co ON dep.objid=co.oid
LEFT JOIN pg_catalog.pg_class coc ON co.conrelid=coc.oid
LEFT JOIN pg_catalog.pg_namespace nso ON co.connamespace=nso.oid
LEFT JOIN pg_catalog.pg_rewrite rw ON dep.objid=rw.oid
LEFT JOIN pg_catalog.pg_class clrw ON clrw.oid=rw.ev_class
LEFT JOIN pg_catalog.pg_namespace nsrw ON clrw.relnamespace=nsrw.oid
LEFT JOIN pg_catalog.pg_language la ON dep.objid=la.oid
LEFT JOIN pg_catalog.pg_namespace ns ON dep.objid=ns.oid
LEFT JOIN pg_catalog.pg_attrdef ad ON ad.oid=dep.objid
LEFT JOIN pg_catalog.pg_foreign_server fs ON fs.oid=dep.objid
LEFT JOIN pg_catalog.pg_foreign_data_wrapper fdw ON fdw.oid=dep.objid
LEFT JOIN pg_catalog.pg_type prtyp ON prtyp.oid = pr.prorettype
LEFT JOIN pg_catalog.pg_inherits inhits ON (inhits.inhrelid=dep.objid)
LEFT JOIN pg_catalog.pg_inherits inhed ON (inhed.inhparent=dep.objid)
LEFT JOIN pg_catalog.pg_event_trigger evt ON evt.oid=dep.objid
LEFT JOIN pg_catalog.pg_collation col ON col.oid=dep.objid
LEFT JOIN pg_catalog.pg_namespace colns ON col.collnamespace=colns.oid
LEFT JOIN pg_catalog.pg_ts_config ftsc ON ftsc.oid=dep.objid
LEFT JOIN pg_catalog.pg_namespace ftscns ON ftsc.cfgnamespace=ftscns.oid
LEFT JOIN pg_catalog.pg_ts_dict ftsd ON ftsd.oid=dep.objid
LEFT JOIN pg_catalog.pg_namespace ftsdns ON ftsd.dictnamespace=ftsdns.oid
LEFT JOIN pg_catalog.pg_ts_parser ftsp ON ftsp.oid=dep.objid
LEFT JOIN pg_catalog.pg_namespace ftspns ON ftsp.prsnamespace=ftspns.oid
LEFT JOIN pg_catalog.pg_ts_template ftst ON ftst.oid=dep.objid
LEFT JOIN pg_catalog.pg_namespace ftstns ON ftst.tmplnamespace=ftstns.oid
LEFT JOIN pg_catalog.pg_extension ext ON ext.oid=dep.objid
LEFT JOIN pg_catalog.pg_policy pl ON pl.oid=dep.objid
WHERE dep.refobjid=16385::oid AND
classid IN ( SELECT oid FROM pg_catalog.pg_class WHERE relname IN
   ('pg_class', 'pg_constraint', 'pg_conversion', 'pg_language', 'pg_proc', 'pg_rewrite', 'pg_namespace',
   'pg_trigger', 'pg_type', 'pg_attrdef', 'pg_event_trigger', 'pg_foreign_server', 'pg_foreign_data_wrapper',
   'pg_collation', 'pg_ts_config', 'pg_ts_dict', 'pg_ts_parser', 'pg_ts_template', 'pg_extension', 'pg_policy'))
ORDER BY classid, cl.relkind
-----
-- [pgadmin]
-- postgres16: 0 e3b0c44298fc
-- expect: same
SELECT
    ref.relname AS refname, d2.refclassid, dep.deptype AS deptype
FROM pg_catalog.pg_depend dep
    LEFT JOIN pg_catalog.pg_depend d2 ON dep.objid=d2.objid AND dep.refobjid != d2.refobjid
    LEFT JOIN pg_catalog.pg_class ref ON ref.oid=d2.refobjid
    LEFT JOIN pg_catalog.pg_attribute att ON d2.refclassid=att.attrelid AND d2.refobjsubid=att.attnum
    WHERE dep.refobjid=16385::OID AND
    dep.classid=(SELECT oid FROM pg_catalog.pg_class WHERE relname='pg_attrdef') AND
    dep.refobjid NOT IN (SELECT d3.refobjid FROM pg_catalog.pg_depend d3 WHERE d3.objid=d2.refobjid)
-----
-- [pgadmin]
-- postgres16: 1 edc38e5ed235
-- expect: same
WITH INH_TABLES AS
    (SELECT
     at.attname AS name, ph.inhparent AS inheritedid, ph.inhseqno,
     pg_catalog.concat(nmsp_parent.nspname, '.',parent.relname ) AS inheritedfrom
    FROM
        pg_catalog.pg_attribute at
    JOIN
        pg_catalog.pg_inherits ph ON ph.inhparent = at.attrelid AND ph.inhrelid = 33896::oid
    JOIN
        pg_catalog.pg_class parent ON ph.inhparent  = parent.oid
    JOIN
        pg_catalog.pg_namespace nmsp_parent ON nmsp_parent.oid  = parent.relnamespace
    GROUP BY at.attname, ph.inhparent, ph.inhseqno, inheritedfrom
    ORDER BY at.attname, ph.inhparent, ph.inhseqno, inheritedfrom
    )
SELECT DISTINCT ON (att.attnum) att.attname as name, att.atttypid, att.attlen, att.attnum, att.attndims,
att.atttypmod, att.attacl, att.attnotnull, att.attoptions, att.attfdwoptions, att.attstattarget,
att.attstorage, att.attidentity,
pg_catalog.pg_get_expr(def.adbin, def.adrelid) AS defval,
pg_catalog.format_type(ty.oid,NULL) AS typname,
        pg_catalog.format_type(ty.oid,att.atttypmod) AS displaytypname,
pg_catalog.format_type(ty.oid,att.atttypmod) AS cltype,
inh.inheritedfrom,
inh.inheritedid,
        CASE WHEN ty.typelem > 0 THEN ty.typelem ELSE ty.oid END as elemoid,
(SELECT nspname FROM pg_catalog.pg_namespace WHERE oid = ty.typnamespace) as typnspname,
        ty.typstorage AS defaultstorage,
description, pi.indkey,
(SELECT count(1) FROM pg_catalog.pg_type t2 WHERE t2.typname=ty.typname) > 1 AS isdup,
CASE WHEN length(coll.collname::text) > 0 AND length(nspc.nspname::text) > 0  THEN
  pg_catalog.concat(pg_catalog.quote_ident(nspc.nspname),'.',pg_catalog.quote_ident(coll.collname))
ELSE '' END AS collspcname,
EXISTS(SELECT 1 FROM pg_catalog.pg_constraint WHERE conrelid=att.attrelid AND contype='f' AND att.attnum=ANY(conkey)) As is_fk,
(SELECT pg_catalog.array_agg(provider || '=' || label) FROM pg_catalog.pg_seclabels sl1 WHERE sl1.objoid=att.attrelid AND sl1.objsubid=att.attnum) AS seclabels,
(CASE WHEN (att.attnum < 1) THEN true ElSE false END) AS is_sys_column,
(CASE WHEN (att.attidentity in ('a', 'd')) THEN 'i' WHEN (att.attgenerated in ('s')) THEN 'g' ELSE 'n' END) AS colconstype,
(CASE WHEN (att.attgenerated in ('s')) THEN pg_catalog.pg_get_expr(def.adbin, def.adrelid) END) AS genexpr, tab.relname as relname,
(CASE WHEN tab.relkind = 'v' THEN true ELSE false END) AS is_view_only,
(CASE WHEN att.attcompression = 'p' THEN 'pglz' WHEN att.attcompression = 'l' THEN 'lz4' END) AS attcompression,
(SELECT dsd.refobjid
 FROM pg_catalog.pg_depend dsd
   JOIN pg_catalog.pg_class dsc ON dsd.refclassid='pg_catalog.pg_class'::regclass
     AND dsd.refobjid=dsc.oid AND dsc.relkind='S'
 WHERE dsd.classid='pg_catalog.pg_attrdef'::regclass
   AND dsd.objid=def.oid AND dsd.deptype='n'
 ORDER BY dsd.refobjid
 LIMIT 1) AS defseqrelid,
seq.*
FROM pg_catalog.pg_attribute att
  JOIN pg_catalog.pg_type ty ON ty.oid=atttypid
  LEFT OUTER JOIN pg_catalog.pg_attrdef def ON adrelid=att.attrelid AND adnum=att.attnum
  LEFT OUTER JOIN pg_catalog.pg_description des ON (des.objoid=att.attrelid AND des.objsubid=att.attnum AND des.classoid='pg_class'::regclass)
  LEFT OUTER JOIN (pg_catalog.pg_depend dep JOIN pg_catalog.pg_class cs ON dep.classid='pg_class'::regclass AND dep.objid=cs.oid AND cs.relkind='S') ON dep.refobjid=att.attrelid AND dep.refobjsubid=att.attnum
  LEFT OUTER JOIN pg_catalog.pg_index pi ON pi.indrelid=att.attrelid AND indisprimary
  LEFT OUTER JOIN pg_catalog.pg_collation coll ON att.attcollation=coll.oid
  LEFT OUTER JOIN pg_catalog.pg_namespace nspc ON coll.collnamespace=nspc.oid
  LEFT OUTER JOIN pg_catalog.pg_sequence seq ON cs.oid=seq.seqrelid
  LEFT OUTER JOIN pg_catalog.pg_class tab on tab.oid = att.attrelid
  LEFT OUTER join INH_TABLES as INH ON att.attname = INH.name
WHERE att.attrelid = 16385::oid
    AND att.attnum = 1::int
    AND att.attnum > 0
    AND att.attisdropped IS FALSE
    ORDER BY att.attnum;
-----
-- [pgadmin]
-- postgres16: 878 16909a003adb
-- expect: 4  # the collations COLLATE accepts
SELECT --nspname, collname,
    CASE WHEN length(nspname::text) > 0 AND length(collname::text) > 0  THEN
        pg_catalog.concat(pg_catalog.quote_ident(nspname), '.', pg_catalog.quote_ident(collname))
    ELSE '' END AS copy_collation
FROM pg_catalog.pg_collation c, pg_catalog.pg_namespace n
WHERE c.collnamespace=n.oid
ORDER BY nspname, collname;
-----
-- [pgadmin]
-- postgres16: 162 9b87cff3c47d
-- expect: 62  # only the types the frontend implements
SELECT
    *
FROM
    (SELECT
        pg_catalog.format_type(t.oid,NULL) AS typname,
        CASE WHEN typelem > 0 THEN typelem ELSE t.oid END as elemoid,
        typlen, typtype, t.oid, nspname,
        (SELECT COUNT(1) FROM pg_catalog.pg_type t2 WHERE t2.typname = t.typname) > 1 AS isdup,
        CASE WHEN t.typcollation != 0 THEN TRUE ELSE FALSE END AS is_collatable
    FROM
        pg_catalog.pg_type t
    JOIN
        pg_catalog.pg_namespace nsp ON typnamespace=nsp.oid
    WHERE
        (NOT (typname = 'unknown' AND nspname = 'pg_catalog'))
    AND
        typisdefined AND typtype IN ('b', 'c', 'd', 'e', 'r', 'm')
AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_class WHERE relnamespace=typnamespace
AND relname = typname AND relkind != 'c') AND
(typname NOT LIKE '_%' OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_class WHERE
relnamespace=typnamespace AND relname = substring(typname FROM 2)::name
AND relkind != 'c'))
AND nsp.nspname != 'information_schema'

    UNION SELECT 'smallserial', 0, 2, 'b', 0, 'pg_catalog', false, false
    UNION SELECT 'bigserial', 0, 8, 'b', 0, 'pg_catalog', false, false
    UNION SELECT 'serial', 0, 4, 'b', 0, 'pg_catalog', false, false
    ) AS dummy
ORDER BY nspname <> 'pg_catalog', nspname <> 'public', nspname, 1
-----
-- [pgadmin]
-- postgres16: 1 3e1212c10e4a
-- expect: same
SELECT n.nspname, r.relname
FROM pg_catalog.pg_class r
    LEFT JOIN pg_catalog.pg_namespace n ON (r.relnamespace = n.oid)
WHERE r.oid = 16385;
-----
-- [pgadmin]
-- postgres16: 1 cb6417df03c0
-- expect: same
SELECT at.attname, at.attnum, ty.typname
FROM pg_catalog.pg_attribute at LEFT JOIN pg_catalog.pg_type ty ON (ty.oid = at.atttypid)
JOIN pg_catalog.pg_class as cl ON cl.oid=AT.attrelid
JOIN pg_catalog.pg_namespace as nsp ON nsp.oid=cl.relnamespace
WHERE
    cl.relname = 'items' AND nsp.nspname='public' AND
attnum = ANY (
(SELECT con.conkey FROM pg_catalog.pg_class rel LEFT OUTER JOIN pg_catalog.pg_constraint con ON con.conrelid=rel.oid
JOIN pg_catalog.pg_namespace as nsp ON nsp.oid=REL.relnamespace
AND con.contype='p' WHERE rel.relkind IN ('r','s','t', 'p') AND
    rel.relname = 'items' AND nsp.nspname='public'
)::oid[])
-----
-- [pgadmin]
-- postgres16: 5 a2d3d8623866
-- expect: same
SELECT oid, pg_catalog.format_type(oid, NULL) AS typname FROM pg_catalog.pg_type WHERE oid = ANY('{23,1043,1700,21,25}') ORDER BY oid;
-----
