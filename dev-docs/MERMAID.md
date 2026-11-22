# Mermaid diagrams in Markdown: plan

Render ```` ```mermaid ```` fences in Markdown buffers as pictures, with a
way to flip between the rendered diagram and its source — Obsidian-style,
the picture *in place of* the fence, revealed to source when the caret
enters.

This is the design: what other editors do, what j already has that fits,
the one hard constraint that decides where the code lives, the rendering
options with their tradeoffs, and a phased build. The core primitive is a
new virtual-text placement, `REPLACE`, which is first proven by **inline
images** in core's Markdown mode (no external renderer), then used by the
**Mermaid extension** — which is also the vehicle for prototyping a
**Clojure** extension via the AOT bridge pattern from
`GAMES-EXTENSION.md`. The facts about j below are read from the tree; the
facts about the renderers are from their projects as of 2026-11.

- [What other editors do](#what-other-editors-do)
- [What j has that fits](#what-j-has-that-fits)
- [The constraint: core carries no dependency](#the-constraint-core-carries-no-dependency)
- [Rendering options](#rendering-options)
- [The toggle: a `REPLACE` placement (Obsidian-style)](#the-toggle-a-replace-placement-obsidian-style)
- [Architecture](#architecture)
- [Core enabling changes](#core-enabling-changes)
- [Phased plan](#phased-plan)
- [Traps](#traps)
- [Open questions](#open-questions)

## What other editors do

Every editor that renders Mermaid calls out to JavaScript or a native
renderer; none reimplements the layout engine in the editor's own language.
The differences are *where* the picture appears and *how* you get back to the
source.

| Editor | Renderer | Where the diagram shows | Source/rendered toggle |
|---|---|---|---|
| **VS Code** (built-in since 1.121, ex-Matt Bierner extension) | mermaid.js in the preview's webview | Markdown **preview** pane only; the editor shows source | Separate preview pane (`Ctrl+Shift+V`); editor and preview are different views |
| **JetBrains** (Markdown plugin) | mermaid.js in **JCEF** (embedded Chromium) | Split preview pane | `viewSource`-style: preview pane ↔ editor, caret mapped back to the block |
| **Obsidian** | mermaid.js in its Electron webview | **Inline**, in the live-preview editor, replacing the fence | Live preview: caret in the fence reveals source, caret out renders it |
| **Zed** (built-in `markdown_preview`, PR #49064) | mermaid.js in a GPUL rendered webview | Side preview | Preview tab vs. code tab |
| **mermaid-preview IntelliJ plugin** (slothlabs) | external, no network | Side panel | **Per-block "Code ↔ Diagram" toggle** |
| **mermaid-preview-offline (VS Code)** | bundled renderer | Native editor tab for `.mmd` | Layout control: preview-only / source-only / beside / above |
| **Emacs `ob-mermaid`** | `mmdc` (mermaid-cli) → image file | Inline image below the source block (org) | Org's own show/hide results |

**What they agree on.**

1. Nobody writes a Mermaid layout engine in the host language. The engine is
   mermaid.js (in a browser/webview) or a native reimplementation shelled
   out to.
2. The diagram is produced as **SVG or PNG**, then displayed as an image.
3. The toggle is either a **separate view** (VS Code, JetBrains, Zed) or an
   **inline reveal** driven by the caret (Obsidian), or a **per-block
   switch** (the IntelliJ plugin).
4. Rendering is **asynchronous** — it never blocks the editor — and cached
   per block, keyed on the block's text.

## What j has that fits

j is a Java2D editor with no webview and no browser component, but several
pieces line up well.

- **`VirtualText` / `VirtualTextLayer`** (`src/org/armedbear/j/VirtualText*.java`).
  Per-buffer, owner-keyed decorations with placements `EOL`, `RIGHT`,
  `ABOVE`, `BELOW`, `TOP`, `BACKGROUND`. `VIRTUAL-TEXT.md` already lists
  *"a markdown image or mermaid diagram under its source — `BELOW`,
  painter — markdown mode"* as a planned use. **This is the intended home
  for an inline diagram.** Today a row is a fixed `charHeight` of text
  (`Display.paintRows`, `rowHeight = line.getHeight() + rows*charHeight`);
  a diagram needs a row of arbitrary pixel height backed by a painter.
- **`ImageLine`** (`mode/image/ImageLine.java`). A `Line` that paints image
  strips instead of text, each strip of arbitrary height, with click→`Link`
  hit-testing. Proven in image mode and the web buffers. A rendered diagram
  is exactly this shape.
- **`SvgIcon`** (`util/SvgIcon.java`). A tiny in-house SVG→Java2D renderer.
  **Not usable for diagrams**: it is deliberately scoped to j's icon subset
  (four elements, straight lines and elliptical arcs) and *throws* on
  anything else. Mermaid's SVG uses `<text>`/`<tspan>`, arrowhead
  `<marker>`s, complex `<path>`s, CSS classes, gradients — all out of scope.
  It is the right tool for icons and the wrong tool here.
- **`MarkdownFormatter`** + **`FenceLanguages`**. The formatter keeps fence
  state in each line's flags and maps a fence's info-string first word to a
  language slot (`FenceLanguages.slotFor`, bits 12–17). `isInFence`,
  `fenceBlock(line)` give the block's first/last line. **But these are
  package-private** (`static`, no modifier, in
  `org.armedbear.j.mode.markdown`), so an extension in
  `org.armedbear.j.mermaid` **cannot call them** — and `fenceNames` only
  drives *coloring*, not rendering. The extension needs a real seam to be
  *handed* the fence body and range. See
  [Fence handler registration](#fence-handler-registration).
- **`WebBuffer.viewSource()`** (`mode/web/WebBuffer.java:189`). The
  existing **rendered↔source** precedent: a `WebBuffer` renders HTML;
  `viewSource` opens the underlying file's source buffer and maps the caret
  back through `WebLine.getSourceOffset()`. This is the model for a
  preview-buffer toggle.
- **`AsynchronousShellCommand`** / `ProcessBuilder` (`AsynchronousShellCommand.java`).
  The pattern for running an external tool off the EDT and reading its
  output.
- **`EditorHooks`** (`extension/EditorHooks.java`). `bufferActivated`,
  `openFile`, `afterSave`, `eventHandled` — buffer **lifecycle** only.
  `eventHandled` fires on every event but "must stay cheap", so it cannot
  scan fences per keystroke. It is *not* the fence seam; the `FenceHandler`
  registry is.
- **`Opener`** (`extension/Opener.java`). The `handles`/action registration
  *pattern* to copy for a fence handler — but `Opener` itself opens
  non-file *names* (URLs, mailboxes), not fences. Wrong seam, right shape.

## The constraint: core carries no dependency

`deps.edn` is explicit: *"core depends on nothing but the JDK."* `bb
check-core` asserts no core class references what an extension provides.
Mermaid rendering needs **something** core does not have — a JS/Chromium
runtime, a native renderer binary, or an SVG rasterizer. Therefore:

> **All Mermaid code lives in an extension** (`extensions/mermaid`), which
> may carry third-party jars (the build copies each extension's resolved
> jars beside its own into `build/lib/extensions/mermaid/`, one classloader
> per directory — `build.clj:extensions`). Core gets only the small,
> generic enabling hooks listed in [Core enabling changes](#core-enabling-changes),
> none of which mention Mermaid.

This keeps `bb check-core` green and the core dependency-free.

## Rendering options

Mermaid is a parser + a graph-layout engine (dagre-style) + an SVG renderer.
Reimplementing that is a large, permanent project. The realistic options:

### 1. External `mmdc` (mermaid-cli) → PNG — **recommended default**

Shell out to the official `@mermaid-js/mermaid-cli`.

- **Fidelity**: the real thing; matches GitHub's rendering.
- **Cost**: needs Node + Puppeteer + Chromium on the user's machine.
  Browser startup is ~2–3 s per cold render (mitigated by caching and a
  warm worker). Can point at an already-installed Chromium
  (`puppeteerConfigFile` / `PUPPETEER_EXECUTABLE_PATH`) to avoid the
  bundled download.
- **Output**: ask for **PNG** and j needs *no* SVG renderer at all —
  `ImageIO` reads PNG (`ImageLoader` already does). We render PNG only;
  crisp vector zoom would need a rasterizer we are not carrying.
- **Fit**: matches `ob-mermaid`'s model and j's existing
  `AsynchronousShellCommand` pattern.

### 2. External native renderer → PNG — **recommended fast path**

A browser-free renderer: **selkie** (Rust, near-complete), **mmdr /
mermaid-rs-renderer** (Rust, 100–1400× faster than `mmdc`), or the Go/
.NET/Python ports. Run as a subprocess; produce PNG.

- **Fidelity**: improving fast but "early development"; some diagram types
  and edge cases lag mermaid.js. Good enough for flowchart/sequence/
  class/gantt; C4 and newer types may not render.
- **Cost**: one small native binary the user installs (found on `PATH` or
  pointed at by `mermaid.rendererPath`). No Node, no Chromium. Sub-100 ms
  renders.
- **Fit**: same subprocess plumbing as option 1; the binary is pluggable.

### 3. JCEF / embedded Chromium — **rejected**

What IntelliJ uses. A ~100 MB+ native Chromium, a heavyweight AWT peer that
breaks j's lightweight Java2D painting, focus, and split model. j has no
browser component and adding one is far out of proportion. Rejected.

### 4. Implement our own subset — **rejected for now**

A hand-written Mermaid parser + layout engine in Java, in the spirit of
`SvgIcon`. The honest scope: even a flowchart-only subset needs a lexer, a
grammar, a node/edge model, a layered layout algorithm, edge routing, and
text measurement. That is a multi-thousand-line permanent engine that will
chase Mermaid's spec forever, and it is the *least* faithful option.
`SvgIcon` works because icons are 4 elements; Mermaid is not. Rejected
unless every external option proves unacceptable.

### Recommendation

**Pluggable renderer, two backends, PNG-only, no bundled binary.**

- **Backend A — `mmdc`** (default): highest fidelity, for users who have
  Node/Chromium. Output **PNG**.
- **Backend B — native binary** (`selkie`/`mmdr`): fast, browser-free, for
  users who install one binary. Output **PNG**.
- **PNG only.** No SVG rasterizer (JSVG/Batik) is carried. j reads PNG with
  `ImageIO`/`ImageLoader`, which it already has. Vector-zoom crispness is
  deferred; a PNG rendered at the editor's scale is good enough for now.
- **No bundled binary.** We do not ship `mmdc` or a native renderer in the
  dist. The renderer is found on `PATH`, or the user points at it with a
  property: `mermaid.renderer = auto|mmdc|native` selects the kind, and
  `mermaid.rendererPath = /path/to/binary` overrides the lookup. If
  neither is found, the fence shows a clear "renderer not found" placeholder
  and a doc pointer — never a crash or a silent blank.

This avoids bundling a browser, avoids bundling a binary, avoids writing a
layout engine, and keeps the editor responsive.

## The toggle: a `REPLACE` placement (Obsidian-style)

The user wants Obsidian's behavior: the fence is *replaced* by the diagram
in place, and the caret entering the fence reveals the source to edit. This
is not a new idea in j — it is the **block-scale sibling of the inline
conceal that already ships**.

### The precedent: conceal reveals characters; REPLACE reveals a block

Today, hidden markup (`LineSegment.isHidden`, the `HIDDEN` bit in
`Display`) is drawn with **zero width**, and the caret reveals it by
entering:

- `Display.revealedItems(formatter, line, segments)` (`Display.java:1267`)
  works out which hidden items the caret is inside and lets them draw.
- `Formatter.getHiddenBlock(line)` (`Formatter.java:458`) returns the
  first/last lines of a block whose hidden markup shows together — a fence's
  open and close lines reveal as a unit when the caret is anywhere in the
  fence.
- `Display.showRevealed(g2d, dotLine)` (`Display.java:948`) repaints the
  lines the caret just showed or stopped showing.

`REPLACE` is the same contract at block scale:

| | Inline conceal (ships today) | `REPLACE` (new) |
|---|---|---|
| Unit | a run of characters on one line | a range of lines (a fence block) |
| Collapsed | the run draws with zero width | the range draws **one picture** instead of any of its lines' text |
| Revealed by | caret inside the run | caret inside `[first..last]` |
| Buffer text | unchanged | unchanged |

The reveal predicate is literally the same `isIn(dotLine, block)` helper
(`Display.java:1330`) the conceal path uses, and the block range is the same
`MarkdownFormatter.fenceBlock(line)` already returns. The extension sets a
`REPLACE` on the fence block; the caret reveals it exactly like a hidden
fence.

### What `REPLACE` means

A `REPLACE` virtual text is anchored to a **range** of lines and carries a
**painter** of arbitrary pixel height:

- **Collapsed** (caret outside the range): the whole range paints as one
  picture. The lines in the range draw **no text**. The picture occupies the
  range's vertical space — the anchor line's height becomes the picture's
  pixel height, and the absorbed lines contribute **zero** height.
- **Revealed** (caret inside the range): every line draws its normal text;
  no picture. The user edits the fence as ordinary Markdown.

**The buffer is never touched.** Copy, undo, save, search, and the tagger all
see the real ```` ```mermaid … ``` ```` text. Only painting and the y↔line
mapping change. This honors the `VIRTUAL-TEXT.md` contract: *"the caret,
search, copying, saving and undo never see them."*

### Why the caret is safe

A `REPLACE` is collapsed only when the caret is **outside** its range. The
moment the caret moves into the range (typing, or arrowing down from the line
above), it is revealed — so the caret never has to be positioned *inside* a
picture. The transition "press Down at the end of the line above the fence →
caret lands on the open fence line → block expands to source" is exactly
Obsidian, and falls out of the reveal rule with no special caret handling.

### What REPLACE swaps the source *with*

The replacement is whatever the **painter** draws, and the painter reports
its own pixel height. So the source range collapses to zero and the painter
occupies the anchor line's slot at *any* height. That single primitive
covers three shapes:

1. **One element** — a single image or vector picture (the inline-image
   case). The painter draws one `BufferedImage` scaled to the width and
   reports the image's height. (Mermaid is this shape too, but its painter
   reports the *fence's* source height, not the picture's natural height —
   see [Width and height control](#width-and-height-control).)
2. **A tall rendered thing** — a diagram, a laid-out table, a math
   formula, a QR code. Same as (1); the painter just reports a taller
   height and draws more. There is no "one element vs. many" distinction in
   the model — height is just a number the painter returns.
3. **N display-only rows** — the replacement is *text* (styled runs), not
   a picture: a rendered pipe-table as an aligned grid, a reflowed
   summary, a formula typeset in runs. This is the same run-list content
   `ABOVE`/`BELOW` already carry; `REPLACE` differs only in that it
   *replaces* the range rather than *adding* beside it.

**The contract holds for all three:** the replacement is **display-only**.
It is never the buffer — copy, search, undo, save, and the tagger still see
the real source lines. The caret reveals the source on entry. This is why
the replacement cannot be *editable* text: editable replacement would mean
a real buffer edit, which breaks the "source is always there, just hidden"
property that makes the reveal safe. If a future feature needs the
replacement to be selectable/searchable, that is a different primitive,
not `REPLACE`.

**Interactivity:** the painter can hit-test sub-regions and the existing
`Run.action` model carries click handlers, so a rendered table can have
clickable cells and a diagram clickable nodes — the IntelliJ inlay model
(renderer paints, reports size, takes clicks).

### Do other editors support multi-line / rich replacement?

Yes — this is a standard, named capability, and the content is not limited
to a single image:

| Editor | Multi-line block replacement | Content it can hold |
|---|---|---|
| **VS Code** | **view zones** (arbitrary-height blocks between lines) | any DOM: images, rich HTML, widgets, multi-line |
| **IntelliJ** | **block inlays** (`InlayModel.addBlockElement`) | any renderer: images, rendered Javadoc, "3 usages", tables |
| **Neovim** | **`virt_lines`** (whole virtual lines above/below) | styled text runs; `conceal`+`cchar` for inline char substitution |
| **Emacs** | overlays with `display` + `before-string`/`after-string` carrying newlines | images or strings, whole lines |
| **Org** | **inline images** (`org-display-inline-images`) | multi-line image blocks replacing a link line |
| **Obsidian** | Live Preview block rendering | rendered tables, images, math, callouts — multi-line rich content |

So "swap a range for a single image" and "swap it for many lines of rich
content" are the *same* feature everywhere; j's `REPLACE` + painter matches
the view-zone / block-inlay model. The inline single-character substitution
(nvim `cchar`, VS Code `contentText`) is the inline-scale cousin of the
conceal that j already ships — `REPLACE` is its block-scale sibling.

### Other scenarios besides Markdown fences

`REPLACE` is a general "render this region as a picture/widget, reveal
source on caret" primitive. The Mermaid fence is just the first client.
Grounded in j's own TODOs and existing hooks:

| Scenario | Source region | Replacement | j status |
|---|---|---|---|
| **Inline images** | `![alt](src)` in Markdown | the image | TODO "inline images: png" (done, on by default) |
| **SVG source** | an `.svg` file or inline `<svg>` | the rendered picture | TODO "svg, render inline preview?" |
| **Color swatches** | `#ff0000` in CSS/HTML/theme files | an inline color chip | j has `Formatter.getGutterColor` (gutter only); `REPLACE` adds an *inline* swatch |
| **Base64 / data URIs** | a base64 blob in HTML/CSS/JSON | the image it encodes | new |
| **Math / LaTeX** | `$…$`, `\begin{equation}` | the typeset formula | new; the primitive fits |
| **Emoji shortcodes** | `:smile:` | the glyph | new |
| **Markdown in Javadoc** | a doc-comment block | the rendered Markdown | TODO "markdown in javadoc comments" |
| **Collapsed JSON/YAML** | a `{ … }` node | `{ … }` + child count, expandable | new |
| **VCS ghost blocks** | a deleted region | the old lines, shaded | `GIT-TOOLING.md` wants virtual lines; `REPLACE` is the block form |
| **QR codes** | a URL | a scannable QR | new |
| **Data viz** | a CSV column / log numbers | a bar / sparkline | new |
| **Checkboxes** | `[ ]` / `[x]` | a drawn box | j colors them today; `REPLACE` could draw a real box |
| **File-icon tokens** | a path token | its file icon inline | new |

The common thread: a region whose *meaning* is better shown than read, but
whose *source* must stay editable. That is broad, and it is the argument for
building `REPLACE` once in core rather than special-casing Mermaid.

### The toggle command

- Diagrams render **on by default** (matching inline images'
  `renderInlineImages`). The old "default off so plain text stays fast"
  worry is moot: rendering is async, debounced, and content-hash cached, so
  a buffer with no `mermaid` fence never invokes a renderer, and a buffer
  with one renders off the event thread.
- `toggleMermaid` flips a **per-buffer** flag held in the handler's
  `State` (an `AtomicBoolean enabled`). Off: `flush!` registers no
  `REPLACE` (the source shows) and `-render` does no render work. On: the
  decorations return. The command re-dispatches via
  `MarkdownFences.onActivate`, so the cache makes toggling back on cheap
  -- a body already drawn re-registers without re-rendering.
- The reveal itself is **implicit** -- caret position, no key needed -- which
  is what makes it feel like Obsidian.

### Naming: what other editors call this

There is no single industry term. The concept splits into a **mechanic** and
a **feature**, and j already has the mechanic named:

| Editor | Mechanic (hide source, show substitute) | Feature (block rendered as a picture) |
|---|---|---|
| **Vim / Neovim** | **conceal** (`conceallevel`, `Conceal` hl group); reveal-on-caret is **"anti-conceal"** | — |
| **JetBrains** | rendered-code **inlay** | **"Rendered Code"** (gutter icon / `Ctrl+Alt+Q`) |
| **VS Code** | decorations / hover preview | **"inline preview"** (*Markdown Inline Preview*, *Markdown Inline SVG*); one extension names states **"Rendered → Ghost → Raw"** |
| **Obsidian** | live-preview reveal | **"Live Preview"** (vs. "Reading view") |
| **Emacs / Org** | `display` text property / overlays | **"inline images"** (`org-toggle-inline-images`) |

**Decision:** `REPLACE` stays the internal `Placement` name — it is precise
and sits with the existing `ABOVE`/`BELOW`/`EOL`/`TOP`/`BACKGROUND` enum,
and it *is* conceal at block scale (same reveal rule, `getHiddenBlock`/
`isIn`). The **user-facing** term is **"rendered view"** / **"inline
preview"**, matching JetBrains and VS Code. So: `Placement.REPLACE` in
code, "rendered diagram" in docs and the status bar, `toggleMermaid` for
the command (a per-buffer in-memory flag, not a preference). Do not invent
a new word — the industry already splits it
into *conceal* (mechanic) + *rendered/inline preview* (feature).

### Fallback: preview buffer

If the `REPLACE` core work is ever blocked, a **preview-buffer** design
(`MermaidPreviewBuffer` of `ImageLine`s + `viewSource`, mirroring
`WebBuffer.viewSource`) still delivers a toggle with **zero core changes**.
It is kept as a fallback, not the primary path, because it is not the
in-place UX the user asked for.

## Architecture

Three pieces, in three places:

- **Core (Java):** the `REPLACE` placement and its display machinery.
- **Markdown mode (Java, in core):** inline images via `REPLACE` — the
  first client, and the proof the design works before Mermaid.
- **Mermaid extension (Clojure, AOT):** the renderer backends and fence
  handling, written in Clojure as a bridge to j's Java extension API,
  exactly as `GAMES-EXTENSION.md` does for Sudoku.

```
src/org/armedbear/j/                         # CORE (Java)
  VirtualText.java                           # + Placement.REPLACE, + Painter
  VirtualTextLayer.java                      # + replaceFor(line), range index
  Display.java                               # + paint REPLACE, gutter skip, mapping
  extension/FenceHandler.java                # NEW: handles(fenceName) + render(...)
  extension/ExtensionContext.java            # + registerFenceHandler(handler)
  extension/Extensions.java                  # + fenceHandler(name) lookup
  mode/markdown/MarkdownInlineImages.java    # NEW: ![alt](src) -> REPLACE (Java)
  mode/markdown/MarkdownMode.java            # + consults FenceHandler on open/edit

extensions/mermaid/                          # MERMAID (Clojure, AOT)
  deps.edn                                   # org.clojure/clojure only
  src/
    META-INF/services/
      org.armedbear.j.extension.Extension     # -> org.armedbear.j.mermaid.MermaidExtension
    org/armedbear/j/mermaid/
      extension.clj      # MermaidExtension (gen-class implements Extension)
      renderer.clj       # PURE-ish: backend selection, config, cache key
      mmdc.clj           # ProcessBuilder -> mmdc -> PNG bytes
      native.clj         # ProcessBuilder -> selkie/mmdr -> PNG
      handler.clj        # implements FenceHandler: render() -> async -> REPLACE
      painter.clj        # implements VirtualText.Painter: draws the cached PNG
      cache.clj          # hash(body+theme+scale+backend) -> BufferedImage, on disk
```

The extension owns everything Mermaid-specific. Core knows only that some
owner registered a `REPLACE` with a painter over a line range, and that a
`FenceHandler` claims the `mermaid` fence name. The inline image path lives
in core's Markdown mode because it needs no external renderer —
`ImageLoader` reads the PNG directly — and because it is the cheapest way
to prove `REPLACE` end-to-end before the Clojure extension.

**Why Clojure for Mermaid, Java for inline images.** The Mermaid extension
is the user's chosen vehicle to prototype a Clojure extension (see
[Clojure bridge](#clojure-bridge-prototyping-the-extension-in-clojure)).
Inline images are a core Markdown feature with no external dependency and no
reason to cross the bridge; they stay in Java where the rest of Markdown
mode lives. The two share the same `REPLACE` primitive from core.

### Fence handler registration

There is **no fence-handler seam today**. `MarkdownFormatter.isInFence` /
`fenceBlock` are package-private, and `fenceNames` only drives *coloring*.
An extension cannot find a fence, and nothing tells it when one appears.
The fix is a registration modeled on `Opener` (a `handles` predicate +
action, held in a list, consulted by core), but **core-driven**: the
Markdown mode finds the fences (it already parses them) and hands the
claimed ones to the handler.

```java
// new: org.armedbear.j.extension.FenceHandler
public interface FenceHandler {
    /** The fence info-string words this handler claims, e.g. "mermaid". */
    boolean handles(String fenceName);

    /**
     * A fence this handler claims is opened or changed. Called on the EDT
     * with the buffer unlocked -- read it on the EDT or copy what you
     * need. Render off the EDT and set/clear the REPLACE yourself when
     * done; return promptly.
     */
    void render(Editor editor, Buffer buffer, Line first, Line last, String body);

    /**
     * The dispatch that just called render is finished with this buffer:
     * every claimed fence has been handed over. Called once per round for
     * every registered handler, even one that got no fence, so a handler
     * that keeps a decoration per fence can drop the ones whose fence is
     * no longer claimed -- renamed to another language, say, so its line
     * survives but no render arrives for it. Default no-op.
     */
    default void renderDone(Editor editor, Buffer buffer) {}
}
```

```java
// new on ExtensionContext
void registerFenceHandler(FenceHandler handler);
```

`Extensions` keeps a `CopyOnWriteArrayList<FenceHandler>` and answers
`fenceHandler(String name)` → the first handler whose `handles` returns
true, exactly as it does for `Opener` (`Extensions.java:115`).

**Who calls it, and when.** The Markdown mode, not the extension:

- **On open/activate:** scan the buffer's fences (the formatter already
  knows them from the line flags) and call `render` for each claimed one.
- **On edit:** the mode knows which lines changed; for a change that
  touches a claimed fence's range, call `render` again (debounced).

This keeps the seam clean and the extension ignorant of fence discovery.
Core never blocks on a render — `render` returns immediately and the
handler does the work async. The extension never reads package-private
formatter methods — core hands it the body and the `[first..last]` range.

**Inline images are not a fence.** `![alt](src)` is inline, not a fenced
block, so it does **not** go through `FenceHandler`. Core's
`MarkdownInlineImages` handles it directly in the Markdown mode. The
`FenceHandler` seam is specifically for fenced blocks like ```` ```mermaid ````.

**Pipeline (async, never on the EDT):**

1. The Markdown mode finds a claimed ```` ```mermaid ```` block and calls
   `handler.render(editor, buffer, first, last, body)`.
2. `cache.clj` keys on `sha256(body + theme + scale + backend)`. Hit →
   register the `REPLACE` immediately. Miss →
3. A background thread (`Background.newThread`, as `AsynchronousShellCommand`
   does) runs the backend: write the body to a temp `.mmd`, invoke
   `mmdc`/native with `htmlLabels:false` and the theme, read the PNG back.
4. On completion, `SwingUtilities.invokeLater` puts the `BufferedImage` in
   the cache and registers/replaces the `REPLACE` for that block, which
   repaints the lines involved.
5. A re-render is debounced on edits (e.g. 300 ms after the last change to
   the block) so typing does not spawn a process per keystroke. While a
   render is in flight, the `REPLACE` shows a placeholder painter ("rendering…")
   or stays revealed, so the user is never looking at a blank gap.

**Threading & locking:** `render` is called on the EDT with the buffer
unlocked -- the same contract as the inline-image path. The handler reads
what it needs on the EDT (or copies it), returns, and renders off the
EDT. The result lands on the EDT. The cache is keyed by content hash, so
it survives buffer reloads and is shared across windows.

**Reveal wiring:** the extension does **not** decide when the picture shows.
It registers the `REPLACE` with the fence's `[first..last]` range; `Display`
applies the same caret-reveal rule it already uses for hidden blocks
(`getHiddenBlock` / `isIn`). The caret entering the fence reveals the
source; leaving re-collapses it. No per-keystroke work in the extension.

**Theme:** pass the editor's theme (light/dark, and the diagram's palette)
to the renderer so diagrams match. `mmdc` takes a JSON config
(`{"theme":"dark","htmlLabels":false}`); native renderers take a theme
flag.

### Width and height control

The painter scales the image to the available text width, capped so a small
image is not blown up. The user can override the fit with a per-image knob.
Conventions from the wild:

| Convention | Syntax | Source |
|---|---|---|
| **Obsidian pipe** | `![alt\|300](src)` / `![alt\|300x200](src)` | Obsidian (size in alt-text after `\|`) |
| **Pandoc attributes** | `![alt](src){width=50%}` | Pandoc `link_attributes` |
| **kramdown** | `![alt](src =300x200)` | kramdown |
| **HTML** | `<img width="400" src=…>` | GFM / any |
| **Mermaid init** | ```` ```mermaid\n%%{init:{…}}```` (deprecated v10.5+) | Mermaid directives |

**Height: match the fence, not the picture.** A Mermaid fence's collapsed
picture is drawn into a box **as tall as the fence's own source lines**,
not the picture's natural height. This is not a cosmetic choice — it is
what keeps the scroll from jumping. A collapsed `REPLACE` makes the anchor
line the painter's height and the absorbed lines zero; if the painter were
the picture's natural height, a tall diagram (a 1600px flowchart over a
25-line, 375px fence) would move ~1200px of content every time the caret
crossed the fence boundary. Scrolling up would drag the caret into the
range, the region would shrink to source height, and the window would jump
back down — and arrowing down into the picture would overshoot past it.
Matching the source height makes the swap **height-neutral**: collapsed
total == revealed total, so nothing moves. The image is scaled to *contain*
itself in the (width × source-height) box, aspect kept, centered. The
painter reports that height as `line-count × charHeight`, recomputed on
every call, so a font-scale change is picked up on the next layout pass
without the handler re-registering (the display asks the painter for its
height during `rowHeight` and paint, and a font change repaints). The
tradeoff is that a tall diagram is drawn small; the user reveals the source
to read it, or a future knob (below) lets them expand it.

**Width:** fit-to-window with a cap (the default). For the user-facing
knob, prefer the **Obsidian pipe** (`![alt|300](src)`) for inline images —
it is the most common and reads cleanly — and for Mermaid fences, a
**floaty button on the image** that cycles `fit → natural → capped`, since
a fence has no alt-text pipe to hang a size on. Both are later polish; the
default fit-to-window-with-cap ships first.

### Clojure bridge: prototyping the extension in Clojure

The Mermaid extension is the vehicle for prototyping a **Clojure**
extension, following the verified pattern in `GAMES-EXTENSION.md`. The
bridge facts that make it work (all verified there against `build/classes`):

- **`gen-class` for anything j names.** `MermaidExtension` is a
  `gen-class :implements org.armedbear.j.extension.Extension` so
  `ServiceLoader` finds it by stable name and `registerCommand` reflects
  its `public static` methods. The pure renderer/cache use ordinary
  Clojure data.
- **Static initializer loads the namespace.** Top-level `def`s (the cache,
  the backend registry) are bound before any `gen-class` method runs — the
  reason the glue is `gen-class`, not `deftype` (which throws
  `Var$Unbound`).
- **One `URLClassLoader` per extension dir** holds `j-mermaid.jar` **and**
  the Clojure jars staged beside it, with core's loader as parent.
  `clojure.lang.*`, core classes, and the mermaid classes all resolve; core
  never depends on Clojure.
- **AOT is required.** `bb extensions` must branch: `.clj` present →
  `clojure-aot!` (shell to a real `java` with Clojure on the classpath,
  `clojure.main -e '(doseq [n nss] (compile n))'`), because babashka's
  Sci cannot `compile`. The `META-INF/services` copy, `b/jar`, and
  `lib-jars` staging stay as they are.
- **`check-core` guard.** Add `"mermaid"` to `extension-packages` so core
  can never reference `org/armedbear/j/mermaid/` or
  `org.armedbear.j.mermaid.`.
- **`bb fmt` widening.** Include `extensions/**/*.clj` in `fmt-paths`.

The full bridge mechanics — `gen-class` constructor types (`long`/`double`
not `int`), the no-`InputHandler` constraint, read-only-by-method-override
— are documented in `GAMES-EXTENSION.md` and apply unchanged here. The
Mermaid extension does not need a custom mode or keymap (it renders into
Markdown buffers, not its own), so it is a *simpler* bridge than Sudoku:
just `Extension` + commands + the `FenceHandler` and `Painter`
implementations.

**Painter from Clojure.** `painter.clj` is a `deftype` implementing
`org.armedbear.j.VirtualText$Painter` (a plain interface, so `deftype` is
enough -- no `gen-class` needed, unlike the `Extension`/`FenceHandler`
glue that `ServiceLoader` and reflection name). Its `getHeight` returns
the fence's source height (see [Width and height
control](#width-and-height-control)), and its `paint` draws the cached
`BufferedImage` fitted and centered in that box. This is the one
non-command interface the extension implements; it is called on the EDT
during paint, so it must be cheap (draw the cached image, never render).

**Verified in Phase 2** (the `clojure-aot!` and bridge gotchas, all hit
and fixed against `build/classes`):

- `-Dclojure.compile.path=VALUE` must be **one token**; a space makes
  java read the path as the main class.
- The extension's `src` dir must be on the AOT classpath, or `compile`
  cannot find the `.clj` sources to read.
- `compile` writes `<compile.path>/<ns path>/<Class>.class` and makes the
  package dirs itself, but **not the `compile.path` root** -- create that
  first. And start from a **clean** output dir: `compile` loads an
  existing `ns__init.class` in preference to recompiling, so a stale
  class ships unchanged when a core interface the extension implements
  changes.
- `compile` takes **symbols**, not strings; `clojure-namespaces` returns
  `symbol`s.
- `:methods` spec is `^{:static true} [name [params] return]` -- the
  metadata goes on the method-spec vector, not the name symbol.
- A `gen-class` method's arity is `this` **plus** the interface's args:
  `-render` for `render(editor, buffer, first, last, body)` is
  `(-render this editor buffer first last body)`, not six-into-five.
- **Test classpath:** a Clojure extension's loose `classes` dir must stay
  **off** the test's app classpath. If it is on, the app loader claims
  `MermaidExtension` and its `<clinit>` fails -- `Could not locate
  clojure/core__init.class` -- because the Clojure jars live only in the
  extension's own `URLClassLoader`. The test reaches the handler's
  statics through `Extensions.findClass`, which asks the extension's
  loader. `bb test-extensions` branches on `clj?` to keep them off.

## Core enabling changes

Generic, Mermaid-agnostic — so `bb check-core` stays green. This is the
`REPLACE` placement itself, added to the virtual-text layer.

1. **`REPLACE` placement** (`VirtualText.Placement`). A new placement whose
   unit is a **line range** and whose content is a **painter** with a pixel
   height, not text rows.
   - The record gains a `last` line (the range end) and a `Painter`
     (`void paint(Graphics2D g, int x, int y, int width)` + `int
     getHeight()`), or reuses a `Painter` interface introduced alongside.
     `REPLACE` takes no `rows`; its height is the painter's.
   - `VirtualTextLayer` indexes `REPLACE` by first line, and answers
     `replaceFor(line)` → the `REPLACE` whose range covers `line`, if any.
     The owner sets/clears it like any other decoration.
2. **Height and the y↔line mapping.** A collapsed `REPLACE` makes the
   anchor line's height the painter's height and the absorbed lines' height
   **zero**. Touches:
   - `Display.rowHeight(line)` (`Display.java:571`) — return the painter's
     height for the anchor line, `0` for absorbed lines.
   - The `getY` / `visibleY` / `lineBesideY` walks
     (`Display.java:537,975,2023`) and the `runAt(... / charHeight ...)`
     arithmetic (`Display.java:1650-1664`) — a zero-height line is skipped
     in the y walk; the picture row is hit-tested as the anchor line.
   - `VirtualTextLayer.rowsBefore` / `totalRows` (`VirtualTextLayer.java:209`)
     — account for the painter's pixel height, not a row count.
3. **Painting.** `Display.paintLine` (`Display.java:1415`): if the line is
   a collapsed `REPLACE` anchor, call the painter instead of `drawText`,
   and skip the text of every absorbed line. If revealed, paint normally.
4. **Gutter: no line numbers over the picture.** A collapsed `REPLACE`
   draws **no line numbers** in the gutter over the picture's rows — the
   gutter is blank there, as Obsidian shows none. The line numbers of the
   absorbed lines are suppressed while collapsed; they return when the
   block is revealed. This means the gutter's per-row number walk must skip
   the picture's pixel span, not just the anchor line.
5. **Picture width: fit-to-window with a cap.** The painter scales the
   image to the window's text width, capped at a maximum (so a small image
   is not blown up). The cap and the scale mode are the painter's concern,
   not core's — core gives the painter the available width; the painter
   decides. See [Width control](#width-control) for the user-facing knobs.
6. **Reveal rule.** Reuse `getHiddenBlock`/`isIn`: a `REPLACE` is
   collapsed iff `!isIn(dotLine, [first..last])`. `showRevealed` already
   repaints the caret's block and the one it left; extend it to repaint the
   `REPLACE` range on both sides of the transition.
7. **`FenceHandler` registration** (the seam the extension needs).
   `extension/FenceHandler.java` (`handles(fenceName)` +
   `render(editor, buffer, first, last, body)`),
   `ExtensionContext.registerFenceHandler`, and `Extensions.fenceHandler(name)`
   — a `CopyOnWriteArrayList` consulted like `openers`
   (`Extensions.java:115`). The Markdown mode calls it on open/activate and
   on edits that touch a claimed fence's range, passing the body and the
   `[first..last]` range so the extension never touches the
   package-private `MarkdownFormatter` fence methods. See
   [Fence handler registration](#fence-handler-registration).

**Existing public surface the extension uses:** `VirtualTextLayer.set(owner, …)`,
`ImageLoader`, `EditorHooks`, `ExtensionContext.registerCommand`, and the
new `registerFenceHandler`. **Not** `MarkdownFormatter` fence introspection
— `isInFence`/`fenceBlock` are package-private and stay that way; the
`FenceHandler` callback is how the body and range reach the extension.

**Cost note:** this is the same variable-height machinery `VIRTUAL-TEXT.md`
defers for inline images. Building `REPLACE` once serves Mermaid **and** the
"inline images: svg" TODO, so the core change is not Mermaid-only overhead.

## Phased plan

**Status: Phases 1–3 done** (core `REPLACE` + inline images; the Clojure
bridge + `FenceHandler` seam; the Mermaid renderer with mmdr/mmdc backends,
cache, painter, and placeholder-on-failure). **Phase 4 in progress:**
`toggleMermaid` (command), click-to-reveal (the
caret-driven rule already reveals on a click into the picture), and
`fuzz-windows` REPLACE coverage are done; the width knob and in-flight
placeholder remain.

| Phase | Deliverable | Depends on |
|---|---|---|
| **1** ✅ | **Core `REPLACE` + inline images (the proof).** (a) The `Placement.REPLACE` value, the `Painter` interface, range indexing in `VirtualTextLayer`, the height/`rowHeight`/y↔line mapping, `paintLine` skip-and-paint, the caret-reveal rule (reuse `getHiddenBlock`/`isIn`/`showRevealed`), and the **gutter showing no line numbers over replaced lines**. (b) `MarkdownInlineImages.java` in core's Markdown mode: `![alt](src)` → a `REPLACE` whose painter draws the PNG via `ImageIO`. **This proves the whole design end-to-end with no external renderer and no Clojure.** | — |
| **2** ✅ | **Clojure bridge scaffold + `FenceHandler` seam.** `extensions/mermaid/deps.edn` (Clojure), `extension.clj` (`MermaidExtension` `gen-class`), the `META-INF/services` pair, and the `build.clj` changes: `clojure-aot!`, the `check-core` guard (`"mermaid"` in `extension-packages`), `fmt-paths` widening. Plus the core seam: `extension/FenceHandler.java`, `ExtensionContext.registerFenceHandler`, `Extensions.fenceHandler(name)`, and the Markdown mode calling it on open/activate and on fence-touching edits. Prove the bridge with a trivial `mermaidPing` command and a `FenceHandler` that just logs the body it receives. | 1 |
| **3** ✅ | **Mermaid renderer.** `renderer.clj` (backend select), `mmdc.clj` + `native.clj` (PNG), `handler.clj` (`FenceHandler` impl: render → async → `REPLACE`), `painter.clj` (`Painter` impl), `cache.clj`. `mermaid.renderer = auto\|mmdc\|native` + `mermaid.rendererPath` prefs, PATH probing, theme pass-through, error surfacing (bad syntax → renderer message / placeholder). Async + debounce + cache. The seam gained `FenceHandler.renderDone` (a batch boundary) so a fence renamed away from `mermaid` is pruned rather than left as a stale picture. | 2 |
| **4** | **Polish.** ✅ `toggleMermaid` command (per-buffer `enabled` flag in the handler's `State`; off clears the `REPLACE` and skips render work, on re-dispatches via `MarkdownFences.onActivate` with the cache making it cheap). ✅ click-to-reveal: the caret-driven rule already reveals a picture when the caret lands in its range, so a click on the picture (which maps to the anchor line) reveals it -- no new code. ✅ `fuzz-windows` REPLACE coverage: `WindowFuzz` now opens a Markdown fixture with inline images and a mermaid fence, toggles and moves the caret into and out of the pictures, and `checkReplace` asserts the collapsed contract (anchor rowHeight == painter height, absorbed lines == 0, no negative heights) under random window ops, reporting how many collapsed lines it checked. Remaining: the width knob (floaty button cycling fit→natural→capped), in-flight placeholder. | 3 |

Phase 1 is the core investment **and** the proof: inline images exercise
every part of `REPLACE` (height, mapping, gutter, reveal, painting) against
a real image with no external dependency. If Phase 1 works, the design is
validated. Phase 2 proves the Clojure bridge with a no-op command. Phase 3
is the real Mermaid feature on top. Phase 4 is polish.

**Fallback** (if the Phase 1 core work is ever blocked): the preview-buffer
design (`MermaidPreviewBuffer` of `ImageLine`s + `viewSource`, mirroring
`WebBuffer.viewSource`) delivers a toggle with zero core changes, but not
the in-place UX.

## Traps

- **Zero-height lines and the y↔line mapping.** A collapsed `REPLACE` gives
  absorbed lines height `0`. Every walk that turns a y into a line
  (`getY`, `visibleY`, `lineBesideY`) and every `runAt(... / charHeight
  ...)` division must treat a zero-height line as "no row here" — never
  return an absorbed line for a y that is really the picture, and never
  infinite-loop on a zero-height step. This is the single riskiest part of
  Phase 1; test it with the fake painter on the first, last, and a folded
  line, exactly as `VIRTUAL-TEXT.md` phase 2 tests virtual rows.
- **Caret never inside a picture.** The reveal rule (collapsed iff caret
  outside the range) is what keeps the caret out of the image. Do **not**
  let a click on the picture place the caret "in" a zero-height line — a
  click on a collapsed `REPLACE` should reveal it (caret to the open fence)
  rather than land on an absorbed line.
- **Gutter number walk.** With no line numbers over the picture, the
  gutter's per-row number mapping must skip the picture's pixel span, not
  just the anchor line, or numbers after the picture will be off by the
  picture's height.
- **Fold interaction.** A `REPLACE` over a folded range, or a fold inside
  one, must not double-count height. Decide: a `REPLACE` fence is not
  separately foldable (the reveal *is* its expand/collapse), or the two
  compose. Simplest: `REPLACE` fences use reveal, not fold; skip fold
  height for a line is a `REPLACE` anchor.
- **`htmlLabels`**: Mermaid's default HTML labels vanish in every non-browser
  SVG renderer. **Always render with `htmlLabels: false`.** (With PNG
  output via `mmdc` the browser rasterizer handles it, but the flag still
  matters for matching GitHub and for any native renderer.)
- **Chromium availability**: `mmdc`'s Puppeteer wants a Chromium. Document
  `PUPPETEER_EXECUTABLE_PATH` / a `puppeteerConfigFile`, or prefer the
  native backend to dodge this entirely.
- **Don't block the EDT**: a 2–3 s cold `mmdc` render on every keystroke
  would freeze the editor. Async + debounce + content-hash cache is
  mandatory, not a later optimization.
- **Process sprawl**: kill the previous render's process when a new one
  starts (reuse `AsynchronousShellCommand`'s `descendants().destroyForcibly`
  pattern). Don't leak Chromium processes.
- **`SvgIcon` is a trap**: it looks like an SVG renderer but throws on
  anything beyond icons. Do not route diagrams through it.
- **`check-core`**: any core class that names `org.armedbear.j.mermaid.*`
  fails the build. Keep the feature in the extension; core hooks stay
  generic (a `REPLACE` placement, a painter, a fence body), never "mermaid".
  Add `"mermaid"` to `extension-packages`.
- **Clojure bridge traps** (all inherited from `GAMES-EXTENSION.md`):
  `gen-class` for anything j names (not `deftype` — `Var$Unbound`);
  `gen-class` ctor types are `long`/`double` not `int`; `bb` cannot AOT so
  the build must shell to a real `java`; `bb fmt` misses `extensions/**/*.clj`
  until `fmt-paths` is widened. The Mermaid bridge is simpler than Sudoku's
  (no custom mode/keymap/read-only-buffer), but the same rules apply.
- **Painter runs on the EDT.** `painter.clj`'s `paint` (a `deftype`
  method, not a gen-class `-paint`) is called during
  paint; it must draw the cached image only and never trigger a render or
  touch the filesystem.
- **Theme/dark mode**: a diagram rendered for light mode looks wrong on a
  dark theme. Key the cache on the theme and re-render on theme change.
- **Scale/zoom**: a PNG is fixed-resolution. Render at the editor's
  current scale (or 2× and downscale) so it is not blurry. True vector
  zoom would need an SVG rasterizer, which we are not carrying now.
- **Re-render churn on scroll/resize**: the painter must not re-invoke the
  backend on every paint. It draws the cached image only; the backend runs
  on content/theme change, not on geometry.

## Open questions

1. **Which native renderer** to standardize on as the fast backend —
   `selkie` (most complete) vs `mmdr`/`mermaid-rs-renderer` (fastest)?
   **mmdr is now the dev-shell renderer** (flake input `mmdr`, on PATH in
   `nix develop`), so Phase 3 can probe and diff it against `mmdc` on a
   corpus of diagram types. The final user-facing default still turns on
   that diff.
2. **Width knob shape.** Fit-to-window-with-cap is the default. For the
   override: the Obsidian pipe (`![alt|300](src)`) for inline images, and
   a floaty button cycling `fit → natural → capped` for Mermaid fences.
   Confirm the button's affordance and whether the cap is a fixed px or a
   preference.
3. **Inline-image edge cases**: missing file, remote `http(s)` image (needs
   a download — reuse `ImageLoader`/`HttpLoadProcess`?), and whether the
   pipe-size syntax (`![alt|300]`) is parsed by core or left for later.
