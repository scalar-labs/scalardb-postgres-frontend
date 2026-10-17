CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR(20), price NUMERIC(10,2), qty SMALLINT, note TEXT);
CREATE INDEX items_name_idx ON items (name);
CREATE SCHEMA sales;
CREATE TABLE sales.orders (id INT, line INT, item_id INT, amount DOUBLE PRECISION, PRIMARY KEY (id, line));
INSERT INTO items VALUES (1, 'pen', 1.50, 10, 'blue'), (2, 'book', 12.00, 3, NULL);
INSERT INTO sales.orders VALUES (1, 1, 1, 3.0), (1, 2, 2, 12.0);
