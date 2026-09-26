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
mode indicator, the pending-command text and whether the selection is linewise
(`InputHandler`'s default methods), and hears `editorDeactivated`.

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
| `VimMode` | `NORMAL INSERT REPLACE VISUAL VISUAL_LINE`, with caret shape and indicator |
| `CommandBuilder` | the command being typed: counts, keys, pending operator |
| `KeyStrokeTrie` | key sequence → binding, one trie per mapping mode; `FULL` / `PARTIAL` / `NONE`, with a `fallback` for a binding that is also a prefix |
| `VimKeyMap`, `VimCommand`, `MappingMode` | the table, parsed into tries; one row is one `VimCommand` |
| `KeyNotation`, `CodePoints` | vim's `<C-w>` notation both ways; stepping by whole characters |
| `VimMotions`, `MotionContext`, `MotionKind` | motions by table name; each resolves its kind at run time (`;` is inclusive or not by direction) |
| `RangeNormalizer`, `VimRange` | motion + kind → the range an operator acts on (`:help exclusive`, the `w`-with-operator clip); a linewise range carries its last line (`VimRange.lines`) |
| `VimOperators`, `VimActions`, `VimTextObjects`, `VimVisual` | the commands, by table name |
| `VimRegisters`, `VimMarks` | in-memory registers typed charwise/linewise; marks on j `Marker`s |
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
  `RegionCommands`);
- new arguments: `sortLines` flags (`Sort.Options`, vim's `:sort` letters),
  `pageDown`/`pageUp vim`, `prevBuffer alternate`, `wordRight vim`,
  `saveAs FILE`, `saveCopy FILE`;
- borrowed as-is: `Search` for matching, `Region` for range text and deletion,
  `Marker` for marks, `Buffer.beginCompoundEdit` for undo, `newlineAndIndent`
  and `indentLine` for indentation, `Editor.findMatchInternal` for `%`,
  `toCenter`/`toTop` for `zz`/`zt`.

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
`map nmap vmap xmap omap imap` and their `noremap`/`unmap` forms, `set`,
`let mapleader` -- into the same tries, later rows winning. A right-hand side
`:cmd<CR>` runs a j command. Everything else is logged and skipped.

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

A half-typed command (an operator, a register, a `/` or `:` line) is dropped as
a unit by `dropPartialCommand`, from Escape, an abandoned prompt, and the end of
`:normal`.

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

### Visual mode

The selection **is** j's mark and dot, so what j paints and what vim thinks is
selected cannot drift apart, and every motion extends it without knowing visual
mode exists. The one disagreement -- vim includes the character under the
caret -- is bridged in `VimVisual.toRange`. Linewise selection needed
`InputHandler.isLinewiseSelection` and `Display` painting whole lines. After `$`
the caret stands for the line end (`STICKY_EOL`), and `o` carries that to the
anchor as the line's end itself.

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
- **The conformance corpus.** `tools/vim-conformance.clj` translates
  CodeMirror's `vim_test.js` (MIT) into `test/conformance/vim/codemirror.conf`;
  what it cannot translate goes to `skipped.txt` with the reason. It is a
  **ratchet**: `passing.txt` lists the cases that pass, one that regresses fails
  the build, and newly passing ones are reported for adding. Promotion is
  deliberate. The corpus is CodeMirror's reading of vim and is wrong in places
  (ignorecase, JavaScript regex, edge motions); **nvim wins**, and those cases
  stay out with the reason in `passing.txt`'s header. Print every failure with
  `-Dvim.conformance.failures=all`.
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
- **`VimDocTest`** checks every key the "What is there" table in
  `doc/editmodes.html` names is bound in its row's modes, and every ex command
  it names runs. The docs once claimed `R` for several milestones with nothing
  bound to it.
- **Mutation-check every fix**: break it alone and watch only its own test
  fail. A test that passes before the fix is decoration. When running a single
  class outside `bb test`, rebuild first -- stale mutated classes have produced
  false failures more than once -- and expect five `VimM13Test` cases to fail
  for environmental reasons that `bb test` does not have.

## Traps

Each of these has bitten at least once. Read them before editing.

1. `Display.drawCaret` skipped drawing when a selection existed. Relaxed for a
   non-bar caret.
2. **Visual block vs `isColumnSelection`.** `Region` derives block columns from
   display state and j's column region is a strict rectangle, whereas vim's
   `<C-v>$` is ragged. Do not make `Region` ragged; build a block selection of
   per-line spans driving `Region` line by line.
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

At `85c28d320`: 755 tests, conformance 136 of 253 (129 ratcheted).

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
