---
status: complete
priority: p1
issue_id: "133"
tags: [security, denial-of-service, parser]
dependencies: []
---

# One deeply nested statement held a server thread for hours

Found on 2026-10-20 by a background security review: JSQLParser's time grows with the cube of the
bracket nesting depth (`SELECT ((((...1...)))) + 1` with 20000 levels ran for over 7 minutes at full
CPU before it was abandoned). The parser is called directly, without the executor and timeout of
CCJSqlParserUtil.parse, so nothing bounded it.

## Acceptance Criteria

- [x] Brackets nested deeper than 256 fail with 54001 before parsing, counted on JSQLParser's own
      tokens (a separate scanner could disagree with its lexer and hide brackets, third review):
      some of JSQLParser's lookahead (`FROM ((((t))))`) never checks its `interrupted` flag, so the
      budget below alone was defeated (second security review).
- [x] Parsing one statement stops after 5 seconds with 54001 (the parser's `interrupted` flag is set
      by a watchdog thread; a parse that finishes while interrupted is not trusted either).
- [x] Short statements still parse on the calling thread (one scheduled task per parse).

## Work Log

- 2026-10-20: QueryParser.parseWithin and PARSE_WATCHDOG; QueryParserTest covers 20000 levels.
- 2026-10-20: QueryParser.nestingChecked (MAX_NESTING); 14 nesting shapes timed at 256 and 20000
      levels: all under 0.2 s at 256; at 20000 all refused at once except CASE (no brackets, 2 s).
- 2026-10-20: QueryParser.checkNesting replaces nestingChecked: depth from the parser's token stream.
