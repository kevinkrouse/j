/*
 * CheckinBuffer.java
 *
 * Copyright (C) 1998-2003 Peter Graves
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

package org.armedbear.j.mode.checkin;

import javax.swing.undo.CompoundEdit;
import org.armedbear.j.Buffer;
import org.armedbear.j.CommentRing;
import org.armedbear.j.Constants;
import org.armedbear.j.Debug;
import org.armedbear.j.Editor;
import org.armedbear.j.Expansion;
import org.armedbear.j.Log;
import org.armedbear.j.Position;
import org.armedbear.j.mode.diff.DiffOutputBuffer;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsBackends;
import org.armedbear.j.vcs.VersionControlBuffer;

public class CheckinBuffer extends VersionControlBuffer implements Constants {
    private final boolean editOnly;

    private int commentIndex = -1;

    public CheckinBuffer(Buffer parentBuffer, int vcType) {
        this(parentBuffer, vcType, false);
    }

    public CheckinBuffer(Buffer parentBuffer, int vcType, boolean editOnly) {
        super(parentBuffer, null, vcType);
        this.editOnly = editOnly;
    }

    protected void init() {
        initializeUndo();
        type = TYPE_NORMAL;
        isUntitled = true;
        mode = CheckinMode.getMode();
        if (!withWriteLock(() -> {
            appendLine("");
            renumber();
        }))
            return;
        setLoaded(true);
        setInitialized(true);
    }

    public final boolean isEditOnly() {
        return editOnly;
    }

    public String getFileNameForDisplay() {
        return title != null ? title : "";
    }

    public static void previousComment() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof CheckinBuffer)
            ((CheckinBuffer) buffer).retrieveComment(editor, -1);
    }

    public static void nextComment() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof CheckinBuffer)
            ((CheckinBuffer) buffer).retrieveComment(editor, +1);
    }

    private void retrieveComment(Editor editor, int arg) {
        final CommentRing commentRing = CommentRing.getInstance();
        if (commentIndex < 0)
            commentIndex = commentRing.size();
        int index = commentIndex + arg;
        if (index > commentRing.size() - 1) {
            // Wrap.
            index = 0;
        } else if (index < 0) {
            // Wrap.
            index = commentRing.size() - 1;
        }
        final String comment = commentRing.get(index);
        if (comment == null) {
            // Comment ring is empty.
            return;
        }
        commentIndex = index;
        VcsBackend backend = VcsBackends.get(getVCType());
        if (backend != null)
            backend.replaceComment(editor, comment);
        else
            Debug.bug();
    }

    public static void finish() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof CheckinBuffer) {
            CheckinBuffer cb = (CheckinBuffer) buffer;
            VcsBackend backend = VcsBackends.get(cb.getVCType());
            if (backend != null) {
                CommentRing.getInstance().appendNew(backend.extractComment(cb));
                backend.finish(editor, cb);
            }
        }
    }

    /** Replaces the editor's checkin buffer's text with comment, leaving the caret at the end. */
    public static void replaceText(Editor editor, String comment) {
        if (!(editor.getBuffer() instanceof CheckinBuffer)) {
            Debug.bug();
            return;
        }
        final CheckinBuffer buffer = (CheckinBuffer) editor.getBuffer();
        if (buffer.getText().equals(comment))
            return;
        if (!buffer.withWriteLock(() -> {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.selectAll();
            editor.deleteRegion();
            editor.insertString(comment);
            editor.endCompoundEdit(compoundEdit);
            buffer.modified();
        }))
            return;
        final Position end = buffer.getEnd();
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() == buffer) {
                ed.setTopLine(buffer.getFirstLine());
                ed.setDot(end.copy()); // No undo.
                ed.moveCaretToDotCol();
                ed.setUpdateFlag(REPAINT);
                ed.updateDisplay();
            }
        }
    }

    public Expansion getExpansion(Position dot) {
        Expansion e =
            new Expansion(dot, Editor.getModeList().getMode(PLAIN_TEXT_MODE));
        if (parentBuffer != null && e.getPrefix() != null) {
            // Look for diff output buffer for same parent buffer.
            for (Buffer b : Editor.getBufferList()) {
                if (b instanceof DiffOutputBuffer) {
                    if (((DiffOutputBuffer) b).getParentBuffer() == parentBuffer) {
                        // Add candidates from diff output buffer.
                        Expansion d =
                            new Expansion(b, e.getPrefix(), e.getCurrent());
                        e.appendCandidates(d.getCandidates());
                        break; // There should be one diff output buffer at most.
                    }
                }
            }
            // Add candidates from parent buffer.
            Expansion p =
                new Expansion(parentBuffer, e.getPrefix(), e.getCurrent());
            e.appendCandidates(p.getCandidates());
        }
        return e;
    }
}
