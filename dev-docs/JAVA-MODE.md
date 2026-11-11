# Modern Java syntax: plan

Bring j's Java mode from Java 1.x to Java 8–25. The scanner, tagger,
keywords, folding and indentation are all regex and hand-written state;
none of them know what a record, a text block or a lambda is. No
treesitter here — that is its own TODO. This updates what j already has.

The golden test (`test/src/org/armedbear/j/GoldenTest.java` over
`test/golden/sample.java`) is the spine: it captures, per line, the
format segments, the indentation, the tags and the comment round-trip,
and diffs against `sample.java.golden`. `GOLDEN_UPDATE=1 bb test`
rewrites the goldens; reading that diff line by line is the proof.

## j today

- **Keywords** (`mode/java/JavaMode.keywords`, 52 words) are Java 1.x.
  No `enum`, `record`, `sealed`, `permits`, `var` or `yield`. `const`
  and `goto` are there as reserved words that never compile.
- **The formatter** (`JavaFormatter`) has nine formats — text, comment,
  string, identifier, keyword, function, operator, brace, number — and
  `JAVA_FORMAT_LAST = 8`, which `CFormatter` and `HtmlFormatter` number
  after. No annotation face: `@Override` comes out as plain text plus an
  identifier (golden line 23). No text blocks. Numbers read hex and the
  `l`/`u` suffixes only; no binary `0b`, no `_` separators, no `f`/`F`,
  and a decimal exponent splits wrong (`4.5e3` is three segments, golden
  line 18).
- **Line state** carries only `NEUTRAL`, `COMMENT` and `QUOTE` across
  lines (`Constants`). A text block's `"""` would corrupt every line
  below it until a stray quote re-synced.
- **The tagger** (`JavaTagger`) is a hand-written state machine —
  `NEUTRAL`, `CLASS_NAME`, `METHOD_NAME`, `NEW`, `FIELD_INITIALIZER`,
  and so on. It has no notion of `<…>` type arguments, which is the
  View bug below. No `record`, no `enum`, no `sealed`/`permits`, no
  interface `default`/`static`/`private` methods, no local records.
- **Folding** (`BraceFolds`) is already structural: a block from the
  line its statement starts on, a multi-line comment, a run of line
  comments at the top, a run of imports. The copyright header and the
  imports already fold on open. Annotations between the imports and the
  class declaration are the only thing to re-check.
- **Indentation** (`JavaMode.getCorrectIndentation`) works off a model
  line. It knows nothing of `->`, of `case 1 -> …`, of a text block's
  own indent, or of a fluent `.` chain.
- **Subclasses to protect.** `CMode`, `JavaScriptMode` and `PHPMode`
  extend `JavaMode` and share `JavaFormatter` and the `JavaMode.color.*`
  table. Their goldens (`sample.c`, `.cpp`, `.js`, `.php`) are the
  regression net; bumping `JAVA_FORMAT_LAST` shifts C's and HTML's
  format numbers, but the goldens name formats, not numbers, so they
  should hold.

## The View bug

`TODO.md` reports that `Editor.java` tags `View` as a method. The line
number is stale — the field is now `Map<SystemBuffer, View> views` at
`Editor.java:227` — but the bug is real and reproduces against the live
tagger. For

```java
Hashtable<SystemBuffer, View> views = new Hashtable<SystemBuffer, View>();
```

the tagger emits `C.SystemBuffer` (field), `C.views` (field) and
**`C.View` (method)**. `View` shows in the JavaTree sidebar and in
tag navigation as a phantom method.

The tagger skips `<…>` as junk, so a comma inside a generic reads as a
declaration-list separator, and the constructor's `(` then makes the
last type argument look like a method name. The phantom needs all three
of a comma in the *second* `<…>`, a following `(`, and a terminating
`;`:

| Declaration | Tags | Why |
|---|---|---|
| `Hashtable<SystemBuffer, View> v = new Hashtable<SystemBuffer, View>();` | `SystemBuffer`(field), `v`(field), **`View`(method)** | the second generic's comma flips `FIELD_INITIALIZER`→`NEUTRAL`, then `(`→`METHOD_NAME`→`;` |
| `Foo<Bar> x = new Foo<Bar>();` | `x`(field) only | one type param, no comma; the RHS is swallowed whole |
| `Foo<Bar, Baz> x = null;` | `Bar`(field), `x`(field) | the comma tags `Bar`; no `(`, so no phantom |
| `Foo<Bar, Baz> x = new Foo<>();` | `Bar`(field), `x`(field) | empty second generic, no comma to flip on |
| `new Foo<Bar, Baz>();` | *(none)* | `NEW` swallows to `(` or `;` |

The fix is to skip a balanced, nest-aware `<…>` (string- and
comment-safe) the moment a `<` shows in a type position, so commas and
identifiers inside type arguments never reach the declaration-list or
`METHOD_NAME` logic. That kills the phantom method and the spurious
`SystemBuffer` field at once.

## The work

Land in this order; each phase green against `bb test` before the next.

### 1. Keywords

`JavaMode.keywords`: add `enum`, `record`, `sealed`, `permits`, `var`,
`yield`. `sealed`, `permits` and `record` are restricted identifiers
(JLS 1.10) — never valid as type names, so always safe to highlight.
`var` and `yield` are contextual: still legal identifiers in older code,
so they will false-positive as keywords there. Highlight them anyway, the
way IntelliJ's default does, and note the trade-off in the commit.
`non-sealed` tokenizes as `non` `-` `sealed`; only `sealed` lights up.
Acceptable, and worth a comment.

### 2. Formatter: annotations, text blocks, numbers

`JavaFormatter`:

- **Annotation.** New `JAVA_FORMAT_ANNOTATION = 9`; bump
  `JAVA_FORMAT_LAST` to 9. `CFormatter` and `HtmlFormatter` derive their
  numbers from it and shift automatically — re-run their goldens to
  confirm. In `parseLine`, a neutral `@` followed by an identifier
  start emits the whole `@Name` (or qualified `@a.b.Name`) as one
  annotation segment. `addEntries` links it:
  `addEntryFromPrefs(JAVA_FORMAT_ANNOTATION, "annotation", "keyword")`,
  so it takes the keyword color unless `JavaMode.color.annotation` is
  set. Give `DefaultTheme` a default.
- **Text block.** A new line state `STATE_TEXT_BLOCK` (next free in
  `Constants`). `"""` opens and closes it; the content formats as
  string; a `\` at the end of a line inside the block continues it. The
  state itself carries across lines, so `quoteContinues` does not apply.
  Handle it in both `parseBuffer` (the line-flag pass) and `parseLine`.
- **Numbers.** Extend `STATE_NUMBER` for `_` between digits, `0b`/`0B`
  binary, the `f`/`F` float suffix, and hex floats with a `p`/`P`
  exponent. Keep `l`/`L`/`u`/`U`.
- **Lambda arrow.** `->` already formats as two operator characters.
  Leave it; no separate face wanted.

### 3. Tagger: records, enums, the generics fix

`JavaTagger` — the biggest risk. Keep the state machine, extend it.

- **Generics fix** first: the nest-aware `<…>` skip above, in `NEUTRAL`.
  This is the View bug, and it helps every level.
- **`record`.** A `RECORD_NAME` state mirroring `CLASS_NAME`.
  `record Foo(…)` tags as a class named `record Foo` — `Constants` has
  no free slot in the common range for a `TAG_RECORD`, so reuse
  `TAG_CLASS` and teach `JavaTree` (its `TAG_CLASS`/`TAG_INTERFACE`
  cases, lines 218–247) to render it. The header parameter list and a
  compact constructor must not leak field tags.
- **`enum`.** An `ENUM_NAME` state → a class tag named `enum Foo`; the
  constants before the `;` tag as `TAG_CONSTANT` (already `8` in
  `Constants`); methods after the `;` tag normally.
- **Interface members.** Verify `default`, `static` and `private`
  methods inside an interface tag through the `METHOD_NAME` path.
  `sealed`/`permits` clauses skip like `extends`/`implements`.
- **Local records and classes in methods.** The stack-based
  `currentClass` already nests; make sure the generics fix does not
  break anonymous classes (`new Runnable() { … }`, the `NEW` state).

### 4. Indentation

`JavaMode.getCorrectIndentation` and its helpers:

- **`case` arrow.** `case 1 -> expr;` on one line needs no change; a
  line starting with `->` under a `case` label indents one step.
  `indentSwitchLabel` and `indentAfterSwitchLabel` need the arrow form.
- **Lambda bodies.** `foo(x -> { … });` mostly works through the brace
  paths. Verify `->` before a `{` is not mistaken for a conditional —
  `->` is not in `conditionals`, so it should fall through; test it.
- **Text block.** A line inside `STATE_TEXT_BLOCK` keeps its own indent,
  like the quoted-text-in-column-0 case already there.
- **Fluent chains** (`.stream().map(…)` wrapped). Optional, behind a
  property or a plain `indent + indentSize` for a line starting with
  `.`. Stretch; low priority.

### 5. Electric keys

- `electricColon` (`ElectricCommands`) must not mis-indent `case 1 ->`
  or regress the ternary `? :`. Check the colon handler's switch-label
  assumption against the arrow form.
- `electricStar` for Javadoc: unchanged.
- `JavaMode`'s `NEW_RE` (anonymous-class indent): verify with generics,
  `new ArrayList<String>() { … }`. `CALL_RE` is fine as is.

### 6. Tests and goldens

- Extend `test/golden/sample.java`: annotations, `var`, a lambda, a
  method reference `Foo::bar`, a text block, a `record`, an `enum` with
  constants and methods, `sealed`/`permits`, a `switch` arrow, `yield`,
  binary and underscore numbers, and the exact `Hashtable<SystemBuffer,
  View>` case.
- `GOLDEN_UPDATE=1 bb test`, then read the `sample.java.golden` diff
  line by line — that is the visual proof of highlighting, tags and
  indent.
- Add `TaggerTest` cases: record tags, enum tags, and the
  generics-no-false-method case from `TODO.md`.
- Regression: full `bb test`. The C, C++, JS and PHP goldens must be
  unchanged; the shared format-table shift is the main risk.
- `bb lint` clean.

### 7. Docs and TODO hygiene

- `doc/changes.html`: note modern Java support.
- `doc/preferences.html` and the themes: document
  `JavaMode.color.annotation`.
- `TODO.md`: check off the sub-items done here — annotations colored,
  the tokenizer bug. Leave the Javadoc-links and markdown-in-Javadoc
  items separate.

## Deferred: a per-level setting

A project-level Java language level was considered and skipped. The
property chain (`Buffer.getStringProperty` → buffer → mode → global)
makes the *knob* free, and indentation and the electric keys could read
it with no new plumbing. But:

- No project-settings loader exists. `ProjectRoot`'s `.j-project` marker
  is documented as one that "will hold its settings" — future tense.
  Nothing reads settings out of it today, and there is no `.editorconfig`
  parser. That is a new subsystem.
- Keywords are a mode-singleton `Set`; `Formatter.isKeyword` reaches the
  mode with no buffer, so a level-aware set means a signature change for
  callers that have no buffer.
- The tagger runs on a `SystemBuffer` with no mode at all, so its level
  would have to come from the file's `ProjectRoot` and be passed in,
  through a class shared with C and PHP.

And for highlighting and tagging the modern syntax is additive: a Java 8
file has no text blocks, records or `case` arrows to trip on, and the
generics fix helps every level. The only thing the knob buys now is
suppressing the `var`/`yield`/`record` contextual false positives — a
cosmetic nit. It earns its cost only with a consumer that treats the
level as real: compile-on-save (`javac --release`), jshell, or an LSP
bridge. Add the `Property` when that feature lands; until then, scan for
the latest syntax always.

## Out of scope

Treesitter, jshell integration, markdown rendering in Javadoc, and
autocomplete — each its own TODO entry.
