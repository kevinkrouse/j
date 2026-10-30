# Fuzzy finder: plan

Ctrl P and Ctrl O find a file, Ctrl Shift P finds a command, both by fuzzy
matching in the location bar. The file finder also lists a typed path's
directory and opens typed paths and URLs.
User docs: `doc/navigation.html`.

## What others do

**VS Code.** Ctrl P (Quick Open) matches files by fuzzy path; an empty query
lists recently opened files; `file:42` goes to a line; `>` switches to the
Command Palette (Ctrl Shift P), which shows each command's label, category and
key binding, recently used first.

**IntelliJ.** Shift Shift (Search Everywhere) has tabs for all, classes, files
and actions; Ctrl Shift N finds files and Ctrl Shift A finds actions, showing
the shortcut and description and matching synonyms. CamelHump matching:
`FoTFH` finds `FooTextFieldHandler`.

**Neovim, telescope with fzf-native.** A prompt, results and a preview. fzf's
scoring rewards matches at word boundaries, camel humps, after `/`, and in
consecutive runs, and penalises gaps; space-separated terms are ANDed;
`'exact`, `^prefix`, `suffix$`, `!not`. Pickers for `find_files` (from the
working directory), `git_files`, `commands`, `keymaps`, `help_tags`. Ctrl N and
Ctrl P move; Ctrl X and Ctrl V open in a split.

**Emacs.** vertico, ivy or helm completion with orderless (space-separated
terms in any order); `M-x` with marginalia (key binding and first docstring
line). projectile and project.el find files from the project root: the
nearest root marker, or a `.projectile` file.

**Helix.** `Space f` file picker from the workspace root, gitignore-aware (the
`ignore` crate), with the nucleo fzf-style matcher; `Space ?` command palette
with bindings and docs.

**Zed, Sublime.** Ctrl P files, Ctrl Shift P commands with descriptions;
Sublime adds `@symbol`, `:line` and `#term`.

Taken here: fzf-style scoring with the matched characters marked, recent items
first on an empty query, Ctrl N/Ctrl P/Up/Down to move, Enter to open and a
key for the other window, `:line`, and key binding plus summary on commands.

## Design

**Matching** (`util/FuzzyMatcher`). fzf's constants and scoring, by dynamic
programming over each term (greedy past 1024 characters or 64K cells), plus a
bonus per character in a path's last component so names beat directories.
Smart case per term, by its part after the last path separator. `rank()` keeps
a bounded heap and, for lists of 20,000 or more, ranks chunks in parallel:
about 15 ms for 100,000 paths. An item can add a boost (open buffers, recent
files).

**Project root** (`ProjectRoot`). The `projectRoot` preference (absolute);
the outermost `.j-project` directory, which will also hold project settings;
the nearest VCS root; the buffer's directory, if under home. Searched up to the home
directory, which is never a project.

**Project files** (`ProjectFiles`). A per-root, in-memory list of
root-relative paths, from an NIO walk on one daemon thread. It honours
`.gitignore` (a subset: names, globs, `dir/`, leading `/`, `**/`, `!`; now `vcs.Ignores`, each backend's),
`.git/info/exclude` and `~/.config/git/ignore`, skips a default list of build
and tool directories and `filenameCompletionsExcludePattern`, leaves out
nested linked worktrees (`.git` file naming `.git/worktrees/NAME`) but keeps
submodules, and stops at `finderMaxFiles`. Stale-then-refresh: the list is
shown at once and rescanned when older than `finderRescanSeconds` or
invalidated (dir-mode delete, copy, move); a first scan publishes partial
lists, later ones keep the old list until done. Saves add new files, filtered
as the walk would. No filesystem watcher. The walk matches `git ls-files
--cached --others --exclude-standard` on this repository.

**UI.** The location bar's text field keeps the focus; the list is a
`CompletionPopup` in the window's popup layer, so one `keyPressed` handles
every key. `FinderTextFieldHandler` refilters on document changes (25 ms
debounce), ranks on a background thread with a generation counter, keeps the
selection across background refreshes, and re-ranks synchronously if Enter
comes before the list has caught up. `TextFieldHandler.detached()` tells a
handler the location bar has replaced it. `FinderCellRenderer` draws one line:
icon, label with matched characters in bold, dimmed detail and note (cut short
first), key binding at the right.

**Files** (`FindFileTextFieldHandler`: Ctrl P, Ctrl O, and the location
bar's default handler). An empty field lists the open buffers and recent
files under the root. A name matches those and the project's files, the
ones under the buffer's directory boosted; with them, the entries of the
directory a relative head names that the project's list leaves out (outside
a project, or what it skips, as `build/`), each matched as its whole path,
head and name, so they rank beside the project's. A path starting with
`./`, `../` or `~`, or an absolute one, lists that directory alone, itself
first; Tab fills in an entry and Enter goes into a directory. No match, a
URL, an alias, an `-e` encoding, or Shift Enter opens what was typed with
`OpenFileTextFieldHandler.enter()`: absolute paths, the source path, new
files. In a remote buffer, Ctrl O gives the location bar's old completion.
Ctrl Shift O's dialog keeps the old prefix completion, which also falls
back to fuzzy matches of the directory's entries. Status bar: root name and
count.

**Actions** (`ActionTextFieldHandler`, Ctrl Shift P). Every command, matched
on its humanised name, its name and its summary. Summaries are the first
sentence of each entry in `doc/commands.html`, in
`command-summaries.properties`, regenerated by `bb command-summaries`. Recent
commands come from the Command: prompt's history. A command that needs an
argument goes to the Command: prompt with its name filled in. Toolbar icons
for the commands that have them, and related ones. In vim mode a command's
vim keys (`VimKeyMap.keysFor`, the first normal mode row still in effect)
come before j's.

**Icons.** `FileIcons` picks an icon by file name; the buffer list, the
finders share it.

**Keys.** Ctrl P was `print` and, in XML mode, `xmlParseBuffer`; Ctrl Shift P
was `xmlValidateBuffer`. Those are unbound now. Shell, Lisp shell and checkin
modes keep Ctrl P for their history.

## Later

- Symbols in the project (`@` in VS Code and Sublime), from tag files.
- Preview of the selected file.
- `.j-project` settings, which `ProjectRoot` already finds.
- Fuzzy search of the source path from the file finder.
