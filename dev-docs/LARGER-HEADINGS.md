# Larger heading fonts: plan

Lines of more than one height, for Markdown headings drawn larger than the
text. Moved here from the Markdown plan (`MARKDOWN-MODE.md`), where it was
Phase 9, the most invasive of its phases: it touches how every line is
measured, scrolled and drawn.

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

