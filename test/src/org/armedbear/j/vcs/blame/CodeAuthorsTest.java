/*
 * CodeAuthorsTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.blame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.EditCommands;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.armedbear.j.Property;
import org.armedbear.j.TextLine;
import org.armedbear.j.VirtualText;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.git.GitFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The authors of a method: the entry after its first line, its hover, its click, and when they show. */
public class CodeAuthorsTest {
    // A class with two methods: one all Ann, one Ann with a line by Bob.
    private static final String JAVA =
            "class A {\n" + "    void one() {\n" + "        a();\n" + "        b();\n" + "        c();\n" + "    }\n"
                    + "    void two() {\n" + "        d();\n" + "        e();\n" + "    }\n" + "}\n";

    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private Buffer file;
    // A second buffer a test opens, removed after it.
    private Buffer other;

    @AfterEach
    public void tearDown() throws Exception {
        onEdt(BlameText::reset);
        onEdt(() -> {
            if (file != null) {
                for (GutterColumn c : List.copyOf(file.getGutterColumns()))
                    if (c instanceof BlameColumn column) {
                        file.removeGutterColumn(column);
                        column.blame().removeListener(column.changed());
                    }
                Editor.getBufferList().remove(file);
            }
            if (other != null)
                Editor.getBufferList().remove(other);
            if (h != null) {
                h.showBuffer(h.buffer());
                h.close();
            }
        });
        GitFixture.reset();
    }

    @Test
    public void theDominantAuthorAndHowManyOthers() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        // One line of method two by Bob.
        git.write("A.java", JAVA.replace("        e();", "        e2();"));
        commitAs("Bob", "bob's line");
        openAuthors("A.java");
        rest(1);
        // Method one is all Ann; method two is Ann with Bob the one other.
        assertEquals("Ann", untilAuthors(1));
        assertEquals("Ann +1", untilAuthors(6));
    }

    @Test
    public void theClassCountsEveryLineInItsMethods() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        git.write("A.java", JAVA.replace("        e();", "        e2();"));
        commitAs("Bob", "bob's line");
        openAuthors("A.java");
        rest(0);
        // The class spans both methods: Ann the most, Bob the one other.
        assertEquals("Ann +1", untilAuthors(0));
        final String tip = tip(0);
        assertTrue(tip.contains("Ann") && tip.contains("Bob"), tip);
        // The class's Ann count covers both methods, more than one method alone.
        assertTrue(countFor(tip, "Ann") > countFor(tip(1), "Ann"), tip);
    }

    @Test
    public void anUncommittedLineCountsForTheUserAndStarsTheEntry() throws Exception {
        git = GitFixture.init(tmp);
        git.git("config", "user.name", "Kevin Krouse");
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        openAuthors("A.java");
        // Three of method one's body lines edited, not saved: the user owns
        // more of it than Ann, who keeps the declaration and closing brace.
        editLines(2, 3, 4);
        rest(1);
        assertEquals("Krouse* +1", untilAuthors(1));
        final String tip = tip(1);
        assertTrue(tip.contains("Not committed yet"), tip);
    }

    @Test
    public void withTheUserUnsetUncommittedStarsButCountsForNoOne() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        openAuthors("A.java");
        editLines(2, 3, 4);
        rest(1);
        // No user.name: the uncommitted lines give the star but name no one.
        assertEquals("Ann*", untilAuthors(1));
    }

    @Test
    public void noneWithThePreferenceOffAndClearedWhenTurnedOff() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        openAuthors("A.java");
        assertEquals("Ann", untilAuthors(1));
        // Off: the next show clears them.
        file.setProperty(Property.VCS_CODE_AUTHORS, false);
        rest(1);
        until(() -> noAuthorsOn(1));
        assertNull(authorsText(1));
    }

    @Test
    public void theAuthorsShowWithTheLineBlameOff() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        openAuthors("A.java");
        file.setProperty(Property.VCS_LINE_BLAME, false);
        rest(1);
        assertEquals("Ann", untilAuthors(1));
        // The caret line's own blame is gone; the authors remain.
        assertNull(lineBlameText(1));
    }

    @Test
    public void onTheCaretLineTheAuthorsComeBeforeTheLineBlame() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        openAuthors("A.java");
        file.setProperty(Property.VCS_LINE_BLAME, true);
        rest(1);
        untilAuthors(1);
        untilLineBlame(1);
        // Both EOL on the caret's line: the authors nearer the text (priority 0).
        final List<VirtualText> eol =
                onEdtList(() -> file.getVirtualText().on(file.getLine(1), VirtualText.Placement.EOL));
        assertEquals(2, eol.size(), "both the authors and the line blame");
        assertTrue(eol.get(0).priority() < eol.get(1).priority(), "authors first");
        // The nearer one is the authors' entry: it's in the authors owner.
        final VirtualText nearer = eol.get(0);
        assertTrue(onEdtBool(() -> owns(file, BlameText.AUTHORS_OWNER, nearer)), "nearer is the authors' entry");
    }

    @Test
    public void noneOverTheMaxLines() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        openAuthors("A.java");
        rest(1);
        untilAuthors(1);
        file.setProperty(Property.VCS_BLAME_MAX_LINES, 3);
        rest(1);
        until(() -> authorsText(1) == null);
        assertNull(authorsText(1), "too long to blame");
    }

    @Test
    public void noneForAnUntrackedFile() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", JAVA);
        commitAs("Ann", "first");
        git.write("B.java", JAVA);
        openAuthors("B.java");
        // Untracked: the blame reads no history, so no entries.
        rest(1);
        until(() -> {
            final Blame b = file.getBlame();
            return b != null && b.data() != null && b.data().isDone();
        });
        assertNull(authorsText(1), "untracked shows nothing");
    }

    @Test
    public void anAddedMethodGetsAnEntryOnTheNextShow() throws Exception {
        git = GitFixture.init(tmp);
        git.git("config", "user.name", "Kevin Krouse");
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        openAuthors("A.java");
        untilAuthors(6);
        // Add a method after two, not saved: its lines are the user's,
        // so the next show puts an entry on its first line.
        onEdt(() -> {
            h.editor().moveDotTo(file.getLine(10), 0);
            h.editor().insertString("    void three() {\n        f();\n    }\n");
        });
        rest(10);
        assertEquals("Krouse*", untilAuthors(10));
    }

    @Test
    public void theTooltipListsEveryAuthorNewestFirst() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        git.write("A.java", JAVA.replace("        e();", "        e2();"));
        commitAsAt("Bob", "bob's line", 2000000000L);
        openAuthors("A.java");
        rest(6);
        untilAuthors(6);
        final String tip = tip(6);
        // Bob changed later, so Bob is listed before Ann.
        assertTrue(tip.indexOf("Bob") < tip.indexOf("Ann"), tip);
        assertTrue(tip.contains("line"), tip);
    }

    @Test
    public void theClickTurnsTheColumnOnAndMovesTheCaretOrTurnsItOff() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        openAuthors("A.java");
        rest(1);
        untilAuthors(1);
        final VirtualText.Run run = authorsRun(1);
        assertNotNull(run.action(), "the entry is clickable");
        onEdt(() -> {
            Editor.setCurrentEditor(h.editor());
            h.editor().moveDotTo(file.getLine(8), 0);
            run.action().run();
        });
        assertTrue(hasColumn(file), "the column turned on");
        assertEquals(1, h.editor().getDotLine().lineNumber(), "the caret on the method's first line");
        // Again, with the column on: it goes, the caret stays.
        onEdt(() -> {
            h.editor().moveDotTo(file.getLine(8), 0);
            run.action().run();
        });
        assertFalse(hasColumn(file), "the column turned off");
        assertEquals(8, h.editor().getDotLine().lineNumber(), "the caret left where it was");
    }

    @Test
    public void comingBackKeepsTheEntriesUntilTheBlameIsReadAgain() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", JAVA);
        git.write("b.txt", "b\n");
        commitAs("Ann", "all");
        openAuthors("A.java");
        assertEquals("Ann", untilAuthors(6));
        // Away to another buffer: A's blame is let go.
        onEdt(() -> {
            final Buffer b = Editor.getBuffer(File.getInstance(git.root.resolve("b.txt").toString()));
            other = b;
            if (!b.initialized())
                b.initialize();
            b.load();
            Editor.getBufferList().add(b);
            h.showBuffer(b);
            h.editor().moveDotTo(b.getLine(0), 0);
            BlameText.viewChanged(h.editor());
            BlameText.show();
        });
        assertEquals("Ann", authorsText(6), "left on the buffer left");
        // Back: the first rest finds the blame still being read, and the entry stays.
        onEdt(() -> {
            h.showBuffer(file);
            h.editor().moveDotTo(file.getLine(6), 0);
            BlameText.viewChanged(h.editor());
            BlameText.show();
        });
        assertEquals("Ann", authorsText(6), "kept while the blame is read again");
    }

    @Test
    public void aChangedUserNameCountsTheUncommittedLinesForThem() throws Exception {
        git = GitFixture.init(tmp);
        git.git("config", "user.name", "Kevin Krouse");
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        openAuthors("A.java");
        untilAuthors(1);
        // Three of one()'s five lines edited: the user wrote the most.
        editLines(2, 3, 4);
        rest(1);
        until(() -> "Krouse* +1".equals(authorsText(1)));
        git.git("config", "user.name", "Zed Other");
        onEdt(() -> Repositories.forDirectory(git.root).invalidate());
        rest(1);
        until(() -> "Other* +1".equals(authorsText(1)));
    }

    @Test
    public void aMailmapAddedIsFollowedAtTheNextRestWithNoEdit() throws Exception {
        git = GitFixture.init(tmp);
        git.git("config", "user.name", "Kevin Krouse");
        git.git("config", "user.email", "kk@example.com");
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        openAuthors("A.java");
        editLines(2);
        rest(1);
        until(() -> "Ann* +1".equals(authorsText(1)));
        // Ann's address mapped to be the user's: her lines are theirs now.
        git.write(".mailmap", "Kevin Krouse <a@example.com> <kk@example.com>\n");
        rest(1);
        until(() -> "Krouse*".equals(authorsText(1)));
    }

    @Test
    public void theIdentityIsReadMappedAndDroppedWhenTheConfigOrMailmapChanges() throws Exception {
        git = GitFixture.init(tmp);
        final VcsRepository repo = Repositories.forDirectory(git.root);
        final VcsBackend backend = repo.backend();
        assertNull(backend.identity(repo), "unset to start");
        git.git("config", "user.name", "Ann Author");
        git.git("config", "user.email", "ann@example.com");
        repo.invalidate();
        assertEquals(new VcsBackend.Identity("Ann Author", "ann@example.com"), backend.identity(repo));
        git.git("config", "user.name", "Bob Other");
        repo.invalidate();
        assertEquals("Bob Other", backend.identity(repo).name(), "the cache dropped when the config changed");
        // The mailmap names the user as blame will: no invalidate needed.
        git.write(".mailmap", "Robert Other <bob@example.com> <ann@example.com>\n");
        assertEquals(new VcsBackend.Identity("Robert Other", "bob@example.com"), backend.identity(repo));
    }

    @Test
    public void theUsersCommitsUnderAnotherNameAreTheirsByMailAddress() throws Exception {
        git = GitFixture.init(tmp);
        git.git("config", "user.name", "kkrouse");
        git.git("config", "user.email", "k@example.com");
        git.write("A.java", JAVA);
        git.git("add", "-A");
        git.git("commit", "-q", "--author=Kevin Krouse <k@example.com>", "-m", "all");
        openAuthors("A.java");
        editLines(2, 3, 4);
        rest(1);
        // One author, the user: not the user and their commits' name as two.
        assertEquals("kkrouse*", untilAuthors(1));
    }

    @Test
    public void anAuthorsNameIsEscapedInTheTooltip() {
        final Line a = new TextLine("a");
        final Line b = new TextLine("b");
        a.setNext(b);
        b.setPrevious(a);
        a.setLineNumber(0);
        b.setLineNumber(1);
        final BlameData d = new BlameData(List.of(a, b));
        d.lines(
            0,
            2,
            new BlameCommit("1234", "<html><img src=x>", "e@example.com", 0, 0, "s", "f", null, null, false),
            0);
        final String tip = BlameText.authors(d, a, b, null, 0).tooltip();
        assertTrue(tip.contains("<td>&lt;html&gt;&lt;img"), tip);
        assertFalse(tip.contains("<img"), tip);
    }

    @Test
    public void aMethodWithItsBraceOnALineOfItsOwnCountsItsBody() throws Exception {
        final String allman = "class B\n{\n    void one()\n    {\n        a();\n    }\n"
                + "    void two()\n    {\n        b();\n    }\n}\n";
        git = GitFixture.init(tmp);
        git.write("B.java", allman);
        commitAs("Ann", "all");
        git.write("B.java", allman.replace("        b();", "        b2();"));
        commitAs("Bob", "bob's line");
        openAuthors("B.java");
        rest(6);
        // two() from its name to its closing brace: Bob's line in it.
        assertEquals("Ann +1", untilAuthors(6));
    }

    @Test
    public void aClickAfterTheMethodsEndIsDeletedCentersWhatIsLeft() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", JAVA);
        commitAs("Ann", "all");
        openAuthors("A.java");
        untilAuthors(1);
        final VirtualText.Run run = authorsRun(1);
        // Method one's last two lines gone before the entries are built again.
        onEdt(() -> {
            h.editor().deleteRegion(new Position(file.getLine(4), 0), new Position(file.getLine(6), 0));
            h.editor().moveDotTo(file.getLine(0), 0);
            run.action().run();
        });
        assertTrue(hasColumn(file), "the column turned on");
        assertEquals(1, h.editor().getDotLine().lineNumber(), "the caret on the method's first line");
    }

    // --- helpers ---

    // Open with the authors preference on and the line blame off, so a test
    // sees only the authors unless it turns the line blame back on.
    private void openAuthors(String path) throws Exception {
        onEdt(() -> {
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            file = Editor.getBuffer(File.getInstance(git.root.resolve(path).toString()));
            if (!file.initialized())
                file.initialize();
            file.load();
            file.setProperty(Property.VCS_CODE_AUTHORS, true);
            file.setProperty(Property.VCS_LINE_BLAME, false);
            h.showBuffer(file);
        });
    }

    private void rest(int line) throws Exception {
        onEdt(() -> {
            h.editor().moveDotTo(file.getLine(line), 0);
            BlameText.viewChanged(h.editor());
            BlameText.show();
        });
    }

    // Edit whole lines (not saved), as typing would: they become uncommitted.
    private void editLines(int... lines) throws Exception {
        onEdt(() -> {
            for (int n : lines) {
                final Line l = file.getLine(n);
                h.editor().moveDotTo(l, 0);
                h.editor().moveDotTo(l, l.length());
                h.editor().insertString("x");
            }
        });
    }

    private String untilAuthors(int line) throws Exception {
        for (int i = 0; i < 800; i++) {
            // Re-rest each poll: the first show may find the blame not yet
            // streamed, and a later batch's redraw fills it in.
            rest(line);
            final String s = authorsText(line);
            if (s != null)
                return s;
            Thread.sleep(10);
        }
        throw new AssertionError("no authors entry on line " + line);
    }

    private void untilLineBlame(int line) throws Exception {
        for (int i = 0; i < 800; i++) {
            if (lineBlameText(line) != null)
                return;
            Thread.sleep(10);
        }
        throw new AssertionError("no line blame on line " + line);
    }

    private String authorsText(int line) throws Exception {
        final VirtualText.Run r = authorsRunOrNull(line);
        return r == null ? null : r.text();
    }

    private boolean noAuthorsOn(int line) throws Exception {
        return onEdtBool(() -> {
            for (VirtualText v : file.getVirtualText().get(BlameText.AUTHORS_OWNER))
                if (v.line() == file.getLine(line))
                    return false;
            return true;
        });
    }

    // The entry's tool tip as its text reads, its HTML undone.
    private String tip(int line) throws Exception {
        // The table's rows as lines, its cells apart.
        return authorsRun(line).tooltip()
                .replace("</tr>", "\n")
                .replace("</td>", " ")
                .replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private VirtualText.Run authorsRun(int line) throws Exception {
        final VirtualText.Run r = authorsRunOrNull(line);
        assertNotNull(r, "no authors run on line " + line);
        return r;
    }

    private VirtualText.Run authorsRunOrNull(int line) throws Exception {
        final VirtualText.Run[] out = new VirtualText.Run[1];
        onEdt(() -> {
            for (VirtualText v : file.getVirtualText().get(BlameText.AUTHORS_OWNER))
                if (v.line() == file.getLine(line) && !v.rows().isEmpty() && !v.rows().get(0).isEmpty())
                    out[0] = v.rows().get(0).get(v.rows().get(0).size() - 1);
        });
        return out[0];
    }

    private String lineBlameText(int line) throws Exception {
        final VirtualText.Run[] out = new VirtualText.Run[1];
        onEdt(() -> {
            for (VirtualText v : file.getVirtualText().get(BlameText.OWNER))
                if (v.line() == file.getLine(line) && !v.rows().isEmpty() && !v.rows().get(0).isEmpty())
                    out[0] = v.rows().get(0).get(v.rows().get(0).size() - 1);
        });
        return out[0] == null ? null : out[0].text();
    }

    private static boolean owns(Buffer buffer, String owner, VirtualText v) {
        for (VirtualText t : buffer.getVirtualText().get(owner))
            if (t == v)
                return true;
        return false;
    }

    private static boolean hasColumn(Buffer buffer) {
        for (GutterColumn c : buffer.getGutterColumns())
            if (c instanceof BlameColumn)
                return true;
        return false;
    }

    // The line count for a name in a hover, e.g. "Ann 10 lines" -> 10.
    private static int countFor(String tooltip, String name) {
        for (String row : tooltip.split("\n")) {
            final String t = row.strip();
            if (t.startsWith(name + " ")) {
                final String rest = t.substring(name.length() + 1).strip();
                final int sp = rest.indexOf(' ');
                try {
                    return Integer.parseInt(rest.substring(0, sp < 0 ? rest.length() : sp));
                }
                catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private void commitAs(String author, String message) {
        git.git("add", "-A");
        git.git("commit", "-q", "--author=" + author + " <a@example.com>", "-m", message);
    }

    private void commitAsAt(String author, String message, long epochSeconds) {
        git.git("add", "-A");
        git.git(
            "-c",
            "user.name=" + author,
            "commit",
            "-q",
            "--author=" + author + " <a@example.com>",
            "--date=" + epochSeconds + " +0000",
            "-m",
            message);
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static <T> T onEdtList(java.util.concurrent.Callable<T> c) throws Exception {
        final Object[] out = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                out[0] = c.call();
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    private static boolean onEdtBool(java.util.function.BooleanSupplier s) throws Exception {
        final boolean[] out = new boolean[1];
        SwingUtilities.invokeAndWait(() -> out[0] = s.getAsBoolean());
        return out[0];
    }

    private static void until(java.util.concurrent.Callable<Boolean> done) throws Exception {
        for (int i = 0; i < 800; i++) {
            if (done.call())
                return;
            Thread.sleep(10);
        }
        throw new AssertionError("timed out");
    }
}
