---
status: complete
priority: p3
issue_id: "055"
tags: [type-modifiers, smallint, integer, code-review]
dependencies: []
---

# Quoted integer input in comparisons follows PostgreSQL only for pushed-down smallint

Found by the review of commit fd0a51c (Phase 5) on 2026-10-15.

## Problem Statement

1. **Quoted out-of-range values.** `WHERE int_col = '5000000000'` matches no row; PostgreSQL 18 raises 22003
   `value "5000000000" is out of range for type integer`. Only a pushed-down smallint comparison raises it, and it
   is told apart in `QueryParser.isOutOfRange` by the message ending in "type smallint". In memory
   (`WHERE s = '40000' OR k = 1`) no 22003 is raised, and a non-number gives 22P02 naming `numeric`, not the
   column's type. This predates Phase 5 for integer and bigint.
2. **PostgreSQL 16+ integer input forms.** `int2in`/`int4in`/`int8in` accept `0x10`, `0o17`, `0b101` and `1_000`.
   `ColumnType`'s SMALLINT `exact()` accepts only `[+-]?\d+`, so these raise 22P02 here.

## Proposed Solution

One integer-input parser shared by every integer type (todo 021, one text-to-value converter), applied to quoted
values wherever they meet an integer column, pushed down or in memory, deciding by whether the value was quoted
rather than by the error message.

## Acceptance Criteria

- [x] `int_col = '5000000000'`, `s = '40000' OR k = 1` raise 22003 with PostgreSQL's message.
- [x] `s = '0x10'`, `s = '1_000'` match as in PostgreSQL 18.
- [x] Difftest cases for both, matching PostgreSQL 18.

## Work Log

- 2026-10-15: filed from the review of fd0a51c; deferred at the user's choice.
- 2026-10-19: done.
  - `Evaluator.integerInput` reads integer text as int2in, int4in and int8in do in PostgreSQL 16+: an
    optional sign, decimal digits or a 0x, 0o or 0b prefix, single underscores between digits and after
    the prefix, whitespace around. Smallint input (`ColumnType`), int and bigint input
    (`QueryParser.parseColumn`, which took `'1.0'` and `'1e3'` before), casts (`Evaluator.integerText`)
    and `QueryParser.isInteger` all use it.
  - Pushed down, a quoted value beyond an int or bigint column's range raises 22003; only a number
    literal (`k = 5000000000`) is still a comparison that is false. `isOutOfRange` no longer tells
    smallint apart by the message's last word.
  - In memory, a quoted literal next to an integer is that integer type's input
    (`Evaluator.coerce`), as PostgreSQL types an unknown literal: `k + 0 = '5000000000'` is 22003,
    `coalesce(s, 0) = '0B111'` matches. A smallint value in memory is typed integer, so its range is
    integer's there.
  - difftest/cases_character.sql: 14 new cases, all matching PostgreSQL 18 (4 OK, 10 the same error);
    the baseline records them. read, types, numeric, session, write and bind keep their baselines.
    `HelpersTest#integerInput_ShouldReadIntegersAsPostgreSql16Does` pins the forms. `./gradlew check`: 375
    tests, 0 failing.
