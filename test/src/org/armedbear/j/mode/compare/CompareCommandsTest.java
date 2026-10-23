/*
 * CompareCommandsTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** diff [FILE]: the buffer against another file. */
public class CompareCommandsTest {
    @TempDir
    Path tmp;

    private EditorHarness h;
    // A window showing the comparison, which follows its buffer only while shown.
    private EditorHarness shown;
    private Buffer file;
    // Open before the test's: another test's, not to be taken for its.
    private final List<CompareBuffer> before = new ArrayList<>();

    @AfterEach
    public void tearDown() throws Exception {
        onEdt(() -> {
            if (shown != null) {
                shown.showBuffer(shown.buffer());
                shown.close();
            }
            for (CompareBuffer c : comparisons())
                if (!before.contains(c))
                    c.close(null);
            if (h != null) {
                h.showBuffer(h.buffer());
                h.close();
            }
            if (file != null)
                Editor.getBufferList().remove(file);
        });
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static List<CompareBuffer> comparisons() {
        final List<CompareBuffer> out = new ArrayList<>();
        for (Buffer b : Editor.getBufferList())
            if (b instanceof CompareBuffer c)
                out.add(c);
        return out;
    }

    private CompareBuffer opened() throws Exception {
        for (int i = 0; i < 200; i++) {
            final List<CompareBuffer> found = new ArrayList<>();
            onEdt(() -> found.addAll(comparisons()));
            found.removeAll(before);
            if (!found.isEmpty())
                return found.get(0);
            Thread.sleep(25);
        }
        throw new AssertionError("no comparison");
    }

    private static void until(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 200; i++) {
            final boolean[] ok = new boolean[1];
            onEdt(() -> ok[0] = done.getAsBoolean());
            if (ok[0])
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out");
    }

    private static List<String> text(Buffer b) {
        final List<String> out = new ArrayList<>();
        for (Line l = b.getFirstLine(); l != null; l = l.next())
            out.add(l.getText());
        return out;
    }

    // B.txt, edited, in the harness's window.
    private void edit(String text) throws Exception {
        edit(text.getBytes(StandardCharsets.UTF_8));
    }

    private void edit(byte[] bytes) throws Exception {
        Files.write(tmp.resolve("B.txt"), bytes);
        onEdt(() -> {
            before.addAll(comparisons());
            h = EditorHarness.create();
            Editor.setCurrentEditor(h.editor());
            file = Editor.getBuffer(File.getInstance(tmp.resolve("B.txt").toString()));
            if (!file.initialized())
                file.initialize();
            file.load();
            h.showBuffer(file);
        });
    }

    @Test
    public void diffWithAFileHasItAtTheLeftAndFollowsTheBuffer() throws Exception {
        Files.writeString(tmp.resolve("A.txt"), "a\nb\n");
        edit("a\nB\n");
        onEdt(() -> CompareCommands.diff(h.editor(), "A.txt"));
        final CompareBuffer d = opened();
        onEdt(() -> {
            assertEquals(List.of("a", "b"), text(d.getOld()));
            assertEquals(List.of("a", "B"), text(d));
            shown = EditorHarness.create();
            shown.showBuffer(d);
            Editor.setCurrentEditor(h.editor());
            h.editor().setDot(file.getFirstLine(), 0);
            h.editor().moveCaretToDotCol();
        });
        h.keys("X");
        until(() -> text(d).get(0).equals("Xa"));
    }

    @Test
    public void diffAloneIsWithThePatchModeBackup() throws Exception {
        Files.writeString(tmp.resolve("B.txt.orig"), "old\n");
        edit("new\n");
        onEdt(() -> {
            file.setProperty(Property.PATCH_MODE, ".orig");
            CompareCommands.diff(h.editor(), null);
        });
        final CompareBuffer d = opened();
        onEdt(() -> assertEquals(List.of("old"), text(d.getOld())));
    }

    @Test
    public void aFileThatIsNotThereOpensNothing() throws Exception {
        edit("a\n");
        onEdt(() -> {
            CompareCommands.diff(h.editor(), "missing.txt");
            assertEquals(before, comparisons());
        });
    }

    @Test
    public void aBinaryFileOpensNothing() throws Exception {
        Files.write(tmp.resolve("A.bin"), new byte[] { 'a', 0, 'b', '\n' });
        edit("a\n");
        onEdt(() -> {
            CompareCommands.diff(h.editor(), "A.bin");
            assertEquals(before, comparisons());
        });
    }

    @Test
    public void theFileIsReadAgainOnceItChangesOnDisk() throws Exception {
        final Path a = tmp.resolve("A.txt");
        Files.writeString(a, "a\n");
        edit("a\n");
        onEdt(() -> CompareCommands.diff(h.editor(), "A.txt"));
        final CompareBuffer d = opened();
        onEdt(() -> {
            shown = EditorHarness.create();
            shown.showBuffer(d);
        });
        Files.writeString(a, "changed\n");
        Files.setLastModifiedTime(a, FileTime.fromMillis(Files.getLastModifiedTime(a).toMillis() + 5000));
        // Shown again.
        onEdt(() -> {
            shown.showBuffer(file);
            shown.showBufferRestored(d);
        });
        until(() -> text(d.getOld()).equals(List.of("changed")));
    }

    @Test
    public void theBufferIsComparedInTheEncodingItWasReadIn() throws Exception {
        edit(new byte[] { (byte) 0xe9, '\n' });
        onEdt(() -> assertEquals(StandardCharsets.ISO_8859_1, file.getCharset()));
    }

    private static List<String> lines(byte[] bytes, Charset charset) {
        return Side.ofFile(bytes, charset).lines();
    }

    @Test
    public void aFilesByteOrderMarkIsNotItsText() {
        final byte[] utf8 = { (byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', '\n' };
        assertEquals(List.of("a"), lines(utf8, StandardCharsets.ISO_8859_1));
        assertEquals(
            List.of("\u00e9", "b"),
            lines("\u00e9\nb\n".getBytes(StandardCharsets.UTF_16), StandardCharsets.UTF_8));
        final byte[] le = "\u00e9\r\n".getBytes(StandardCharsets.UTF_16LE);
        final byte[] utf16le = new byte[le.length + 2];
        utf16le[0] = (byte) 0xff;
        utf16le[1] = (byte) 0xfe;
        System.arraycopy(le, 0, utf16le, 2, le.length);
        assertEquals(List.of("\u00e9"), lines(utf16le, StandardCharsets.UTF_8));
    }

    @Test
    public void aFileIsInUtf8IfItsBytesAreElseInTheBuffersEncoding() {
        final Charset cp1252 = Charset.forName("windows-1252");
        assertEquals(List.of("\u00e9"), lines("\u00e9\n".getBytes(StandardCharsets.UTF_8), cp1252));
        assertEquals(List.of("\u20ac"), lines(new byte[] { (byte) 0x80, '\n' }, cp1252));
        assertEquals(List.of("\u00e9"), lines(new byte[] { (byte) 0xe9, '\n' }, StandardCharsets.UTF_8));
    }
}
