/*
 * GitLog.java
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.diff.DiffParser;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.log.CommitChange;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.log.LogQuery;
import org.armedbear.j.vcs.log.LogRef;

/** The log's reads from git. */
final class GitLog {
    // Fields apart by US, commits by -z's NUL: a subject is one line.
    private static final String FORMAT = "--format=%H%x1f%P%x1f%an%x1f%ae%x1f%at%x1f%D%x1f%s";

    private GitLog() {}

    static List<LogCommit> read(VcsRepository repository, LogQuery query, int skip, int max) {
        final List<String> args = new ArrayList<>(
                List.of("log", "--topo-order", "-z", "--no-color", "--no-show-signature", "--decorate=full", FORMAT));
        args.add("--skip=" + skip);
        args.add("--max-count=" + max);
        if (query.author() != null)
            args.add("--author=" + query.author());
        if (query.since() != null)
            args.add("--since=" + query.since());
        if (query.grep() != null) {
            args.add("--grep=" + query.grep());
            args.add("--regexp-ignore-case");
            args.add("--fixed-strings");
        }
        if (query.pickaxe() != null)
            args.add("-S" + query.pickaxe());
        if (query.follow() && query.path() != null)
            args.add("--follow");
        if (query.revisions().isEmpty()) {
            args.addAll(List.of("--branches", "--remotes", "--tags"));
            if (hasHead(repository))
                args.add("HEAD");
        } else
            for (String rev : query.revisions()) {
                // Never an option.
                if (rev.startsWith("-"))
                    return List.of();
                args.add(rev);
            }
        args.add("--");
        if (query.path() != null)
            args.add(query.path());
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        // An empty repository, or a revision gone: nothing to show.
        if (!r.succeeded())
            return List.of();
        return parse(r.stdout());
    }

    // An unborn branch's HEAD names no commit, and git log fails on it.
    static boolean hasHead(VcsRepository repository) {
        return GitCli.run(GitCli.read(repository.root(), "rev-parse", "--verify", "--quiet", "HEAD")).succeeded();
    }

    static List<LogCommit> parse(byte[] out) {
        final List<LogCommit> commits = new ArrayList<>();
        for (String record : new String(out, StandardCharsets.UTF_8).split("\0")) {
            final String s = record.startsWith("\n") ? record.substring(1) : record;
            if (s.isEmpty())
                continue;
            final String[] f = s.split("\u001f", -1);
            if (f.length < 7)
                continue;
            long time;
            try {
                time = Long.parseLong(f[4]);
            }
            catch (NumberFormatException e) {
                time = 0;
            }
            commits.add(new LogCommit(f[0], words(f[1], " "), f[2], f[3], time, refs(f[5]), f[6]));
        }
        return commits;
    }

    // "HEAD -> refs/heads/main, tag: refs/tags/v1, refs/remotes/origin/main", or "HEAD" detached.
    static List<LogRef> refs(String decoration) {
        final List<LogRef> refs = new ArrayList<>();
        for (String d : words(decoration, ", ")) {
            String r = d;
            boolean head = false;
            if (r.startsWith("HEAD -> ")) {
                r = r.substring("HEAD -> ".length());
                head = true;
            }
            if (r.startsWith("tag: "))
                r = r.substring("tag: ".length());
            if (r.equals("HEAD"))
                refs.add(new LogRef("HEAD", LogRef.Kind.HEAD, true));
            else if (r.startsWith("refs/heads/"))
                refs.add(new LogRef(r.substring("refs/heads/".length()), LogRef.Kind.BRANCH, head));
            else if (r.startsWith("refs/remotes/"))
                refs.add(new LogRef(r.substring("refs/remotes/".length()), LogRef.Kind.REMOTE, false));
            else if (r.startsWith("refs/tags/"))
                refs.add(new LogRef(r.substring("refs/tags/".length()), LogRef.Kind.TAG, false));
            // Others, as refs/stash and notes, aren't badges.
        }
        return refs;
    }

    private static List<String> words(String s, String separator) {
        return s.isEmpty() ? List.of() : List.of(s.split(separator));
    }

    static List<CommitChange> changes(VcsRepository repository, String id, String parent) {
        final List<String> args = new ArrayList<>(List.of("-c", "diff.renames=true"));
        if (parent != null)
            args.addAll(List.of("diff", "--name-status", "-z", "-M", parent, id));
        else
            args.addAll(List.of("show", "--root", "--format=", "--name-status", "-z", "-M", id));
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        return r.succeeded() ? parseChanges(r.stdout()) : null;
    }

    // "M\0path\0", "R100\0old\0new\0".
    static List<CommitChange> parseChanges(byte[] out) {
        final String[] f = new String(out, StandardCharsets.UTF_8).split("\0");
        final List<CommitChange> changes = new ArrayList<>();
        int i = 0;
        while (i < f.length) {
            final String status = f[i].strip();
            if (status.isEmpty()) {
                i++;
                continue;
            }
            final char c = status.charAt(0);
            final boolean two = c == 'R' || c == 'C';
            if (i + (two ? 2 : 1) >= f.length)
                break;
            if (two)
                changes.add(new CommitChange(f[i + 2], f[i + 1], c == 'R' ? Kind.RENAMED : Kind.COPIED));
            else
                changes.add(
                    new CommitChange(f[i + 1], null, c == 'A' || c == 'D' || c == 'T' ? Kind.of(c) : Kind.MODIFIED));
            i += two ? 3 : 2;
        }
        return changes;
    }

    static FileDiff diff(VcsRepository repository, String id, String parent, CommitChange change) {
        final List<String> args = new ArrayList<>(List.of("--literal-pathspecs"));
        if (parent != null)
            args.addAll(List.of("diff", "--no-ext-diff", "--no-color", "-M", parent, id));
        else
            args.addAll(List.of("show", "--root", "--format=", "--no-ext-diff", "--no-color", "-M", id));
        args.add("--");
        if (change.origPath() != null)
            args.add(change.origPath());
        args.add(change.path());
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        if (!r.succeeded())
            return null;
        // None, as for a mode change: no change to its content.
        final List<FileDiff> diffs = DiffParser.parse(r.stdout());
        return diffs.isEmpty() ? FileDiff.EMPTY : diffs.get(0);
    }

    static FileDiff toWorkingTree(VcsRepository repository, String id, String path) {
        return first(
            GitCli.run(
                GitCli.read(
                    repository.root(),
                    "--literal-pathspecs",
                    "diff",
                    "--no-ext-diff",
                    "--no-color",
                    id,
                    "--",
                    path)));
    }

    private static FileDiff first(VcsResult r) {
        if (!r.succeeded())
            return null;
        final List<FileDiff> diffs = DiffParser.parse(r.stdout());
        return diffs.isEmpty() ? null : diffs.get(0);
    }

    static String message(VcsRepository repository, String id) {
        final VcsResult r = GitCli.run(
            GitCli.read(repository.root(), "show", "-s", "--no-color", "--no-show-signature", "--format=%B", id));
        return r.succeeded() ? r.out().stripTrailing() : null;
    }

    static byte[] fileAt(VcsRepository repository, String id, String path) {
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), "show", id + ":" + path));
        return r.succeeded() ? r.stdout() : null;
    }
}
