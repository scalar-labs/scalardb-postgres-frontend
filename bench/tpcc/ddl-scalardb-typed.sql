-- TPC-C schema for ScalarDB through the PostgreSQL frontend with BenchBase's declared types: the same keys,
-- column order and index as ddl-scalardb.sql, but DECIMAL(p,s), CHAR(n) and VARCHAR(n) as BenchBase's
-- ddl-postgres.sql declares them. The frontend records each declaration in the ScalarDB column name
-- (docs/type-encoding.md): every DECIMAL here has p <= 12, so it is stored exactly as a scaled BIGINT.
-- c_ytd_payment is FLOAT in BenchBase too. Foreign keys, NOT NULL and DEFAULT are dropped as in
-- ddl-scalardb.sql.
CREATE COORDINATOR TABLES IF NOT EXISTS;
CREATE FRONTEND METADATA TABLES;  -- where NUMERIC, VARCHAR(n), CHAR(n) and SMALLINT record their types
CREATE SCHEMA IF NOT EXISTS tpcc;
DROP TABLE IF EXISTS history;
DROP TABLE IF EXISTS new_order;
DROP TABLE IF EXISTS order_line;
DROP TABLE IF EXISTS oorder;
DROP TABLE IF EXISTS customer;
DROP TABLE IF EXISTS district;
DROP TABLE IF EXISTS stock;
DROP TABLE IF EXISTS item;
DROP TABLE IF EXISTS warehouse;
CREATE TABLE warehouse (w_id INT, w_ytd DECIMAL(12,2), w_tax DECIMAL(4,4), w_name VARCHAR(10), w_street_1 VARCHAR(20), w_street_2 VARCHAR(20), w_city VARCHAR(20), w_state CHAR(2), w_zip CHAR(9), PRIMARY KEY (w_id));
CREATE TABLE item (i_id INT, i_name VARCHAR(24), i_price DECIMAL(5,2), i_data VARCHAR(50), i_im_id INT, PRIMARY KEY (i_id));
CREATE TABLE stock (s_w_id INT, s_i_id INT, s_quantity INT, s_ytd DECIMAL(8,2), s_order_cnt INT, s_remote_cnt INT, s_data VARCHAR(50), s_dist_01 CHAR(24), s_dist_02 CHAR(24), s_dist_03 CHAR(24), s_dist_04 CHAR(24), s_dist_05 CHAR(24), s_dist_06 CHAR(24), s_dist_07 CHAR(24), s_dist_08 CHAR(24), s_dist_09 CHAR(24), s_dist_10 CHAR(24), PRIMARY KEY (s_w_id, s_i_id));
CREATE TABLE district (d_w_id INT, d_id INT, d_ytd DECIMAL(12,2), d_tax DECIMAL(4,4), d_next_o_id INT, d_name VARCHAR(10), d_street_1 VARCHAR(20), d_street_2 VARCHAR(20), d_city VARCHAR(20), d_state CHAR(2), d_zip CHAR(9), PRIMARY KEY (d_w_id, d_id));
CREATE TABLE customer (c_w_id INT, c_d_id INT, c_id INT, c_discount DECIMAL(4,4), c_credit CHAR(2), c_last VARCHAR(16), c_first VARCHAR(16), c_credit_lim DECIMAL(12,2), c_balance DECIMAL(12,2), c_ytd_payment FLOAT, c_payment_cnt INT, c_delivery_cnt INT, c_street_1 VARCHAR(20), c_street_2 VARCHAR(20), c_city VARCHAR(20), c_state CHAR(2), c_zip CHAR(9), c_phone CHAR(16), c_since TIMESTAMP, c_middle CHAR(2), c_data VARCHAR(500), PRIMARY KEY (c_w_id, c_d_id, c_id)) WITH (partition_key = 'c_w_id, c_d_id', clustering_key = 'c_id');
CREATE TABLE history (h_c_id INT, h_c_d_id INT, h_c_w_id INT, h_d_id INT, h_w_id INT, h_date TIMESTAMP, h_amount DECIMAL(6,2), h_data VARCHAR(24), PRIMARY KEY (h_c_id, h_c_d_id, h_c_w_id, h_d_id, h_w_id, h_date)) WITH (partition_key = 'h_c_id, h_c_d_id, h_c_w_id', clustering_key = 'h_d_id, h_w_id, h_date');
CREATE TABLE oorder (o_w_id INT, o_d_id INT, o_id INT, o_c_id INT, o_carrier_id INT, o_ol_cnt INT, o_all_local INT, o_entry_d TIMESTAMP, PRIMARY KEY (o_w_id, o_d_id, o_id)) WITH (partition_key = 'o_w_id, o_d_id', clustering_key = 'o_id');
CREATE TABLE new_order (no_w_id INT, no_d_id INT, no_o_id INT, PRIMARY KEY (no_w_id, no_d_id, no_o_id)) WITH (partition_key = 'no_w_id, no_d_id', clustering_key = 'no_o_id');
CREATE TABLE order_line (ol_w_id INT, ol_d_id INT, ol_o_id INT, ol_number INT, ol_i_id INT, ol_delivery_d TIMESTAMP, ol_amount DECIMAL(6,2), ol_supply_w_id INT, ol_quantity DECIMAL(6,2), ol_dist_info CHAR(24), PRIMARY KEY (ol_w_id, ol_d_id, ol_o_id, ol_number)) WITH (partition_key = 'ol_w_id, ol_d_id', clustering_key = 'ol_o_id, ol_number');

-- As in ddl-scalardb.sql: one-column ScalarDB index for the by-last-name lookups.
CREATE INDEX idx_customer_name ON tpcc.customer (c_last);
