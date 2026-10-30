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

**Several repositories, always.** Every feature is designed for a
project of several repositories as well as one: it either acts in each
(as IntelliJ's "all roots": a popup's `-a`, names labeled with how many
repositories have them, one repository after another with each one's
outcome said, a failure in one leaving the others done) or says why it
stays with the repository at the caret. Which repositories an action
takes is shown before it runs. A phase's plan states this for each of
its features, and its tests use three repositories, one failing or left.

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
  `GIT_OPTIONAL_LOCKS=0` for reads, `LC_ALL=C` and `GIT_TERMINAL_PROMPT=0`,
  and turns `color.ui` and `core.quotepath` off with `-c`. Its output is a
  pipe, so git pages nothing. The command log leaves these `-c` options out
  of each line and lists them once above.
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
  coming back to the frame, and `Ctrl R` (refresh) cover it, as magit and
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
   or, when that says `HEAD`, the first (oldest) `checkout: moving from X
   to <name>` in HEAD's reflog, which is the branch's making; a later one
   names wherever the branch was last switched to from. A branch deleted
   and made again under the same name gets the first one's source, as a
   reflog can't tell them apart; any record above overrides it. j makes its own branches with the start point named
   (`git switch -c new master`, not `git switch -c new`), so their reflog
   says `Created from master`. Reflogs expire (90 days by default) and are
   local, so this covers recent branches made here.
6. `origin/HEAD`, then `main`, then `master`.

`gitSetParent` (Enter on the `Parent:` line, or the branch popup's `p`)
is the only thing that writes, and it writes `gh-merge-base`: its meaning is the same (the branch
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

### Status buffer (`vcsStatus`, `Ctrl Alt G` / `<leader>gg` in vim)

There is one buffer per repository, or one for all of them when the project
has several, with a heading per repository in its color. Sections, in
magit's order:

```
Head:     master 1a2b3c4 icons: draw at the window's device scale
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
Changelists                           ← phase 10
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
  commit popup, `z` the stash popup, `l` the log, `D` the diff beside it,
  `=` its layout, `$` the command log, and `Ctrl R` refreshes (`r` is kept
  for rebase). `N`/`P` (`]]`/`[[` in vim) go from section to section. `?`
  opens the popup of all of these, in Show, Go, Changes and Commands.
- **The diff beside it** (`D`, Show Diff, until `D` opens a diff popup as
  Neogit's does; `vcsStatusDiff=off|right`
  for how it opens): the status keeps the left of the frame and the diff of
  the file at the caret fills a window at the right, as lazygit's main panel
  and IntelliJ's commit window's preview have it. It follows the caret 100
  ms after it stops: a file's whole diff, the unstaged or staged one as its
  section says; on a hunk, scrolled to that hunk. The pane is the log's diff
  pane, made shared (`LogDiffBuffer` becomes a `DiffPane` that takes any
  `FileDiff`), so Tab folds its hunks, `n`/`p` and `]c`/`[c` go by hunk,
  Space and Shift Space scroll it from the status, and Enter visits the
  file. Files can stay closed in the status while it is open; inline diffs
  still work. `D` again, or `q` in the pane, closes it, through
  `Frame.captureLayout` and `restoreLayout` as the log does. With the
  side-by-side diff (phase 6) the pane can show that instead.
- **Sidebar: an outline by directory.** The sidebar's lower half, the
  mode's `getSidebarComponent` (empty for the status today), shows the
  buffer's outline, as the Java tree does for code: a node per repository
  when there are several, then each section with its count (Untracked 3,
  Unstaged 5, Staged 2, and the unpushed and unpulled ones of phase 7), and
  under each its files as a directory tree, with their letters in the
  buffer list's colors. It is the grouping by directory that VS Code's
  Source Control view and IntelliJ's commit window offer and the buffer's
  flat lists don't. A click, or Enter in it, puts the status's caret on
  that section or file. Its label (`getLabelText`) is the branch, its
  upstream and ahead/behind, which stay in sight however far the buffer
  scrolls. It is rebuilt from the same `StatusData` as the buffer, on each
  refresh, its open nodes kept by key.
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

### Compare (`vcsDiff`)

Two panes, the old text at the left and the new at the right, scrolled
together, as nvim's diff mode and IntelliJ's diff viewer have it, with
IntelliJ's track between them: each change a band whose edges curve from its
lines on one side to its lines on the other.

```
 old (HEAD)                 │╲  new (working tree)
 60   private int width;    │ ╲ 60   private int width;
 61   private int height;   ├──┤ 61   private int heightPx;     ← changed: blue
 62                         │  ╲62   private int depth;        ← added: green,
 63   void paint() {        │  ╱63                               a curve fanning
                            │ ╱ 64   void paint() {              out from a line
```

- **Where it opens.** From the status's and the log's diff panes (`=` there
  toggles unified and side by side), on a file in the status, from a file
  (`vcsDiff`: the buffer against the index, or HEAD), and against a
  revision from the log. It takes the frame's windows the way the log does,
  through `Frame.captureLayout` and `restoreLayout`, and `q` puts them back.
- **In the log.** Side by side, the diff wants the frame's width: two panes
  beside the list at the right, or under the files at the bottom, are each
  narrower than the unified diff. So the log gains a third layout for it:
  the list at the top left, the commit's details (fields, message and files)
  at the top right, and the two panes and their track across the whole
  width at the bottom.

  ```
  ┌──────────────────────────┬─────────────────┐
  │ list                     │ details: commit │
  │                          │ message, files  │
  ├────────────┬──┬──────────┴─────────────────┤
  │ old        │╲╱│ new                        │
  │            │  │                            │
  └────────────┴──┴────────────────────────────┘
  ```

  `=` in the log's diff switches to it, and back to the layout `D` chose.
  Side by side has only this layout, as stacking the two sides would undo
  them, so `D` does nothing there. Whether the diff is side by side is
  remembered in the session, as `D`'s choice is. The panes follow the file at
  the caret in the files pane as the unified diff does, and Space and Shift
  Space scroll both from any pane. In the sides, `d`, `u`, `f` and `F` keep
  their usual meaning.
- **The panes are buffers.** Each side is a read-only buffer of that side's
  text in the file's mode, so it has the file's colors, search, motions and
  folds. The right side of a working-tree diff can later be the file's own
  buffer, edited in place and diffed again as you type (IntelliJ lets you
  edit only the right); this phase keeps both sides read-only.
- **Aligned.** A side with fewer lines in a change gets filler rows under
  them, virtual lines (`VIRTUAL-TEXT.md`), striped, so both sides' unchanged
  lines are level, as nvim's filler lines and IntelliJ's "align changes"
  are. The alignment is a model of its own (`DiffAlignment`), from
  `util/Diff`'s edits: for each row of either pane, the line or the filler on
  the other side. It is what the scroll lock, the track and the folds read.
- **Scroll lock.** A scroll of either pane, by key, wheel or the smooth
  scroll (`Display.scrollByPixels`), moves the other to the same row through
  the alignment, a pixel offset and all, so they never drift. The carets are
  bound too (vim's `cursorbind`): moving to a line on one side moves the
  other's caret to its row. A toggle (`s`) unlocks them, as IntelliJ's
  Synchronize Scrolling does.
- **Highlights.** Removed lines on red at the left, added on green at the
  right, a changed pair on blue on both, its words or characters that differ
  stronger (`WordDiff`, as the status buffer and the hunk preview do).
- **The track.** A narrow component between the panes, not an editor,
  painted from the two panes' scroll positions and the alignment: for each
  change on screen, a band from its rows on the left to its rows on the
  right, two cubic Béziers for its edges, filled with the change's color and
  outlined a shade darker; a change with no lines on one side narrows to a
  point at the line it would go after. Antialiased, in `UIScale`, and only
  the changes in view. Later, as IntelliJ's chevrons, arrows on a band to
  take the change from one side to the other.
- **Folding unchanged runs.** Runs of unchanged lines longer than twice the
  context (`vcsDiffContext`, 3) fold on both sides together, "… 120 lines",
  as nvim's diff folds and IntelliJ's "collapse unchanged fragments"; Tab on
  a fold opens it on both sides.
- **Keys.** `]c`/`[c`, Ctrl Alt N/P and `n`/`p` (and F7/Shift F7 outside vim)
  go by change on either side (`Mode.getChangeStarts`); Space and Shift
  Space page both; `=` back to unified; `s` scroll lock; `w` whitespace
  ignored or not; Enter visits the file at the line.
- **Shared.** The panes, alignment and track take any two texts, so svn's
  BASE, a stash or two commits work the same, and the merge view later is
  three panes and two tracks of the same parts.

### Blame in the gutter (`vcsBlame`, toggles)

- `git blame --porcelain --incremental --contents - <file>`, with the
  buffer's text on stdin, so lines added since are "not committed yet". It
  fills in as it streams and runs again after a save.
- A gutter column as wide as its widest entry, `2026-10-08 kkrouse` by
  default (`vcsBlameFormat`: date, author, short hash, summary). An
  annotation shows on the first line of each run of the same commit, and the
  lines after it get a thin bar in the commit's color.
- Brightness by age, as IntelliJ does it: the commits in view are ranked by
  date and mapped onto 8 steps, from the text color down to the comment
  color, so the newest are brightest whatever the file's age. Ranking, not
  absolute age, keeps an old file from being all dim.
- Hover shows the commit's hash, author, date and full message. A click (or
  `Enter` on the gutter in vim, `<leader>gb` to toggle) opens that commit in
  the log, with the file selected. `vcsBlameParent` re-blames at the commit's
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
  - `vcsCodeAuthors`, off by default; files over `vcsCodeAuthorsMaxLines`
    (10,000) get none, since blame on them is slow.
  - It is a virtual line `ABOVE` the method's first line
    (`VIRTUAL-TEXT.md`), so it needs that phase. Other code vision (usages,
    tests) can share the line later.
- `Display` needs a gutter column provider for this: an interface that
  gives a width and paints the column for a line. Change marks, line numbers,
  color swatches and blame then become providers in a list instead of special
  cases.

### Log (`vcsLog`, `l l` from status)

**It takes the whole frame**, as magit's and Neogit's do. `vcsLog` saves the
frame's window layout (below), then shows only the log and its details. `q`
closes the log and puts the layout back: the same splits and weights, the
same buffers, and each window's caret and scroll.

**The quick log** (`vcsLogQuick`, `l q` from status, `<leader>gl`) opens the
list alone in a split under the current window, with no details. `Enter`
(or a double click) on a commit grows it into the full-frame log with that
commit selected. `q` in the full log then goes back to the layout from
before the quick log, not to the quick log.

**Details at the right**, the one layout of the unified diff:

```
┌ branch: master ▾  …  ┬──────────────┐
│ ▌ ●─┐ icons: draw …  │ M Display.java│
│ ▌ │ ● build: bb ru…  │ A Icons.java  │
│ ▌ ●─┘ Merge pull r…  ├──────────────┤
│ ▌ ● │ web: webShowI… │ @@ -81,6 +81 │
│ ▌ │ │ xml: splitAt…  │ -  private … │
│ ▌ ●─┘ vim: list ex…  │ +  private … │
└──────────────────────┴──────────────┘
```

The files are a small window on top (a third of the height) and the diff
fills the rest. That is close to magit's: `Enter` on a commit there shows
a revision buffer in another window, its message, a diffstat of the files,
then every file's diff. Here the files are a window of their own, so the
diff shows one file and the files stay in view while it scrolls. `=`
switches to the diff side by side across the bottom.

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
- **A row**, left to right: the repository's strip (several repositories
  only), the graph, the subject, ref badges (branches, tags), the author and
  the date. The subject takes what is left. As the window narrows, first
  the tags go, then the remote branches (local branches and HEAD stay),
  then the author shortens to initials, and then the subject is cut with an
  ellipsis. The graph, the strip and the date never go. A hover over a cut
  row shows it whole. The text of a row is laid out for the window's width
  when the window is resized, as a directory listing's is.
- **Loading**: `git log --topo-order --format=%H%x00%P%x00%an%x00%ae%x00%at%x00%D%x00%s -z`,
  in pages (`--skip` and `--max-count`, from the commits the first page
  started from). The first 2,000 commits are shown
  at once and more are read as the caret nears the end. All refs are loaded (`--branches --remotes --tags
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
- **Several repositories**, as IntelliJ's log shows a project's roots
  together: one list of every repository's commits, each row marked with
  its repository.
  - **Which.** `vcsLog` shows the repositories the status would
    (`VcsStatusMode.repositoriesFor`): all of the project's when it has
    several, from a file or from a status buffer of them all. From a status
    buffer of one repository, or with a prefix argument, that repository
    alone, which is the repository filter set to it. `vcsLogFile` is always
    the file's own repository.
  - **The order.** Each repository's log is read in its own topological
    order, then merged by commit time (committer time, `%ct`): the next row
    is the newest of the repositories' next commits, ties going by the
    repositories' order. A repository's commits keep their order among
    themselves, so its graph holds. This is IntelliJ's "by commit date"
    across roots; within one repository the order stays topological.
  - **Paging.** Each repository is read a page at a time on its own. A
    commit is only shown once every repository with more to read has a
    commit read that is no newer, so a page arriving later never puts a
    row above one already shown. A repository that runs low is read
    again; one that ends drops out. The reads run in parallel, one git
    process per repository.
  - **The strip.** A colored strip (`▌`) at the row's left, in the
    repository's color (`Repositories.colorIndex`, as the status buffer's
    headings and the buffer list's strips have it). A hover shows the
    repository's path. `vcsLogRepositoryNames` (off) widens the strip into
    a column with the repository's short name, as IntelliJ's "Show Root
    Names".
  - **The graph.** Lanes come from one pool, as for one repository: an
    edge never joins two repositories, since a commit's parents are in its
    own, so no bands of columns are needed. A lane's color is the
    palette's, its strip the repository's.
  - **A commit's repository.** `LogCommit` carries its repository; the
    files, the diff, Enter, `O` and the refresh's caret go by the commit's
    repository and id together, as two repositories can hold the same
    commit (a fork cloned twice, a vendored copy).
  - **Filters.** The repository chip (`-r`) is a checklist of the
    repositories, and a click on a row's strip is "only this one". A
    branch filter applies in each repository that has the branch, and the
    others drop out while it is set, as IntelliJ does. A path filter goes
    to the repository that holds the path.
  - **The rest.** The title is "git log: 3 repositories". The refs tree in
    the sidebar has a node per repository. The quick log shows them all too.
  - **Tests.** The merge on synthetic logs: interleaved times, equal times,
    one repository empty, one far older, one ending first; paging that
    never moves a row once shown; the same id in two repositories.
- **Filters**: repository, branch, author, path (the current file for
  `vcsLogFile`), date and text (`--grep`, and `-S` for a change). Each runs
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
  - **Keyboard.** `l` opens the log's filter popup, as magit's `l` in its
    log does (`f` is the next file). Each filter has a key, as magit's
    arguments do (`-b` branch, `-a` author, `-p` path, `-r` repository, `-d`
    since, `-g` grep, `-s` pickaxe): in the popup it opens its field there,
    and in the log the field over its chip, with the same fuzzy list
    (`vcsLogFilter author`; `-` is a prefix key map, which a mode's vim
    key map can now have too). `x` clears every filter.
  - **Enter in a field** takes the candidate highlighted, as fuzzy pickers
    do (VS Code, fzf, Telescope, vertico), or the text when none is. The
    first match is highlighted, and the first candidate when the field
    opens blank, so Enter in an unset filter's field picks it; erasing a
    value that was set leaves none highlighted, so Enter clears it, as an
    editable combo box does. Down highlights one again. A candidate that
    is the text comes first, then those that start with it, then those
    with it inside, so `main` takes main, not domain-x. A list still being
    read, as the names when the status's popup opens, is asked for again
    until it comes. In the bar, the key is shown in
    bold beside each chip (`-b branch: master ▾`), so the keys can be
    learned from it.
- **Sidebar: refs.** The sidebar's lower half shows the repository's refs,
  as nearly every client has them beside its log (IntelliJ's Branches pane,
  Sublime Merge's locations, GitKraken's left panel, Fork, Tower, and
  magit's refs buffer): HEAD, then local branches, remotes by remote, tags
  (newest first, the newest 100 unless widened, as Tower limits them), and
  stashes (phase 7); a node per repository when there are several. Names
  with a slash are folders (`feature/x` under `feature`), as IntelliJ
  groups them. The branch checked out is bold, and each local branch has
  its upstream's ahead and behind after it (`↑2 ↓1`), or "gone" when the
  upstream was deleted, as Fork and magit show. It is read with one `git
  for-each-ref` (`%(upstream:track)`), not from the log's decorations, which
  cover only the pages read so far.
  - **Click** a ref: the log's caret goes to its commit, reading pages
    until it is loaded, as Sublime Merge reveals a ref in its graph.
  - **Enter or a double click**: the log filtered to that ref, the filter
    bar's branch chip set, as IntelliJ's branch filter and GitKraken's solo
    do (`LogQuery.revisions`). The chip's `×` clears it.
  - **Typing** narrows the tree, as the filter fields of Sublime Merge,
    Tower and GitKraken do.
  - **Actions** on a ref (checkout, delete, merge, rebase) come with phase
    7's branch popups; until then the tree is for finding.
  - **One tree per log.** The list, files and diff share it, so the
    lower half doesn't change as the focus moves between the panes.
- **The selected commit**: a tree of its files (status letter, icon, path)
  over the diff of the selected file, at the right of the list.
  The focus stays in the list, as in magit and IntelliJ: `Up`/`Down` (or
  `j`/`k`, `n`/`p`) move through the commits, and the files and the diff
  follow, 100 ms after the caret stops so that holding a key does not run
  git for every row. From the list, `Space` and `Shift Space` scroll the
  diff (magit's `SPC`/`DEL`), and `Alt Down`/`Alt Up` select the next or
  previous file without leaving the list. `Tab` moves the focus list →
  files → diff → list, and in the files the same holds: moving there updates
  the diff, and the focus stays. `Enter` in the files focuses the diff.
  The diff is a `DiffMode` buffer, and folding a hunk works as in the
  status buffer.
- **To the file**: `Enter` (or a double click) on a diff line opens the
  working-tree file at that line. The line is mapped through `git diff
  <commit> -- <file>`, adding up the hunks above it, as IntelliJ's "show in
  file" does. `Shift Enter` opens the file as it was at that commit,
  read-only (`git show <commit>:<path>`, named `File.java @ 1a2b3c4`).
- **Other logs**: `vcsLogFile` (the current file, `--follow`), `vcsLogLine`
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
  `vcsConflictNext`/`Previous` (`]x`/`[x` in vim) and
  `vcsConflictOurs`/`Theirs`/`Both`/`Base` (`u`/`l`/`a`, as smerge has them,
  after the simple-mode prefix, and `<leader>co`, `ct`, `cb` in vim).
  `merge.conflictStyle=diff3` (or `zdiff3`) is suggested so there is a base
  to show.
- After the last conflict is resolved and the file is saved, the status bar
  offers `s` to mark it resolved (`git add`). Deleted-by-them and the like
  get `vcsConflictKeep`/`vcsConflictDelete`.
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
  parts would hold for svn, whether duplicate code has been introduced,
  whether unnecessary or overly complicated abstractions have been added;
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
   - Refresh is `Ctrl R` in both edit modes (`r` is kept for rebase).
   - Ctrl Alt G opens the status directly, not as a prefix yet;
     `<leader>gg` comes from rows of the vim key map with `<leader>`, bound
     with the vimrc's mapleader before the vimrc's own maps.
   - Left for later phases: old and new line numbers in the gutter (phase
     4's gutter columns), the `Parent:` line (7), untracked files' contents
     and conflicted files' diffs (9). Enter on a commit shows `git show` in
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
     buffer tells its `SignsUpdater`, one of its `Buffer.Follower`s, of
     each edit and each look at its version control state, and the change-mark column
     draws a buffer's signs in place of j's marks when it has them. It is
     that one column, not yet general column providers: blame's column
     (phase 8) makes them.
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
   - Left for phase 10: outlined marks for other changelists, and moving a
     hunk to one from the popup.
5. **Done. Log.** `WindowLayout` capture and restore, the full-frame log and the
   quick log, the list buffer with streaming load, rows that fit the width,
   details at the right, to-file and at-revision, the filter
   bar and the filter popup, then the graph
   (lanes, then painting), then several repositories (the merge by commit
   time, paging per repository, the strip and names, the repository
   filter). The graph gets its own tests, of the lane layout on synthetic
   histories (octopus merges, criss-cross merges, 500 parallel branches, a
   root commit in the middle), apart from painting.
   Built so far:
   - `WindowLayout` (core): a frame's split tree, each split's shares, and
     each window's buffer, caret and top line; `capture` and `restore`
     (`Frame.restoreLayout`, through `EditorPane.capture`/`restore`), a
     buffer closed meanwhile left out. The log builds its own layouts with
     it too. `EditorPane.place` shares space by the weights, so a restored
     split keeps its shares.
   - `vcs.log`, shared: `LogCommit`, `LogRef` (typed, so the shared layer
     never reads git's decorations), `LogQuery`, `CommitChange`, and the
     backend's `readLog` (a page: `--skip`/`--max-count` over
     `--topo-order`, which gives the same order each time; not a stream),
     `readMessage`, `readCommitChanges`, `readChangeDiff` (against the first
     parent; a root's with `show`), `readDiffToWorkingTree` and
     `readFileAt`. Git's are `GitLog` (`--decorate=full`, so `feature/x` is
     a branch and `origin/x` a remote).
   - `LogRow` lays a row out for a width as the plan says; `LogBuffer`
     reads pages of 2,000 as the caret comes within 200 rows of the end and
     lays its rows out again on a resize (`Buffer.windowResized`). Its
     details are two buffers of `StatusLine`s, so they draw as the status
     buffer does: `LogFilesBuffer` (the commit's fields and message, then
     its files) and `LogDiffBuffer` (one file, its hunks folding with `=`
     and vim's z keys). They follow the caret 100 ms after it stops
     (`Buffer.afterCommand`, from `Editor.commandRan`).
   - The quick log is `Frame.openBelow`; its layout is taken before the
     split, so `q` from the full log it grew into goes back to before it.
   - Keys, after looking at magit, Neogit, fugitive, lazygit and tig: as
     the status buffer's where they mean the same. Tab opens or closes the
     diff's hunk (Shift Tab all of them), not a pane: the panes are windows,
     and j's window keys (Alt O, vim's Ctrl W) move between them. n and p
     are the next commit, file or hunk in the pane, as magit's sections.
     Space, and Shift Space or Backspace (magit's DEL), scroll the diff from
     any pane; the popup has d and u. `]c`/`[c` go by hunk in a diff, the
     status buffer's too (`Mode.getChangeStarts`). Vim mode let a shifted
     key wait for its typed character, which drops the Shift; a mode's
     binding by key code, as Shift Space, now runs first. A transient's keys
     are typed text, so Enter and Tab can't be in the popup: its keys are
     letters that are the log's own keys too (o opens the file, O the file
     as of the commit, f/F files, d/u scroll), as `Mode.getTransient` asks,
     and a test holds them to it. Each pane's hint row shows what Enter
     does there.
   - Enter on a diff line, and Shift Enter's file at the commit, close the
     log and open the file in the window the log was opened from.
   - Filters: `FilterBar` (transients, shared) is a strip in the window's
     `north` panel under the location bar, shown when the mode's
     `getFilters(editor)` gives options. Each filter is a popup `Option`
     with a `Binding`, so the bar's chips and the popup's items set the
     same value; `OptionField`, the field and its fuzzy list, came out of
     `TransientPopup` for both. The log's (`LogFilters`) are bound to its
     `LogQuery`: setting one reads the log again from the first page, and
     a page read for the query before is dropped (`LogBuffer`'s
     generation). Branches and tags (`readRefs`, `for-each-ref`) and the
     tracked paths with their directories (`readPaths`, `ls-files`) are
     read in the background when the log opens and on a refresh; the
     authors are those of the commits read. A path that is a file follows
     its renames. The bar is over the list only, not the files or diff.
   - The status buffer's `l` popup has the same filters, unbound, as
     Neogit's log popup: set first, they are the popup's arguments
     (`vcs.log.open`, kept per buffer and session, `Ctrl S` defaults),
     and `vcsLog`, `vcsLogQuick` and `vcsLogFile` start from them (a file's
     log keeps its path). They are kept per repository (the popup's id is
     `vcs.log.open:<root>`), so one repository's branch never filters
     another's log.
   - The filters' keys (`l`, `-` and its letters, `x`) are the list's alone:
     the files and diff panes have a key map without them
     (`Mode.getKeyMap(Buffer)`, `getModalKeyMap(Buffer)`), so vim's `l` and
     `-` move there, and `x` can't clear the filters by habit. The names the filters offer are a repository's
     (`LogNames`: refs, paths, and the authors of its newest 500 commits
     and of every log read), read when the popup opens.
   - The graph: `LogGraph` (shared) gives each commit a `Row` as pages are
     read, the lanes in between kept, so a page added later continues the
     rows before; a lane's color comes from the commit it starts at.
     Tested on histories by hand (a merge, a second head, lanes moving
     left, an octopus, pages apart) and on 200 random ones: each row's
     lanes out are the next row's lanes in, with no gap. `LogGraphColumn`
     paints a row in the gutter, before the text: `GutterColumn`, a
     buffer's column there (`Buffer.getGutterColumns`), which
     `Display.getGutterWidth` counts, so clicks, columns and the rows'
     width leave room for it; it is the first of the gutter column
     providers the blame column needs; `Display` lays a buffer out again
     (`windowResized`) when its gutter's width changes. Lanes past 16 are
     cut off at the right. `git log --parents` rewrites a path's parents
     to those it lists, which the graph takes (`graphParents`); the
     commits' own, for their diffs, come from `rev-list --no-walk
     --parents --stdin`. An author, message or change filter, or
     `--follow`, leaves commits out that nothing rewrites, so those logs
     have no graph and no column. A new lane takes a color none of the
     lanes beside it has. A lane is named for the ref at its
     first commit, else the branch its merge's subject names ("Merge
     branch 'x'", a pull request's), else that commit; the column's tool
     tip (`GutterColumn.getToolTipText`) gives it over a lane. Branch
     badges (HEAD's, local, remote) take their row's lane color through
     `LogFormatter`'s lane formats; tags, and every badge when there is
     no graph, keep the kind colors.
   - Several repositories: `LogScope` is what a log covers, one
     repository or the status's several, with `base`, the directory above
     them, that a path filter is from; `queryFor` gives each repository its
     query, or null when the repository (`-r`) or a path in another leaves
     it out. `LogMerge` reads each a page at a time, in parallel, and lists
     the newest of their next by committer time (`%ct`), ties by their
     order; nothing is listed while a repository with more has none read,
     so a row never moves. Tested on synthetic logs, with pages read in
     random order. `LogCommit` carries its repository: the details, `o`,
     `O` and a refresh's caret go by it (`isSame`); the graph keys lanes
     by repository and id. The strip and, with `vcsLogRepositoryNames`,
     the name are the gutter column's, before the graph; its hover is the
     path and a click (`GutterColumn.clicked`, a hand over it by
     `isClickable`) toggles `-r`. A `Pick` can give each candidate a
     color (`swatch`), a box before it in the list: a branch's lane's, from
     the rows read, and a repository's. The filters'
     names are the repositories' together (`LogNames.refs`/`paths`/
     `authors` of a scope), paths under each one's prefix. The popup's
     arguments are kept per scope. No prefix argument for one repository
     alone: j has none; `-r` does it.
   - The full log as one view: `FrameView` (core), a buffer that takes
     the frame with buffers of its own, switched to and away from as one.
     `Frame.switchToBuffer` goes to one with `showView` (the windows before
     kept in it, or taken over from the view shown) and away from one with
     `leaveView` (they come back; the view stays), beside mail's pair,
     which binds one window under another and is unchanged. Each window's
     view is saved first, so `WindowLayout.Window.of(buffer)` lays a pane
     out again where it was (one `Window.of(editor)` for `EditorPane` and
     the log). `Buffer.getChildren` (a pair's second half by default) puts
     a buffer's parts under it in the buffer list, a real tree now, its
     closed parents kept; a pane is not primary, so going through the
     buffers passes the log once. The status's diff beside it will be one
     too.
   - The review's fixes and the details' badges: filters that leave every
     repository out show none, not "Reading…" forever (a merge of no
     sources lists nothing at once); a name shared by two repositories is
     their paths' (`LogScope.name`); paths are by each repository's
     prefix, from the project's directory (`scopeOf` passes it), one
     outside under its name, so `base` never runs out; several have a key
     of their own; a column's click is taken once, a double click's second
     press swallowed (`Dispatcher`, `GutterColumn.isClickable`); a jump
     runs `afterCommand`, so the details follow; `-T` with a branch set
     is kept and reads nothing again; branch colors come from a map the
     rows fill (`LogBuffer.laneColorOf`). The details' first line is
     badges (`LogDetailsFormatter`, a `VcsStatusFormatter` subclass): the
     repository's in its color, for several, then the refs' as the list's.
   - Tags and long edges: the log reads `--branches --remotes HEAD`, and
     `--tags` only with `-T` (`LogQuery.tags`, `LogFilters.TAGS`, a
     switch in both popups, `vcsLogToggleTags` in the list): cvs2git makes
     a commit of its own for each CVS tag, and efx's 226 of them, each far
     above its parent in topological order, kept up to 61 lanes open. A
     lane longer than `LogGraph.LONG` (30) rows is a stub at each end with
     an arrow (`Edge.arrow`, `jump`): a page's rows tell near from far,
     and a lane whose commit isn't read leaves once it has passed 30 rows;
     it comes back a row before its commit, or at it if read too late.
     A click on an arrow goes to the other end (`LogBuffer.jump`, reading
     on to it). efx's widest row: 61 lanes before, 2 now, 6 with tags.
   - The status's diff beside it, a frame view too. `ViewPane` (shared) is
     a view buffer that is part of another: listed under its owner, shown
     in its frame view, the log's panes' fill and place; one killed on its
     own asks its owner (`FrameView.partKilled`), so the log closes whole
     and the status closes only its diff. `DiffPane` is one file's diff
     with folding hunks, out of `LogDiffBuffer`: the log's diff extends it,
     and so does `StatusDiffBuffer`, keyed as the status's lines, so a hunk
     at the status's caret is found in it. The status follows its caret 100
     ms after it rests, as the log does, reading the diff of a file it has
     closed. `D` opens and closes it (`vcsStatusDiff`, and
     `vcsStatusDiff=right` opens a new status with it), Space and Shift
     Space scroll it from either side, and in it Enter, Tab, `n`/`p` and
     `q` are the status's own commands. Found on the way: a frame view
     left puts back its windows with its own buffers where they are now,
     not where they were when it opened (the status was in a window then);
     `WindowLayout.Window.of(buffer)` falls back to the view's kept line
     numbers when the buffer's lines were replaced, as a status refresh
     replaces them; `Editor.show` returns the window a frame view's leaving
     put the buffer in. The review's fixes: the diff follows the caret for a
     reason (`StatusDiffBuffer.Follow`): moved to another file or hunk, it
     goes there, reading a closed file's diff; a redraw shows it again in
     place, reading nothing; a refresh reads it again in place, so a closed
     file's change shows though the status's lines don't. `D` lays out a
     diff open but not laid out, as after its window was closed by hand.
     Space and Backspace move as they would with no diff. The diff has its
     own popup. A buffer in two windows of the layout before keeps their
     places. A window closed saves its place (`Frame.unsplitInternal`).
     `Editor.windowShowing` is the one lookup of a buffer's window.
   - Dir mode's filter bar: `Mode.getFilters` gives any popup item, and
     dir mode's are its popup's Filters group, so the chips and the popup
     share their keys and bindings. The bar draws a `Switch` as a chip
     that turns over on a click (show hidden), and a `Cycle`'s always
     with its value, set when it isn't the first (the sort), its × going
     back to it. `dirLimit` types over the limit's chip
     (`FilterBar.choose(name)`, a `Field` keeping a history), the dialog left for a window
     with no bar. A change by key, popup or chip lays out every window's
     bar on the listing again. `x` clears the filters in every view with a
     bar, the log's and dir mode's (`dirClearFilters`), so dir mode tags
     with `t` in vim too; `a` opens a new file, written when saved, and `A` makes a directory (vim's `n` and `N` left to search)
     (`dirNewFile`, `dirNewDirectory`). A view closing puts back the
     windows from before without its own buffers, which they hold when it
     was in a window before it took the frame (`Frame.leaveView(view,
     closing)`): the fuzz's one intermittent failure. Hidden names are
     dotfiles, `filenameCompletionsExcludePattern`'s and what git ignores
     (`HiddenNames`), left out unless `dirShowHidden`; shown, muted, and
     italic for the ignored. Every ignore check goes through `vcs.Ignores`,
     the backend's rules for a repository (`VcsBackend.ignores`): git's
     `GitIgnores` (moved from `ProjectFiles`, with `!` and git's order of
     precedence) and svn's `SvnIgnores` (`svn:ignore`, `svn:global-ignores`,
     the config's `global-ignores`); the project walk, find in files and
     dir mode use it. Neither tells a tracked file from an untracked one.
     What is in an ignored directory is ignored; a listing or walk that
     starts in one gets no rules (`Ignores.holding`, `Walker.enter`), as
     git wouldn't list it. A backend with no rules gives null, so an
     enclosing repository's apply. svn's are read in the background with
     `ProcessRunner`; the event thread doesn't wait for them, and the
     listing is made again when they come (`Ignores.whenRead`). They are
     kept while being read and read again 10s after, never sooner, so a
     slow read can't loop; only the top of a pre-1.7 working copy, every
     directory of which has a .svn, is its root. Reverse
     and directories first reorder what was read (`rearrange`), and j's own
     listing restyles without reading; `a` opens a new file as a buffer,
     as `openFile` does, making the directories it is in, and `A` makes
     one with `File.mkdirs`, leaving vim's `n`/`N`.
     Keys aligned across the three views: `-` then a letter sets a
     filter or a view option in the directory and the log, as magit's
     arguments (dir mode's up is Backspace, netrw's `-` given up); `x`
     clears the filters in both, and the status discards with `x`, as
     magit and Neogit; each view has one popup, `?`, the log's with its filters
     first (`l`/`a` freed). Shift and Ctrl Enter open the entry at the
     caret in a split and a vertical split (`dirOpenFileInSplit`,
     `dirOpenFileInVsplit`; `dirOpenFileAndKillDirectory` unbound).
     The log's `-t` steps its dates through age, locale, iso and long-iso
     (`util.TimeStyle`, shared with dir mode's `ListingStyle`), in the rows
     and the details' Date, in the popup's View group (`vcsLogTimeStyle`).
     Dir mode's lines are read by their entries, not by the pattern ls's
     dates match: `DirectoryEntry` keeps where its size, date and name are
     (`nameColumn`, `sizeColumn`), so the age style, which no date pattern
     finds, writes over ls's date (`writeAge`, from a long-iso date with
     GNU's ls, else ls's own, read back by `ListingStyle.parseDate`).
     Dir mode's View group, after `-`: reverse (`=r`) and directories
     first (`=d`) are j's, ordering the entries, so they work in every
     listing; human sizes (`=h`) and the time style (`=t`) are j's in its
     own format (`ListingStyle`), and `ls -h` and GNU's `--time-style`
     with ls. The internal format now writes ls's columns, a mode, a size
     and a date in a time style, so one pattern finds the name in both;
     the formatter colors each permission, the size and the date.
     In the log, vim's `a` picks a filter, as it limits a directory, and
     `vcsStatus` (`<leader>gg`) opens the status of the log's repositories.
   - Collapsing linear runs (`-l`, `vcsLogCollapseRuns`): a run of two or
     more plain commits (one parent, one child read, no merge, no ref) one
     after another down a lane is a fold of hidden lines, headed by the
     commit above (`LogBuffer.Run`). Every commit's line stays in the
     buffer; folding a run hides its lines, the head's row shows
     `… N commits` after its subject and badges (`LogRow.Part.COUNT`, which
     never goes, as the date doesn't) and its
     lane is dotted below the head and above the commit after (`dottedLane`).
     Only rows nothing but their lane passes straight through can be in a
     run (`Row.straight`), so the graph is whole with them hidden. A run's
     state is its lines' hidden flags: a page joins new lines to it as it
     is (`findRuns`), and the caret landing in one, as a search's does
     through `Editor.updateDisplay`, opens it (`syncRuns`). The mode's fold
     hooks answer the gutter and za/zo/zc/zM/zR; `Buffer.foldsChanged`
     tells the gutter's marker cache, as no edit does. The log keeps the
     default and the runs flipped from it, those of runs gone dropped once
     every commit is read.
   - `-r` is a checklist (`Pick.several`): `OptionField`'s Enter or a
     click checks or unchecks a candidate, its field kept open, and the
     value (the names joined by ", ") is set 300 ms after the last, or as
     the field closes, as each set reads the log again. Nothing is
     highlighted until typed or moved, so Enter alone closes it; a double
     click's second is no click (`CompletionPopup`). The query keeps the
     names in the log's order (`LogQuery.repositories`), every one being
     none. The filter bar's open field is laid out as its chip, its × still
     there, which closes the field first. The strip's click is still "only
     this one".
   - The badges' smaller font: `TextStyle.SMALL` draws a format in the
     text's fonts at 85%, centered on the line (`Display.baseline`, for
     every place text is drawn), measured as drawn, so the caret, clicks and
     chips follow. The details pane's badges are small too. The gap before
     the author is `LogFormatter.GAP`, styled as the text with no entry of
     its own, which ends on its column (`Formatter.endsOnColumn`), so the
     author and date line up as with no badges: the run is as wide as takes
     its end to where the text up to it would end all plain, spread evenly
     over its columns and never broken at the caret, and `formatLine` fills
     the whole line so that a run is always measured whole.
   - The files pane keeps a fixed share of its column: fitting it to each
     commit's text was tried and dropped.
   - Staging from the status's diff: `s`, `u` and `d` in the pane (its keys,
     popup and hints) run `StatusActions` on the pane's lines, which are
     drawn in the status's section (`StatusRenderer.renderFiles` with a
     section, `DiffPane.show` passes it; the log's have none, so nothing
     stages there), writing through the status (`VcsStatusMode.owner`);
     `StatusLine.head` finds a diff line's hunk in either buffer. The pane
     is stale from a write the status starts (`VcsStatusBuffer.writes`)
     until a follow begun after it shows (`StatusDiffBuffer.isStale`), and
     staging there waits; with no file at the status's caret, a refresh
     reads the pane's own file again, or clears it once its section has
     none. After one, its caret goes to the nearest line still there
     (`DiffPane.nearest`), never the file's heading while a hunk is left.
     Vim's hunk keys there (`<leader>hs`, `hu`, `hr`: `vcsStageHunk`,
     `vcsUnstageHunk`, `revertLines`) hand off to `StatusActions.run`
     when `StatusActions.takesHunkCommands`.
   - The refs tree in the sidebar (`LogRefTree`, the log's own, which
     `VcsLogMode.getSidebarComponent` gives each pane and keeps on its
     view): read with one `for-each-ref` per repository
     (`VcsBackend.readRefTips`, `LogRefTip`: an annotated tag's commit, its
     date, `%(upstream:track)`), and HEAD first when detached. Its shape is
     `build`'s, a pure function: HEAD, Branches, Remotes by remote and Tags,
     a name's parts before a slash folders, the newest `TAGS` tags and a
     "more" node, a node per repository for several. A click reveals the
     ref's commit (`LogBuffer.reveal`, through `jump`); Enter or a double
     click sets the branch filter; keys typed narrow it, the sidebar's label
     saying so (`Sidebar.setBottomLabelText`). One tree per frame
     (`LogBuffer.refTree(frame)`), a component having one parent; the refs
     are `LogNames`' (`tips`, one `for-each-ref` for the tree and the
     filters' names, 30 s fresh, one read at a time, callbacks when done).
     A rebuild keeps what was open and selected by the nodes' names, and a
     narrowing what was open before it. A click asks the backend first
     (`logHas`, `git log --format=%H` with the query's selection) and reads
     no page for a commit the log leaves out, then gives the keys back to
     the log; Enter filters by the whole ref name when the short one is
     shared, and to the ref's repository for several. Keys typed with
     Shift or AltGr narrow; the sidebar's key handling is
     `SidebarTree.sidebarKeyPressed`, shared with the tag trees.
     The branches are by name or, with `-o` (`vcsLogRefsOrder`, a view
     option), newest first; folders have the file tree's icons; the newest
     25 tags are shown. The default branch (`LogRefTip.primary`: what a
     remote's HEAD names, `%(symref)`, origin's first, else a local main or
     master), then the one checked out, are first by their whole names, as
     GitHub Desktop's default branch and IntelliJ's favorites are.
   - The review that closes the phase, and its fixes: a jump reading on
     to a commit gives up when the caret moves or its window shows another
     buffer (`Jump.stands`); `logHas` asks `rev-list --not` whether the
     log's starting points reach a commit when no filter narrows it; the
     `-b` chip takes a branch by its whole name when a tag or remote has its
     name (`LogFilters.revision`); a failed `git log` says so, not "no
     commits" (`LogBuffer.failed`), and a failed read of a commit's files
     isn't "No files changed"; Enter in a long list finds the candidate that
     is the text past those listed; the authors are read in date order
     (`readAuthors`), not a walk of the whole history; two tests wait on a
     signal, not a sleep; a log's later pages start from the commits its
     first did (`readStarts`, `LogMerge.Source.starts`), so a commit, fetch
     or rebase between pages repeats or drops none.
   - Left open from the review: a frame view keeps one layout from before
     it (`FrameView.before`), so a log shown in two frames puts the first's
     windows back in the second when it is left there. A layout kept per
     frame would fix it, for the status's diff too.
6. **Done. Side-by-side diff.** `DiffAlignment` from `util/Diff` with filler
   rows, tested on its own (a change at the top and the bottom, an empty
   side, a file new or deleted, a change of only blank lines, CR LF); the two
   panes as buffers with filler virtual lines; the scroll and caret lock,
   by key, wheel and smooth scroll, tested headless on the rows; the
   highlights and word diffs; folding unchanged runs on both sides; the
   track's geometry (a band's corners and curves for a change, a pure
   function tested apart from its painting), then its painting; the keys;
   and the ways in, from the status and log panes and from a file; the log's
   layout with the list and details at the top and the panes across the
   bottom. It comes after the log, several repositories and all, so the log
   has its third layout; it needs only phase 4's `util/Diff` and virtual
   lines otherwise.
   - Progress: `DiffAlignment` (`mode/compare`), tested on its own: rows of
     the old line and the new or a filler, the changes, a line's row, the
     fillers after a line, the other side's line at a row. The panes
     (`mode/compare`, not tied to vcs, with `WordDiff`; `ViewBuffer` and
     `ViewPane` are in `org.armedbear.j`): `CompareBuffer`, the new side, is the frame view
     and `OldSide` its part, each a `ViewBuffer` in the file's mode with a
     `SideFormatter` over the mode's (`getLineBackground` by the row's
     kind) and its fillers as virtual rows (above the first line, or below
     one). A side's `getKeyMapForMode` lays its `q`, `n` and `p` over a
     copy of its mode's map (`KeyMap.copy`), the modal one too
     (`getModalKeyMapForMode`); `Buffer.getChangeStarts`, the mode's by
     default, gives `nextChange` the changes; `Buffer.scrolled`, from `Editor.updateScrollBars` after
     any scroll, scrolls the other side to the same height, the two being
     as tall row for row, and `afterCommand` takes the other's caret to its
     row, or to the line over a filler row only when that is in view.
     `vcsDiff` (`vcs/VcsDiffCommands`) shows a file against the index as the change marks
     read it (`readBases`, `Side` in the file's charset, the
     buffer's lines through `Side.ofBuffer`, compared by `keys`, which mark
     a last line with no newline), in the buffer's mode, and reuses a diff
     of the same file; `compareQuit` closes it. In vim, `n` and `p` are
     search's and `]c`/`[c` go by change. An empty side's blank line is a
     filler row; the fillers are laid again when the formatter resets. A
     changed line's words that differ (`WordDiff.diff`, against its pair)
     are `Formatter.getSpans`, which `Display` fills behind the text as it
     does search matches, keeping the mode's colors. The diff's folds are its only ones, as
     vim's `foldmethod=diff` and IntelliJ's diff editors (which turn the
     language's folding off) have it: `Folds`, the fold hooks `Mode` had, is
     what `Buffer.getFolds` gives the fold commands, the gutter and clicks,
     the mode by default and `CompareFolds` for a side. Each unchanged run but
     for `CONTEXT` (3) next to a change is a fold pair, hidden lines on both
     sides, "… N lines" after the line over them, opened and closed on both by
     the fold commands (Tab is `toggleFold` outside vim), vim's `z` keys,
     the gutter and a click on the count. Core's own ways of hiding and
     showing lines (a click past a line, the caret entering a fold, the
     unfold, fold region and fold methods commands) ask the buffer's folds
     first (`Folds.setFoldAt`), and leave a buffer whose folds are its own
     alone, so a side's lines are hidden only by its folds; a side's windows are laid out and restored on lines shown, as a caret in
     a fold opens it, and `Buffer.jumpTarget` takes `gg`, `G`, `:N`,
     `jumpToLine` and the ends of the buffer, there, to a closed fold's count
     line, but not an operator's `gg` or `G`, which takes in the fold. A change of mode
     keeps the folds that were open. The track is `CompareTrack`, the
     component `Buffer.besideDisplay` gives a window of the new side, at
     its display's left (`Editor.updateBeside`): `TrackGeometry.bands`, a
     pure function of the changes and each side's line tops, gives each
     change in view a band, its edges cubic Béziers, a side with no lines a
     point under the line before; the tops are walked once per paint as
     `Buffer.getY` counts them, less each window's scroll. It is repainted
     on any scroll of either side and on a fold opening or closing. By
     default the sides are not aligned, as IntelliJ's are not: no filler
     rows, and the scroll lock maps the place at the middle of one window
     to the other's middle through `ScrollMap`, piecewise linear over the
     changes, a change with no lines on one side taking in the line before
     it (or after it, at the top) so the other side does not jump; both at
     the top when either is. `compareAlign` keeps the fillers and the
     row-for-row lock. The place matched slides from the middle to the
     window's top over its first half window down, and to its bottom over
     its last, so that the ends are left smoothly; an empty side's blank
     line spans the other side. The other caret follows only to a line in
     view, as framing the other window would pull it from where the lock
     put it; a fold opening or closing moves the windows with the lock
     held, then scrolls the other side to match once (`resync`). Each
     side's line positions are `SideGeometry`, one walk with the rows
     `VirtualTextLayer.rowsAbove` and `rowsBelow` give, as `getY` counts
     them, kept until the
     side's first line, fold version or rows version
     (`VirtualTextLayer.rowsVersion`) changes, which the lock and the
     track read. A comparison of a file follows it, with the change marks
     on or off: a `Buffer.Follower` (`mode/compare/Following`, which the
     comparison follows) is told of each edit, undo or
     reload and of each look at its version control state, and a moment
     later reads its `Following.Base` (for `vcsDiff`, what is staged:
     `BasesCache`, as the change marks do, kept until the state is looked
     at again, and not when git could not say; for `diff FILE`, the file,
     kept until its time or size changes, which the frame's activation and
     the comparison's showing check) and diffs on a worker thread, then shows it, only the last asked for;
     the first time too, read and aligned in the background
     (`Following.Show`); a file with change marks shares their
     `BasesCache`, so what is staged is read once for both;
     not while no window shows it, until one does. A side whose text is the
     same keeps its lines and their parse, its open folds stay open where
     they overlap the folds before, the other side scrolls to match it, and
     each window keeps its caret, selection and scroll. It stops when the
     comparison closes or the buffer is saved as another file. `diff
     FILE` compares the buffer with another file the same way. In the
     status, `=` (`vcsStatusDiffView`) switches the diff beside between
     unified and side by side under the status, or opens it as last shown:
     side by side is a `CompareBuffer` part of the status's view
     (`CompareBuffer.embed`: the status its `ViewBuffer` owner, as a
     pane's, and `=`, `q` and Enter over its sides' keys), the status laid
     out above its two panes. The unified diff stays, unshown, as what
     follows the caret, and the status shows the file it shows: what is
     staged against HEAD, else the file's open buffer, through
     `Following`, or the file, against what is staged. The focus stays in
     the status or the diff, and the diff at the file's line
     (`CompareBuffer.place`, `DiffPane.placeAt`). The log has the same
     through `SideBySide` (`vcs/status`), which both share: the comparison,
     the file it was asked for and shows, and where it goes. `=` there
     (`vcsLogDiffView`) lays the list and the files over the two sides
     across the frame, `D` doing nothing, and is kept in the session
     (`vcsLog.sideBySide`); the files pane asks for the commit's file
     (`LogBuffer.compareChange`, `readFileAt` at the first parent and at
     the commit, read at once) in place of the unified diff, which is read
     again on `=` back. `s` (`compareScrollLock`) unlocks the sides, the
     scroll and the carets, and locks them again matched to the side in
     use; `w` (`diffIgnoreWhitespace [trailing|amount|all]`) ignores
     whitespace, or not, in every diff: `util.Whitespace`, git's
     `--ignore-space-at-eol`, `-b` or `-w` (`GitCli.ignoring`), on or off
     and which until j exits (`DiffWhitespace`), from the
     `diffIgnoreWhitespace` preference, each open buffer that shows diffs
     told (`DiffWhitespace.Listener`). Git's diffs shown take it, passed
     to the backend's reads (the status's and the log's, read again on a
     change, and a recent commit's), and never a patch's; a comparison
     aligns its sides on their lines as git would
     (`DiffAlignment.of(Side, Side, Whitespace)`), in the background, one
     not shown once it is, and one worked out before the change again
     once it is shown. A hunk or lines are not staged while whitespace is
     ignored. In vim, where `w` is a motion, they have no key; the
     toolbar and the Mode menu have them. The status, the log, a
     comparison and a commit message each have a toolbar and a Mode menu
     of their commands: a view's for each of its parts
     (`FrameView.getToolBar`, `populateModeMenu`, its mode's by default),
     so that a comparison's sides, in the file's mode, have the
     comparison's, or the view's they are part of. A mode's toolbar is
     made once per frame (`Frame.keep`; the sidebar's trees are one per
     frame too, `PerFrame`) and its buttons enabled, pressed in and shown as they say once
     a command or a change is done (`ToolBarButton.enabledWhen`,
     `selectedWhen`, `visibleWhen`; `Frame.updateToolbar`); Stage, Unstage
     and Discard are worked out once for each update
     (`ToolBar.perUpdate`). A button's
     or a menu item's command runs in the window its view says
     (`FrameView.windowFor`, `run`): a diff's in the diff, unified or a
     side (`CompareCommands.windowFor`); from a side, the view's own `vcs`
     commands it has no key for in the view's window (`=` and `q` stay in
     the side, `CompareCommands.sideRuns`), and in the status those the
     diff beside has no keys for, as Stage All; any other, as Copy, where it is
     chosen. A menu shows the keys of the window it is opened in.
     Whitespace is a split button, its arrow dropping down the kinds
     (`ToolBar.addSplitButton`). The VCS menu, registered after Mode
     (`AbstractMode.registerMenu`, from `BuiltinModes`), opens the status,
     the log, `vcsDiff` and the commit popup; a project's repositories are
     listed once until `Repositories.invalidateAll`. The sidebar shows the status's
     sections and files, what the caret is on selected (`StatusTree`).
     The command log leaves out the `-c` options git always runs with,
     listing them once (`VcsCommand.options`).
7. **Done. Branches relative to others, and stash.** The parent branch, the
   unmerged, unpulled and unpushed sections, the stash section and popup,
   stashes in the log and the refs tree, the status's files in the
   sidebar by directory, actions on the refs
   tree's branches, and branching, committing, fetching, stashing and
   merging in every repository at once. Draft plan, in the order it
   lands:
   - **a. The branch's others, read.** `GitSnapshot` gains the push target
     and the parent, read with the snapshot (one `git for-each-ref
     --format='%(refname)%00%(push:short)%00%(push:track)%00%(symref:short)'`
     over the branch, `origin/HEAD` and the candidates, the upstream
     coming from the status's own header, and one `git config --get-regexp` for the
     records of [Parent branch](#parent-branch), in its order). The
     parent's source is kept with it (`upstream`, `gh-merge-base`,
     `git-town`, `machete`, `vscode`, `reflog`, `default`). A detached
     HEAD, an unborn branch and a gone upstream have none. Shared: the
     snapshot's `Relation(name, source, ahead, behind)` for the upstream,
     the push target and the parent, so svn can leave them empty. Tests
     against repositories set up each way, one per row of the list.
     Built as `GitRelations`: a record naming a branch there isn't is
     passed over; the reflog is read only when the list reaches it, and
     a name it or `origin/HEAD` gives is listed then; a parent that is
     neither the upstream nor the push target is counted with `git
     rev-list --left-right --count`. A push target not pushed yet is
     gone, as a gone upstream is (counts of -1).
   - **b. The header.** `Head:`, `Upstream: origin/x ↑2 ↓1` (or `gone`),
     `Push: origin/x ↑1 ↓0` when it differs from the upstream, and `Parent:
     origin/main (upstream)` with the source in parentheses, as the
     design has it. `Push:` is left out when it is the upstream; a gone
     push target says `gone` too, pushed and deleted or never pushed.
     `Parent:` shows whenever there is one, though another line names its
     branch, so its source is seen and step d's Enter has a line. A parent's name is a local branch before a remote one, so
     a `gh-merge-base` of `main` is counted against the local `main`,
     which may be behind the `origin/main` that `gh pr create` compares
     with; the source in parentheses says which record it came from.
   - **c. The commit sections.** "Unpulled from <upstream>" (`HEAD..@{u}`),
     "Unpushed to <upstream>" (`@{u}..HEAD`), "Unpushed to <push>" when
     the push target differs (`@{push}..HEAD`), "Unmerged from <parent>"
     (`HEAD..<parent>`) and "Not in <parent>" (`<parent>..HEAD`), in
     magit's order after Stashes and before Recent commits. Each is a
     `CommitItem` list as Recent commits is, read with one `git log
     --format` per section, the newest 10, with a last line "… N more"
     whose Enter opens the full log on that range
     (`LogQuery.withRevisions(List.of("HEAD..origin/main"))`). A section
     with no commits is left out, and one with the same range as one
     before it is left out, so the parent and the upstream never show
     twice. "Not in <parent>" is what a pull request against the parent
     would hold, pushed or not; Recent commits shows only when it is empty
     or absent (on the parent itself, or just after branching), as magit
     shows unpushed commits or else recent ones. Enter on a commit shows it as Recent commits' do; `D` and `=`
     show its diff beside, in the log's commit form: with the caret on a
     commit, the diff beside is the log's files over its diff, unified or
     side by side as `=` last left either, and a file at the caret brings
     the file's diff back. The log's panes and their side-by-side form are
     `CommitDetails`, which the log and the status each own
     (`CommitDetails.Host`); the panes' keys find it through its host. Collapsed to begin
     with, as Untracked, Unstaged and Staged are; the sidebar's tree has
     them too. Counts are as of the last fetch, which `f` makes now (below).
   - **c2. Fetch, `f`.** A popup after Neogit's and magit's: switches
     `-p` prune and `-t` tags; "Fetch from" `p` the push remote, `u` the
     upstream's remote, `a` all remotes, `e` elsewhere (a remote asked for
     with a fuzzy list of `git remote`); "Fetch" `o` another branch (a
     remote's branch asked for). A line whose remote isn't set says so
     dimmed, as Neogit shows an unset push remote, and can't be run. The
     fetch runs in the background, with the status bar saying it is
     fetching and then what it fetched or why it failed; the refs it moves
     refresh the status and the refs tree through the repository's
     watcher. It runs with `GIT_TERMINAL_PROMPT=0`, so a remote that wants
     a password j can't give fails at once with git's message (a credential
     helper or an ssh agent works); asking for one is Later. It times out
     after two minutes and can't be cancelled before then; the command
     log shows it once it is done, as it shows every command, and the
     status bar while it runs. Writes go
     through `VcsBackend.fetch(repository, remote, refspec, options)`,
     the remotes listed by `VcsBackend.readRemotes`. The push remote and
     the upstream's are the remotes their branches' names start with. A
     popup command that can't be run is `Command.unavailable`, dimmed;
     `e` and `o` ask with `Finders.choose`, which step d's parent uses too.
     Pushing and pulling stay Later, with their popups beside this one.
   - **d. Setting the parent.** `gitSetParent [BRANCH]` writes
     `branch.<name>.gh-merge-base` (a branch asked for with the refs'
     fuzzy list when none is given), and `gitSetParent -` removes it. The
     `Parent:` line's Enter runs it, as the branch popup's `p` does. A remote's
     branch is written by its bare name (`origin/main` as `main`), as
     `gh pr create` takes it, and a bare name is read back as the local
     branch, else a remote's of that full name, else origin's. The list
     leaves out the branch and its own copies on a remote. Written through
     `VcsBackend.setParent`; picked with `Finders.choose`.
   - **e. Stash.** A `Stashes (2)` section after Staged, each
     `stash@{0}: On main: message` an item that opens to its files and
     their diffs as a staged file's do (`git stash show -p --include-untracked`
     where git has it, 2.32). Enter shows it in the log's details, its
     index and untracked parents labeled. On a stash, `x` drops it after
     asking, naming the stash, as `x` discards elsewhere. The stash popup, `z`: save `z`,
     keeping the index `i`, with untracked `u`, of the staged alone `s`
     (git 2.35's `--staged`), apply `a`, pop `p`, drop `k` (confirmed),
     each acting on the stash at the caret, else `stash@{0}`, with a
     `-m` message option. A pop or apply that conflicts leaves the stash,
     says so, and the status shows the conflicts (phase 9 resolves them).
     Writes go through `VcsBackend` (`stash`, `stashApply`, `stashDrop`),
     git's in `GitWrites`, and `Capability.STASH` gates the popup. `s`
     before git 2.35 fails saying so, without running git. Undo-like safety: a dropped stash's hash goes in the
     command log and the status bar, so `git stash apply <hash>` can bring
     it back. Built as: a `StashItem` (name, hash, subject, files) whose
     line is a commit's, so the diff beside follows it as it follows a
     commit, from the stash's line or any line in it; its files are read
     when it is opened (`wantsDiff` asked of `stashes` and its hash). The
     popup is `z`, and `Z` with the vim keys, where `z` begins the folds.
     Apply goes by the hash; pop and drop, which git takes only by name,
     leave a stash whose name has moved to another alone. Ignoring
     whitespace, a stash's file changed only in that is still listed, from
     a second `stash show --name-status`. The details' parents are
     labeled through `CommitDetails.Host.isStash`; their files are the
     stash's tracked changes, against the commit it was made on, while
     the status's lines have its untracked files too.
   - **f. Stashes in the log and the refs tree.** The refs tree's last
     node lists the stashes; a click goes to the stash's commit, which the
     log shows when the `-z` filter (`vcsLogToggleStashes`, off by
     default) adds `refs/stash` and its reflog (`git log --walk-reflogs`
     is not a graph, so each stash is read as `stash@{n}` and drawn as a
     tip off its base, labeled `stash@{n}`). Built as: `LogQuery.stashes`,
     shown (`showsStashes`) only with no filter but the repositories, as a
     filter could leave a stash's base out; `VcsBackend.readStashes`, git's
     a `stash list` for the names then one `log --no-walk` of them, each a
     `LogCommit` badged with a `LogRef.Kind.STASH` whose `graphParents` is
     its first parent alone, so the index's and untracked commits aren't
     read. Each repository's stashes are a `LogMerge` source of their own,
     read whole and placed among the repository's commits by commit time,
     so the branches' pages and skips are untouched. The refs tree's
     Stashes come from `readRefTips`; a click, Enter or a double click goes
     to the stash, or says `-z` shows it. The graph draws a stash as a
     plain commit (`LogCommit.isStash`).
   - **g. The sidebar by directory.** The status's `StatusTree` groups each
     section's files under their directories, collapsed to the first
     directory with more than one child (`src/org/armedbear/j` as one
     node), with the commit sections' commits as leaves. Its selection
     still follows the caret.
     Built as: `StatusTree`'s `DirNode`s, keyed by the section's key and
     the directory's path with a trailing slash, so no status line's key
     selects one but as the shown node around a file; a click on one goes
     to its first file. Directories come before files, by name; a
     directory opens with its section, unless it was left closed. Commits
     and stashes are `CommitNode`s with the commit icon; a stash's files
     stay in the status.
   - **h. Actions on the refs tree's branches.** A popup on a ref (`Enter`
     stays the filter, so the popup is the right button and `b` in the
     tree, and `b` in the status and log for the branch at the caret, or
     HEAD's): switch to it (`s`; a remote branch makes a local tracking
     one, as `git switch` does), create a branch from it (`c`, asking for
     the name and starting from the ref named, so the reflog records the
     parent), rename (`r`) and delete (`k`, confirmed; `-D` only after a
     second confirm naming the commits that would be lost), set it as the
     current branch's parent (`p`), and merge it into the current branch
     (`m`, `--no-ff` an option). Rebase is Later. A switch with changes in
     the way says what git said and changes nothing.
     Built as Neogit's branch popup instead, at the user's asking, after
     a branch made from `main` left `main` unreachable from `b` (both on
     one commit, the popup acting on the one checked out): `BranchCommands`,
     whose commands each ask from a list (`Finders.choose`), the ref at the
     caret first and the branch checked out left out where it can't be the
     answer. Checkout `b` branch/revision, `l` local branch (a remote's
     without a local one tracked), `r` recent branch (the reflog's
     "checkout: moving from"), `c` new branch; Create `n`; Do `m` rename,
     `D` delete, and `p` parent, ours. Not Neogit's spin-off, spin-out,
     worktrees, configure, reset, pull request, nor `-r`. The commands take
     the ref at the caret and the repository's root as their argument
     (`vcsBranchCheckout main /repo`, `-` for none), which the popup's
     lines carry, so nothing is kept between the popup and the command
     (`Transient.Command.name` is the name without it, for help). The ref
     at the caret is a branch on its commit other than the one checked
     out, else a remote's or tag, that branch, or the commit. In the tree
     the popup is the right button and the menu key or Shift F10, not `b`,
     which typing there takes to narrow it. Merge moved to its own popup,
     `m`, as Neogit's: `-f` `--ff-only`, `-n` `--no-ff`, `m` merge; during
     a merge, `m` commit and `a` abort (`MergeCommands`). A delete reads
     the commits on no other branch, tag or remote's branch first (`git
     log x --not --exclude=x --branches --tags --remotes`; not `--all`,
     whose HEAD or stash would hide them): none, it asks once and deletes
     with `-D`, nothing being lost; some, it asks again naming them. After
     each write the repository's open logs are read again
     (`LogBuffer.refreshAll`); the stash commands' background write is
     shared (`StatusWrites`).
   - **i. Branching in every repository.** As IntelliJ's "Execute branch
     operations on all roots": with several repositories in the status or
     log, the branch popup gains `-a`, all repositories, absent with one.
     With it on, the lists offer the names any repository has, each with
     how many have it (`main (3)`, `feature (1 of 3)`); checkout (`b`,
     `l`) acts by name in each repository that has the branch, saying
     which don't; create (`c`, `n`) asks one start point, a name each
     has, else HEAD where it hasn't, and makes the branch in each; rename
     and delete act in each that has it, the delete's second question
     asked once, the commits lost listed by repository. Each repository's
     result is said; one that fails, as a checkout with changes in the
     way, leaves the others done, as IntelliJ does, with no rollback.
     The writes run one repository at a time in the background, the
     status bar counting them. Not `r` (each repository's reflog differs)
     nor `p`. Tests: three repositories, one without the branch, one with
     changes in the way.
     Built as `AllBranchCommands`, which `BranchCommands`' commands hand
     to first when the buffer's `-a` is on and it has several
     repositories: each repository's `Context` read, the names labeled by
     count (a name checked out in every one that has it left out of
     checkout and delete), and one background job doing each in turn,
     its `Done`s said together. Checked in the app under Xvfb: three
     repositories, one with a change in the way.
   - **j. Committing, fetching, stashing and merging in every
     repository.** With several repositories in the status or the log:
     - **Commit** goes to each repository with something staged (with
       `-a`, any tracked change), one commit each with the one message,
       as IntelliJ's commit does across roots. Which repositories a commit
       takes is never left to guess: the popup's `c` line names them
       ("commit to x" or "commit to 3 repositories: x, y, z"); the
       message window's title does too ("git commit: x" or "git commit:
       3 repositories"); its template opens with them in capitals
       (`# COMMITS TO 3 REPOSITORIES, ONE EACH: x, y, z`), or with one
       of several, which it is and that the others have nothing staged;
       the staged files are listed under each repository, and a verbose
       diff is headed by each. The hint line over the message says it
       again, and Ctrl Enter's status line says each commit made. Amend,
       extend and reword take the caret's repository alone, and their
       lines name it ("amend x's last commit"). A failure in one, as a
       hook's, leaves the others committed and the message open for the
       ones that failed, its title and template saying which are left.
     - **Fetch** gains `-a`, all repositories: `p`, `u` and `a` fetch in
       each, each from its own remotes; one without the remote asked for
       is said and left. `e` and `o` stay with the caret's repository,
       remote names being each one's own.
     - **Stash** gains `-a`: `z`, `i`, `u` and `s` stash in each
       repository with something to stash, with the one message; apply,
       pop and drop stay with the stash at the caret, which is one
       repository's.
     - **Merge** gains `-a`: `m` merges a ref by name, labeled with how
       many repositories have it, into what each that has it has checked
       out; a conflict in one stops that one alone. Committing or
       aborting a merge stays with the caret's repository.
     All but commit's report as the branch commands' do: one repository
     after another, the status bar counting them, then where it was done,
     where not and why, and where it failed. Tests: three repositories,
     each operation, with one that fails or is left.
     Built as: `EachRepository`, the `-a` switch, the loop and its report,
     taken out of `AllBranchCommands` for fetch, stash and merge to share.
     Commit has no switch: `CommitCommands.targets` picks the repositories
     and `reach` writes the template's capitals line;
     `CommitMessageBuffer` holds its repositories, its title from them, and
     after a partial failure the ones left, its template written again
     around the message. `VcsRepository.name()` names a repository as the
     status's heading does. A stash with `-a` is in each that the status
     last read with something to stash.
   - **Docs and keys.** `doc/git.html` gains the parent, the commit
     sections, fetch, stash and the branch popup; the status popup gains
     `f`, `z` and `b` under Commands. Help anchors for each.
   - **Tests.** Fetch against a second repository as the remote (prune,
     tags, a remote not set, a failure); the parent's sources in their
     order; each section's range and its leaving out, Recent commits only
     without "Not in <parent>"; a gone upstream; a detached HEAD; an
     unborn branch; stash save, apply, pop with a conflict, drop, and show,
     (not on git versions that lack `--staged` and `--include-untracked`,
     as the tests run the git installed);
     the refs tree's actions against a real repository, a delete refused
     for unmerged commits included.
   - **Decided:** `f` fetches from a popup; `x` on a stash drops it after
     asking; `b` is the branch popup; "Not in <parent>" shows, collapsed,
     in place of Recent commits when it has any.
8. **Next. Blame.** Streaming blame, aging, hovers, to the log, blame at the
   parent, the current line's blame, and the authors of each method, as
   [Blame in the gutter](#blame-in-the-gutter-vcsblame-toggles) has them.
   Draft plan, in the order it lands:
   - **a. Phase 7's leftovers.** The branch's relations read once per
     HEAD and refs, not with every snapshot: kept with the repository and
     dropped when `RepositoryWatcher` sees HEAD, the refs or (new) the config
     change (it watches the index too, so the cache needs its own reason
     to drop, not `invalidate()` alone), so a save's change marks pay
     nothing; and a section's range by full ref (`HEAD..refs/heads/main`),
     as its count is, so a tag of the branch's name can't make them
     disagree. First, since blame runs a snapshot's worth of git per file
     shown and should not add to a cost already paid on every save.
   - **b. Gutter columns.** `Buffer.getGutterColumn()` becomes
     `getGutterColumns()`, a list, painted left to right after the line
     numbers; `Display`'s width, hit tests, tool tips and clicks go through
     the list (`GutterColumn.At` gains its column). The log's graph is
     one; blame's is another, which a file buffer adds and removes.
     Change marks and line numbers stay `Display`'s own: nothing needs
     them moved, and the plan's "every column a provider" would be an
     abstraction with one new user.
   - **c. Reading blame.** `VcsBackend.blame(repository, path, contents,
     sink)` streams `git blame --incremental --contents - -- <path>`
     (the buffer's text on stdin, so lines changed since read as not
     committed yet) into `BlameData`: per line, a `BlameCommit` (hash,
     author, author time, summary, the previous commit and path from
     `previous`, boundary) shared by its lines. `.git-blame-ignore-revs`
     is passed with `--ignore-revs-file` when the repository has one and
     `blame.ignoreRevsFile` is not set: git reads it only through that
     config, so "git honors it" holds for those who set it alone (2.23 and
     later; gated). A `Capability.BLAME`; svn's comes in phase 11.
     `GitBlame` parses `--incremental`'s groups (a header per run, the
     commit's fields the first time it is seen). The result is the
     buffer's lines as `Line` objects, read under the buffer's lock when
     it was started, so an edit made while it streamed moves its
     annotations with the lines; it runs again 500 ms after a save or a
     change of HEAD, the old one kept until the new one has finished.
     Several repositories: a file's blame is its own repository's, a
     nested one's included, by `Repositories.forFile`. Tests: a file with
     commits by two authors, an edited buffer, a rename followed through
     `previous`, an ignored revision, a file not committed, a binary
     file.
     Built: `vcs/blame`'s `Blame` is the buffer's (a `Buffer.Follower`
     kept on the buffer, as its signs are, read while it has listeners),
     `BlameData` its lines'. The text sent is what a save writes
     (`SystemBuffer.write`: a byte order mark, UTF-16's separators). A line whose
     text changed since it was blamed reads as `NOT_COMMITTED` (an
     insertion at a line's start keeps the old `Line` for the new text).
     "Read again" is the repository's `refsGeneration` and the file's
     modified time changing, seen on `vcsChecked`. A file with no history
     is not committed throughout. `VcsRunner.run(command, stdout)` streams.
   - **d. The column.** `vcsBlame` (`<leader>gb` in vim) toggles it on the
     buffer. Each run of one commit shows `vcsBlameFormat` (`%d %a`, date
     and author; `%h` hash, `%s` summary) on its first line, and a thin
     bar in the commit's color on the rest; lines not committed yet are
     blank. The column is as wide as its widest entry, capped at
     `vcsBlameMaxWidth` columns, with an ellipsis. Age by rank, as the
     design says: the commits in the buffer, not only those in view
     (which would change the shade as you scroll), ranked by author time
     onto 8 steps from the text's color to the comment's. Turning it on
     says "Reading blame…" until the first lines arrive.
   - **e. Hover, click and the parent.** The tool tip has the hash,
     author, both dates when they differ, and the whole message, read
     with `git show -s` the first time it is asked for, cached by hash.
     A click on an entry opens the log at that commit (`LogBuffer.reveal`,
     the file's path as the log's file filter) with the file selected in
     its details. `vcsBlameParent` on the caret's line opens the file as
     the commit's parent had it (`previous`'s hash and path, so a rename
     is followed), read-only (`RevisionBuffer`, made public to `vcs`),
     blamed at that revision (`git blame <rev> -- <path>`), the caret on
     the line nearest; going back is the buffer list's. A boundary
     commit has no parent, said.
   - **f. The current line's blame.** `vcsLineBlame`, on by default: `EOL`
     virtual text (`vcs.blame.line`) on the caret's line after the caret
     rests 500 ms, `kkrouse, 2 days ago · summary`, dimmed; "Not committed
     yet" on a changed line; none on a line that is a run's first while
     the column is on, which already says it. It reads the blame of (c)
     in the background once per buffer shown, so a file never blamed
     pays one `git blame` when it is first looked at; files over
     `vcsBlameMaxLines` (10,000) get none.
   - **g. Authors of a method.** `vcsCodeAuthors`, off by default (a
     line over every method is noisy): an
     `ABOVE` line (`vcs.blame.authors`) over each method and class the
     mode's tags give, its end from `Mode.getFoldRange`, `kkrouse +4`,
     with `*` when it has uncommitted lines (counted for `user.name`).
     The hover lists every author with their lines and last date; a
     click turns the column on with the method's lines' commits bright
     and the rest dimmed until the caret leaves it. Made again with the
     blame and when the tags are read again. Same blame, same limit.
   - **h. Docs, keys, review.** `doc/git.html` gains Blame; commands and
     preferences documented; the status's and editor's `?` popups list
     `vcsBlame`; vim `<leader>gb`. Checked in the app under Xvfb: a file
     of this repository blamed, a hover, a click into the log, the parent.
     The adversarial review closes the phase.
   - **Decided:** the line's blame on and the authors off by default;
     the column's age ranks the buffer's commits, so a line's shade stays
     as it scrolls.
9. **Conflicts.** Markers, inline actions, navigation, marking resolved,
   and finishing.
10. **Changelists.** The store, ranges as markers, re-anchoring, the status
   section, moving, outlined marks, committing with a private index, and
   drag and drop.
11. **Subversion.** Its backend on the shared layer: status, diff, marks,
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
fetch popups, the three-pane merge (phase 6's panes and tracks), comparing two
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
  backend will need its own header writer, behind the backend, in phase 11.
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
- Git leaks into shared code: `VcsStatusMode`'s help is `git.html#status`, `BufferListStatus` reads git's porcelain codes, and the preference is `gitBufferListStatus`. Svn's backend (phase 11) gives the mode its help topic and the entry its letters.
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

- The status code names git's sections (`"staged"`, `"unstaged"`) and its staging model in the shared layer. Svn's backend (phase 11) gives the sections and actions by capability.
- `VcsBackend.commit` takes no paths and no index file; phase 10's private-index commits and svn's commits of paths add them.
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
- `Signs.Sign.staged` becomes a state with a third value, another changelist, in phase 10; the drawing moves into a column provider with blame's column in phase 8.
- A reset whose buffer is reloaded from disk between its read and its edit could edit the new text: `modCount` starts again at 0 on a reload. The window is a git command long.


### Simplicity review, phases 0–4

Removed: code nothing called (`VcsRunner.runAsync` and its pool, `CommandLog`'s listeners, `Repositories.all`, `GitCli.apply`, `GitStatusCache.invalidate`), `WordDiff`'s own LCS table (it uses `util/Diff`), and the change colors hard-coded in five places (`VcsColors`). Kept, though nothing uses them yet, because later phases need them: the popup's `Pick` option (phase 5's filters), virtual text's `EOL` and `RIGHT` placements and priorities (blame, phase 8), and the `Capability` values past `STAGING`.

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


### Phase 5 review

Fixed:

- Quitting the quick log closes its window. Only the full log puts back the
  windows from before it, which a quick log keeps for when it grows.
- `LogBuffer.close` runs once. Killing the log or a pane, from the buffer
  list or `:bd`, closes it all as `q` does. A file opened from the log
  (`o`, Enter, Shift Enter) after a `q` is given up.
- After a refresh, the caret's commit is looked for in the first page
  only, so a later page can't take the caret.
- `git log` and `git show` read with `--no-show-signature`, and a root
  commit with `--root`, whatever `log.showSignature` and `log.showRoot`
  say. A diff that fails to read says "No diff: see vcsCommandLog";
  `FileDiff.EMPTY` is one with no change to its content.
- Folding in the log's diff keeps each window's top and caret on their
  lines, or on their hunks' when those close.
- A subject is never cut inside a surrogate pair.
- Shared: the diff line's file line and showing a buffer at it
  (`VcsStatusMode.lineNumber`, `showAt`), the change letters
  (`FileChange.Kind.letter`, `of`), `VcsRepository.relativePath`, the last
  commit's message (`GitLog.message`), HEAD's check (`GitLog.hasHead`), and
  the read-only view buffer (`vcs/ViewBuffer`) the status buffer and the
  log's buffers extend; the modes' properties alone keep line numbers and
  the like off.
- `WindowLayout` is the tree's types alone; `Frame.captureLayout` and
  `restoreLayout` take and put back a `WindowLayout.Node`. The log's `full`
  is whether it has its panes.

Left open:

- Rows are laid out for one width, the last window resized; one shown in a
  window of another width, with no resize, is laid out wrong, and a drag
  lays out every row read. The formatter could lay a row out for the
  width it draws at.
- Each page runs `git log --skip=N` again, so later pages of a large
  repository take longer. One `git log` read as it goes would not.
- Wide characters (CJK) count one column, as everywhere in j.
- `Frame.restoreLayout` makes new windows, so they lose their views of
  other buffers. It could keep the windows aside and reuse them.
- The backend's diffs overlap (`readDiff`, `readCommit`, `readChangeDiff`,
  `readDiffToWorkingTree`): one `readDiff(from, to, paths)` could serve
  them all. `GitStatusReader.recent` is a second log parser.
- `LogRef.Kind.HEAD` is kept: it marks a detached HEAD, which the `head`
  flag alone doesn't tell from a branch checked out.

### Phase 6 review

Fixed:

- What is staged is read past its byte order mark, and UTF-16 split on its
  own newlines (`Side.of`), so a file with a mark compares, and its change
  marks show, as unchanged; each line's raw bytes stay as a patch has them.
- A change only to whitespace, while it is ignored, says so
  (`FileDiff.onlyWhitespace`) in the status, the diff beside and the log,
  not that the diff failed; `readDiff` gives null when git fails, so a
  failure is not taken for it.
- A file deleted from the working tree is compared from the disk, not
  from its buffer still open.
- From a side, a view's command the side has a key for (`=`, `q`) runs in
  the side (`CompareCommands.sideRuns`), keeping the focus there.
- The toolbar no longer caches what Stage, Unstage and Discard can do
  across updates, which kept a killed status alive; it is worked out once
  per update.
- A stored view's selection goes when the buffer's lines are laid out
  again (`View.invalidate`); a comparison, laying a side out as it was,
  keeps it (`invalidateKeepingMark`).
- A comparison's first alignment is worked out in the background with its
  read (`Following.Show`), not on the event thread.
- The change marks' `SignsUpdater` is a `Buffer.Follower`, told as a
  comparison is, not called beside the followers; a comparison of a file
  with change marks reads what is staged through their `BasesCache`.
- Core's hiding and showing of lines asks the buffer's folds first, so
  `Buffer.renumbered` and `CompareFolds.read`, which read back folds core
  had opened behind them, went.
- `SideGeometry` takes each line's rows from `VirtualTextLayer`, as
  `Buffer.getY` does, not from its own copy of where TOP's rows go.
- `CompareBuffer.Source` went: `Following` is the one. A side's keys and
  change starts are the buffer's own methods, the mode's by default
  (`getKeyMapForMode`, `getChangeStarts`), not hooks beside the mode's.
- The window fuzz checks where a toolbar or menu command runs, from every
  window of a view, as unit tests have no frame.

A second review, of those fixes:

- A hunk of a UTF-16 file is not staged or unstaged, as its lines are not
  git's, which splits on a byte; the file is, whole.
- A hunk at a UTF-8 file's first line keeps the file's byte order mark in
  the index (`Side.withMarkOf`).
- What the comparison follows is worked out before a redraw's early
  return, so a file deleted while its buffer is followed is compared from
  the disk; the disk is looked at only for a file with an open buffer.
- `foldRegion`, and any other way of hiding lines, leaves a buffer whose
  folds are its own to them (`FoldCommands.hide`).
- `BasesCache.get` waits for a read already under way, so the change
  marks and a comparison read what is staged once.

Left open:

- The VCS menu is registered (`AbstractMode.registerMenu`) by core itself.
- The popup shows Enter, Tab and Ctrl R as `<cr>`, `<Tab>` and `<C-r>` in
  both edit modes, as it does `<BS>`.

### Phase 7 review

Fixed:

- A status commit's refs are read typed, `--decorate=full` as the log's
  are, and the ref at the caret is chosen by kind: git lists tags and
  remotes' branches before local ones, so `origin/main` had come first.
- A branch's parent is never where it pushes, so `main` in a clone is not
  its own parent through `origin/HEAD`, which had hidden Recent commits
  whenever it was ahead.
- A popup opened from a source, or a prefix's, is made again as a switch
  or option changes (`TransientPopup.open(Editor, Supplier)`), so fetch,
  merge, stash and commit say what `-a` or `--all` now does.
- With `-a`, the start, checkout and merge lists take a name from the
  list alone; a typed revision, one repository's, had become HEAD in each.
- A parent on a remote but origin is recorded whole, `upstream/main`,
  which reads back as that remote's.
- `l` with `-a` lists branches by bare name, checked out local where one
  is and from a remote's, origin's first, where none is.
- Fetch with `-a` leaves a repository with no remote, said; `git fetch
  --all` succeeds there.
- Stash with `-a` decides on the event thread which repositories have
  something to stash.
- With `-a`, a repository that throws fails alone, its outcome said; the
  delete's failed read says so; `Background.then` runs its callback after
  an error too, so a status's writing always ends.
- The branch popup's tests wait on what is said, not a sleep; `-a`'s
  reports are checked.

Left open:

- A section's range uses short names (`HEAD..main`) while the parent's
  counts use the full ref; with a tag and a branch of one name, git takes
  the tag, so the section and its count could disagree. Not reproduced.
- Every snapshot reads the branch's relations, 3 to 6 more git processes
  (config, for-each-ref, the reflog, rev-list), and the status adds up to
  five `git log` runs and `stash list` a repository a refresh; the change
  marks pay for the relations on each save and focus return. Read them
  lazily in the status reader, or cache them by HEAD and refs, in phase 8.
