-- Run through the frontend; native.sql is the same schema for the reference PostgreSQL
CREATE TABLE people (id int PRIMARY KEY, name text, city text);
CREATE INDEX ON people (name);
CREATE TABLE tags (tag text PRIMARY KEY, n int);
CREATE TABLE cl (p int, k text, v int, PRIMARY KEY (p, k));
