# Scrollbar marks

Colored marks on the vertical scrollbar show where a buffer's bookmarks,
search hits, changes and conflicts are. Hovering a mark shows what it is and
its line; clicking it jumps there. Diagnostics come later, with LSP. User
docs: `doc/navigation.html#scroll-bar-marks`, `doc/preferences.html#scrollBarMarks`.

## What others do

**IntelliJ.** An error stripe beside the thumb: one tick per error, warning,
search hit, TODO and VCS change. Hovering shows the message, clicking goes
there. A severity icon sits at the top.

**VS Code.** An overview ruler over the scrollbar with three lanes: left for
VCS changes, middle for search and selection hits, right for diagnostics. The
cursor line is a thin bar. Clicking the ruler scrolls; it has no tooltip.

Taken here: VS Code's placement over the scrollbar, and IntelliJ's hover and
click-to-jump.

## Facts it rests on

- Each pane is an `Editor` (a `BorderLayout` `JPanel`) with a `Display` in the
  center and a `VerticalScrollBar` (a `JScrollBar`) beside it; no
  `JScrollPane` (`Editor.addVerticalScrollBar`).
- The scrollbar model is in pixels: maximum `buffer.getDisplayHeight()`,
  extent the Display's height, value `buffer.getY(topLine) +
  pixelsAboveTopLine` (`Editor.updateVerticalScrollBar`). Folded lines take no
  height; virtual rows (blame, diff fillers) do. There is no soft wrap.
- `buffer.getY(line)` asserts the line is not hidden and walks every visible
  line when the buffer is folded, so it cannot be called per mark.
- `ScrollBarUI` is used only with j's own look and feel
  (`DefaultLookAndFeel`); Metal, System and Aqua use their own.
  `Frame.resetDisplay()` replaces every scrollbar on a theme change.
- In a side-by-side comparison the old pane's gutter is on its right and its
  scrollbar on its left (`OldSide.isGutterOnRight()`,
  `Editor.scrollBarsLeft()`).
- `Display` already serves Swing tooltips per mouse event
  (`Display.getToolTipText(MouseEvent)`, `Display.toHtml`).
- There is no marker-changed event: VCS signs, search changes and folds end in
  `Editor.repaintDisplay()` or `Editor.updateScrollBars()`.

## Design

### Drawing over the scrollbar

`VerticalScrollBar.paintComponent` calls `super`, which paints the track and
thumb, then paints the marks over both. This works under every look and feel.
The bar holds the snapshot; one replaced by `resetDisplay()` makes it again.
The arrow buttons are children, painted afterwards, so marks never cover them.

The track rectangle comes from `ScrollBarUI` when it is installed, through a
new public `trackBounds()` returning `getTrackBounds()` (protected in
`BasicScrollBarUI`). Under other looks and feels: the component's bounds less
its insets and the heights of its `JButton` children.

Changes and conflicts are drawn in a lane 3 scaled pixels wide on the edge
next to the text: the left of a right-hand scrollbar, the right of a
left-hand one (the old side of a comparison), like the gutter's change bar;
a span of lines reads as a thin vertical line, as IntelliJ's do. Search hits
and bookmarks span the track's width less 2 scaled pixels at each side, over
that lane: kinds paint in ascending priority. Colors are translucent (alpha
140 of 255): subdued on the track, with the thumb and the marks under
showing through; a kind's overlapping marks are filled as one rectangle, so
they don't darken where they overlap. A mark of one line, or of a boundary
between lines, is always 2 scaled pixels high, however long the buffer; a
span of lines is as high as its lines, at least 4. Where changes of
different kinds side by side would overlap, grown to those heights, the
later moves down under the earlier with a pixel between; the places are
worked out once per track size and window, for painting and hit testing.

### Coordinates

Marks use the scrollbar's own coordinates, so a mark lies inside the thumb
exactly when its line is on screen:

    trackY = track.y + y * track.height / max(height, windowHeight)

`y` and `height` are the mark's in the model's pixels: the line's text top and
its lines' height, virtual rows before it included, as `buffer.getY` counts.
A mark drawn at another height than its own is centered on its place.
With `max(..., windowHeight)` (the Display's height: the model clamps its
extent to the buffer's), the marks of a buffer shorter than the window sit
where its lines sit rather than being stretched. A hidden line takes its
fold's visible first line's place.

### The marks

A new `ScrollMarks` class (package `org.armedbear.j`) builds an immutable
snapshot for one Editor:

```java
enum Kind { MODIFIED, SAVED, ADDED, CHANGED, DELETED, CONFLICT, SEARCH, BOOKMARK }   // ascending priority
record Mark(Kind kind, Line line, int offset, int lineNumber, int lines, int y, int height, String text)
```

`build(Editor)` makes one walk over the buffer's lines, counting line numbers
and adding up y (each visible line's height, plus its virtual rows from one
locked copy of the layer's, `VirtualTextLayer.rowsByLine()`). On each line it
collects that line's marks from the sources below. One walk gives every mark
its y and line number without `getY`, `getLine(int)` or `renumber()`. Marks
are kept in buffer order, which is y order, for hit testing.

Sources:

| Kind | Source | Notes |
|---|---|---|
| bookmark | `Editor.getBookmarks()`: markers of this buffer by line identity; those of a closed buffer of the same file by line number, as `Marker.gotoMarker` finds them. | Numbered, lettered (also Vim's `A`-`Z`) and the temporary marker. The text names the bookmark. |
| search | `editor.getSearchMatcher()`: what `getSearchMatches(line)`, the call the Display paints highlights with, gives for every line, its settings read once, with a key that changes when they do (`InputHandler.getSearchMatcher`, `Search.matchKey`, the mode). Marks appear exactly when highlights do (`highlightSearchMatches`, Vim `hlsearch` and its pattern being typed). One mark per line with hits; the text gives the count. | Costly on big buffers: stop at 20 ms (checked every 16 lines) and drop all search marks rather than show some; not tried again in that buffer until the search's key changes. |
| added, changed, deleted | `buffer.getSigns()` when change marks show (`showChangeMarks`, not a new file) and `vcsSigns` is on; consecutive equal signs merge into one span. Without signs: runs of `line.isModified()`, as in `ChangeMarks.hunkStarts`. | A deletion (`Sign.above`) is a point mark. |
| added, changed, deleted | `buffer.getChangeSpans()`: a comparison's side gives its changes from the alignment (`CompareBuffer.spans`), a new list with each alignment; other buffers give none. | A change with no lines on this side is a point mark at the boundary the comparison track draws: under the line before, over the filler rows. Shown whatever `showChangeMarks` says. |
| conflict | `buffer.getConflicts().openers()`, each a span of its region's lines (`regionAt(opener)`, end - start + 1), labelled with the markers' labels as the conflict row does. Covers any change mark on those lines. | Only on unmerged files. A new list of openers per scan. |

Diagnostics, the cursor line, selections, folds, jump and change history,
Vim's lowercase marks, breakpoints and TODOs are left out.

### When to rebuild

`VerticalScrollBar` holds the last snapshot, which records what it was made
from:

    buffer, firstLine (Buffer.empty and setText replace every line without
    a modCount), modCount, foldVersion, rowsVersion, charHeight, fileChangedAt
    (saves mark lines saved), whether change marks show, signs (identity),
    the bookmark slots (by identity: Marker.equals matches a moved bookmark
    dropped again where it was first), conflict openers (identity; a new
    list per scan), the search matcher's key, the change spans (identity;
    a new list per alignment)

`paintComponent` and the hover and click handlers rebuild when any differs;
otherwise they use the snapshot. Scrolling, the most frequent repaint,
rebuilds nothing.

Swing repaints only the thumb's old and new places when the model changes, so
a rebuild inside that clip would leave stale marks elsewhere. Hence:

- `Editor.updateVerticalScrollBar()`, after every command, repaints the whole
  bar when the snapshot is stale: an edit, a fold, a buffer switch.
- `paintComponent` repaints the whole bar when it rebuilt under a partial clip.
- `Editor.repaintDisplay()` repaints the bar: VCS signs, search highlight
  changes, Vim preview.
- `Editor.setBookmark` and `setTemporaryMarker` repaint every bar.

### Hover

The constructor calls `setToolTipText("")`. `getToolTipText(MouseEvent)` lists
the marks within 2 scaled pixels of the pointer, across the track's width,
nearest and highest priority first, at most 6 and then "and N more":

    Bookmark 3, line 120
    Search (2 matches), line 300
    Changed, lines 410-418

Texts are escaped with `Display.escapeHtml` and joined into HTML. The tool tip
sits beside the bar over the text (at the pointer it would leave the frame and
cover the marks near the one it names). The pointer becomes a hand over a mark
outside the thumb. Away from marks there is no tool tip and the scroll bar
behaves as before.

A preview of the lines is later work; the tool tip is where it would go.

### Click

`processMouseEvent` is overridden. A primary-button press on a mark, outside
the thumb, jumps to the nearest mark and returns without calling `super`, so
`BasicScrollBarUI`'s track listener neither pages nor starts a drag; the
release and any drags until it are swallowed too. A press on the thumb drags
it as before: the lines of the marks on it are already in the window. This
needs no cooperation from the look and feel; under other looks and feels the
thumb is located as `BasicScrollBarUI` lays it out.

The click first does what a click in the Display does: ends the location
bar's input (an incremental find finishes) and makes the window current
(`Dispatcher.clickedIn`, shared with the Display). The jump goes through a new
`Editor.jumpToLine(Line, int)`, to which `jumpToLine(int, int)` delegates
after `getLine`: `buffer.jumpTarget(line)` (which comparison sides override),
`recordJump()`, the offset clamped, `moveDotTo`, `REFRAME`, then
`updateDisplay()`, which unfolds a hidden destination. A line no longer in the
buffer is not jumped to. Bookmarks carry their offset; search marks the first
match's start; spans their first line.

### Colors

New theme keys, with fallbacks in `ScrollMarks` chosen for contrast with the
scrollbar's background (`ScrollBar.track`):

| Kind | Key | Fallback |
|---|---|---|
| bookmark | `color.scrollMarkBookmark` | violet |
| changed since loaded, saved | existing `color.change`, `color.savedChange` | as the gutter's |
| search | `color.scrollMarkSearch` | `color.searchMatchBackground` with its saturation raised |
| added, changed, deleted | existing `color.vcsAdded`, `color.vcsChanged`, `color.vcsDeleted` | `Signs.color(kind, track)` |
| conflict | `color.scrollMarkConflict` | orange |

Search highlight backgrounds are pale, chosen to sit behind text; on a light
gray track they need more saturation to be seen.

### Preference

`scrollBarMarks`, boolean, default true, read through
`buffer.getBooleanProperty(Property.SCROLL_BAR_MARKS)` so modes and buffers can
override it. When false, nothing is built or painted and the scrollbar behaves
as it did. Documented in `doc/preferences.html` with the color keys, in
`doc/navigation.html`, and as a jump in `doc/commands.html` (`jumpBack`).

## Steps

1. `ScrollMarks`: kinds, `Mark`, the walk with bookmark and change sources,
   y mapping, colors. `VerticalScrollBar` paints them and holds the snapshot;
   `ScrollBarUI` gains `trackBounds()`; the repaint hooks
   (`updateVerticalScrollBar`, `repaintDisplay`, `setBookmark`,
   `setTemporaryMarker`); the preference.
2. Hover and click: tool tip, hand pointer, `processMouseEvent`,
   `Editor.jumpToLine(Line, int)`, `Dispatcher.clickedIn`.
3. Search marks: `SearchMatcher`, `InputHandler.getSearchMatcher`, the time
   limit.
4. Conflicts and comparison changes: `Buffer.getChangeSpans`.
5. Documentation.

Each step works on its own.

## Later: diagnostics

Once LSP is supported, its diagnostics become ERROR, WARNING and INFO kinds
above BOOKMARK in priority, with `color.scrollMarkError`,
`color.scrollMarkWarning` and `color.scrollMarkInfo`. The LSP client's
per-buffer diagnostics are the source, their version joins the key, and their
publication repaints the scrollbar; the tooltip shows the message. Compiler
and XML output (`CompilationErrorBuffer`), which carries no severity, is not
used.

## Verification

- Unit tests of the y mapping: unfolded, folded (a mark on a hidden line
  lands on the header), virtual rows above a line, a buffer shorter than the
  window; the change lane at the text's side, under the other marks.
- Driver run: a file with bookmarks, VCS changes and a search; check the
  marks, a tooltip, and that a click moves the caret, unfolds a fold, and
  does not page the view. The same in a side-by-side
  comparison, checking the old pane's left scrollbar.
- Timing on a 200,000-line file: rebuild without search well under a frame;
  search with a costly regex stops at the limit.
- Metal look and feel: marks inside the track, clear of the arrow buttons.

## Risks

- `getSearchMatches` follows the Editor's search, which may be shared by every
  window (`shareSearch`); marks then follow it too, as highlights do.
- A marked line deleted since the snapshot is not jumped to
  (`Buffer.contains`); the next rebuild drops it.

## Review notes

Minor issues found by the review after each step and left as they are.

### Step 1

- Lines hidden before the first line shown (only TOP's rows above them) are
  marked at y 0 rather than at the first line shown.
- Mark colors are cached per snapshot and track color: a color preference
  changed without a rebuild shows at the next rebuild.
- Each rebuild walks every line, O(lines), on the event thread. An edit, a
  fold or a sign update rebuilds; scrolling does not.
- `Buffer.getDisplayHeight` uses the renumbered visible line count; until a
  renumber the model's maximum and the marks' height can differ by the lines
  added since.

### Step 2

- A mouse move over the bar finds the marks near the pointer twice, for the
  hand pointer and the tool tip: a linear scan stopped past the pointer.
- `MOUSE_CLICKED` after a swallowed press still goes to Swing; neither the
  track listener nor the tool tip manager acts on it.
- The firstLine key catches lines replaced wholesale (`Buffer.empty`,
  `setText`) only when the first line is replaced too, as both do; the jump
  also checks the line is still in the buffer, an O(lines) walk per click.
- `getToolTipLocation` builds a `JToolTip` to measure the text, once per
  tool tip shown.

### Step 3

- `Search.findStringInLine` lowercases the rest of the line for each match
  of an ignore-case search, so one long line with many matches costs
  O(matches × length); the deadline, checked every 16 lines, cannot stop
  one line. The Display pays the same for the lines it paints.
- A literal search over 200,000 short lines takes 12 to 18 ms to mark, near
  the 20 ms limit: much larger files show no search marks.
- A search that takes just under the limit runs again in full after each
  edit.
- `Editor.getSearchMatcher` makes a key and a lambda on each check of the
  snapshot: each paint and mouse move over the bar.

### Step 4

- Comparison marks use the vcs colors (`color.vcsAdded` and so on), not the
  comparison track's shades.
- Conflict regions are found with `Conflicts.regionAt`, a scan of the regions
  per opener: O(regions²) per rebuild.
- A conflict's line count is its region's as of the last scan; an edit
  inside the region rescans and makes a new list of openers, so the marks
  are made again.

### Step 5

- No theme sets the `color.scrollMark*` keys: the fallbacks are chosen for
  the scroll bar's track, which j's look and feel keeps light whatever the
  theme.
