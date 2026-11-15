# Escape sequences: plan

Color source escapes in strings and character literals (`\n`, `\x41`,
`\u00e9`) and references in XML and HTML (`&amp;`, `&#233;`). Mark malformed
escapes and suspicious unknown escapes where the language profile calls for
it. Ordinary Unicode text such as `é` stays in its original format. Also
make raw control characters in buffer text visible.

This changes presentation only: never decode an escape, replace buffer
contents, or change save, copy, search, undo, or character offsets.
`escapeError` means a local highlighting problem, not a compiler diagnostic.

`escapeError` identifies a specific kind of syntax highlighting. A compiler
diagnostic carries additional information: severity, a message, and potentially
a fix. An invalid escape can receive both token coloring and an error underline.

Keeping a separate style gives us:

- Independent customization: themes can change invalid escapes without changing every error.
- Accurate scope: our scanner checks local spellings; it doesn’t provide complete compiler validation.
- Separation from existing styles: J already uses error for VCS status rows, with a keyword fallback.

Other editors follow this pattern:

- IntelliJ defines separate `VALID_STRING_ESCAPE` and `INVALID_STRING_ESCAPE` highlighting keys.
  Parser errors and diagnostic annotations are handled through additional highlighting layers.
  [Highlighing keys](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/openapi/editor/DefaultLanguageHighlighterColors.java)
  [highlighting archetecture](https://plugins.jetbrains.com/docs/intellij/syntax-highlighting-and-error-highlighting.html)
- VS Code’s JSON grammar uses `constant.character.escape.json` for valid escapes
  and `invalid.illegal.unrecognized-string-escape.json` for invalid ones. Language diagnostics
  are supplied separately.
  [JSON grammar](https://github.com/microsoft/vscode/blob/main/extensions/json/syntaxes/JSON.tmLanguage.json),
  [diagnostics API](https://code.visualstudio.com/api/language-extensions/programmatic-language-features#provide-diagnostics).


## Evaluation of the original approach

Splitting formatter segments is a good fit for the existing display and
theme machinery. The current code needs these qualifications:

- String format IDs are local to their formatters, so new escape formats
  can be introduced without changing mode commands. However, a string
  format alone does not identify its escape rules. Java ordinary strings
  and text blocks share a format; Python prefixes are emitted separately
  from their quotes; Perl and Ruby regexes and heredocs share the string
  format. PHP also uses its string format for HTML attribute values.
- A post-pass cannot recover all of that context from the segment's first
  character, especially on continuation lines. Preserve lexical context
  when the formatter emits a literal. Small changes at token emission and
  to continuation state are necessary; a full tokenizer rewrite is not.
- HTML tags have several formats after tag classification, and a tag can
  span lines. Whole-tag scanning cannot distinguish attribute values from
  names or embedded JavaScript. HTML also permits some references without
  a semicolon, so `&word` cannot universally be an error.
- `Display` measures glyph vectors. Painting a one-character substitute
  alone does not guarantee a one-column advance; measurement and the block
  caret's separate character drawing must use the same rule.
- `MarkdownFormatter.formatCode` passes a fresh `TextLine` without previous
  lexical state. Single-line fenced literals inherit escape highlighting;
  multiline fenced literals remain subject to that existing limitation.

## Scanner and formatter integration

Add a public `Escapes` utility in `org.armedbear.j` so formatters in mode
subpackages can use it. Its scanner takes
a bounded content range, a language/quoting profile (`Escapes.Syntax`), and
whether the range ends at an actual continued physical line. It reports
ordered, nonempty, nonoverlapping spans, each classified as escape or error.
Offsets are Java UTF-16 offsets in the formatter's text, after detabbing,
just as existing segments are. Delimiters and prefixes are outside the
content range and retain the literal's base format.

Use one shared splitting primitive in `Formatter`, with two callers:

1. Keep the proposed convenience helper for segments with uniform rules:

   ```java
   protected final void markEscapes(int in, int escape, int error,
                                   Escapes.Syntax syntax)
   ```

   It replaces only segments of format `in`, keeping every other segment.
   XML text and attribute values are candidates, once their lexical context
   excludes declarations and processing instructions.
2. Add an emission helper for literals whose rules depend on token state.
   It accepts the full token range, its content subrange, base/escape/error
   format IDs, syntax, and continuation context, then appends the split
   segments. Call it from the existing `endToken`/`endSegment` string cases.
   The formatter already knows the quote kind there. Retain prefix/raw/
   bytes information when needed instead of guessing from emitted text.
   Record whether a closing delimiter was consumed; an escaped quote at
   the end of an unfinished token is still content.

Do not run both paths over the same literal. In particular, Java-family
literals are split during emission, while HTML classifies tags before
splitting their eligible attribute ranges. Repeated `formatLine` calls must
produce identical output, independent of which line was painted first.

The splitting primitive must:

- Preserve the concatenated text and total UTF-16 length exactly, including
  the empty-line behavior. Leave unchanged segments intact when possible.
- Copy `hidden` and `item` metadata to every piece. Leave `bar` segments
  unsplit: duplicating that flag repeats the marker, while dropping it
  changes the width of concealed text. Current eligible callers do not
  use bars, so supporting their subdivision is unnecessary.
- Keep quote characters and prefixes in the base format; never scan across
  a closing delimiter into the next literal or into a comment.
- Preserve complete escape boundaries even if horizontal scrolling clips
  their start. Scan the complete formatted line before display clipping.

### Matching and error recovery

Prefer a small deterministic scanner to two general regexes. Compiled
patterns can handle simple alternatives, but recognition must enforce
digit counts, maximal matches, literal boundaries, and error extent. In
Java regex syntax `\h` means horizontal whitespace, **not hexadecimal**;
use ASCII `[0-9A-Fa-f]`, `[0-9]`, and `[0-7]` predicates explicitly. See
the [Pattern reference](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/regex/Pattern.html#predef).

- Scan left to right. After recognizing an escape, advance past its whole
  source span. In `"\\n"`, `\\` is one escape and the following `n` is
  ordinary string text; do not restart at the second backslash.
- Choose the longest legal numeric form. Java `\377` is one escape;
  Java `\400` is `\40` followed by ordinary `0`. C `\x41face` consumes all
  the hex digits, while Python `\x4142` consumes `\x41` only.
- For a malformed fixed-width escape, color the introducer and available
  payload up to the expected width. Stop before whitespace, another
  backslash, or a literal boundary. Thus `\u12G4` is one error, while
  `\u12\n` is an error followed by a valid escape. Do not color just `\u`
  and leave its partial digits looking unrelated.
- For a braced form, include its closing brace if present; otherwise stop
  at the content boundary or next backslash. A profile may allow spaces
  inside it (Unicode names and Ruby code-point lists). Consume an invalid
  braced candidate once rather than repeatedly searching its suffix.
- Unknown strict-profile escapes consume the backslash and the following
  Unicode code point, keeping surrogate pairs together. Unknown literal
  escapes stay in the base style; accepted identity escapes are colored
  as escapes. These are separate policies.
- In profiles that diagnose a dangling backslash, recognize continuation
  only when the profile permits it and a physical line break follows.
  Literal-fallback profiles keep their own rules. A segment boundary is
  not an end-of-line, and the final line without a following line has no
  continuation.
- Bound every scan to its content range and guarantee forward progress.
  Work should be linear in the eligible text and emitted pieces, with no
  repeated substring copying or regex backtracking over long suffixes.

For new continuation states, store the syntax context in line state or a
cache rebuilt by `parseBuffer`, not mutable state left by the last paint.
Audit mode consumers of `Line.flags()` before extending its encoding:
`JavaMode` and `XmlMode` compare states directly, and `PHPMode` uses
`PHPFormatter.getState`. Preserve their indentation and editing behavior.

## Language profiles

These are explicit highlighting profiles, not promises to validate every
language version or compiler extension. Syntax below is written as source
text rather than Java string literals. Every numeric digit is ASCII.

### Java, C family, and JavaScript

| Profile | Recognized forms | Error policy |
|---|---|---|
| Java strings and character literals | `\b \t \n \f \r \s \" \' \\`; octal: 1–2 digits, or 3 with first digit 0–3; backslash + one or more `u`s + exactly 4 hex digits | Unknown and malformed forms are errors; trailing backslash is an error |
| Java text blocks | Java forms, plus backslash followed by a physical line break | Same, allowing the continuation backslash |
| C, C++, Objective-C ordinary literals | `\a \b \f \n \r \t \v \" \' \? \\`; 1–3 octal digits; `\x` with at least 1 hex digit; `\u` + 4 hex; `\U` + 8 hex; line continuation | Flag malformed forms and unknown forms as suspicious under the classic profile; accept common `\e` extension |
| JavaScript quoted strings | Simple escapes; `\0`; `\x` + 2 hex; `\u` + 4 hex; `\u{...}` with one or more hex digits; identity escapes such as `\q`; physical line continuation | Malformed `\x`/`\u` and an uncontinued trailing backslash are errors |

The Java profile is based on [JLS lexical structure](https://docs.oracle.com/javase/specs/jls/se25/html/jls-3.html#jls-3.10.7).
Java Unicode translation actually precedes tokenization, including comments
and identifiers. This feature colors its source spelling inside recognized
literals only; it does not implement that translation or retokenize quotes
or line breaks produced by it.

Java has no general identity-escape fallback. At an unconsumed backslash,
a structurally complete Unicode spelling always receives `escape`, even
when its value is a backslash, quote, or line break. Do not special-case
`\u005C` or interpret the character it produces. In source `"\u005Cn"`,
color `\u005C` as one escape and leave `n` in the string's base format;
that `n` has no source backslash introducing it and cannot itself be an
unknown-escape error. The same structural policy leaves `q` plain in
`"\u005Cq"`, despite its compiler-level meaning. See
[JLS Unicode escapes](https://docs.oracle.com/javase/specs/jls/se25/html/jls-3.html#jls-3.3).

Loop over the entire `u+` marker before reading the four digits:
`\uuuu0041` is one escape, not several candidates. The ordinary
left-to-right consumption rule still applies: in `"\\u0041"`, the `\\`
escape consumes both backslashes and the remaining `u0041` stays plain.

The classic C-family profile covers the common forms in
[C++ character literals](https://eel.is/c++draft/lex.ccon). Newer braced
numeric/named forms and full pre-tokenization line splicing need separate
support; treat those recognized newer introducers conservatively as plain
text until supported. Do not describe `\q` coloring as a universal compiler
error. Identify C++ raw literals and leave their contents alone; if the
formatter does not yet recognize their boundaries, add that recognition
before enabling escape errors in them.

For JavaScript, use a permissive quoted-string profile: legacy octal
escapes and `\8`/`\9` receive escape coloring, without deciding strict-mode
legality. Do not claim every character following a backslash is valid:
`\x` and `\u` have required payloads. See
[ECMAScript string literals](https://tc39.es/ecma262/2025/multipage/ecmascript-language-lexical-grammar.html#sec-literals-string-literals).

Pick the profile from `JavaFormatter`'s constructor language discriminator,
with C++ raw-literal handling using its language mode. The buffer mode
may be HTML or Markdown. `CFormatter` inherits Java's emission path and
adds its own formats; disabled `#if 0` segments and comments stay untouched.

HTML currently delegates whole script lines to a JavaScript formatter.
Those lines gain highlighting automatically. Same-line `<script>...</script>`
content and template literals do not gain new tokenization in this phase.

### Python

Use `\a \b \f \n \r \t \v \\ \' \"`, 1–3 octal digits, exactly 2 hex
digits after `\x`, exactly 4 after `\u`, exactly 8 after `\U`, and nonempty
`\N{...}` names. Unknown forms receive `escapeError` as suspicious escapes;
Python has warned about them rather than always rejecting them. Bytes
literals exclude the Unicode forms. Raw literals receive no escape
highlighting. See [Python lexical analysis](https://docs.python.org/3/reference/lexical_analysis.html#escape-sequences).

Recognize the complete, case-insensitive prefix adjacent to a quote,
including both `rb` and `br`. The current formatter emits that prefix
separately, so inspecting the string segment cannot work. Carry raw/bytes
and quote-kind state through multiline literals in both `parseBuffer` and
`formatLine`. A fresh repaint of a middle line must preserve that context.

### Ruby, Perl, and PHP

Give these separate profiles; none is simply “C plus braced Unicode.”

| Context | Initial supported rules |
|---|---|
| Ordinary single-quoted Ruby, Perl, PHP strings | Highlight `\\` and `\'`; keep other backslashes literal. Do not skip the whole string. |
| Ruby double-quoted strings | Ruby simple escapes, octal, `\x` with 1–2 hex digits, `\u` with 4 hex digits, braced lists of Unicode values, control/meta escapes, and identity escapes. Malformed structured escapes are errors. |
| Perl double-quoted strings | Perl simple escapes, octal, `\x`/`\x{...}`, `\o{...}`, `\N{...}`, control escapes and case/quoting operators such as `\l`, `\U`, `\Q`, `\E`. Do not mistake `\u` for a four-hex escape or `\v` for C's vertical tab. |
| PHP double-quoted strings | `\n \r \t \v \e \f \\ \$ \"`, 1–3 octal digits, `\x` with 1–2 hex digits, and `\u{...}`. Unknown forms remain literal; malformed braced Unicode is an error. |

Use the language-specific rules in the [Ruby literals documentation](https://docs.ruby-lang.org/en/3.4/syntax/literals_rdoc.html#label-Escape+Sequences),
[Perl quoting documentation](https://perldoc.perl.org/perlop#Quote-and-Quote-like-Operators),
and [PHP strings manual](https://www.php.net/manual/en/language.types.string.php).
In permissive profiles, do not blanket-flag `\x` or `\u` just because a
different language would require a payload; distinguish accepted literal
fallbacks from malformed structured forms according to that profile.

For Perl specifically, unbraced `\x` consumes up to two hex digits and can
have none; even `\x{}` is accepted. Do not flag those spellings merely for
being empty. Color malformed braced payloads as suspicious, and keep other
unrecognized forms neutral in this initial profile.

Token state must distinguish ordinary strings from regexes, substitutions,
heredocs/nowdocs, and PHP's HTML values. Initially apply these profiles only
to ordinary quoted strings. Leave other constructs in their current style
until their context has a dedicated profile. Preserve single/double-quote
state on continuation lines; never infer it from a line's first character.

## XML and HTML references

Use separate XML and HTML scanners. A backslash has no escape meaning here.
Unknown complete named references may receive escape coloring, but that
only recognizes their shape. No entity-name database or DTD lookup is planned.

| Context | Escape | Error or unchanged |
|---|---|---|
| XML character data and attribute values | `&Name;`, `&#` + decimal digits + `;`, `&#x` + hex digits + `;` | Bare `&`, missing digits/semicolon, and malformed candidates are errors |
| HTML text and eligible attribute values | Complete `&` + ASCII alphanumeric name + `;`; decimal and hex numeric references (allow `x` or `X`) with semicolon | Malformed `&#...` candidates are errors; bare `&` and semicolonless named candidates stay plain |

For XML names, use XML 1.0 `NameStartChar`/`NameChar` rules, including colon
and non-ASCII names, rather than ASCII `\w`. Skip comments, CDATA,
processing instructions, and DTD declarations/internal subsets. The current
formatter can emit declaration contents as `XML_FORMAT_TEXT`; add context
gating before applying the text post-pass. An ASCII-only recognizer must
leave non-ASCII candidates neutral rather than falsely mark them as errors.
These boundaries follow [XML references](https://www.w3.org/TR/xml/#sec-references).

In HTML, missing semicolons cannot all be treated as errors: parsing has
legacy named references and attribute-specific rules. Keep semicolonless
named candidates neutral. Numeric references without a semicolon may be
marked as missing-semicolon errors, even though browsers recover. Unknown
complete names remain unchecked by design. See
[HTML character reference parsing](https://html.spec.whatwg.org/multipage/parsing.html#character-reference-state).

For malformed references, consume the candidate through its semicolon if
present; otherwise stop at whitespace, `<`, another `&`, the attribute
delimiter, or line end. Never consume an entire following tag. A reference
does not continue across a physical newline.

HTML integration requires a small attribute-context scan, synchronized with
`parseBuffer` for tags and quoted values that span lines. Handle quoted and
unquoted values. Do not scan tag/attribute names, comments, raw script/style
contents, or JavaScript in event-handler attributes. Classify the original
whole tag first so `<a>`, `<img>`, and table-specific colors survive. Check
all the resulting tag format IDs when locating eligible value ranges.
Track quotes so a `>` inside a value does not prematurely end its tag.
Keep title/textarea text eligible for references while excluding raw text;
add the narrow context states the existing formatter lacks. This is the
largest tokenizer change in the plan and should be its own implementation
step with focused tests.

## Styles and format IDs

- `escape`: a shared style with no built-in color, linked to `number` in
  `DefaultTheme.LINKS`. Existing themes inherit their number color.
- `escapeError`: a shared color in `DefaultTheme.SHARED_COLORS`, red
  `#cf222e` for light backgrounds and `#ff7b72` for dark. No link to
  `keyword` or `number`, which could override the intended diagnostic
  fallback. Avoid the name `error`, already used by `VcsStatusFormatter`.
- `specialChar`: a shared name linked to `muted`; resolve it through the
  active formatter's `FormatTable.resolveColor(..., true)` for mode and
  theme preference support. It needs no per-language token format ID.

This style split is intentional. Source `\n` uses `escape`; an actual
control code unit displayed as a picture or fallback box uses `specialChar`,
including inside a string. `link.escape` and `color.escape` do not affect
control pictures. Customize those with `link.specialChar` or
`color.specialChar`; they have no escape-style fallback.

Support `color.*`, `style.*`, and `link.*` overrides through the existing
format-table resolution path, including mode-specific preferences. Keep
the current standalone table namespaces: Java and JavaScript share
`JavaMode`; C, C++, and Objective-C share `CMode`. Preserve HTML's existing
combined JavaScript/HTML table behavior. Add no new `Property` constants for
these dynamic style names. Ensure the new names appear in `listStyles`.

Each participating formatter adds ESCAPE and ESCAPE_ERROR entries via
`addEntryFromPrefs`. Java appends IDs 10 and 11 and raises
`JAVA_FORMAT_LAST` to 11; C and HTML already number theirs relative to it.
HTML must also expose the delegated JavaScript escape entries in its
combined table, which already reuses the JavaScript table. Finish adding
all entries before the first lookup initializes the table's array. XML and
later formatters append their own local IDs.

Two more formats fit Markdown's 12-bit `EMBED_FORMAT` field. Embedded
colors/styles must still delegate to the language formatter, and every new
local table remains below `FormatTable.lookup`'s 128-entry fast-path bound.
Reset any new cached colors with the existing formatter reset/theme-change
path. No theme files need mandatory edits.

## Raw control characters

Show U+0000–U+001F except tab, and U+007F, when those code units actually
occur inside a buffer line. Ordinary line separators belong to the line
model and are not additional visible cells. Tabs retain existing detabbing
and `tabsAreVisible` behavior. C1 controls and invisible Unicode format
characters are outside this change.

Draw U+2400 + code for C0 controls, and U+2421 for DEL (for example ␀, ␛,
␡). Each consumes exactly one plain-font cell. If the selected font cannot
display the picture, draw an outlined box. Center/clip the glyph in that
cell even when its natural glyph advance differs. Resolve the foreground
as `specialChar`; use the existing formatter shade mechanism for a subtle
background that works in light and dark themes.

Implement this in `Display`, across modes, with a shared rule for drawing
and measuring:

- Isolate control cells from surrounding shaped runs. Make `drawText`,
  `measureLine`, and `plainWidth` agree on their fixed advance, including
  runs using `endsOnColumn`. Never feed a raw control's zero-width glyph
  into the width calculation or allow a ligature to cross its cell.
- Keep the raw code unit in `textArray` and the buffer; substitute only
  when drawing. A user-typed `␛` remains ordinary printable text.
- Update `drawCharacterInCaret` to draw the same picture/box in the block
  caret's inverted foreground. An outlined caret still reveals the cell.
- Paint the cell shade before search, selection, and bracket highlights,
  respecting the same geometry, so it does not cover those backgrounds.
  Honor any explicit layer foreground when the display supplies one.
- Preserve concealed-markup behavior, horizontal scrolling, clipping,
  surrogate pairs beside controls, and scaled rendering. Use the same
  path for full repaint and incremental line repaint.
- Check glyph availability outside the per-character hot path; invalidate
  that cache when fonts change. Reuse theme/reset handling for colors.

The logical column model remains unchanged. Pixel measurement gains an
explicit control-cell advance; it cannot be left unchanged on the
assumption that every control already occupies a visible glyph cell.

## Delivery phases and acceptance checks

The only shared prerequisite for the later phases is phase 1's scanner,
helpers, and styles. Their ordering is flexible; HTML has no dependency on
Python or control rendering. Prefer Python next for early user feedback,
and keep HTML's larger parsing change isolated in a later phase. This is a
delivery choice, not a requirement to tackle parsing risk first.

1. **Scanner, shared styles, Java family.** Add `Escapes`, the splitting
   helpers, Java/C/JavaScript profiles, and literal emission hooks. Keep
   raw C++ bodies neutral. Extend `CFamilyFormatterTest` and add focused
   scanner tests. Verify delegated HTML script lines and single-line
   Markdown fences select the language profile, not the host buffer's.
2. **Python.** Carry prefix/raw/bytes context through multiline parsing
   before enabling splitting. Reuse the scanner but not C's hex rules.
3. **Control-character display.** Add the shared picture/width rule and
   drawing integration, with geometry tests and a GUI driver fixture.
4. **XML, then HTML contexts and references.** Add XML context gating and
   reference splitting first. Implement HTML attribute/raw-text tracking
   separately, then enable references in eligible ranges. Keep parsing and
   rendering state consistent when quotes/tags are edited across lines.
5. **Ruby, Perl, PHP individually.** Add separate profiles and reliable
   ordinary-string context, including single-quoted escapes. Add dedicated
   cases before extending any profile to regexes or heredocs.

For every phase, assert both run formats and exact concatenated text.
Use `EditorHarness`, run `parseBuffer`, then inspect `formatLine` output as
`MarkdownFormatterTest.runs()` does. Java test sources must build backslash
plus `u` fixtures safely: Java processes Unicode escapes even in comments.
Avoid accidentally compiling the intended fixture into a different string.

### Control fixture prerequisite

The existing harness supports deterministic raw-control data without a new
driver verb. `EditorHarness.create(String)` uses `Buffer.setText`, whose
`FastStringReader.readLine` preserves NUL/BEL/BS/ESC/DEL and separates CR/LF
into lines. Build the unit-test string from explicit character values.
Use direct harness lines only for tests that deliberately need an embedded
line-separator code unit.

For GUI checks, generate the file before startup and pass it as the file
argument to `bb drive`. A UTF-8 fixture containing NUL is detected as
Binary mode by `Utilities.getFileType`, so use a UTF-16LE text fixture with
an explicit BOM instead: write bytes `FF FE` followed by UTF-16LE encoded
text. `Utilities.getFileType` recognizes the BOM and `SystemBuffer.load`
preserves those control code units. This loading path was checked with a
headless probe against the existing compiled core: UTF-8 with NUL selected
binary, UTF-16 with BOM selected text, and all five controls survived the
load and the `setText` reader unchanged. This verifies fixture ingestion;
the feature's rendering still needs implementation and GUI validation.

Phase 3 adds `test/driver/control-characters-fixture.py` and a drive script,
following the existing fixture-generator pattern in
`test/driver/README.md`. Generate labeled controls at the start, middle,
and end of lines, plus ordinary control-picture glyphs for comparison.
Seed separate scratch homes with light/dark theme preferences and
`blinkCaret=false`, then run the same script with each `--home` and `--out`.
Use printable labels as mouse targets, rather than typing controls or
embedding them in driver commands. No injection or evaluation mechanism
needs adding to `GuiDriver`. Screenshots verify appearance; unit tests
assert geometry and deterministically force the unsupported-glyph fallback.

Minimum regression matrix:

| Area | Cases and expected result |
|---|---|
| Boundaries | Empty/null lines where supported; adjacent strings; escaped quotes/backslashes; three or more backslashes; surrogate pairs intact; prefix and delimiter colors retained; no loss/duplication of text; split metadata preserved and barred segments left intact |
| Numeric scanning | Java `\377` versus `\400`; C `\x41face`; Python `\x4142`; hex digits match, spaces do not; incomplete `\x`, `\u12`, `\u{12`, `\N{` get the profile's intended extent |
| Java Unicode spelling | `\u005Cn` and `\u005Cq` color only the complete Unicode escape, leaving the suffix plain; `\uuuu0041` is one escape; malformed multi-`u` payloads are errors; `\\u0041` consumes only the escaped backslash |
| Error recovery | `\u12G4` is one error; a following `\n` is still recognized; a malformed escape never eats the closing quote or another token |
| Lexical context | Ordinary Java trailing backslash versus text-block continuation; C continuation; disabled code and comments untouched; raw C++ neutral; same-line Markdown escapes work while multiline fence limitations remain explicit |
| Reformatting | Format a continuation line first after `parseBuffer`; edit an opening quote/prefix and reparse; repeat `formatLine`; no dependence on prior paint order; existing indentation and electric editing still work |
| Python | Single/double/triple quotes; raw and bytes prefixes in mixed case and both orders; continuation lines keep raw/bytes rules |
| Other strings | Single-quoted `\\`/`\'` highlighted but `\n` plain; Perl `\u` is a case operator; PHP `\$`, `\x4`, `\u{41}`; Ruby braced Unicode lists; excluded regex/heredoc/PHP-HTML contexts untouched |
| XML | Text and both quote kinds; multiline values; Unicode/colon entity names; malformed numerics and bare ampersands; CDATA, comments, PI and DTD skipped |
| HTML | Named/numeric references; bare `&`; `?a=1&b=2` unchanged; semicolonless named candidates neutral; malformed numeric references; multiline quoted/unquoted attributes; specialized tag colors retained; quoted `>`; script/style excluded; title/textarea eligible |
| Themes | `escape` inherits `number`; explicit escape override; light/dark error defaults; mode color/style/link overrides; `specialChar` inherits `muted`; changing `link.escape` leaves raw-control pictures unchanged; `listStyles` visibility; theme reset |
| Control geometry | NUL, BEL, BS, ESC, DEL at start/middle/end; exactly one-cell advance; following text, selection, mouse-to-offset and caret agree; tabs/line separators unchanged; literal control pictures stay ordinary text |
| Control rendering | Supported picture and forced fallback box; light/dark themes; search/selection and block caret; ligatures on/off; clipping/horizontal scroll; scaled display; full and incremental repaint |
| Cost | Long strings without escapes and long malformed/braced candidates; linear scanning and bounded work, with no repeated suffix scans |

Use deterministic unit tests for span classification, metadata preservation,
theme resolution, and control-cell geometry. Follow existing display test
patterns for geometry; use `bb drive` screenshots for visual checks in both
light and dark themes. Assert cell advances rather than font-dependent
exact pixels. Run `bb test` for implementation changes; run
`bb test-extensions` if shared APIs or extension formatters are affected.

## Explicit limits

- Shell and Lisp profiles, standalone Markdown prose, and ANSI sequence
  interpretation are not included. Raw ESC is displayed, not executed.
- Full Java Unicode preprocessing, C/C++ translation-phase line splicing,
  newer C/C++ literal grammars, and compiler/language-version validation
  remain outside the local scanner.
- No new parsing for JavaScript templates or Python f-/t-string expression
  boundaries. Leave these constructs neutral where context is unreliable.
- No general repair of existing Perl/Ruby quoting or HTML inline-script
  parsing. Unsupported contexts must stay neutral rather than receive
  confidently wrong escape errors.
- No entity-name lookup, Unicode character-name lookup, numeric value/range
  validation, or normalization. A structurally complete reference/escape
  may still be semantically invalid, such as `\u{110000}` or `&#0;`.
- Multiline state inside Markdown fences remains a separate enhancement.
