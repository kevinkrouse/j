/*
 * DirectoryBufferTest.java
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

package org.armedbear.j.mode.dir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.JumpList;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegment;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.Platform;
import org.armedbear.j.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Making files and directories from a listing. */
public class DirectoryBufferTest {
    @TempDir
    Path tmp;

    private EditorHarness h;
    private DirectoryBuffer dir;

    @BeforeEach
    public void setUp() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "a\n");
        h = EditorHarness.create();
        onEdt(() -> {
            Editor.setCurrentEditor(h.editor());
            dir = new DirectoryBuffer(File.getInstance(tmp.toString()));
            dir.load();
            h.showBuffer(dir);
        });
    }

    @AfterEach
    public void tearDown() throws Exception {
        onEdt(() -> {
            final Buffer shown = h.editor().getBuffer();
            h.showBuffer(h.buffer());
            if (shown != dir && shown != h.buffer())
                Editor.getBufferList().remove(shown);
            Editor.getBufferList().remove(dir);
            h.close();
        });
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private void command(String name, String argument) throws Exception {
        onEdt(() -> {
            try {
                h.editor().execute(name, argument);
            }
            catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        });
    }

    // The name on the caret's line.
    private String caretName() {
        final String text = h.editor().getDotLine().getText();
        return text.substring(text.lastIndexOf(' ') + 1);
    }

    @Test
    public void aNewFileIsABufferWrittenWhenSaved() throws Exception {
        command("dirNewFile", "b.txt");
        assertEquals(tmp.resolve("b.txt").toString(), h.editor().getBuffer().getFile().canonicalPath());
        assertFalse(Files.exists(tmp.resolve("b.txt")), "nothing on disk until it is saved");
    }

    @Test
    public void aNewFileInDirectoriesNotThereMakesThemSoItCanBeSaved() throws Exception {
        command("dirNewFile", "src/deep/c.txt");
        assertTrue(Files.isDirectory(tmp.resolve("src/deep")));
        assertFalse(Files.exists(tmp.resolve("src/deep/c.txt")));
        assertEquals(tmp.resolve("src/deep/c.txt").toString(), h.editor().getBuffer().getFile().canonicalPath());
        final String text = dir.getLastView().getDot().getLine().getText();
        assertTrue(text.endsWith(" src"), "the listing's caret on the directory made: " + text);
    }

    @Test
    public void aFileThereAlreadyIsOpened() throws Exception {
        command("dirNewFile", "a.txt");
        assertEquals(tmp.resolve("a.txt").toString(), h.editor().getBuffer().getFile().canonicalPath());
        assertEquals("a", h.editor().getBuffer().getFirstLine().getText());
    }

    @Test
    public void aNewDirectoryLeavesTheCaretOnIt() throws Exception {
        command("dirNewDirectory", "sub/deeper");
        assertTrue(Files.isDirectory(tmp.resolve("sub/deeper")));
        assertSame(dir, h.editor().getBuffer());
        assertEquals("sub", caretName(), "on the directory it is in, here");
    }

    @Test
    public void aNewFileWithNoExtensionIsOpened() throws Exception {
        // Its type is read from its bytes, and it has none.
        command("dirNewFile", "notes");
        assertEquals(tmp.resolve("notes").toString(), h.editor().getBuffer().getFile().canonicalPath());
    }

    @Test
    public void aQuotedNameIsOpenedWithoutItsQuotes() throws Exception {
        command("dirNewFile", "\"a b\"");
        assertEquals(tmp.resolve("a b").toString(), h.editor().getBuffer().getFile().canonicalPath());
    }

    @Test
    public void makingOneKeepsTheTags() throws Exception {
        onEdt(() -> {
            h.editor().setDot(dir.findName("a.txt"), 0);
            dir.tagFileAtDot();
        });
        command("dirNewDirectory", "archive");
        onEdt(
            () -> assertTrue(
                ((DirectoryLine) dir.findName("a.txt")).getDirectoryEntry().isMarked(),
                "a.txt still tagged"));
    }

    @Test
    public void jumpingBackFromAFileOpenedInASubdirectoryGoesBackToItsListing() throws Exception {
        Files.createDirectory(tmp.resolve("sub"));
        Files.writeString(tmp.resolve("sub/c.txt"), "c\n");
        onEdt(() -> {
            JumpList.clear();
            // A jump in the listing, in the directory it starts in.
            h.editor().recordJump();
            dir.changeDirectory(File.getInstance(tmp.resolve("sub").toString()));
            h.editor().setDot(dir.findName("c.txt"), 0);
            DirectoryBuffer.dirOpenFile();
        });
        assertEquals(tmp.resolve("sub/c.txt").toString(), h.editor().getBuffer().getFile().canonicalPath());
        onEdt(JumpList::jumpBack);
        assertSame(dir, h.editor().getBuffer());
        assertEquals(tmp.resolve("sub").toString(), dir.getFile().canonicalPath());
        assertEquals("c.txt", caretName());
        onEdt(JumpList::jumpBack);
        assertSame(dir, h.editor().getBuffer(), "the same listing, back in the directory it started in");
        assertEquals(tmp.toString(), dir.getFile().canonicalPath());
        assertTrue(dir.findName("a.txt") != null, "listed, not empty");
    }

    // The kind of hidden name on its line, or null if it isn't listed.
    private Integer hiddenKind(String name) {
        final Line line = dir.findName(name);
        return line == null ? null : ((DirectoryLine) line).getDirectoryEntry().getHidden();
    }

    @Test
    public void dotfilesAndExcludedNamesAreHiddenUntilShown() throws Exception {
        // No repository here: only the names themselves hide them.
        Files.writeString(tmp.resolve(".dot"), "");
        Files.writeString(tmp.resolve("notes.bak"), "");
        onEdt(dir::rescan);
        assertNull(hiddenKind(".dot"));
        assertNull(hiddenKind("notes.bak"));
        assertEquals(HiddenNames.SHOWN, hiddenKind("a.txt"));
        onEdt(() -> dir.setShowHidden(true));
        assertEquals(HiddenNames.HIDDEN, hiddenKind(".dot"));
        assertEquals(HiddenNames.HIDDEN, hiddenKind("notes.bak"), "filenameCompletionsExcludePattern");
        assertEquals(HiddenNames.SHOWN, hiddenKind(".."));
    }

    @Test
    public void whatGitIgnoresIsHiddenAsIgnored() throws Exception {
        final Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".gitignore"), "*.log\nout/\n");
        Files.writeString(repo.resolve("run.log"), "");
        Files.writeString(repo.resolve("Main.java"), "");
        Files.createDirectory(repo.resolve("out"));
        onEdt(() -> dir.changeDirectory(File.getInstance(repo.toString())));
        assertNull(hiddenKind("run.log"));
        assertNull(hiddenKind("out"));
        onEdt(() -> dir.setShowHidden(true));
        assertEquals(HiddenNames.IGNORED, hiddenKind("run.log"));
        assertEquals(HiddenNames.IGNORED, hiddenKind("out"), "a directory rule");
        assertEquals(HiddenNames.HIDDEN, hiddenKind(".gitignore"));
        assertEquals(HiddenNames.SHOWN, hiddenKind("Main.java"));
    }

    // The names listed, in order, past . and ..
    private List<String> names() {
        final List<String> names = new ArrayList<>();
        for (Line l = dir.getFirstLine(); l != null; l = l.next())
            if (l instanceof DirectoryLine dl) {
                final String n = dl.getDirectoryEntry().getName() != null
                        ? dl.getDirectoryEntry().getName()
                        : dl.getDirectoryEntry().extractName();
                if (!n.equals(".") && !n.equals(".."))
                    names.add(n);
            }
        return names;
    }

    private String lineOf(String name) {
        return dir.findName(name).getText();
    }

    // The listing in each format: j's own, then ls's where there is one.
    private void inEachFormat(Runnable check) throws Exception {
        final boolean unix = Platform.isPlatformUnix();
        for (boolean nativeFormat : unix ? new boolean[] { false, true } : new boolean[] { false }) {
            Editor.preferences().setProperty(Property.DIR_USE_NATIVE_FORMAT, String.valueOf(nativeFormat));
            try {
                onEdt(() -> {
                    dir.setDirectoriesFirst(true);
                    dir.rescan();
                    check.run();
                });
            }
            finally {
                Editor.preferences().removeProperty(Property.DIR_USE_NATIVE_FORMAT.key());
            }
        }
    }

    @Test
    public void reverseKeepsDotsOnTopAndDirectoriesFirstWhateverTheSort() throws Exception {
        Files.writeString(tmp.resolve("b.txt"), "bb\n");
        Files.createDirectory(tmp.resolve("zdir"));
        inEachFormat(() -> {
            assertEquals(List.of("zdir", "a.txt", "b.txt"), names());
            dir.setReverse(true);
            assertEquals(List.of("zdir", "b.txt", "a.txt"), names());
            assertEquals(".", dir.getFirstLine().getText().substring(dir.getFirstLine().getText().length() - 1));
            dir.setDirectoriesFirst(false);
            assertEquals(List.of("zdir", "b.txt", "a.txt"), names(), "z after b, backward");
            dir.setReverse(false);
            assertEquals(List.of("a.txt", "b.txt", "zdir"), names());
        });
    }

    @Test
    public void humanSizesAndTimeStylesChangeTheColumnsNotTheNames() throws Exception {
        Files.writeString(tmp.resolve("big.bin"), "x".repeat(3000));
        inEachFormat(() -> {
            dir.setHumanSizes(true);
            assertTrue(lineOf("big.bin").contains(" 3.0K "), lineOf("big.bin"));
            dir.setTimeStyle("long-iso");
            assertTrue(lineOf("big.bin").matches(".* \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} big\\.bin"), lineOf("big.bin"));
            assertTrue(lineOf("big.bin").substring(2).startsWith("-rw"), "the mode column");
            dir.setHumanSizes(false);
            dir.setTimeStyle("locale");
            assertTrue(lineOf("big.bin").contains(" 3000 "), lineOf("big.bin"));
        });
    }

    // Each segment's text, with its format's name.
    private String segments(String name) {
        final LineSegmentList list = dir.getFormatter().formatLine(dir.findName(name));
        // DirectoryFormatter's formats, in order.
        final String[] formats = {
            "text",
            "directory",
            "symlink",
            "marked",
            "hidden",
            "ignored",
            "permissionRead",
            "permissionWrite",
            "permissionExecute",
            "permissionNone",
            "size",
            "date" };
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            final LineSegment seg = list.getSegment(i);
            sb.append('[').append(seg.getText()).append('|').append(formats[seg.getFormat()]).append(']');
        }
        return sb.toString();
    }

    @Test
    public void theModeSizeAndDateEachHaveTheirColors() throws Exception {
        inEachFormat(() -> {
            dir.setTimeStyle("long-iso");
            final String s = segments("a.txt");
            assertTrue(s.contains("[r|permissionRead][w|permissionWrite]"), s);
            assertTrue(s.contains("|size]"), s);
            assertTrue(s.matches(".*\\[ \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} \\|date\\]\\[a\\.txt\\|text\\].*"), s);
        });
    }

    @Test
    public void inTheAgeStyleNamesSizesAndColorsAreStillFound() throws Exception {
        final Path old = Files.writeString(tmp.resolve("old file.txt"), "x".repeat(300));
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 3L * 3600 * 1000));
        inEachFormat(() -> {
            dir.setTimeStyle("age");
            final String line = lineOf("old file.txt");
            assertTrue(line.matches(".* 300 +3h old file\\.txt"), line);
            assertTrue(segments("old file.txt").contains("[   3h |date][old file.txt|text]"), segments("old file.txt"));
            assertEquals("old file.txt", names().get(names().indexOf("old file.txt")));
            assertNotNull(dir.findName("a.txt"), "found by its entry");
            dir.setTimeStyle("locale");
        });
    }

    @Test
    public void theTotalIsTheFilesBytesNotTheDirectories() throws Exception {
        Files.writeString(tmp.resolve("b.txt"), "bbbb");
        Files.createDirectory(tmp.resolve("sub"));
        inEachFormat(() -> {
            Line last = dir.getFirstLine();
            while (last.next() != null)
                last = last.next();
            assertEquals("6", last.getText().strip(), "a.txt's 2 and b.txt's 4");
        });
    }

    @Test
    public void reorderingKeepsTheCaretOnItsName() throws Exception {
        Files.writeString(tmp.resolve("b.txt"), "b");
        inEachFormat(() -> {
            h.editor().setDot(dir.findName("a.txt"), 0);
            dir.setReverse(true);
            assertEquals("a.txt", caretName());
            dir.setReverse(false);
        });
    }

    @Test
    public void aAndAMakeAFileAndADirectoryAndNAndNAreVimsSearchAgain() {
        final DirectoryMode mode = DirectoryMode.getMode();
        for (KeyMap km : List.of(mode.getKeyMap(), mode.getModalKeyMap())) {
            assertEquals("dirNewFile", km.lookup('a', 0, 0).getCommand());
            assertEquals("dirNewDirectory", km.lookup('A', 0, 0).getCommand());
        }
        assertNull(mode.getModalKeyMap().lookup('n', 0, 0));
        assertNull(mode.getModalKeyMap().lookup('N', 0, 0));
    }

    @Test
    public void jumpingBackToAFileNeverSavedMakesItsBufferAgain() throws Exception {
        final File unsaved = File.getInstance(tmp.resolve("new.txt").toString());
        onEdt(() -> {
            JumpList.clear();
            final Buffer b = Buffer.createBuffer(unsaved);
            h.showBuffer(b);
            h.editor().recordJump();
            h.showBuffer(dir);
            Editor.getBufferList().remove(b);
            JumpList.jumpBack();
        });
        assertEquals(unsaved, h.editor().getBuffer().getFile());
    }

    @Test
    public void aNameThatExistsIsLeftAlone() throws Exception {
        onEdt(() -> assertEquals("a.txt exists", dir.makeDirectory("a.txt")));
        assertEquals("a\n", Files.readString(tmp.resolve("a.txt")));
        onEdt(() -> assertNull(dir.makeDirectory("c")));
        onEdt(() -> assertEquals("c exists", dir.makeDirectory("c")));
        onEdt(() -> assertEquals("c is a directory", dir.prepareFile("c")));
    }
}
