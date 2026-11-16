# Atomic state files: plan

J's state files are written in place today: `getOutputStream()` truncates the
real file and writes into it, so a crash, a full disk, or a kill mid-write
leaves a half-written `props`, `session.xml`, `recent`, or register. The
fix is what `util/AtomicFiles.write` already does for the port file, the
stash note, and the changelists: write the bytes aside in the destination
directory, then move them over the target, atomically where the file system
can. This covers the nine in-place writers and the two delete-then-rename
callers in the TODO item.

## Progress

- [x] **popup defaults** (`Transients.saveDefaults`) — done first as the
      smallest: already `Path`-based, no `File.toPath()` bridge needed.
      `Properties.store` into a `ByteArrayOutputStream`, then
      `AtomicFiles.write(file, bytes, true)`; the manual
      `Files.createDirectories(file.getParent())` dropped (`AtomicFiles`
      creates it). `TransientPopupTest` (33) round-trips the real file;
      new `util/AtomicFilesTest` (20) pins the utility's invariants —
      whole write, replace, no `.new` residue, parent creation,
      owner-only perms, target untouched and temp cleaned up on a
      refused move, empty file, touches nothing until `close()`,
      idempotent `close()`.
- [x] **the other eight in-place writers + the two delete-then-rename
      callers** — all done. `Autosave.flush`, `Aliases.save`,
      `RecentFiles.save`, `FileHistory.save`, `Registers.setText`,
      `KeyMap.writeKeyMap`, `Cache.saveCatalog`, `TagFileCatalog.save`,
      `Session.save`, `SessionProperties.save` now write through
      `AtomicFiles`, `owner=true` throughout. The `Properties.store`
      writers and the raw-text writer take the `write(bytes)` path; the
      text writers that wrap the stand-in in a `BufferedWriter` keep the
      streaming `newWriter` shape. `File.toPath()` is the `File`→`Path`
      bridge. The session's temp-in-temp-dir + `deleteRename` tail and
      the props three-way delete-rename are gone; `props~` is now a
      `Files.copy` backup.
- [x] **review fixes** — three issues from the code review of the
      migration:
      - `SessionProperties.save`: the backup `Files.copy` now has its own
        `catch`. In the first cut it shared the primary write's `try`, so
        a failed backup (disk full, unwritable or directory-shaped
        `props~`) skipped `AtomicFiles.write` entirely and the new props
        were never saved — the old code logged the backup failure and
        went on to install the new props.
      - `Autosave.flush`, `Aliases.save`, `Transients.saveDefaults`,
        `Registers.setText`: switched from the streaming stand-in to the
        byte[] path. `Properties.store` and a raw string write straight
        through a stream with no buffering layer, so a disk-full
        mid-store threw in the body while the stand-in's `close()` still
        moved the *partial* temp over the good file. Building the bytes
        first closes that gap.
      - `RecentFiles.save`: `changed = false` moved back outside the
        inner try-with-resources. The "cleanup nested trys" pass had
        pulled it inside, where it ran before `close()` (the move), so a
        failed save cleared the flag and the dirty list was never
        retried.

## The utility

`org.armedbear.j.util.AtomicFiles.write(Path file, byte[] bytes, boolean owner)`:

- writes `bytes` to a temp file in `file`'s own directory (created if
  missing), then `Files.move` with `REPLACE_EXISTING` + `ATOMIC_MOVE`,
  falling back to a plain replace where atomic move is unsupported;
- the temp is removed if it was not moved;
- `owner` asks for `rw-------` on the temp, which the move carries to the
  target. On POSIX `createTempFile` is owner-only either way; the flag
  states intent and covers file systems without POSIX permissions.

Two shapes land the bytes:

- **`write(Path, byte[], owner)`** — the writer builds its whole body in
  memory first, then one call writes the temp and moves it. Used wherever
  the body is a `Properties.store` or a raw string, because those write
  straight through a stream: a streaming stand-in's `close()` moves the
  temp unconditionally, so a disk-full mid-store would move a *partial*
  file into place. With the bytes already whole, the write either lands or
  doesn't. All these files are small (kilobytes), so buffering the body
  is free.
- **`newWriter` / `newOutputStream`** (in `File` and `Path` flavors, each
  taking the `owner` flag) — a `Writer`/`OutputStream` that writes to the
  aside file and moves it over the target on `close()`, so the
  try-with-resources shape stays. Used only where the caller wraps the
  stand-in in a `BufferedWriter`: on disk-full the flush inside
  `BufferedWriter.close()` throws *before* the move runs, so the target
  survives. The guarantee is narrower than `write`'s — a non-IO exception
  from the body after bytes have reached the stream would still move a
  partial temp — but for these producers (well-formed entries, no
  serializer that can throw mid-loop) that path is unreachable.

`File.toPath()` bridges j's `File` to `java.nio.file.Path`, guarded like
`getInputStream`/`getOutputStream`: a remote or null file throws
`NotSupportedException`.

## Shared conversion: `File.toPath()`

Every caller today holds an `org.armedbear.j.File` (j's own abstraction,
local/FTP/HTTP/SSH) and `AtomicFiles` wants a `java.nio.file.Path`. Add
one method to `File`, mirroring the guards on `getInputStream`/
`getOutputStream` (File.java:881):

```java
/** The local path, for NIO. */
public java.nio.file.Path toPath() {
    if (isRemote)
        throw new NotSupportedException();
    if (file == null)
        throw new NotSupportedException();
    return java.nio.file.Path.of(canonicalPath);
}
```

`Path.of(canonicalPath())` was already the pattern in `getPermissions`/
`setPermissions`; those now call `toPath()` too. This keeps the call
sites honest: a remote state file fails loudly, the same way
`getOutputStream` fails today.

## The in-place writers

Each change keeps the exact same writer chain so bytes are byte-for-byte
identical to what was on disk before — same UTF-8, same `newLine()`
separators — and swaps the target for the atomic one. `owner=true` for
all of them: this is the user's private state — paths, registers,
session, properties. Note the one-time side effect: files that existed
with the umask default (e.g. 644) become 600 on the first atomic write.
That is the intent of the flag and a privacy improvement; call it out in
the commit message.

| File | Class / method | Shape |
|---|---|---|
| `<state>/autosave/catalog` | `Autosave.flush()` (Autosave.java:81) | `Properties.store` into a BAOS, then `AtomicFiles.write(catalogFile.toPath(), bytes, true)` |
| `<state>/popup-defaults.properties` | `Transients.saveDefaults()` (Transients.java:73) | store into BAOS, `AtomicFiles.write(file, bytes, true)`; the `Files.createDirectories(file.getParent())` is gone — `AtomicFiles` creates the parent |
| `<config>/aliases` | `Aliases.save()` (Aliases.java:101) | BAOS + `AtomicFiles.write(file.toPath(), bytes, true)` |
| `<state>/recent` | `RecentFiles.save()` (RecentFiles.java:188) | `BufferedWriter` over `newWriter(file, true)`; `changed = false` only after the move in `close()` |
| `<state>/files.xml` | `FileHistory.save()` (FileHistory.java:138) | `BufferedWriter` over `newWriter(file, true)` |
| `<data>/registers/<name>` | `Registers.setText()` (Registers.java:66) | `text.getBytes(UTF_8)` + `AtomicFiles.write(file.toPath(), bytes, true)`; the manual `dir.mkdirs()` is gone (`AtomicFiles` creates parents), the `dir == null` and `file == null` checks stay |
| key map (user-chosen) | `KeyMap.writeKeyMap(File)` (KeyMap.java:669) | `PrintWriter` over a BAOS, then `AtomicFiles.write(file.toPath(), bytes, true)` — outside the `PrintWriter`, which swallows close errors |
| `<cache>/cache/catalog` | `Cache.saveCatalog()` (Cache.java:134) | `BufferedWriter` over `newWriter(catalogFile, true)` |
| `<cache>/tagfiles/catalog` | `TagFileCatalog.save()` (TagFileCatalog.java:137) | `BufferedWriter` over `newWriter(catalogFile, true)`; the `# date` header kept |

The `Properties.store` writers and the raw-text writer take the byte[]
path: `store` writes straight through a stream, so a streaming stand-in
would move a partial temp on a disk-full mid-store. The text writers that
wrap the stand-in in a `BufferedWriter` keep the streaming shape — the
buffer's flush inside `close()` throws before the move on disk-full. No
format changes anywhere: loaders, version lines, and separators stay
exactly as they were.

## Session.save(): delete-then-rename → one atomic write

Before the migration, `Session.save()` wrote the XML to a temp file in
the *temp* directory, then `Utilities.deleteRename(tempFile, file)`:
delete the target, rename the temp over it, retry five times with
`System.gc()` on Windows, fall back to overwriting in place. The
cross-directory move is exactly what `ATOMIC_MOVE` can't promise and what
the retry dance papers over.

The migration replaces the whole tail: build the XML into a
`ByteArrayOutputStream` (same `OutputStreamWriter` chain, same content),
then `AtomicFiles.write(file.toPath(), bytes, true)`
(Session.java:237). The temp file in the temp directory disappears
entirely; the aside file lands next to `session.xml`, so the move is
same-directory and atomic on any POSIX volume.

## SessionProperties.save(): keep the backup, drop the dance

`SessionProperties.save()` (SessionProperties.java:255) used to do two
delete-renames: `props` → `props~` (backup), then temp → `props`. The
constructor (SessionProperties.java:40) falls back to `props~` when
`props` is missing, so the backup is load-bearing and stays. What goes is
the mechanism:

```java
public void save() {
    try {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, null);
        if (file.isFile()) {
            // The backup is secondary: a failed copy is logged, not
            // allowed to block the save itself.
            try {
                Files.copy(file.toPath(), backupFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            catch (IOException e) {
                Log.error(e);
            }
        }
        AtomicFiles.write(file.toPath(), bytes.toByteArray(), true);
    }
    catch (IOException e)
    { Log.error(e); }
}
```

Copy, not move, for the backup: `props` is small, and the net effect is
the same — `props~` holds the previous save, `props` the new one.
`COPY_ATTRIBUTES` carries the source's mode, as the old `deleteRename`
did; without it the copy would take the umask default instead. The
three-way `deleteRename` failure logging collapses into the one
`IOException` catch. The backup copy gets its own catch: the old code
logged a failed backup and *continued* to install the new props, and the
new code must keep that — a `props~` that can't be written (disk full,
unwritable, directory-shaped) is a secondary fault and must not block the
primary write the way a shared `try` would. `props~` stays in
`Directories.STATE_ENTRIES` for migration; nothing else reads it.

## Out of scope

- `Utilities.deleteRename` itself and its other callers — `Buffer`
  (Buffer.java:1610), `SystemBuffer` (SystemBuffer.java:492),
  `DirectoryBuffer` (DirectoryBuffer.java:2197) keep it; those write user
  files, not j's state.
- `prefs` (`Preferences`), the `~/.j` migration, `Cache`'s payload files
  (already temp-named), `TagFileManager.makeTagFile` (the tagfile itself
  is a temp file by construction), `VimRegisters` (in-memory).
- The `java.io.File` → NIO migration (separate TODO item); `toPath()` is
  a bridge, not a step in that direction.

## Edge cases

- **Key map to a remote destination.** `SaveFileDialog` can hand back a
  remote `File`. Today `getOutputStream` throws `NotSupportedException`
  into the EDT; after, `toPath()` throws the same. No behavior change,
  no new handling.
- **Key map into a missing directory.** `AtomicFiles` creates the parent;
  today that's a `FileNotFoundException`. More forgiving, harmless.
- **Kill -9 residue.** A stray `<name>.new` can survive a hard kill.
  `Cache.cleanup` and `TagFileManager.cleanup` sweep their directories;
  the one mildly exposed listing is `ListRegistersBuffer`, which lists
  every name in the registers dir, so a crashed write of register `a`
  could show a register `a.new` until the next sweep. Cosmetic, crash-only;
  not worth a filter.
- **Streaming vs whole-or-nothing.** The `BufferedWriter`-over-`newWriter`
  sites (recent, files.xml, the two catalogs) are safe on the realistic
  failure — disk-full — because the buffer's flush inside `close()`
  throws before the move runs, leaving the target intact. The guarantee is
  narrower than the byte[] path's: a non-IO exception from the body after
  bytes have reached the stream (a serializer throwing mid-loop) would
  still move a partial temp. That path is unreachable for these
  producers, which is why they keep the streaming shape; anything that
  *can* fail mid-body (`Properties.store`, a raw string) takes the byte[]
  path instead, where the body is whole before the write begins.
- **The `changed` flag.** `RecentFiles.save` clears `changed` only after
  the inner try-with-resources closes, i.e. after the move succeeds. A
  failed save leaves the list dirty so the next save retries it. The flag
  must stay outside the `try (writer = ...)` block: inside, it would clear
  before `close()` runs and a failed move would silently lose the dirty
  state.
- **Concurrency.** `Autosave`, `RecentFiles`, `FileHistory`,
  `TagFileCatalog`, and `Transients` already synchronize their savers;
  `Registers.setText` is unsynchronized but each call writes its own
  temp, so the last move wins and the file is never torn.
- **Windows.** `ATOMIC_MOVE` + `REPLACE_EXISTING` works on the same
  volume; `AtomicFiles` already falls back to a plain replace otherwise.
  The five-retry `System.gc()` loop is gone.

## Verification

1. `bb build`, then `bb lint` and `bb fmt-check`.
2. `bb test` — the existing suite; `TransientPopupTest` already
   round-trips popup defaults through `saveDefaults` into a temp file, so
   the new path is exercised there.
3. Throwaway smoke script against a temp `J_*` home: set an alias, save
   recent files, file history, a register, the global key map, a session,
   and props; assert each file round-trips and no `*.new` is left in any
   state directory.
4. Crash test: loop `Session.save()`/`SessionProperties.save()` while
   `kill -9`ing j; the previous file must still load intact next run, and
   `props~` must hold the save before it.

## Docs

- `TODO.md`: check off the item.
- `dev-docs/GIT-TOOLING.md:2821`: the parenthetical says `AtomicFiles`
  is "shared with the stash note and the port file" — add the state files
  so the shared-use list stays true.
