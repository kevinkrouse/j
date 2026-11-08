/*
 * VcsContextMenuTest.java
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.armedbear.j.MenuItem;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.git.GitFixture;
import org.armedbear.j.vcs.log.LogBuffer;
import org.armedbear.j.vcs.log.LogCommands;
import org.armedbear.j.vcs.signs.SignsUpdater;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The status buffer's right-click menu by line kind: the items, their
 * commands, their keys, and what is dimmed with why. Built headless by
 * calling {@link VcsStatusMode#menuFor}; the caret set as the menu
 * would set it.
 */
public class VcsContextMenuTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private VcsStatusBuffer status;

    private static final String SIXTY;

    static {
        final StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 60; i++)
            sb.append("line ").append(i).append('\n');
        SIXTY = sb.toString();
    }

    private static final StatusActions.Confirm SAVED_CONFIRM = StatusActions.confirm;
    private boolean answer = true;
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    public void setUp() {
        StatusActions.confirm = (editor, text, title) -> {
            asked.add(text);
            return answer;
        };
        CommitCommands.show = (editor, buffer) -> {
            h.showBuffer(buffer);
            return h.editor();
        };
    }

    @AfterEach
    public void tearDown() throws Exception {
        StatusActions.confirm = SAVED_CONFIRM;
        CommitCommands.show = (editor, buffer) -> editor.activateInOtherWindow(buffer);
        final List<Buffer> buffers = new ArrayList<>();
        for (Buffer b : Editor.getBufferList())
            buffers.add(b);
        SwingUtilities.invokeAndWait(() -> {
            for (Buffer b : buffers)
                if (b instanceof VcsStatusBuffer s && s.getDiff() != null)
                    s.closeDiff(null);
            for (Buffer b : buffers)
                if (b instanceof VcsStatusBuffer || b instanceof CommitMessageBuffer || b instanceof LogBuffer) {
                    b.dispose();
                    Editor.getBufferList().remove(b);
                }
        });
        if (h != null) {
            h.showBuffer(h.buffer());
            h.close();
        }
        SwingUtilities.invokeAndWait(RepositoryStatus::closeAll);
        GitFixture.reset();
    }

    // a.txt of sixty lines with lines 3 and 50 changed; b.txt staged; c.txt new.
    private void repository() throws Exception {
        git = GitFixture.init(tmp);
        git.write("a.txt", SIXTY);
        git.write("b.txt", "b\n");
        git.commitAll("first");
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n").replace("line 50\n", "line fifty\n"));
        git.write("b.txt", "b changed\n");
        git.git("add", "b.txt");
        git.write("c.txt", "new\n");
    }

    private VcsRepository repo() {
        return Repositories.forDirectory(git.root);
    }

    private void open() throws Exception {
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsRepository r = repo();
        r.invalidate();
        SwingUtilities.invokeAndWait(() -> {
            status = VcsStatusBuffer.of(List.of(r));
            h.showBuffer(status);
            h.editor().setDot(status.getFirstLine(), 0);
        });
        until(() -> text().contains("  M  a.txt"));
    }

    private void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 200; i++) {
            final boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out: " + text());
    }

    private void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private List<String> text() {
        final List<String> list = new ArrayList<>();
        if (status != null)
            for (Line l = status.getFirstLine(); l != null; l = l.next())
                list.add(l.getText());
        return list;
    }

    private StatusLine line(String text) {
        for (Line l = status.getFirstLine(); l != null; l = l.next())
            if (l.getText().equals(text))
                return (StatusLine) l;
        throw new AssertionError("no line " + text + " in " + text());
    }

    // The first line of the given kind, or throws.
    private StatusLine lineOf(StatusLine.Kind kind) {
        for (Line l = status.getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == kind)
                return s;
        throw new AssertionError("no " + kind + " line in " + text());
    }

    // The first line of the given kind whose text contains what.
    private StatusLine lineOf(StatusLine.Kind kind, String contains) {
        for (Line l = status.getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && s.kind() == kind && s.getText().contains(contains))
                return s;
        throw new AssertionError("no " + kind + " line with " + contains + " in " + text());
    }

    // The menu with the caret on line.
    private JPopupMenu menuOn(StatusLine target) throws Exception {
        final JPopupMenu[] out = new JPopupMenu[1];
        onEdt(() -> {
            h.editor().setDot(target, 0);
            out[0] = VcsStatusMode.menuFor(h.editor());
        });
        assertNotNull(out[0], "no menu at " + target.getText());
        assertRegistered(out[0]);
        return out[0];
    }

    // The menu with the caret on the line whose text is text.
    private JPopupMenu menuAt(String text) throws Exception {
        final JPopupMenu[] out = new JPopupMenu[1];
        onEdt(() -> {
            h.editor().setDot(line(text), 0);
            out[0] = VcsStatusMode.menuFor(h.editor());
        });
        assertNotNull(out[0], "no menu at " + text);
        assertRegistered(out[0]);
        return out[0];
    }

    // The item whose action command is command, or null.
    private static MenuItem item(JPopupMenu menu, String command) {
        for (java.awt.Component c : menu.getComponents())
            if (c instanceof MenuItem mi && command.equals(mi.getActionCommand()))
                return mi;
        return null;
    }

    // The commands of the menu, in order, separators left out.
    private static List<String> commands(JPopupMenu menu) {
        final List<String> out = new ArrayList<>();
        for (java.awt.Component c : menu.getComponents())
            if (c instanceof JMenuItem mi)
                out.add(mi.getActionCommand());
        return out;
    }

    // Every command the status's menu offers is registered, so clicking it
    // runs rather than erroring "Unknown command".
    private static void assertRegistered(JPopupMenu menu) {
        for (String name : commands(menu))
            assertNotNull(org.armedbear.j.CommandTable.getCommand(name), "command " + name + " is registered");
    }

    @Test
    public void aFileLineOffersTheFileItemsWithTheirKeys() throws Exception {
        repository();
        open();
        final JPopupMenu menu = menuAt("  M  a.txt");
        // The whole set, each a command.
        assertTrue(
            commands(menu).containsAll(
                List.of(
                    "vcsCommitFiles",
                    "vcsDiscard",
                    "vcsStage",
                    "vcsUnstage",
                    "vcsChangelistMove",
                    "vcsStatusDiff",
                    "vcsLogFileAt",
                    "vcsBlame",
                    "vcsStatusVisit",
                    "vcsStatusPick",
                    "vcsCopyPath")),
            commands(menu).toString());
        // The keys shown: the status mode's, S/U/X and d for the diff.
        assertEquals("S", item(menu, "vcsStage").getAcceleratorText());
        assertEquals("U", item(menu, "vcsUnstage").getAcceleratorText());
        assertEquals("X", item(menu, "vcsDiscard").getAcceleratorText());
        assertEquals("Shift D", item(menu, "vcsStatusDiff").getAcceleratorText());
        // A tracked changed file, unstaged: Stage live, Unstage dimmed.
        assertTrue(item(menu, "vcsStage").isEnabled());
        assertFalse(item(menu, "vcsUnstage").isEnabled());
        assertEquals("Not staged", item(menu, "vcsUnstage").getToolTipText());
        // Not untracked: no ignore item.
        assertNull(item(menu, "vcsIgnore"));
    }

    @Test
    public void aStagedFilesStageIsDimmedAlreadyStaged() throws Exception {
        repository();
        open();
        final JPopupMenu menu = menuAt("  M  b.txt");
        assertFalse(item(menu, "vcsStage").isEnabled(), "already staged");
        assertEquals("Already staged", item(menu, "vcsStage").getToolTipText());
        assertTrue(item(menu, "vcsUnstage").isEnabled());
    }

    @Test
    public void anUntrackedFileSaysDeleteAndOffersIgnore() throws Exception {
        repository();
        open();
        final JPopupMenu menu = menuAt("  ?  c.txt");
        // An untracked file is added, not staged/unstaged: "Add to VCS", no Unstage.
        assertEquals("Add to VCS", item(menu, "vcsStage").getText());
        assertTrue(item(menu, "vcsStage").isEnabled(), "an untracked file can be added");
        assertNull(item(menu, "vcsUnstage"));
        // Delete, not Discard; and the ignore item.
        assertEquals("Delete…", item(menu, "vcsDiscard").getText());
        assertNotNull(item(menu, "vcsIgnore"));
        // Blame dimmed: nothing to blame in a file not committed.
        assertFalse(item(menu, "vcsBlame").isEnabled());
    }

    @Test
    public void aHunkHeadingOffersCommitAloneAndPick() throws Exception {
        repository();
        open();
        // Open a.txt so its hunk headings show.
        onEdt(() -> status.toggle(line("  M  a.txt")));
        until(() -> {
            for (Line l = status.getFirstLine(); l != null; l = l.next())
                if (l instanceof StatusLine s && s.kind() == StatusLine.Kind.HUNK)
                    return true;
            return false;
        });
        final JPopupMenu menu = menuOn(lineOf(StatusLine.Kind.HUNK));
        assertTrue(
            commands(menu).containsAll(
                List.of(
                    "vcsStage",
                    "vcsUnstage",
                    "vcsDiscard",
                    "vcsChangelistMove",
                    "vcsCommitFiles",
                    "vcsStatusPick")),
            commands(menu).toString());
        // A hunk reads "Commit alone…".
        assertEquals("Commit alone…", item(menu, "vcsCommitFiles").getText());
        // A hunk is pickable: the item is live, not dimmed with a false reason.
        assertTrue(item(menu, "vcsStatusPick").isEnabled(), "a hunk can be picked");
    }

    // Picks pending and the caret on another file: Commit file… commits the
    // caret's file, not the picks, which is vcsCommitPicked's job.
    @Test
    public void commitFilesIgnoresPicksAndCommitsTheCaretFile() throws Exception {
        repository();
        open();
        // b.txt is staged; pick it, then put the caret on a.txt.
        onEdt(() -> status.pick(List.of(line("  M  b.txt"))));
        until(() -> !status.picks().isEmpty());
        onEdt(() -> {
            h.editor().setDot(line("  M  a.txt"), 0);
            CommitCommands.vcsCommitFiles(h.editor());
        });
        until(() -> message() != null);
        final String text = message().getText();
        assertTrue(text.contains("a.txt"), "the caret's file: " + text);
        assertFalse(text.contains("b.txt"), "the picked file left out: " + text);
        finish("Just a");
        until(() -> "Just a".equals(git.git("log", "-1", "--format=%s").strip()));
        assertTrue(git.head("a.txt").contains("line three\n"), "a.txt committed");
        assertEquals("b\n", git.head("b.txt"), "the picked b.txt not committed");
        assertEquals("b changed\n", git.index("b.txt"), "and still staged");
    }

    @Test
    public void theDiffPaneBesideHasAContextMenu() throws Exception {
        repository();
        open();
        // The diff beside: its own buffer, the status's mode. The status's
        // caret on a file makes the pane follow it and lay out its diff.
        onEdt(() -> {
            status.openDiff(null);
            h.editor().setDot(line("  M  a.txt"), 0);
            status.followCaret();
        });
        final org.armedbear.j.vcs.status.StatusDiffBuffer diff = status.getDiff();
        assertNotNull(diff, "the diff pane opened");
        until(() -> {
            for (Line l = diff.getFirstLine(); l != null; l = l.next())
                if (l instanceof StatusLine s && StatusPicks.pickable(s))
                    return true;
            return false;
        });
        final StatusLine hunk = firstDiffLine(diff);
        assertNotNull(hunk, "a pickable diff line in the pane");
        final JPopupMenu[] out = new JPopupMenu[1];
        onEdt(() -> {
            h.showBuffer(diff);
            h.editor().setDot(hunk, 0);
            out[0] = VcsStatusMode.menuFor(h.editor());
        });
        assertNotNull(out[0], "the diff pane's menu builds through owner()");
        assertRegistered(out[0]);
        assertTrue(commands(out[0]).contains("vcsStatusPick"), commands(out[0]).toString());
    }

    // The first pickable diff line in the pane.
    private StatusLine firstDiffLine(org.armedbear.j.vcs.status.StatusDiffBuffer diff) {
        for (Line l = diff.getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && StatusPicks.pickable(s))
                return s;
        return null;
    }

    @Test
    public void aChangelistHeadingDimsDefaultAndActive() throws Exception {
        repository();
        open();
        // Lists Default and Feature; Default stays active.
        onEdt(() -> {
            assertTrue(RepositoryLists.of(repo()).update(c -> c.withList("Feature")));
            SignsUpdater.listsChanged(repo());
        });
        until(() -> text().contains("  Feature (0)"));
        // Default: active, can't be renamed or deleted.
        final JPopupMenu def = menuOn(lineOf(StatusLine.Kind.LIST, "Default"));
        assertFalse(item(def, "vcsChangelistActive").isEnabled(), "Default is active");
        assertEquals("Already active", item(def, "vcsChangelistActive").getToolTipText());
        assertFalse(item(def, "vcsChangelistRename").isEnabled());
        assertFalse(item(def, "vcsChangelistDelete").isEnabled());
        // Feature: not active, all live.
        final JPopupMenu feature = menuOn(lineOf(StatusLine.Kind.LIST, "Feature"));
        assertTrue(item(feature, "vcsChangelistActive").isEnabled());
        assertTrue(item(feature, "vcsChangelistRename").isEnabled());
        assertTrue(item(feature, "vcsChangelistDelete").isEnabled());
    }

    @Test
    public void aSectionHeadingStagesUnstagesDiscardsAllAndToggles() throws Exception {
        repository();
        open();
        final JPopupMenu menu = menuAt("Unstaged changes (1)");
        assertEquals("Stage all", item(menu, "vcsStage").getText());
        assertEquals("Unstage all", item(menu, "vcsUnstage").getText());
        assertEquals("Discard all…", item(menu, "vcsDiscard").getText());
        assertNotNull(item(menu, "vcsStatusToggle"));
    }

    @Test
    public void aCommitLineShowsCopiesAndLogsAndRewordsOnlyHead() throws Exception {
        repository();
        open();
        // A recent commit line: HEAD's, so Reword shows.
        final JPopupMenu menu = menuOn(lineOf(StatusLine.Kind.COMMIT));
        assertTrue(
            commands(menu).containsAll(List.of("vcsStatusVisit", "vcsCopyHash", "vcsLogCommit")),
            commands(menu).toString());
        assertNotNull(item(menu, "vcsCommitReword"), "HEAD's commit can be reworded");
    }

    @Test
    public void aStashLineOffersApplyPopDrop() throws Exception {
        repository();
        open();
        // Make a stash, then read it in.
        onEdt(() -> {
            repo().backend()
                    .stash(repo(), "saved work", java.util.EnumSet.noneOf(org.armedbear.j.vcs.StashOption.class));
            repo().invalidate();
            status.refresh();
        });
        until(() -> text().stream().anyMatch(t -> t.contains("saved work")));
        final String stashLine = text().stream()
                .filter(t -> t.contains("saved work"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stash line in " + text()));
        final JPopupMenu menu = menuAt(stashLine);
        assertEquals(List.of("vcsStashApply", "vcsStashPop", "vcsStashDrop"), commands(menu).subList(0, 3));
        // No commit items on a stash.
        assertNull(item(menu, "vcsCommitReword"));
    }

    @Test
    public void aRepositoryHeadingFetchesRefreshesLogs() throws Exception {
        final Path a = Files.createDirectories(tmp.resolve("a"));
        final Path b = Files.createDirectories(tmp.resolve("b"));
        git = GitFixture.init(a);
        git.write("a.txt", "a\n");
        git.commitAll("first");
        git.write("a.txt", "a changed\n");
        final GitFixture other = GitFixture.init(b);
        other.write("x.txt", "x\n");
        other.commitAll("first");
        other.write("x.txt", "x changed\n");
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        final VcsRepository ra = Repositories.forDirectory(a);
        final VcsRepository rb = Repositories.forDirectory(b);
        onEdt(() -> {
            status = VcsStatusBuffer.of(List.of(ra, rb));
            h.showBuffer(status);
        });
        until(() -> text().contains("  M  x.txt"));
        final JPopupMenu menu = menuOn(lineOf(StatusLine.Kind.REPOSITORY));
        assertTrue(
            commands(menu).containsAll(List.of("vcsFetchPopup", "vcsStatusRefresh", "vcsLog")),
            commands(menu).toString());
    }

    @Test
    public void aSelectionInsideTheClickIsTakenNotTheCaretLine() throws Exception {
        repository();
        open();
        // Select two files' lines; the menu takes the selection.
        final JPopupMenu[] out = new JPopupMenu[1];
        onEdt(() -> {
            h.editor().setMark(new org.armedbear.j.Position(line("  M  a.txt"), 0));
            h.editor().setDot(line("  ?  c.txt"), 0);
            out[0] = VcsStatusMode.menuFor(h.editor());
        });
        final JPopupMenu menu = out[0];
        // A selection spanning a tracked and an untracked file: the
        // commit item says files, and the untracked's Delete shows.
        assertEquals("Commit files…", item(menu, "vcsCommitFiles").getText());
        assertEquals("Delete…", item(menu, "vcsDiscard").getText());
    }

    // vcsCommitFiles: two files committed alone with another staged.
    @Test
    public void vcsCommitFilesCommitsTheSelectedAloneWhatIsStagedStays() throws Exception {
        repository();
        // d.txt another file, committed alone so b.txt stays staged.
        git.write("d.txt", "d\n");
        git.git("add", "d.txt");
        git.git("commit", "-m", "add d", "--", "d.txt");
        git.write("d.txt", "d changed\n");
        open();
        until(() -> text().contains("  M  d.txt"));
        onEdt(() -> {
            h.editor().setMark(new org.armedbear.j.Position(line("  M  a.txt"), 0));
            h.editor().setDot(line("  M  d.txt"), 1);
            CommitCommands.vcsCommitFiles(h.editor());
        });
        until(() -> message() != null);
        assertTrue(message().getText().contains("a.txt"), message().getText());
        assertTrue(message().getText().contains("d.txt"), message().getText());
        finish("Two alone");
        until(() -> "Two alone".equals(git.git("log", "-1", "--format=%s").strip()));
        // a.txt and d.txt committed; b.txt's staged change left staged.
        assertTrue(git.head("a.txt").contains("line three\n"));
        assertTrue(git.head("d.txt").contains("d changed\n"));
        assertEquals("b\n", git.head("b.txt"), "what was staged not committed");
        assertEquals("b changed\n", git.index("b.txt"), "and still staged");
    }

    // A file with something staged is refused.
    @Test
    public void vcsCommitFilesRefusesAFileWithSomethingStaged() throws Exception {
        repository();
        open();
        // a.txt has a staged change and an unstaged one.
        git.write("a.txt", SIXTY.replace("line 3\n", "line three\n"));
        git.git("add", "a.txt");
        git.write("a.txt", SIXTY.replace("line 50\n", "line fifty\n"));
        onEdt(() -> {
            repo().invalidate();
            status.refresh();
        });
        until(() -> status.model().files(repo(), "staged").stream().anyMatch(f -> f.path().equals("a.txt")));
        onEdt(() -> {
            h.editor().setDot(line("  M  a.txt"), 0);
            // The unstaged a.txt: committing it alone is refused as it has a staged change.
            final List<StatusActions.Target> targets = StatusActions.targets(status.model(), h.editor());
            final String why = VcsContextMenus.commitWhy(status.model(), targets);
            assertNotNull(why, "a file with something staged is refused");
            assertTrue(why.contains("staged"), why);
        });
    }

    // vcsIgnore: .gitignore created, the file gone from Untracked.
    @Test
    public void vcsIgnoreCreatesGitignoreAndDropsTheFile() throws Exception {
        repository();
        open();
        onEdt(() -> {
            h.editor().setDot(line("  ?  c.txt"), 0);
            StatusActions.vcsIgnore(h.editor());
        });
        until(() -> !text().contains("  ?  c.txt"));
        final String ignore = git.read(".gitignore");
        assertTrue(ignore.contains("/c.txt"), ignore);
    }

    @Test
    public void vcsIgnoreAppendsToAnExistingGitignore() throws Exception {
        repository();
        git.write(".gitignore", "/keep.txt\n");
        open();
        onEdt(() -> {
            h.editor().setDot(line("  ?  c.txt"), 0);
            StatusActions.vcsIgnore(h.editor());
        });
        until(() -> !text().contains("  ?  c.txt"));
        final String ignore = git.read(".gitignore");
        assertTrue(ignore.contains("/keep.txt"), ignore);
        assertTrue(ignore.contains("/c.txt"), ignore);
    }

    // Show history on a file that isn't the current buffer.
    @Test
    public void showFileLogOpensTheLogOfAFileNotInTheBuffer() throws Exception {
        repository();
        open();
        onEdt(() -> {
            h.editor().setDot(line("  M  a.txt"), 0);
            LogCommands.vcsLogFileAt(h.editor());
        });
        until(() -> {
            for (Buffer b : Editor.getBufferList())
                if (b instanceof LogBuffer log && "a.txt".equals(log.getQuery().path()))
                    return true;
            return false;
        });
    }

    private void finish(String subject) throws Exception {
        until(() -> {
            for (Buffer b : Editor.getBufferList())
                if (b instanceof CommitMessageBuffer m && m.getPartial() != null)
                    return true;
            return false;
        });
        final CommitMessageBuffer m = message();
        onEdt(() -> {
            final String text = m.getText();
            final int at = text.indexOf("# ");
            CommitCommands.replace(h.editor(), m, subject + "\n\n" + (at >= 0 ? text.substring(at) : ""));
            CommitCommands.vcsCommitFinish(h.editor());
        });
        until(() -> !Editor.getBufferList().contains(m) || !m.isCommitting() && m.getText() != null);
    }

    private CommitMessageBuffer message() {
        for (Buffer b : Editor.getBufferList())
            if (b instanceof CommitMessageBuffer m)
                return m;
        return null;
    }
}
