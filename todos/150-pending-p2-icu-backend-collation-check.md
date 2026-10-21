---
status: pending
priority: p2
issue_id: "150"
tags: [collation, icu, backend, truth-or-error]
dependencies: []
---

# Check the backing database's collation under ICU too

Under `scalar.db.collation=ICU`, ScalarDB creates text columns with the backing database's default
collation, and the user has to alter each one to the matching nondeterministic collation after every
`CREATE TABLE` and `ADD COLUMN` (getting-started/README.md, difftest/collation-icu/backend.sql). Nothing
checks that they did. A column left in the default collation makes key lookups, ranges, `ORDER BY` on a
clustering key and index lookups answer by the database's comparison, while the same comparison
evaluated in the frontend follows ICU: different answers by path, no error. Under `BINARY` the frontend
already checks the backing PostgreSQL or MySQL database at startup
(`scalar.db.postgres.backend_collation_check`).

## Proposed Solutions

1. With the JDBC storage on PostgreSQL, read each text column's collation (`pg_attribute.attcollation`
   joined with `pg_collation`: provider `i`, the locale, not deterministic) at startup and after the
   frontend's own `CREATE TABLE` / `ADD COLUMN`, and refuse the table (`55000`, hint naming the
   `ALTER COLUMN ... COLLATE`) until it matches. Small; covers the frontend's own DDL.
2. Have the frontend issue the `ALTER COLUMN ... COLLATE` itself after its DDL on a JDBC PostgreSQL
   backend. Removes the manual step, but writes to the backing database behind ScalarDB.
3. ScalarDB core creating text columns in the configured collation. The real fix, outside this repo.

## Acceptance Criteria

- [ ] Under `ICU` on a JDBC PostgreSQL backend, a text column in another collation makes reads of its
      table fail with an error instead of answering.
- [ ] Cluster and other storages: documented as unchecked, as for `BINARY`.
