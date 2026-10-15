# Encryption and ABAC against a standalone ScalarDB Cluster node

A Cluster node with authentication, encryption and attribute-based access control on, the frontend in
cluster mode with `scalar.db.postgres.auth=userpass`, and the psql scenario in `run.sh` (31 checks):
`CREATE TABLE ... WITH (encrypted = '...')`, values stored as ciphertext, conditions, sorts and joins on
encrypted columns evaluated in the frontend, and two users at different ABAC levels seeing and writing
different rows of the same table. `results-20261015.txt` is a run.

## Setup

It reuses [cluster-auth](../cluster-auth/README.md)'s node build and SDK classpath: `node` and
`sdk-classpath/lib` here are links to them, which git ignores (`ln -s ../cluster-auth/node node`,
`mkdir sdk-classpath && ln -s ../../cluster-auth/sdk-classpath/lib sdk-classpath/lib`). The node is the
plain `node` module, which runs both features without a licence check.

1. A PostgreSQL at 15450 with a database `clustersec` (any empty PostgreSQL; the run of 2026-10-15 used
   `initdb -D pgsec -U postgres --auth=trust -E UTF8 --locale=C && pg_ctl -D pgsec -o "-p 15450 -k ''" start`
   and `createdb -h localhost -p 15450 -U postgres clustersec`).
2. A Vault dev server holding the keys, with the transit engine (self-managed keys need Kubernetes):
   ```sh
   docker run -d --name sdb-vault -p 127.0.0.1:8200:8200 -e VAULT_DEV_ROOT_TOKEN_ID=root \
       -e VAULT_DEV_LISTEN_ADDRESS=0.0.0.0:8200 --cap-add=IPC_LOCK hashicorp/vault:1.18
   docker exec -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN=root sdb-vault vault secrets enable transit
   ```
3. The node (`./node.sh`, JDK 21), then, once it listens on 60053, the policy, users and table as the
   superuser, and the frontend on 15447:
   ```sh
   java -cp ../../server/build/libs/scalardb-postgres-frontend.jar:'sdk-classpath/lib/*' Setup.java admin.properties
   ./frontend.sh
   ./run.sh
   ```

`Setup.java` creates the ABAC policy `lv` (data tag column `lv_data_tag`) with the levels `PUB` and
`CONF`, the users alice (`CONF`, password `alicepw`) and bob (`PUB`, `bobpw`) with READ, WRITE, DELETE
and CREATE on the namespace `secdemo`, and the table `docs` under the policy with one row at each level.
`run.sh` creates the encrypted table `people` itself, and drops it and the rows it inserted on the next
run.

## What it found

- Cluster leaves the data tag column out of a read without a projection list, so `SELECT *` showed
  it as NULL; the frontend now always names the columns it reads.
- psql's `\d+` builds the `Options` footer with `reloptions || array(...)`, which the frontend
  evaluated as text concatenation; `||` now concatenates arrays.
- Policies cannot be read by an ordinary user (`getTablePolicies` is superuser-only), so the frontend
  cannot tell which column is a data tag column; Cluster's refusal of one in a condition or ordering is
  passed on as `0A000`.
