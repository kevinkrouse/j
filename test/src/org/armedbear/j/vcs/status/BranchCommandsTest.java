/*
 * BranchCommandsTest.java
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

package org.armedbear.j.vcs.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.vcs.MergeOption;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.log.LogRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class BranchCommandsTest {
    @TempDir
    Path tmp;

    private static final BranchCommands.Confirm CONFIRM = BranchCommands.confirm;
    private static final BranchCommands.Ask ASK = BranchCommands.ask;
    private static final BranchCommands.Choose CHOOSE = BranchCommands.choose;

    private GitFixture git;

    // What each list offered, in order.
    private final List<List<String>> offered = new ArrayList<>();

    @AfterEach
    public void reset() {
        GitFixture.reset();
        BranchCommands.confirm = CONFIRM;
        BranchCommands.ask = ASK;
        BranchCommands.choose = CHOOSE;
    }

    // A clone of a repository with main and feature, a commit on each, on
    // main with a local branch a at it and b a commit past it.
    private void cloned() throws Exception {
        final GitFixture origin = GitFixture.init(Files.createDirectory(tmp.resolve("origin")));
        origin.write("f.txt", "one\n");
        origin.commitAll("one");
        origin.git("branch", "feature");
        origin.git("switch", "-q", "feature");
        origin.write("g.txt", "feature\n");
        origin.commitAll("on feature");
        origin.git("switch", "-q", "main");
        git = GitFixture.init(Files.createDirectory(tmp.resolve("work")));
        git.git("remote", "add", "origin", origin.root.toString());
        git.git("fetch", "-q", "origin");
        git.git("reset", "-q", "--hard", "origin/main");
        git.git("branch", "-q", "--set-upstream-to=origin/main");
        git.git("branch", "-q", "a");
        git.git("switch", "-q", "-c", "b");
        git.write("b.txt", "b\n");
        git.commitAll("on b");
        git.git("switch", "-q", "main");
    }

    private VcsRepository repo() {
        final VcsRepository r = Repositories.forDirectory(git.root);
        r.invalidate();
        return r;
    }

    private String branch() {
        return git.git("branch", "--show-current").strip();
    }

    private boolean has(String ref) {
        return git.run("rev-parse", "--verify", "-q", ref).succeeded();
    }

    private BranchCommands.Context context(String at) {
        final String current = branch();
        return new BranchCommands.Context(repo(),
                at,
                current.isEmpty() ? null : current,
                repo().backend().readRefTips(repo()));
    }

    // Runs command with the ref at, each list answered with pick.
    private void run(BiConsumer<Editor, String> command, String at, String pick) throws Exception {
        BranchCommands.choose = (editor, history, names, chosen) -> {
            offered.add(names);
            chosen.accept(pick);
        };
        final EditorHarness h = EditorHarness.create();
        onEdt(() -> command.accept(h.editor(), (at == null ? "-" : at) + " " + git.root));
    }

    @Test
    public void aBranchMadeFromTheOneCheckedOutLeavesItFirstToCheckOut() throws Exception {
        cloned();
        git.git("switch", "-q", "-c", "new");
        // In the status, its commit's refs: the other branch, not new.
        assertEquals("main", BranchCommands.refOf("abc1234", "HEAD -> new, main"));
        run(BranchCommands::vcsBranchCheckout, "main", "main");
        until(() -> branch().equals("main"));
        assertEquals("main", offered.get(0).get(0));
        assertFalse(offered.get(0).contains("new"), "not the one checked out");
    }

    @Test
    public void aLocalBranchOrARemotesWithoutOneIsCheckedOutTrackingIt() throws Exception {
        cloned();
        run(BranchCommands::vcsBranchCheckoutLocal, null, "origin/feature");
        until(() -> branch().equals("feature"));
        assertEquals(List.of("a", "b", "origin/feature"), offered.get(0), "origin/main has main; not origin/HEAD");
        assertEquals("origin/feature", git.git("rev-parse", "--abbrev-ref", "feature@{upstream}").strip());
    }

    @Test
    public void aRemotesBranchWithALocalOneChecksOutThatOne() throws Exception {
        cloned();
        git.git("branch", "feature", "origin/main");
        run(BranchCommands::vcsBranchCheckout, null, "origin/feature");
        until(() -> branch().equals("feature"));
        assertEquals(git.git("rev-parse", "origin/main"), git.git("rev-parse", "HEAD"), "its own");
    }

    @Test
    public void theRecentBranchesAreTheReflogsLatestFirst() throws Exception {
        cloned();
        git.git("switch", "-q", "a");
        git.git("switch", "-q", "b");
        git.git("switch", "-q", "main");
        git.git("branch", "-q", "-D", "a");
        assertEquals(List.of("main", "b", "a"), repo().backend().readRecentBranches(repo()));
        run(BranchCommands::vcsBranchCheckoutRecent, null, "b");
        until(() -> branch().equals("b"));
        assertEquals(List.of("b"), offered.get(0), "neither the one checked out nor one gone");
    }

    @Test
    public void aNewBranchStartsWhereItIsAskedToAndARemotesOffersItsName() throws Exception {
        cloned();
        final List<String> suggested = new ArrayList<>();
        BranchCommands.ask = (editor, prompt, title, offer) -> {
            suggested.add(offer);
            return "feature2";
        };
        run(BranchCommands::vcsBranchCreate, "a", "origin/feature");
        until(() -> has("refs/heads/feature2"));
        assertEquals(List.of("a", "main"), offered.get(0).subList(0, 2), "the ref at the caret, then HEAD's");
        assertEquals(List.of("feature"), suggested);
        assertEquals("main", branch(), "n: not checked out");
        assertEquals(git.git("rev-parse", "origin/feature"), git.git("rev-parse", "feature2"));
        BranchCommands.ask = (editor, prompt, title, offer) -> "c";
        run(BranchCommands::vcsBranchCheckoutNew, null, "b");
        until(() -> branch().equals("c"));
        assertEquals(git.git("rev-parse", "b"), git.git("rev-parse", "c"));
    }

    @Test
    public void aRenameOffersTheRefAtTheCaretThenTheOneCheckedOut() throws Exception {
        cloned();
        BranchCommands.ask = (editor, prompt, title, offer) -> "aa";
        run(BranchCommands::vcsBranchRename, "a", "a");
        until(() -> has("refs/heads/aa"));
        assertEquals(List.of("a", "main", "b"), offered.get(0));
        assertFalse(has("refs/heads/a"));
    }

    @Test
    public void aCheckoutWithChangesInTheWaySaysWhatGitSaidAndChangesNothing() throws Exception {
        cloned();
        git.git("switch", "-q", "b");
        git.write("f.txt", "on b\n");
        git.commitAll("f on b");
        git.git("switch", "-q", "main");
        git.write("f.txt", "changed\n");
        final VcsResult r = repo().backend().switchTo(repo(), new LogRef("b", LogRef.Kind.BRANCH, false));
        assertFalse(r.succeeded());
        assertEquals("main", branch());
        assertEquals("changed\n", git.read("f.txt"));
        assertEquals(
            "Checking out b failed: Your local changes to the following files would be overwritten by checkout",
            BranchCommands.failed("Checking out b", r));
    }

    @Test
    public void theCommitsOnlyOnABranchAreTheOnesItsDeleteWouldLose() throws Exception {
        cloned();
        assertEquals(List.of(), repo().backend().readOnlyOn(repo(), "a"));
        final List<String> only = repo().backend().readOnlyOn(repo(), "b");
        assertEquals(1, only.size());
        assertTrue(only.get(0).endsWith(" on b"), only.get(0));
        git.git("switch", "-q", "--detach", "b");
        git.write("b.txt", "stashed\n");
        git.git("stash", "-q");
        assertEquals(only, repo().backend().readOnlyOn(repo(), "b"), "nor HEAD at it, nor a stash on it, keeps them");
        git.git("switch", "-q", "main");
        assertFalse(repo().backend().deleteBranch(repo(), "b", false).succeeded(), "-d refuses it");
        assertTrue(
            BranchCommands.lost("b", List.of("abc1234 on b")).startsWith("b has a commit on no other branch or tag"));
    }

    @Test
    public void aDeleteAsksAgainNamingTheCommitsItWouldLose() throws Exception {
        cloned();
        final List<String> asked = new ArrayList<>();
        final boolean[] answers = { true, false };
        BranchCommands.confirm = (editor, text, title) -> {
            asked.add(text);
            return answers[asked.size() - 1];
        };
        run(BranchCommands::vcsBranchDelete, "b", "b");
        until(() -> asked.size() == 2);
        assertEquals(List.of("b", "a"), offered.get(0), "the ref at the caret first, not the one checked out");
        assertEquals("Delete branch b?", asked.get(0));
        assertTrue(asked.get(1).contains(" on b"), asked.get(1));
        Thread.sleep(200);
        assertTrue(has("refs/heads/b"), "kept: the second was no");
        // a's commits are main's: asked once, and deleted.
        asked.clear();
        run(BranchCommands::vcsBranchDelete, null, "a");
        until(() -> !has("refs/heads/a"));
        assertEquals(1, asked.size());
        // b both times yes: deleted with -D.
        asked.clear();
        answers[1] = true;
        run(BranchCommands::vcsBranchDelete, null, "b");
        until(() -> !has("refs/heads/b"));
    }

    @Test
    public void theListsPutTheRefAtTheCaretFirstAndLeaveOutTheOneCheckedOut() throws Exception {
        cloned();
        git.git("tag", "feature");
        final BranchCommands.Context c = context("b");
        final List<String> locals = c.names(LogRef.Kind.BRANCH);
        assertEquals(List.of("b", "a"), c.offered(locals, false));
        assertEquals(List.of("a", "b"), context("abc1234").offered(locals, false), "not a branch: not offered");
        assertEquals("abc1234", context("abc1234").offered(c.refs(), true).get(0), "a revision too, with any");
        assertEquals(LogRef.Kind.TAG, c.ref("feature").kind(), "no local feature: the tag");
        assertEquals(LogRef.Kind.REMOTE, c.ref("origin/feature").kind());
        assertEquals(LogRef.Kind.HEAD, c.ref("abc1234").kind(), "a revision, detached");
        assertEquals("refs/remotes/origin/feature", c.ref("origin/feature").fullName());
    }

    @Test
    public void aStatusCommitsRefIsTheFirstButTheBranchCheckedOut() {
        assertEquals("origin/main", BranchCommands.refOf("abc1234", "HEAD -> main, origin/main, tag: v1"));
        assertEquals("v1", BranchCommands.refOf("abc1234", "tag: v1"));
        assertEquals(
            "feature",
            BranchCommands.refOf("abc1234", "HEAD -> main, tag: v1, feature"),
            "a branch before a tag");
        assertEquals(
            "v1",
            BranchCommands.refOf("abc1234", "HEAD -> main, tag: v1"),
            "a tag before the branch checked out");
        assertEquals("main", BranchCommands.refOf("abc1234", "HEAD -> main"));
        assertEquals("abc1234", BranchCommands.refOf("abc1234", "HEAD"));
        assertEquals("abc1234", BranchCommands.refOf("abc1234", ""));
    }

    @Test
    public void thePopupIsNeogitsItsCommandsGivenTheRefAtTheCaret() throws Exception {
        cloned();
        final Transient p = BranchCommands.popup(context("a"));
        final String arg = " a " + git.root;
        assertEquals("vcsBranchCheckout" + arg, ((Command) p.item("b")).command());
        assertEquals("vcsBranchCheckout", ((Command) p.item("b")).name());
        for (String key : List.of("l", "r", "c", "n", "m", "D", "p"))
            assertTrue(((Command) p.item(key)).command().endsWith(arg), key);
        assertEquals(
            "vcsBranchCheckoutLocal - " + git.root,
            ((Command) BranchCommands.popup(context(null)).item("l")).command());
        git.git("switch", "-q", "--detach", "a");
        assertNull(((Command) BranchCommands.popup(context("a")).item("p")).command(), "no branch to be the parent of");
    }

    @Test
    public void aFastForwardOnlyMergeFailsWhereTheyDivergeAndNoFastForwardMakesAMergeCommit() throws Exception {
        cloned();
        assertTrue(repo().backend().merge(repo(), "refs/heads/b", EnumSet.of(MergeOption.NO_FF)).succeeded());
        assertEquals(2, git.git("log", "-1", "--format=%p").strip().split(" ").length, "--no-ff: a merge commit");
        assertFalse(
            repo().backend().merge(repo(), "refs/remotes/origin/feature", EnumSet.of(MergeOption.FF_ONLY)).succeeded());
        assertEquals("vcsMerge", ((Command) MergeCommands.popup(context(null), false).item("m")).name());
    }

    @Test
    public void aMergesConflictIsSaidAndCanBeAborted() throws Exception {
        cloned();
        git.git("switch", "-q", "a");
        git.write("f.txt", "a's\n");
        git.commitAll("f on a");
        git.git("switch", "-q", "main");
        git.write("f.txt", "main's\n");
        git.commitAll("f on main");
        final VcsResult r = repo().backend().merge(repo(), "refs/heads/a", EnumSet.noneOf(MergeOption.class));
        assertFalse(r.succeeded());
        assertEquals("Merging a conflicted: the conflicts are in Unmerged", BranchCommands.failed("Merging a", r));
        assertTrue(repo().backend().isMerging(repo()));
        final Transient merging = MergeCommands.popup(context(null), true);
        assertEquals("vcsMergeCommit", ((Command) merging.item("m")).name());
        assertEquals("vcsMergeAbort", ((Command) merging.item("a")).name());
        assertTrue(repo().backend().mergeAbort(repo()).succeeded());
        assertFalse(repo().backend().isMerging(repo()));
        assertEquals("main's\n", git.read("f.txt"));
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 200; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out");
    }
}
