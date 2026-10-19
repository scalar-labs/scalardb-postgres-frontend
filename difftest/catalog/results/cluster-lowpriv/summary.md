# Catalog audit as a low-privilege Cluster user (todo 062)

Run on 2026-10-19 against main at 2766c13. The privilege queries were run again after the fixes
below (`priv.fe.*.txt`).

**Setup**
- **Cluster side:** the frontend in cluster mode with `userpass` authentication and TLS (port 15445), on a
  standalone ScalarDB Cluster node with authentication on (`difftest/cluster-auth`, node built locally).
- **Schema:** `audit` holds `difftest/catalog/schema.sql`, loaded as the superuser `admin`.
- **The user:** `carol`, created with `CREATE USER carol WITH PASSWORD 'carolpw'` and given
  `GRANT SELECT ON items TO carol` only.
- **Reference:** PostgreSQL 16.15 (port 15416), database `audit` with the same schema. There `carol` has
  `SELECT` on `items` and `USAGE` on schema `sales`, the closest grants, since ScalarDB has no schema
  privilege.

**Commands**

```sh
NATIVE_PORT=15416 NATIVE_USER=carol NATIVE_PASSWORD=carolpw FE_PORT=15445 FE_USER=carol FE_PASSWORD=carolpw \
  FE_SSLMODE=require zsh meta.zsh < meta.txt > results/cluster-lowpriv/meta-carol.txt
# the same as postgres / admin: meta-admin.txt
psql ... -f results/cluster-lowpriv/priv.sql   # priv.{pg,fe}.{carol,postgres,admin}.txt
```

## Result

Carol gets the same catalog as the superuser, on PostgreSQL and on the frontend alike: the catalog is
readable by every user, and only the privilege answers depend on the user. Across all 98 psql
meta-commands and the privilege queries in `priv.sql`, the frontend's answers to carol and to admin
differ only in `current_user`. So no user sees another user's view.

Every answer the frontend gives carol is the truth or an error. The table owner was the one
exception, and it is now fixed (see Fixed below).

| Answer | PostgreSQL 16 (carol) | Frontend (carol) | Verdict |
|---|---|---|---|
| `\dt *.*`, `\d sales.orders` | lists `orders`, which she cannot read | the same | true: the catalog is public |
| `SELECT * FROM sales.orders` | 42501 permission denied | 42501, Cluster's `DB-AUTH-10008` | true |
| `\du`, `pg_roles` | every role | every Cluster user, `admin` as superuser | true |
| `\dp items`, `relacl` | `postgres=arwdDxt/postgres, carol=r/postgres` | `scalardb=arwdD/scalardb, carol=r/scalardb` | true (fixed: the owner's entry was missing) |
| table owner (`relowner`, `\dt` Owner) | `postgres`, in `pg_roles` | `scalardb` (OID 10), in `pg_roles` as a superuser who cannot log in | true (fixed: it was not in `pg_roles`) |
| `\db+` | ERROR: permission denied for tablespace `pg_global` | lists both tablespaces with an empty Size | Size is NULL for every user (`pg_tablespace_size`), not specific to carol |
| `has_table_privilege` (`items` SELECT, INSERT, WITH GRANT OPTION; `sales.orders`; `pg_class`; by role name) | `t f f f t f` | `t f f f t f` | true (fixed: it was unsupported) |
| `has_schema_privilege` | t | ERROR: Unsupported function | error |
| `information_schema.table_privileges`, `role_table_grants` | carol's SELECT on items | ERROR 42P01 | error |
| `\dconfig` | 10 rows (14 for a superuser) | 1 | known gap, not privilege-related |

The other count differences in `meta-carol.txt` are the same for the superuser (`meta-admin.txt`). They
come from what the frontend does not implement: fewer functions, operators, types and text search
objects.

## Fixed

- **The table owner.** In cluster mode, `pg_roles` now lists `scalardb`, the tables' owner and the
  grantor in their ACLs, as a superuser nobody can log in as. A table's ACL lists the owner's entry
  first, as PostgreSQL does.
- **`has_table_privilege`.** It answers from the same ACLs as `\dp`. A superuser or the owner holds
  every privilege. Other roles hold a privilege granted to them directly, to a role they are a member
  of (transitively), or to PUBLIC. Several listed privileges mean any of them, and `WITH GRANT OPTION`
  asks for a grantable one. Without Cluster, every session is the ScalarDB user and holds everything.

## Still errors

- `has_schema_privilege` (ERROR: Unsupported function).
- `information_schema.table_privileges` and `role_table_grants` (42P01).
