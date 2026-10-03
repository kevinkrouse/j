# Markdown mode: plan

A Markdown mode that highlights and styles the common constructs, tracks task
status in checkboxes, shows the heading outline in the sidebar, folds headings
and lists, highlights code inside fences in its own language, hides link URLs
until the caret is on them, and can draw headings larger.

Several parts of this are not Markdown's alone: richer text styles, styles
that link to shared ones the way emacs faces `:inherit` and nvim's `hi link`
do, folding by a range the mode decides, hidden text, and lines of more than
one height. They are built in the editor so that other modes can use them.

- [What the editor has today](#what-the-editor-has-today)
- [Phase 0: rendering groundwork](#phase-0-rendering-groundwork)
- [Phase 1: MarkdownMode and highlighting](#phase-1-markdownmode-and-highlighting)
- [Phase 2: task checkboxes](#phase-2-task-checkboxes)
- [Phase 3: sidebar outline](#phase-3-sidebar-outline)
- [Phase 4: folding](#phase-4-folding)
- [Phase 5: highlighting code in fences](#phase-5-highlighting-code-in-fences)
- [Phase 6: hiding markup](#phase-6-hiding-markup)
- [Phase 7: following links](#phase-7-following-links)
- [Phase 8: electric pairs](#phase-8-electric-pairs)
- [Phase 9: larger heading fonts](#phase-9-larger-heading-fonts)
- [Colors for light and dark backgrounds](#colors-for-light-and-dark-backgrounds)
- [Order and tests](#order-and-tests)

## What the editor has today

- **Formatters** (`Formatter` → `FormatTable`) map a format id to one color
  and one style, and the style can only be `PLAIN`, `BOLD` or `ITALIC`
  (`FormatTable.addEntryFromPrefs`). There is no bold italic, strikethrough or
  underline (`Formatter.getUnderline` exists, but nothing can turn it on from a
  theme).
- **Theme fallback is one level deep**: `addEntryFromPrefs(format, "key",
  "function")` tries `Mode.color.key`, `color.key`, `Mode.color.function`,
  `color.function`, then `DefaultTheme`.
- **The caret and mouse clicks are measured from the glyphs drawn**, not from
  a character grid: `Display.measureLine` places the caret and
  `Display.getColumn` maps a click. Zero-width hidden text and mixed font
  sizes on a line will not throw them off.
- **Lines are assumed to be one height**: `Buffer.getY` is
  `lineNumber * charHeight`, and scrolling steps by `charHeight`. Image lines
  are the only taller lines.
- **Folding is by indentation** and refuses a line at column 0
  (`Editor.fold(Line)`), so it cannot fold under a heading. The vim layer has
  no `z` commands.
- **The sidebar outline** is `Mode.isTaggable()` + `getTagger()` +
  `getSidebarComponent()`, as `JavaTree extends SidebarTree` does.
- **TODO.md uses `[ ]`, `[/]`, `[-]` and `[x]`**, Obsidian's convention, and
  the mode adopts it.

## Phase 0: rendering groundwork

Done: `TextStyle`, `DefaultTheme`'s shared styles and links,
`FormatTable.addEntryFromPrefs(format, thing, String... fallbacks)`, and
`Display.fontFor`. Tested in `FormatTableTest`. A format that resolves to no
color at all now takes the theme's `color.text` rather than DefaultTheme's
black.

Also from review: colors may be `#rgb` or `#rrggbb` (`Utilities.parseColor`);
a properties line that sets a color shows a swatch of it in the gutter
(`Formatter.getGutterColor`); and `listStyles [all]` lists each style drawn
in itself, with the names it resolved through and where its color and style
came from (`FormatTableEntry` keeps them).

**0a. More text styles.**

- The style becomes a bitmask: `BOLD = 1` and `ITALIC = 2` as in `Font`, plus
  `UNDERLINE = 4` and `STRIKETHROUGH = 8`. `BOLD | ITALIC` is bold italic.
- `Display` gets a bold italic font, and `drawText`, `measureLine` and
  `fontForFormat` share one `fontFor(style)` in place of their three
  `switch`es.
- Underline and strikethrough are drawn from the style bits; strikethrough at
  about the middle of the x-height.
- A style preference can be words as well as the old number:
  `style.heading = bold italic`, `MarkdownMode.style.cancelled = strikethrough`.

**0b. Shared styles modes link to.**

- `DefaultTheme` defines shared styles -- `heading`, `link`, `url`, `code`,
  `emphasis`, `strong`, `quote`, `listMarker`, `todo`, `inProgress`,
  `inProgressMarker`, `done`, `cancelled`, `muted` -- each with a color for a
  light background and one for a dark, chosen by the background's brightness
  as `Formatter.getRainbowColor` chooses its palette.
- `addEntryFromPrefs(format, thing, String... fallbacks)` tries each name in
  turn, and a theme or prefs can link a name to another, as nvim's
  `hi link` does: `MarkdownMode.link.heading1 = heading`, or for every mode
  `link.heading1 = heading`.
- The order for a thing: `Mode.color.thing`, `color.thing`, the thing's link
  (followed the same way), each fallback, then `DefaultTheme` for the thing
  and each fallback.
- A theme sets `color.heading` once and Markdown, HTML headings, and any later
  org or rst mode follow it.

## Phase 1: MarkdownMode and highlighting

Done: `mode/markdown/MarkdownMode` and `MarkdownFormatter`, tested in
`MarkdownFormatterTest`. As built:

- Line flags hold the block a line begins in (fence, HTML comment, front
  matter) in bits 0-2, a setext heading's level in bits 3-5, and in a fence
  its tilde bit and length in bits 6-11. `MarkdownFormatter.getHeadingLevel`
  and `isCode` read them for later phases.
- `formatLine` fills an array of formats a character at a time -- block
  structure first, then inline markup, recursively inside emphasis and link
  text -- and turns it into runs.
- Indented code blocks are colored too, after a blank line and outside a
  list item.
- The heading path in the status bar comes with Phase 3: `AbstractMode`'s
  `getContextString` already gives the tag before the caret.
- Emphasis is CommonMark's flanking rules simplified: a run opens before
  something other than white space and closes, a run as long, after it.
  `_` does not open or close inside a word.

- `MARKDOWN_MODE = 47` in `Constants`; `ModeList` takes
  `.+\.md|.+\.markdown|.+\.mkd`.
- `mode/markdown/MarkdownMode.java` and `MarkdownFormatter.java`.
- `parseBuffer()` keeps the state that spans lines in each line's flags: in a
  fence (and the fence's language), HTML comment, front matter.
- `formatLine()` does block structure first -- ATX and setext headings 1-6,
  `>` quotes, list markers (`-*+`, `1.`, `1)`), task boxes, `---` rules,
  fences, tables with the pipes muted -- then inline: escapes, code spans,
  autolinks, `[text](url)`, `[text][ref]`, `![alt](src)`, `***`, `**`,
  `*`/`_`, `~~`.
- List items and task boxes may be indented:
  `^(\s*)([-*+]|\d+[.)])\s+\[([ xX/-])\]\s`.
- `getContextString()` shows the heading path in the status bar.

| Element | Style | Links to |
|---|---|---|
| Heading text, 1-6 | bold italic | `heading` |
| `#` markers | plain | `muted` |
| Code block | italic | `code` |
| Inline code | plain | `code` |
| Link text | underline | `link` |
| URL, `(...)` | plain | `url` → `muted` |
| `*em*`, `**strong**`, `~~del~~` | italic, bold, strikethrough | text |
| Blockquote | italic | `quote` → `comment` |
| Bullets, numbers | bold | `listMarker` |

## Phase 2: task checkboxes

The brackets, the marker and the item's text each have their own format, so an
item in progress can have purple brackets and a blue marker.

| State | Syntax | Brackets | Marker | Item text |
|---|---|---|---|---|
| Not started | `[ ]` | `todo` (amber/yellow) | | text |
| In progress | `[/]` | `inProgress` (purple) | `inProgressMarker` (blue) | text |
| Completed | `[x]` | `done` (green) | `done` | `muted` |
| Cancelled | `[-]` | `cancelled` (grey) | `cancelled` | `cancelled`, strikethrough |

Done: the `task [todo|doing|done|cancel]` command
(`mode/markdown/MarkdownTasks`), tested in `MarkdownTasksTest`.

- With no argument it moves the tasks on, ` ` → `/` → `x` → ` `, as the first
  box in the selection says; a cancelled task starts again. `cancel` cancels
  them, or if all are, starts them again. A list item without a box gets
  one; a line of text becomes `- [ ] text`.
- Every line of the selection, or the caret's line, as one undo step. A
  selection from a block caret (vim's) takes in its last line even at offset
  0; j's own, ending at the start of a line, does not.
- Ctrl+Enter and Ctrl+Shift+Enter (`task cancel`) in Markdown mode, which
  reach it from vim's normal and visual modes too.
- One command for both edit modes, rather than a vim `:Task`: vim's
  selection is j's mark and dot. Later: let vim's `:` hand a range to j's
  commands as a selection, so `:'<,'>task done` works; it refuses one now
  (E481).
- Fixed on the way: undoing an edit of several lines made with
  `UndoLineEdit(buffer, line)` logged a bug when the caret was on a line it
  shortened; such an edit no longer records the caret.

## Phase 3: sidebar outline

Done, tested in `MarkdownOutlineTest`:

- `MarkdownTagger` tags headings as `MarkdownTag`s, type `TAG_HEADING`, which
  `Tagger.writeTags` leaves out of tag files. Each knows its level and the
  heading it is under; `getLongName` is the path, "A › B › C", and the
  name is the heading as it reads, links as their text and markup gone.
- It runs on its own thread before the buffer may have been parsed, so it
  walks the lines with `MarkdownFormatter.scan`, which `parseBuffer` now
  uses too, rather than reading flags.
- The outline is `SidebarTagTree`, in core: any mode's tags nested by a
  level the mode gives, each under the last before it at a lower level.
  It follows the caret, and a click or Enter goes to a heading. Keys and
  mouse as `JavaTree`'s.
- The status bar shows the path whether or not the long context is asked
  for (`MarkdownMode.getContextString`).

- `MarkdownTagger` makes `LocalTag`s for ATX and setext headings, skipping
  fences.
- `MarkdownTree extends SidebarTree` nests them by level, after `JavaTree`'s
  `findParentNodeForTag` and `addNode`, and follows the caret.
- Being taggable gives `findTag` and `listTags` on headings for nothing.

## Phase 4: folding

Done, tested in `MarkdownFoldingTest`:

- `Mode.getFoldRange(Editor, Line)` returns the lines to hide, an empty
  array for nothing, or null for indentation; `Editor.fold` asks it after a
  selection and `{{{ }}}`. `Mode.foldAll(Editor)` is `foldMethods` unless a
  mode says otherwise. New commands `toggleFold` and `foldAll`.
- `MarkdownFolding` folds the innermost of a fence's code, an indented code
  block (but its first line), a list item's children, a heading's section
  and its parents' sections that has a line still to be seen, so folding
  again closes outward. A blank line is as far in as the line after it. Hidden is a count, so
  opening the outer fold leaves the inner closed, as in vim.
- A section keeps its trailing blank lines visible, to keep the headings
  apart; `foldHeadings [level]` hides everything but the headings.
- Vim: `za zc zo zR zM` in the key table, for every mode. `zo` is
  `unfoldHere`, the fold just below the caret; j's `unfold` opens the next
  one wherever it is. `foldAll` is `foldMethods` in Java and Perl modes,
  and says there is nothing to fold elsewhere.
- The fold marker in the gutter is a chevron in the line numbers' color.

## Phase 5: highlighting code in fences

v1 done, tested in `MarkdownFencedCodeTest`:

- `FenceLanguages` maps an info string's first word (`java`, `py`, `{.sh}`,
  `bash title=x`, or any j mode's own name) to a slot, kept in bits 12-17
  of each fenced line's flags.
- `MarkdownFormatter` keeps a formatter per slot, lent the buffer with
  `Formatter.setLanguageMode`, so its keywords and identifier characters
  are its language's: `isKeyword` and the formatters that asked
  `buffer.getMode()` now ask `getLanguageMode()`.
- Formats come back as `EMBED` (bit 19) | slot << 12 | the language's own
  format, below bit 20 where `Display` packs rainbow depths; `getColor`,
  `getStyle` and `getUnderline` decode them. The style is the language's,
  plus italic if the theme's `codeBlock` style has it.
- v1 limit as planned: each line is formatted alone (a copy, no flags), so
  a `/* ... */` over several lines is a comment only on its first.

- The fence's language names a mode -- `java`, `js`, `py`, `sh`/`bash`, `c`,
  `cpp`, `xml`/`html`, `css`, `lisp`, `diff`, `properties`, `make` and their
  aliases -- through `ModeList.getModeFromModeName`, with one formatter per
  language kept for the buffer.
- Its formats come back with a high bit (`EMBED = 1 << 28`, clear of
  `Display`'s `RAINBOW = 1 << 30`), a language slot, and its own format;
  `MarkdownFormatter.getColor` and `getStyle` decode them and ask the
  language's formatter. `Display` only reaches colors through those.
- `MarkdownMode.codeBlockItalic` adds italic to whatever the language says.
- State across lines is the risk: `JavaFormatter` and others read
  `line.flags()`, which their own `parseBuffer` wrote.
  - v1 formats each fenced line as a copy with neutral flags. Lines are right
    on their own; a `/* ... */` over several lines is not.
  - v2 adds `Formatter.scanState(String text, int stateIn)` to the C family
    and Java, and Markdown's `parseBuffer` keeps the inner state in the low
    bits of each fenced line's flags.
- Perhaps `Formatter.getLineBackground(Line)` to shade a fence's rows.

## Phase 6: hiding markup

Done, tested in `MarkdownHidingTest`:

- `LineSegment` carries `isHidden` and `getItem` (a number per line, or
  `BLOCK`); `Formatter.hidesMarkup` and `getHiddenBlock(Line)` default to
  none. Markdown marks links, emphasis, code spans, escapes, autolinks and
  fence lines, and with `conceal=all` heading markers and setext underlines.
- `Display.formatLine` reveals the items the caret is in -- on its line,
  from an item's first column to just past its last; for a block, the
  caret on any of its lines -- and marks the rest `HIDDEN` (bit 31), which
  `drawText` and `measureLine` give no width. The caret's x and clicks
  measure the same way, so they agree.
- `Display.showRevealed`, from `drawCaret`, repaints the caret's line and
  the ends of a block it is in or was in when the caret moves.
- `Property.CONCEAL`, read as `MarkdownMode.conceal`: a list of what to
  hide, `markup` (default) and `headings`; `none` hides nothing. Mode
  keys like it had been broken since the modes moved into packages; fixed
  in `AbstractMode.getFullKey`.
- Hiding is the core's, for any mode: a formatter says `hidesMarkup`, asks
  `Formatter.conceals(kind)` what the setting names, and marks segments
  with `addSegment(text, begin, end, format, hidden, item)`.
  `HiddenMarkupTest` hides `{{ }}` with a formatter of its own.
- Code is shaded, as Obsidian's: `Formatter.getLineBackground(Line)` for a
  fence's and an indented block's lines, from the gutter to the right edge,
  and `getRunBackground(format)` for inline code, a rounded chip with its
  backticks (`codeMarker`). The shade is `Formatter.getShade`: a theme's
  `color.codeBackground`; else on a light background half as far from it
  as the current line's highlight, and cool (darker by 1, 0.78, 0.55 of the
  step in red, green, blue: GitHub's `#f6f8fa` on white), so it is not
  taken for the current line; on a dark one, 12% of the text mixed in. The
  caret's line keeps its own background.
- A blockquote's `>` each have a thin bar at their left in the quote
  marker's color, blue (`LineSegment.setBar`, the `BAR` bit in `Display`,
  which takes bit 29 from the rainbow depth: 511 levels now). Hidden, a
  `>` keeps its room with the bar alone in it, so the text never moves; the
  caret on the line shows the `>` beside its bar.
- Mail: `MessageFormatter` colors a line starting `>` (or `name>`) whole in
  one quote color, `color.string`, with no depth; `MessageBuffer.quoteBody`
  quotes a reply with `> `. The bars could serve it too, a color per depth.

Hidden until the caret is in it, as Obsidian's live preview and nvim's
`conceallevel=2` show Markdown: what the markup means, not the markup.

What hides:

- links and images: the `[` and `](url)`, `[ref]` and `<` `>` of autolinks,
  leaving the link text;
- emphasis: `*`, `_`, `**`, `__`, `***`, `~~`;
- code spans: the backticks;
- fences: the whole opening and closing line, backticks, tildes and the
  language name;
- backslash escapes: the backslash;
- heading markers, `#` and a setext underline, if `MarkdownMode.conceal`
  asks for them.

When it shows again: when the caret is in the item, not just on its line.

- An inline item -- a link, an emphasis run, a code span -- is the markers
  and what is between them. The caret inside it, or just after its closing
  marker, shows its markers; the rest of the line stays hidden.
- A fence is the block from its opening line to its closing one. The caret
  on any of its lines shows both fence lines.
- A heading is its line (and its underline).

How:

- The formatter marks hidden runs with a `CONCEAL` bit, and for each line
  the items they belong to: the span of each inline item, and for a block,
  the lines it covers. A `Formatter.getHiddenItems(Line)` hook, empty by
  default, so other modes are untouched.
- `Display` drops the bit from the runs of the item the caret is in before
  drawing, and `drawText` and `measureLine` give the remaining hidden runs
  no width. The caret and clicks measure with `measureLine`, so they agree.
- Moving the caret marks the lines of the item it left and the item it
  entered changed, as the block caret's repaint already marks the line it
  left. For a fence that is both fence lines.
- A fence line hidden whole is drawn empty but still takes its row, so
  lines do not jump as the caret moves in and out.
- `MarkdownMode.conceal = all | links | none`, default `links` plus emphasis,
  code and fences; `all` adds heading markers.
- Later: drawing other text in place of hidden text (nvim's `cchar`, `[x]` as
  a check mark) needs more than zero width.
- Horizontal scrolling and `getMaxCols` still count characters; a line with
  hidden runs only looks shorter.
- To check: vim motions count characters, not what is drawn, so `w` and `l`
  step over hidden markers one at a time, showing them as the caret enters
  the item. That is nvim's behavior too.

## Phase 7: following links

Ctrl+Enter on a link goes where it points. Anywhere else it is `task`, as
now: Obsidian also uses one key to follow a link or toggle a box.

- `followLink`, a new command: the link under the caret, inline
  `[text](url)`, reference `[text][ref]` (through its `[ref]: url`
  definition), an autolink `<url>`, or a bare URL.
- What the target is:
  - `#heading` in this file: the heading whose GitHub-style slug matches
    (lower case, punctuation dropped, spaces to `-`, `-1`, `-2` for
    repeats), found through the tags from Phase 3;
  - a relative or absolute path, `notes.md` or `../README.md`, from the
    buffer's directory: opened in j, then to its `#heading` if it has one;
  - a line in a file, `file.java#L42`: opened at that line;
  - `http:`, `https:`, `mailto:`: handed to the desktop's browser (j's
    `BrowseFile`, or `java.awt.Desktop`).
- Going there pushes the jump list, so vim's `Ctrl-O` and j's jump back
  return.
- A target that does not resolve says so in the status bar, with the
  heading or file it looked for.
- Ctrl+Enter becomes `followLinkOrTask`: `followLink` if the caret is on a
  link, else `task`. Ctrl+Shift+Enter stays `task cancel`. In vim, `gx`
  also follows a link, and Ctrl+click does with the mouse.
- With Phase 6, the caret on a link's text shows its URL as it follows it.

## Phase 8: electric pairs

Typing an opener inserts its closer; typing the closer where it already is
steps over it. For every mode, with Markdown's own pairs.

### What others do

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

### Edge cases they agree on

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

### For j

- An `ElectricPairs` handler under `Editor.insertNormalChar`, so typing in
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

## Phase 9: larger heading fonts

The most invasive, so last.

- `parseBuffer` keeps the heading level in the flags, and
  `Mode.getLineScale(Line)` gives the scale: 1.4, 1.2, 1.1 for levels 1-3,
  else 1, behind `MarkdownMode.scaleHeadings`.
- `Display` derives scaled fonts, cached by style and scale, and draws a
  scaled line on its own baseline. `measureLine` uses them, so the caret and
  clicks hold.
- `Buffer.getLineHeight(Line)`, `line.getHeight()` by default, replaces the
  direct calls in `Display` and in `Buffer.getY` and `getDisplayHeight`, which
  sum real heights (the loop for folded buffers already walks the lines).
- To audit: scrolling a line at a time (`scrollPixelsUp(charHeight)`),
  `reframe`, the scroll bar, the caret's height, and vim's `H`, `M`, `L` and
  `Ctrl-D`, which count rows by `charHeight`. Image lines show
  `pixelsAboveTopLine` copes with taller lines.

## Colors for light and dark backgrounds

From GitHub's Primer light and dark palettes; each foreground is at least 4.5:1
(WCAG AA) on its background, which is why not-started is amber on a light
background rather than yellow.

| Style | Light | Dark | |
|---|---|---|---|
| `heading` | `#0550AE` | `#58A6FF` | bright blue, bold italic |
| `link` | `#0969DA` | `#79C0FF` | underline |
| `url`, `muted` | `#6E7781` | `#8B949E` | |
| `code` | `#953800` | `#FFA657` | orange, apart from blue and green |
| `quote` | `#57606A` | `#8B949E` | italic |
| `listMarker` | `#8250DF` | `#D2A8FF` | |
| `todo` | `#9A6700` | `#E3B341` | |
| `inProgress` | `#8250DF` | `#BC8CFF` | brackets |
| `inProgressMarker` | `#0969DA` | `#58A6FF` | |
| `done` | `#1A7F37` | `#3FB950` | text `muted` |
| `cancelled` | `#8C959F` | `#6E7681` | strikethrough |

Themes override any of them (`color.heading = r g b`); `Default`, `Dark`,
`SolarizedLight` and `SolarizedDark` get lines that suit them.

## Order and tests

| Order | Work | |
|---|---|---|
| 1 | 0a, 0b, then 1 | the groundwork and a usable mode |
| 2 | 2 and 3 | small, and most of the value |
| 3 | 4 | medium |
| 4 | 5, v1 | medium; v2 later |
| 5 | 6 | medium |
| 6 | 7 | small to medium |
| 7 | 8 | medium; for every mode |
| 8 | 9 | large, the riskiest |

Tests in `test/src`, after `RainbowDelimitersTest` and `SearchHighlightTest`:
format runs for each construct, cycling tasks and undoing it, the outline's
nesting, fold ranges, hidden runs' width and the caret's column on a line with
them, and `getY` with lines of several heights.
