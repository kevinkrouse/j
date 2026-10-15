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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.armedbear.j.vcs.CommitOption;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;

/** Git's writes for the status buffer: staging, discarding and committing. */
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
