# Command and preference metadata: research

Today a command is a name and a lambda in `CommandTable` (434 of them), and a
preference a name and a default in `Property` (180). Everything else about
them lives elsewhere, by hand: the summary and help in `doc/commands.html` and
`doc/preferences.html` (423 and 193 entries, written separately), the summary
the action finder shows (scraped back out of the HTML by
`bb command-summaries`), the icon (a table in `ActionTextFieldHandler`), the
key bindings in the docs (typed in, and wrong when a binding moves: Ctrl P,
Ctrl T, Ctrl E all had to be found and fixed by hand), and "see also" links.
Nothing checks that a command has docs, or that documented keys are bound.

## What others do

**VS Code.** Metadata is declarative, in the extension's `package.json`:
`contributes.commands` gives each command id a `title`, `category`, `icon`
and `enablement`; `contributes.keybindings` the keys (with a `when`
clause); `contributes.configuration` each setting as JSON Schema (`type`,
`default`, `enum`, `description`/`markdownDescription`, `deprecationMessage`).
The code only registers the handler by id. The Command Palette, the
Keyboard Shortcuts editor and the Settings UI are all generated from these;
so is much of the extension's reference documentation.

**IntelliJ.** Actions are classes registered in `plugin.xml`
(`<action id class text description icon>`, with `<keyboard-shortcut>` per
keymap); the text and description usually come from a resource bundle
(`action.<id>.text`, `action.<id>.description`) so they can be translated.
Find Action, menus and the keymap settings all read the same registration.
Settings are `Configurable`s with their own UI; the help is separate.

**Emacs.** The documentation is in the definition: a `defun` has a docstring
whose first line is the summary, `(interactive)` marks it a command, and
`describe-function` and `where-is` show the docstring and the keys live, from
the running keymaps. Settings are `defcustom`s with a docstring, `:type`,
`:group` and `:options`, from which the Customize UI is generated. The
manual (Texinfo) is written separately, but the reference help is always the
code's own.

**Neovim.** `nvim_create_user_command(name, fn, {desc=, nargs=, complete=})`
and keymaps take a `desc` (which which-key and telescope show). The help
files are hand-written vimdoc, but the built-in options and Lua API docs are
generated from source (`gen_vimdoc.py`, `gen_options`).

**Helix.** Commands are defined in Rust with their doc string beside them
(`static_commands!` pairs each with a description; typable commands carry a
`doc` field). `cargo xtask docgen` generates the book's command and keymap
reference pages from the source, and CI fails if they're stale. The command
palette shows the same text.

**Zed.** Actions are declared with macros (`actions!`, `impl_actions!`); doc
comments on them become the descriptions shown in the command palette and
the keymap editor, and settings structs carry doc comments that generate the
JSON schema used for completion and docs.

**Sublime.** `.sublime-commands` files give palette captions; key bindings
are `.sublime-keymap` JSON; documentation is separate.

Common ground: one definition per command holds its summary, longer help,
icon and category; keys come from the keymaps, not the docs; the palette,
the help and the reference pages are generated, and a check keeps them in
step.

## Options for j

**A. An annotation on each command method.**
`@JCommand(name = "openFile", summary = "...", icon = "document-open",
seeAlso = {"recentFiles"})`, read by an annotation processor at build time to
generate the command table (no reflection at startup) and `commands.html`.
Close to Helix and Zed. But most commands are lambdas over static methods
today, some take arguments and some don't, extensions register by name, and
long help is HTML paragraphs, which are poor inside annotation strings.

**B. A resource beside the code.**
`commands.md` (and `preferences.md`): one section per command, with a few
fields up front (summary, icon, see also, argument) and the help in Markdown
after. `CommandTable` keeps registering the code; `bb docs` renders
`commands.html` and `preferences.html` from the Markdown, with each entry's
key bindings read from the keymaps (j's and vim's) rather than typed in; a
test fails when a registered command or preference has no section, a section
names nothing registered, or the generated pages are stale. Close to VS Code
(declarative) and IntelliJ (resource bundles), and Markdown is easy to edit
and diff. Extensions ship their own `commands.md`.

**C. Builder calls in `CommandTable`.**
`add("openFile", FileCommands::openFile).summary("...").icon(...)`.
Keeps everything in one place but puts prose in Java strings and makes
`CommandTable` much longer.

## Recommendation

B, with the summary as the section's first sentence so it reads like Emacs'
docstrings:

```markdown
## openFile
icon: document-open
see: recentFiles, findFileInProject

Finds a file under the current buffer's directory, then in the project...
```

- The action finder, F1 help and `describeBindings` read the same metadata
  (loaded once, from the jar), and `command-summaries.properties` and the
  icon table in `ActionTextFieldHandler` go away.
- "Default key mapping" lines are generated from `KeyMap`, every mode's map
  and the vim table, so they can't go stale; per-mode and vim keys are
  listed separately.
- `Property` gets the same: type and default come from the code; the
  description and allowed values from `preferences.md`.
- A first step converts today's HTML entries to Markdown mechanically, so the
  pages don't change when the generator takes over.

Not recommended: generating the Java table from metadata (A's processor).
Registration in code is simple and type-checked already; what's missing is
the documentation side and the check that ties the two together.
