# TODO

## Cleanup
- [x] bring baseline up to Java 25
- [x] render on high-dpi displays
    - text is small and pixelated by default
- [/] modernize look and feel
    - [x] new icons, svg
    - old legcy IBM jdk stuff -- look at commit 6aac4ffe9b2d477170d9b85a5da6dfa975bfde66
- [x] remove legacy util classes
- [ ] @Override/@Nullable other annotations
- [ ] java.io.File -> NIO? j has it's own File abstraction for local, FTP, HTTP, SSH paths
- [ ] introduce java code style into build? lint?
- [x] remove dependency on abcl
    - core builds with `:deps {}`; `bb check-core` asserts it stays that way
    - `bb dist` still bundles the extension, so an ordinary install has Lisp


## Config
- [x] XDG base directory layout (config/data/state/cache/runtime), see doc/filelocations.html
    - legacy `~/.j` still wins when present; `j --migrate-to-xdg` moves it
- [ ] drop the `~/.j` fallback once users have had time to migrate
- [ ] sessions
    - cli option to list avilable sessions
    - cli option and command to delete a session


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
- [ ] update docs to remove old stuff
    - installation docs mention Java 1.6... barf
    - remove 'JIMI' notes from imagebuffers topic


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
- [ ] get rid of top-menu and toolbar - very 90s feel
    - logo icon in top-left, inline menu, a few icons, ... hamburger menu
- [ ] location text area is weird, let's do something different
- [ ] mini-top-of-editor find dialog similar to vscode/IntelliJ
    - dialogs slide/animate in from top of the buffer area and are not separate windows
- [x] '/' search highlights next search hit with cursor and all other hits in doc
    - hlsearch and incsearch
- [ ] scrollbar with info/warn/error/bookmarks/search-hits ... highlighted ala intellij
    - https://github.com/kensyo/nvim-scrlbkun
    - https://github.com/petertriho/nvim-scrollbar
    - https://github.com/dstein64/nvim-scrollview
    - https://github.com/mihovilrak/scroll.nvim
- [ ] smooth scrolling
- [ ] rainbow parens
- [ ] draw vertical indentation line
- [ ] typing in sidebar highlights matches
- [ ] typing in buffst list hightlights matches


## Input
- [x] vim input mode
    - emacs evil, intellij ideavim
- [ ] directory buffers map their own keys, but should support basic vim movements and commands when in vim mode
- [ ] when entering an ex-command, `<C-D>` should list options (possibly with short summary description)
- [ ] ctrl-p like command entry
- [ ] ctrl-e to open list of buffers to switch around like intellij?
- [ ] sexp editing, parinfer


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
        - when on a directory buffer, what would the structure tree show? maybe just hide it.
    - allow basic file operations: move, rename, delete, copy, open folder in desktop file tool


## Syntaxes/Modes
- [ ] java syntax woefully out of date
- [ ] markdown
    - checklists
    - sidebar tree
    - inline images: svg
- [ ] nix
- [ ] nested syntaxes
    - markdown in javadoc comments
    - markdown blocks with syntax specified
    - highlight color strings in the color
- [ ] svg, render inline preview?
- [ ] inline mermaid diagram editor? el-easydraw like
    

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
- [ ] remove cvs, darcs, p4 (or at least to extensions?)
- [ ] better git support
    - show modified files, staged files
    - show edited lines and staged hunks
    - show log with branches/merges
    - lots of stuff
- [ ] diff view
- [ ] changes tab on file-tree view

