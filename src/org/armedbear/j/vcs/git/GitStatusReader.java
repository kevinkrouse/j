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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.FileChange.Kind;
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
    private static final int BATCH = 100;
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

        final List<Item> unmerged = new ArrayList<>();
        final List<Item> untracked = new ArrayList<>();
        final List<FileChange> unstaged = new ArrayList<>();
        final List<FileChange> staged = new ArrayList<>();
        for (FileChange c : git.changes()) {
            if (c.isIgnored())
                continue;
            if (c.isConflicted())
                unmerged.add(new FileItem(c, 'U', false, null));
            else if (c.isUntracked())
                untracked.add(new FileItem(c, '?', false, null));
            else {
                if (c.unstaged() != null)
                    unstaged.add(c);
                if (c.staged() != null)
                    staged.add(c);
            }
        }

        final List<Section> sections = new ArrayList<>();
        if (!unmerged.isEmpty())
            sections.add(new Section("unmerged", "Unmerged", unmerged));
        if (!untracked.isEmpty())
            sections.add(new Section("untracked", "Untracked files", untracked));
        if (!unstaged.isEmpty())
            sections.add(
                new Section("unstaged", "Unstaged changes", files(repository, unstaged, false, wantsDiff, ignored)));
        if (!staged.isEmpty())
            sections.add(new Section("staged", "Staged changes", files(repository, staged, true, wantsDiff, ignored)));
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
        return new StatusData(header, inProgress(git, unmerged.size()), sections, null);
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

    private static String inProgress(GitSnapshot git, int conflicts) {
        if (git.operation() == GitSnapshot.Operation.NONE)
            return null;
        final String what = switch (git.operation()) {
            case CHERRY_PICK -> "Cherry-pick";
            case AM -> "Applying patches";
            default -> {
                final String s = git.operation().name().toLowerCase(Locale.ROOT);
                yield Character.toUpperCase(s.charAt(0)) + s.substring(1);
            }
        };
        final String s = what + " in progress";
        return conflicts == 0 ? s : s + " (" + conflicts + (conflicts == 1 ? " conflict)" : " conflicts)");
    }

    // A section's files, each with its diff if it is wanted: one git diff for them all.
    private static List<Item> files(
            VcsRepository repository,
            List<FileChange> changes,
            boolean staged,
            BiPredicate<String, String> wantsDiff,
            Whitespace ignored) {
        final String section = staged ? "staged" : "unstaged";
        final List<String> paths = new ArrayList<>();
        for (FileChange c : changes) {
            if (wantsDiff.test(section, c.path())) {
                if (staged && c.origPath() != null)
                    paths.add(c.origPath());
                paths.add(c.path());
            }
        }
        final Map<String, FileDiff> diffs = new HashMap<>();
        // In batches, as a command line has a limit (32k characters on Windows).
        for (int i = 0; i < paths.size(); i += BATCH) {
            final List<String> batch = paths.subList(i, Math.min(paths.size(), i + BATCH));
            final List<FileDiff> read = diff(repository, staged, batch, ignored);
            if (read == null)
                continue;
            for (FileDiff d : read)
                diffs.put(d.path(), d);
            // What git left out, ignoring whitespace, changed only in that.
            if (ignored != Whitespace.NONE)
                for (String p : batch)
                    diffs.putIfAbsent(p, FileDiff.ONLY_WHITESPACE);
        }
        final List<Item> items = new ArrayList<>();
        for (FileChange c : changes) {
            final Kind kind = staged ? c.staged() : c.unstaged();
            items.add(new FileItem(c, kind == null ? ' ' : kind.letter(), staged, diffs.get(c.path())));
        }
        return items;
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
