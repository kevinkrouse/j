/*
 * ChangeMarksPreviewTest.java
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

package org.armedbear.j.vcs.signs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.ActionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.ChangeMarks;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.File;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.armedbear.j.transients.Transient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The preview and the popup of j's own changes, in a file no version control tracks. */
public class ChangeMarksPreviewTest {
    @TempDir
    Path tmp;

    private EditorHarness h;
    private Buffer buffer;

    @AfterEach
    public void tearDown() throws Exception {
        if (h == null)
            return;
        onEdt(() -> {
            h.editor().closeCaretPopup();
            if (buffer != null)
                Editor.getBufferList().remove(buffer);
            h.showBuffer(h.buffer());
            h.close();
        });
    }

    private void open() throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++)
            sb.append("line ").append(i).append('\n');
        final Path file = tmp.resolve("a.txt");
        Files.writeString(file, sb);
        h = EditorHarness.create();
        Editor.setCurrentEditor(h.editor());
        onEdt(() -> {
            buffer = Editor.getBuffer(File.getInstance(file.toString()));
            if (!buffer.initialized())
                buffer.initialize();
            buffer.load();
            h.showBuffer(buffer);
            h.editor().setDot(buffer.getFirstLine(), 0);
        });
        assertNull(buffer.getSigns(), "no version control here");
    }

    private void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private Line line(int n) {
        return buffer.getLine(n);
    }

    private void change(int n, String text) throws Exception {
        onEdt(() -> {
            final Line l = line(n);
            h.editor().deleteRegion(new Position(l, 0), new Position(l, l.length()));
            h.editor().insertString(text);
        });
    }

    // The preview shows after the command, as the event queue gets to it.
    private void preview() throws Exception {
        onEdt(() -> ChangeMarks.previewChange(h.editor()));
        flush();
    }

    private void flush() throws Exception {
        for (int i = 0; i < 3; i++)
            onEdt(() -> {});
    }

    private void at(int n) throws Exception {
        onEdt(() -> h.editor().setDot(line(n), 0));
    }

    @Test
    public void previewShowsAChangeSinceTheFileWasLoaded() throws Exception {
        open();
        change(4, "line 5!");
        onEdt(() -> {
            h.editor().setDot(line(7), line(7).length());
            h.editor().insertString("\nnew");
        });
        at(4);
        preview();
        assertTrue(HunkPreview.showing(h.editor()));
        assertEquals("[-line 5, +line 5!]", HunkPreview.rows(h.editor()).toString());
        assertEquals("Hunk 1 of 2", HunkPreview.title(h.editor()));
        assertEquals(
            List.of("previousChange", "nextChange", "revertLines"),
            HunkPreview.buttons(h.editor()),
            "no staging without version control");
        at(8);
        preview();
        // The line split for it is marked too, as j marks it.
        assertEquals("+new", HunkPreview.rows(h.editor()).getLast().toString());
        assertEquals("Hunk 2 of 2", HunkPreview.title(h.editor()));
    }

    @Test
    public void previewRunAsACommandOutlivesIt() throws Exception {
        open();
        change(4, "x");
        at(4);
        // As a key or the preview's button runs it: the editor closes its
        // caret popup when a command ends.
        onEdt(
            () -> h.editor()
                    .getDispatcher()
                    .actionPerformed(new ActionEvent(h.editor(), ActionEvent.ACTION_PERFORMED, "previewChange")));
        flush();
        assertTrue(HunkPreview.showing(h.editor()), "status " + h.status());
        assertEquals("[-line 5, +x]", HunkPreview.rows(h.editor()).toString());
    }

    @Test
    public void aJoinedLineShowsTheLinesItWas() throws Exception {
        open();
        onEdt(() -> {
            final Line l = line(2);
            h.editor().deleteRegion(new Position(l, l.length()), new Position(line(3), 0));
        });
        at(2);
        preview();
        assertEquals("[-line 3, -line 4, +line 3line 4]", HunkPreview.rows(h.editor()).toString());
    }

    @Test
    public void noPreviewOnAnUnchangedLine() throws Exception {
        open();
        change(4, "x");
        at(2);
        preview();
        assertFalse(HunkPreview.showing(h.editor()));
        assertEquals("No change here", h.status());
    }

    @Test
    public void nextAndPreviousGoAroundJsOwnChanges() throws Exception {
        open();
        change(2, "x");
        change(3, "y");
        change(7, "z");
        at(0);
        onEdt(() -> ChangeMarks.nextChange(h.editor()));
        assertEquals(2, h.editor().getDotLineNumber());
        onEdt(() -> ChangeMarks.nextChange(h.editor()));
        assertEquals(7, h.editor().getDotLineNumber());
        onEdt(() -> ChangeMarks.nextChange(h.editor()));
        assertEquals(2, h.editor().getDotLineNumber(), "past the last: around to the first");
        onEdt(() -> ChangeMarks.previousChange(h.editor()));
        assertEquals(7, h.editor().getDotLineNumber(), "before the first: around to the last");
    }

    @Test
    public void anOpenPreviewFollowsToTheNextChange() throws Exception {
        open();
        change(2, "x");
        change(7, "z");
        at(2);
        preview();
        onEdt(() -> {
            h.editor().replacingCaretPopup();
            ChangeMarks.nextChange(h.editor());
        });
        flush();
        assertEquals("[-line 8, +z]", HunkPreview.rows(h.editor()).toString());
    }

    @Test
    public void typedBracketCKeepsThePreviewOpenOnTheNextChange() throws Exception {
        open();
        change(2, "x");
        change(7, "z");
        onEdt(() -> {
            h.vim();
            buffer.setProperty(org.armedbear.j.Property.EDIT_MODE, "vim");
        });
        at(2);
        preview();
        onEdt(() -> h.keys("]c"));
        flush();
        assertTrue(HunkPreview.showing(h.editor()));
        assertEquals("[-line 8, +z]", HunkPreview.rows(h.editor()).toString());
        onEdt(() -> h.keys("j"));
        flush();
        assertFalse(HunkPreview.showing(h.editor()), "another command closes it");
    }

    @Test
    public void theRevertButtonPutsTheChangeBack() throws Exception {
        open();
        change(4, "x");
        at(4);
        onEdt(() -> ChangeMarks.revertLines(h.editor()));
        assertEquals("line 5", line(4).getText());
        assertFalse(line(4).isModified());
    }

    @Test
    public void thePopupStagesOnlyWhereTheVersionControlTracks() {
        assertEquals(List.of("revertLines", "nextChange", "previousChange"), commands(ChangeMarks.popup(false)));
        assertEquals(
            List.of(
                "vcsStageHunk",
                "vcsUnstageHunk",
                "vcsPickHunk",
                "vcsChangelistMove",
                "revertLines",
                "nextChange",
                "previousChange"),
            commands(ChangeMarks.popup(true)));
    }

    private static List<String> commands(Transient t) {
        final List<String> out = new ArrayList<>();
        for (Transient.Group g : t.groups())
            for (Transient.Item i : g.items())
                if (i instanceof Transient.Command c)
                    out.add(c.command());
        return out;
    }
}
