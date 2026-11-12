# Shared VCS selections

`vcs.selection` owns captures, typed file keys, hunk identity and
resolution. It does no I/O. Its own imports are the repository, diffs and
status data (`SelectionDependenciesTest`); with `VcsBackend`, they reach no
editor, Swing or AWT type, the older hooks being `VersionControlHooks` since
phase 12. Its use
of `StatusData` is deliberate:
that record and its file items are the existing backend result, without UI
state. Moving that data to another package would rename the dependency
without changing the boundary. `RepositoryStatus` owns reads and scheduling;
status rows, trees, comparisons, and signs adapt their selections at the UI
boundary.

## Capture and resolution

`Target.Files` captures whole paths. `Target.Part` captures a hunk and the
indexes of its lines selected, a `BitSet` it copies in and out; null means
the whole hunk, while an empty set means no lines. `Selections.Builder` merges overlapping selections, pairs replacements,
and lets a whole file subsume its parts. Context rows contribute no change.

`FileKey` keeps repository, opaque section, and literal path separate. Read
requests, pending reads, picked-file requests, and viewed-file requests use
these keys. Renderer strings remain only for caret placement and folding;
they are not parsed into selection data.

Resolution uses the latest supplied status, with no reads of its own. A whole
file keeps its current changes, provided the change kind, staged side, and
rename source still match. A hunk is identified by a SHA-256 of its changed
lines, their kinds and newline marks, and its occurrence among identical
hunks. A candidate must also retain the changed
lines' base rows, working rows, or surrounding context. More than one match
is ambiguous. Context can expand and working rows can shift; selected bits
are translated through offsets among changed lines. A split, merged, moved,
or changed hunk can require reselection. Changelist anchors retain their
separate approximate matching policy.

`Resolution` separates unread files from problems, each with a message
saying why: gone, changed, moved, unsupported or a failed read. Commands require every target to resolve.
Picks retain the valid subset and report dropped captures. `RepositoryStatus`
uses its bounded pending-read mechanism once and turns missing results into
failures. Moves in editor-managed lists also require whole-file diffs for
anchors. A file provisionally shown in a list before its split membership is
read requires reselection rather than moving newly discovered members.

Whole-file picks include edits made after picking. Refreshing alone does not
invalidate a capture. A resolved status is not a repository lock: execution
still checks fresh hunks and patch applicability, and writes retain their
repository and index locks.

## UI precedence and commit review

Typed commands take the text selection, then picks, then the caret. A context
menu takes the row or node clicked, or the selection containing it, before
picks; its Pick takes only the lines that can be picked.
Its actions retain that capture while the caret moves or a diff is read;
there is no global menu flag. Pick consumption keys are captured with an
action that consumes picks. A drag similarly captures its payload at gesture
start and checks its destination again at execution.

Commit review compares the versions that staging the selection produces,
per path; any change to what is selected changes them. Git previews through a private index
and checks the actual private index against the confirmed versions before
committing. A different review requires another confirmation. The message
draft survives a refusal. This does not freeze the working tree or replace
execution-time checks.

## Backend boundary and persistence

`STAGING` enables stage and unstage. The current selected-commit implementation
requires both `PARTIAL_COMMIT` and `STAGING`. `HUNK_CHANGELISTS` enables the
editor's saved anchors, the only changelists moves use. Native changelists,
as svn's, have no hooks yet: phase 12 designs them with its backend.

This is not yet a complete SVN implementation. Whole-path commits without an
index, per-repository commit capabilities, section roles, and native
changelists remain phase 12. Shared status code still contains
Git section-name assumptions, as recorded in `GIT-TOOLING.md`.

No stored data needs migrating. Picks are in memory. Changelist XML remains
version 1, with the original anchor hashes and coordinates; SHA-256 selection
ids are not persisted there. The compatibility test loads a phase 10 XML
fixture, places its assignments, and edits its draft through the current store.
