/*
 * BlameCommit.java
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

/**
 * The commit a run of lines was last changed in, as blame says, shared by
 * every line of it.
 *
 * @param hash          the commit; all zeros for lines not committed yet
 * @param authorTime    the author date, seconds since the epoch
 * @param committerTime the commit date, seconds since the epoch
 * @param path          the file's path in the commit, as a rename left it
 * @param previous      the commit before it that changed the file, or null
 * @param previousPath  the file's path there, or null
 * @param boundary      whether it is where the history blame read ends
 */
public record BlameCommit(String hash, String author, String authorMail, long authorTime, long committerTime,
        String summary, String path, String previous, String previousPath, boolean boundary) {

    /** The commit of lines not committed yet, of path in the working tree. */
    public static BlameCommit uncommitted(String path) {
        return new BlameCommit("0".repeat(40), "Not Committed Yet", "", 0, 0, "", path, null, null, false);
    }

    /** Whether its lines are not committed yet. */
    public boolean isUncommitted() {
        return hash.chars().allMatch(c -> c == '0');
    }

    /** Told of each run of lines blame reads, as it reads them. */
    public interface Sink {
        /**
         * count lines from first, counted from 0, were last changed in
         * commit; count may run past the last. source is first's line in
         * commit's version of the file, counted from 0.
         */
        void lines(int first, int count, BlameCommit commit, int source);
    }
}
