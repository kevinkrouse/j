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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        final List<Item> recent = recent(repository, git);
        if (!recent.isEmpty())
            sections.add(new Section("recent", "Recent commits", recent));
        return new StatusData(header(repository, git), inProgress(git, unmerged.size()), sections, null);
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
            header.add(
                new Field("Upstream", up.name() + (up.gone() ? "  gone" : "  ↑" + up.ahead() + " ↓" + up.behind())));
        return header;
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

    private static List<Item> recent(VcsRepository repository, GitSnapshot git) {
        final List<Item> items = new ArrayList<>();
        if (git.head() == null)
            return items;
        final VcsResult r = GitCli
                .run(GitCli.read(repository.root(), "log", "-n", Integer.toString(RECENT), "--format=%h%x1f%s%x1f%D"));
        if (!r.succeeded())
            return items;
        for (String line : r.out().split("\n")) {
            final String[] f = line.split("\u001f", -1);
            if (f.length == 3)
                items.add(new CommitItem(f[0], f[1], f[2]));
        }
        return items;
    }
}
