/*
 * GitWrites.java
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.armedbear.j.vcs.CommitOption;
import org.armedbear.j.vcs.FetchOption;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Finish;
import org.armedbear.j.vcs.MergeOption;
import org.armedbear.j.vcs.StashOption;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.log.LogRef;

/** Git's writes for the status buffer: staging, discarding, committing, stashing and branching. */
final class GitWrites {
    // A commit's hooks may lint or test for a while.
    private static final Duration COMMIT_TIMEOUT = Duration.ofMinutes(30);

    private GitWrites() {}

    private static VcsResult run(VcsRepository repository, List<String> args) {
        return GitCli.run(GitCli.write(repository.root(), null, args.toArray(new String[0])));
    }

    private static List<String> args(String... args) {
        return new ArrayList<>(List.of(args));
    }

    static VcsResult stage(VcsRepository repository, List<String> paths) {
        final List<String> args = args("--literal-pathspecs", "add", "-A", "--");
        args.addAll(paths);
        return run(repository, args);
    }

    static VcsResult fetch(VcsRepository repository, String remote, String refspec, Set<FetchOption> options) {
        final List<String> args = args("fetch");
        if (options.contains(FetchOption.PRUNE))
            args.add("--prune");
        if (options.contains(FetchOption.TAGS))
            args.add("--tags");
        if (remote == null)
            args.add("--all");
        else {
            args.add(remote);
            if (refspec != null)
                args.add(refspec);
        }
        return run(repository, args);
    }

    /**
     * The parent as GitHub's CLI records it, which gh pr create takes as
     * its base; none to remove it, which is no failure if there is none.
     */
    static VcsResult setParent(VcsRepository repository, String branch, String parent) {
        final String key = "branch." + branch + ".gh-merge-base";
        if (parent != null)
            return run(repository, args("config", key, parent));
        final VcsResult r = run(repository, args("config", "--unset", key));
        // 5: there was none.
        return r.exitCode() == 5 ? new VcsResult(r.command(), 0, r.stdout(), "", r.millis()) : r;
    }

    /**
     * The refs a fetch moved, as git says, a line each: " * [new branch]
     * x -> origin/x", "   a..b  main -> origin/main". Not a branch fetched
     * by name, which is "-> FETCH_HEAD" moved or not.
     */
    static int fetchedRefs(String said) {
        int refs = 0;
        for (String line : said.split("\n"))
            if (line.startsWith(" ") && line.contains(" -> ") && !line.endsWith("-> FETCH_HEAD"))
                refs++;
        return refs;
    }

    static VcsResult stash(VcsRepository repository, String message, Set<StashOption> options) {
        final List<String> args = args("stash", "push");
        if (options.contains(StashOption.KEEP_INDEX))
            args.add("--keep-index");
        if (options.contains(StashOption.UNTRACKED))
            args.add("--include-untracked");
        if (options.contains(StashOption.STAGED))
            args.add("--staged");
        if (message != null)
            args.addAll(List.of("-m", message));
        // An older git's usage text would be all it said.
        if (options.contains(StashOption.STAGED) && !GitCli.versionAtLeast(STASH_STAGED))
            return new VcsResult(GitCli.write(repository.root(), null, args.toArray(new String[0])),
                    1,
                    new byte[0],
                    "fatal: git " + GitCli.versionString() + " can't stash what is staged alone; 2.35 can",
                    0);
        return run(repository, args);
    }

    private static final int[] STASH_STAGED = { 2, 35 };

    static VcsResult stashApply(VcsRepository repository, String name, String id, boolean pop) {
        // Applied by its hash, the one shown whatever name it has now; pop
        // takes only a name.
        if (!pop)
            return run(repository, args("stash", "apply", id));
        final VcsResult moved = moved(repository, name, id);
        return moved != null ? moved : run(repository, args("stash", "pop", name));
    }

    static VcsResult stashDrop(VcsRepository repository, String name, String id) {
        if (name == null) {
            final Map<String, String> names = stashNames(repository);
            name = names == null ? null : names.get(id);
            if (name == null)
                return new VcsResult(null, 1, new byte[0], "The stash is gone", 0);
        }
        final VcsResult moved = moved(repository, name, id);
        return moved != null ? moved : run(repository, args("stash", "drop", name));
    }

    /** Each stash's name now, as "stash@{1}", by its id; null if git fails. */
    static Map<String, String> stashNames(VcsRepository repository) {
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), "stash", "list", "--format=%gd %H"));
        if (!r.succeeded())
            return null;
        final Map<String, String> names = new HashMap<>();
        for (String line : r.out().split("\n")) {
            final int space = line.indexOf(' ');
            if (space > 0)
                names.put(line.substring(space + 1), line.substring(0, space));
        }
        return names;
    }

    static VcsResult switchTo(VcsRepository repository, LogRef ref) {
        return switch (ref.kind()) {
            case BRANCH -> run(repository, args("switch", "--no-guess", ref.name()));
            case REMOTE -> run(repository, args("switch", "--track", "refs/remotes/" + ref.name()));
            case TAG -> run(repository, args("switch", "--detach", "refs/tags/" + ref.name()));
            case HEAD, STASH -> run(repository, args("switch", "--detach", ref.name()));
        };
    }

    static VcsResult createBranch(VcsRepository repository, String name, String from, boolean checkout) {
        return run(repository, checkout ? args("switch", "-c", name, from) : args("branch", name, from));
    }

    static VcsResult renameBranch(VcsRepository repository, String branch, String name) {
        return run(repository, args("branch", "-m", branch, name));
    }

    static VcsResult deleteBranch(VcsRepository repository, String branch, boolean force) {
        return run(repository, args("branch", force ? "-D" : "-d", branch));
    }

    static VcsResult merge(VcsRepository repository, String ref, Set<MergeOption> options) {
        final List<String> args = args("merge", "--no-edit");
        if (options.contains(MergeOption.FF_ONLY))
            args.add("--ff-only");
        if (options.contains(MergeOption.NO_FF))
            args.add("--no-ff");
        args.add(ref);
        return run(repository, args);
    }

    /**
     * Continues, skips or aborts operation: a merge's continue is its
     * commit, git's message kept; null for nothing in progress, or a
     * skip of what has no steps. No editor: the messages are
     * kept. A continue or skip can stop at the next step's conflict, so
     * as the merges before it they keep the base with the conflict style
     * when the user has set none.
     */
    static VcsResult finish(VcsRepository repository, GitSnapshot.Operation operation, Finish how) {
        if (operation.inProgress == null || how == Finish.SKIP && !operation.inProgress.steps)
            return null;
        if (operation == GitSnapshot.Operation.MERGE && how == Finish.CONTINUE)
            // git's message, its "# Conflicts:" comments stripped, as an
            // editor would: --no-edit alone keeps them.
            return GitCli.run(
                GitCli.write(
                    repository.root(),
                    null,
                    COMMIT_TIMEOUT,
                    Map.of("GIT_EDITOR", "true"),
                    "commit",
                    "--no-edit",
                    "--cleanup=strip"));
        final List<String> args = how == Finish.ABORT ? args() : conflictStyle(repository);
        args.addAll(List.of(operation.command, switch (how) {
            case CONTINUE -> "--continue";
            case SKIP -> "--skip";
            case ABORT -> "--abort";
        }));
        return GitCli.run(
            GitCli.write(
                repository.root(),
                null,
                COMMIT_TIMEOUT,
                Map.of("GIT_EDITOR", "true"),
                args.toArray(new String[0])));
    }

    // "-c merge.conflictStyle=" the base style when the user has set
    // none: a conflict with its base.
    private static List<String> conflictStyle(VcsRepository repository) {
        final VcsResult set = GitCli.run(GitCli.read(repository.root(), "config", "--get", "merge.conflictStyle"));
        if (set.succeeded() && !set.out().isBlank())
            return args();
        return args("-c", "merge.conflictStyle=" + baseStyle());
    }

    // zdiff3 keeps the base between the sides; older gits know only diff3.
    private static String baseStyle() {
        return GitCli.versionAtLeast(ZDIFF3) ? "zdiff3" : "diff3";
    }

    // A failure if name is no longer the stash id, as after a stash made
    // since the status was read; null if it is.
    private static VcsResult moved(VcsRepository repository, String name, String id) {
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), "rev-parse", "--verify", "-q", name));
        if (r.succeeded() && r.out().strip().equals(id))
            return null;
        return new VcsResult(r.command(), 1, r.stdout(), name + " is no longer the stash shown: refresh", r.millis());
    }

    // In batches, as the reads that find paths are.
    static VcsResult unresolve(VcsRepository repository, List<String> paths) {
        VcsResult r = null;
        for (int i = 0; i < paths.size(); i += GitStatusReader.BATCH) {
            final List<String> args = args("update-index", "--unresolve", "--");
            args.addAll(paths.subList(i, Math.min(paths.size(), i + GitStatusReader.BATCH)));
            r = run(repository, args);
            if (!r.succeeded())
                return r;
        }
        return r;
    }

    static VcsResult stageAll(VcsRepository repository) {
        return run(repository, args("add", "-u"));
    }

    static VcsResult unstage(VcsRepository repository, List<FileChange> changes) {
        final List<String> paths = new ArrayList<>();
        for (FileChange c : changes) {
            if (c.origPath() != null)
                paths.add(c.origPath());
            paths.add(c.path());
        }
        // Before the first commit too, reset takes them out of the index.
        final List<String> args = args("--literal-pathspecs", "reset", "-q", "--");
        args.addAll(paths);
        return run(repository, args);
    }

    static VcsResult unstageAll(VcsRepository repository) {
        return run(repository, args("reset", "-q"));
    }

    static VcsResult discard(VcsRepository repository, List<String> paths) {
        final List<String> args = args("--literal-pathspecs", "checkout", "-q", "--");
        args.addAll(paths);
        return run(repository, args);
    }

    static VcsResult checkoutSide(VcsRepository repository, List<String> paths, boolean ours) {
        final List<String> args = args("--literal-pathspecs", "checkout", ours ? "--ours" : "--theirs", "--");
        args.addAll(paths);
        return run(repository, args);
    }

    static VcsResult restoreConflict(VcsRepository repository, List<String> paths) {
        final List<String> args = args("--literal-pathspecs", "checkout", "-m", "--conflict=" + baseStyle(), "--");
        args.addAll(paths);
        return run(repository, args);
    }

    private static final int[] ZDIFF3 = { 2, 35 };

    static VcsResult apply(
            VcsRepository repository,
            byte[] patch,
            boolean index,
            boolean workingTree,
            boolean reverse) {
        return apply(repository, patch, index, workingTree, reverse, false);
    }

    /** With check, only whether it would apply. */
    static VcsResult apply(
            VcsRepository repository,
            byte[] patch,
            boolean index,
            boolean workingTree,
            boolean reverse,
            boolean check) {
        final List<String> args = args("apply", "--whitespace=nowarn");
        if (check)
            args.add("--check");
        if (index && workingTree)
            args.add("--index");
        else if (index)
            args.add("--cached");
        if (reverse)
            args.add("--reverse");
        args.add("-");
        return GitCli.run(GitCli.write(repository.root(), patch, args.toArray(new String[0])));
    }

    static VcsResult commit(VcsRepository repository, String message, Set<CommitOption> options) {
        final List<String> args = args("commit");
        if (options.contains(CommitOption.AMEND))
            args.add("--amend");
        if (options.contains(CommitOption.MESSAGE_ONLY))
            args.add("--only");
        if (options.contains(CommitOption.NO_VERIFY))
            args.add("--no-verify");
        if (options.contains(CommitOption.SIGNOFF))
            args.add("--signoff");
        if (options.contains(CommitOption.ALL))
            args.add("--all");
        if (options.contains(CommitOption.ALLOW_EMPTY))
            args.add("--allow-empty");
        byte[] stdin = null;
        if (message == null)
            args.add("--no-edit");
        else {
            // As git's own editor would leave it: no # lines, no blank ends.
            args.addAll(
                List.of(
                    options.contains(CommitOption.VERBATIM) ? "--cleanup=whitespace" : "--cleanup=strip",
                    "-F",
                    "-"));
            stdin = message.getBytes(StandardCharsets.UTF_8);
        }
        // No editor: the message is given, or kept.
        return GitCli.run(
            GitCli.write(
                repository.root(),
                stdin,
                COMMIT_TIMEOUT,
                Map.of("GIT_EDITOR", "true"),
                args.toArray(new String[0])));
    }

    static boolean verboseCommits(VcsRepository repository) {
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), "config", "--bool", "--get", "commit.verbose"));
        return r.succeeded() && r.out().strip().equals("true");
    }

    /**
     * What the commit takes: the staged diff, or with ALL the working tree's,
     * against HEAD, or for an amend against HEAD's parent (the empty tree
     * for a first commit); a reword's, the last commit's own.
     */
    static byte[] commitDiff(VcsRepository repository, Set<CommitOption> options) {
        final List<String> args;
        if (options.contains(CommitOption.MESSAGE_ONLY))
            args = args("show", "--format=", "--no-ext-diff", "--no-color", "HEAD");
        else {
            args = args("diff", "--no-ext-diff", "--no-color");
            if (!options.contains(CommitOption.ALL))
                args.add("--cached");
            final String base =
                    options.contains(CommitOption.AMEND) ? parentOfHead(repository) : headOrNull(repository);
            if (base != null)
                args.add(base);
        }
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        return r.succeeded() ? r.stdout() : null;
    }

    private static String headOrNull(VcsRepository repository) {
        return GitLog.hasHead(repository) ? "HEAD" : null;
    }

    // HEAD's parent, or the empty tree when HEAD is a first commit.
    private static String parentOfHead(VcsRepository repository) {
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), "rev-parse", "-q", "--verify", "HEAD^"));
        if (r.succeeded())
            return "HEAD^";
        final VcsResult empty =
                GitCli.run(GitCli.write(repository.root(), new byte[0], "hash-object", "-t", "tree", "--stdin"));
        return empty.succeeded() ? empty.out().strip() : null;
    }
}
