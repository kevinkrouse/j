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
- [Phase 6: hiding link URLs](#phase-6-hiding-link-urls)
- [Phase 7: larger heading fonts](#phase-7-larger-heading-fonts)
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

- `Mode.getFoldRange(Editor, Line)`, null for indentation as now, is asked
  first by `Editor.foldNearLine` and `fold`.
- Markdown: a heading folds to the next heading of its level or higher; a list
  or task item folds its more indented children and continuation lines; a
  fence folds its body.
- `foldHeadings [level]`, like `foldMethods`, for an outline.
- Vim `za`, `zc`, `zo`, `zR`, `zM` on `fold`, `unfold`, `unfoldAll`, for every
  mode.

## Phase 5: highlighting code in fences

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

## Phase 6: hiding link URLs

nvim's `conceallevel=2` with an empty `concealcursor`.

- The formatter marks runs to hide with a `CONCEAL` bit: the `[` and `](url)`
  of a link, and, if asked, `**` and backticks.
- `drawText` and `measureLine` give those runs no width; the caret and clicks
  measure with `measureLine`, so they agree.
- `Display` shows the caret's line whole. Moving the caret to another line
  marks both lines changed, as the block caret's repaint already does.
- `MarkdownMode.conceal = links | markup | none`.
- Later: drawing other text in place of hidden text (nvim's `cchar`, `[x]` as
  ☑) needs more than zero width.
- Horizontal scrolling and `getMaxCols` still count characters; a hidden line
  only looks shorter.

## Phase 7: larger heading fonts

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
| 6 | 7 | large, the riskiest |

Tests in `test/src`, after `RainbowDelimitersTest` and `SearchHighlightTest`:
format runs for each construct, cycling tasks and undoing it, the outline's
nesting, fold ranges, hidden runs' width and the caret's column on a line with
them, and `getY` with lines of several heights.
