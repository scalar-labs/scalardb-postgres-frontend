"""Random differential read queries over difftest's schema.
    python3 fuzz.py SEED COUNT > fuzz_cases.sql && STOP=0 FEPORT=15444 python3 run.py fuzz_cases.sql
Native PostgreSQL is the oracle, so every DIFF is a wrong result. Expressions are typed, so PG_ERR and
BOTH_ERR mostly come from overflow and division by zero. Every output column is aliased (c1, c2, ...),
so NAMES cannot hide a DIFF. ORDER BY ... LIMIT cases are made totally ordered by trailing key columns."""
import random, re, sys
seed, N = int(sys.argv[1]), int(sys.argv[2])
r = random.Random(seed)
T = {  # table: (key columns, column types); bigint and double both count as 'num' where it matters
    'emp': (['id'], {'id': 'int', 'name': 'text', 'dept_id': 'int', 'mgr_id': 'int', 'salary': 'int',
                     'bonus': 'num', 'active': 'bool', 'hired': 'date', 'note': 'text'}),
    'dept': (['id'], {'id': 'int', 'name': 'text', 'region': 'text', 'budget': 'num', 'parent_id': 'int'}),
    'proj': (['dept_id', 'proj_id'], {'dept_id': 'int', 'proj_id': 'int', 'title': 'text', 'cost': 'int', 'started': 'ts'}),
    'assign': (['emp_id', 'proj_id'], {'emp_id': 'int', 'proj_id': 'int', 'hours': 'int', 'role': 'text'}),
}
JOINS = [('emp', 'dept_id', 'dept', 'id'), ('emp', 'mgr_id', 'emp', 'id'), ('assign', 'emp_id', 'emp', 'id'),
         ('assign', 'proj_id', 'proj', 'proj_id'), ('proj', 'dept_id', 'dept', 'id'), ('dept', 'parent_id', 'dept', 'id')]
LIT = {'int': ['-50', '0', '1', '2', '5', '7', '10', '40', '42', '1000', '2000', '3500', '12345'],
       'num': ['0.0', '-300.25', '1000.5', '2500.0', '10.5', '99.99'],
       'text': ["'alice'", "'bob1'", "'dept03'", "'north'", "'alpha'", "'dev'", "''", "'x'", "'Remote'", "'Dave'", "'zz'"],
       'date': ["DATE '2015-06-01'", "DATE '2020-01-01'", "DATE '2024-12-28'"],
       'ts': ["TIMESTAMP '2023-06-15 12:00:00'", "TIMESTAMP '2023-01-01 00:00:00'"]}
LIKES = ["'%a%'", "'bob%'", "'_ve%'", "'%1'", "'alpha'", "'%'", "'dept0_'", "'%e%'", "'%\\%%'", "'%card%'"]
CMP = ['=', '<>', '<', '<=', '>', '>=']
ch = r.choice

def of(cols, t): return [c for c, ty in cols if ty == t]

def ne(cols, d=0):
    """numeric expression -> (sql, 'int' | 'num')"""
    k = r.random()
    ints, nums = of(cols, 'int'), of(cols, 'num')
    if d > 1 or k < .4:
        if nums and r.random() < .25: return ch(nums), 'num'
        return (ch(ints), 'int') if ints else (ch(LIT['int']), 'int')
    if k < .48: t = ch(['int', 'int', 'num']); return ch(LIT[t]), t
    if k < .6:
        (a, ta), (b, tb) = ne(cols, d + 1), ne(cols, d + 1)
        return f"({a} {ch('+-*')} {b})", 'num' if 'num' in (ta, tb) else 'int'
    if k < .66:
        a, ta = ne(cols, d + 1); dv = ch(['2', '3', '7', '-4', '2.5'])
        return f"({a} / {dv})", 'num' if ta == 'num' or '.' in dv else 'int'
    if k < .72:
        a, ta = ne(cols, d + 1)
        return (f"({a} % {ch(['2', '3', '7', '-4'])})", 'int') if ta == 'int' else (f"(-{a})", ta)
    if k < .78: a, ta = ne(cols, d + 1); return f"abs({a})", ta
    if k < .84: a, ta = ne(cols, d + 1); return f"coalesce({a}, {ch(LIT[ta])})", ta
    if k < .88: a, ta = ne(cols, d + 1); return f"nullif({a}, {ch(LIT[ta])})", ta
    if k < .93:
        (a, ta), (b, tb) = ne(cols, d + 1), ne(cols, d + 1)
        return f"CASE WHEN {pred(cols, d + 1)} THEN {a} ELSE {b} END", 'num' if 'num' in (ta, tb) else 'int'
    if k < .96: return f"length({te(cols, d + 1)})", 'int'
    dates = of(cols, 'date') + of(cols, 'ts')
    if dates and k < .98: return f"extract({ch(['year', 'month', 'day'])} from {ch(dates)})", 'int'
    (a, ta), (b, tb) = ne(cols, d + 1), ne(cols, d + 1)
    return f"{ch(['greatest', 'least'])}({a}, {b})", 'num' if 'num' in (ta, tb) else 'int'

def te(cols, d=0):
    k = r.random()
    texts = of(cols, 'text')
    if d > 1 or k < .5: return ch(texts) if texts else ch(LIT['text'])
    if k < .62: return f"({te(cols, d + 1)} || {te(cols, d + 1)})"
    if k < .72: return f"substr({te(cols, d + 1)}, {ch(['1', '2', '3'])}, {ch(['1', '2', '3', '10'])})"
    if k < .82: return f"coalesce({te(cols, d + 1)}, {ch(LIT['text'])})"
    if k < .88: return f"nullif({te(cols, d + 1)}, {ch(LIT['text'])})"
    if k < .94: return f"lower({te(cols, d + 1)})"  # upper() of an accented letter is a known C-ctype difference
    return f"CASE WHEN {pred(cols, d + 1)} THEN {te(cols, d + 1)} ELSE {te(cols, d + 1)} END"

def anyexpr(cols, d=0):
    k = r.random()
    if k < .55: return ne(cols, d)
    if k < .85: return te(cols, d), 'text'
    for t in ('date', 'ts', 'bool'):
        if of(cols, t) and r.random() < .5: return ch(of(cols, t)), t
    return ne(cols, d)

def pred(cols, d=0):
    k = r.random()
    if d > 2: k *= .55
    if k < .2:
        a, ta = ne(cols, d + 1)
        b = ne(cols, d + 1)[0] if r.random() < .35 else ch(LIT[ta])
        return f"{a} {ch(CMP)} {b}"
    if k < .3:
        b = te(cols, d + 1) if r.random() < .3 else ch(LIT['text'])
        return f"{te(cols, d + 1)} {ch(['=', '<>', '<', '>'])} {b}"
    if k < .37: return f"{te(cols, d + 1)} {ch(['LIKE', 'NOT LIKE', 'ILIKE'])} {ch(LIKES)}"
    if k < .44: return f"{ch([ne(cols, d + 1)[0], te(cols, d + 1)])} IS {ch(['', 'NOT '])}NULL"
    if k < .49 and of(cols, 'bool'):
        b = ch(of(cols, 'bool')); return ch([b, f"NOT {b}", f"{b} IS NULL", f"{b} = FALSE", f"{b} IS DISTINCT FROM TRUE"])
    if k < .55:
        a, ta = ne(cols, d + 1); lits = r.sample(LIT[ta], r.randint(1, 3)) + (['NULL'] if r.random() < .2 else [])
        return f"{a} {ch(['', 'NOT '])}IN ({', '.join(lits)})"
    if k < .6:
        a, ta = ne(cols, d + 1); lo, hi = sorted(r.sample(LIT[ta], 2), key=float)
        return f"{a} {ch(['', 'NOT '])}BETWEEN {lo} AND {hi}"
    if k < .65 and (of(cols, 'date') or of(cols, 'ts')):
        t = ch([t for t in ('date', 'ts') if of(cols, t)]); return f"{ch(of(cols, t))} {ch(CMP)} {ch(LIT[t])}"
    if k < .7: a, ta = ne(cols, d + 1); return f"{a} IS {ch(['', 'NOT '])}DISTINCT FROM {ch(LIT[ta] + ['NULL'])}"
    if k < .8: return f"({pred(cols, d + 1)} {ch(['AND', 'OR'])} {pred(cols, d + 1)})"
    if k < .85: return f"NOT ({pred(cols, d + 1)})"
    if k < .95 and d < 2 and of(cols, 'int'): return subq_pred(cols, d)
    return f"({pred(cols, d + 1)} {ch(['AND', 'OR'])} {pred(cols, d + 1)})"

def subq_pred(cols, d):
    oc = ch(of(cols, 'int'))
    t = ch(list(T)); s = 'xyz'[d]
    icols = [(f"{s}.{c}", ty) for c, ty in T[t][1].items()]
    ic = ch(of(icols, 'int'))
    inner = pred(icols + (cols if r.random() < .3 else []), d + 1)
    k = r.random()
    if k < .3: return f"{ch(['', 'NOT '])}EXISTS (SELECT 1 FROM {t} {s} WHERE {ic} = {oc} AND {inner})"
    if k < .55: return f"{oc} {ch(['', 'NOT '])}IN (SELECT {ic} FROM {t} {s} WHERE {inner})"
    if k < .8:
        agg = ch(['min', 'max', 'avg', 'count', 'sum'])
        return f"{ne(cols, d + 1)[0]} {ch(CMP)} (SELECT {agg}({ic}) FROM {t} {s} WHERE {inner})"
    return f"{ne(cols, d + 1)[0]} {ch(CMP)} {ch(['ANY', 'ALL'])} (SELECT {ic} FROM {t} {s} WHERE {inner})"

def agg(cols):
    k = r.random()
    if k < .2: return 'count(*)', 'int'
    e, t = anyexpr(cols, 1)
    if k < .4: return f"count({ch(['', 'DISTINCT '])}{e})", 'int'
    if t == 'bool': e, t = ne(cols, 1)  # PostgreSQL has no min/max(boolean)
    if k < .6: return f"{ch(['min', 'max'])}({e})", t
    e, t = ne(cols, 1)
    if k < .9: return f"sum({e})", t
    return f"avg({e})", 'num'

def colsof(al): return [(f"{a}.{c}", ty) for a, t in al for c, ty in T[t][1].items()]
def keys(al): return [f"{a}.{c}" for a, t in al for c in T[t][0]]

def from_clause():
    t = ch(list(T)); al = [('a', t)]; sql = f"{t} a"
    for i in range(1, 1 + ch([0, 0, 1, 1, 1, 2])):
        (oa, ot), j = ch([(o, j) for o in al for j in JOINS if ot_in(j, o[1])])
        nt, oc, nc = (j[2], j[1], j[3]) if j[0] == ot else (j[0], j[3], j[1])
        na = 'abc'[i]; al.append((na, nt))
        on = f"{na}.{nc} = {oa}.{oc}"
        if r.random() < .25: on += f" AND {pred(colsof(al), 1)}"
        sql += f" {ch(['JOIN', 'JOIN', 'LEFT JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'FULL JOIN'])} {nt} {na} ON {on}"
    return sql, al
def ot_in(j, t): return j[0] == t or j[2] == t

def order_tail(items, tie):
    """ORDER BY one item plus every tie column, so the order is total; returns (sql, ordered)"""
    k = r.random()
    if k < .3:
        first = ch(items)
        o = f" ORDER BY {first}{ch(['', ' DESC'])}{ch(['', ' NULLS FIRST', ' NULLS LAST', ''])}"
        o += ''.join(f", {t}" for t in tie if t != first)
        return o + f" LIMIT {ch(['1', '3', '5', '10'])}{ch(['', '', ' OFFSET 2', ' OFFSET 5'])}", True
    if k < .45: return f" ORDER BY {ch(items)}{ch(['', ' DESC'])}", False
    return '', False

def select():
    frm, al = from_clause(); cols = colsof(al)
    where = f" WHERE {pred(cols)}" if r.random() < .8 else ''
    shape = r.choices(['plain', 'agg', 'distinct', 'window'], [5, 3, 1, 1])[0]
    items, types, tail, group = [], [], '', ''
    if shape == 'agg':
        gk = [anyexpr(cols, 1) for _ in range(ch([0, 1, 1, 2]))]
        gk = [(e, t) for e, t in gk if re.search(r'\b[a-c]\.[a-z_]+', e)]  # a bare constant is a GROUP BY error
        items = [e for e, _ in gk]; types = [t for _, t in gk]
        for _ in range(r.randint(1, 3)):
            e, t = agg(cols); items.append(e); types.append(t)
        if gk:
            group = ' GROUP BY ' + ch([', '.join(e for e, _ in gk), ', '.join(str(i + 1) for i in range(len(gk))),
                                      ', '.join(f"c{i + 1}" for i in range(len(gk)))])
        if r.random() < .4:
            e, t = agg(cols); group += f" HAVING {e} {ch(CMP)} {ch(LIT[t]) if t in LIT else 'NULL'}" if t in LIT else f" HAVING {e} IS NOT NULL"
        aliases = [f"c{i + 1}" for i in range(len(items))]
        tail, ordered = order_tail(aliases, aliases[:len(gk)])
    else:
        for _ in range(r.randint(1, 4)):
            e, t = anyexpr(cols); items.append(e); types.append(t)
        if shape == 'window':
            g, _ = anyexpr(cols, 1)
            w = ch([f"row_number() OVER (PARTITION BY {g} ORDER BY {', '.join(keys(al))})",
                    f"sum({ne(cols, 1)[0]}) OVER (PARTITION BY {g})", f"rank() OVER (ORDER BY {ne(cols, 1)[0]})",
                    f"count(*) OVER ()", f"max({te(cols, 1)}) OVER (PARTITION BY {g})"])
            items.append(w); types.append('int')
        if shape == 'plain' and r.random() < .12:
            (oa, ot), j = ch([(o, j) for o in al for j in JOINS if ot_in(j, o[1])])
            nt, oc, nc = (j[2], j[1], j[3]) if j[0] == ot else (j[0], j[3], j[1])
            mx = 'max(s.' + ch([c for c, t in T[nt][1].items() if t != 'bool']) + ')'  # no max(boolean) in PostgreSQL
            items.append(f"(SELECT {ch(['count(*)', mx])} FROM {nt} s WHERE s.{nc} = {oa}.{oc})"); types.append('int')
        aliases = [f"c{i + 1}" for i in range(len(items))]
        tail, ordered = order_tail(aliases, aliases if shape == 'distinct' else keys(al))
    sel = ', '.join(f"{e} AS c{i + 1}" for i, e in enumerate(items))
    return f"SELECT {'DISTINCT ' if shape == 'distinct' else ''}{sel} FROM {frm}{where}{group}{tail}", ordered, types

def setop():
    sig = ch([['int'], ['int', 'text'], ['text']])
    def side():
        t = ch(list(T)); cols = [(f"a.{c}", ty) for c, ty in T[t][1].items()]
        items = [ne(cols)[0] if s == 'int' else te(cols) for s in sig]
        return f"SELECT {', '.join(f'{e} AS c{i + 1}' for i, e in enumerate(items))} FROM {t} a" + (f" WHERE {pred(cols, 1)}" if r.random() < .7 else '')
    op = ch(['UNION', 'UNION ALL', 'INTERSECT', 'EXCEPT', 'INTERSECT ALL', 'EXCEPT ALL'])
    return f"{side()} {op} {side()}", False, sig

def query():
    k = r.random()
    sql, ordered, types = setop() if k < .08 else select()
    if k > .92:
        wcols = [(f"w.c{i + 1}", t) for i, t in enumerate(types)]
        sql = f"WITH w AS ({sql}) SELECT * FROM w" + (f" WHERE {pred(wcols, 1)}" if r.random() < .6 else '')
        ordered = False  # the outer SELECT has no order guarantee; the inner LIMIT still fixes the row set
    return sql, ordered

for i in range(N):
    sql, ordered = query()
    print(f"-- @fz{seed}_{i}{' ordered' if ordered else ''}\n{sql};")
