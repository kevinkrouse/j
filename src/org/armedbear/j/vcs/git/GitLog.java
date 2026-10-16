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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final String FORMAT = "--format=%H%x1f%P%x1f%an%x1f%ae%x1f%at%x1f%D%x1f%ct%x1f%s";

    private GitLog() {}

    static List<LogCommit> read(VcsRepository repository, LogQuery query, int skip, int max) {
        final List<String> args = new ArrayList<>(
                List.of("log", "--topo-order", "-z", "--no-color", "--no-show-signature", "--decorate=full", FORMAT));
        // A path's history, but for one followed, names its parents in it,
        // for the graph; the commits' own come apart.
        final boolean rewrites = query.path() != null && !query.follow();
        if (rewrites)
            args.add("--parents");
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
            // Tags only with tags: a commit only one reaches is often
            // made for it, as cvs2git's are, apart from any branch.
            args.addAll(List.of("--branches", "--remotes"));
            if (query.tags())
                args.add("--tags");
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
        final List<LogCommit> commits = new ArrayList<>();
        for (LogCommit c : parse(r.stdout()))
            commits.add(c.in(repository));
        return rewrites ? withOwnParents(repository, commits) : commits;
    }

    // commits, read with their parents rewritten, with their own: the
    // rewritten ones the graph's.
    private static List<LogCommit> withOwnParents(VcsRepository repository, List<LogCommit> commits) {
        if (commits.isEmpty())
            return commits;
        final StringBuilder ids = new StringBuilder();
        for (LogCommit c : commits)
            ids.append(c.id()).append('\n');
        final VcsResult r = GitCli.run(
            GitCli.read(
                repository.root(),
                ids.toString().getBytes(StandardCharsets.UTF_8),
                "rev-list",
                "--no-walk=unsorted",
                "--parents",
                "--stdin"));
        if (!r.succeeded())
            return List.of();
        final Map<String, List<String>> parents = new HashMap<>();
        for (String line : r.out().split("\n")) {
            final List<String> w = words(line.strip(), " ");
            if (!w.isEmpty())
                parents.put(w.get(0), w.subList(1, w.size()));
        }
        final List<LogCommit> out = new ArrayList<>(commits.size());
        for (LogCommit c : commits)
            out.add(
                new LogCommit(c.id(),
                        parents.getOrDefault(c.id(), c.parents()),
                        c.author(),
                        c.email(),
                        c.time(),
                        c.refs(),
                        c.subject(),
                        c.parents(),
                        c.committed(),
                        c.repository()));
        return out;
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
            if (f.length < 8)
                continue;
            final List<String> parents = words(f[1], " ");
            commits.add(
                new LogCommit(f[0], parents, f[2], f[3], number(f[4]), refs(f[5]), f[7], parents, number(f[6]), null));
        }
        return commits;
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s);
        }
        catch (NumberFormatException e) {
            return 0;
        }
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

    static List<LogRef> allRefs(VcsRepository repository) {
        final VcsResult r = GitCli.run(
            GitCli.read(
                repository.root(),
                "for-each-ref",
                "--format=%(HEAD)%09%(refname)",
                "refs/heads",
                "refs/remotes",
                "refs/tags"));
        return r.succeeded() ? parseRefs(r.out()) : List.of();
    }

    // "*\trefs/heads/main", " \trefs/tags/v1"; a remote's HEAD, a pointer to one of its branches, left out.
    static List<LogRef> parseRefs(String out) {
        final List<LogRef> refs = new ArrayList<>();
        for (String line : out.split("\n")) {
            final int tab = line.indexOf('\t');
            if (tab < 0 || line.endsWith("/HEAD"))
                continue;
            final boolean head = line.startsWith("*");
            final String name = line.substring(tab + 1);
            for (LogRef r : refs(name))
                refs.add(new LogRef(r.name(), r.kind(), head));
        }
        return refs;
    }

    static List<String> paths(VcsRepository repository) {
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), "ls-files", "-z"));
        return r.succeeded() ? words(new String(r.stdout(), StandardCharsets.UTF_8), "\0") : List.of();
    }

    static byte[] fileAt(VcsRepository repository, String id, String path) {
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), "show", id + ":" + path));
        return r.succeeded() ? r.stdout() : null;
    }
}
