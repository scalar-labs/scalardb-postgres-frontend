-- The reference: PostgreSQL before 18 (which refuses pattern matching on a nondeterministic
-- collation, as the frontend does), with every text column in the collation the frontend uses
CREATE COLLATION ci (provider = icu, locale = 'und-u-ks-level2', deterministic = false);
CREATE TABLE people (id int PRIMARY KEY, name text COLLATE ci, city text COLLATE ci);
CREATE INDEX ON people (name);
CREATE TABLE tags (tag text COLLATE ci PRIMARY KEY, n int);
CREATE TABLE cl (p int, k text COLLATE ci, v int, PRIMARY KEY (p, k));
