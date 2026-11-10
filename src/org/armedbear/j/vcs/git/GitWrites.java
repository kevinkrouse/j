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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.armedbear.j.Log;
import org.armedbear.j.vcs.CommitOption;
import org.armedbear.j.vcs.FetchOption;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Finish;
import org.armedbear.j.vcs.MergeOption;
import org.armedbear.j.vcs.StashOption;
import org.armedbear.j.vcs.VcsBackend;
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

    // path appended to the root's .gitignore, /-anchored so it means this
    // file alone, not any of its name below here; the file made if it isn't
    // there. What went wrong, or null.
    static String ignore(Path root, String path) {
        final Path file = root.resolve(".gitignore");
        if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0)
            return "A filename containing a newline can't be represented in .gitignore";
        final String rule = "/" + escape(path);
        // Refuse links when opening either handle. Keep the OS's append
        // semantics so a simultaneous append is never overwritten.
        try (SeekableByteChannel channel = Files.newByteChannel(
            file,
            Set.of(
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS));
             SeekableByteChannel reader =
                     Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            final byte[] before = Channels.newInputStream(reader).readAllBytes();
            final String text = new String(before, StandardCharsets.UTF_8);
            // Whole lines only: a rule is already there if a line is it,
            // not if the file merely contains it, as /lib/ is in /vendor/lib/.
            for (String line : text.split("\n", -1))
                if (chomp(line).equals(rule))
                    return null;
            final String sep = text.isEmpty() || text.endsWith("\n") ? "" : "\n";
            // Appended, not rewritten whole: a non-UTF-8 line read above is
            // left as it is on disk, and a crash loses no more than the one line.
            final ByteBuffer added = ByteBuffer.wrap((sep + rule + "\n").getBytes(StandardCharsets.UTF_8));
            while (added.hasRemaining())
                channel.write(added);
            return null;
        }
        catch (IOException | UnsupportedOperationException e) {
            return "Couldn't write " + file + ": " + e.getMessage();
        }
    }

    // line without its trailing carriage return, for a CRLF file.
    private static String chomp(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    // A path as a .gitignore rule: glob characters and the rule's own
    // marks backslash-escaped, so a literal name with one still matches.
    private static String escape(String path) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            final char c = path.charAt(i);
            if (c == '\\' || c == '#' || c == '!' || c == '*' || c == '?' || c == '[' || c == ']' || c == ' ')
                sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
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

    // Stashes the changes, or those to paths if any, whole.
    static VcsResult stash(VcsRepository repository, String message, Set<StashOption> options, List<String> paths) {
        final List<String> args = args("stash", "push");
        if (options.contains(StashOption.KEEP_INDEX))
            args.add("--keep-index");
        if (options.contains(StashOption.UNTRACKED))
            args.add("--include-untracked");
        if (options.contains(StashOption.STAGED))
            args.add("--staged");
        if (message != null)
            args.addAll(List.of("-m", message));
        if (!paths.isEmpty()) {
            args.add("--");
            // Each a path, not a pattern: a file named *.txt alone.
            for (String p : paths)
                args.add(":(literal)" + p);
        }
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

    /**
     * As VcsBackend.commitPrivately: the private index a file in dir, a
     * copy of the repository's made HEAD's tree (`read-tree -i -m`, the
     * index alone, so entries the work tree has changed don't stop it, and
     * unchanged entries keep their file data), as git's commit of paths
     * makes its own; none before the first commit. Gone after.
     */
    static VcsBackend.PrivateCommit commitPrivately(
            VcsRepository repository,
            Path dir,
            List<String> paths,
            Supplier<String> stage,
            String message,
            Set<CommitOption> options) {
        if (dir == null)
            return null;
        // No commit yet only if HEAD is missing, not if it couldn't be read:
        // a tree from nothing would drop every other file.
        final VcsResult head = GitCli.run(GitCli.read(repository.root(), "rev-parse", "--verify", "-q", "HEAD"));
        final boolean born = head.succeeded();
        if (!born && (head.exitCode() != 1 || !head.stderr().isBlank()))
            return new VcsBackend.PrivateCommit(head, null);
        final String before = born ? head.out().strip() : null;
        // Capture before the refusal: a writer between these reads is
        // either refused now or detected when reconciling after the hooks.
        final VcsResult entries = indexEntries(repository, paths);
        if (!entries.succeeded())
            return new VcsBackend.PrivateCommit(entries, null);
        // What the status showed may be old: the real index read now.
        final String refused = refusal(repository, paths);
        if (refused != null)
            return new VcsBackend.PrivateCommit(failed(refused), null);
        Path index = null;
        try {
            Files.createDirectories(dir);
            forgetOld(dir);
            index = Files.createTempFile(dir, "commit-index-", "");
            final Path real = realIndex(repository);
            if (born && real != null && Files.isRegularFile(real))
                Files.copy(real, index, StandardCopyOption.REPLACE_EXISTING);
            else
                // git makes it: an empty file is no index.
                Files.delete(index);
            final Path in = index;
            final VcsResult result = GitCli.withIndex(in, () -> {
                final VcsResult read =
                        run(repository, born ? args("read-tree", "-i", "-m", "HEAD") : args("read-tree", "--empty"));
                if (!read.succeeded())
                    return read;
                final String failed = stage.get();
                if (failed != null)
                    return failed(failed);
                // A commit made meanwhile would be this one's parent, its
                // changes undone by a tree from before it.
                if (!Objects.equals(before, headNow(repository)))
                    return failed("HEAD moved as this was staged: commit again");
                return commit(repository, message, options);
            });
            if (!result.succeeded())
                return new VcsBackend.PrivateCommit(result, null);
            // As git's commit of paths leaves the index: those paths as
            // committed, nothing else touched.
            String trouble = resetPaths(repository, paths, entries.stdout());
            final String parent = firstParent(repository);
            if (!Objects.equals(before, parent))
                trouble = (trouble == null ? "" : trouble + "; ")
                        + "HEAD moved as this committed: see that the commit doesn't undo the one before it";
            return new VcsBackend.PrivateCommit(result, trouble);
        }
        catch (IOException e) {
            Log.error(e);
            return new VcsBackend.PrivateCommit(failed("Couldn't make a private index: " + e.getMessage()), null);
        }
        finally {
            if (index != null)
                try {
                    Files.deleteIfExists(index);
                    Files.deleteIfExists(index.resolveSibling(index.getFileName() + ".lock"));
                }
                catch (IOException e) {
                    Log.error(e);
                }
        }
    }

    private static VcsResult failed(String why) {
        return new VcsResult(null, 1, new byte[0], why, 0);
    }

    // Why paths can't be committed alone, as the real index has them now:
    // a conflict anywhere, or something of them staged; null if they can.
    private static String refusal(VcsRepository repository, List<String> paths) {
        final VcsResult unmerged = GitCli.run(GitCli.read(repository.root(), "ls-files", "-u"));
        if (!unmerged.succeeded())
            return "git: " + firstLine(unmerged);
        if (!unmerged.out().isBlank())
            return "Resolve the conflicts first";
        final List<String> args = args("--literal-pathspecs", "diff", "--cached", "--name-only", "--");
        args.addAll(paths);
        final VcsResult staged = GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
        if (!staged.succeeded())
            return "git: " + firstLine(staged);
        final String first = staged.out().lines().filter(l -> !l.isBlank()).findFirst().orElse(null);
        return first == null ? null : first + " has staged changes: c c commits them, or u unstages them";
    }

    private static String firstLine(VcsResult r) {
        final String err = r.stderr().strip();
        return err.isEmpty() ? "exit code " + r.exitCode() : err.split("\n", 2)[0];
    }

    // HEAD's commit now, or null if there is none.
    private static String headNow(VcsRepository repository) {
        final VcsResult head = GitCli.run(GitCli.read(repository.root(), "rev-parse", "--verify", "-q", "HEAD"));
        return head.succeeded() ? head.out().strip() : null;
    }

    // HEAD's first parent, or null for a first commit.
    private static String firstParent(VcsRepository repository) {
        final VcsResult p = GitCli.run(GitCli.read(repository.root(), "rev-parse", "--verify", "-q", "HEAD^"));
        return p.succeeded() ? p.out().strip() : null;
    }

    private static VcsResult indexEntries(VcsRepository repository, List<String> paths) {
        final List<String> args = args("--literal-pathspecs", "ls-files", "--stage", "-z", "--");
        args.addAll(paths);
        return GitCli.run(GitCli.read(repository.root(), args.toArray(new String[0])));
    }

    // Reconcile only unchanged entries, under git's real index lock. Build
    // from the latest index so staging of unrelated files survives too.
    private static String resetPaths(VcsRepository repository, List<String> paths, byte[] before) {
        final Path real = realIndex(repository);
        if (real == null)
            return "the index couldn't be found; review it before resetting the committed paths";
        final Path lock = real.resolveSibling(real.getFileName() + ".lock");
        boolean held = false;
        Path scratch = null;
        try {
            for (int i = 0; i < 10 && !held; i++) {
                try {
                    Files.write(lock, new byte[0], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    held = true;
                }
                catch (FileAlreadyExistsException e) {
                    Thread.sleep(200);
                }
            }
            if (!held)
                return "the index is locked; review it before resetting the committed paths";
            final VcsResult current = indexEntries(repository, paths);
            if (!current.succeeded())
                return "the index was left unchanged: " + firstLine(current);
            if (!Arrays.equals(before, current.stdout()))
                return "staging changed while the commit ran; the index was left unchanged: review the committed paths";
            final String head = headNow(repository);
            if (head == null)
                return "HEAD couldn't be read; the index was left unchanged";
            scratch = Files.createTempFile(real.getParent(), "j-reconcile-", "");
            if (Files.exists(real))
                Files.copy(real, scratch, StandardCopyOption.REPLACE_EXISTING);
            else
                Files.delete(scratch);
            final Path in = scratch;
            final List<String> args = args("--literal-pathspecs", "reset", "-q", head, "--");
            args.addAll(paths);
            final VcsResult reset = GitCli.withIndex(in, () -> run(repository, args));
            if (!reset.succeeded())
                return "the index was left unchanged: " + firstLine(reset);
            try {
                Files.move(scratch, real, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(scratch, real, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        }
        catch (IOException e) {
            return "the index couldn't be updated: " + e.getMessage();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "updating the index was interrupted; the index was left unchanged";
        }
        finally {
            try {
                if (scratch != null) {
                    Files.deleteIfExists(scratch);
                    Files.deleteIfExists(scratch.resolveSibling(scratch.getFileName() + ".lock"));
                }
                if (held)
                    Files.deleteIfExists(lock);
            }
            catch (IOException e) {
                Log.error(e);
            }
        }
    }

    // Private indexes a j that died left behind, a day old or more.
    private static void forgetOld(Path dir) {
        final long day = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        try (var files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator)
                if (p.getFileName().toString().startsWith("commit-index-")
                        && Files.getLastModifiedTime(p).toMillis() < day)
                    Files.deleteIfExists(p);
        }
        catch (IOException e) {
            Log.error(e);
        }
    }

    // The repository's index file, or null if git can't say.
    private static Path realIndex(VcsRepository repository) {
        final VcsResult r = GitCli.run(GitCli.read(repository.root(), "rev-parse", "--git-path", "index"));
        // Relative to where it ran, the root, unless absolute.
        return r.succeeded() && !r.out().isBlank() ? repository.root().resolve(r.out().strip()) : null;
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
