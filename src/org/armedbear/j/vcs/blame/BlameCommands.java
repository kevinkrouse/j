/*
 * BlameCommands.java
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

package org.armedbear.j.vcs.blame;

import java.nio.charset.Charset;
import javax.swing.JPopupMenu;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.Line;
import org.armedbear.j.Menu;
import org.armedbear.j.Mode;
import org.armedbear.j.mode.compare.CompareBuffer;
import org.armedbear.j.mode.compare.DiffAlignment;
import org.armedbear.j.mode.compare.DiffWhitespace;
import org.armedbear.j.mode.compare.Side;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.log.LogCommands;
import org.armedbear.j.vcs.log.RevisionBuffer;
import org.armedbear.j.vcs.status.SideBySide;
import org.armedbear.j.vcs.status.VcsStatusMode;

/** The blame column, on and off, and what it offers on the caret line's commit. */
public final class BlameCommands {
    private BlameCommands() {}

    /** Show or Hide Blame in editor's context menu, for a buffer that can be blamed. */
    public static void addContextMenuItems(Editor editor, JPopupMenu popup) {
        final Buffer buffer = editor.getBuffer();
        if (!Blame.canBlame(buffer))
            return;
        popup.addSeparator();
        AbstractMode.addContextMenuItem(
            column(buffer) != null ? "Hide Blame" : "Show Blame",
            "vcsBlame",
            popup,
            editor.getDispatcher());
    }

    /** Blame in the VCS menu, checked while the column is on. */
    public static void addMenuItem(Editor editor, Menu menu) {
        final Buffer buffer = editor.getBuffer();
        menu.addCheckBox(editor, "Blame", 'B', "vcsBlame", column(buffer) != null).setEnabled(Blame.canBlame(buffer));
    }

    /** Toggles the blame column on the editor's buffer. */
    public static void vcsBlame(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        final BlameColumn on = column(buffer);
        if (on != null) {
            buffer.removeGutterColumn(on);
            return;
        }
        turnOn(editor);
    }

    /** Turns the blame column on for the editor's buffer, as a context menu does after opening the file. */
    public static void blameBuffer(Editor editor) {
        turnOn(editor);
    }

    // The column on the editor's buffer, with its blame read, unless it is
    // there already. Says why when there is none.
    private static void turnOn(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        if (column(buffer) != null)
            return;
        final Blame blame = Blame.of(buffer);
        if (blame == null) {
            editor.status("No blame: not a file in a repository");
            return;
        }
        final BlameColumn column = new BlameColumn(buffer, blame);
        blame.addListener(column.changed());
        buffer.addGutterColumn(column);
        // What another has read already, as the caret line's, shows now.
        column.changed().run();
        if (!column.told())
            editor.status("Reading blame\u2026");
    }

    // The caret line's commit in its buffer's blame, the line's number in
    // the commit's version of the file, and the caret's own.
    private record At(Blame blame, BlameCommit commit, int source, int caret) {}

    // The caret line's commit; null, said why, if it has none.
    private static At at(Editor editor) {
        final Blame blame = editor.getBuffer().getBlame();
        if (blame == null || blame.data() == null) {
            editor.status("No blame: turn it on with vcsBlame");
            return null;
        }
        final Line dot = editor.getDotLine();
        final BlameCommit commit = blame.data().commit(dot);
        if (commit == null || commit.isUncommitted()) {
            editor.status("Not committed yet");
            return null;
        }
        return new At(blame, commit, blame.data().sourceLine(dot), editor.getDotLineNumber());
    }

    /** The caret line's commit in the log, the file picked out in its files. */
    public static void vcsBlameShowCommit(Editor editor) {
        final At at = at(editor);
        if (at != null)
            showCommit(editor, at.blame(), at.commit());
    }

    static void showCommit(Editor editor, Blame blame, BlameCommit commit) {
        LogCommands
                .showCommit(editor, blame.repository(), blame.path(), blame.revision(), commit.hash(), commit.path());
    }

    /** The caret line's commit's hash on the clipboard. */
    public static void vcsBlameCopyHash(Editor editor) {
        final At at = at(editor);
        if (at == null)
            return;
        Editor.getKillRing().appendNew(at.commit().hash());
        Editor.getKillRing().copyLastKillToSystemClipboard();
        editor.status("Copied " + shortHash(at.commit().hash()));
    }

    /** The caret line's commit's whole message on the clipboard. */
    public static void vcsBlameCopyMessage(Editor editor) {
        final At at = at(editor);
        if (at == null)
            return;
        final String hash = at.commit().hash();
        at.blame().readMessage(hash, message -> {
            if (message == null) {
                editor.status("Can't read the message of " + shortHash(hash));
                return;
            }
            Editor.getKillRing().appendNew(message.strip());
            Editor.getKillRing().copyLastKillToSystemClipboard();
            editor.status("Copied the message of " + shortHash(hash));
        });
    }

    /**
     * The caret line's commit's change to the file, side by side: as its
     * parent had the file and as it has it, the caret on the line. A
     * commit with no parent adds the file whole.
     */
    public static void vcsBlameDiff(Editor editor) {
        final At at = at(editor);
        if (at == null)
            return;
        final VcsRepository r = at.blame().repository();
        final BlameCommit c = at.commit();
        final String name = c.path().substring(c.path().lastIndexOf('/') + 1);
        final Mode mode = SideBySide.modeFor(name);
        final Charset charset = SideBySide.charset();
        final Whitespace ignored = DiffWhitespace.ignored();
        record Read(byte[] old, byte[] now) {}
        Background.then(
            "vcs blame diff",
            () -> new Read(
                    c.previous() == null ? new byte[0] : r.backend().readFileAt(r, c.previous(), c.previousPath()),
                    r.backend().readFileAt(r, c.hash(), c.path())),
            read -> {
                if (read == null || read.old() == null || read.now() == null) {
                    editor.status("Can't read " + c.path() + " at " + shortHash(c.hash()) + " or its parent");
                    return;
                }
                final Side old = Side.ofFile(read.old(), charset);
                final Side now = Side.ofFile(read.now(), charset);
                final String oldTitle =
                        c.previous() == null ? "none" : c.previousPath() + " @ " + shortHash(c.previous());
                final CompareBuffer compare = CompareBuffer.open(
                    editor.getFrame(),
                    mode,
                    r.root() + "|" + c.hash() + "|" + c.path(),
                    oldTitle,
                    old,
                    c.path() + " @ " + shortHash(c.hash()),
                    now,
                    DiffAlignment.of(old, now, ignored));
                if (at.source() >= 0)
                    compare.place(compare, at.source());
            });
    }

    /**
     * The file as the caret line's commit has it, read-only and blamed, the
     * caret on that line there.
     */
    public static void vcsBlameRevision(Editor editor) {
        final At at = at(editor);
        if (at != null)
            open(editor, at, at.commit().hash(), at.commit().path());
    }

    /**
     * The caret line's file as its commit's parent had it, read-only and
     * blamed, the caret on the nearest line. Again in that buffer digs
     * further back.
     */
    public static void vcsBlameParent(Editor editor) {
        final At at = at(editor);
        if (at == null)
            return;
        final BlameCommit commit = at.commit();
        if (commit.previous() == null) {
            editor.status(
                commit.boundary()
                        ? "No parent: " + shortHash(commit.hash()) + " is where the history starts"
                        : "No parent");
            return;
        }
        open(editor, at, commit.previous(), commit.previousPath());
    }

    // path at revision, blamed, the caret near at's line.
    private static void open(Editor editor, At at, String revision, String path) {
        RevisionBuffer.read(editor, at.blame().repository(), path, revision, buffer -> {
            // The line nearest: its line in the commit's version of the
            // file, clamped; the caret's own number when unknown.
            final int line = at.source() >= 0 ? at.source() : at.caret();
            turnOn(VcsStatusMode.showAt(editor, buffer, Math.min(line, buffer.getLineCount() - 1) + 1));
        });
    }

    private static String shortHash(String hash) {
        return hash.substring(0, Math.min(7, hash.length()));
    }

    static boolean hasColumn(Buffer buffer) {
        return column(buffer) != null;
    }

    // The column on buffer, or null.
    private static BlameColumn column(Buffer buffer) {
        for (GutterColumn c : buffer.getGutterColumns())
            if (c instanceof BlameColumn b)
                return b;
        return null;
    }
}
