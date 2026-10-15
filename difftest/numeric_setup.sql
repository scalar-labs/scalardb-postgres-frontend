CREATE TABLE nm (k int PRIMARY KEY, a numeric(10,2), b numeric(5,0), c numeric(4,4));
INSERT INTO nm VALUES (1, 19.905, 12345, 0.12345), (2, -0.005, 1, -0.9999), (3, NULL, NULL, NULL), (4, 1.01, 7, 0.1);
CREATE TABLE nk (k int, p numeric(10,2), v int, PRIMARY KEY (k, p));
INSERT INTO nk VALUES (1, 1.00, 1), (1, 2.50, 2), (1, -3.25, 3), (2, 0, 4);
CREATE TABLE nt (k int PRIMARY KEY, b numeric, c numeric(30,10));
INSERT INTO nt VALUES (1, 9, 1), (2, 10, 2.5), (3, 1.10, 12345678901234567890.123456789012), (4, 'NaN', 0), (5, 'Infinity', -7.25), (6, NULL, NULL), (7, 1.1, 1e-10), (8, -12345678901234567890123456789.5, 99999999999999999999.9999999999);
