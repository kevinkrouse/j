/*
 * SideBySideCommandsTest.java
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

package org.armedbear.j.vcs.sidebyside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.vcs.git.GitFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** vcsDiffSideBySide on files of a repository: the buffer against the index. */
public class SideBySideCommandsTest {
    @TempDir
    Path tmp;

    private GitFixture git;
    private EditorHarness h;
    private Buffer file;

    @AfterEach
    public void tearDown() throws Exception {
        onEdt(() -> {
            final List<Buffer> all = new ArrayList<>();
            for (Buffer b : Editor.getBufferList())
                all.add(b);
            for (Buffer b : all)
                if (b instanceof SideBySideBuffer s)
                    s.close(null);
            if (h != null) {
                h.showBuffer(h.buffer());
                h.close();
            }
            if (file != null)
                Editor.getBufferList().remove(file);
        });
        GitFixture.reset();
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

    // The file at path, its buffer in the harness's window, then the diff of it.
    private SideBySideBuffer diff(String path) throws Exception {
        return diff(path, null);
    }

    // The same, the file read in encoding, or the default for null.
    private SideBySideBuffer diff(String path, String encoding) throws Exception {
        onEdt(() -> {
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            file = Editor.getBuffer(File.getInstance(git.root.resolve(path).toString()));
            if (encoding != null)
                file.getFile().setEncoding(encoding);
            if (!file.initialized())
                file.initialize();
            file.load();
            h.showBuffer(file);
            SideBySideCommands.vcsDiffSideBySide(h.editor());
        });
        final SideBySideBuffer[] found = new SideBySideBuffer[1];
        until(() -> {
            for (Buffer b : Editor.getBufferList())
                if (b instanceof SideBySideBuffer s)
                    found[0] = s;
            return found[0] != null;
        });
        return found[0];
    }

    private static List<String> text(Buffer b) {
        final List<String> out = new ArrayList<>();
        for (Line l = b.getFirstLine(); l != null; l = l.next())
            out.add(l.getText());
        return out;
    }

    @Test
    public void anUnchangedFileWithAFinalNewlineHasNoChange() throws Exception {
        git = GitFixture.init(tmp);
        git.write("A.java", "class A {\n}\n");
        git.commitAll("first");
        final SideBySideBuffer d = diff("A.java");
        onEdt(() -> {
            assertEquals(List.of(), d.getAlignment().changes(), "no blank line added at the end");
            assertEquals(List.of("class A {", "}"), text(d));
            assertSame(file.getMode(), d.getMode(), "the buffer's mode");
        });
    }

    @Test
    public void anEditIsAChangeAndAFileNoModeClaimsIsShownAllTheSame() throws Exception {
        git = GitFixture.init(tmp);
        git.write("README", "one\ntwo\n");
        git.commitAll("first");
        git.write("README", "one\n2\n");
        final SideBySideBuffer d = diff("README");
        onEdt(() -> {
            assertNotNull(d.getMode());
            assertEquals(1, d.getAlignment().changes().size());
            assertEquals(List.of("one", "two"), text(d.getOld()));
            assertEquals(List.of("one", "2"), text(d));
        });
    }

    @Test
    public void theStagedSideIsReadInTheFilesEncoding() throws Exception {
        git = GitFixture.init(tmp);
        final Path a = git.root.resolve("a.txt");
        Files.write(a, "café\nb\n".getBytes(StandardCharsets.ISO_8859_1));
        git.commitAll("first");
        final SideBySideBuffer d = diff("a.txt", "ISO-8859-1");
        onEdt(() -> {
            assertEquals("café", d.getOld().getFirstLine().getText());
            assertTrue(d.getAlignment().changes().isEmpty(), "no line changed by its encoding");
        });
    }

    @Test
    public void aFileNotInTheIndexIsAllAdded() throws Exception {
        git = GitFixture.init(tmp);
        git.write("x.txt", "x\n");
        git.commitAll("first");
        git.write("new.txt", "a\nb\n");
        final SideBySideBuffer d = diff("new.txt");
        onEdt(() -> {
            assertTrue(d.getOld().getFileNameForDisplay().contains("not in the index"));
            assertEquals(1, d.getAlignment().changes().size());
            assertEquals(d.getDisplayHeight(), d.getOld().getDisplayHeight());
        });
    }
}
