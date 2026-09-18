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
- [ ] introduce java code style? lint?
- [x] remove dependency on abcl
    - extension SPI in `org.armedbear.j.extension`; abcl is `extensions/abcl`
    - core builds with `:deps {}`; `bb check-core` asserts it stays that way
    - `bb dist` still bundles the extension, so an ordinary install has Lisp
- [ ] relicense?
    - from GPL to what?
    - only things I've written?
    - other stuff would need to be rewritten... maybe in clojure? wink wink

## Config
- [x] XDG base directory layout (config/data/state/cache/runtime), see doc/filelocations.html
    - legacy `~/.j` still wins when present; `j --migrate-to-xdg` moves it
- [ ] drop the `~/.j` fallback once users have had time to migrate

## Build
- [x] use bb and new tools.build (bb.edn, deps.edn, build.clj)
- [/] drop build.xml / build.properties / configure* / Makefile.in once the bb build has proven itself
- [-] port the `install` targets (deliberately left out of build.clj)

## UI
- [x] support ligatures with setting: --> <=> www ----
    - `ligatures` preference: auto (default), true, false
    - auto probes the font for contextual substitutions; Monospaced pays nothing
    - breaks apart under the caret; buffer text only
- [ ] separate core from swing components
    - headless editor engine
        - communicate via protocol or shared memory
    - tui and gui
- [ ] FlatLAF
    - https://www.formdev.com/flatlaf/
    - https://github.com/JFormDesigner/FlatLaf
- [ ] get rid of top-menu and toolbar - very 90s feel
    - logo icon in top-left, inline menu, a few icons, ... hamburger menu
- [ ] location text area is weird, let's do something different
- [ ] mini-top-of-editor find dialog similar to vscode/IntelliJ
    - dialogs animate in from top of the buffer area and are not separate windows
- [ ] scrollbar with info/warn/error/bookmarks/... highlighted ala intellij
- [ ] smooth scrolling

## Input
- [ ] vim input mode
    - emacs evil, intellij ideavim
- [ ] ctrl-p like command entry

## Editor/Syntax
- [ ] add treesitter support, fallback to regex based syntax highlighting
    - update regex based syntax for modern versions of java
        - bug in java tokenizer: Editor.java, line 175 is tagged as a `View` method:
        - `Hashtable<SystemBuffer, View> views = new Hashtable<SystemBuffer, View>();`
- [ ] syntax based folding
- [ ] autocomplete/tab-complete
- [ ] more syntaxes
    - [ ] markdown
        - with checklists
    - [ ] nix


## Version Control
- [ ] remove cvs
- [ ] git support
    - show modified files, staged files
    - show log
    - lots of stuff

## Experimental
- [ ] replace terminal with whatever intellij is using
- [ ] clojure nrepl support
- [ ] compile with graal and distribute binaries
