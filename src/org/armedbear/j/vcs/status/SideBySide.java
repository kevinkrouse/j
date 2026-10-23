/*
 * SideBySide.java
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

import static org.armedbear.j.Constants.PLAIN_TEXT_MODE;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Frame;
import org.armedbear.j.FrameView;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Mode;
import org.armedbear.j.Property;
import org.armedbear.j.mode.compare.CompareBuffer;
import org.armedbear.j.mode.compare.DiffAlignment;
import org.armedbear.j.mode.compare.Side;

/**
 * A diff pane's side-by-side form, as the status's and the log's have it: a
 * comparison, part of the pane's owner's view, showing a file, by key, as
 * the pane shows it, from the line the pane's caret was on, and back.
 */
public final class SideBySide {
    private final CompareBuffer comparison;
    // Each file asked for's number, so that only the last asked for is
    // shown; the file asked for, and the file shown.
    private int asked;
    private String file;
    private String shown;
    // Where it goes when it first shows placeFile; a negative line its first change.
    private String placeFile;
    private boolean placeOnOld;
    private int placeLine = -1;

    /** Empty until a file is shown, a part of owner, keys mapped over its sides'. */
    public SideBySide(Buffer owner, Consumer<KeyMap> keys) {
        comparison = CompareBuffer.open(null, plainText(), null, "", Side.NONE, "", Side.NONE);
        comparison.embed(owner, keys);
    }

    public CompareBuffer comparison() {
        return comparison;
    }

    /** Plain text, for nothing or a file of no mode. */
    public static Mode plainText() {
        return Editor.getModeList().getMode(PLAIN_TEXT_MODE);
    }

    /** The mode of a file named name, or plain text. */
    public static Mode modeFor(String name) {
        final Mode mode = Editor.getModeList().getModeForFileName(name);
        return mode != null ? mode : plainText();
    }

    /** The default encoding's charset, a text read from git or a file with no buffer read in it. */
    public static Charset charset() {
        try {
            return Charset.forName(Editor.preferences().getStringProperty(Property.DEFAULT_ENCODING));
        }
        catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** Whether file is the one last asked for. */
    public boolean isAsked(String file) {
        return file != null && file.equals(this.file);
    }

    /** Asks for file, null for none: the number of the asking, to show it with. */
    public int ask(String file) {
        this.file = file;
        return ++asked;
    }

    /** Whether n is the last asking, and the comparison is still open. */
    public boolean isCurrent(int n) {
        return n == asked && !comparison.isClosed();
    }

    /** Forgets the file asked for, so that asking for it again reads it again. */
    public void forget() {
        file = null;
    }

    /**
     * The two sides, alignment theirs, or worked out if null, for asking n:
     * of a file it didn't show, at the place asked for it, else from its
     * first change.
     */
    public void show(
            int n,
            Mode mode,
            String oldTitle,
            Side oldSide,
            String newTitle,
            Side newSide,
            DiffAlignment alignment) {
        if (!isCurrent(n))
            return;
        comparison.set(
            mode,
            oldTitle,
            oldSide,
            newTitle,
            newSide,
            alignment != null ? alignment : DiffAlignment.of(oldSide.keys(), newSide.keys()));
        final boolean moved = file == null || !file.equals(shown);
        shown = file;
        if (!moved)
            return;
        final boolean placed = file != null && file.equals(placeFile);
        comparison.place(placed && placeOnOld ? comparison.getOld() : comparison, placed ? placeLine : -1);
        placeFile = null;
    }

    /** Whether it shows file, read. */
    public boolean shows(String file) {
        return file != null && file.equals(shown);
    }

    /**
     * Takes pane's place in view across frame, if it is laid out there,
     * focused if pane was, and once it shows file, pane's at the line pane's
     * caret is on.
     */
    public void replace(Frame frame, FrameView view, Buffer focus, DiffPane<?> pane, String file) {
        placeFrom(pane, file);
        if (frame != null)
            frame.layOutView(view, focus == pane ? placedSide() : focus);
    }

    /**
     * Gives pane its place back in view across frame, as replace took it,
     * and closes: what puts pane's caret at the line the comparison's is
     * on, to run once pane shows file.
     */
    public Runnable restore(Frame frame, FrameView view, Buffer focus, DiffPane<?> pane, String file) {
        final Runnable place = placer(focus, pane, file);
        if (frame != null)
            frame.layOutView(view, CompareBuffer.of(focus) == comparison ? pane : focus);
        close();
        return place;
    }

    /**
     * The new side's line, from 1, at the caret of editor, in a side, to
     * visit file at; -1, saying why, for none or before it is read.
     */
    public int lineAt(Editor editor, String file) {
        if (file == null) {
            editor.status("Nothing to visit here");
            return -1;
        }
        if (!shows(file)) {
            editor.status("Not read yet");
            return -1;
        }
        return comparison.newLine(editor.getBuffer(), editor.getDotLineNumber()) + 1;
    }

    // Where it is to go once it shows file, as pane does: the line pane's
    // caret is on, a removed line's on the old side; a hunk's first change.
    private void placeFrom(DiffPane<?> pane, String file) {
        placeFile = null;
        placeOnOld = false;
        placeLine = -1;
        final Editor ed = Editor.windowShowing(pane);
        if (ed == null || ed.getDot() == null || !(ed.getDotLine() instanceof StatusLine at) || at.hunk() == null)
            return;
        if (at.diffLine() != null) {
            placeOnOld = at.diffLine().isRemoved();
            placeLine = (placeOnOld ? at.oldLine() : at.newLine()) - 1;
        } else
            placeLine = VcsStatusMode.firstChange(at.hunk()) - 1;
        placeFile = file;
    }

    // The side it is to go to, as placeFrom found it.
    private Buffer placedSide() {
        return placeOnOld ? comparison.getOld() : comparison;
    }

    // What puts pane's caret at the line its caret is on, focus's side's or
    // else the new side's, read now, before a layout gives their windows to
    // other buffers; nothing while it shows another file than pane's.
    private Runnable placer(Buffer focus, DiffPane<?> pane, String file) {
        final Editor from = Editor.windowShowing(CompareBuffer.of(focus) == comparison ? focus : comparison);
        if (from == null || from.getDot() == null || !shows(file))
            return () -> {};
        final boolean onOld = from.getBuffer() == comparison.getOld();
        final int line = from.getDotLineNumber();
        return () -> pane.placeAt(onOld, line + 1);
    }

    /** Closes the comparison. */
    public void close() {
        comparison.close(null);
    }
}
