CREATE TABLE ch (k int PRIMARY KEY, c char(5), v varchar(7), s smallint, b character);
INSERT INTO ch VALUES (1, 'ab', 'xyz', 7, 'q'), (2, 'abc  ', 'x  ', -32768, NULL), (3, NULL, NULL, NULL, 'z'), (4, 'abcde', 'abcdefg', 32767, ' ');
CREATE TABLE chk (c char(4), v varchar(5), n int, PRIMARY KEY (c, v));
INSERT INTO chk VALUES ('a', 'x', 1), ('ab', 'y', 2), ('b  ', 'z', 3);
