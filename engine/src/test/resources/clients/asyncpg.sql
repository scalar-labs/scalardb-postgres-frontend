-- asyncpg 0.32.0: type introspection for a prepared statement with parameters, captured from PostgreSQL 16's statement log
-- Database audit with difftest/catalog/schema.sql. postgres16: PostgreSQL 16.15's answer (rows, digest; difftest/catalog/replay.py); expect: the frontend's. Relation OIDs are PostgreSQL 16's, listed in oids.
-- source: postgres16
-- oids: public.items=16385 public.items_name_idx=16392 public.items_pkey=16390 sales.orders=16394 sales.orders_pkey=16397
-- [asyncpg]
-- postgres16: 2 2e576cef02c5
WITH RECURSIVE typeinfo_tree(
    oid, ns, name, kind, basetype, elemtype, elemdelim,
    range_subtype, attrtypoids, attrnames, depth)
AS (
    SELECT
        ti.oid, ti.ns, ti.name, ti.kind, ti.basetype,
        ti.elemtype, ti.elemdelim, ti.range_subtype,
        ti.attrtypoids, ti.attrnames, 0
    FROM
            (
        SELECT
            t.oid                           AS oid,
            ns.nspname                      AS ns,
            t.typname                       AS name,
            t.typtype                       AS kind,
            (CASE WHEN t.typtype = 'd' THEN
                (WITH RECURSIVE typebases(oid, depth) AS (
                    SELECT
                        t2.typbasetype      AS oid,
                        0                   AS depth
                    FROM
                        pg_type t2
                    WHERE
                        t2.oid = t.oid

                    UNION ALL

                    SELECT
                        t2.typbasetype      AS oid,
                        tb.depth + 1        AS depth
                    FROM
                        pg_type t2,
                        typebases tb
                    WHERE
                       tb.oid = t2.oid
                       AND t2.typbasetype != 0
               ) SELECT oid FROM typebases ORDER BY depth DESC LIMIT 1)

               ELSE NULL
            END)                            AS basetype,
            t.typelem                       AS elemtype,
            elem_t.typdelim                 AS elemdelim,
            COALESCE(
                range_t.rngsubtype,
                multirange_t.rngsubtype)    AS range_subtype,
            (CASE WHEN t.typtype = 'c' THEN
                (SELECT
                    array_agg(ia.atttypid ORDER BY ia.attnum)
                FROM
                    pg_attribute ia
                    INNER JOIN pg_class c
                        ON (ia.attrelid = c.oid)
                WHERE
                    ia.attnum > 0 AND NOT ia.attisdropped
                    AND c.reltype = t.oid)

                ELSE NULL
            END)                            AS attrtypoids,
            (CASE WHEN t.typtype = 'c' THEN
                (SELECT
                    array_agg(ia.attname::text ORDER BY ia.attnum)
                FROM
                    pg_attribute ia
                    INNER JOIN pg_class c
                        ON (ia.attrelid = c.oid)
                WHERE
                    ia.attnum > 0 AND NOT ia.attisdropped
                    AND c.reltype = t.oid)

                ELSE NULL
            END)                            AS attrnames
        FROM
            pg_catalog.pg_type AS t
            INNER JOIN pg_catalog.pg_namespace ns ON (
                ns.oid = t.typnamespace)
            LEFT JOIN pg_type elem_t ON (
                t.typlen = -1 AND
                t.typelem != 0 AND
                t.typelem = elem_t.oid
            )
            LEFT JOIN pg_range range_t ON (
                t.oid = range_t.rngtypid
            )
            LEFT JOIN pg_range multirange_t ON (
                t.oid = multirange_t.rngmultitypid
            )
    )
 AS ti
    WHERE
        ti.oid = any('{1007}'::oid[])

    UNION ALL

    SELECT
        ti.oid, ti.ns, ti.name, ti.kind, ti.basetype,
        ti.elemtype, ti.elemdelim, ti.range_subtype,
        ti.attrtypoids, ti.attrnames, tt.depth + 1
    FROM
            (
        SELECT
            t.oid                           AS oid,
            ns.nspname                      AS ns,
            t.typname                       AS name,
            t.typtype                       AS kind,
            (CASE WHEN t.typtype = 'd' THEN
                (WITH RECURSIVE typebases(oid, depth) AS (
                    SELECT
                        t2.typbasetype      AS oid,
                        0                   AS depth
                    FROM
                        pg_type t2
                    WHERE
                        t2.oid = t.oid

                    UNION ALL

                    SELECT
                        t2.typbasetype      AS oid,
                        tb.depth + 1        AS depth
                    FROM
                        pg_type t2,
                        typebases tb
                    WHERE
                       tb.oid = t2.oid
                       AND t2.typbasetype != 0
               ) SELECT oid FROM typebases ORDER BY depth DESC LIMIT 1)

               ELSE NULL
            END)                            AS basetype,
            t.typelem                       AS elemtype,
            elem_t.typdelim                 AS elemdelim,
            COALESCE(
                range_t.rngsubtype,
                multirange_t.rngsubtype)    AS range_subtype,
            (CASE WHEN t.typtype = 'c' THEN
                (SELECT
                    array_agg(ia.atttypid ORDER BY ia.attnum)
                FROM
                    pg_attribute ia
                    INNER JOIN pg_class c
                        ON (ia.attrelid = c.oid)
                WHERE
                    ia.attnum > 0 AND NOT ia.attisdropped
                    AND c.reltype = t.oid)

                ELSE NULL
            END)                            AS attrtypoids,
            (CASE WHEN t.typtype = 'c' THEN
                (SELECT
                    array_agg(ia.attname::text ORDER BY ia.attnum)
                FROM
                    pg_attribute ia
                    INNER JOIN pg_class c
                        ON (ia.attrelid = c.oid)
                WHERE
                    ia.attnum > 0 AND NOT ia.attisdropped
                    AND c.reltype = t.oid)

                ELSE NULL
            END)                            AS attrnames
        FROM
            pg_catalog.pg_type AS t
            INNER JOIN pg_catalog.pg_namespace ns ON (
                ns.oid = t.typnamespace)
            LEFT JOIN pg_type elem_t ON (
                t.typlen = -1 AND
                t.typelem != 0 AND
                t.typelem = elem_t.oid
            )
            LEFT JOIN pg_range range_t ON (
                t.oid = range_t.rngtypid
            )
            LEFT JOIN pg_range multirange_t ON (
                t.oid = multirange_t.rngmultitypid
            )
    )
 ti,
        typeinfo_tree tt
    WHERE
        (tt.elemtype IS NOT NULL AND ti.oid = tt.elemtype)
        OR (tt.attrtypoids IS NOT NULL AND ti.oid = any(tt.attrtypoids))
        OR (tt.range_subtype IS NOT NULL AND ti.oid = tt.range_subtype)
        OR (tt.basetype IS NOT NULL AND ti.oid = tt.basetype)
)

SELECT DISTINCT
    *,
    basetype::regtype::text AS basetype_name,
    elemtype::regtype::text AS elemtype_name,
    range_subtype::regtype::text AS range_subtype_name
FROM
    typeinfo_tree
ORDER BY
    depth DESC
-----
-- [asyncpg]
-- postgres16: 2 e2a3fdd0d24f
WITH RECURSIVE typeinfo_tree(
    oid, ns, name, kind, basetype, elemtype, elemdelim,
    range_subtype, attrtypoids, attrnames, depth)
AS (
    SELECT
        ti.oid, ti.ns, ti.name, ti.kind, ti.basetype,
        ti.elemtype, ti.elemdelim, ti.range_subtype,
        ti.attrtypoids, ti.attrnames, 0
    FROM
            (
        SELECT
            t.oid                           AS oid,
            ns.nspname                      AS ns,
            t.typname                       AS name,
            t.typtype                       AS kind,
            (CASE WHEN t.typtype = 'd' THEN
                (WITH RECURSIVE typebases(oid, depth) AS (
                    SELECT
                        t2.typbasetype      AS oid,
                        0                   AS depth
                    FROM
                        pg_type t2
                    WHERE
                        t2.oid = t.oid

                    UNION ALL

                    SELECT
                        t2.typbasetype      AS oid,
                        tb.depth + 1        AS depth
                    FROM
                        pg_type t2,
                        typebases tb
                    WHERE
                       tb.oid = t2.oid
                       AND t2.typbasetype != 0
               ) SELECT oid FROM typebases ORDER BY depth DESC LIMIT 1)

               ELSE NULL
            END)                            AS basetype,
            t.typelem                       AS elemtype,
            elem_t.typdelim                 AS elemdelim,
            COALESCE(
                range_t.rngsubtype,
                multirange_t.rngsubtype)    AS range_subtype,
            (CASE WHEN t.typtype = 'c' THEN
                (SELECT
                    array_agg(ia.atttypid ORDER BY ia.attnum)
                FROM
                    pg_attribute ia
                    INNER JOIN pg_class c
                        ON (ia.attrelid = c.oid)
                WHERE
                    ia.attnum > 0 AND NOT ia.attisdropped
                    AND c.reltype = t.oid)

                ELSE NULL
            END)                            AS attrtypoids,
            (CASE WHEN t.typtype = 'c' THEN
                (SELECT
                    array_agg(ia.attname::text ORDER BY ia.attnum)
                FROM
                    pg_attribute ia
                    INNER JOIN pg_class c
                        ON (ia.attrelid = c.oid)
                WHERE
                    ia.attnum > 0 AND NOT ia.attisdropped
                    AND c.reltype = t.oid)

                ELSE NULL
            END)                            AS attrnames
        FROM
            pg_catalog.pg_type AS t
            INNER JOIN pg_catalog.pg_namespace ns ON (
                ns.oid = t.typnamespace)
            LEFT JOIN pg_type elem_t ON (
                t.typlen = -1 AND
                t.typelem != 0 AND
                t.typelem = elem_t.oid
            )
            LEFT JOIN pg_range range_t ON (
                t.oid = range_t.rngtypid
            )
            LEFT JOIN pg_range multirange_t ON (
                t.oid = multirange_t.rngmultitypid
            )
    )
 AS ti
    WHERE
        ti.oid = any('{22}'::oid[])

    UNION ALL

    SELECT
        ti.oid, ti.ns, ti.name, ti.kind, ti.basetype,
        ti.elemtype, ti.elemdelim, ti.range_subtype,
        ti.attrtypoids, ti.attrnames, tt.depth + 1
    FROM
            (
        SELECT
            t.oid                           AS oid,
            ns.nspname                      AS ns,
            t.typname                       AS name,
            t.typtype                       AS kind,
            (CASE WHEN t.typtype = 'd' THEN
                (WITH RECURSIVE typebases(oid, depth) AS (
                    SELECT
                        t2.typbasetype      AS oid,
                        0                   AS depth
                    FROM
                        pg_type t2
                    WHERE
                        t2.oid = t.oid

                    UNION ALL

                    SELECT
                        t2.typbasetype      AS oid,
                        tb.depth + 1        AS depth
                    FROM
                        pg_type t2,
                        typebases tb
                    WHERE
                       tb.oid = t2.oid
                       AND t2.typbasetype != 0
               ) SELECT oid FROM typebases ORDER BY depth DESC LIMIT 1)

               ELSE NULL
            END)                            AS basetype,
            t.typelem                       AS elemtype,
            elem_t.typdelim                 AS elemdelim,
            COALESCE(
                range_t.rngsubtype,
                multirange_t.rngsubtype)    AS range_subtype,
            (CASE WHEN t.typtype = 'c' THEN
                (SELECT
                    array_agg(ia.atttypid ORDER BY ia.attnum)
                FROM
                    pg_attribute ia
                    INNER JOIN pg_class c
                        ON (ia.attrelid = c.oid)
                WHERE
                    ia.attnum > 0 AND NOT ia.attisdropped
                    AND c.reltype = t.oid)

                ELSE NULL
            END)                            AS attrtypoids,
            (CASE WHEN t.typtype = 'c' THEN
                (SELECT
                    array_agg(ia.attname::text ORDER BY ia.attnum)
                FROM
                    pg_attribute ia
                    INNER JOIN pg_class c
                        ON (ia.attrelid = c.oid)
                WHERE
                    ia.attnum > 0 AND NOT ia.attisdropped
                    AND c.reltype = t.oid)

                ELSE NULL
            END)                            AS attrnames
        FROM
            pg_catalog.pg_type AS t
            INNER JOIN pg_catalog.pg_namespace ns ON (
                ns.oid = t.typnamespace)
            LEFT JOIN pg_type elem_t ON (
                t.typlen = -1 AND
                t.typelem != 0 AND
                t.typelem = elem_t.oid
            )
            LEFT JOIN pg_range range_t ON (
                t.oid = range_t.rngtypid
            )
            LEFT JOIN pg_range multirange_t ON (
                t.oid = multirange_t.rngmultitypid
            )
    )
 ti,
        typeinfo_tree tt
    WHERE
        (tt.elemtype IS NOT NULL AND ti.oid = tt.elemtype)
        OR (tt.attrtypoids IS NOT NULL AND ti.oid = any(tt.attrtypoids))
        OR (tt.range_subtype IS NOT NULL AND ti.oid = tt.range_subtype)
        OR (tt.basetype IS NOT NULL AND ti.oid = tt.basetype)
)

SELECT DISTINCT
    *,
    basetype::regtype::text AS basetype_name,
    elemtype::regtype::text AS elemtype_name,
    range_subtype::regtype::text AS range_subtype_name
FROM
    typeinfo_tree
ORDER BY
    depth DESC
-----
-- [asyncpg]
-- postgres16: 1 f71998fe363b
SELECT indkey FROM pg_catalog.pg_index LIMIT 1
-----
