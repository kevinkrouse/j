# TODO

## Cleanup
- [x] bring baseline up to Java 25
- [x] render on high-dpi displays
    - text is small and pixelated by default
- [/] modernize look and feel
    - [x] new icons, svg
    - [/] remove any old legacy IBM jdk stuff -- look at commit 6aac4ffe9b2d477170d9b85a5da6dfa975bfde66
- [x] remove legacy util classes
- [/] @Override/@Nullable other annotations
- [ ] java.io.File -> NIO? j has it's own File abstraction for local, FTP, HTTP, SSH paths
- [x] introduce java code style into build? lint?
- [x] remove dependency on abcl
    - core builds with `:deps {}`; `bb check-core` asserts it stays that way
    - `bb dist` still bundles the extension, so an ordinary install has Lisp
- [x] write j's state files with `AtomicFiles.write`, not in place: autosave catalog, popup defaults, aliases, recent files, file history, registers, key map, cache and tag file catalogs; Session and SessionProperties' delete-then-rename
- [ ] move each frame's sidebar state (`frame.N.sidebar.*`: show, width, divider, scale, and the panels shown, `top`/`tree`) and window placement from `props` (SessionProperties) to `session.xml`, so a session keeps its layout with its buffers


## Config
- [x] XDG base directory layout (config/data/state/cache/runtime), see doc/filelocations.html
    - legacy `~/.j` still wins when present; `j --migrate-to-xdg` moves it
- [ ] drop the `~/.j` fallback once users have had time to migrate
- [ ] sessions
    - cli option to list avilable sessions
    - cli option and command to delete a session
    - switch between sessions in ui
    - open new frame with previous/new session
- [/] project-level settings
    - [x] find project root based on vcs dir (.git, .svn)
        - `projectRoot`, then outermost `.j-project` dir, then VCS root; see doc/navigation.html
    - [x] project with sub-repos: a `.j-project` dir above them
    - [ ] session & settings stored in `.j-project`
    - [x] find in files supports (defaults?) to searching from project root
    - [x] ctrl-p/fuzzy-find matches files from project root


## Build
- [x] use bb and new tools.build (bb.edn, deps.edn, build.clj)
- [/] drop build.xml / build.properties / configure* / Makefile.in once the bb build has proven itself
- [-] port the `install` targets (deliberately left out of build.clj)
- [ ] compile with graal and distribute binaries?


## Help
- [ ] consider embedding help docs as resources so `help` always works
- [ ] help mode, distinct from web mode?
    - creates sidebar tag list
- [ ] write docs in markdown?
- [x] update docs to remove old stuff
    - installation docs mention Java 1.6... barf
    - remove 'JIMI' notes from imagebuffers topic
- [ ] commands / preferences
    - write brief command summaries
    - generate help html file from command metadata/resources instead of generating command summaries
    - attach icon to `Command` instead of the current `ActionFieldTextHandler.ICONS` map


## UI
- [x] support ligatures with setting: --> <=> www ----
    - `ligatures` preference: auto (default), true, false
    - auto probes the font for contextual substitutions; Monospaced pays nothing
    - breaks apart under the caret; buffer text only
- [ ] separate core from swing components
    - headless editor engine
        - communicate via protocol or shared memory
    - tui and gui
    - terminal ides
        https://github.com/eugenioenko/ttt
        https://github.com/evanlin96069/nino
        https://github.com/sinelaw/fresh
    - tui libraries
        java - https://github.com/tamboui/tamboui
        java - https://github.com/anomalyco/opentui
        java - https://github.com/WilliamAGH/tui4j
        Go - https://github.com/charmbracelet/bubbletea
        .net - https://xenoatom.github.io/terminal/
        python - https://textual.textualize.io/ 
- [ ] get rid of lookAndFeel setting
- [ ] FlatLAF
    - https://www.formdev.com/flatlaf/
    - https://github.com/JFormDesigner/FlatLaf
- [/] get rid of top-menu and toolbar - very 90s feel
    - logo icon in top-left, inline menu, a few icons, ... hamburger menu
- [-] location text area is weird, let's do something different
- [ ] mini-top-of-editor find dialog similar to vscode/IntelliJ
    - dialogs slide/animate in from top of the buffer area and are not separate windows
- [x] '/' search highlights next search hit with cursor and all other hits in doc
    - hlsearch and incsearch
- [/] scrollbar with info/warn/error/bookmarks/search-hits ... highlighted ala intellij
    - [x] dev-docs/SCROLLBAR-MARKS.md: bookmarks, changes, conflicts, search done
    - [ ] diagnostics (info/warn/error) wait for LSP
- [x] smooth scrolling
- [x] rainbow parens
- [/] special highlighting for control characters and escape sequence in strings
    - dev-docs/ESCAPE-SEQUENCES.md
- [ ] injected languages
      [IntelliJ Injected Languages](https://www.jetbrains.com/help/idea/using-language-injections.html#add-language-injections)
      e.g. special characters in regex patterns
      e.g. SQL in a java string that is executed
      e.g. XPath1, XPath2
      css in xml, html
- [ ] draw vertical indentation line
- [ ] typing in sidebar highlights matches
- [ ] typing in buffst list hightlights matches
- [ ] visual tabs
    - change setting to `visualWhitespace` similar to nvim `listchars` setting
    - with a comma separated list of values: `tabs,space,eol,trail`

- [ ] bug: after maximizing window, toolbar disappeared? not sure what happened


## Input
- [x] i don't like the file open, ctrl-p, and ctrl-shift-p distinction
    - the ctrl-o file open is not easily accessible from vim
    - ctrl-p (find files in project) kinda replicates ctrl-o but not quite.. maybe just use ctrl-o popup instead of ctrl-p?
- [x] vim input mode
    - emacs evil, intellij ideavim
- [/] other vim
    - [x] `z<cr>`, `zz`, `z.`, and friends
    - [x] `gd` to navigate to definition
    - [ ] j-built in commands that operate on a region should support vim ranges (e.g., `:%detabRegion` or `:'<,'>detabRegion`)
    - [ ] `CTRL-]` go to tag (or navigate in help views, hmm maybe help should have tags?)
    - [ ] when entering an ex-command, `<C-D>` should list options (possibly with short summary description)
- [x] help and directory buffers map their own keys, but should support basic vim movements and commands when in vim mode
- [x] fuzzy find
    - [x] ctrl-p like command entry: ctrl-shift-p, `findAction`
    - [x] ctrl-e to open list of buffers to switch around like intellij?
    - [x] bug: typing `:w<cr>` quickly shouldn't popup the list of completions after typing `w`
- [ ] sexp editing, parinfer
- [x] transient/help buffers are weird
    - escape closes them -> good
    - can't always jump back into them once navigated away (not sure what the rules are) -> bad
    - if i'm using vertical splits, help opens in one of the splits which can be annoying - maybe always open a new split
    - see what prior art exists
        - nvim help, nvim quickfix window and location list windows (more than one allowed)
        - nvim neogit popup, [msgarea](https://github.com/edisj/msgarea.nvim)
        - emacs magit transient window popup; see also [transient](https://www.gnu.org/software/emacs/manual/html_mono/transient.html) and [transient-showcase](https://github.com/positron-solutions/transient-showcase)
- [x] jumping back to a help topic should remember it was in web mode:
    repro:
    1. open help commands.html
    2. click on a command link
    3. type `<C-o>` to jump back
      - bug 1: jumps back to wherever you were before you opened help... i'd like to go back to the help
    4. type `<C-i>` to jump forward
      - bug 2: opens the help file in html mode instead of web mode


## Editor/Syntax
- [ ] add treesitter support, fallback to regex based syntax highlighting
    - update regex based syntax for modern versions of java
        - bug in java tokenizer: Editor.java, line 175 is tagged as a `View` method:
        - `Hashtable<SystemBuffer, View> views = new Hashtable<SystemBuffer, View>();`
- [ ] syntax based folding
- [ ] autocomplete/tab-complete
- [ ] triggers
    - on save: format, run tests, ...
- [ ] single project-wide file-tree view ala NeoTree/NerdTree/Treemacs
    - we have a file tree but only when looking at a directory buffer
        - when on a directory buffer, what would the structure tree show? maybe just hide it. or maybe show bookmarked locations/files (home, project, ~/docs)
    - allow basic file operations: move, rename, delete, copy, open folder in desktop file tool
- [ ] save files when buffer is switched; nvim's `autowrite` setting
- [ ] send every fold change through `Buffer.getFolds()`
    - foldRegion, foldMethods, foldNearLine, the click-to-unfold in Dispatcher and `Editor.updateDisplay`'s reveal set hidden flags directly
    - then a comparison needn't read its folds back after each renumber (`renumbered`) or patch carets and top lines off hidden lines in several places
- [ ] per-buffer fold ranges, as VS Code, Neovim and IntelliJ keep them
    - every fold's first and last lines in one sorted list with parent links, built in one pass (a stack of indents) when the modCount changes
    - the fold around a line: a binary search for the last fold starting at or before it, then parents until one contains it
    - replaces `Display.foldAround`'s walk up the buffer: 6 ms worst after an edit on a class-level line of Display.java's 3,700, every method above it measured
    - needs a `Folds` method giving all ranges, indentation by default, Markdown its own; decide with the one `getFoldBelow`'s javadoc suggests, saying whether a mode's folds are ranges of lines (a status's and a log's aren't)
- [/] line decorations
    - [x] underline
    - [ ] others: overline, double-underline, dotted-underline, dashed-underline, wavy-underline
- [ ] flag `<` in attribute values as a well-formedness error
    - XML: `<` is forbidden in an attribute value (production [10]: `[^<&"]`), must be `&lt;`.
    - HTML: `<` is allowed in a quoted value but a parse error in an unquoted one
        (`unexpected-character-in-unquoted-attribute-value`).
    - Deferred: this is a well-formedness diagnostic, not reference highlighting. The
        current feature leaves `<` as value content (neutral), per the "neutral over
        confidently wrong" rule. Needs a separate well-formedness-checking pass.


## Search
- [ ] navigate search results when non-search buffer has focus
    - similar to `:cnext` and `:cprev` -- does j already do this?
- [ ] update search options to redo the search:
    - maybe click on the pattern, option, file-set to start the search again
- [ ] ability to mark or ignore (exclude or delete?) search results
    - then do... *something* with marked results?
    - `:cnext` and `:cprev` would ignore the deleted results


## Syntaxes/Modes
- [/] java syntax woefully out of date
    - [ ] update j's javadoc comments to use links
    - [x] annotations (`@Override`) should be colored
    - [x] auto-fold copyright block and import blocks 
    - (markdown in javadoc comments tracked under nested syntaxes)
- [/] markdown
    - [x] codeblocks italic
    - [x] headings bright blue
    - [x] checklists - regular font
    - [x] sidebar tree
    - [/] inline images: png (opt-in via `renderInlineImages`; PNG only, no SVG yet)
    - [ ] inline mermaid diagrams
    - [x] indent
        - [x] added `tab` to indent to the right location
        - [x] added `<C-tab>` to insert a tab
        - [x] added `<cr>` to add line and indent
    - [x] auto-insert list entries when hitting `<cr>` at the end of a list item?
    - [x] `---` horizontal rule draws thin line all the way across
    - [x] h1, h2 headers draw light underline all the way across
    - [x] strike-through format
- [ ] directory
    - [ ] show inline image thumbnails
- [ ] xml
    - [x] fix attribute indentation
    - [x] fix Ctrl-E 'xmlInsertMatchingEndTag' mapping shadowing Ctrl-E switch buffer popup
- [ ] new modes
    - [ ] Nix
    - [ ] Yaml
    - [ ] Typescript
    - [ ] C#
    - [ ] Rust
    - [ ] SQL
    - [ ] Dart
    - [ ] R
- [/] nested syntaxes
    - [ ] markdown in javadoc comments
    - [x] markdown blocks with syntax specified
    - [ ] highlight color strings in the color
- [ ] svg, render inline preview?
- [ ] inline mermaid diagram editor? el-easydraw like
- [ ] man sidebar/tagger seems broken
- [ ] mail
    - [ ] showing message: when i have vertical splits (file & mail), it feels a little odd to show the mail message all the way across the bottom of the screen
        - maybe introduce window panel concept that attaches to just a single window (on bottom or side)?
    - [ ] quoted text:
        - Coloring: MessageFormatter, used both for reading mail and writing replies, colors any line starting with > (or a Supercite-style name> prefix) whole in a single "quote" color. That color falls back to the theme's color.string.
        - No depth: nesting levels aren't distinguished, and the marker isn't separated from the text. >>>>>  attribution lines and mbox-escaped >From  lines are left as plain text.
        - Replying: MessageBuffer.quoteBody re-wraps the original at wrapCol − 2 and prefixes >  (a bare > on blank lines).
        So the bars could replace mail's flat quote coloring: one bar per >, ideally a color per depth as Thunderbird does. The bar is now a core feature, so it's mostly a MessageFormatter change.
    

## Extensions
- [ ] rss reader
- [ ] calender
- [ ] fun: snake, tetris, solitare, ...
- [ ] matrix/slack client - ement.el


## REPL/Shell
- [ ] terminal
- [ ] replace terminal with whatever intellij is using?
- [ ] nrepl
- [ ] jshell for java


## Version Control
- [x] remove cvs, darcs, p4 (or at least to extensions?)
- [/] better git support
    - [x] show modified files, staged files
    - [x] show edited lines and staged hunks
    - [x] show log with branches/merges
    - [x] show modified lines in gutter ala nvim gitsigns
    - [x] stage hunks right from editor ala nvim gitsigns
    - [x] emacs magit, nvim neogit, intellij log and diff viewer
    - [ ] delete and rename popups
    - [ ] push/pull/fetch popups
    - [ ] three-pane merge
    - [ ] comparing two commits or branches
    - [ ] rebase (interactive, as a buffer to edit)
    - [ ] fixup & autosquash
    - [ ] cherry-pick
    - [ ] revert
    - [ ] worktrees
    - [ ] branch create
    - [ ] forge PRs
- [x] diff view
- [ ] ability to have `git difftool` run j
- [x] changes tab on file-tree view


## Issues
- [ ] menus on osx are broken - selecting 'Bookmarks...' or 'Help' doesn't seem to do anything
- [ ] help popup broken... maybe just the help files are not in the right place?
