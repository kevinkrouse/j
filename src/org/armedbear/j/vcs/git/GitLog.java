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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.diff.DiffParser;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.log.CommitChange;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.log.LogQuery;
import org.armedbear.j.vcs.log.LogRef;
import org.armedbear.j.vcs.log.LogRefTip;

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
        final List<String> selection = selection(repository, query);
        if (selection == null)
            return List.of();
        args.addAll(selection);
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        // Failed, as for a revision gone or a lock: not the end of the log.
        if (!r.succeeded())
            throw new IllegalStateException("git log failed in " + repository.root());
        final List<LogCommit> commits = new ArrayList<>();
        for (LogCommit c : parse(r.stdout()))
            commits.add(c.in(repository));
        return rewrites ? withOwnParents(repository, commits) : commits;
    }

    /** The stashes, newest first, each its name, id, commit time and subject; null if git fails. */
    static List<String[]> stashList(VcsRepository repository) {
        final VcsResult r =
                GitCli.run(GitCli.read(repository.root(), "stash", "list", "--format=%gd%x1f%H%x1f%ct%x1f%gs"));
        if (!r.succeeded())
            return null;
        final List<String[]> out = new ArrayList<>();
        for (String line : r.out().split("\n")) {
            final String[] f = line.split("\u001f", -1);
            if (f.length == 4)
                out.add(f);
        }
        return out;
    }

    /** The branches the reflog says were checked out, the latest first, each once; none if git fails. */
    static List<String> recentBranches(VcsRepository repository) {
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), "reflog", "show", "--format=%gs", "-n", "1000"));
        final Set<String> names = new LinkedHashSet<>();
        if (r.succeeded())
            for (String line : r.out().split("\n"))
                if (line.startsWith(MOVING) && line.indexOf(" to ", MOVING.length()) >= 0)
                    names.add(line.substring(line.indexOf(" to ", MOVING.length()) + 4));
        return List.copyOf(names);
    }

    private static final String MOVING = "checkout: moving from ";

    /**
     * The commits on branch and no other branch, tag or remote's branch,
     * newest first, as "abc1234 subject"; null if git fails. Not HEAD nor a
     * stash: what keeps them only while it is there.
     */
    static List<String> onlyOn(VcsRepository repository, String branch) {
        final VcsResult r = GitCli.run(
            GitCli.read(
                repository.root(),
                "log",
                "--no-color",
                "--format=%h %s",
                "refs/heads/" + branch,
                "--not",
                "--exclude=" + branch,
                "--branches",
                "--tags",
                "--remotes",
                "--"));
        return r.succeeded() ? r.out().lines().toList() : null;
    }

    /**
     * The stashes, newest first, each a commit badged with its name whose
     * graph's parent is the commit it was made on: the index's and the
     * untracked files' commits, its other parents, aren't listed; null if
     * git fails.
     */
    static List<LogCommit> stashes(VcsRepository repository) {
        final List<String[]> list = stashList(repository);
        if (list == null)
            return null;
        if (list.isEmpty())
            return List.of();
        final List<String> args = new ArrayList<>(List.of("log", "--no-walk=unsorted", "-z", "--no-color", FORMAT));
        for (String[] f : list)
            args.add(f[1]);
        args.add("--");
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        if (!r.succeeded())
            return null;
        final Map<String, LogCommit> read = new HashMap<>();
        for (LogCommit c : parse(r.stdout()))
            read.put(c.id(), c);
        final List<LogCommit> out = new ArrayList<>();
        for (String[] f : list) {
            final LogCommit c = read.get(f[1]);
            if (c == null || c.parents().isEmpty())
                continue;
            out.add(
                new LogCommit(c.id(),
                        c.parents(),
                        c.author(),
                        c.email(),
                        c.time(),
                        List.of(new LogRef(f[0], LogRef.Kind.STASH, false)),
                        c.subject(),
                        List.of(c.parents().get(0)),
                        c.committed(),
                        repository));
        }
        return out;
    }

    /** What query starts from, as commit ids, each once; null if a revision can't be told. */
    static List<String> starts(VcsRepository repository, LogQuery query) {
        final List<String> args = new ArrayList<>(List.of("rev-parse"));
        if (query.revisions().isEmpty()) {
            args.addAll(List.of("--branches", "--remotes"));
            if (query.tags())
                args.add("--tags");
            if (hasHead(repository))
                args.add("HEAD");
        } else
            for (String rev : query.revisions()) {
                if (rev.startsWith("-"))
                    return null;
                args.add(rev);
            }
        args.add("--");
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        // A revision this repository hasn't: none to start from, as a branch
        // filter leaves out the repositories without the branch.
        if (!r.succeeded())
            return query.revisions().isEmpty() ? null : List.of();
        final Set<String> ids = new LinkedHashSet<>();
        for (String line : r.out().split("\n"))
            if (!line.isBlank() && !line.equals("--"))
                ids.add(line.strip());
        return List.copyOf(ids);
    }

    /**
     * The authors of the newest max commits of every branch, newest first,
     * each once: in date order, which needs no walk of the whole history as
     * the log's topological order does.
     */
    static List<String> authors(VcsRepository repository, int max) {
        final List<String> args =
                new ArrayList<>(List.of("log", "--max-count=" + max, "--format=%an", "--branches", "--remotes"));
        if (hasHead(repository))
            args.add("HEAD");
        args.add("--");
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        final Set<String> out = new LinkedHashSet<>();
        if (r.succeeded())
            for (String line : r.out().split("\n"))
                if (!line.isBlank())
                    out.add(line.strip());
        return List.copyOf(out);
    }

    /** Whether the log query reads in repository lists the commit id. */
    static boolean has(VcsRepository repository, LogQuery query, String id) {
        // Only where it starts: whether id is reachable from there, which
        // stops at it, not a walk of all the history.
        if (query.author() == null
                && query.since() == null
                && query.grep() == null
                && query.pickaxe() == null
                && query.path() == null) {
            final List<String> starts = selection(repository, query);
            if (starts == null)
                return false;
            final List<String> args = new ArrayList<>(List.of("rev-list", "--max-count=1", id, "--not"));
            args.addAll(starts.subList(0, starts.size() - 1));
            final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
            // Nothing of id's history that they don't reach: they reach id.
            return r.succeeded() && r.out().isBlank();
        }
        final List<String> selection = selection(repository, query);
        if (selection == null)
            return false;
        final List<String> args = new ArrayList<>(List.of("log", "--format=%H"));
        args.addAll(selection);
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        if (!r.succeeded())
            return false;
        for (String line : r.out().split("\n"))
            if (line.equals(id))
                return true;
        return false;
    }

    // What query selects: its filters, its revisions or every branch, and
    // its path; null for a revision that would be taken as an option.
    private static List<String> selection(VcsRepository repository, LogQuery query) {
        final List<String> args = new ArrayList<>();
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
                    return null;
                args.add(rev);
            }
        args.add("--");
        if (query.path() != null)
            args.add(query.path());
        return args;
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
            throw new IllegalStateException("git rev-list failed in " + repository.root());
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

    static FileDiff diff(VcsRepository repository, String id, String parent, CommitChange change, Whitespace ignored) {
        final List<String> args = new ArrayList<>(List.of("--literal-pathspecs"));
        if (parent != null)
            args.add("diff");
        else
            args.addAll(List.of("show", "--root", "--format="));
        GitCli.ignoring(ignored, args).addAll(List.of("--no-ext-diff", "--no-color", "-M"));
        if (parent != null)
            args.add(parent);
        args.add(id);
        args.add("--");
        if (change.origPath() != null)
            args.add(change.origPath());
        args.add(change.path());
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        if (!r.succeeded())
            return null;
        // None, as for a mode change: no change to its content, or none but whitespace ignored.
        final List<FileDiff> diffs = DiffParser.parse(r.stdout());
        if (!diffs.isEmpty())
            return diffs.get(0);
        return ignored != Whitespace.NONE ? FileDiff.ONLY_WHITESPACE : FileDiff.EMPTY;
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

    // HEAD's mark, the ref, its commit, a tag's commit, its date, its
    // upstream's track, and what a remote's HEAD points at.
    private static final String TIPS = "%(HEAD)%09%(refname)%09%(objectname)%09%(*objectname)%09"
            + "%(creatordate:unix)%09%(upstream:track)%09%(symref)";

    static List<LogRefTip> refTips(VcsRepository repository) {
        final VcsResult r = GitCli.run(
            GitCli.read(
                repository.root(),
                "for-each-ref",
                "--sort=-creatordate",
                "--format=" + TIPS,
                "refs/heads",
                "refs/remotes",
                "refs/tags"));
        if (!r.succeeded())
            return List.of();
        final List<LogRefTip> tips = new ArrayList<>(parseTips(r.out()));
        final List<String[]> stashes = stashList(repository);
        if (stashes != null)
            for (String[] f : stashes)
                tips.add(new LogRefTip(new LogRef(f[0], LogRef.Kind.STASH, false), f[1], number(f[2]), 0, 0, false));
        // No branch checked out: HEAD detached, at a commit of its own.
        if (tips.stream().noneMatch(t -> t.ref().head())) {
            final VcsResult head =
                    GitCli.run(GitCli.read(repository.root(), "rev-parse", "--verify", "--quiet", "HEAD"));
            if (head.succeeded() && !head.out().isBlank()) {
                final List<LogRefTip> out = new ArrayList<>();
                out.add(new LogRefTip(new LogRef("HEAD", LogRef.Kind.HEAD, true), head.out().strip(), 0, 0, 0, false));
                out.addAll(tips);
                return out;
            }
        }
        return tips;
    }

    // Lines of TIPS. A remote's HEAD, a pointer to one of its branches, is
    // left out, but names the default branch: origin's, else the first's.
    static List<LogRefTip> parseTips(String out) {
        final List<LogRefTip> tips = new ArrayList<>();
        String primary = null;
        boolean fromOrigin = false;
        for (String line : out.split("\n")) {
            final String[] f = line.split("\t", -1);
            if (f.length < 6)
                continue;
            if (f[1].startsWith("refs/remotes/") && f[1].endsWith("/HEAD")) {
                final String remote = f[1].substring("refs/remotes/".length(), f[1].length() - "/HEAD".length());
                final String to = f.length > 6 ? f[6] : "";
                final String prefix = "refs/remotes/" + remote + "/";
                if (to.startsWith(prefix) && (primary == null || !fromOrigin && remote.equals("origin"))) {
                    primary = to.substring(prefix.length());
                    fromOrigin = remote.equals("origin");
                }
                continue;
            }
            final List<LogRef> named = refs(f[1]);
            if (named.isEmpty())
                continue;
            final LogRef name = named.get(0);
            final LogRef ref = new LogRef(name.name(), name.kind(), f[0].equals("*"));
            // An annotated tag's commit is the one it points at.
            final String id = !f[3].isEmpty() ? f[3] : f[2];
            final String track = f[5];
            tips.add(
                new LogRefTip(ref,
                        id,
                        number(f[4]),
                        count(track, "ahead "),
                        count(track, "behind "),
                        track.equals("[gone]")));
        }
        return withPrimary(tips, primary);
    }

    // The default branch marked: the one named, if there is a local one,
    // else a local main, else master.
    private static List<LogRefTip> withPrimary(List<LogRefTip> tips, String named) {
        String primary = null;
        for (String name : Arrays.asList(named, "main", "master"))
            if (primary == null && name != null && tips.stream().anyMatch(t -> isLocal(t, name)))
                primary = name;
        final List<LogRefTip> out = new ArrayList<>(tips.size());
        for (LogRefTip t : tips)
            out.add(primary != null && isLocal(t, primary) ? t.asPrimary() : t);
        return out;
    }

    private static boolean isLocal(LogRefTip t, String name) {
        return t.ref().kind() == LogRef.Kind.BRANCH && t.ref().name().equals(name);
    }

    // n in "[ahead 2, behind 1]" after word; 0 if it isn't there.
    private static int count(String track, String word) {
        final int at = track.indexOf(word);
        if (at < 0)
            return 0;
        int end = at + word.length();
        while (end < track.length() && Character.isDigit(track.charAt(end)))
            ++end;
        try {
            return Integer.parseInt(track.substring(at + word.length(), end));
        }
        catch (NumberFormatException e) {
            return 0;
        }
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
