# Interactive git: plan

A place to review changes before committing them and to read the changes
already made: a status buffer for the working tree, a log with a drawn graph,
diffs that open the file at the change, blame and change marks in the editor's
gutter, and IntelliJ-style changelists. It is for mouse and keyboard, in
simple and vim edit modes. Each tool links to its help, and a command log
shows the git commands that ran.

Out of scope for now: rebase, fixup and autosquash, cherry-pick, worktrees,
push and pull beyond what the status buffer shows, and submodule management.

Git comes first, on a VCS layer that Subversion can sit on too (below).
Hints, inline blame and conflict actions are virtual text,
which `dev-docs/VIRTUAL-TEXT.md` plans. The `?` and prefix-key popups are
`dev-docs/TRANSIENT.md`, shared with dir mode and mail. Both come before
the tools that use them.

## What others do

**Magit (Emacs).** One status buffer in collapsible sections: the head,
upstream and push-remote lines, then untracked, unstaged and staged changes,
unmerged files, stashes, unpulled and unpushed commits, and recent commits.
Tab folds a section, a file or a hunk, and 1 to 4 set the depth for every
section. The key acts on the thing at the caret: `s` stages, `u` unstages and
`k` discards a file, a hunk, or the lines of a hunk inside the region. `RET`
visits the file at the line. `c` opens a transient popup (`c` commit, `a`
amend, `e` extend, `w` reword). The popups list their keys and infix flags
(`-a`, `--author=`), and `C-h` on a key shows its doc. `l` opens the log
popup and `l l` the log with an ASCII graph, where `RET` shows a commit and
`SPC` scrolls it in another window. `$` shows the process buffer, the git
command lines with their output, folded. `z` is stash, with `z z` to save and
`z a`, `z p` and `z k` to apply, pop and drop. Hunks are staged by building a
patch and sending it to `git apply --cached`. Refreshing runs some 15 git
commands, and huge repositories turn sections off. `magit-blame` puts
headings in the buffer or in margins. `smerge-mode` handles conflict markers
with `C-c ^ u` (upper), `l` (lower), `a` (all) and `n`/`p`. Many users run
`diff-hl` beside it for change marks in the fringe.

**Neogit (Neovim).** A Magit port with the same status sections and much the
same keys (`s`, `u`, `x` to discard, `c c`, `Tab`, `za` in vim style).
Popups are transient-style, with `?` showing them all. It uses diffview.nvim
for side-by-side diffs and conflict resolution. Its log graph is drawn
in Unicode box-drawing characters or kitty's branch glyphs, colored per
branch. `$` shows the command history with each command's exit code and
time.

**lazygit.** A TUI with panels for status, files, branches, commits and
stash, and the main view showing the diff of whatever is selected.
Everything is single keys: `space` stages, `a` stages all and `d` discards,
and `enter` on a file opens its hunks, where `space` stages a line or a
`v`-selected range. `c` commits, `A` amends and `s` squashes, and `?` lists
the keys for the focused panel. The command log is a pane on the side,
toggled with `@` and filterable. It shows the
graph per commit in Unicode with colors. A conflicted file shows its
conflicts one at a time, with `space` to pick a side and `b` to pick both. It
can filter by path and diff two arbitrary commits by marking one first.

**IntelliJ.** The Commit tool window lists changes by changelist: named
groups of files, or of hunks within a file. A file's hunks can be in
different lists, and you move them by dragging or with Alt Shift M. There is
one default list, and you commit one list at a time, with checkboxes per file
and per hunk. Its "staging area" mode swaps changelists for git's index, and
you cannot have both. The Git tool window has a branches tree, the log
(commit, author and date columns, with the graph drawn in anti-aliased
colored lanes and curves at merges) and the changes and details of the
selected commit. Lanes can be collapsed and expanded. It filters by branch,
user, date, path and text. With several roots, each repository gets a color
strip on its commits, and a filter picks roots. The log loads lazily and
indexes in the background. Annotate (blame) shows the date and author in the
gutter. Newer commits are drawn brighter, and you can hover for the message
or click to show the commit. Change markers beside the line numbers can be
clicked to show the old text, roll back, or stage. The merge dialog has three
panes (yours, result, theirs), with arrows per change and "apply
non-conflicting".

**Gitsigns (Neovim).** Signs in the sign column (`│` added, `│` changed, `_`
deleted, `‾` deleted at the top), diffed against the index, in-process, on
every change. `]c`/`[c` go to the next or previous hunk. It can stage, reset,
undo the last stage of a hunk, or preview it inline, and in visual mode it
works on a range. There is a blame line as virtual text, and the base can be
a commit (`:Gitsigns change_base`).

**Also.** vim-fugitive (`:Git` status, `:Gvdiffsplit`, `dp`/`do` in a merge),
diffview.nvim (a file panel with a side-by-side diff, history per file), GitUp
and Fork (drawn graphs, editing the map), git-delta (word-level highlighting
in diffs).

**Taken here.** Magit's status buffer, its sections, and acting on the thing
at the caret. Its patches to `git apply`, and the `$` command log. Its
transient popups, kept small. IntelliJ's drawn graph, its repository colors
and root filter, its changelists, and its blame aged by brightness. The
Gitsigns keys and in-editor hunk actions. lazygit's
habit of showing the selected thing's diff alongside, and `?` everywhere.

## What j has today

- `vcs/git/Git`: `git <args>` runs a command async into an output buffer.
  `findRoot` walks up for `.git`.
- `vcs/git/GitStatusCache`: `git status --porcelain` per repository, cached
  and invalidated on save. `GitEntry` puts `git AM` in the status bar.
- `mode/diff`: `DiffMode`, `DiffFormatter` and `DiffOutputBuffer`.
  `diffGotoFile` on Enter goes to the source line.
- `CheckinBuffer` with a comment history.
- `ChangeMarks`: the gutter shows lines modified since the file was loaded
  (`Line.isModified`). It has `nextChange`, `previousChange` and
  `revertLines`, which work against j's own record of the lines, not git.
- `Display`: the gutter (`changeMarkWidth`, line numbers, and
  `Formatter.getGutterColor` for a swatch), `Formatter.getLineBackground`,
  and `ImageLine`, a line painted by `paintImageLine` instead of from text.
- `FoldCommands`: `hideLines` and `showOnly` with a predicate, plus fold and
  unfold.
- `Mode.getHints` gives a hint line of keys in the edit mode's notation, and
  `Mode.getModalKeyMap` gives vim-style keys in buffers with nothing to edit.
- The bottom panel holds transient buffers (help, results, output), and
  Escape or q closes it.
- `ProjectRoot` finds the outermost `.j-project` above several repositories.
- `SidebarBufferTree` and `SidebarList` are the buffer list.
- `util/ProcessRunner`.

Missing: a diff algorithm in Java, a file watcher (`WatchService`), a
repository model, a hunk model, a way to paint a graph, and any notion of
several repositories at once.

## Design

### A shared VCS layer

Today `VcsBackend` has a backend per system with commands to run
(`replaceComment`, `finish`, `autoEdit`, `gotoDiffSource`), and
`VersionControl` holds the helpers they share for running a command into an
output buffer. Neither has a model of a repository, a change or a hunk, so
nothing above them can be shared. Most of this plan works the same for
Subversion. The split:

| Shared (`org.armedbear.j.vcs`) | Git (`vcs.git`) | Subversion (`vcs.svn`) |
|---|---|---|
| `VcsRunner`: processes, the command log | `git`, its env and `-c` flags | `svn --non-interactive`, `--xml` |
| `VcsRepository`: root, color, snapshot, listeners | `.git` (or a `.git` file) | `.svn` at the working copy root |
| `Repositories`: discovery under the project, colors | | |
| `FileChange`: path, old path, kind, staged | porcelain v2 | `status --xml` |
| `FileDiff`, `Hunk`, the unified diff parser | `diff --no-ext-diff` | `svn diff` (unified) |
| `Patch.forHunk`/`forLines` | `git apply [--cached] [-R]` | `svn patch [--reverse-diff]` |
| `util/Diff`, change marks against a base text | the index blob, HEAD | `svn cat -r BASE` (pristine) |
| `Blame`: per line commit, author, date | `blame --porcelain` | `blame --xml` |
| `Commit` (id, parents, author, date, refs, subject), the log buffer, graph painting | `log -z` | `log --xml`, one lane |
| Status buffer: sections, folding, actions by capability | staged, stash, branches | changelists, locks |
| Conflict regions, next/previous, take ours/theirs | `git add` resolves | `svn resolve --accept=working` |
| Changelists: the model and UI | j's store, hunks | `svn changelist`, files only |
| Buffer list letters, gutter columns, hints, help | | |

A backend says what it can do with `capabilities()`: `STAGING`, `STASH`,
`BRANCHES`, `MERGE_GRAPH`, `HUNK_CHANGELISTS`, `NATIVE_CHANGELISTS`,
`PARTIAL_COMMIT`. The shared status buffer and popups leave out what a
backend cannot do: no Staged section for svn, and no hunk moves between svn
changelists, since svn's are per file. The existing per-system commands
(`svnDiff`, `svnCommit`, …) stay as they are until the new tools cover
them. The core keeps the layer and git and svn. CVS, P4 and Darcs stay in
`vcs-legacy` on the old interface.

Git is built first, but phase 0 puts each piece on the shared side of the
line, so svn needs only its backend: parsers, its runner flags and its
capabilities.

### Running commands

All of it shells out to `git` (or `svn`). The core has no dependencies (`bb
check-core`), so JGit is out, and the command log should show exactly what
ran. Nothing else runs them.

- **`VcsRunner`**: `run(repo, argv, stdin)` returns a future of `VcsResult`
  (exit code, stdout as bytes, stderr, ms). Reads run in parallel on a small
  pool, and writes are serialized per repository. Git's backend sets
  `GIT_OPTIONAL_LOCKS=0` for reads, `LC_ALL=C`, `GIT_TERMINAL_PROMPT=0` and
  `--no-pager`, and turns `color.ui` and `core.quotepath` off with `-c`.
- **Parsing**: machine formats only: `status --porcelain=v2 -z --branch
  --show-stash`, `log -z --format=<fields separated by %x00>`, `diff
  --no-ext-diff --no-color -z`, `blame --porcelain` (incremental), `stash list
  -z` and `for-each-ref --format`. A diff `--no-ext-diff` is required, since
  the user sets an external diff tool.
- **Command log** (`vcsCommandLog`, `$` in every VCS buffer): every
  invocation goes in a ring of the last 500, with time, repository, argv,
  exit code, duration and stderr. Its buffer shows one line per command, Tab
  expands the output, and a failed command is in the error color. It is
  transient (bottom panel) and appears on its own only when a write fails,
  as magit's does.

### The model

- **`VcsRepository`**: root, backend, color, and a `Snapshot` refreshed as a
  whole: the `FileChange`s, and what the backend adds. For git that is HEAD,
  branch, upstream, ahead and behind, the stash count, merge, rebase or
  cherry-pick in progress, and the parent branch (below). Listeners are told
  when it changes. `GitStatusCache` becomes a view on it.
- **`Repositories`**: the repositories in the project. That is the current
  buffer's repository; the `.j-project` directory and its immediate
  subdirectories, each of which may be a repository (two levels can come
  later if needed); and any repository of an open buffer. Each gets a color
  from a palette the theme can override (`color.vcs.repo1` … `repo8`),
  assigned by sorted path so it is stable between sessions and frames.
- **Refresh**: a `WatchService` on each git dir (`index`, `HEAD`, `refs/`,
  `packed-refs`, `MERGE_HEAD`), debounced 150 ms, plus j's own saves and the
  end of each write. Svn's `wc.db` is watched the same way. The working tree
  is not watched, since that is too many directories. Instead, saves, focus
  coming back to the frame, and `g` (refresh) cover it, as magit and
  IntelliJ (on frame activation) do.
- **Diff model** (`vcs/diff`): `FileDiff` (old and new path, mode, binary,
  status) holds `Hunk` (old and new start and count, lines with
  `+`/`-`/space, "no newline" markers). `Patch.forHunk(hunk)` and
  `Patch.forLines(hunk, from, to)` build a patch that `git apply --cached`
  takes. For lines left out, a `-` becomes context and a `+` is dropped (or
  the other way round when reversing), and the header counts are recomputed.
  A new file is staged whole with `git add -N` first, so that its hunks can
  then be split. This is the hardest code to get right, and it gets its
  tests first (below).
- **In-process diff** (`util/Diff`): Myers with a histogram heuristic, over
  lines, for the editor's change marks against the base text. It runs as
  you type, and running git on each keystroke would be too slow. Its output
  is the same `Hunk`, so the editor and the status buffer stage the same way.

### Parent branch

"Changes on the parent branch not merged into this one" are `git log
HEAD..<parent>`, and our own are `<parent>..HEAD`.

Git has no parent branch. A branch is a name for a commit. Commits know
their parents, but a branch does not know the branch it came from, and
every commit before the fork is on both. What git does keep is near it but
not it:
- The upstream (`branch.<name>.merge`) is set by `switch -c x origin/main`,
  so then it is the parent. But not when the branch starts from a local
  branch, and `push -u` changes it to `origin/x`, which is where most
  feature branches end up.
- The reflog says `branch: Created from HEAD` when made from a checkout,
  which names nothing. It is also local and expires, and a branch fetched
  from someone else has none.
- `git merge-base --fork-point <ref>` reads the reflog to find where the
  branch left `<ref>`, but `<ref>` must already be known: it answers
  "where", not "which".
- Guessing from the graph (the branch whose merge-base with HEAD is
  nearest) is ambiguous for stacked branches: `feature-b`, from `feature-a`,
  from `main`, has both as candidates, and which is meant depends on what
  the user is doing.

Other tools record it, each its own way; there is no standard:

| Tool | Where | Value | Written |
|---|---|---|---|
| GitHub CLI | `branch.<name>.gh-merge-base` | a branch name | by the user; `gh pr create` reads it as the PR's base |
| VS Code | `branch.<name>.vscode-merge-base` | a remote branch (`origin/main`) | by VS Code, on its own, the first time it needs one |
| git-town | `git-town-branch.<name>.parent` | a local branch | by git-town, for each branch it makes |
| git-machete | `.git/machete` | an indented tree of branches | by the user or `git machete discover` |
| Graphite | its own store under `.git` | `parentBranchName` | by `gt` |
| Magit | git's own upstream, `branch.<name>.merge` | the branch it merges into | by Magit, when it makes a branch |
| IntelliJ | nowhere | | the upstream gives incoming and outgoing; comparing is by hand |

Magit uses no key of its own. Its manual: "The upstream branch of some
local branch is the branch into which the commits on that local branch
should eventually be merged." Where a feature branch is pushed is a
separate setting git already has, the push-remote
(`branch.<name>.pushRemote`, `remote.pushDefault`, and `@{push}` to name
it). Its status buffer then has "Unmerged into origin/master" from the
upstream and "Unpushed to origin/my-feature" from the push-remote. Making a
branch from a remote feature branch, Magit sets the upstream to `master`
or `maint` (`magit-branch-adjust-remote-upstream-alist`).

j takes Magit's model when the repository is set up for it, reads the
others' records otherwise, and adds no key of its own. The parent is the
first of these that exists:

1. `branch.<name>.gh-merge-base`, which is what `gitSetParent` writes, so
   an explicit choice wins over anything inferred.
2. The upstream, unless the branch pushes to it. `git for-each-ref
   --format='%(upstream:short)%00%(push:short)'` gives both, by name, even
   for a remote branch not pushed yet (`git rev-parse @{push}` fails then).
   When the push target differs (Magit's setup: `push.default=current` and
   an upstream like `origin/main`), or is empty (git's default `simple`
   refuses to push to an upstream of another name), the upstream is the
   branch it merges into. When they are the same (`origin/x` after `push
   -u`), it is the branch's own copy, not its parent.
3. `git-town-branch.<name>.parent`, then the branch's parent in
   `.git/machete`.
4. `branch.<name>.vscode-merge-base`.
5. The reflog, as VS Code reads it: the branch's `branch: Created from X`,
   or, when that says `HEAD`, the last `checkout: moving from X to <name>`
   in HEAD's reflog. j makes its own branches with the start point named
   (`git switch -c new master`, not `git switch -c new`), so their reflog
   says `Created from master`. Reflogs expire (90 days by default) and are
   local, so this covers recent branches made here.
6. `origin/HEAD`, then `main`, then `master`.

`gitSetParent` (or editing the `Parent:` line) is the only thing that
writes, and it writes `gh-merge-base`: its meaning is the same (the branch
this one merges into), `gh pr create` then targets the same branch, and git
moves it with `git branch -m` and drops it with `git branch -d`. It never
changes the upstream, so `git pull` and `git push` behave as before.

The status buffer's `Parent:` line says which one was used (`origin/main
(upstream)`, `origin/main (default)`), so a guess is never shown as if it
were recorded. Sections show "Unmerged from origin/main (12)" and "Not in
origin/main (3)" against the parent, "Unpulled from" and "Unpushed to"
against the upstream, and, in Magit's setup, "Unpushed to
origin/my-feature" against `@{push}`. A section whose branch is the same as
another's is left out, so the parent and the upstream are never listed
twice.

### Status buffer (`gitStatus`, `Ctrl Alt G` / `<leader>gg` in vim)

There is one buffer per repository, or one for all of them when the project
has several, with a heading per repository in its color. Sections, in
magit's order:

```
Head:     master  icons: draw at the window's device scale
Upstream: origin/master  ↑2 ↓0
Parent:   origin/master

Merge in progress (2 conflicts)      ← only when there is one
Unmerged (2)
  UU src/org/armedbear/j/Display.java
Untracked (3)
Unstaged (4)
  M  TODO.md
     @@ -60,6 +60,12 @@ …           ← expanded with Tab
Staged (1)
Changelists                           ← phase 9
Stashes (2)
Unmerged from origin/main (5)
Unpushed to origin/master (2)
Recent commits (10)
```

- **Buffer**: a `GitStatusBuffer` whose lines are `GitLine`s carrying the
  section, file, hunk and line they show, as `DirectoryLine` does. Rendering
  rebuilds the text and keeps the caret on the same object, not the same line
  number. A refresh never moves the caret.
- **Folding**: sections, files and hunks are nodes with an expanded flag, not
  `FoldCommands` folds. A collapsed node's lines are not in the buffer at all,
  so search and motions skip them. Tab toggles the node at the caret, Shift
  Tab cycles every node, and `1`–`4` set the depth (magit). With the vim keys,
  `za`, `zo`, `zc`, `zM` and `zR` do the same. Unstaged and staged hunks
  start collapsed, and a file's diff loads the first time it is expanded.
- **Diffs inline**: `DiffFormatter` colors, plus word-level highlighting
  inside paired `-`/`+` runs (git's `--word-diff-regex` or our own diff over
  tokens) with `getRunBackground`. Line numbers for the old and new file go
  in the gutter.
- **Actions** on the thing at the caret, or on the lines in the region: `s`
  stages, `u` unstages, `x` discards (with a confirm naming what is lost; an
  untracked file is moved to the trash with `Desktop.moveToTrash`, or deleted
  where there is none), `S`/`U` stage or unstage all, `Enter` visits the file
  at the line (a staged hunk maps through the unstaged diff), `c` opens the
  commit popup, `z` the stash popup, `l` the log, and `g` refreshes. `d`
  opens the diff of the thing in its own buffer. `?` opens the popup of all
  of these.
- **Commit**: `CheckinBuffer` in its own window, the staged diff below the
  message (folded per file), and a summary length ruler at 50/72. `Ctrl
  Enter` finishes and `Escape` asks. The popup also has amend, extend (amend,
  keep the message), reword, `--no-verify` and `--signoff`.

### Editor: change marks and hunks

`vcsSigns`, per buffer, on by default in a repository:

- **One column, one source.** The change-mark column asks the buffer's
  `ChangeSource` for its marks. A file a VCS backend tracks uses the
  backend's, and j's own modified-since-load marks are not drawn. A file in
  no repository (or `vcsSigns=false`) keeps j's marks as now. The
  `ChangeSource` interface and its column are shared, so svn gets marks by
  giving the base text (`svn cat -r BASE`) and nothing more.
- **How they look.** A thin bar is green (added) or blue (changed), and a
  triangle marks lines deleted below. The three states:
  - unstaged, in the active changelist: solid;
  - staged: the same bar, dimmed (blended halfway to the background), as
    Gitsigns draws `signs_staged`;
  - in another changelist: outlined, as IntelliJ draws them, with the list's
    name on hover.
  Staged changes stay visible until committed. A hunk that is partly staged
  draws each line by its own state.
- **The base.** Unstaged marks are the buffer's text against the index blob
  (`git show :path`, cached by blob id). Staged marks are the index against
  HEAD, mapped onto the buffer's lines through the unstaged hunks. Both run
  100 ms after typing stops, by `util/Diff`.
- `nextChange`/`previousChange` go by git hunk when signs are on (`]c` and
  `[c` in vim, `Ctrl Alt N`/`Ctrl Alt P` in simple mode, as now).
  `vcsStageHunk`/`revertLines`/`previewChange`/`vcsUnstageHunk` work on
  the hunk at the caret, or on the lines of the region. In vim they are
  `<leader>hs`, `hr`, `hp` and `hu`, as Gitsigns suggests. Staging from a
  buffer with unsaved changes stages what is in the buffer: the patch is
  built from the buffer's text, not the file's.
- A click on a mark opens a popup with the hunk's old text and buttons to
  stage, reset, move to a changelist and go to the next hunk (IntelliJ).
  Preview shows the hunk's removed and added lines in a window floating
  under the caret, the characters that differ marked (gitsigns'
  `preview_hunk`), until the next command or a scroll. With no unstaged
  change at the caret it shows the staged one. Its bar has the hunk's
  number and buttons for the previous and next hunk (around at the ends),
  stage or unstage, and reset (unstaged only); going to another hunk while
  it is open shows that one in its place.
- `revertLines`, `previewChange` and `changePopup` work on j's own marks
  too; in a tracked file they reset, preview and offer the git hunk.

### Blame in the gutter (`gitBlame`, toggles)

- `git blame --porcelain --incremental --contents - <file>`, with the
  buffer's text on stdin, so lines added since are "not committed yet". It
  fills in as it streams and runs again after a save.
- A gutter column as wide as its widest entry, `2026-10-08 kkrouse` by
  default (`gitBlameFormat`: date, author, short hash, summary). An
  annotation shows on the first line of each run of the same commit, and the
  lines after it get a thin bar in the commit's color.
- Brightness by age, as IntelliJ does it: the commits in view are ranked by
  date and mapped onto 8 steps, from the text color down to the comment
  color, so the newest are brightest whatever the file's age. Ranking, not
  absolute age, keeps an old file from being all dim.
- Hover shows the commit's hash, author, date and full message. A click (or
  `Enter` on the gutter in vim, `<leader>gb` to toggle) opens that commit in
  the log, with the file selected. `gitBlameParent` re-blames at the commit's
  parent, which is how you dig past a reformat. `.git-blame-ignore-revs` is
  honored, since git honors it.
- **The current line's blame**, as Gitsigns' `current_line_blame`: virtual
  text after the end of the caret's line (`kkrouse, 2 days ago · icons:
  draw at…`), dimmed, after the caret rests for 500 ms. It is
  `vcsLineBlame`, on by default, and it does not need the gutter column.
- **Authors of a method**, as IntelliJ's code vision shows them: a dimmed
  virtual line above each method and class, `kkrouse +4`. That is the
  author of the most lines in it, and how many others have changed it.
  - Ranges: the mode's tags give where methods and classes start (a
    `LocalTag` of type `TAG_METHOD`, or a class), and `Mode.getFoldRange`
    at that line gives where they end, as folding already does. A mode with
    neither gets no authors.
  - Data: the same blame as the gutter's, run in the background when a
    tracked file is shown, and kept until it is saved or HEAD moves. Lines
    not committed yet count for the user (`user.name`), and the entry gets
    a `*` when the method has any (`kkrouse* +4`).
  - Hover lists every author with their line count and last date. A click
    opens the blame in the gutter and scrolls the method into view, with its
    lines' commits bright and the rest dimmed until the caret leaves it.
  - `vcsCodeAuthors`, on by default; files over `vcsCodeAuthorsMaxLines`
    (10,000) get none, since blame on them is slow.
  - It is a virtual line `ABOVE` the method's first line
    (`VIRTUAL-TEXT.md`), so it needs that phase. Other code vision (usages,
    tests) can share the line later.
- `Display` needs a gutter column provider for this: an interface that
  gives a width and paints the column for a line. Change marks, line numbers,
  color swatches and blame then become providers in a list instead of special
  cases.

### Log (`gitLog`, `l l` from status)

**It takes the whole frame**, as magit's and Neogit's do. `gitLog` saves the
frame's window layout (below), then shows only the log and its details. `q`
closes the log and puts the layout back: the same splits and weights, the
same buffers, and each window's caret and scroll.

**The quick log** (`gitLogQuick`, `l q` from status, `<leader>gl`) opens the
list alone in a split under the current window, with no details. `Enter`
(or a double click) on a commit grows it into the full-frame log with that
commit selected. `q` in the full log then goes back to the layout from
before the quick log, not to the quick log.

**Details at the bottom or the right** (`vcsLogDetails=bottom|right`,
toggled with `D`):

```
bottom (IntelliJ)                          right
┌ branch: master ▾  author ▾  path ▾  …┐   ┌ branch: master ▾  …  ┬──────────────┐
│ ▌ ●─┐ icons: draw at the …  kk  2h   │   │ ▌ ●─┐ icons: draw …  │ M Display.java│
│ ▌ │ ● build: bb run-way…    kk  1d   │   │ ▌ │ ● build: bb ru…  │ A Icons.java  │
│ ▌ ●─┘ Merge pull reque…     kk  3d   │   │ ▌ ●─┘ Merge pull r…  ├──────────────┤
├──────────────────┬───────────────────┤   │ ▌ ● │ web: webShowI… │ @@ -81,6 +81 │
│ M Display.java   │ @@ -81,6 +81,9 @@ │   │ ▌ │ │ xml: splitAt…  │ -  private … │
│ A Icons.java     │ -  private int …  │   │ ▌ ●─┘ vim: list ex…  │ +  private … │
└──────────────────┴───────────────────┘   └──────────────────────┴──────────────┘
```

On the right, the files are a small window on top (as tall as the list of
files, up to a third of the height) and the diff fills the rest. That is
close to magit's: `Enter` on a commit there shows a revision buffer in
another window, its message, a diffstat of the files, then every file's
diff. Here the files are a window of their own, so the diff shows one
file and the files stay in view while it scrolls. `vcsLogDetails=right` is
the default when the frame is wider than it is tall by more than 1.5 times.

**Window layout snapshots.** Nothing saves a layout today: sessions keep
buffers but not splits. `WindowLayout.capture(frame)` records the split
tree (`EditorPane`'s model), weights, which window had the focus, and each
window's buffer, caret and top line. `restore` rebuilds it, skipping a
buffer closed in the meantime. The log is the first user. Sessions can save
splits with it later.

- **The list is a buffer**: one `LogLine` per commit. Search, motions, vim
  keys, marks and copying work as in any buffer. The graph is painted in a
  left column, the way `ImageLine` is painted. That makes it a line
  decoration with a width, not text, so text selection and columns stay
  plain.
- **A row**, left to right: the graph, the repository's strip (several
  repositories only), the subject, ref badges (branches, tags), the author and
  the date. The subject takes what is left. As the window narrows, first
  the tags go, then the remote branches (local branches and HEAD stay),
  then the author shortens to initials, and then the subject is cut with an
  ellipsis. The graph, the strip and the date never go. A hover over a cut
  row shows it whole. The text of a row is laid out for the window's width
  when the window is resized, as a directory listing's is.
- **Loading**: `git log --topo-order --format=%H%x00%P%x00%an%x00%ae%x00%at%x00%D%x00%s -z`,
  streamed. The first 2,000 commits are shown at once and more are read as
  the caret nears the end. All refs are loaded (`--branches --remotes --tags
  HEAD`) unless a branch filter is set. Parents come with the commits, so the
  graph needs no second pass.
- **Graph** (`git/graph`): lane assignment on the commits loaded so far. A
  commit takes the lane of the first child that named it as first parent, or
  the leftmost free lane. Its other parents get lanes as they appear. A lane
  ends at its commit, and lanes to its right shift left with a curve. Each
  row stores the lanes passing through, those ending and starting, and the
  shifts. Painting, per row, in `Graphics2D` with antialiasing: straight
  segments for lanes going through, cubic Béziers for a lane moving between
  columns and for a merge or fork edge, and a filled circle for the commit
  (hollow for a merge, ringed for HEAD). The stroke is scaled by
  `UIScale`. Lane colors come from a palette keyed by the lane's first
  commit, so a branch keeps its color when the list scrolls or more loads.
  Rows are as tall as the text, and a very long edge (thousands of rows) is
  drawn as a short stub with an arrow, as IntelliJ does. The row being
  painted only needs its own row record, so painting stays O(visible rows).
- **Refs**: badges after the subject: local branch, remote, tag, HEAD, and
  stash in the log of stashes. They are tinted and the font is smaller.
- **Several repositories**: each repository's log is loaded, then merged by
  commit date. A colored strip (`▌`) in the repository's color is drawn
  to the right of the graph, and the graph is drawn per repository: lanes
  from different repositories never join, and each repository's lanes sit in
  its own band of columns. The repo filter (`r`) is a checklist, and
  "only this repo" is a click on the strip.
- **Filters**: repository, branch, author, path (the current file for
  `gitLogFile`), date and text (`--grep`, and `-S` for a change). Each runs
  git again with the filter. The filters sit in a **filter bar** at the top
  of the log window, outside the buffer:
  - **Why outside.** The filters are the state of the view, so they stay in
    sight however far the list is scrolled. The hint scrolls away, but this
    doesn't. The buffer stays nothing but commits, for search and copying.
    The bar is a strip of the window, the way the location bar is: a
    `FilterBar` component, painted by j in the buffer's font and theme
    colors. Dir mode uses the same bar for its sort, limit and hidden
    files (`TRANSIENT.md`), so it is built as a shared component, with the
    chips and choosers given by the mode.
  - **Not Swing combo boxes.** A `JComboBox` takes the keyboard focus, does
    its own key handling against vim's, and looks like the look and feel,
    not the theme. Each filter is a chip instead: `branch: master ▾`, or a
    dimmed `author ▾` when unset, with `×` to clear it. Chips never take the
    focus.
  - **Mouse.** A click on a chip opens a fuzzy list under it, from
    `CompletionPopup` as the finders use: branches and tags, authors seen in
    the log, paths in the repository, or the repositories as a checklist.
    Typing narrows it, `Enter` or a click picks. The text and date chips open
    a field. `×` clears one.
  - **Keyboard.** `f` opens the log's filter popup (below). Each filter has
    a key, as magit's arguments do (`-b` branch, `-a` author, `-p` path, `-r`
    repository, `-d` since, `-g` grep, `-s` pickaxe), and opens the same
    fuzzy list under its chip. `x` clears every filter. In the bar, the
    key is shown in bold beside each chip (`-b branch: master ▾`), so the
    keys can be learned from it.
- **The selected commit**: a tree of its files (status letter, icon, path)
  and the diff of the selected file, laid out as `vcsLogDetails` says.
  The focus stays in the list, as in magit and IntelliJ: `Up`/`Down` (or
  `j`/`k`, `n`/`p`) move through the commits, and the files and the diff
  follow, 100 ms after the caret stops so that holding a key does not run
  git for every row. From the list, `Space` and `Shift Space` scroll the
  diff (magit's `SPC`/`DEL`), and `Alt Down`/`Alt Up` select the next or
  previous file without leaving the list. `Tab` moves the focus list →
  files → diff → list, and in the files the same holds: moving there updates
  the diff, and the focus stays. `Enter` in the files focuses the diff.
  The diff is a `DiffMode` buffer, and folding a hunk works as in the
  status buffer. At the bottom, in a window narrower
  than `vcsLogStackedWidth` (100 columns), the files stack above the diff,
  as they always do on the right.
- **To the file**: `Enter` (or a double click) on a diff line opens the
  working-tree file at that line. The line is mapped through `git diff
  <commit> -- <file>`, adding up the hunks above it, as IntelliJ's "show in
  file" does. `Shift Enter` opens the file as it was at that commit,
  read-only (`git show <commit>:<path>`, named `File.java @ 1a2b3c4`).
- **Other logs**: `gitLogFile` (the current file, `--follow`), `gitLogLine`
  (the region, `-L`), the parent-branch sections, and stash, all in the same
  buffer with a different query.

### Stash

There is a status section and a popup (`z`): save (`z z`), keep the index
(`z i`), include untracked (`z u`), apply (`z a`), pop (`z p`), drop (`z
k`, with a confirm), and show (`Enter`, the stash as a commit in the log's
details view, with index and untracked as separate parents, labeled). The
log can show stashes as a filter.

### Buffer list

`SidebarBufferTree` and `SidebarList` (and the `Ctrl E` switcher) draw the
porcelain code from the repository snapshot after the name: `M` unstaged, `M`
staged in another color, `A`, `?`, `U` (conflict) in the error color, or a
dot, `gitBufferListStatus=letters|dot|none`. A row gets a thin strip in its
repository's color when the project has more than one. They repaint when the
snapshot changes, not on a timer.

### Conflicts

- The status buffer's "Unmerged" section shows the operation (merge,
  cherry-pick, stash pop) and per file the kind (both modified, deleted by
  them, …). `Enter` opens the file.
- In the editor, `ConflictFormatter` overlays the mode's formatter. It
  finds `<<<<<<<`/`|||||||`/`=======`/`>>>>>>>` regions and shades ours,
  base and theirs with `getLineBackground`. The marker lines get "accept
  ours · accept theirs · both · base" links above them, clickable as VS Code
  and IntelliJ's inline ones are. The links are a virtual line above
  `<<<<<<<`, so the conflict's own text is left alone. The commands are
  `gitConflictNext`/`Previous` (`]x`/`[x` in vim) and
  `gitConflictOurs`/`Theirs`/`Both`/`Base` (`u`/`l`/`a`, as smerge has them,
  after the simple-mode prefix, and `<leader>co`, `ct`, `cb` in vim).
  `merge.conflictStyle=diff3` (or `zdiff3`) is suggested so there is a base
  to show.
- After the last conflict is resolved and the file is saved, the status bar
  offers `s` to mark it resolved (`git add`). Deleted-by-them and the like
  get `gitConflictKeep`/`gitConflictDelete`.
- The finish line (`git commit` for a merge, `git stash drop` after a
  conflicted pop) is in the popup.
- Later: IntelliJ's three panes. Out of the first cut.

### Changelists

#### How IntelliJ does it

IntelliJ has two modes, and you pick one in Settings › Version Control ›
Git › "Enable staging area". Turning the staging area on deletes every
changelist; its help says the two "do not co-exist with the way the IDE
handles changes".

- **Changelists (the default).** The index is hidden. All of your changes,
  staged or not, are "local changes", sorted into named lists. There is
  always a default list, and one list is active. An edit goes to the active
  list, unless it touches a range already in another list, where it joins
  that list. A file's ranges can be in different lists. The editor tracks
  the ranges as you type, and the gutter draws the ranges of other lists
  differently. A commit takes the checked files and ranges, whatever the
  index holds. IntelliJ writes what is to be committed into the index for
  just those paths, commits, and puts back what was staged in the paths it
  left out. The changelists are kept in the project's workspace file, not
  in git.
- **Staging area.** The Commit window shows Staged, Unstaged and Unversioned,
  as `git status` does. The gutter has stage and unstage for a range, and a
  commit is whatever is staged. There are no changelists.

The reason they cannot both work is that each describes the next commit.
The index is one candidate commit. Changelists are several candidate
commits over the same changes. A hunk that is staged and in list B: does a
commit of list A take it? Is it unstaged when it moves to A? IntelliJ
answers by having only one of them at a time.

#### The choices here

1. **Either/or, as IntelliJ.** A preference, `vcsChangelists`, swaps the
   Staged and Unstaged sections for the lists. It is simple to explain, and
   each mode is consistent. But the status buffer, the gutter and commit have
   two behaviors each to build and test, and switching loses the lists.
2. **Changelists over the unstaged changes, with staging as it is (the
   first plan).** The Staged section is git's own. The lists group what is
   unstaged (the work tree against the index). Staging a hunk takes it out
   of its list, and unstaging puts it back in the active one. Committing a
   list goes through a private index (`GIT_INDEX_FILE`: `read-tree HEAD`,
   `apply --cached` of the list's lines, `commit`), then applies the same
   patch to the real index so the staged changes survive. The index stays
   true, so `git commit` in a shell, magit and lazygit see what j shows.
   The costs: two ways of grouping on one screen. A staged change has no
   list. When a list's change and a staged change overlap in a file, the
   patch to the real index can fail; then j says so, and leaves the index
   alone. And `c c` (commit what is staged) beside "commit this list" is a
   choice the user has to understand.
3. **Changelists as several indexes.** Each list is an index file of its
   own (HEAD plus that list's changes), and the real index is the list
   called "Staged". Moving a hunk applies it to one index and removes it
   from the other, and committing a list commits its index. Git does the
   bookkeeping. But a list then holds a snapshot of a change, the way
   staging does, not the live lines: a later edit to a hunk in list B shows
   as a new change in the active list, until it is moved too. That is
   staging with names, not IntelliJ's changelists.
4. **Changelists only, the index hidden (IntelliJ's default).** One
   concept, and the most like IntelliJ. But j would rewrite the index on
   every commit. That surprises anyone who stages from a shell or another
   tool, and it gives up line-by-line staging, which is what the status
   buffer is for.

Decided: 2. It keeps git's model honest, and changelists are an
addition you can ignore (`vcsChangelists=false` hides them). With one list,
the default, the status buffer is exactly magit's. The cost is the
explaining, which goes in the help: "Staged is git's; changelists sort what
you haven't staged yet." It also maps onto svn, which has no index: its
changelists are svn's own, by file (`svn changelist`), and committing one is
`svn commit --changelist`.

#### Design (choice 2)

- **The store.** Lists are kept per repository in
  `<gitdir>/j/changelists.json` (untracked and never seen by other tools): a
  name, a comment draft, and assignments. A file goes to its list whole
  unless some of its lines are assigned elsewhere. Assignments are ranges of
  work-tree lines. While a file is open they are `Marker`s in its buffer, so
  edits carry them along as IntelliJ's ranges are carried. When it is
  closed, each range is saved with the hash of its `-`/`+` lines and is
  re-anchored on load to the hunk it overlaps most. A change that matches
  no range goes to the active list.
- **The status buffer.** A "Changelists" heading replaces "Unstaged", with a
  heading per list, files under each, and ranges under a file that is split.
  `m` moves the thing at the caret (file, hunk, region lines) to a list,
  picked by a fuzzy prompt that can also create one. Drag and drop does the
  same, and `M` makes the list at the caret the active one.
- **Committing** a list uses the private index above. Its message is the
  list's draft, and an empty list other than the default is deleted after.

### Fold markers in the gutter

Today the gutter shows `›` (a character) on a line whose next lines are
folded away, and nothing where a fold could begin. The status buffer, the
log's diffs and the editor all want both, so this is a core feature, not a
VCS one:

- Two markers in the fold column, from the icons the sidebar trees already
  use: `expanded.svg` where a fold begins and is open, `collapsed.svg` where
  it is closed (replacing `›`). They are drawn at the window's device
  scale, as the other icons now are.
- An open fold's marker shows when the mouse is over the gutter
  (`foldMarkers=hover`, the default, as VS Code's `showFoldingControls`),
  always (`always`, as IntelliJ), or never. A closed fold's marker always
  shows.
- A click on either toggles the fold, as `toggleFold` does with the caret
  there.
- Where folds begin: a new `Mode.isFoldStart(Line)`, answered for visible
  lines only and cached per line until the buffer changes. Its default asks
  `getFoldRange`, which modes already have. The status buffer and the
  log's diffs answer from their own nodes (a section, a file, a hunk) with
  no search.

### Discoverability and keys

- Each VCS buffer opens with a hint: a virtual line above its first line
  (`s stage · u unstage · x discard · c commit · ? more`), in the edit mode's
  notation, made from `getHints` as dir mode's hint is now. It is not text,
  so the caret, search and copying pass over it, and it scrolls away with
  the first line, as Neogit's does. `vcsHints=false` turns it off.
- `?` opens the buffer's transient popup (`dev-docs/TRANSIENT.md`): its
  arguments, its filters and its commands, grouped, with the keys in bold
  as magit and Neogit show them. Each command line shows its command name
  too, so `Ctrl Shift P` finds it. The last line is "help: git status" and
  links to `doc/git.html#status`. Every git buffer and popup names its
  anchor (`Mode.getHelpTopic`, new), and `F1` or `:help` in that buffer
  opens it there. The same popups open on their own prefix keys: `c`
  commit, `z` stash, `l` log, `f` the log's filters.
- New user docs, `doc/git.html`: status, staging, committing, log, graph and
  filters, stash, blame, signs, conflicts, changelists, command log, keys in
  both edit modes, and preferences. `vcs.html` links to it.
- **Keys**: the git buffers have nothing to edit, so the same single letters
  work in simple and vim edit modes, via `getModalKeyMap` as in dir mode.
  Movement is the arrows and `n`/`p` (magit) in both, and `j`, `k`, `/`,
  `gg` and `G` too in vim mode, so no git action takes those letters. Section
  jumps are `Alt n`/`Alt p` and `]]`/`[[`. In the editor, simple mode gets a
  `Ctrl Alt G` prefix (`Ctrl X` is `killRegion`, not a prefix), and vim
  gets `<leader>g…`/`<leader>h…`, as fugitive and Gitsigns users expect,
  with `]c`/`[c` and `]x`/`[x`.
- **Mouse**: a click on a section, file or hunk heading, or on its fold
  marker in the gutter (above), toggles it. A double click visits. The
  right button gives a menu of the actions for that thing, with their keys
  shown. Graph rows
  and blame have hovers, and change marks have the hunk popup. Files and
  hunks can be dragged between changelists.

## Phases

Every phase lands with its user docs, its hints and help anchor, and tests
against real repositories made in a temporary directory (`git init`, scripted
commits, with `GIT_CONFIG_GLOBAL=/dev/null` and fixed author dates).

**An adversarial review closes each phase.** A reviewer that did not write
the code (a fresh agent, given the phase's diff and this plan, not the
conversation that produced it) is asked to find what is wrong, not to
approve. It looks at:
- architecture: whether the shared layer stays free of git, whether the
  phase leans on something a later phase will have to undo, whether its
  parts would hold for svn;
- logic: patches and line mappings off by one, refresh races, a stale
  snapshot used after a write, caret and selection after a refresh;
- threads: git off the event thread, Swing on it, and locks taken in a
  different order than the buffer's;
- failures: git missing or old, a command that fails or hangs, a repository
  mid-rebase, a detached HEAD, an empty repository, paths with spaces or
  non-ASCII names;
- security: look for injection attacks, possible data-loss, file operations,
  path traversal;
- tests: ones that pass for the wrong reason, and the cases above that
  have none.
Each finding is fixed, or recorded under "Review notes" at the end of this
plan with why it is left, before the next phase starts.

0. **Done. Groundwork.** The shared layer: `VcsRunner` and the command log,
   `VcsRepository`, `Repositories` with colors, the watcher, the diff parser
   and `Hunk`. Then git on it: snapshots from porcelain v2, and
   `Patch.forHunk`/`forLines` with tests: stage and unstage one hunk, one
   line, a line next to a "no newline" end, a new file, a deleted file, a
   rename, CRLF files, and a file whose patch git rejects. `GitStatusCache`
   moves onto the snapshot. Done means a round trip of every hunk and line
   through `git apply --cached` and back leaves `git diff` as it was.
1. **Done. Virtual text and transient popups** (`dev-docs/VIRTUAL-TEXT.md`, its
   phases 1 and 2; `dev-docs/TRANSIENT.md`, its phases 1 and 2): end-of-line
   text and virtual lines, enough for hints and previews, and the popups.
   Dir mode's hint moves onto a virtual line, and `?` there opens its
   popup.
2. **Done. Status buffer, read-only.** Fold markers in the gutter (a core
   change, tested in Java and markdown modes too), sections, folding,
   inline diffs with word
   highlighting, visiting, refresh, hints, `?`, `doc/git.html#status`, and the
   status letters in the buffer list. As built:
   - The buffer is shared, not git's: `vcs.status` has `VcsStatusBuffer`,
     `StatusLine`, `StatusRenderer`, `VcsStatusMode` and `WordDiff`, and
     reads `StatusData` from `VcsBackend.readStatus`; git's is
     `GitStatusReader`. Svn needs only its reader.
   - Fold markers are the tree icons' chevrons drawn as vectors in the line
     numbers' color (the svg files fix their own color). `Mode` has
     `isFoldStart`, `getFoldBelow`, `isFolded`, `toggleFoldAt`, and for a
     mode whose folds are its own, `setFoldAt` and `setAllFolds`, which the
     fold commands (and vim's z keys) ask first. `foldMarkers` is the
     preference.
   - `Mode.sectionFrom` gives vim's `]]`/`[[` a mode's headings;
     `Mode.getHelpTopic` makes F1 open the mode's page.
   - Refresh is `r` in vim edit mode, since `g` starts `gg`.
   - Ctrl Alt G opens the status directly, not as a prefix yet;
     `<leader>gg` comes from rows of the vim key map with `<leader>`, bound
     with the vimrc's mapleader before the vimrc's own maps.
   - Left for later phases: old and new line numbers in the gutter (phase
     4's gutter columns), the `Parent:` line (6), untracked files' contents
     and conflicted files' diffs (8). Enter on a commit shows `git show` in
     a diff buffer until the log (5).
   - Paging counts virtual rows (`Display.pageAbove`, `getBottomLine`).
3. **Done. Staging and committing.** Stage, unstage and discard for files, hunks
   and lines, commit, amend, reword, and the commit popup. As built:
   - `VcsBackend` has the writes (`stage`, `stageAll`, `unstage`,
     `unstageAll`, `discard`, `apply`, `commit`, `lastMessage`) and
     `CommitOption`; git's are `GitWrites`. `StatusActions` works out what
     the caret or selection takes (a section's files, files, hunks, lines of
     a hunk) and runs them in the background; a failure shows the command
     log.
   - Unstaging is `git reset -- paths`, before the first commit too;
     discarding a staged change takes its reverse out of the working tree,
     then the index, hunk by hunk from the bottom, each step checked first; a
     file no commit has goes to the desktop's trash where there is one.
   - A selection takes a changed line's removed and added halves together.
   - The commit popup's `-v` (or git's `commit.verbose`) puts the commit's
     diff under a scissors line, as `git commit -v` does. In vim, `ZZ` and
     `:wq` commit, `ZQ` and `:q!` give up the message: `Mode.runExCommand`
     lets a mode take ex commands, and `ZZ`/`ZQ` are `:x`/`:q!` everywhere.
   - The message is a `CommitMessageBuffer` in a window beside the status,
     not over the staged diff: the status buffer's Staged section, beside it,
     shows the diff, and the message lists the staged files as # lines.
     Commits run with `--cleanup=strip -F -` and no editor, and a 30 minute
     timeout for hooks. Escape is mapped in vim's normal mode only, so it
     still leaves insert mode first; a new message starts in insert mode.
   - After a write, the caret goes to the next thing in the buffer, or to a
     heading just closed.
4. **Done. Editor hunks.** `util/Diff`, gutter column providers, `ChangeSource`,
   signs (solid, dimmed staged), hunk navigation, stage, reset and preview in
   the editor, and the mark popup. As built:
   - `util/Diff` is Myers' diff between the lines the two sides don't share
     at their ends, with lines in only one side left out of the search
     first (histogram diff's idea, not all of it), and one replacement past
     1024 differences.
   - The core has `Signs` (each line's mark, and where hunks start); a
     buffer tells its `SignsUpdater` of each edit, and the change-mark column
     draws a buffer's signs in place of j's marks when it has them. It is
     that one column, not yet general column providers: blame's column
     (phase 7) makes them.
   - `vcs/signs`: `SignsUpdater` (per buffer, 100 ms after typing stops or
     when the repository changes) and `SignsMath` work out the signs from
     `VcsBackend.readBases`, git's `GitBases` (`ls-files -s`, `ls-tree`,
     blobs by id, cached). j's last newline is an empty last line, which git
     does not count; buffers are read as git counts them.
   - `HunkCommands`: `vcsStageHunk`, `vcsUnstageHunk`, and the reset and
     preview `revertLines` and `previewChange` call in a tracked file;
     `ChangeMarks.changePopup` (a click on a mark: the preview and a popup of
     the rest). Patches come from the buffer's text against the
     index, CR LF and a missing last newline kept (`EditorHunks`); a hunk's
     context is the unchanged lines only, so a patch applies to either side.
     A changed line's halves stage together, as in the status buffer
     (`Patch.pairChanges`).
   - `nextChange`/`previousChange` go by hunk; `revertLines`, now a
     command, resets the hunk in a tracked file. Vim: `]c`, `[c`,
     `<leader>hs`/`hr`/`hp`/`hu`.
   - Left for phase 9: outlined marks for other changelists, and moving a
     hunk to one from the popup.
5. **Log.** `WindowLayout` capture and restore, the full-frame log and the
   quick log, the list buffer with streaming load, rows that fit the width,
   details at the bottom or the right, to-file and at-revision, the filter
   bar and the filter popup, then the graph
   (lanes, then painting), then several repositories (merge, strip, bands,
   filter). The graph gets its own tests, of the lane layout on synthetic
   histories (octopus merges, criss-cross merges, 500 parallel branches, a
   root commit in the middle), apart from painting.
6. **Branches relative to others, and stash.** The parent branch, the
   unmerged, unpulled and unpushed sections, the stash section and popup, and
   stashes in the log.
7. **Blame.** Streaming blame, aging, hovers, to the log, blame at the
   parent, the current line's blame, and the authors of each method.
8. **Conflicts.** Markers, inline actions, navigation, marking resolved,
   and finishing.
9. **Changelists.** The store, ranges as markers, re-anchoring, the status
   section, moving, outlined marks, committing with a private index, and
   drag and drop.
10. **Subversion.** Its backend on the shared layer: status, diff, marks,
   blame, log (one lane), conflicts, and svn's own changelists. This proves
   the split. Any git-only leaks found here are fixed in the shared code.

Status comes before the log, although the log is first on the list. Both need
phase 0, and the status buffer exercises the diff and hunk model that the
log's diff view and the editor's hunks reuse. The log could go second if
it is wanted sooner. Nothing in phase 5 depends on phases 2–4 but the
diff view.

## Open questions

- Big repositories: the time to refresh status on a repository the size of
  Linux. That means `core.untrackedCache`, `--untracked-files=no` beyond a
  threshold, and sections that load when expanded (magit's
  `magit-status-sections-hook` trimming).

## Later

Rebase (interactive, as a buffer to edit), fixup and autosquash, cherry-pick,
revert, worktrees, branch create, delete and rename popups, push and pull and
fetch popups, a side-by-side diff view, the three-pane merge, comparing two
commits or branches, a file's history in the log with the graph narrowed to
it, a changes tab on the file tree (TODO.md), signs on the scrollbar (TODO.md
scrollbar item), GitHub PRs.

## Review notes

Findings from the reviews that close each phase, left open on purpose.

**Phase 0** (fixed: reverse patches lost a newline on a removed line; the
random test now checks each result against one worked out without `Patch`;
a part of a rename or mode change no longer carries it; combined diffs;
svn's added and deleted files; repository links; user color settings;
reftable refs; git older than 2.16 says so; the stash count is unknown
before 2.35; ignored files listed by directory; the refresh loop survives a
failing listener; stale status answers at once and refreshes in the
background, updating open buffers; command input in the log.)

- A repository's first snapshot is still read on the thread that asks, the
  event thread for the status bar, as before. Later ones are not.
- `Patch` writes git's headers (`diff --git`, a/ and b/, mode lines). svn's
  backend will need its own header writer, behind the backend, in phase 10.
- `Snapshot` has no branch or revision; shared UI reads `GitSnapshot`. A
  neutral "where you are" (branch, revision, upstream) comes with the
  status buffer's header in phase 2.
- `VersionControl.guessVCS` and `Repositories` find roots separately (one
  stops at home, one canonicalizes). With vcs-legacy loaded, a `CVS/`
  directory inside a git repository makes it CVS for both. Merge them when
  the old per-system commands move onto the shared layer.
- `VcsRunner` serializes writes by directory, not repository: callers run
  writes at the repository root, which `GitCli.write` takes as its argument.

**Phase 1** (fixed:
- **Option fields:** Enter or Escape in one closed the whole popup.
- **Completion lists:** a pick option's list was drawn under the popup.
- **The top line:** rows over the caret's line counted as its text being partly hidden.
- **Visibility:** the bottom line, the caret and moving down disagreed about rows under a line; all now go by its text.
- **Hidden directories:** they stayed in the Java listing.
- **Lock order:** the layer took the buffer's monitor inside its own lock, and the scrollbar waited on a remote directory loading. The layer now reads the first line without the lock, and finds deleted lines in one walk.
- **Saved defaults:** saving one popup's defaults could delete a dotted id's.
- **Rows that go while scrolled:** they left the top past its line.
- **`toBottom`/`toCenter`:** they counted lines, not rows.
- **TOP rows:** they now go over the first line shown, and `Buffer.getY` counted a hidden first line.
- **`Ctrl S` on bound values:** it claimed to save them.
- **Popup keys:** they are the listing's own in either edit mode, and work with the popup closed.
- **Escape:** it now always goes back or closes.
- **AltGr:** characters typed with it are taken.
- **Resizing:** it lays the popup out again.
- **Clicks on virtual text:** they update the window.
- **Empty hints:** an empty hint is no longer shown.
- **EOL text:** a line scrolled out of view to the left no longer leaves its text at the edge.)

- `WebBuffer.getDisplayHeight` leaves out virtual rows. Web pages have none.
- A `Binding`-backed switch or option keeps its value where the binding
  says, so session seeding and saved defaults skip it. A git popup that
  wants defaults keeps its values in `TransientArgs` instead.
- A popup taller than its window is cut, help line and all. Popups are a
  screen or less today, and phase 5's log popup is the largest planned.
- The popup's focus and its field are checked under Xvfb by hand
  (`java.awt.Robot`). The headless tests have no root pane, and the fuzzer
  sets fields directly.

**Phase 2** (fixed:
- **Hunks:** a hunk was known by its place in the file, so a change above it moved its open or closed state and the caret to another hunk. It is now known by what it changes.
- **Refreshes:** each save and each return to j's window laid the buffer out again and dropped the selection. One that finds nothing new now leaves it alone, and one that does keeps the mark on its line.
- **Gutter clicks:** a click anywhere in the gutter, a line number say, folded the line, even with `foldMarkers=never`. Only a chevron that is drawn, in the fold column, now folds or unfolds; a click elsewhere in the gutter beside a closed fold still opens it, as it did before the chevrons.
- **Large diffs:** word diffs are cached, and a run of more than 200 changed lines is not refined. Paths go to `git diff` 100 at a time, under Windows' limit on a command line.
- **Help links:** `#status`, `#bufferlist` and `directories.html#popup` were `id`s, which j's help pages don't find; they are `<a name>`s.
- **Staged lines:** a hunk with an empty side mapped a line one too far.
- **Page up:** it counted the rows over the top line twice.
- **Empty diffs:** a rename with no change, a mode change, and a diff git failed to give now say so.
- **CRLF:** a line's CR no longer shows.
- **Fold starts:** they are asked again when a buffer's mode changes.
- **zc:** on a closed file it closes the section, as vim's does.
- **Depths:** a file opened by hand at depth 1 shows its hunks.
- **Buffer lists:** a row's repository color is kept for a few seconds, not looked up on each paint.
- **Comments:** four left on the wrong method by inserted code.)

- A change only of tabs to spaces shows `-` and `+` lines that look alike, with no word marked: both are detabbed before the word diff.
- Git leaks into shared code: `VcsStatusMode`'s help is `git.html#status`, `BufferListStatus` reads git's porcelain codes, and the preference is `gitBufferListStatus`. Svn's backend (phase 10) gives the mode its help topic and the entry its letters.
- A render empties the buffer, which invalidates the views other windows keep of it; a window that comes back to it later starts at its top. Windows showing it keep their place.
- No tests for git missing, a rebase in progress, or a path with a leading dash (a review's scratch test showed one renders).

**Phase 3** (fixed:
- **Intent-to-add files:** `x` emptied one (`git checkout` wrote its empty index entry over it). A file no commit has, untracked, added or noted with `git add -N`, now goes whole to the trash, out of the index first.
- **A commit's message:** Escape or closing its buffer while the commit ran threw the message away, and a failing hook then lost it. It can't be closed while committing, and closing it any other way (j quitting) puts it in the comment ring.
- **Staged new files:** discarding one deleted it outright; it goes to the trash, as an untracked file does.
- **Staged changes in files with unstaged ones:** `git apply --index -R` refused them all. The change comes out of the working tree, then the index, and every step of a discard is checked (`git apply --check`) before any runs, so a discard is all or nothing.
- **Pressing twice:** a second `s` or `x` before the refresh applied the same patch again, wherever its context matched. An action waits until the last one's refresh is shown.
- **Vim:** Escape in normal mode never reached the commit message's mode, and visual `s` was vim's. A mode's modal key map now gets an idle Escape, and `Mode.getVisualKeyMap` gives the status buffer `s`, `u` and `x` on a visual selection, which is taken line for line.
- **Reword with `-a`:** git refuses `--only` with `--all`; reword leaves `-a` out.
- **Unstaging before the first commit:** `git rm --cached` refused a file changed since; `git reset` works on an unborn branch.
- **Staged renames:** discarding one in more than one hunk left the rename; it is refused, with a word to unstage it first.
- **Templates:** a reword lists no files, and `-a` lists what it commits. A last message with # lines of its own keeps them (`--cleanup=whitespace`).
- **Stale diffs:** staging or unstaging a hunk checks it is still in the file's diff.
- **Selections:** one starting at the end of a line leaves that line out.)

- The status code names git's sections (`"staged"`, `"unstaged"`) and its staging model in the shared layer. Svn's backend (phase 10) gives the sections and actions by capability.
- `VcsBackend.commit` takes no paths and no index file; phase 9's private-index commits and svn's commits of paths add them.
- On a desktop without a trash, a file no commit has is deleted, as the question says.

**Phase 3, second review** (fixed:
- **The write guard:** a read already running when a write started could end it, its data from before the write. A write now marks the buffer as written at the end of time until it is done.
- **Commits:** they went around the guard, so a status action could race a `-a` commit, and a double extend ran two amends. They take the guard too.
- **`U` in a merge:** `git reset` ended the merge, keeping its markers. It is refused while a merge or rebase is in progress; `u` on a conflicted file was refused already.
- **Refreshes:** each action read the status twice. The repositories' refresh tells the buffer, which reads once.
- **Ctrl N:** a fresh one went to the oldest message. It now comes forward toward the message being written, as a shell's history does.
- **Gates:** the commit popup and `S`/`U` check `Capability.STAGING`, as the popup's groups did.
- **Docs:** this phase's note had unstaging and the discard's order wrong.)

**Phase 4** (fixed:
- **Save As:** the buffer kept its old file's signs and path, so staging wrote into the other file's index entry. A new path is a new `SignsUpdater`.
- **Undo and redo:** they left the signs as they were before. `Buffer.undo` and `redo` tell the sign source.
- **A last line with no newline:** it compared equal to one with, so lines added below it could not be staged, and a reset at the end dropped the file's last newline. A side's last line is compared with its newline or lack of one (`SignsMath.keys`), and a reset at the end takes the index's.
- **Staged deletions:** one under a line changed again in the buffer had no mark, and could be unstaged from line 1. Both go under the nearest line before that the buffer still has.
- **Line numbers:** a command right after a newline was typed read the caret's stale line number. The hunk commands renumber first.
- **The diff's limit:** past 4096 differences it had kept 140 MB to give up. The limit is 1024, a few MB.
- **Bases:** a read begun before a stage could put its old bases back; a generation guards them. A file in a conflict no longer runs git on each pause in typing.
- **Renames:** a staged one was all "added", against the new name in HEAD; it is against its old name.
- **An emptied buffer:** its deletion now has a mark, on j's empty last line.
- **vcsSigns:** turning it off shows j's marks at once.
- **Core and vcs:** this review moved core's calls to the hunk commands behind a `SignSource` interface and a caret hook. A later simplicity review took them out again, because core already uses vcs in a dozen places and there was one implementation.
- **`nextChange`:** it walked the buffer once per hunk; once now.)

- Each repository refresh reads every open file's bases again: two git processes per buffer. Batching them (`ls-files -s` for all at once) can come when it shows.
- A file with smudge and clean filters (LFS, git-crypt) compares its text with the clean blob, so all of it may show as changed; staging a hunk of it would bypass the clean filter. Not handled.
- `Signs.Sign.staged` becomes a state with a third value, another changelist, in phase 9; the drawing moves into a column provider with blame's column in phase 7.
- A reset whose buffer is reloaded from disk between its read and its edit could edit the new text: `modCount` starts again at 0 on a reload. The window is a git command long.


### Simplicity review, phases 0–4

Removed: code nothing called (`VcsRunner.runAsync` and its pool, `CommandLog`'s listeners, `Repositories.all`, `GitCli.apply`, `GitStatusCache.invalidate`), `WordDiff`'s own LCS table (it uses `util/Diff`), and the change colors hard-coded in five places (`VcsColors`). Kept, though nothing uses them yet, because later phases need them: the popup's `Pick` option (phase 5's filters), virtual text's `EOL` and `RIGHT` placements and priorities (blame, phase 7), and the `Capability` values past `STAGING`.

### Phase 4 feedback

- The preview was virtual text: the old lines struck through over the
  hunk. It is now a floating window under the caret with the removed and
  added lines, the characters that differ marked (`WordDiff.chars`), as
  gitsigns' `preview_hunk` has it. The next command or a scroll closes it,
  except `]c`/`[c`, which show the hunk they go to in its place (no
  flash), and the first key of a sequence, as `[`. A bar over it has
  "Hunk n of m", numbered as `]c`/`[c` go, and buttons for previous, next,
  stage or unstage, and reset, in the text color (`Icons` caches an icon by
  color too), dimmed when they can't be used. Icons: `up`, `down` (new),
  `plus`/`minus` (new; VS Code's and JetBrains' stage and unstage), `undo`
  (their discard and rollback).
- `nextChange`/`previousChange` go around past the last or first hunk, in a
  file git tracks, saying so in the status bar. Unlike vim's `]c`, by
  request.
- `Display` paints changed lines, the caret and scrolls straight to the
  screen. Over a popup in the layered pane (the hunk popup, the preview)
  that drew over it until it painted again, a flash; with one over it,
  the display repaints through Swing instead.
- The preview is placed against the caret's line again on each step of a
  scroll (`Display.scrollByPixels`), so one shown while `]c` eases the
  window to a hunk ends up beside it, not where the line was.
- With no unstaged change at the caret, the preview shows the staged hunk
  there (a new file's is all of it), with unstage in place of stage.
- Web buffers (the help pages) have no signs: their text is the file
  rendered, so every line differed from the index.

### Review fixes

- Reset and preview check that the editor still shows the buffer they read,
  not only its modCount: two unedited buffers both have 0.
- The preview hides while the caret's line is off screen
  (`Display.getLineTop`), rather than being placed outside the window.
- `WordDiff` skips pairs past 40,000 token cells again, so long minified
  lines don't stall the event thread.
- `Display` resolves the sign colors once per paint; `hunkStarts` hashes
  the starts, not every line; `attachSigns` starts the computation itself.
- The preview's lifecycle is the editor's: `Editor.showCaretPopup` holds
  one `CaretPopup` per editor and closes it after a command (not on the
  first key of a sequence) or on a wheel scroll; `Display` tells it of
  scrolls. Only `ChangeMarks` knows of the preview, to follow `]c`/`[c`
  (`replacingCaretPopup`). Its bar is a `JToolBar` of `JButton`s, flat
  until hovered as j's toolbar's are.

### Commands for any change marks

- Previewing, resetting and the mark's popup aren't git's: `previewChange`,
  `revertLines` and `changePopup` work on j's own marks (the lines as
  loaded, from `Line.getOriginalText`) and, in a tracked file, on the
  version control's hunks. Only staging needs an index: `vcsStageHunk` and
  `vcsUnstageHunk`, named as the status buffer's `vcsStage`. The
  `gitHunkPopup`, `gitPreviewHunk`, `gitResetHunk`, `gitStageHunk` and
  `gitUndoStageHunk` names are gone (never released). Help is
  `changes.html`.
- `nextChange`/`previousChange` go by the first lines of j's runs of
  changed lines as by git's hunks, around at the ends, and an open preview
  follows them.
- Hunk numbers renumber the buffer first: after a split they were stale.

