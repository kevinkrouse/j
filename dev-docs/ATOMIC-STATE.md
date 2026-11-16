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
      new `util/AtomicFilesTest` (7) pins the utility's invariants —
      whole write, replace, no `.new` residue, parent creation,
      owner-only perms, temp cleanup on a refused move, empty file.
- [ ] the other eight in-place writers + the two delete-then-rename callers.

## The utility

`org.armedbear.j.util.AtomicFiles.write(Path file, byte[] bytes, boolean owner)`:

- writes `bytes` to a temp file in `file`'s own directory (created if
  missing), then `Files.move` with `REPLACE_EXISTING` + `ATOMIC_MOVE`,
  falling back to a plain replace where atomic move is unsupported;
- the temp is removed if it was not moved;
- `owner` asks for `rw-------` on the temp, which the move carries to the
  target. On POSIX `createTempFile` is owner-only either way; the flag
  states intent and covers file systems without POSIX permissions.

It takes `byte[]`, not a stream, so every writer below builds its bytes
first. All of these files are small (kilobytes), so buffering the whole
body in memory is free.

## Shared conversion: `File.toPath()`

Every caller today holds an `org.armedbear.j.File` (j's own abstraction,
local/FTP/HTTP/SSH) and `AtomicFiles` wants a `java.nio.file.Path`. Add
one method to `File`, mirroring the guards on `getInputStream`/
`getOutputStream` (File.java:882):

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

`Path.of(canonicalPath())` is already the pattern at File.java:922. This
keeps the nine call sites honest: a remote state file fails loudly, the
same way `getOutputStream` fails today.

## The in-place writers

Each change is the same shape: build the bytes the writer already builds
(keep the exact same writer chain so bytes are byte-for-byte identical to
what is on disk today — same UTF-8, same `newLine()` separators), then
`AtomicFiles.write(path, bytes, true)` instead of opening the target.
`owner=true` for all of them: this is the user's private state — paths,
registers, session, properties. Note the one-time side effect: files that
existed with the umask default (e.g. 644) become 600 on the first atomic
write. That is the intent of the flag and a privacy improvement; call it
out in the commit message.

| File | Class / method | Today | Change |
|---|---|---|---|
| `<state>/autosave/catalog` | `Autosave.flush()` (Autosave.java:87) | `catalog.store(catalogFile.getOutputStream())` | `Properties.store` into a `ByteArrayOutputStream`, then `AtomicFiles.write(catalogFile.toPath(), bytes, true)` |
| `<state>/popup-defaults.properties` | `Transients.saveDefaults()` (Transients.java:72) | `Files.newOutputStream(file)` | store into BAOS, `AtomicFiles.write(file, bytes, true)`; drop the now-redundant `Files.createDirectories(file.getParent())` — `AtomicFiles` creates the parent |
| `<config>/aliases` | `Aliases.save()` (Aliases.java:101) | `userAliases.store(file.getOutputStream())` | BAOS + `AtomicFiles.write(file.toPath(), bytes, true)` |
| `<state>/recent` | `RecentFiles.save()` (RecentFiles.java:197) | `BufferedWriter(OutputStreamWriter(file.getOutputStream()))` | same writer over a BAOS, then `AtomicFiles.write` |
| `<state>/files.xml` | `FileHistory.save()` (FileHistory.java:136) | same writer chain | same: writer over BAOS, then `AtomicFiles.write` |
| `<data>/registers/<name>` | `Registers.setText()` (Registers.java:80) | `OutputStreamWriter(file.getOutputStream())` | `text.getBytes(UTF_8)` + `AtomicFiles.write(file.toPath(), bytes, true)`; the manual `dir.mkdirs()` can go (`AtomicFiles` creates parents), the `dir == null` and `file == null` checks stay |
| key map (user-chosen) | `KeyMap.writeKeyMap(File)` (KeyMap.java:667) | `PrintWriter(...file.getOutputStream())` | `PrintWriter` over BAOS, then `AtomicFiles.write(file.toPath(), bytes, true)` |
| `<cache>/cache/catalog` | `Cache.saveCatalog()` (Cache.java:145) | writer chain | writer over BAOS, then `AtomicFiles.write` |
| `<cache>/tagfiles/catalog` | `TagFileCatalog.save()` (TagFileCatalog.java:140) | writer chain, `# date` header | writer over BAOS, then `AtomicFiles.write`; keep the header line |

`Properties.store` needs an `OutputStream`; the text writers need a
`Writer` over the same BAOS (`OutputStreamWriter(baos, UTF_8)`), flushed
before `baos.toByteArray()`. No format changes anywhere: loaders,
version lines, and separators stay exactly as they are.

## Session.save(): delete-then-rename → one atomic write

`Session.save()` (Session.java:246) writes the XML to a temp file in the
*temp* directory, then `Utilities.deleteRename(tempFile, file)`: delete
the target, rename the temp over it, retry five times with `System.gc()`
on Windows, fall back to overwriting in place. The cross-directory move is
exactly what `ATOMIC_MOVE` can't promise and what the retry dance papers
over.

Replace the whole tail: build the XML into a `ByteArrayOutputStream`
(same `OutputStreamWriter` chain, same content), then
`AtomicFiles.write(file.toPath(), bytes, true)`. The temp file in the
temp directory disappears entirely; the aside file lands next to
`session.xml`, so the move is same-directory and atomic on any POSIX
volume.

## SessionProperties.save(): keep the backup, drop the dance

`SessionProperties.save()` (SessionProperties.java:253) does two
delete-renames: `props` → `props~` (backup), then temp → `props`. The
constructor (SessionProperties.java:38) falls back to `props~` when
`props` is missing, so the backup is load-bearing and stays. What goes is
the mechanism:

```java
public void save() {
    try {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, null);
        if (file.isFile())
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        AtomicFiles.write(file.toPath(), bytes.toByteArray(), true);
    }
    catch (IOException e)
    { Log.error(e); }
}
```

Copy, not move, for the backup: `props` is small, and the net effect is
the same — `props~` holds the previous save, `props` the new one. The
three-way `deleteRename` failure logging collapses into the one
`IOException` catch. `props~` stays in `Directories.STATE_ENTRIES` for
migration; nothing else reads it.

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
