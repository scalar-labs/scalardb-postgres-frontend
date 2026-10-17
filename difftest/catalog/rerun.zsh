#!/bin/zsh
# rerun.zsh OUT_DIR: every client of this audit against native PostgreSQL and the frontend, each
# answer next to the other, into OUT_DIR. NATIVE_PORT (15416) and FE_PORT (15473) hold the schema
# of schema.sql in database audit; PGJDBC is the pgjdbc jar; PYTHON has psycopg and SQLAlchemy.
D=${0:a:h}; O=${${1:?out dir}:a}; mkdir -p $O; cd $D
export NATIVE_PORT=${NATIVE_PORT:-15416} FE_PORT=${FE_PORT:-15473} FE_LOG=/dev/null
J=${PGJDBC:-$(ls ~/.gradle/caches/modules-2/files-2.1/org.postgresql/postgresql/42.7.14/*/postgresql-42.7.14.jar | head -1)}
PY=${PYTHON:-./venv/bin/python}
T=$(mktemp -d)
./meta.zsh < meta.txt >| $O/meta.txt
PATH=${PSQL14:-/opt/homebrew/opt/postgresql@14/bin}:$PATH ./meta.zsh < meta.txt >| $O/meta-psql14.txt
side() { # "name | native | frontend" from two outputs of "name | value" lines
  paste -d'\t' <(print -r -- "$1") <(print -r -- "$2") \
    | awk -F'\t' '{split($2, f, " \\| "); print $1 " | " substr($2, length(f[1]) + 4)}'
}
side "$(java -cp $J Meta.java $NATIVE_PORT 2>&1)" "$(java -cp $J Meta.java $FE_PORT 2>&1)" >| $O/jdbc.txt
{ print "# TypeInfo.java: lines the frontend gives that PostgreSQL does not (each should be none)"
  comm -13 <(java -cp $J TypeInfo.java $NATIVE_PORT 2>&1 | sort) \
    <(java -cp $J TypeInfo.java $FE_PORT 2>&1 | sort); } >| $O/typeinfo.txt
sed -e "s/datname='ns'/datname='audit'/; s/relname='t'/relname='items'/g; s/tablename='t'/tablename='items'/g" \
  ../../engine/src/test/resources/dbeaver/catalog.sql >| $T/dbeaver.sql
paste -d'\t' <(java -cp $J RunFile.java $NATIVE_PORT $T/dbeaver.sql 2>&1) \
    <(java -cp $J RunFile.java $FE_PORT $T/dbeaver.sql 2>&1) \
  | awk -F'\t' '{m = split($2, b, " \\| "); print $1 " | " b[m]}' >| $O/dbeaver.txt
paste -d"|" <(${=PY} inspect_all.py $NATIVE_PORT 2>&1) <(${=PY} inspect_all.py $FE_PORT 2>&1) >| $O/sqlalchemy.txt
{ print "table | native count(*) | frontend count(*)"
  tail -n +2 results/catalog-tables.txt | cut -d' ' -f1 | while read t; do
    n=$(psql -X -A -t -h 127.0.0.1 -p $NATIVE_PORT -U postgres -d audit -c "SELECT count(*) FROM $t" 2>&1 | head -1 | cut -c1-70)
    f=$(psql -X -A -t -h 127.0.0.1 -p $FE_PORT -U postgres -d audit -c "SELECT count(*) FROM $t" 2>&1 | head -1 | cut -c1-70)
    print -r -- "$t | $n | $f"
  done } >| $O/catalog-tables.txt
rm -r $T
