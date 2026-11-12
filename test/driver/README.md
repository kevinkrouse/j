# GUI regression drives

`bb drive SCRIPT [FILE...] --out DIR` starts j under Xvfb with a fresh home.
`--wm` also starts openbox. `--home DIR` reuses a test home across launches;
use a scratch directory, never a real home. `--home-from DIR` seeds it from
another directory. j runs with `--no-session`, so at the end the driver saves
window placement and sidebar state itself, with the calls `Editor.saveState`
makes, and saves no edited buffers.

Scripts contain one step per line. See `GuiDriver.java` for the complete
list. Mouse targets are resolved from visible UI state when each step runs:

- `line TEXT`: the beginning of the first visible editor line containing TEXT.
- `text TEXT`: the beginning of TEXT in a visible editor line.
- `node TEXT`: a visible sidebar tree row containing TEXT.
- `button NAME`: a painted Sidebar, Buffers, Changes, or Tree status button.
- `X Y`: coordinates relative to the current frame, for geometry tests.

`click [double] [right] [ctrl] [shift] TARGET` sends a real mouse click.
`press TARGET`, `move TARGET`, and `release` split a drag so a screenshot can
record its drop feedback before release. `drag TARGET -> TARGET` combines
them; the older `drag X1 Y1 X2 Y2` form still works. `menuitem LABEL` clicks an
enabled item of the open popup. Targets never move the caret or select a tree
node themselves.

`expect popup TEXT` reads the open popup, an item a line as
`LABEL [enabled]` or `LABEL [disabled] — why`. `expect sidebar TEXT` reads
the panels showing, such as `top=Changes tree=true`, or `hidden`.
`expect selection TEXT` reads selected rows of the focused tree.
`expect text@NAME TEXT` reads the buffer whose name ends with the word NAME,
shown or not. `expect input TEXT` checks the input handler. Expectations wait
up to the configured `timeout`. `shot NAME` records the frame, including a
drag's feedback.

`exec` runs only the commands and properties listed in `GuiDriver.COMMANDS`
and `PROPERTIES`; none runs a shell or evaluates code. Keys and typed text
reach j as a user's would, so a script is trusted code, reviewed as a test
is. Create fixtures before starting j.

## Selections

Create a fresh fixture and run the generated script:

```sh
python3 test/driver/selections-fixture.py /tmp/j-selections-simple
bb drive /tmp/j-selections-simple/selections.txt /tmp/j-selections-simple/repo/a.txt \
  --home /tmp/j-selections-simple/home --out /tmp/j-selections-simple/shots
bb drive test/driver/selections-saved.txt /tmp/j-selections-simple/repo/a.txt \
  --home /tmp/j-selections-simple/home --out /tmp/j-selections-simple/saved-shots
```

Repeat with a different fixture directory and `--vim` on the fixture command.
The fixture refuses an existing destination. `selections.txt` is the template;
the generator substitutes the absolute fixture path and keeps a line starting
`vim: ` or `simple: ` for that mode alone. The repositories carry their own
identity and hooks directory, so j's git doesn't use the host's. All changes
during the drive come from j. The fixture includes two repositories, a
two-hunk file, staged content, an untracked file, and ordinary text for
dragging. Hooks and concurrent writers are covered by unit tests against real
git repositories.

## Subversion

Create a fresh fixture and run the generated script, in each mode:

```sh
python3 test/driver/svn-fixture.py /tmp/j-svn-simple
bb drive /tmp/j-svn-simple/svn.txt /tmp/j-svn-simple/svn/a.txt \
  --home /tmp/j-svn-simple/home --out /tmp/j-svn-simple/shots
```

Repeat with a different fixture directory and `--vim` on the fixture command.
The fixture needs `svn` and `svnadmin` (the dev shell has them). Its project
holds an svn working copy, a second one of the same repository whose commit
left a conflict in the first's `a.txt`, and a Git repository. The drive takes
the conflict and marks it resolved, blames the file, then from the status of
all three shows the diffs, discards a file, commits a file whole, moves a
file to a new svn changelist and commits the list, and shows the log.
