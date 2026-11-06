/*
 * StatusView.java
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

import java.util.BitSet;
import java.util.List;
import java.util.function.IntPredicate;
import org.armedbear.j.Editor;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.util.Diff.Edit;
import org.armedbear.j.util.PickState;

/**
 * What an open git status has of a file shown in a buffer: git's diff of
 * its unstaged changes, as read, and what of them is picked. The file's
 * signs and hunk commands take the diff while it is still the file's, and
 * then show the picks. On the event thread.
 */
public interface StatusView {
    /** A status, giving its view of a file; null if it has none. */
    interface Source {
        StatusView viewOf(SignsUpdater u);

        /** repository's changelists changed: shown again, if it shows them. */
        default void listsChanged(org.armedbear.j.vcs.VcsRepository repository) {}
    }

    /**
     * The rows, from 0, as the working tree has them, of the changes
     * picked: every row, the file picked whole; else rows, git's diff's.
     */
    record Picked(boolean whole, BitSet rows) implements IntPredicate {
        @Override
        public boolean test(int n) {
            return whole || rows.get(n);
        }
    }

    /** git's edits of the file, as read; null unread, or the buffer edited or saved since. */
    List<Edit> edits();

    /** What of the file is picked, as its diff is the buffer's; null for nothing, or not. */
    Picked picked();

    /** Whether anything of the file is picked, its diff the buffer's or not. */
    boolean hasPicks();

    /**
     * The column of boxes the buffer has while anything of the file is
     * picked, the same each time; its boxes there while its signs are by
     * the diff.
     */
    GutterColumn column();

    /** How much of the change at row, from 0, or the nearest in its hunk, is picked. */
    PickState stateAt(int row);

    /**
     * Picks the change at row from, or with lines, the changed lines of
     * rows from to to; or, each picked, unpicks them; said in editor, and
     * then run, if editor still shows the file, its caret where it was.
     */
    void pick(Editor editor, int from, int to, boolean lines, Runnable then);
}
