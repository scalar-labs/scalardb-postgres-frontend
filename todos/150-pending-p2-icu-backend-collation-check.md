---
status: pending
priority: p2
issue_id: "150"
tags: [collation, icu, backend, truth-or-error]
dependencies: []
---

# Check the backing database's collation under ICU too

Under `scalar.db.collation=ICU`, ScalarDB creates text columns with the backing database's default
collation, and the user has to alter each one to a matching collation after every `CREATE TABLE` and
`ADD COLUMN` (getting-started/README.md, difftest/collation-icu/backend.sql). Nothing checks that they
did. A column left in the default collation makes key lookups, ranges, `ORDER BY` on a clustering key
and index lookups answer by the database's comparison, while the same comparison evaluated in the
frontend follows ScalarDB's ICU4J comparator: different answers by path, no error. Under `BINARY` the
frontend already checks the backing PostgreSQL or MySQL database at startup and refuses to start on a
mismatch unless `scalar.db.postgres.backend_collation_check=false`.

## Where ICU can be used at all

From `RdbEngineStrategy.throwIfCollationNotSupported` and the storages' constructors in the published
`3.20.0-SNAPSHOT` (2026-10-20):

- **Allowed:** the JDBC storage on PostgreSQL, YugabyteDB, MySQL, MariaDB, TiDB, Oracle, SQL Server
  and Db2.
- **Refused by ScalarDB at startup** (`COLLATION_ICU_NOT_SUPPORTED_BY_STORAGE`): Cassandra, Cosmos DB,
  DynamoDB, the JDBC storage on Spanner and SQLite, and object storage. Nothing to check there.

## Why only PostgreSQL can match exactly

Two collations agree on every pair of strings only with the same algorithm, the same Unicode/CLDR data
version, the same strength and options, and the same trailing-space rule. ScalarDB compares with ICU4J
(77.1 in the snapshot; `scalardb-collation.properties` in the core jar records the version).

- **PostgreSQL, YugabyteDB:** an ICU-provider collation runs ICU itself, but the system's libicu, whose
  version can differ from ICU4J's (e.g. 74 against 77.1), and versions differ on characters whose rules
  changed. Exact only when provider ICU, the same locale, nondeterministic, and the same collator version
  (`pg_collation.collversion` against ICU4J's `Collator.getVersion()`).
- **MySQL, TiDB:** `utf8mb4_0900_*` is MySQL's own UCA implementation at Unicode 9.0 on DUCET, not CLDR
  root; older collations (`general_ci`, `unicode_ci`) lack contractions and expansions and are PAD SPACE.
- **MariaDB:** its own implementations (`general_ci`; `uca1400` at Unicode 14 in newer releases), many
  PAD SPACE.
- **SQL Server:** Windows (`Latin1_General_100_*`) and `SQL_*` collations from Microsoft's own tables;
  `=` ignores trailing spaces.
- **Oracle:** linguistic sorts and Oracle's own UCA versions (up to 12.1 in recent releases).
- **Db2:** one collation per database, chosen at creation (`CLDR181_*`, CLDR 1.8.1).

These agree with `und-u-ks-level2` on everyday Latin text and differ on rare characters, newer Unicode,
contractions and trailing spaces; a column's collation name cannot show that they agree.

## Proposed Solution

At startup with the JDBC storage under `ICU`, unless `scalar.db.postgres.backend_collation_check=false`
(the same opt-out as under `BINARY`):

1. **PostgreSQL, YugabyteDB:** check every text column of the frontend's namespaces, and again after
   the frontend's own `CREATE TABLE` / `ADD COLUMN`: provider `i`, the configured locale,
   `collisdeterministic = false`, and `collversion` equal to ICU4J's collator version. A column that
   does not match makes its table fail with `55000`, the hint naming the `ALTER COLUMN ... COLLATE`, or
   the ICU version mismatch.
2. **MySQL, MariaDB, TiDB, SQL Server, Oracle, Db2:** refuse to start with an error saying no
   collation there matches ICU exactly and naming the opt-out.
3. **ScalarDB Cluster:** the frontend cannot see the nodes' database; document as unchecked, as under
   `BINARY`.

Alternatives considered: the frontend running the `ALTER COLUMN ... COLLATE` itself after its DDL
(writes to the backing database behind ScalarDB); ScalarDB core creating text columns in the configured
collation (the real fix for the manual step, outside this repo, and it does not solve the version
mismatch).

## Acceptance Criteria

- [ ] PostgreSQL under ICU: a text column in the default collation, with another locale, deterministic,
      or with a different collator version makes reads of its table fail instead of answering.
- [ ] PostgreSQL with matching columns and versions starts and answers as before
      (difftest/collation-icu still passes).
- [ ] MySQL and SQL Server under ICU refuse to start without the opt-out, and start with it.
- [ ] LIMITATIONS.md and getting-started/README.md describe the check, the opt-out, and that Cluster is
      unchecked.
