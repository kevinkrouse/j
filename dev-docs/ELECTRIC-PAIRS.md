# Electric pairs: plan

Typing an opener inserts its closer; typing the closer where it already is
steps over it. For every mode, with each mode's own pairs. Moved here from
the Markdown plan (`MARKDOWN-MODE.md`), where it was Phase 8; Markdown's
own pairs, the fence among them, are below.

The build plan — insertion points, the decision function, the undo and
vim-repeat contract, the migration of the existing electric commands,
and the tests — follows the design in this document.

## What others do

**Emacs, `electric-pair-mode` (elec-pair.el).** The pairs come from the
mode's syntax table (`electric-pair-pairs`, and `electric-pair-text-pairs`
in comments and strings).

- `electric-pair-skip-self`: typing a closer that is next steps over it.
- `electric-pair-preserve-balance` (on): no closer is inserted if there is
  already an unpaired one later, and a closer is not skipped if it would
  leave one unbalanced.
- `electric-pair-delete-adjacent-pairs` (on): Backspace on an opener with
  its closer right after deletes both.
- `electric-pair-open-newline-between-pairs` (on): Enter between `{` and
  `}` opens a line between them.
- `electric-pair-skip-whitespace`: a closer typed with only white space
  before the next closer skips over it.
- `electric-pair-inhibit-predicate`, by default
  `electric-pair-conservative-inhibit`: no pair after a character of word
  syntax or the same delimiter, or before a word.
- With a region, typing an opener or closer wraps the region in the pair.

**nvim-autopairs.** Rules, each with conditions.

- `ignored_next_char` = `[%w%%%'%[%"%.%`%$]`: no pair before a letter,
  digit, `%`, `'`, `[`, `"`, `.`, `` ` `` or `$`.
- `enable_check_bracket_line`: no closer if the line already has an
  unmatched one.
- `enable_moveright`: type over a closer.
- `enable_afterquote`, `enable_bracket_in_quote`: pair after a quote and
  inside strings.
- `map_bs`, `map_c_h`, `map_c_w`: Backspace, Ctrl-H and Ctrl-W delete the
  pair.
- `map_cr`: Enter between a pair opens a line.
- `check_ts`: with treesitter, no pair inside a string or comment.
- `disable_in_macro`, `disable_in_visualblock`, `disable_in_replace_mode`.
- `fast_wrap` (Alt-E): wrap what follows in a pair.
- `break_undo`: keep undo and `.` whole.
- Rules: `with_pair` (when to pair), `with_move` (when to type over),
  `with_del` (when Backspace deletes both), `with_cr` (when Enter opens a
  line). Rules are per file type.

**IntelliJ, Settings > Editor > General > Smart Keys.**

- Insert pair brackets `()`, `[]`, `{}`, `<>`; insert pair quote.
- Jump outside closing bracket or quote with Tab when typing.
- Surround selection on typing quote or brace.
- Insert pair `}` on Enter; smart indent; close block comment.
- Backspace: unindent; it also removes an empty pair.

## Edge cases they agree on

- **Type-over** only for a closer the pairing inserted, or one right
  after the caret: not one far along the line.
- **Quotes** are their own closers: `"` pairs only where a string can
  start -- not after a letter or digit (`don't`, `5"`), not after a
  backslash, not inside a string, where it closes or steps over.
- **Before a word**, no closer: `(|foo` stays `(foo`.
- **Balance**: no closer if one is already waiting unmatched.
- **Backspace** in an empty pair `(|)` deletes both.
- **Enter** in `{|}` opens a line between, indented.
- **A selection** is wrapped, not replaced.
- **Undo** takes back the pair in one step; vim's `.` repeats the typing
  as typed; a macro replays it the same.
- **Per mode**: no `'` in Lisp, where it quotes; in prose and comments
  `'` is an apostrophe; Markdown pairs `` ` ``, `*`, `_` only where
  emphasis or code can start.
- **Replace mode** and pasting do not pair.

## For j

- An `ElectricPairs` handler under `EditCommands.insertNormalChar`, so typing in
  simple and vim insert mode both go through it, with
  `Mode.getPairs()` giving a mode's pairs: `()`, `[]`, `{}`, `""` by
  default, `''` and `<>` where they pair.
- `electricPairs` (on), per mode as `JavaMode.electricPairs`, and the rules
  above as settings where others make them settings: type-over, balance,
  Backspace, Enter, wrapping a selection.
- Not inside a string or comment by the formatter's own state, as
  nvim-autopairs asks treesitter: `Formatter` can say what a column is.
- One undo step with the character typed; vim's `.` and macros record the
  typed character only, so replaying it pairs the same way.
- Markdown:
  - Typing the third backtick of ```` ``` ```` at the start of a line
    inserts a closing ```` ``` ```` on the line below and leaves the caret
    after the opening one, for the language name. In a fence, Enter after
    the language goes to the code.
  - `` ` `` pairs to `` `|` ``; `*`, `**`, `_`, `~~` pair only where
    emphasis can start, not at the start of a list item, where `* ` is a
    bullet.
  - `[` pairs to `[]`, and `](` to `]()`.
- `electricQuote` and the existing `electricOpenBrace` keep their bindings;
  the pairing applies to the characters typed.

# Electric pairs: implementation plan

The build plan for `ELECTRIC-PAIRS.md`. The design and the survey of
Emacs/nvim/IntelliJ live there; this is the how: the exact insertion
points, the decision function, the undo and vim-repeat contract, the
migration of the existing ad-hoc electric commands, and the test plan.
Every claim below is grounded in the current source.

## The shape

One decision function with an explicit operation, called from the
three active places a pair can act:

```
ElectricPairs.decide(Mode mode, Buffer buffer, Position dot, Operation operation, char c,
                     boolean hasSelection, boolean pairingSuppressed)
    -> Decision(action, pair)
action = INSERT_PAIR | INSERT_OPENER | TYPE_OVER | WRAP | SUPPRESS
       | DELETE_BOTH | OPEN_LINE | PLAIN
```

`Decision.pair` carries a normalized `{opener, closer}` for INSERT_PAIR
and WRAP; other actions need no pair payload. In particular, WRAP must
not treat a typed closer as the opening character.
`Operation` is INSERT, BACKSPACE or ENTER; `c` is used only for INSERT.
Backspace and Enter use the structural rules in their insertion-point
sections after the common off/suppression guard, with selection,
read-only and virtual-caret handling retained by their callers. They
must not fall through the printable-character decision table below.

`INSERT_OPENER` is the balance case: the opener inserts, no closer is
generated. `SUPPRESS` inserts nothing at all. Both are distinct from
PLAIN, which inserts the typed character unchanged.

The function is pure: it reads the buffer and the mode's pair table,
touches nothing. The caller captures `pairingSuppressed` from the
editor's suppression depth, so the function need not read editor state.
The callers act on the result inside one compound
edit. This matters because the same decision must come out the same on
vim `.` replay and j macro replay — the recording stores the typed
character only (VimInputHandler.java:386-391, Macro.java:132), so
replay re-enters the same call with the same context and re-derives
the pair. No state in the handler; the buffer is the state.

The call sites (forward Delete remains an optional future hook):

| Typed/pressed | Site | Decision used |
|---|---|---|
| any printable char | `EditCommands.insertNormalChar` (:244) | INSERT_PAIR / INSERT_OPENER / TYPE_OVER / WRAP / SUPPRESS / PLAIN |
| Backspace | `EditCommands.backspace` (:142) | DELETE_BOTH / PLAIN |
| Enter | `EditCommands.newlineAndIndent` (:200) | OPEN_LINE / PLAIN |
| Delete | `EditCommands.delete` (:112) | (optional) DELETE_CLOSER / PLAIN |

Paste and vim REPLACE never reach these paths: paste goes through
`ClipboardCommands.paste` (:336/:399) with whole-string inserts; vim
REPLACE goes to `VimActions.replaceTypedCharacter` (:851). Both are
safe by construction. Two paths that *do* reach `insertNormalChar`
must be suppressed explicitly — vim block insert and Ctrl-R register
insertion — see "Literal insertion" below.

## The pair table

`Mode.getPairs()`, a new default method on `Mode` beside
`getPairMatcher()` (Mode.java:331), mirroring its pattern:

```java
/** The mode's electric pairs: each entry is {opener, closer}. */
default String getPairs() {
    return "()[]{}\"\"";
}
```

A flat string of pairs, not a collection — cheap to build, cheap to
scan, and the modes that differ override with a literal:

- `LispMode`: `"()[]\"\""` — no `'` (quotes in CL), no `{}` as a
  typed pair is fine but `'` must never pair.
- `ClojureMode` (future): `"()[]{}\"\""` — `'` is quote/read-macro.
- `MarkdownMode`: single backticks can use the generic pair action;
  emphasis runs and fences use the Markdown override described below,
  outside the flat pair table.
- `HtmlMode`/`XmlMode`: `""` only; `<>` is tag syntax, handled by
  `electricCloseAngleBracket`'s tag logic, not the generic rule.

`PairTable` provides both `pairForOpener(c)` and
`pairForDelimiter(c)`, returning a normalized `{opener, closer}` or
no match. The latter searches both positions: `(` and `)` both resolve
to `{ '(', ')' }`, while `"` resolves to `{ '"', '"' }`. Opener-only
lookup drives pair insertion; either-side lookup drives selection
wrapping. The closer set is the odd positions. Cache the tiny value
object once per mode so `insertNormalChar` doesn't re-parse per
keystroke.

## The decision function

For INSERT, in order; first match wins. `c` is the post-`fixCase` character
(insertNormalChar:259 runs the hook after `fixCase`, so Html/Xml
tag-case rewriting and pairing agree). `pair(c)` is the closer mapped
from `c` in the mode's pair table; `closerOf(c)` is true when `c`
appears in a closer position. A delimiter can be both opener and
closer — `"` pairs to `"` — so the sets overlap and the order below
is what makes that work.

1. **Off or suppressed**: `electricPairs` false for this buffer, or
   the caller's `pairingSuppressed` is true (block insert, Ctrl-R
   register text — see "Literal insertion") → PLAIN. The property is
   mode-scoped like every other property (`JavaMode.electricPairs=false`
   works, the same mechanism as `JavaMode.editMode=vim`,
   Property.java:247).
2. **Selection present and `pairForDelimiter(c)` exists**: WRAP with
   that normalized pair (see below). Typing either `(` or `)` around
   selected `foo` produces `(foo)`, with the caret after `(`. A
   selection plus a character that is not a pair delimiter
   keeps the existing replace behavior — typing `x` over a selection
   replaces it, as it does today.
3. **Escaped quote**: `c` is a quote delimiter and the run of the
   mode's escape character (backslash in most modes) immediately
   before dot has **odd** length → PLAIN. Parity matters: one
   backslash escapes the quote, but two
   consecutive backslashes are themselves escaped, so the quote
   after them is *not* escaped and must type over the closer
   normally.
   Count the consecutive escape chars before dot; odd → the typed
   quote is escaped, insert literally and neither type over nor open
   a pair; even → fall through. Skipping after an odd run would leave
   the string unterminated; treating an even run as escaped would
   wrongly insert an extra quote instead of stepping over the closer.
   The escape char is mode-aware because it differs: backslash in
   C-family and Lisp, none in some modes.
4. **Type-over**: `closerOf(c)` and the char at dot is `c` → TYPE_OVER
   (skip, don't insert). Match the *typed* closer against the char
   under the caret, not `pair(c)`: `pair(c)` maps an opener to its
   closer, so typing `(` before `)` would wrongly skip the `)`, and
   a typed `)` has no `pair(c)` and could never type over. Require
   the typed char to itself be a closer and to equal the char at dot.
   A symmetric delimiter like `"` is in the closer set, so `"` at
   dot on `"` steps over; `"` with a non-quote at dot fails this
   rule and falls through to opener handling (rule 6).
5. **In string or comment**: `mode.lexicalState(buffer, dot)` is
   STRING or COMMENT → PLAIN. This generalizes the
   `electricSemi` context-check pattern (ElectricCommands.java:72-76)
   and the nvim-autopairs `check_ts` rule. See "Lexical context"
   for the mode-coverage caveat and the empty-pair exception.
6. **Opener typed** (`c` in the opener set):
   - the char before dot is a word character or the same delimiter →
     PLAIN (Emacs `conservative-inhibit`: `don't`, `5"`, `a"`).
   - the char at dot is a word character → PLAIN (`(|foo` stays
     `(foo`).
   - balance: an unmatched closer of `pair(c)`'s kind later on the
     line → insert the opener only, no closer (Emacs
     `preserve-balance`: `(|)` + `(` → `((|)`, not `((|))`).
   - else → INSERT_PAIR.
7. **Closer typed** (`c` in the closer set, not type-over from rule
   4):
   - balance: an unmatched closer of `c`'s kind later on the line →
     SUPPRESS — insert nothing (Emacs `preserve-balance`, nvim
     `enable_check_bracket_line`: `(|foo)` + `)` inserts nothing).
   - else → PLAIN (insert the typed closer as-is).
8. **Anything else** → PLAIN.

Note the two balance outcomes are different actions, not both PLAIN:
for an opener, balance means "insert the opener but generate no
closer"; for a closer, it means "insert nothing". A check whose both
branches insert the typed character would be vacuous.

Quote special case: `"` is its own closer, and the ordering handles
it without a special case in the handler. Rule 3 keeps an escaped
`"` literal; rule 4 fires when dot is on the closing `"` (step
over); rule 6's inhibit keeps `don't` and `5"` plain; rule 6's
INSERT_PAIR gives `""` with the caret between in neutral context. In
Lisp modes `'` is absent from the table entirely, so it is never
paired — the design's per-mode rule with zero special-casing in the
handler.

## Literal insertion

Two paths reach `insertNormalChar` with text that must not pair, and
neither is covered by the paste/REPLACE exemptions. Both need
suppression checked by rule 1, and the suppression must **nest**:
Ctrl-R can be typed during a block insert, so a shared boolean that
Ctrl-R clears would drop the block's suppression and let a later
opener pair on the first line only.

**Scoped suppression depth.** `Editor` carries an int
`literalInsertionDepth` with a scoped accessor:

```java
try (PairSuppression s = editor.suppressPairing()) { ... }
```

`suppressPairing()` increments the depth and returns a handle whose
idempotent `close()` decrements it once. Rule 1 tests `depth > 0`.
Method-local insertions use try-with-resources. A block-insert session
spans input events, so `VimState` owns its handle and closes it through
the session cleanup described below.

**Vim block insert.** `I`/`A`/`c` over a visual block enters ordinary
INSERT mode on the first line; the mark and the column-selection flag
are not set, so the handler fires there. On Escape,
`VimState.finishBlockInsert` (VimState.java:174-189) copies only
`blockInsertLine.substring(blockInsertOffset, dot.getOffset())` onto
the other lines. An auto-generated closer sits beyond the caret, so
it is excluded: the first line gets `(|)`, the rest get `(|`. Fix:
the block-insert session holds a suppression open from a successful
`beginBlockInsert` (:152) through first-line typing and the final copy.
`finishBlockInsert` releases it in `finally`, including its early-return
and copy-failure paths. The copied text is exactly what was typed.

**All block exits release the handle.** Add one idempotent
`releaseBlockInsert` cleanup that clears the replication state and
closes the stored handle, clearing its field before closing.
`finishBlockInsert` invokes it in `finally`. Preserve the successful
I/A landing position until Escape captures it. In `escape`, move
`takeBlockInsertLanding()` immediately after `finishBlockInsert` and
save the result in a local variable, before `setMode(NORMAL)` calls
`endInsert`. Apply that local landing position after the normal
insert-mode step-back, where Escape currently applies the landing.
Otherwise `endInsert` would erase it before the old take call. A
`cancelBlockInsert` wrapper also clears
that landing position and never copies text. Call cancellation from
`restartInsert` (an arrow cancels block replication) and `endInsert`
before its existing early return. `editorLeftBuffer` and a subsequent
`beginInsert` already call `endInsert`; both therefore release
suppression even without Escape. A failed session setup or an
exception that aborts the insert must use cancellation too. The handle
belongs to the editor on which the session began, and closing it must
not depend on whichever buffer is current later.

**Ctrl-R register insertion.** `insertRegister` (VimActions.java:437-444)
calls `handler.typeText(editor, register.text())`, and `typeText`
(VimInputHandler.java:831-843) feeds every character through
`typeInsertKey` → `insertNormalChar`. A register holding `(` would
insert `()`. The fix must preserve the repeat contract: suppressing
pairing only during the initial insertion would make `.` replay the
register *with* pairing, a different result. So the suppression
travels with the recording, and the recording must carry the
register's text as it was at insertion time — not the register name.

Recording `<C-r>{name}` is wrong: it rereads the register on replay,
so if the register changed between insertion and `.`, the repeat
inserts different text. The current `typeText` deliberately avoids
this by recording the resolved characters, so `.` re-inserts the
original text regardless of later register changes. Keep that
snapshot property.

**Representation: a literal-run token with a side-channel.** The
recording is a flat string tokenized by `KeyNotation.tokenize`, which
grabs `<`…first `>` as one token — so the literal text cannot be
embedded in a bracketed token (it may contain `<` or `>`, and the
tokenizer cannot nest). Instead:

- The handler keeps `List<String> literalRuns`.
- `typeText`, when called for a register insertion, appends the
  text to `literalRuns` and records the single token
  `<literal:N>` (N = index) instead of the per-character key names,
  in both the change recording and the session's `insertKeys` so
  counted inserts repeat it too. It holds a suppression open around
  the insertion loop so the live insert doesn't pair either.
- `dispatchReplay` recognizes a token with the `<literal:` prefix
  and replays `literalRuns.get(N)` **through the existing
  `typeText` loop** — with the suppression open and recording
  disabled — not through a raw-insert shortcut.

**Replay must reuse the live loop.** A raw `insertStringInternal` or
direct `insertChar` per character is *not* equivalent to the live
path and breaks three things: newline indentation (a register
containing `\n` must indent like Enter, which only
`typeInsertKey`'s ENTER branch does), REPLACE-mode behavior (the
live loop dispatches REPLACE to `replaceTypedCharacter`), and undo
(`insertStringInternal` adds no undo record of its own). So the
replay path is literally the same `typeText` call the live path
uses, with two differences: the suppression is held open so no
character pairs, and `recordingEdit` is off so the replayed
characters don't re-record. The snapshot text is what gets typed;
the register is never consulted.

The index is a plain integer, so the recording stays parseable; the
text lives in the handler while any current insert recording,
counted-insert keys, held insert, or `lastChange` refers to it. Do not
recycle an index while one of those recordings retains it. These are
Vim repeat recordings; j's keyboard macro is owned by `Macro`, not by
this handler, and Vim `q`/`@a` recording is not implemented. The
invariant holds: what `.` repeats is the same
literal text, captured once, never re-paired and never re-read from
the register.

## Lexical context

Rule 5 uses `mode.lexicalState`; reusing only the existing
`isInQuote`/`isInComment` defaults would be inaccurate:

- `AbstractMode.isInComment` returns `false` always (:782). Only
  JavaMode (:968), LispMode (:337) and XmlMode override it. Python,
  Shell, Markdown, Ruby, etc. inherit the `false` default, so pairing
  would stay active inside their comments.
- `AbstractMode.isInQuote` (:755) treats both `"` and `'` as quotes
  and scans only the current line. In Markdown prose an apostrophe
  (`don't`) opens a "string" and inhibits pairing for the rest of
  the line — wrong.

The fix is a normalized query backed by the formatter, but the
formatter is **not** a uniform source, and the naive versions of the
query are all wrong. Three facts force the design:

- `line.flags()` is the state **entering** the line, not its trailing
  state. At EOL after `# comment` the flags still say NEUTRAL, so
  pairing would be permitted inside the comment.
- The segment covering the char before the caret cannot determine the
  boundary either: after a closing quote the segment's format is
  still STRING, but the insertion position is neutral.
- Parser state and token format are **different encodings even within
  one formatter**: Python parser state 4 is COMMENT while segment
  format 4 is KEYWORD; Shell's string format 4 corresponds to
  parser state IDENTIFIER. A mapping built for one space misclassifies
  in the other.

**The correct mechanism: consume the prefix through the dot.** The
state the typed character inherits is the parser state *after*
consuming the line's text from its entering state up to the caret
offset — not the entering state, not the segment under the caret.
This is exactly what the correct existing overrides do
(`JavaMode.isInComment` :968 walks chars from `line.flags()` to
`pos.getOffset()`). Generalize that pattern:

```java
enum LexicalState { NEUTRAL, STRING, COMMENT }
```

**Formatter interface: query a position with the full parser context.**

```java
Optional<LexicalState> Formatter.lexicalStateAt(Line line, int bufferOffset)
```

The default returns `Optional.empty()` for an unsupported query;
NEUTRAL is an actual answer, not the unsupported sentinel. Each
formatter that supports the query owns the scan and returns the state
at the insertion boundary. The query has the full line, its entering
context and a cursor; it can inspect multi-character tokens and
advance by their actual lengths. It shares lexer helpers with the
formatter instead of duplicating a simplified quote scanner.

Do not reduce the lexer to `advanceState(char, int)` with its existing
state constants. Python triple quotes require delimiter lookahead,
escapes consume the following character, and shell heredocs retain
their terminator outside the integer state. Keep quote kind, escape
handling, delimiter runs and any carried terminator in a formatter-
specific scan context. Where line flags omit part of that context,
reconstruct it from preceding lines or expose a complete parse
checkpoint; do not assume that the flags alone are sufficient. Query
scans use local state and do not overwrite the highlighting parser's
mutable fields.

The cursor consumes the prefix up to the caret using those token
rules, preserving context when the caret is within a multi-character
delimiter. A closing quote consumed before dot yields NEUTRAL;
the caret inside a string or a trailing line comment yields STRING
or COMMENT. At offset zero use the complete entering context. At
EOL use the result after consuming the whole line, not `line.flags()`.

**Markdown block and inline context.** Its query decodes the actual
parser flags: `flags & BLOCK_MASK` identifies `IN_FENCE`,
`IN_COMMENT` and `IN_FRONT_MATTER`; the remaining fence bits carry
delimiter kind, length and language. A fence body is COMMENT for
pairing purposes, including an empty line at offset zero. An HTML
comment remains COMMENT until its closing marker is consumed, and
the position after that marker is NEUTRAL. Front matter is also
excluded from prose pairing. Outside those blocks, scan inline code
delimiters with their run lengths and escapes; an insertion position
inside a code span is COMMENT for pairing purposes. Emphasis and link
text remain NEUTRAL. `CODE`, `CODE_BLOCK` and `FENCE` are display
format IDs and must never be interpreted as parser states.

**Mode dispatch and parse freshness.**
`Mode.lexicalState(Buffer, Position)` returns `LexicalState`. Modes
with correct existing overrides (Java, Lisp, Xml) can adapt their
current scans directly. Otherwise consult the buffer's formatter
query, falling back to `isInQuote`/`isInComment` only when unsupported.
Python, Shell and Markdown implement the position query as part of
this feature. Before invoking the pure decision function, callers
refresh dirty parse metadata under the existing buffer lock so a
preceding edit cannot leave stale entering context. The query itself
only reads that metadata and scans text.

**No offset conversion needed.** The query receives actual buffer
characters and offsets. Its lexer helpers must accept those buffer
coordinates rather than indexing detabbed display segments.

**Empty-quote exception for Backspace.** At the caret between two
quotes (`"|"`), both the default scanner and LispMode report STRING.
Allow DELETE_BOTH only when the adjacent characters form a configured
symmetric quote pair, the left quote is unescaped, and the lexical
state immediately before that left quote is NEUTRAL. This identifies
an actual empty string rather than quote characters within another
string or comment. The mode's lexer must also identify the quotes as
complete single-character opening/closing delimiters, not part of a
longer delimiter such as Python triple quotes. Earlier, already-closed strings on the same line
do not disqualify the pair. For all other pairs retain the ordinary
STRING/COMMENT exclusion; adjacency alone must never bypass it.
For example, Backspace inside `"(|)"` deletes only `(`, and between
a closing quote and the next string's opener it deletes only the left
quote. Do not weaken the general insertion-context guard to implement
this Backspace-only exception.

## Insertion points

### 1. `EditCommands.insertNormalChar` (:244)

Current order: column-selection bail (:245) → `checkReadOnly` (:249)
→ `lockWrite` (:251) → `fixCase` (:259) → mark branch (:260,
delete+replace) → WRAP branch (:266) → bare `insertChar` (:277) →
`moveCaretToDotCol` (:283).

Insert the handler between `fixCase` and the mark check. The action
switch below is the insertion stage; for electric-brace modes it runs
inside the shared brace coordinator described under migration. Its
early returns return from that stage, not from the coordinator's
post-insertion indentation and compound-edit cleanup:

```java
c = editor.getMode().fixCase(editor, c);
// Refresh dirty parse metadata before the read-only decision/query.
Decision decision = ElectricPairs.decide(mode, buffer, dot, Operation.INSERT, c,
    editor.getMark() != null, editor.isPairingSuppressed());
switch (decision.action()) {
    case INSERT_PAIR:   insertPair(editor, decision.pair().opener(), decision.pair().closer()); return;
    case INSERT_OPENER: editor.insertChar(c); return; // balance: no closer
    case TYPE_OVER:     typeOver(editor); return;
    case WRAP:          wrapSelection(editor, decision.pair().opener(), decision.pair().closer()); return;
    case SUPPRESS:      return; // balance: insert nothing
    default: break; // PLAIN falls through to the existing body
}
```

`insertPair`: one `beginCompoundEdit`/`endCompoundEdit` around
`insertChar(opener)` + `insertChar(closer)` + `addUndo(MOVE)` +
`dot.skip(-1)` + `moveCaretToDotCol()`. `Editor.insertChar` already
adds its own `LINE_EDIT` (Editor.java:1846) — do NOT add a second
one; the compound edit is what makes the two inserts one undo step.
The `MOVE` undo for the caret-back is the `electricQuote` pattern
(:309-324). The trailing `moveCaretToDotCol()` is required, not
optional: `insertChar` syncs the displayed caret after each insert
(Editor.java:1852), but `dot.skip(-1)` moves only the dot, and the
early `return` bypasses `insertNormalChar`'s own final
`moveCaretToDotCol` (:283) — without it the caret renders after the
closer while the dot sits between the pair.

**Every early-return path must sync the caret.** The switch's `return`
skips the epilogue, so each case that moves the dot — `insertPair`,
`typeOver`, `wrapSelection` — ends with `moveCaretToDotCol()`.
`INSERT_OPENER` and `SUPPRESS` don't move the dot beyond what
`insertChar` already synced, but a trailing sync is harmless and
keeps the contract uniform.

`typeOver`: `addUndo(MOVE)` + `dot.skip(1)` + `moveCaretToDotCol`.
No buffer change — see the vim truncation note below for why that is
subtle in insert mode.

### 2. Selection wrap — the mark branch (:260)

Today a selection is replaced (deleteRegion + insertChar). The design
says wrap. `wrapSelection` copies `insertParentheses`'s mark handling
(ElectricCommands.java:243-262) verbatim in structure: order
begin/end, `MOVE` undo, insert closer at end, `MOVE` undo, insert
opener at begin, caret left after the opener. One compound edit.
The opener and closer are the normalized values from
`pairForDelimiter(c)`, regardless of which side the user typed.
Selecting `foo` and typing `(` or `)` wraps it as `(foo)`; typing
`"` wraps it as `"foo"`. Neither the lookup nor the wrapper may use a
typed `)` as an opener or attempt an opener-only lookup for it.
`PARENS_REQUIRE_SPACES` is honored when the normalized opener is `(`,
including a wrap triggered by `)` — it is a paren property, not a
general one.

### 3. Backspace — `EditCommands.backspace` (:142)

The char-delete branch is :157-161 (`dotOffset > 0` → LINE_EDIT +
moveLeft + deleteNormalChar). Before it, test: the char left of dot is
an opener and the char at dot is its closer, and the position is not
inside a string/comment (or it passes the precise empty-quote
exception above) → delete both in one compound edit (LINE_EDIT
covers the splice; delete the closer first, then the opener, or one
`deleteNormalChar` twice inside the compound). The caret lands where
the opener was.

Vim routes here: `typeInsertKey` calls `EditCommands.backspace`
(VimInputHandler.java:850). Vim REPLACE goes to
`VimActions.replaceBackspace` (:898) and never sees this — replace
mode doesn't pair, by construction.

**Ctrl-W/Ctrl-U interplay**: `state.insertDeletedBack(editor)`
(VimInputHandler.java:851) tracks the insert-start stop and assumes
one char per backspace. Deleting both chars with one backspace moves
the stop by two; verify against the
`ctrlWStopsOnceWhereTypingBegan` tests (VimInsertModeKeysTest.java:72-76)
and adjust `insertDeletedBack` to take the deleted count if the stop
logic breaks. This is the highest-risk integration point; test it
first.

### 4. Enter — `EditCommands.newlineAndIndent` (:200)

After the common off/suppression guard, OPEN_LINE additionally requires
`electricPairs.openLine`, no selection, a NEUTRAL lexical context,
and an adjacent configured structural pair such as `{|}`. Quote,
comment and inline-code contents do not use this expansion; the
empty-quote Backspace exception does not apply to Enter. Otherwise
retain ordinary `newlineAndIndent`. The order of
operations matters: **create both lines first, then compute the
interior indentation.** Inserting one separator and indenting before
the second split leaves the closer on the interior line, and
`JavaMode.getCorrectIndentation` sees the `}` and returns the outer
indentation — the middle line lands at the wrong column. The correct
sequence, in one compound edit:

1. Insert a line separator at dot (the closer moves to line 2).
2. Insert another separator at dot (the closer is now on line 3; the
   empty interior is line 2).
3. Compute the interior indentation with the closer *not* on the
   interior line — `getCorrectIndentation` on the empty line 2 with
   the opener on line 1 above gives the body indent.
4. Set line 2's indentation; reindent line 3 (the closer)
   independently with its own `getCorrectIndentation` call.
5. Caret at the end of the indented line 2.

The precedent for "insert + reindent atomically" is
`electricBraceInternal`'s blank-line branch (:171-189) and
`insertBraces` (:223); the difference is that `insertBraces`
reindents the closer line separately, which is the part to copy.

Plain `newline` (:188) gets the same treatment only if the design
wants it in non-indent contexts; default: `newlineAndIndent` only.

### 5. Delete forward — `EditCommands.delete` (:112)

Optional, low value: typing Del on a closer deletes it (the pair
collapses). The design lists it under nvim's `with_del` for
backspace; forward-delete over a closer is not in the edge-case list.
Skip in v1; the hook point is :123 if wanted.

## The vim contract

The recording stores the typed key only
(VimInputHandler.java:386-391: `recording.append(name)` where `name`
is the key as typed, before insertion). Replay re-enters
`insertNormalChar` with the same char, so a handler *inside*
`insertNormalChar` re-pairs automatically. Three rules follow:

1. **Never record the closer.** The handler must not append to the
   vim recording or route the closer through a separately-recorded
   command. It lives inside `insertNormalChar`; the recording never
   sees it.
2. **Determinism.** Same buffer context → same decision. The handler
   reads only the buffer, the mode table, and properties. No
   "remembered that I inserted a closer" state — on replay the buffer
   is in the same state and the decision re-derives.
3. **The no-op truncation trap does not apply to plain typing.**
   `runInInsert` (VimInputHandler.java:300-328) truncates the
   recording when a *binding* leaves `buffer.getModCount()`
   unchanged (:315-318) — but plain typed characters never enter
   `runInInsert`. In the live path, an unshifted, unchorded,
   non-named key returns `Result.PASS_THROUGH` at :264 and reaches
   `insertNormalChar` via `handleKeyMapEvent` → the Dispatcher
   fallback (Dispatcher.java:294-304); in the replay path,
   `dispatchReplay` calls `typeInsertKey` directly (:819), never
   `runInInsert`. So a TYPE_OVER that changes no buffer cannot be
   truncated from the recording. The trap only bites chorded or
   named keys routed through `runInInsert` — none of which pair.
   Confirm with a test: `i(` then `)` then `.` must produce
   `()()` — the `(` pairs, the `)` types over, the `.` replays
   `(` (pairs) and `)` (types over). The recording is `i()<Esc>`;
   `commitInsert` keeps it because only a bare `i` with nothing
   after is dropped (`recordedInsert` returns null only when
   `insertRestarted && recording.length() == 1`, :734-738).

j macros: `Macro.record` (:132) records the `Character` before
`insertNormalChar` (Dispatcher.java:302-303); playback (:157) calls
`insertNormalChar` per char inside one buffer-level compound edit
(:150). Same re-derivation property; nothing to do beyond keeping the
handler inside `insertNormalChar`.

## Migrating the existing electric commands

Today `)` and `{` are *bound* to electric commands in several modes —
JavaMode:125 (`{`→electricOpenBrace), :136 (`)`→closeParen),
LispMode:98 (`)`→closeParen), PerlMode:81/:88, ShellMode:61. A bound
key never reaches the catch-all, so the new handler would not fire for
those characters in those modes. The migration is the bulk of the
work, and it is a cutover: the old bindings go away.

- **`)` → `closeParen`**: `closeParen` (ElectricCommands.java:32) is
  `insertNormalChar(')')` + a timed match-flash. The flash is the
  separate behavior to retain. Extract a `flashMatchingParen` helper
  that never inserts or calls `insertNormalChar`, and invoke it after
  inserting or typing over `)`. Unbind `)` in the four modes.
  Keep `closeParen` as a compatibility wrapper around
  `insertNormalChar(')')`, so the Lisp shell's explicit call
  (LispShellBuffer.java:350) still inserts and gets exactly one flash.
  The handler calls only the flash helper, never the wrapper; this
  avoids recursive insertion and preserves the shell command.
- **`{` → `electricOpenBrace`**: the blank-line behavior (clear the
  line, insert brace, indent, eol, optional auto-newline) is a
  different feature — it's about typing `{` on an empty line to get
  a block, not about pairing. But the live and replay paths diverge
  if the binding is retained as-is: live, a blank-line `{` goes to
  `electricOpenBrace`'s blank-line branch, which inserts directly
  and never reaches the pairing handler; vim replay calls
  `insertNormalChar('{')` directly, which *does* reach the handler
  and pairs. Same recorded key, same starting buffer, different
  result — a repeat-contract violation. Move **both** the blank-line
  preparation and the mid-line post-insertion indentation into one
  mode-aware coordinator under `insertNormalChar`. Add a mode hook
  enabling the existing electric-brace behavior for modes that bind
  it today (Java and derived modes, Perl, and optional Tcl); other
  modes keep generic brace pairing without acquiring Java-style
  reindentation. Migrate the extension bindings in the same cutover.
  `electricOpenBrace` becomes a compatibility wrapper that calls
  `insertNormalChar('{')` once. The shared coordinator must never
  call that wrapper or recursively re-enter `insertNormalChar`.
  Its sequence, inside one compound edit, is:
  1. Unless literal suppression is active, capture the pre-edit
     blank-line/selection context and perform any legacy blank-line
     preparation before deriving the pair decision.
  2. Apply the normal pair decision once, including plain insertion
     when `electricPairs` is off. The insertion stage's early returns
     must still reach the coordinator's post-processing.
  3. Reindent the brace line once for both blank and nonblank input,
     including a typed-over `}`. A SUPPRESS decision performs no
     reindentation. No wrapper adds a second indent call.
  4. Preserve optional `AUTO_NEWLINE` behavior on originally blank
     lines. If `{}` was generated, retain the interior caret and use
     the same Enter decision when auto-newline is enabled, including
     its `electricPairs.openLine` setting; do not move to EOL past
     the generated closer before splitting.
     With pairing off, preserve the legacy single-brace behavior.
  Literal register and block insertions bypass this coordinator's
  brace transformations as well as pairing. Live typing, Vim replay,
  and j macro playback all enter the same coordinator with the same
  mode/context. Test an under-indented, nonblank Java line as well as
  a blank one; merely sharing the blank-line branch is insufficient.
- **`}` → `electricCloseBrace`**: same treatment as `{` — the
  blank-line and mid-line formatting use the shared coordinator;
  the binding becomes a wrapper around `insertNormalChar('}')`.
  Type-over for `}` is the handler's rule 4, followed by the same
  single reindent pass in live typing and replay.
- **`electricQuote`** (Lisp Ctrl-Shift-`'`, :112): a different
  binding (Ctrl-Shift), no collision with typed `"`. Leave it.
- **`insertParentheses`** (Ctrl-Shift-9 in Java/Lisp/Perl): the
  explicit wrap command. Keep it; the WRAP path in the handler is
  the same code, and the explicit command stays for users who want
  wrapping without pairing.
- **`electricCloseAngleBracket`**: XML/HTML `>` is tag syntax, not a
  pair. Leave it; `<>` is not in those modes' pair tables.

The cutover rule: remove the `)` bindings; retained brace bindings
delegate once to `insertNormalChar` and have no independent insertion
or formatting behavior. Shared formatting and the match-flash helper
never call those compatibility wrappers.

## Settings

- `electricPairs` (boolean, default true), mode-scoped:
  `JavaMode.electricPairs=false` disables per mode, the
  `JavaMode.editMode=vim` mechanism (Property.java:247).
- Sub-rules as settings only where other editors make them settings
  and users actually toggle them: `electricPairs.typeOver`,
  `electricPairs.balance`, `electricPairs.deletePairs`,
  `electricPairs.openLine`. Default all true. Resist adding a
  setting per rule until a user asks; the design's edge-case list is
  the default behavior.
- Per-char opt-out is the pair table, not a setting: a mode that
  doesn't want `'` to pair omits it from `getPairs()`.

## Markdown specifics

From the design, mapped to the handler:

- The triple-backtick fence: typing the third `` ` `` at line start
  inserts a closing ```` ``` ```` on the line below, caret after the
  opening fence for the language name. This is not the generic
  INSERT_PAIR (the closer is on another line); it's a
  `MarkdownMode`-specific rule checked before the generic one — a
  `getPairs()` entry with a predicate, or a small override hook
  `Mode.electricOverride(editor, c)` consulted by the handler before
  the generic decision. Decision: the override hook, because the
  fence rule needs multi-line insertion and the generic table can't
  express it.
- `` ` `` pairs to `` `|` `` inline: generic INSERT_PAIR.
- `*`, `**`, `_`, `~~` are **delimiter runs**, not single-char pairs,
  and the flat two-char table cannot express them. The generic rule
  would make the first `*` insert `**` and the second type over its
  closer, leaving the text outside an unclosed strong opener. Model
  them as run-aware transitions in the `MarkdownMode` override, not
  as table entries with a predicate:
  - **Opening** (caret immediately after the run, content empty):
    typing `*` where emphasis can start grows the run by one — run 0
    → `*|`, run 1 → `**|`, run 2 → `***|`. No closer yet. Same for
    `_` and `~`.
  - **Closer generation** (the transition the earlier draft lacked):
    typing a non-whitespace content character while the caret is
    immediately after a run classified as an **unmatched opener** by
    the delimiter analysis below — the run is complete
    (length 1, 2 or 3), the content between the run and the caret is
    empty, and there is no closer after the caret → insert the
    content char and the matching closer run after the caret. `**|`
    + `b` → `**b|**`; `*|` + `b` → `*b|*`. The content char must be
    non-whitespace — `** ` is not emphasis, so a space after the
    opener does not generate a closer.
  - **Classify runs by matching them.** Extract the formatter's
    delimiter recognition and matching into a shared, read-only
    `MarkdownDelimiters` helper used by highlighting and the override.
    It scans the inline text with a stack of eligible opener runs,
    respecting marker kind and length, escaping, code spans, and the
    mode's opening/closing rules. When a run can close an earlier
    opener, record that match before considering it as a new opener.
    A run that has already closed emphasis is not eligible for closer
    generation, even if its immediate surroundings would also allow
    opening. For an empty opener at the caret, evaluate opening
    eligibility with the prospective first content character included
    in a temporary text view; do not mutate the buffer to classify it.
  - **Punctuation does not establish the role.** Both `*b*|` and
    `*b!*|` end in a matched closer. In the latter, the final `*` is
    preceded by punctuation, so a predecessor-only opening predicate
    would incorrectly accept it. Typing `c` must produce `*b*c|` and
    `*b!*c|`, respectively, with no additional star. Use the actual
    earlier-opener match, not a word/space/punctuation shortcut.
  - **Locating the opener**: adjacency identifies the candidate run
    before the first content character, but determining its role
    requires the inline scan above. Later content inside an existing
    pair is plain insertion. The analysis may be cached only with a
    buffer modification stamp; it must give the same result from the
    same text during live typing and replay.
  - **Closing**: typing the delimiter when the caret is immediately
    before a run type-overs **one** delimiter per keystroke, not the
    whole run — the same single-char type-over as rule 4. `**bold|**`
    + `*` → `**bold*|*`; the second `*` → `**bold**|`. Skipping the
    whole run on the first keystroke would leave the second `*`
    stranded after the closer. The user can also delete the generated
    closer and close manually.
  - `~`/`~~` (strikethrough) follow the same run logic.
  - The shared analysis excludes list markers such as `* ` from
    emphasis openers. Opening eligibility depends on both neighboring
    text and matching context, not only the character before a run.
  - **Replay safety**: every transition reads the buffer at the
    moment of the keystroke, so `**b` typed incrementally
    (`*`→`*|`, `*`→`**|`, `b`→`**b|**`) reproduces exactly on `.`
    — each replayed keystroke sees the same buffer state.
  - **Test the incremental case**: `**b` must yield `**b|**` with
    the closer generated by the transition. The fully-typed
    `**bold**` test is insufficient — it passes with pairing
    disabled, so it proves nothing about closer generation.
  The run state is read from the buffer at each keystroke — no
  remembered state. This is the one place the generic
  INSERT_PAIR/TYPE_OVER pair is insufficient, and why the override
  hook exists.
- `[` pairs to `[]`, and `](` typed after a link text pairs to
  `]()`: the second is a two-char trigger; handle as a
  `MarkdownMode` override (the handler sees `(` with `]` before the
  dot — a rule in the override, not the table).

## Test plan

Harness: `EditorHarness` (test/src/org/armedbear/j/EditorHarness.java)
— `create(text).mode(Mode)` / `.vim()`, `keys(...)` presses through
the real Dispatcher (:337), `assertText`, `assertCursorAt`.

Register fixtures use supported yanks or the register API, for example
`VimRegisters.getInstance().yanked('a', "(", VimRegisters.Type.CHARWISE)`.
Changing a fixture register through that API must not replace the
insert change that `.` is meant to repeat. Vim `q` recording is absent
and `@a` is a no-op in the current handler; neither is a test setup or
a macro playback mechanism for this feature.

New file `test/src/org/armedbear/j/ElectricPairsTest.java`:

1. **Insert pair**: `keys("(")` → `()` with caret between; `u`
   removes both in one step.
2. **Symmetric delimiter pairs**: empty buffer + `keys("\"")` →
   `""` caret between (the closer-set membership must not block
   opener handling); second `"` types over.
3. **Type-over matches the typed closer**: `(|)` + `keys(")")` →
   `()` caret after; `(|)` + `keys("(")` → `((|)` — typing `(`
   before `)` must NOT skip the `)` (the `pair(c)` bug).
4. **Balance, closer suppressed**: `(|foo)` + `keys(")")` →
   unchanged (SUPPRESS inserts nothing).
5. **Balance, opener only**: `(|)` + `keys("(")` → `((|)` — the
   opener inserts, no closer generated.
6. **Inhibit**: `a|` + `keys("\"")` → `a"` no pair (word char
   before); `(|foo)` + `keys(")")` → unchanged.
7. **Escaped quote, odd backslashes**: from an empty buffer, type
   `"` to create an empty quote pair with the caret between. Type one
   backslash and then `"`; the escaped quote inserts before the
   existing closer and the caret remains inside the string. Repeat
   with three backslashes. Assert text and caret, not only state.
8. **Escaped backslash, even backslashes**: from the same empty-pair
   fixture, type two backslashes and then `"`. The final keystroke
   leaves the text unchanged and moves the caret over the existing
   closer. Repeat with four backslashes; a naive "preceded by
   backslash" check fails these cases.
9. **Selection wrap only for pair chars**: select `foo`, `keys("(")`
   → `(foo)` caret after `(`. Repeat with `keys(")")` → the same
   `(foo)` and caret, and with `keys("\"")` → `"foo"`. Cover both
   selection directions and one-step undo. Select `foo`, `keys("x")`
   → `x` (replace, unchanged behavior).
10. **Caret sync**: after INSERT_PAIR the displayed caret is between
    the pair, not after the closer — assert the caret column, not just
    the text (the early-return bypasses the epilogue).
11. **String/comment**: inside a string, `(` does not pair; typing
    `"` inside a string closes it. Test in a mode with a real comment
    override (Java `// x |`) and one that needs the formatter-backed
    query (Python `# x |`). Use trailing spaces so the word-inhibit
    rule cannot hide a broken comment query. Markdown `don't |`
    remains NEUTRAL and allows a bracket pair.
12. **Lexical state at EOL**: Python `# comment|` reports COMMENT at
    the caret — the state after consuming the prefix through the
    dot, not the entering `flags()` (which says NEUTRAL and would
    permit pairing). `"foo"|` reports NEUTRAL: the closing quote's
    transition consumed the string.
    Also check the following context cases directly and by typing a
    bracket at a position with whitespace on either side:
    - A Python triple-quoted string containing an isolated `"` and
      an escaped quote remains STRING; its closing triple delimiter
      returns to NEUTRAL. Include continuation lines and a tabbed
      prefix.
    - A shell heredoc body remains STRING until the exact stored
      terminator line; a partial terminator does not end it, and the
      following ordinary line is NEUTRAL. Use distinct terminators
      in sequential queries to detect leaked mutable scan context.
    - A blank Markdown fence body at offset zero reports COMMENT,
      both without a language and with language/fence metadata bits
      set; typing `(` is literal. Repeat inside an HTML comment and
      inline code, and verify NEUTRAL after their closing delimiters.
    - Change an earlier line's string or fence delimiter and query a
      later line immediately, before repaint, to verify parse
      freshness.
13. **Empty quote pair backspace**: `"|"` + BS → empty, one undo
    step (the in-string guard must not block DELETE_BOTH here).
    Repeat after another closed string on the same line. Quotes in a
    comment, escaped quotes inside a string, brackets inside string
    contents, quotes within a triple delimiter, and the boundary
    between two nonempty strings must retain ordinary single-character
    Backspace behavior.
14. **Backspace both**: `(|)` + BS → empty, one undo step.
15. **Enter between**: `{|}` + Enter → three lines, caret middle,
    closer on line 3, interior indented one level, closer at outer
    indent (the create-then-indent ordering).
    Verify quotes and bracket characters inside strings/comments
    retain ordinary Enter behavior, as do suppressed insertion and
    `electricPairs.openLine=false`.
16. **Undo granularity**: every multi-char decision is one `u`.
17. **Per mode**: Lisp `'` never pairs; default modes `"` pairs.

Repeat and literal-insertion cases go in `VimRepeatTest.java` or a
new `VimElectricPairsTest.java`; case 22 tests j's keyboard macro in
simple edit mode in `ElectricPairsTest.java`.

18. `i(` → `()`; `.` on a fresh line → `()` again (re-pairs).
19. `i()` (type-over) then `.` → `()` — the typeover must survive
    the recording (the truncation trap above).
20. `i(` + BS → empty (delete-both through vim's backspace path);
    `.` reproduces it.
21. Ctrl-W after `i(abc)` deletes per the stop rules; the
    delete-both stop adjustment doesn't break
    `ctrlWStopsOnceWhereTypingBegan` (VimInsertModeKeysTest.java:72-76).
22. **j keyboard macro**: with an isolated macro fixture in simple
    edit mode, execute `startMacro` through
    `h.editor().executeCommand`, type `(` through `h.keys`, and
    execute `endMacro`. Move to a fresh line and execute
    `playbackMacro`; assert the pair and interior caret are reproduced
    and one undo removes the replayed pair. Reset macro state in the
    fixture so the test does not invoke an overwrite dialog. This
    exercises the existing `Macro` commands, not Vim `q`/`@a`.
23. **Block insert**: visual-block select three lines, `I(`, type
    `(`, Escape → every line gets exactly `(`, no stray `)` on any
    line (the literal-insertion suppression).
    Also cancel a block insert with an arrow and verify subsequent
    ordinary `(` typing pairs. Switch buffers during block insertion
    without Escape, then enter ordinary insert mode in the other
    buffer and verify pairing works. Assert suppression depth returns
    to zero after normal completion, empty insertion, cancellation,
    and a fixture-injected copy/setup failure; repeated cleanup is
    harmless and never copies cancelled text. Retain the existing I/A
    Escape landing-position assertions, exercising the actual
    `finishBlockInsert` → `setMode` → `endInsert` sequence and the
    local landing capture before mode exit.
24. **Nested suppression**: block-append at EOL on equal-length lines,
    Ctrl-R a register containing one space, then type `(`. The char
    before the caret is whitespace and there is no following word,
    so pairing would occur if the inner scope wrongly cleared the
    outer one. Assert no closer appears on any line and the block's
    suppression stays active until Escape. As a control, repeat the
    same space-plus-opener insertion outside a block and assert `()`
    is generated. A register ending in `x` would mask the regression
    through the word-before-caret inhibition and is not this fixture.
25. **Ctrl-R literal**: initialize register a to `(`, `i` + Ctrl-R a
    → `(` inserted, not `()`; `.` repeats the literal `(`, not a
    pair.
26. **Ctrl-R snapshot**: initialize register a to `(`, insert with
    `i<C-r>a<Esc>`, then replace a's text with `)` through the
    register API. `.` still inserts `(` — the text recorded at
    insertion time, not the register's current contents.
27. **Ctrl-R newline indents**: a register containing `a\nb` replayed
    through the literal path indents the second line like Enter
    (proves the replay reuses `typeInsertKey`, not a raw insert).
    Also count an insert containing a literal register run and assert
    every repetition preserves its text and pairing suppression.
28. **Brace formatting replay**: compare live and `.`-replayed `{`
    and `}` on identical blank and under-indented nonblank Java
    contexts. For example, a method header starting at column zero
    inside a class must gain the same indentation in both paths.
    Assert text, indentation, caret and undo, including closer
    type-over. Cover `AUTO_NEWLINE`, `electricPairs.openLine` and
    `electricPairs` on/off,
    generic braces in a mode without electric-brace formatting, and
    literal register/block insertion bypassing the coordinator.
29. **Markdown closer generation**: `i**b` → `**b|**` — the closer
    is generated by the first content char after the complete
    opener run. The fully-typed `**bold**` case is NOT the test;
    it passes with pairing disabled.
30. **Markdown closer role**: in one insert session, `*b*` → `*b*|`
    (the second `*` closes); then `c` → `*b*c|`, with no additional
    closer. Repeat with `*b!*` followed by `c` → `*b!*c|`: punctuation
    before a matched closer must not make it a new opener. Cover
    strong emphasis as well, escaped markers, and markers in inline
    code, and retain the full `**bold**` typing test alongside the
    incremental closer-generation test.
31. **Markdown single-char closer skip**: `**bold|**` + `*` →
    `**bold*|*` (one delimiter skipped); second `*` → `**bold**|`.
    The first keystroke must not skip the whole run.
32. **Markdown run replay**: type `i**b<Esc>`, move to another empty
    line, then `.` reproduces `**b|**` before leaving insert mode
    (each replayed keystroke sees the same starting buffer context).
    Assert the final normal-mode caret after Escape separately.
33. **Markdown no closer on space**: `i** ` → `** |` — a space
    after the opener does not generate a closer.

Conformance: run `tools/vim-conformance.clj` + `VimConformanceTest`
after the migration; the `passing.txt` ratchet (VimConformance.java:271-275)
must not regress. The upstream corpus has no electric-pairs cases
(vim doesn't pair), so the ratchet only guards against breaking
existing insert-mode behavior.

## Build order

1. `PairTable` with opener-only and either-delimiter lookup returning
   normalized pairs, `Mode.getPairs()` default and per-mode overrides
   (Lisp drops `'`). No behavior change yet.
2. `ElectricPairs.decide` pure function + unit tests on the decision
   table (no editor needed — construct positions in a buffer).
   Includes the symmetric-delimiter ordering, the typed-closer
   type-over match, the escape-run parity guard, the WRAP membership
   gate with normalized pairs for typed closers, and the two distinct
   balance actions. Tests 2-9.
3. Wire into `insertNormalChar`: INSERT_PAIR + TYPE_OVER + WRAP,
   compound-edit discipline, and the caret-sync epilogue on every
   early return. Tests 1, 9, 10, 16, 17.
4. Vim repeat tests 18-19 — the truncation trap. Fix before going
   further; everything else depends on replay being clean.
5. Lexical context: the `LexicalState` enum, per-formatter
   `lexicalStateAt` queries sharing the actual lexer context and
   cursor, Markdown block-flag decoding and inline-code scanning,
   parse freshness, and the empty-pair exception. Test lookahead,
   triple quotes, heredoc terminators and boundaries, not only
   single-line quote toggling. Tests 11-13. (Rule 5 is live from
   step 3 with the old `isInQuote`/`isInComment` defaults; this step
   makes it correct for the modes the defaults get wrong.)
6. Backspace delete-both + Ctrl-W stop fix. Tests 13-14, 20-21.
7. Enter-between, with the create-then-indent ordering. Test 15.
8. Literal insertion: the scoped `literalInsertionDepth` on `Editor`,
   the block-insert session holding it open, the Ctrl-R literal-run
   token with the text snapshot, and the replay path reusing
   `typeText` with suppression held and recording off. Centralize
   block cleanup for completion, arrows, buffer switches and failures,
   capturing a successful landing locally before mode exit clears
   session state. Use a nested-suppression fixture with no word-inhibit
   condition on either side of the caret.
   Use supported register fixtures. Tests 23-27; test 22 separately
   covers the existing j keyboard macro commands in simple mode.
9. Migration: unbind `)` in Java/Lisp/Perl/Shell, extract the
   insertion-free match-flash helper, and preserve the Lisp shell's
   `closeParen` wrapper. Move all `electricOpenBrace`/
   `electricCloseBrace` blank-line preparation and mid-line
   reindentation into the shared mode-aware coordinator, including
   extension modes. Test 28 and a Lisp shell close-paren smoke test.
   Run the full suite + conformance ratchet.
10. Markdown overrides: the fence, the delimiter-run transitions
    including shared delimiter matching for opener/closer roles,
    punctuation-ending content, closer generation, the single-char
    closer skip, and `](`. Tests 29-33.
11. Settings pass: `electricPairs` + the four sub-rules,
    mode-scoped.

Steps 1-4 are the core; 5-7 are the context and the two extra call
sites; 8 is the literal-insertion contract; 9 is the cutover; 10-11
are mode-specific polish. Each step is independently shippable
behind the property.

