# Vim editing: how it works

j has two edit modes. **simple** is j's own non-modal editing and the default.
**vim** is modal editing that aims to behave like nvim closely enough that the
uncanny valley never opens: not only the right keys, but the right *ranges*
(`dw` at the end of a line, `d}` turning linewise, `cw` acting as `ce`), one
undo step per insert session, and a `.` that repeats the whole change.

This is the developer's view: how it is built, what is easy to break, and what
the work so far has learned. The user's view -- what is there, how to configure
it, where it differs from vim -- is `doc/editmodes.html`, and it is the
authority on divergences.

- [Turning it on](#turning-it-on)
- [Architecture](#architecture)
- [Semantics that decide whether it feels like vim](#semantics-that-decide-whether-it-feels-like-vim)
- [Undo](#undo)
- [Rendering](#rendering)
- [Testing](#testing)
- [Traps](#traps)
- [History](#history)

## Turning it on

`editMode=vim` in `~/.config/j/prefs` (`Property.EDIT_MODE`). It resolves
through the usual buffer → mode → global chain, so `JavaMode.editMode=vim`
works. Only `TYPE_NORMAL` buffers get it: directory, image, compilation and
list buffers bind bare letters as their own commands and stay simple.
`vimKeyMap=FILE` (`Property.VIM_KEY_MAP`) replaces the built-in key table;
`~/.config/j/vimrc` overrides parts of it.

## Architecture

Three layers: a seam in j's input path, an engine in `org.armedbear.j.vim`,
and configuration as data. The engine imports no Swing.

### The input seam

`org.armedbear.j.InputHandler` is the one structural change to j's input
path. `Editor.handleJEvent` asks the editor's handler first, just below the
`insertingKeyText` short-circuit and only when no Emacs-style prefix key is in
flight (`requestedKeyMap == null`), so `checkKeyboardQuit` still works:

| result | meaning |
|---|---|
| `CONSUMED` | the handler took the key; the dispatcher then ignores the matching key-typed event |
| `DEFER` | "I want this key, but decide at `KEY_TYPED`": skip the key maps without consuming |
| `PASS_THROUGH` | j's own key maps handle it as they always have |

Simple mode has **no handler object at all**, so "simple mode is unchanged" is
structural rather than argued. The handler also supplies the caret shape, the
mode indicator, the pending-command text, whether the selection is linewise
and the search matches to paint on a line (`InputHandler`'s default
methods), and hears `editorDeactivated`.

`Editor.handleKeyMapEvent` is the key-map half of `handleJEvent`, split out so
the vim layer can run *j's own binding* for a key and then do something after
it. Insert-mode Enter is the user: the mode's binding (`newlineAndIndent` in a
programming mode, `newline` in plain text) runs, and the vim layer marks the
indent it left as the session's. Replayed Delete and Tab go the same way.

**Why both event ids.** `KEY_TYPED` alone cannot see `<C-w>`, `<Esc>`, `<BS>`
or the arrows -- `dispatchKeyTyped` bails on modifiers and control characters.
`KEY_PRESSED` alone cannot tell `d` from `D` from `:` on a non-US layout, where
`getKeyChar()` is often `CHAR_UNDEFINED`. So `VimInputHandler`:

| event | condition | result |
|---|---|---|
| `KEY_PRESSED` | Escape (or Ctrl-[) | Escape |
| `KEY_PRESSED` | Ctrl/Alt/Meta, or a named key (Enter, BS, Tab, Delete, arrows, Home/End, Page) | dispatch; `PASS_THROUGH` if nothing claims it and no command is part-typed, so `Ctrl-S` still saves |
| `KEY_PRESSED` | any other key in a command mode | `DEFER` |
| `KEY_TYPED` | command mode | dispatch, `CONSUMED` always -- a plain key never inserts itself |
| either | insert or replace mode | mostly `PASS_THROUGH`; Escape, the insert-mode bindings (`<C-t>` `<C-d>`), Enter, and replace mode's typing and Backspace are the vim layer's |

AWT names a control key by the character it *produces* (Ctrl-R is keyCode
`VK_R`, keyChar `0x12`), so `KeyNotation.name` takes the letter from the key
code. A character outside the Basic Multilingual Plane arrives as two key-typed
events; a command holds the high surrogate until the low one comes, so `f😀`
is one key.

### The engine (`org.armedbear.j.vim`)

| class | role |
|---|---|
| `VimInputHandler` | events → key names → dispatch; the insert session, dot-repeat recording and replay, counted inserts, the `/` and `:` hand-off |
| `VimState` | per-editor: mode, desired column (`STICKY_EOL` after `$`), insert session and its undo step, autoindent tracking, replace-mode record, last `f`/`t`, last search, pending register, last selection |
| `VimMode` | `NORMAL INSERT REPLACE VISUAL VISUAL_LINE VISUAL_BLOCK`, with caret shape and indicator |
| `CommandBuilder` | the command being typed: counts, keys, pending operator |
| `KeyStrokeTrie` | key sequence → binding, one trie per mapping mode; `FULL` / `PARTIAL` / `NONE`, with a `fallback` for a binding that is also a prefix |
| `VimKeyMap`, `VimCommand`, `MappingMode` | the table, parsed into tries; one row is one `VimCommand` |
| `KeyNotation`, `CodePoints` | vim's `<C-w>` notation both ways; stepping by whole characters |
| `VimMotions`, `MotionContext`, `MotionKind` | motions by table name; each resolves its kind at run time (`;` is inclusive or not by direction) |
| `RangeNormalizer`, `VimRange` | motion + kind → the range an operator acts on (`:help exclusive`, the `w`-with-operator clip); a linewise range carries its last line (`VimRange.lines`) |
| `VimOperators`, `VimActions`, `VimTextObjects`, `VimVisual` | the commands, by table name |
| `VimRegisters`, `VimMarks` | registers on j's: files for `"a`-`"z`, the kill ring for the unnamed register and `"1`-`"9`, the clipboards for `"+ "*`; marks on j `Marker`s, A to Z as j's bookmarks |
| `VimSearch`, `VimSearchPrompt`, `VimRegex` | `/ ? n N * #`; vim's pattern dialect translated for `java.util.regex` |
| `VimEx`, `VimExPrompt`, `VimExCommands`, `VimExSubstitute`, `VimExSort` | the `:` line: parsing, ranges, commands |
| `VimOptions`, `VimrcParser` | `:set` and the vimrc subset |

Dispatch is CodeMirror's shape: match keys in the trie for the current mapping
mode (operator-pending when an operator is waiting), then run the row by its
kind. An operator waits for a motion or text object; the pair goes through
`RangeNormalizer` and the operator gets a `VimRange`.

**Reuse j, do not reimplement it.** This is a standing rule of the project: a
small divergence means adjusting j's command to match vim, a large one means
an argument or flag on it; never a second copy inside the vim layer. What that
produced, all reachable from j's key maps and `executeCommand` too:

- new j commands: `joinLines`, `duplicateLines`, `moveLinesUp`/`Down`,
  `shiftLinesLeft`/`Right`, `toggleCaseRegion`, `toBottom`, `replaceChar`,
  `findCharInLine`/`Backward`, `tillCharInLine`/`Backward`,
  `moveToWindowTop`/`Middle`/`Bottom` (in `Lines`, `CaretCommands`,
  `RegionCommands`), `incrementNumber`/`decrementNumber` (`NumberCommands`,
  whose `plan` is vim's CTRL-A arithmetic on a line's text);
  `adjacentWindow`, `balanceWindows`, `gotoWindow` (`Editor`, over
  `Frame.adjacent` and `EditorPane.balance`);
- new arguments: `sortLines` flags (`Sort.Options`, vim's `:sort` letters),
  `pageDown`/`pageUp vim`, `prevBuffer alternate`, `wordRight vim`,
  `splitWindow vim` and `vsplitWindow vim` (the caret stays top or left),
  `killWindow vim` (the caret goes where the space goes),
  `saveAs FILE`, `saveCopy FILE`;
- borrowed as-is: `Search` for matching, `Region` for range text and deletion,
  `Marker` for marks, `Buffer.beginCompoundEdit` for undo, `newlineAndIndent`
  and `indentLine` for indentation, `toCenter`/`toTop` for `zz`/`zt`;
- `Editor.findMatchInternal` for `%`, with a `vim` flag for vim's smart
  matching: brackets in `"..."` (counted from the start bracket, per line,
  only on lines with an even number of quotes) and in `'x'` are skipped, and
  an escaped bracket pairs only with an escaped one.
- `Editor.deleteRegion(start, end)`, the mark-and-dot delete the vim layer
  had spelled out at every site, caret at the start for undo;
- `Words.backwardToWordStart`, `b`'s scan, for insert-mode `<C-w>`.
- `Paragraphs.find`, vim's findpar, for `{ } ]] [[ ][ []`, and
  `Sentences.find`, its findsent, for `( )`; `Editor.findUnmatched`,
  `findMatchInternal`'s scan from a caret rather than a bracket, for
  `[( ]) [{ ]}`. Each is a j command too (`forwardParagraph`,
  `forwardSection`, `forwardSentence`, `findUnmatchedBracket`).
- j's last search (`Editor.getLastSearch`) for `/ n * #`, and its
  highlighting and `clearSearchHighlight` for `hlsearch` and `:noh`;
- j's `JumpList` for `<C-o> <C-i>`, and its bookmarks for the file marks
  `A`-`Z`.
- `NumberCommands.addOverLines` for visual `<C-a>` and `g<C-a>`, which
  j's `incrementNumber` uses over a selection; `openFileInSplit` and
  `openFileInVsplit` for `:sp FILE` and `:vs FILE`.
- j's registers (`Registers`), kill ring and clipboards (`KillRing`) for
  vim's registers: a register is its text, and how vim took it is
  remembered for the texts vim wrote this session, and otherwise taken as
  lines when the text ends in a newline.
- `Block`, new in j, for visual block: screen columns over lines, tabs the
  edge cuts split into spaces and the rest kept. j's own column selection
  uses it too: `Region.toBlock`, with `deleteColumn` (cut, delete) and
  `pasteColumn` on `Block.delete` and `Block.put`, which keep the tabs j
  used to expand on every line they touched.
  `Position.moveOntoCol`, `moveToCol` staying on a tab rather than past it,
  for its corners and for `j k |`.

`CaretCommands.findCharacter` and `replaceChars` work in code points, so `f`,
`t` and `r` take an emoji.

### Configuration as data

The built-in bindings are `src/org/armedbear/j/vim/default-keymap.conf`; nothing
is hard-coded. One row per binding:

```
# modes  keys            kind      command           args
n,v,o    w               motion    moveByWords       forward
n,v,o    f<character>    motion    moveToCharacter   forward,inclusive
n,o,v    d               operator  delete            -
n        x               keytokey  dl
n,v      zz              command   toCenter          -
```

Kinds: `motion operator action textobj search ex keytokey command idle`.
`command` runs one of j's named commands (with `param=` and `once`), which is
how `zz`, `<C-f>` and `<C-^>` are j's own. `keytokey` stands for other keys, so
`x` *is* `dl`. `<character>` is a placeholder for any one key, which is how
`f`, `r`, `m`, `"` and `@` take their argument. Args are `name=value` or a bare
`name` meaning true; `keepColumn` on an action tells the runner not to clear the
desired column.

`VimrcParser` reads the parts of `~/.config/j/vimrc` it understands --
`map nmap vmap xmap omap imap cmap` and their `noremap`/`unmap` forms, `set`,
`let mapleader` -- into the same tries, later rows winning. `vmap` and `xmap`
both write the one visual mode. A right-hand side `:cmd<CR>` runs the j
command of that name, else the vim ex command. Everything else (`mapclear`
included) is logged and skipped.

**There is no `'timeoutlen'` timer.** A binding that is also a prefix of a
longer one (`,` and `,d`) is held as the trie's `fallback` and runs as soon as
the next key shows the longer one is not coming. Deterministic and testable;
it differs from vim only for a mapping the user never completes.

### Insert sessions, dot-repeat and counts

An insert session opens one compound undo edit (`VimState.beginInsert`) and
Escape closes it. **Dot-repeat records keys, not a command object**: every key
of a change is recorded as typed, insert-mode text included, and `.` replays
them through `runKeys`, so a repeat runs the same command rather than an
approximation. The session also keeps its own keys (`VimState.insertKeys`),
and a count before an insert (`3iab<Esc>`, `3o`, `3R`) makes Escape replay them
count−1 more times -- opening a line first for `o`/`O`. Replay goes through
`dispatchReplay`, which feeds insert-mode keys as typing would: characters as
text, Backspace and Enter and the named keys through j's bindings.

**An arrow splits an insert**, as in vim. `runInInsert` runs j's binding for
a named or chorded key; if the text did not change but the caret moved, the
undo step closes and a new one opens (`VimState.restartInsert`), what was typed
so far becomes the last change, the count is dropped, and the recording
restarts as `i` -- which only becomes the last change once something is typed
after it. A key that neither moves nor types (an arrow at the edge) is not
recorded, or a count's replay would run it where it does move. The rule is
"moved without typing", not a list of keys. The split sets `'[ '] '.` for the
part before it, `']` where typing stopped rather than where the arrow went;
after a split with nothing typed since, Escape sets only `'^`.

**Insert-mode bindings** are the `i` rows, run by `runInsertBinding`, which
holds a partial sequence (`insertBindingKeys`) so that `<C-r>` can wait for
its register -- the next key, typed or named, and Escape takes back only the
`<C-r>`. A row marked `unrecorded` is not recorded as its keys:
`insertRegister` types the register's text through `typeText`, which records
that text (a newline as `<CR>`, so it indents as Enter does), and `.` types
the same text again, as in vim. `<C-w>` and `<C-u>` (`insertDeleteBack`) take
`b`'s range, or the line but its indent, clamped to the caret's line and to
where typing began (`VimState.backStop`) -- a stop only when it is reached,
not when the caret starts there, which is vim's "stops once". Backspace within
the line does not move that start; one that joins the line to the one before
moves it to the join (`insertDeletedBack`). In replace mode they are
Backspace that many times.

**`<C-o>`** (`runOneCommand`) leaves insert mode with a return mode on
`VimState`, shown as `-- (insert) --`, and `resumeInsert` comes back once the
command is over: not while the builder, a register, a search, a `:` line or
visual mode is still waiting, and not at all if the command began an insert of
its own. It is a split, but the part before is held rather than made the last
change: inside the command, `.` still repeats the change before the insert, as
in vim, and afterwards the held insert becomes the last change unless the
command was one. At the end of a line the caret steps back as for Escape, and
comes back past the end if it is on the last character of that line, or `j`
and `k` aim past it (vim's `ins_at_eol` and `curswant`). A `<C-o>` from keys
being replayed only comes back within that replay.

A half-typed command (an operator, a register, a `/` or `:` line) is dropped as
a unit by `dropPartialCommand`, from Escape, an abandoned prompt, and the end of
`:normal`.

### Marks and the jump list

`VimMarks` holds every mark on j `Marker`s, vim's own ones too: each command
that changes or yanks text notes its extent with `noteChange` (`'[`, `']`,
and `'.` for a change), and an ex command with `noteLines`. The insert session
notes where typing began at its first key -- by line number, moved to the join
when a Backspace joins that line to the one before -- and Escape sets
`'[ '] '^ '.` from it (`markInsertStop`). A mark whose line was deleted is
gone, as in vim. `]`` and `[`` walk the lowercase marks only. A to Z, vim's
file marks, are j's bookmarks (`Editor.getBookmark`): one set for all of j,
in any file, moved by edits as bookmarks are; `` `A `` into another file is
a jump that switches buffers itself (`goToFileMarkElsewhere`), since a
motion can only return a place in this one.

A row with `jump` in its args is one of vim's jumps; `runMotion` records the
position it leaves (`VimState.jumped`) after working out where it goes, since
`''` goes to the jump *before* this one. `/`, `:N` and `:s` record theirs
themselves, and `:g` holds jumps while its commands run and records one. A
motion under an operator records none, as in vim. The list is j's own
(`JumpList`, which `jumpBack`, `jumpForward`, `pushPosition` and
`popPosition` use, and j's go-to-line, `bob`, `eob`, finds and tags record
into): vim's rules -- one entry a line, the newest kept; the first `<C-o>`
after a jump lists where it was typed -- on j `Marker`s, which follow their
text through edits and cross files. So `<C-o>` is an action, not a motion:
it may land in another buffer. One list for all of j, not one a window.

### Search and the `:` line

Both prompt in j's **location bar**, reusing the `incrementalFind` /
`executeCommand` pattern -- which is why the prompt is at the top of the window,
not the bottom (a documented divergence). `/` is an asynchronous motion: it
parks the half-built command (`PendingSearch`) and the prompt's Enter finishes
it, so `d/foo<CR>` works without a command-line mode. Focus does the
arbitration: while the text field has focus the display gets no keys. A
frameless editor (tests, `.` replay, `:normal`) has no location bar, so the
handler collects the line itself -- which is what lets the corpus and `.` type
`/foo<CR>` as one sequence.

`:` names that are not vim's own fall through to j's `CommandTable`, but the ex
parser owns the string: `executeCommand` would read a leading `(` as Lisp and
`a=b` as a property assignment.

`VimRegex` translates vim's dialect -- all four magic levels, groups and
backreferences, `\{n,m}` and `\{-}`, `\zs`/`\ze`, the `\@=` family, letter and
POSIX classes, `\%x..`, `~`, `\c`/`\C` -- and **refuses by name** what it cannot
express (`\%V`, `\&`, a `\zs` after a variable-width prefix) rather than quietly
misreading it. Every pattern goes through it: `/ ? n N * #`, `:s`, `:g`,
`:sort`, `/pat/` addresses. Vim finds the matches on a line by scanning from
the line's start, not from the caret; so does `VimSearch`.

**One last search, j's.** A vim search is kept as j's own last search
(`Editor.getLastSearch`), as a `VimSearch.Compiled`: a j `Search` that
remembers the vim query it came from. So j's `findNext` (F3) goes on with a
`/`, and `n` reads the query back -- or, after a find of j's own, runs that
`Search` as it is (`Query.own`), spelled for vim only for `:s//` and
messages. A bad pattern is kept too, as vim keeps it, matching nothing. The
`shareSearch` preference makes the search every window's or each window's,
in one place (`Editor.isSearchShared`), for both.

**Highlighting the matches is j's.** `Display` paints what
`Editor.getSearchMatches` names, behind the text and under the selection:
in simple mode the last search's matches (`Search.matchesOnLine`, the ones
`findNext` finds) when the `highlightSearchMatches` preference is on; with
an input handler, what it says -- vim's `hlsearch`, on by default as in
nvim, and incsearch's preview. The `:noh` state is j's too:
`clearSearchHighlight` hides the matches until the next search or
`findNext`, and `:noh` runs it. `:set` at the prompt is the vimrc's `set`,
and compiles the last search again, since `ignorecase` and `smartcase`
change what it matches.

**incsearch** runs off the prompt's `keyReleased`: `searchTyped` finds the
pattern so far from where `/` was typed (`PendingSearch.origin`), moves the
caret there without a jump, and shows the pattern as `VimState`'s preview,
its match through `getCurrentSearchMatch` in a colour of its own -- the
display has no focus, so no caret shows it. Enter, Escape and anything else
that drops the half-typed command put the caret and the window back first
(`endPreview`, inside `dropPartialCommand`), so the real search runs from
where it was typed. CTRL-G and CTRL-T are rows in the `c` map (vim's
`cmap`), which the prompt consults for a chord before its text field sees
it (`runCommandLineKey`); `searchStep` moves the preview a match on and the
search's start to just before it, so Enter and further typing find it -- vim's
own model, but for the count, which vim applies again after a step. The
switches and their defaults are one table, `VimOptions.SWITCHES`, read with
`isOn`.

### Visual mode

The selection **is** j's mark and dot, so what j paints and what vim thinks is
selected cannot drift apart, and every motion extends it without knowing visual
mode exists. The one disagreement -- vim includes the character under the
caret -- is bridged in `VimVisual.toRange`. Linewise selection needed
`InputHandler.isLinewiseSelection` and `Display` painting whole lines. After `$`
the caret stands for the line end (`STICKY_EOL`), and `o` carries that to the
anchor as the line's end itself.

Visual block is the same mark and dot, taken as j's `Block`: the screen
columns from one corner to the other, each corner taking in the character it
is on, over the lines between -- or, with `STICKY_EOL`, from the left column
to the end of each line. `VimRange.block` carries it to the operators, which
call `Block`'s edits (`delete`, `transform`, `insertOnEachLine`,
`shiftLeft`, `shiftRight`, `put`), each one undo step. `Display` paints it from
`InputHandler.getBlockSelection`, the offsets `Block.getOffsets` gives for
each line. `I`, `A` and `c` insert on the first line only; `Escape`
(`VimState.finishBlockInsert`) copies what was typed there onto the rest,
inside the insert's undo step. For `.`, `VimVisual.take` notes the
selection's shape as keys (`<C-v>2j3l`, `V2j`, `v4l`), and `afterCommand`
puts them in front of the recording.

## Semantics that decide whether it feels like vim

These are the things each reference emulator had to fix; treat them as
requirements.

1. The caret is *on* a character. Normal mode never rests past the last one;
   leaving insert steps back one.
2. Exclusive-motion promotion (`:help exclusive`): an exclusive motion ending
   in column 0 ends at the previous line's end and becomes inclusive, and
   linewise too if it started at or before the first non-blank.
3. `cw` is `ce` on a non-blank. `dw` on the last word of a line stops at the
   line end.
4. Counts multiply across operator and motion (`2d3w` is six words); the count
   belongs to the motion.
5. `j`/`k` keep the desired column; `$` makes it stick to the line end.
6. One undo step per insert session.
7. `.` repeats the whole change with its inserted text, and a new count
   replaces the old.
8. A register's type (charwise/linewise) decides how `p` puts it; numbered
   registers shift on multi-line deletes; `0` holds the last yank, `-` small
   deletes.
9. Mapping precedence, `noremap`, and ambiguous prefixes (`nmap jj` must not
   break `j`).
10. Motions at the buffer edges are asymmetric: forward ones clamp to the end
    (`de` on the last character deletes it), backward ones at the start fail.
11. `:help d`: a characterwise `d` across lines with only blanks before its
    start and after its end takes the lines whole
    (`RangeNormalizer.deleteRange`). Operator-pending `d` only -- not `c`, not
    visual -- but text objects and `/` too.
12. An inclusive end on an empty line takes nothing from it (`d$` there is a
    no-op, `dge` onto one keeps its newline); a visual selection ending on one
    takes its newline (`VimVisual.toRange`).

## Undo

`Buffer.beginCompoundEdit` opens a compound that absorbs every edit until
`endCompoundEdit`; nested compounds work.

| command | begin | end |
|---|---|---|
| `i a I A` | on entering insert, after the caret move | Escape |
| `o O` | *before* the new line, so `u` removes it too | Escape |
| `c cc s S C` | before the delete, sharing one compound with the insert | Escape |
| `R` | on entering replace | Escape |
| `x dd d{m} p J >> ~` | at command start | at command end |
| counted insert | one compound around every repeat | Escape |

- **j's undo restores the caret from where it is *at undo time*.** A command
  that moves the caret to what it changes records the move inside its own
  compound (`VimOperators.recordCaret`, an `addUndo(SimpleEdit.MOVE)`).
  Compounds undo in reverse, so the move is undone first. `c` is the
  deliberate exception: vim leaves the caret at the start of what changed.
- **The buffer-switch hazard.** A compound left open when the editor switches
  buffers absorbs every later edit to that buffer. `VimState.endInsert` is
  idempotent and is called from Escape and `editorDeactivated`, and keeps the
  buffer the compound belongs to.
- Do not add undo boundaries: compounds already are the boundaries.

## Rendering

- **Caret shape.** Block in normal and visual, bar in insert, underline in
  replace, drawn inside a selection. The block redraws the character under it
  in the background colour and its syntax font, and is as wide as the
  character (two UTF-16 units for an emoji).
- **Mode indicator.** `-- INSERT --` and the pending command (`2d`, `"a3`) come
  from the handler and are drawn by `StatusBar`. Not `Property.EMULATION`: that
  is per buffer, and two windows on one buffer would fight over it.
- **j repaints by line, and the caller names the lines.** This is the property
  that caused nearly every rendering defect (trap 8). `Display` has a
  package-private `isRepaintPending`/`clearRepaintPending` pair so a headless
  test can assert that a command which changes the screen without changing a
  line asks for a redraw.

## Testing

`nix-shell --run 'bb test'` (JUnit 4, headless), plus `bb check-core` (core
carries no non-JDK dependency) and `bb fmt-check`.

- **`EditorHarness`** (`test/src/org/armedbear/j/`) builds a real, frameless
  `Editor` and synthesizes the `KeyEvent`s AWT would send -- the real
  `Dispatcher`, the real `handleJEvent`, the real self-insert fallback. It
  must send what AWT sends, not what is convenient (trap 17). `mode(Mode)`
  switches the buffer's mode, for tests that need a mode's own bindings.
  `Editor` remembers its last status message so a test can read it (trap 19).
  `close()` fails a test that made j log an error (trap 37); a test that
  does so on purpose calls `forgetLoggedErrors`.
- **The conformance corpus.** `tools/vim-conformance.clj` translates
  CodeMirror's `vim_test.js` (MIT) into `test/conformance/vim/codemirror.conf`;
  what it cannot translate goes to `skipped.txt` with the reason. It is a
  **ratchet**: `passing.txt` lists the cases that pass, one that regresses fails
  the build, and newly passing ones are reported for adding. Promotion is
  deliberate. The corpus is CodeMirror's reading of vim and is wrong in places
  (ignorecase, JavaScript regex, edge motions); **nvim wins**, and those cases
  stay out with the reason in `passing.txt`'s header. Print every failure with
  `-Dvim.conformance.failures=all`; the listing holds NULs (a `:g` case), so
  grep it with `-a`. The generator turns a `doKeys` argument that is one of
  CodeMirror's key names (`Backspace`, `Down`) into vim notation, and resolves
  a `value:` that names a variable, where it once fell back to the shared
  fixture and gave the case the wrong document. The runner clamps `cursor` to
  the document, as CodeMirror's `setCursor` does.
- **`tools/vim-oracle.sh '<text>' <line> <col> '<keys>' ['<keys>'...]`** runs
  real nvim headless and prints the buffer and cursor. It settles every
  non-obvious expectation. Each extra argument is its own step behind an undo
  boundary -- the only way to ask about `u`. Columns are **bytes**; convert for
  multi-byte text. It takes `<Del>`-style notation. A failed motion aborts the
  rest of a `:normal`, so split steps where that matters. Twice it has given a
  plausible wrong answer rather than failing: check what it *ran*.
- **`tools/vim-screenshot.sh <out> <file> <keys>...`** runs j under Xvfb and
  photographs it after each key. **Anything that changes the caret, the
  selection or the status bar goes through it.** Export the build's JDK on
  `PATH` before `nix-shell -p xvfb xdotool imagemagick`. xdotool cannot type a
  character outside the BMP: AWT truncates its keysym (U+1F600 arrives as
  U+F600), so an emoji can only be tested through an input method.
  `drag:X1,Y1,X2,Y2` drags the mouse, for a split's divider; the drag
  leaves j's keyboard focus off the editor until a click in the text.
  Under Xvfb `xdotool key F3` arrives with Alt held, and j opens its Find
  dialog (Alt+F3): use Ctrl+G, which is also `findNext`.
- **`VimDocTest`** checks every key the "What is there" table in
  `doc/editmodes.html` names is bound in its row's modes, and every ex command
  it names runs. The docs once claimed `R` for several milestones with nothing
  bound to it.
- **Mutation-check every fix**: break it alone and watch only its own test
  fail. A test that passes before the fix is decoration. When running a single
  class outside `bb test`, rebuild first -- stale mutated classes have produced
  false failures more than once -- and expect five `VimM13Test` cases to fail
  for environmental reasons that `bb test` does not have.
- **Fuzz a port of a vim scan against nvim.** M20 generated some 17,000
  short random documents from a small alphabet (`. ! ? ) " \n` for
  sentences, `{ } \f .SH .PP` for sections, brackets, quotes and
  backslashes for `[(`), ran them all through one headless nvim with a
  vimscript loop (a fraction of a second), and compared j case by case in a
  throwaway JUnit test. It found four bugs the hand-written tests had not,
  two of them in older code (`%` and `:help d`), and one divergence it left
  documented, and gave the mutation checks the cases they were missing. One nvim session carries state from
  case to case, so check a surprising answer with `tools/vim-oracle.sh`.

## Traps

Each of these has bitten at least once. Read them before editing.

1. `Display.drawCaret` skipped drawing when a selection existed. Relaxed for a
   non-bar caret.
2. **Visual block vs `isColumnSelection`.** `Region` derives block columns from
   display state and j's column region is a strict rectangle, whereas vim's
   `<C-v>$` is ragged. Do not make `Region` ragged. M26 built `Block`
   instead, which works out each line's span itself; nothing in it goes
   through `Region`.
3. Mode key maps are half insert-mode electric characters (`CMode` `#`,
   `HtmlMode` `>`). The vim trie must win outright in the command modes.
4. Non-text buffers bind bare letters. Gate on `TYPE_NORMAL`.
5. `Buffer.getLine(int)` is O(n). Step with `Line.next()` for counted motions.
6. `Region.delete()` adjusts markers. New position bookkeeping must go through
   the same path or drift.
7. Regex divergence is a documented choice; refuse what cannot be expressed.
8. **j repaints by line, and the caller names the lines.** A caret move must
   mark the line it left (done centrally in `Display.drawCaret`); a change of
   appearance without a change of text -- a mode change, clearing a selection,
   `v` to `V` -- paints nothing unless it says so (`setMark(null)` is the trap,
   `Editor.unmark()` the fix); a jump must repaint what it crossed.
9. `Display.getY(Line)` answers for a line that is not on screen. Use
   `visibleY(Line)`, which returns −1.
10. j's undo restores the **mark** too, so a scratch mark set to reuse
    `deleteRegion` comes back on `u` as a selection nobody asked for.
11. **j's undo reads the caret at undo time.** Record moves with
    `recordCaret` inside the command's compound.
12. AWT names a control key by the character it produces. Read the key code.
13. **`setDot` moves the model caret; the display keeps its own column, and j
    pads an insert out to it.** Whenever a command sets the dot and then
    inserts, call `moveCaretToDotCol()` too.
14. **A `Line` does not survive a delete** -- a region delete merges the first
    and last lines. Hold line *numbers* across edits, and work bottom-up.
15. **j renumbers lines lazily.** `Buffer.renumber()` before reading a line
    number after an edit.
16. Two mechanisms in one command need one compound around them, or one undo
    produces text the buffer never held.
17. A synthesized keystroke must be the one AWT sends.
18. Every door a value comes in by needs the same guard: test a new failure
    mode through every path that reaches it.
19. When the fix is a message, the test has to read the message.
20. **The vim layer must ask before it edits.** j's commands call
    `checkReadOnly()`; the primitives the vim layer uses do not. The check sits
    at the choke points (`applyOperator`, `runAction` for `isEdit`, the
    text-changing ex commands, the insert-mode edits the layer does itself);
    any new edit path needs its own.
21. **A linewise range's end cannot say which line is its last.** It is the
    line after at offset 0, or the last line at its length, and on an empty
    last line the two are the same position. Read `VimRange.last`.
22. Insert-mode keys replayed as text: a replay must treat a named key as the
    key it is (Delete's character is DEL), through j's binding.
23. **A line end is a blank to vim's word scans; j's `Words` calls it
    `NEWLINE`.** Vim's `dec()` stops on the line end too (as `Position.prev()`
    does) and `cls()` counts it blank, which is what keeps a word from running
    on across a line break. A scan that treats `NEWLINE` as a class of its own
    can stop there: `ge` returned the slot past `word`, which looked right on
    its own -- the caret is clamped -- and was wrong under an operator.
    Skipping the slot instead joins `ab` and `cd` across the break into one
    word; the mutation check caught that.
24. Insert-mode arrows are j's commands, and differ from vim's at the edges:
    j's `right` wraps at a line end and `up` on the first line moves the caret
    sideways, where vim's do not move.
25. **Cancelling a prompt fires no `eventHandled`**, so nothing repaints the
    status bar: a `<C-o>:` abandoned with Escape was back in insert mode with
    `-- (insert) --` still showing. The prompts' `escape` calls it now, as
    their Enter always did. Only the screenshot showed it.
26. **j's Enter does not indent in plain text**, where the harness starts. A
    test of an indent Enter makes -- `<C-r>` of several lines, `<C-o>`
    taking an untouched indent away -- has to switch to Java mode, or it
    passes without testing anything; the mutation check caught one.
27. **j's redo leaves the caret where the edit left it**: `UndoMove.redo`
    restores the caret as it was when undo ran, so no arrangement of MOVE
    records makes redo land where the command was typed, as vim's does. It
    agrees for most commands and not for `<C-a>` or `p`; documented. A
    MOVE record after the edit is not needed for a one-line insert:
    `replaceChars` and `NumberCommands.add` have none, and undo is right.
28. **`EditorPane.root` left the kept window's leaf in its old split.** The
    next split then added to that split, which was no longer on screen, and
    nothing appeared: `<C-w>o` then `<C-w>s` did nothing. A bug in j's own
    `unsplitAllWindows` followed by `splitWindow`; the leaf is detached now.
29. **Headless nvim knows no screen columns.** `wincmd k` picks the window
    over the cursor's screen column, which headless nvim works out only on a
    redraw: without `redraw!` first it always said window 1. The window
    probes use a script that prints `winnr()` and `winlayout()`.
30. **A dragged divider stops the split layout following the weights**
    (`MultiSplitLayout` floating dividers off, for good), and a divider made
    after that has no place: the next split came out at the edge or not at
    all. `EditorPane.evenOut` puts every divider back evenly on each split
    and close once that has happened, as vim's `equalalways` does, and is
    what `<C-w>=` runs.
31. **A blank to vim is a space or a tab**, not Java's `isWhitespace`: a form
    feed is a paragraph boundary, and counted as a blank it moved `^` and
    `]]` off column 0 and made the `:help d` rule take whole lines.
    `CaretCommands.firstNonBlank` and `RangeNormalizer.deleteRange` say so
    now; the text objects still use `isWhitespace`.
32. **Vim past its last line is not a specification.** `d]]` from a last
    line starting with `}` sets the cursor one line beyond the buffer; nvim
    then deletes nothing, and `c]]` sometimes inserts and sometimes loses
    what is typed, depending on the buffer's history. j takes the motion as
    empty. Do not chase such cases.
33. **A paint before `Editor.displayReady()` draws only the background**,
    and `AdjustPlacementRunnable`, which sets it at startup, then called
    only `reframe` -- which repaints nothing when the window is already at
    the restored top line. So the file a session reopened stayed blank
    until something moved, depending on which came first: one start in
    three at M18, every start after M19 on Kevin's session. It now
    repaints every window once the flag is set. Found by starting j with a
    copy of the real config and session under Xvfb; the screenshot tool's
    `--no-session` start never showed it.
34. **`Display` paints a line in two places**: `paintTextLine`, for one
    line, and the loop in `paintComponentInternal`, for the whole window.
    Something drawn behind the text goes in both. hlsearch went into the
    first only, the unit tests (which ask the handler) passed, and the
    screenshot showed no highlights at all: a search repaints the whole
    window.
35. **j's caret is between characters; vim's is on one.** `moveToCol` takes
    a column inside a tab to the character after it, which is right for j's
    caret and wrong for vim's: `k` onto a tab landed past it, and a block's
    corner with it. `moveOntoCol` stays on the tab. Likewise a line that
    ends exactly at a block's left edge is not "short": `I` inserts at its
    end, and `c` over a block empties lines to just that.
    In visual mode vim goes one further: `j` and `k` may stop on the end
    of a short line (vim's `coladvance` "may stop on the NUL"), so
    `clampCaret` lets visual mode rest there too.
36. **Inserting a line break at the start of a line can split the other
    way.** After a block or selection empties a line, `insertString("\n"
    + text)` there may leave the old `Line` below the new text, so
    `line.next()` is not the pasted line. Count back from the caret, as
    `putLinewise` always did.
37. **An edit needs the buffer's write lock, and j only logs when it is
    missing.** `Line.setText` and `modified()` expect it, and undoing a
    line edit checks for it; without it j logs "called without write
    lock" or `BUG!` and carries on, so every test passed while Kevin's
    log filled up. j's commands take it themselves; vim's `u`, `<C-r>`,
    `:sort` and `Block` did not. `Buffer.withWriteLock` takes it, and the
    harness now fails a test that logged an error. Tests call
    `Editor.undo()`, which locks, not `Buffer.undo()`.
38. **A vimrc `noremap` was recursive.** Its keys went back through the
    whole map, so `vnoremap < <gv` found itself and logged "key map
    recursion". `VimKeyMap` now keeps the table as it was before the vimrc
    (`getBuiltIn`), and a key-to-key row without `remap` -- every
    `noremap`, and the table's own rows -- dispatches its keys there.
    And `>` / `<` over a charwise selection passed `range.last`, null for
    a charwise range, to `Lines.shift`, which took null as the end of the
    buffer: every line below was shifted before the NPE. `VimRange.lastLine`
    is the last line any range touches.

## Open work

The planned milestones are done. What is left, none of it blocking:

**Known gaps** (from the branch review, `dev-docs/VIM-REVIEW.md`, and the
milestones; each documented in `doc/editmodes.html`):

- `nostartofline`: nvim keeps the caret's column after `G`, `gg`, `dd`,
  `:N`, `<C-d>` and the like; j goes to the first non-blank, vim's old
  default.
- `d2w` across lines (`a\nb\nc`): nvim deletes to the end of line 2, j clips
  to the end of line 1. The word-motion clip has to be per word moved.
- `:g` runs bottom-up: `:g/^/m0` does not reverse the buffer, and the caret
  ends on the first match. Top-down needs Line identity that survives a
  region delete (it merges into the first line); tried and reverted.
- `.` after a characterwise visual command over several lines repeats it at
  the caret alone, where vim takes in as many lines again.
- Redo leaves the caret where the edit left it, vim where the command was
  typed (trap 27); they differ after `<C-a>` and `p`.
- The read-only registers (`. : / %`) and `=` are not built, nor
  `<C-r><C-o>`/`<C-p>`.

**Postponed** (Kevin, 2026-09-26): `:map` at the prompt, `gn`/`gN`, macros,
`is`/`as`/`it`/`at`, `:s` across lines and its `c` flag, `gj`/`gk`, `:marks`
as a listing, `:normal!` without mappings, `~` and `\u \U \l \L \e \E` in a
replacement, `\zs` after a variable-width prefix.

**Tidying** (review): `VimExPrompt` and `VimSearchPrompt` repeat their
scaffold; `VimOperators.lineNumbered` and `VimVisual.lineAt` repeat
`Lines.lineAt`; bare `:set` does nothing; the message `:w` gives on an
unmodified buffer; test classes named for milestones and reviews rather than
what they cover. In the conformance runner a case named `#` cannot be
ratcheted (comment syntax), and 23 cases wait on register and mode
assertions being ported.

## History

Branch `vim-editing`, from `ab7513524`. Every milestone was followed by a code
review before the next.

| | | commits |
|---|---|---|
| M0 | headless `Editor`, `EditorHarness`, the conformance rig | `5c417c34b` |
| M1 | the input seam; normal and insert modes; the key table, counts, motions | `111b69211`, `ffd897a9f` |
| M2 | `d` and `c`, the range rules | `08d89ba2c` |
| M3 | registers, yank and put | `53a819e29` |
| M4 | marks; visual and visual-line | `aeb894683`, `dde3ebd4e` |
| M5 | undo, join, replace, case, indent, `%`, `H M L` | `97fdfbfa4` |
| M6 | block caret and mode indicator | `25115eaa6` |
| M7 | vimrc, user mappings, `:set` | `e817ba403` |
| M8 | dot-repeat | `05b7a2136` |
| M9 | the rendering defects from review | `0f71e7bd3` … `02ad3d993` |
| M10 | runtime motion kinds, text objects | `81446c7ce`, `56c712a81`, `71e7b6e5d` |
| M11 | search | `a8f9378c4`, `84cb7a8a5` |
| — | undo gives back the caret; control keys; `R` | `6524fe221` … `103bce404` |
| M12 | the `:` line: ranges, `:s :sort :g :v :normal :j :m :t :delmarks` | `bc38550a1` … `efb83dc36` |
| — | `zz zt zb`, `f t H M L r` as j commands | `590ce1e6e`, `6cb23626a` |
| M12.5 | vim's regular expressions | `504a6f600`, `0434f1afd`, `6204e26c7` |
| M13 | scroll, insert indent, `:w`, `<C-^>`, autoindent, visual bindings, surrogates | `1a0b7f2da` … `f7df39d26` |
| M14 | docs pass, `VimDocTest` | `7794cfe17` |
| — | branch review: linewise last line, `:s///g`, `:sort u`, prompts, emoji put | `dab86d5a6` … `42f6390cc` |
| — | M13 leftovers: counted inserts, Enter autoindent, `O` indent, `v$o`, emoji `f t r`, visual `J` undo caret; replayed Delete/Tab | `1485050bf` … `85c28d320` |
| M15 | corpus key names and documents; `ge` over line ends; `:help d`; `%` and quotes; an arrow splits an insert | `db9e981c6` |
| M16 | `'. '[ '] '^`, the jump list, `''` and ````, `<C-o>` `<C-i>` | `d31fed8ec` |
| M17 | insert-mode `<C-w> <C-u> <C-r> <C-o>`; the marks a split leaves | `219b055f0` |
| M18 | `<C-a>` `<C-x>`, visual and `g`; `NumberCommands` | `35bc31aa9` |
| M19 | windows: `<C-w>` and `:sp :vs :q :clo :on` | `22750f115` |
| — | review fixes: `visualPut`'s delete, the `J` mark divergence | `30f790a4d` |
| M20 | `]] [[ ][ []`, `( )`, `[( ]) [{ ]}`; `{ }` on vim's findpar | `0a337b057` |
| M21 | `hlsearch`, `:noh`, `:set` at the prompt; `incsearch`, `smartcase` on, `shareSearch`; `c` map, CTRL-G and CTRL-T | `462bcfedc` |
| M22 | one last search for j and vim; highlighting and `clearSearchHighlight` in j | `28fb66223` |
| M23 | jump list on j's `JumpList` (was the position stack); file marks as bookmarks | `5a012f6af` |
| M24 | `incrementNumber` over a selection; `openFileInSplit`, `openFileInVsplit` | `30f71e596` |
| M25 | vim's registers on j's: register files, kill ring, clipboards | `e28c4d22d` |
| M26 | visual block on j's new `Block`; `.` over the selection's shape; `j k |` onto a tab, and in visual mode onto a line's end; `shiftwidth` read; j's column selection on `Block`; the write lock, and the harness failing on a logged error; `<` `>` over a charwise selection; vimrc `noremap` | `44d6695a9` |
| — | branch review: `}` at the end, `J` before `)`, `dw` before an indent, `di(` across lines, `:s` ranges, `;` ranges, vimrc ex fallback | `0ae3ca616` |

After the review: 1051 tests, conformance 167 of 253 (167 ratcheted).

### What the work learned

- **Almost every defect found after Phase 1 was a rendering defect**: the model
  right, the unit tests green, the screen wrong. The screenshot tool found each
  one, plus two the fixes introduced. And a screenshot proves what was painted,
  not what would be: a stale line that is never repainted looks fine.
- **Tests written alongside a feature agree with the model it already has.**
  Driving the editor found what they could not: undo returned the text but not
  the caret; every control binding fell through to j; a documented `R` was
  bound to nothing. Checking one axis across a whole family (the undo caret of
  `J dd o O >> p`) found seven wrong, not one.
- **The corpus is evidence, not a verdict; so is a review; so is your own
  earlier reading.** nvim decides. Several "shipped, wrong" corpus cases were
  CodeMirror being wrong, and one of our own tests pinned a Java-regex
  divergence.
- **j's plumbing was often better than a new version.** `Sort`, `RegionCommands`
  and `newlineAndIndent` could not have the display-column bug a hand-rolled
  version had, and turning vim needs into j commands gave simple mode
  `joinLines`, `moveLinesUp`, `duplicateLines` and the rest.
- **A heuristic that enumerates cases is a list of the ones you thought of.**
  Replace mode's Backspace became correct when it checked the caret against
  where the last keystroke left it instead of listing the keys that move it --
  which turned out to be vim's own rule.
- **A probe that always succeeds is worse than none**: the screenshot tool's
  ffmpeg check answered help for any format name and picked the broken branch
  on exactly the machine it guarded.
- **Targets based on bucket counts overshot every time**, because the corpus
  barely tests some features (one text-object case) and tests others through
  CodeMirror's quirks. Unit tests against nvim carry what the corpus cannot.
- **The mutation check also catches fixes for bugs that are not there.** In
  M21 `set number` looked as if it read as `no` + `mber`; the fix went in with
  a test, and breaking the fix changed nothing -- `number` starts with `nu`.
  Both came out again.
