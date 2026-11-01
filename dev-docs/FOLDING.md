# Folding

How j folds, and what other editors offer that j might.

## j today

- **One model.** A mode's `Folds` says where folds start (`isFoldStart`)
  and what each hides (`getFoldBelow`). The gutter's chevrons and extent
  line, a click on a chevron, the middle click, `fold` (vim `zc`),
  `unfoldHere` (`zo`), `toggleFold` (`za`), `foldMethods` and going to a
  tag all use it. `FoldCommands.foldAround` finds the innermost open fold
  around a line. `fold` passes over a closed fold the line heads to the one
  around it, as vim's `zc` does.
- **By mode:**
  - Java, C, JavaScript and PHP (`mode/java/BraceFolds`) fold by structure:
    - a `{…}` block, from the line its statement starts on;
    - a comment of several lines;
    - a run of line comments at the top;
    - a run of imports or `#include`s.
  - Python folds what is indented under a line, the comments at the top,
    and the imports.
  - Markdown folds headings' sections, list items and fences.
  - Other modes fold the lines indented under a line.
  - A comparison, the log and the status have folds of their own.
- **Explicit markers.** `{{{` and `}}}` on lines, and a selection of whole
  lines (`foldRegion`), fold without the mode.
- **The count.** A closed fold shows `… 12 lines` (`foldSymbol`) after the
  line over it; a click opens it.
- **Folded on opening** (`FoldCommands.foldOnOpen`, from
  `Editor.bufferActivated` the first time). Each fold is `Folds.initialFold`,
  by its preference; one the caret opens in stays open.
  - `foldHeaderComment` (on): the comment at the top, as a license.
  - `foldImports` (on): the imports.
  - `foldClassDoc` (off): the class's documentation comment after the
    imports; Java-like modes only.

## Other editors

### IntelliJ IDEA

Settings | Editor | General | Code Folding.
- **Arrows:** always, or on hover; a "Show bottom arrows" option.
- **Fold by default, general:**
  - file header;
  - imports;
  - documentation comments;
  - method bodies;
  - custom folding regions (`//region` … `//endregion`, `<editor-fold>`).
- **Per language:** Java, JavaScript, XML, YAML, SQL, Scala, Android,
  properties. The Java list, from memory:
  - one-line methods;
  - closures (lambdas and anonymous classes);
  - generic parameters;
  - simple property accessors;
  - inner classes;
  - annotations;
  - i18n strings.
- **Fold display:** a folded lambda or anonymous class reads as
  `() -> { … }`, and a folded accessor as its expression.
- **Commands:**
  - expand and collapse, recursively too;
  - expand all and collapse all;
  - expand to level N;
  - collapse doc comments;
  - fold a selection.

### Eclipse (Java editor)

Java > Editor > Folding.
- **Enable folding:** on by default.
- **Initially fold these elements:**
  - comments (off);
  - header comments (on);
  - inner types (off);
  - members (off);
  - imports (on).
- **Extended folding (experimental):** off; it folds loops and `if`s too.
- **Custom folding regions:** off, with start and end marker text (`region`,
  `endregion`).

### VS Code

- **Strategy:** `editor.foldingStrategy` is `auto`, by the language's folding
  provider or server, or `indentation`. Languages without a provider fold by
  indentation.
- **Settings**, from memory, to be checked against the settings reference:
  - `editor.folding`;
  - `editor.showFoldingControls` (`always`, `mouseover`, `never`);
  - `editor.foldingHighlight`;
  - `editor.foldingImportsByDefault` (off; TypeScript and JavaScript);
  - `editor.foldingMaximumRegions`;
  - `editor.unfoldOnClickAfterEndOfLine`.
- **Commands:**
  - fold and unfold, recursively too;
  - toggle fold;
  - fold all and unfold all;
  - fold level N;
  - fold all block comments;
  - fold and unfold marker regions;
  - a manual folding range from the selection.
- **Mouse:** Shift-click on a control folds recursively.
- **Region markers by language:**
  - Java: `//#region`, `//<editor-fold>`;
  - JavaScript and TypeScript: `//#region`;
  - Python: `#region`;
  - C and C++: `#pragma region`;
  - CSS: `/*#region*/`;
  - Markdown: `<!-- #region -->`.

### Vim and Neovim

- **`foldmethod`:** `manual`, `indent`, `expr` (tree-sitter's in Neovim),
  `marker` (`{{{`), `syntax` or `diff`.
- **Other options:**
  - `foldlevel` and `foldlevelstart`: how deep to start closed;
  - `foldnestmax`;
  - `foldminlines`;
  - `foldopen` and `foldclose`: which motions open or close folds;
  - `foldtext`: the closed fold's line;
  - `foldcolumn`.
- **Commands:**
  - `zc` and `zo`, and their recursive forms `zC` and `zO`;
  - `za`;
  - `zM` and `zR`: all;
  - `zm` and `zr`: a level;
  - `zv`: open to show the cursor;
  - `zj` and `zk`: to the next fold;
  - `[z` and `]z`: the fold's start and end.
- **nvim-ufo** closes folds by their LSP kind on opening (`comment`,
  `imports`, `region`).

### The LSP

- **`textDocument/foldingRange`** returns ranges with an optional kind:
  `comment`, `imports` or `region`.
- **Mapping to j:** a server's ranges would map onto `Folds`, and its kinds
  onto `foldImports` and the comment folds. See the deferred tagging
  options, which include a language server.

## Not yet in j

- Method bodies, inner classes, lambdas and anonymous classes folded on
  opening.
- A closed fold shown as its code, as `() -> { … }`, rather than
  `… N lines`.
- Region markers in the editors' syntax (`//region`, `#region`, `<editor-fold>`);
  j has `{{{` `}}}`.
- Folding by level (`zm`/`zr`, "fold level N"), recursive fold and unfold
  (`zC`/`zO`), folding every doc comment, and moving between folds
  (`zj`/`zk`).
- A limit on fold length (`foldminlines`), and opening folds as motions
  enter them (`foldopen`).
- Per-language brace folds for more modes (Perl, Rust, Go, and Kotlin
  when it has a mode); a Python class's docstring as `foldClassDoc`.

## Known gaps

- `BraceFolds` finds a block's head by the line before it ending a
  statement (`;`, `{`, `}`, `:`, an annotation, a comment or a blank
  line). An enum constant with a body after other constants is headed by
  the first constant, and an annotation wrapped over lines heads the
  method after it.
- A closed fold's count keeps the old `foldSymbol` until the folds change.
