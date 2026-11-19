-- Puts mt, mk and me back as merge_setup.sql loads them, after each case of cases_merge.sql (SNAP_RELOAD)
TRUNCATE mt;
TRUNCATE mk;
TRUNCATE me;
INSERT INTO mt VALUES (1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three'), (4, 4, 'four'), (5, 5, 'five');
INSERT INTO mk VALUES (1, 1, 11), (1, 2, 12), (2, 1, 21);
