# Electric pairs: plan

Typing an opener inserts its closer; typing the closer where it already is
steps over it. For every mode, with each mode's own pairs. Moved here from
the Markdown plan (`MARKDOWN-MODE.md`), where it was Phase 8; Markdown's
own pairs, the fence among them, are below.

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

