# Transient popups: plan

A popup of a buffer's keys, opened with `?` or a prefix key: arguments to
set, filters, and commands to run, grouped, with the keys in bold. Clicks
work as well as keys. The first users are dir mode, then the git tools
(`GIT-TOOLING.md`), then mail. It replaces the hint line as the place to
learn a buffer's keys; the hint (a virtual line, `VIRTUAL-TEXT.md`) shrinks
to a few keys and `? more`.

## What others do

**Magit's transient (Emacs).** A prefix key (`c`, `l`, `z`) opens a popup
in a window at the bottom of the frame. It has three kinds of thing:
- **Switches** (`-a --all`) toggle a flag, shown highlighted when on.
- **Options** (`-n --max-count=`, `=a --author=`) read a value with
  completion and show it.
- **Suffixes** (`c commit`, `l current`) are the commands, which run with
  the arguments set.

The items are in labeled groups and columns. The keys are bold, and the
popup takes all keys until a suffix runs or `C-g` quits. Arguments are kept
for the session, and `C-x C-s` saves them as the defaults. `C-h` then a key
shows that item's doc. Levels hide the rarer items until asked for. `?` in
magit's buffers opens the dispatch popup, which lists every prefix.

**Neogit.** The same popups (switches, options, actions), drawn in a float
or split, with `?` listing the popups and the keys bold. Options read their
values in the command line or in a picker. Its hints at the top of the
status buffer name `?`.

**lazygit.** `?` opens a list of the focused panel's keys, which you can
filter by typing, and `Enter` runs one. Menus such as the commit options
list a key per item. The mouse picks any item.

**which-key (Emacs, Neovim), Helix, Kakoune.** After a prefix key, a box
shows what the next key can be. Helix shows it at once for `Space` and `g`.
These describe key sequences, not arguments.

**Taken here.** Magit's three kinds of item and its groups, the bold keys,
and arguments kept per buffer and session. lazygit's clicks. Neogit's `?` to
list everything.

## Design

### The model

A `Transient` is data, not a class per popup:

- a title and a help anchor (`doc/git.html#log`);
- groups, each a label and a list of items:
  - `Switch(key, name, description)`: on or off;
  - `Option(key, name, description, chooser)`: a value, picked by a
    chooser (a fuzzy list as the finders have, a field, a checklist, or a
    cycle through fixed values);
  - `Command(key, command, description)`: a j command, which reads the
    popup's values;
  - `Prefix(key, transient)`: replaces the box's contents with another
    popup, as `l` inside magit's dispatch does. The box resizes to the new
    rows and stays anchored where it was. Values set in the first popup are
    kept. `Escape` goes back to the popup before, as magit's `C-g` does.
    There is no key to close every level at once (magit's `C-q`; `Ctrl Q`
    is j's `quit`): popups nest one or two deep, so `Escape` twice does it,
    as does a click outside the box.

The values live in a `TransientArgs` owned by the buffer (the log's filters
are its log's), seeded from the session's last values and from saved
defaults. A command gets them from the editor (`Transient.args(editor)`).
A popup can be built from a resource file, in the format of
`vim/default-keymap.conf`, so `COMMAND-METADATA.md` can describe them
later.

### Showing it

- **A floating box.** A buffer in a box over the window it was opened
  from, in the frame's popup layer, where `CompletionPopup` puts its list.
  It is anchored to that window's bottom edge, as wide as the window (at
  most 100 columns, centered), and as tall as its rows. It has a thin
  border and a shadow, and the bottom panel's tint. It has no location bar
  and no status bar: the title is its first row, and the help line its last.
- **What it is inside.** An `Editor` without a location bar, status bar
  or gutter, around a `Display` (which needs an `Editor`). It is not in
  `EditorPane`'s split tree, so it never takes a window's place, and window
  commands (`Ctrl W` in vim, split, next window) skip it. Being a real
  buffer, it is drawn by the same formatter and theme code, can scroll when
  the popup is taller than the window, and can be searched.
- **Closing.** `Escape` (from the first popup; from a nested one it goes
  back), a command that runs, a click outside the box, or
  the window under it closing. Focus goes back to the window it came from.
  The bottom panel is left as it was.
- The rows are laid out in columns by group, to the window's width:

```
 Arguments                     Filters                      Log
 -a  --all                     -b  branch   master          l  current
 -m  merges only               -p  path     src/…/vcs       o  other branch
 -n  limit         2000        -A  author   ▾               h  HEAD
                                                            q  quit
 help: git log
```

- The keys are bold and in the link color. An active switch and a set
  option are in the "on" color, and an unset option is dimmed. The command
  name shows on hover, and in the popup's own search.

### Input

- While it is open, the popup takes the keys: the same keys in simple and
  vim edit modes, since nothing in it is edited. A two-letter key (`-a`)
  waits for its second letter. `Escape` closes it (or goes back a level),
  and so does `q` unless an item uses `q`. `Ctrl H` then a key opens that
  item's help.
- A switch toggles where it is. An option opens its chooser in the box: a
  field on the option's row, with a fuzzy list under it (`CompletionPopup`,
  which works under any text field). If the option is also a chip in the
  window's filter bar, the chooser is the same one. A command runs and closes
  the popup, unless it is marked to stay open (a filter, so you can set
  several).
- Mouse: a click on any item does what its key does. The help line is a
  link. Hovers show the command name and its first line of doc.
- `Ctrl S` saves the values as the popup's defaults, as `C-x C-s` does in
  magit. It is a popup key, so it works the same in both edit modes.

### Dir mode, the first popup

`?` opens it:
- **Sort**, an option cycled by its key: name, date, size.
- **Filters**: limit (the `dirLimit` pattern), hidden files.
- **Go**: open, up, back, forward, home.
- **Files**: tag, copy, move, delete, shell command, rescan.
- **help: directories**.

Its hint line shrinks to `Enter open · - up · ? more`. Sort, limit and
hidden files are also chips in a filter bar over the listing, the same
`FilterBar` as the git log's (`GIT-TOOLING.md`); the popup and the bar
change the same values.

## Phases

1. The model, the floating box, keys and clicks, switches and commands,
   and the help line. Dir mode's popup and the shorter hint. Tests: the box
   opens over the right window, closes on each way out, and gives the focus
   back; `fuzz-windows` with popups open while windows split and close.
2. Options with choosers, values kept per buffer and session, saved
   defaults, and `Prefix`. The git popups use these.
3. Mail's popups.
