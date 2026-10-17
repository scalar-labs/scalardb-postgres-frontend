#!/bin/zsh
# meta.zsh < meta.txt: run each psql meta-command on native PostgreSQL and the frontend; print
# command | native | frontend | the catalog fallbacks the frontend's log shows for it (FE_LOG)
export LANG=C LC_ALL=C
FE_LOG=${FE_LOG:-${0:a:h}/fe.log}
N=(psql -X -h 127.0.0.1 -p ${NATIVE_PORT:-15499} -U postgres -d audit)
F=(psql -X -h 127.0.0.1 -p ${FE_PORT:-15473} -U postgres -d audit)
summ() { # output -> rows count, 0, or ERR: msg
  local out=$1 err
  err=$(print -r -- "$out" | grep -m1 -E '^(ERROR|psql:.*ERROR|FATAL)' | sed -E 's/^psql:[^E]*//' | cut -c1-80)
  if [[ -n $err ]]; then print -r -- "ERR $err"; return; fi
  local n=$(print -r -- "$out" | grep -m1 -oE '^\(([0-9]+) rows?\)' | grep -oE '[0-9]+')
  if [[ -n $n ]]; then print -r -- $n; return; fi
  if print -r -- "$out" | grep -q -E '^Did not find|^No (matching )?[a-z ]+ found|^List of .*$' && ! print -r -- "$out" | grep -q -- '----'; then print -r -- 0; return; fi
  print -r -- "out:$(print -r -- "$out" | grep -v '^\s*$' | head -2 | tr '\n' '|' | cut -c1-60)"
}
while IFS= read -r c; do
  [[ -z $c || $c == \#* ]] && continue
  before=$(grep -c CATALOG-FALLBACK $FE_LOG)
  n=$(summ "$($N -c "$c" 2>&1)")
  f=$(summ "$($F -c "$c" 2>&1)")
  hits=$(grep CATALOG-FALLBACK $FE_LOG | tail -n +$((before+1)) | sed -E 's/.*CATALOG-FALLBACK (.*) :: .*/\1/' | cut -c1-70 | sort -u | tr '\n' ';')
  print -r -- "$c | $n | $f | $hits"
done
