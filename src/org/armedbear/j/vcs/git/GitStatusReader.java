/*
 * GitStatusReader.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j.vcs.git;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.InProgress;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.Snapshot.Relation;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.diff.DiffParser;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.status.StatusData;
import org.armedbear.j.vcs.status.StatusData.CommitItem;
import org.armedbear.j.vcs.status.StatusData.Field;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Item;
import org.armedbear.j.vcs.status.StatusData.Section;
import org.armedbear.j.vcs.status.StatusData.StashItem;

/** What git's status buffer shows: the snapshot, the diffs asked for, and the recent commits. */
final class GitStatusReader {
    static final int RECENT = 10;
    static final int BATCH = 100;
    // Commits' "abbreviated-hash subject", by hash, which a commit never
    // changes: HEAD's is asked for on every refresh.
    private static final Map<String, String> SUBJECTS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 100;
                }
            });

    private GitStatusReader() {}

    static StatusData read(VcsRepository repository, BiPredicate<String, String> wantsDiff, Whitespace ignored) {
        final Snapshot snapshot = repository.snapshotNow();
        if (snapshot.error() != null)
            return StatusData.failed(snapshot.error());
        if (!(snapshot instanceof GitSnapshot git))
            return StatusData.failed("not a git snapshot");

        final List<FileChange> unmerged = new ArrayList<>();
        final List<Item> untracked = new ArrayList<>();
        final List<FileChange> unstaged = new ArrayList<>();
        final List<FileChange> staged = new ArrayList<>();
        for (FileChange c : git.changes()) {
            if (c.isIgnored())
                continue;
            if (c.isConflicted())
                unmerged.add(c);
            else if (c.isUntracked())
                untracked.add(new FileItem(c, '?', false, null));
            else {
                if (c.unstaged() != null)
                    unstaged.add(c);
                if (c.staged() != null)
                    staged.add(c);
            }
        }

        // The working tree's diffs, an unmerged file's combined, in one
        // git diff; the staged ones' in another.
        final List<String> conflicted = wanted(StatusData.UNMERGED, unmerged, false, wantsDiff);
        final List<String> worktree = new ArrayList<>(conflicted);
        worktree.addAll(wanted("unstaged", unstaged, false, wantsDiff));
        final Map<String, FileDiff> diffs = readDiffs(repository, false, worktree, Set.copyOf(conflicted), ignored);
        final Map<String, FileDiff> stagedDiffs =
                readDiffs(repository, true, wanted("staged", staged, true, wantsDiff), Set.of(), ignored);
        final List<Section> sections = new ArrayList<>();
        if (!unmerged.isEmpty())
            sections.add(new Section(StatusData.UNMERGED, "Unmerged", items(unmerged, false, diffs)));
        if (!untracked.isEmpty())
            sections.add(new Section("untracked", "Untracked files", untracked));
        if (!unstaged.isEmpty())
            sections.add(new Section("unstaged", "Unstaged changes", items(unstaged, false, diffs)));
        if (!staged.isEmpty())
            sections.add(new Section("staged", "Staged changes", items(staged, true, stagedDiffs)));
        // The count says whether to list them, where this git gives one.
        final List<Item> stashes = git.stashes() == 0 ? List.of() : stashes(repository, wantsDiff, ignored);
        if (stashes != null && !stashes.isEmpty())
            sections.add(new Section(StatusData.STASHES, "Stashes", stashes));
        sections.addAll(ranges(repository, git));
        // Recent ones when none are on this branch alone, as just after
        // branching, or on the parent itself.
        final Relation parent = git.parent();
        if (parent == null || parent.ahead() <= 0) {
            final List<Item> recent = git.head() == null ? List.of() : commits(repository, null);
            if (!recent.isEmpty())
                sections.add(new Section("recent", "Recent commits", recent));
        }
        final List<Field> header = header(repository, git);
        if (stashes == null)
            header.add(new Field("Stashes", "can't be read: see vcsCommandLog"));
        return new StatusData(header, inProgress(repository, git, unmerged.size()), sections, null);
    }

    private static List<Field> header(VcsRepository repository, GitSnapshot git) {
        final List<Field> header = new ArrayList<>();
        String head = git.isDetached() ? "(detached)" : git.branch();
        if (git.head() == null)
            head += "  (no commits yet)";
        else {
            final String subject = SUBJECTS.computeIfAbsent(git.head(), h -> {
                final VcsResult r = GitCli.run(GitCli.read(repository.root(), "log", "-1", "--format=%h %s", h));
                return r.succeeded() ? r.out().strip() : null; // A failure is asked again.
            });
            if (subject != null && !subject.isEmpty())
                head += "  " + subject;
        }
        header.add(new Field("Head", head));
        final Relation up = git.upstream();
        if (up != null)
            header.add(new Field("Upstream", up.name() + (up.gone() ? "  gone" : counts(up))));
        // Where it pushes, when not to its upstream.
        final Relation push = git.push();
        if (push != null && (up == null || !push.name().equals(up.name())))
            header.add(new Field("Push", push.name() + (push.gone() ? "  gone" : counts(push))));
        // Always, with where it was found, though another line names it.
        final Relation parent = git.parent();
        if (parent != null)
            header.add(new Field("Parent", parent.name() + "  (" + parent.source() + ")"));
        return header;
    }

    private static String counts(Relation r) {
        return "  ↑" + r.ahead() + " ↓" + r.behind();
    }

    // git keeps a staged conflict's stages, "resolve undo", until the
    // index is next read from a tree, as a rebase's next step does; a
    // commit keeps them, so they may be an earlier merge's. Only those
    // whose ours, stage 2, is HEAD's are this operation's. Records are
    // "mode id stage\tpath".
    static Set<String> resolvedConflicts(VcsRepository repository, List<String> paths) {
        final Set<String> out = new HashSet<>();
        for (int i = 0; i < paths.size(); i += BATCH) {
            final List<String> batch = paths.subList(i, Math.min(paths.size(), i + BATCH));
            final Map<String, String> ours = new HashMap<>();
            for (String e : records(repository, "ls-files", "--resolve-undo", batch)) {
                final String[] f = e.split("[ \t]", 4);
                if (f.length < 4)
                    continue;
                ours.putIfAbsent(f[3], null);
                if (f[2].equals("2"))
                    ours.put(f[3], f[1]);
            }
            if (ours.isEmpty())
                continue;
            // "mode type id\tpath"
            final Map<String, String> head = new HashMap<>();
            for (String e : records(repository, "ls-tree", "HEAD", List.copyOf(ours.keySet()))) {
                final String[] f = e.split("[ \t]", 4);
                if (f.length == 4)
                    head.put(f[3], f[2]);
            }
            for (Map.Entry<String, String> e : ours.entrySet())
                if (Objects.equals(e.getValue(), head.get(e.getKey())))
                    out.add(e.getKey());
        }
        return out;
    }

    // The NUL-separated records git's command gives for paths; none if it fails.
    private static List<String> records(VcsRepository repository, String command, String option, List<String> paths) {
        final List<String> args = new ArrayList<>(List.of("--literal-pathspecs", command, "-z", option, "--"));
        args.addAll(paths);
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        final List<String> out = new ArrayList<>();
        if (r.succeeded())
            for (String e : r.out().split("\0"))
                if (!e.isEmpty())
                    out.add(e);
        return out;
    }

    // "Rebase in progress (2 conflicts)", in InProgress's words, or a
    // bisect's; a conflicted stash apply or pop, which leaves no state in
    // git, as j's note says.
    private static String inProgress(VcsRepository repository, GitSnapshot git, int conflicts) {
        final VcsRepository.StashConflict stash = repository.stashConflict();
        // As VcsRepository.inProgress has it, which the line's links act on:
        // a stash's before a bisect.
        final String what = git.inProgress() != null ? git.inProgress().label
                : stash != null ? (stash.pop() ? InProgress.STASH_POP : InProgress.STASH_APPLY).label
                : git.operation() == GitSnapshot.Operation.BISECT ? "bisect"
                : null;
        if (what == null)
            return null;
        final String s = Character.toUpperCase(what.charAt(0)) + what.substring(1) + " in progress";
        return conflicts == 0 ? s : s + " (" + conflicts + (conflicts == 1 ? " conflict)" : " conflicts)");
    }

    // The paths of changes whose diffs section wants, a staged rename's old one too.
    private static List<String> wanted(
            String section,
            List<FileChange> changes,
            boolean staged,
            BiPredicate<String, String> wantsDiff) {
        final List<String> paths = new ArrayList<>();
        for (FileChange c : changes) {
            if (wantsDiff.test(section, c.path())) {
                if (staged && c.origPath() != null)
                    paths.add(c.origPath());
                paths.add(c.path());
            }
        }
        return paths;
    }

    // The diffs of paths, by path, in batches, as a command line has a
    // limit (32k characters on Windows); a batch git fails on, none. A
    // path git leaves out: an unmerged one, of which git gives no combined
    // diff, as for a side that deleted it, an empty combined one; else,
    // ignoring whitespace, changed only in that.
    private static Map<String, FileDiff> readDiffs(
            VcsRepository repository,
            boolean staged,
            List<String> paths,
            Set<String> unmerged,
            Whitespace ignored) {
        final Map<String, FileDiff> diffs = new HashMap<>();
        for (int i = 0; i < paths.size(); i += BATCH) {
            final List<String> batch = paths.subList(i, Math.min(paths.size(), i + BATCH));
            final List<FileDiff> read = diff(repository, staged, batch, ignored);
            if (read == null)
                continue;
            for (FileDiff d : read)
                diffs.put(d.path(), d);
            for (String p : batch)
                if (unmerged.contains(p))
                    diffs.putIfAbsent(p, FileDiff.NO_COMBINED);
                else if (ignored != Whitespace.NONE)
                    diffs.putIfAbsent(p, FileDiff.ONLY_WHITESPACE);
        }
        return diffs;
    }

    // A section's files, each with its diff, if read: an unmerged one's 'U'.
    private static List<Item> items(List<FileChange> changes, boolean staged, Map<String, FileDiff> diffs) {
        final List<Item> items = new ArrayList<>();
        for (FileChange c : changes) {
            final Kind kind = staged ? c.staged() : c.unstaged();
            final char letter = c.isConflicted() ? 'U' : kind == null ? ' ' : kind.letter();
            items.add(new FileItem(c, letter, staged, diffs.get(c.path())));
        }
        return items;
    }

    /** The same in batches, as files reads them; null if git fails on any. */
    static List<FileDiff> diffs(VcsRepository repository, boolean staged, List<String> paths, Whitespace ignored) {
        if (paths.size() <= BATCH)
            return diff(repository, staged, paths, ignored);
        final List<FileDiff> out = new ArrayList<>();
        for (int i = 0; i < paths.size(); i += BATCH) {
            final List<FileDiff> read =
                    diff(repository, staged, paths.subList(i, Math.min(paths.size(), i + BATCH)), ignored);
            if (read == null)
                return null;
            out.addAll(read);
        }
        return out;
    }

    /**
     * The working tree's diff against the index, or with staged the index's
     * against HEAD, of paths; null if git fails.
     */
    static List<FileDiff> diff(VcsRepository repository, boolean staged, List<String> paths, Whitespace ignored) {
        final List<String> args = new ArrayList<>(List.of("--literal-pathspecs", "diff"));
        if (staged)
            args.add("--cached");
        GitCli.ignoring(ignored, args).addAll(List.of("--no-ext-diff", "--no-color", "-M", "--"));
        args.addAll(paths);
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        return r.succeeded() ? DiffParser.parse(r.stdout()) : null;
    }

    // stash show --include-untracked came in 2.32.
    private static final int[] SHOW_UNTRACKED = { 2, 32 };

    // The stashes, newest first, each with its files if wanted; null if git fails.
    private static List<Item> stashes(
            VcsRepository repository,
            BiPredicate<String, String> wantsDiff,
            Whitespace ignored) {
        final List<Item> items = new ArrayList<>();
        final List<String[]> list = GitLog.stashList(repository);
        if (list == null)
            return null;
        for (String[] f : list) {
            final List<FileItem> files =
                    wantsDiff.test(StatusData.STASHES, f[1]) ? stashFiles(repository, f[1], ignored) : null;
            items.add(new StashItem(f[0], f[1], f[3], files));
        }
        return items;
    }

    // What the stash hash holds against the commit it was made on, its
    // untracked files too where git shows them; none if git fails.
    private static List<FileItem> stashFiles(VcsRepository repository, String hash, Whitespace ignored) {
        final List<FileItem> files = new ArrayList<>();
        final VcsResult r = stashShow(repository, hash, ignored, "-p");
        if (!r.succeeded())
            return files;
        final Map<String, FileDiff> diffs = new LinkedHashMap<>();
        for (FileDiff d : DiffParser.parse(r.stdout()))
            diffs.put(d.path(), d);
        if (ignored == Whitespace.NONE) {
            for (FileDiff d : diffs.values()) {
                final Kind kind = d.isNew() ? Kind.ADDED
                        : d.isDeleted() ? Kind.DELETED
                        : d.isRename() ? Kind.RENAMED
                        : Kind.MODIFIED;
                files.add(stashFile(d.path(), d.isRename() ? FileDiff.decode(d.oldPath()) : null, kind, d));
            }
            return files;
        }
        // Ignoring whitespace, git leaves out a file changed only in that:
        // the files named as they are, each with its diff or none.
        final VcsResult names = stashShow(repository, hash, Whitespace.NONE, "--name-status", "-z");
        if (!names.succeeded())
            return files;
        final String[] f = names.out().split("\0");
        for (int i = 0; i + 1 < f.length;) {
            final Kind kind = Kind.of(f[i].charAt(0));
            final boolean two = kind == Kind.RENAMED || kind == Kind.COPIED;
            if (two && i + 2 >= f.length)
                break;
            final String orig = two ? f[i + 1] : null;
            final String path = f[two ? i + 2 : i + 1];
            i += two ? 3 : 2;
            files.add(
                stashFile(
                    path,
                    orig,
                    kind == null ? Kind.MODIFIED : kind,
                    diffs.getOrDefault(path, FileDiff.ONLY_WHITESPACE)));
        }
        return files;
    }

    // git stash show of hash, with args.
    private static VcsResult stashShow(VcsRepository repository, String hash, Whitespace ignored, String... args) {
        final List<String> all = new ArrayList<>(List.of("stash", "show"));
        all.addAll(List.of(args));
        if (GitCli.versionAtLeast(SHOW_UNTRACKED))
            all.add("--include-untracked");
        GitCli.ignoring(ignored, all).addAll(List.of("--no-ext-diff", "--no-color", "-M", hash));
        return GitCli.run(GitCli.read(repository.root(), all.toArray(new String[0])));
    }

    // A stash's file, staged as a commit's are: a rename shown from its old path.
    private static FileItem stashFile(String path, String orig, Kind kind, FileDiff d) {
        return new FileItem(new FileChange(path, orig, kind, null, kind.letter() + " "), kind.letter(), true, d);
    }

    // The commits on one side of a range and not the other: its section.
    private record Range(String id, String title, String range, int total) {}

    // The commits between the branch and its upstream, push target and
    // parent each way, as of the last fetch: a section of each that has
    // any, and no section before it of the same range.
    private static List<Section> ranges(VcsRepository repository, GitSnapshot git) {
        final List<Range> wanted = new ArrayList<>();
        final Relation up = git.upstream();
        if (up != null) {
            wanted.add(new Range("unpulled", "Unpulled from " + up.name(), "HEAD.." + up.revision(), up.behind()));
            wanted.add(new Range("unpushed", "Unpushed to " + up.name(), up.revision() + "..HEAD", up.ahead()));
        }
        final Relation push = git.push();
        if (push != null)
            wanted.add(
                new Range("unpushed-push", "Unpushed to " + push.name(), push.revision() + "..HEAD", push.ahead()));
        final Relation parent = git.parent();
        if (parent != null) {
            wanted.add(
                new Range("unmerged-parent",
                        "Unmerged from " + parent.name(),
                        "HEAD.." + parent.revision(),
                        parent.behind()));
            wanted.add(
                new Range("not-in-parent", "Not in " + parent.name(), parent.revision() + "..HEAD", parent.ahead()));
        }
        final List<Section> sections = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (Range w : wanted) {
            // None, or gone.
            if (w.total() <= 0 || !seen.add(w.range()))
                continue;
            final List<Item> items = commits(repository, w.range());
            if (!items.isEmpty())
                sections.add(new Section(w.id(), w.title(), items, Math.max(w.total(), items.size()), w.range()));
        }
        return sections;
    }

    // The newest commits of range, or of HEAD when it is null.
    private static List<Item> commits(VcsRepository repository, String range) {
        final List<String> args = new ArrayList<>(
                List.of("log", "-n", Integer.toString(RECENT), "--decorate=full", "--format=%h%x1f%s%x1f%D"));
        if (range != null)
            args.add(range);
        args.add("--");
        final List<Item> items = new ArrayList<>();
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        if (!r.succeeded())
            return items;
        for (String line : r.out().split("\n")) {
            final String[] f = line.split("\u001f", -1);
            if (f.length == 3)
                items.add(new CommitItem(f[0], f[1], GitLog.refs(f[2])));
        }
        return items;
    }
}
