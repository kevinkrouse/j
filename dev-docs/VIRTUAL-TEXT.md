# Virtual text: plan

Text and lines that the display draws but the buffer does not hold: a hint
over a listing, the blame of the caret's line after its end, a hunk's old
lines above it, "accept ours · theirs" over a conflict, a parameter name
before an argument. The caret, search, copying, saving and undo never see
them.

Today's nearest things are dir mode's hint, which is a Swing `Label` over
the window (`Editor.updateHint`), hidden markup (`LineSegment.isHidden`,
drawn with no width), the blockquote bar (`BAR`), and `ImageLine`, a line
painted instead of drawn from text. Each is a special case. The git tools
(`GIT-TOOLING.md`) want four more, so this comes first.

## What others do

**Neovim: extmarks.** One mechanism for all of it. `nvim_buf_set_extmark`
puts a mark at a (row, col), in a namespace, which edits move. A mark can
carry `virt_text` (a list of [text, highlight] chunks) placed by
`virt_text_pos`: `eol` (after the line's end), `overlay` (drawn over the
text), `right_align`, `inline` (since 0.10; between characters, pushing text
right), or at a fixed `virt_text_win_col`. It can also carry `virt_lines`,
whole lines below (or `virt_lines_above`) the mark's line, which take up
screen rows and have no buffer line number. Plus `sign_text` (the sign
column), `line_hl_group`, `conceal`, and a priority. A namespace is cleared
as a whole. Gitsigns draws its blame with `eol` text and its inline preview
with `virt_lines`. Inlay hints are `inline`. Neogit's hints are plain text
in a scratch buffer, and the cursor skips nothing.

**VS Code.** Decorations: `before` and `after` attachments on a range, with
`contentText` and a style. They are inline, and the cursor steps over them.
Inlay hints are a provider API drawn the same way, and can be clicked.
CodeLens are whole lines above a line, made of clickable commands, as "Run
test" or "Accept Current Change | Accept Incoming Change" over a conflict.
View zones are blocks of any height between lines, filled with DOM, for the
peek view and inline diffs. Ghost text (suggestions) is inline and goes past
the end of the line.

**IntelliJ.** The `Inlay` API on the editor's `InlayModel`: inline elements
(at an offset, between characters: parameter hints), after-line-end
elements (blame, debugger values), and block elements (above or below a
line, of any height: Code Vision's "3 usages", rendered Javadoc). Each has
a renderer that paints and reports its width or height, and can take
clicks and hovers. Inlays move with the offset they are attached to, as a
`RangeMarker` does. Folding placeholders and soft wraps are their own
models.

**Emacs.** Overlays and text properties: `before-string` and `after-string`
(inline or, with a newline, whole lines), `display` (draws something else
in place of the text, an image or a string), `invisible`, and `line-prefix`.
`header-line-format` is a line pinned at the top of a window, which is
where many modes put their hints. Magit's hints are plain text.

**Helix and Zed.** Helix has three kinds of annotation: inline (inlay
hints), overlay, and line annotations (virtual lines, used for diagnostics
below a line), with positions that map through edits. Zed's `InlayMap`
holds inline hints, and its `BlockMap` holds blocks between lines
(diagnostics, inline diff hunks, the assistant's panels).

**What they agree on.**

1. Anchors move with edits, and a decoration lives at an anchor, not at a
   line number.
2. There are four placements: inline, end of line, overlay, and blocks above
   or below a line.
3. Each decoration has an owner (a namespace, a provider), which updates
   and clears its own.
4. The caret and editing never land inside virtual text. Moving across
   inline text is one step, and virtual lines are not counted by `j` and
   `k`.
5. They are drawn in styled runs or by a renderer, and can be clicked or
   hovered.

## For j

### The model

- **`VirtualText`**: an anchor, a placement, content, a priority, and an
  owner.
  - The anchor is the `Line`, as a `Position`'s is: typing on the line
    keeps it, and lines inserted before it move it. A deleted line takes its
    text with it. Joining lines keeps the first line's object, so text on a
    line joined into the one above it goes, and text on the line a deletion
    starts in stays on what is left there, as nvim's extmarks do. Owners
    whose text is about a line's content (blame, previews) redraw it on
    change anyway. An `INLINE` anchor will need an offset as well.
  - The placement is one of `INLINE` (before the anchor's column), `EOL`
    (after the end of the line, one space of gap), `RIGHT` (at the right
    edge, if it fits), `ABOVE` or `BELOW` (whole lines), or `TOP` (above
    line 1 of the buffer, for hints).
  - The content is a list of runs (text plus a format or a color, as
    `LineSegment` has), or a `Painter` for a renderer that paints and gives
    its width or height. A run may carry an action, which makes it a link.
- **`VirtualTextLayer`**: per buffer, a map from owner (`"vcs.blame"`,
  `"vcs.conflict"`, `"hint"`) to its decorations, indexed by line. An owner
  replaces all of its decorations at once with `set(owner, list)`, or clears
  them. Changes tell the displays to repaint the lines involved, or the
  whole window when rows come or go. Providers are added by modes and
  features, never by `Display`. It never takes the buffer's lock inside
  its own; painting takes its lock inside the buffer's read lock.

### Drawing (`Display`)

`Display` already lays a line out into `textArray` and `formatArray`, with
`HIDDEN` and `BAR` as flag bits, and works out x from a column with
`measureLine` and a column from x with `getColumn`. Each placement fits that
differently:

- **`EOL` and `RIGHT`** are the cheapest: they are drawn after the line's
  last character, or right-aligned, in their own runs, and change no
  mapping. They are cut off at the window's edge, or left out when they do
  not fit (`RIGHT`). This is phase 1.
- **`ABOVE`, `BELOW` and `TOP`** add height to a line. The y of a line
  already comes from adding up `Line.getHeight()`, and scrolling is by the
  pixel, so a line's height becomes its text plus its virtual lines.
  `paintLine` draws them in the extra space, without line numbers, the
  gutter blank or drawn by the owner. The mapping from y to line and caret
  placement must step over that space. Scrolling by line counts a line with
  its virtual lines as one, as nvim does. A `TOP` line is a block above line
  1. It scrolls away with line 1, as Neogit's hint does, and replaces dir
  mode's `Label`. What must stay in sight, as the log's filters, goes in a
  window header instead, which is a component, not virtual text. This is
  phase 2.
- **`INLINE`** changes columns. It is laid into `textArray` with a new
  `VIRTUAL` flag bit, and the column from x (`getColumn`) and the x of a
  column skip the flagged characters, as `HIDDEN` makes them take no width.
  It is the most work, since every place that turns a column into an x has
  to go through one mapping. It is phase 3, and nothing in the git plan
  needs it.
- **Overlay** is not needed yet. Hidden markup covers concealing, and the
  rest is format bits.

Hit-testing: a click on a virtual run with an action runs it, and a hover
shows its tooltip. A click on virtual text without an action puts the caret
at its anchor. Selection never includes it, and copying never copies it.

Vim: `j`, `k`, counts and `:N` go by buffer lines, and so do `gj`/`gk`,
since j does not wrap lines. Scrolling by line (`Ctrl E`, `Ctrl Y`) treats a
line and its virtual lines as one step.

### Uses

| Use | Placement | Owner |
|---|---|---|
| The key hint over a listing (dir mode, VCS buffers) | `TOP` | the mode, from `getHints` |
| The caret line's blame | `EOL` | `vcs.blame` |
| "accept ours · theirs · both · base" | `ABOVE` the `<<<<<<<` | `vcs.conflict` |
| The commit message's 50/72 length notes | `EOL` | checkin mode |
| Later: diagnostics, compile errors under a line | `BELOW` | compilation |
| Later: parameter names, inferred types | `INLINE` | an LSP client |
| Later: a markdown image or mermaid diagram under its source | `BELOW`, painter | markdown mode |

### Phases

1. Done. The model, the layer and `EOL`/`RIGHT`. Tests: anchors through
   insertions and deletions on and around the line, and an owner replacing
   and clearing its decorations. The blame owner comes with git's blame
   (`GIT-TOOLING.md`, phase 7), not as a stub.
2. Done. `ABOVE`/`BELOW`/`TOP`. Dir mode's hint moves onto `TOP`, and
   `Editor.updateHint`'s label goes. Tests: y to line and back with virtual
   lines on the first, last and a folded line; scrolling by the pixel and by
   line; the caret never in virtual space; a painted `TOP` row pushing the
   text down; `fuzz-windows` with decorations on.
3. `INLINE`, when there is a use for it.

### Open questions

- Whether `BELOW` lines of a folded line are shown.
