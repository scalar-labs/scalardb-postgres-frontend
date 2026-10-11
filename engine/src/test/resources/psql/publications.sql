SELECT pubname AS "Name",
  pg_catalog.pg_get_userbyid(pubowner) AS "Owner",
  puballtables AS "All tables",
  pubinsert AS "Inserts",
  pubupdate AS "Updates",
  pubdelete AS "Deletes",
  pubtruncate AS "Truncates",
  pubviaroot AS "Via root"
FROM pg_catalog.pg_publication
ORDER BY 1;
