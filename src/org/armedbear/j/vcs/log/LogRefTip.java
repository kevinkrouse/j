/*
 * LogRefTip.java
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

package org.armedbear.j.vcs.log;

/**
 * A ref and the commit it is at, for the refs tree: HEAD, a branch, a
 * remote branch or a tag.
 *
 * @param id its commit: for an annotated tag, the one it points at
 * @param time when it was made, in seconds since the epoch: a tag's date,
 *     else its commit's
 * @param ahead commits on a local branch its upstream hasn't; 0 for none
 * @param behind commits on its upstream it hasn't
 * @param gone whether its upstream was deleted
 * @param primary whether it is the repository's default branch: the one
 *     its remote's HEAD names, else a local main or master
 */
public record LogRefTip(LogRef ref, String id, long time, int ahead, int behind, boolean gone, boolean primary) {
    public LogRefTip(LogRef ref, String id, long time, int ahead, int behind, boolean gone) {
        this(ref, id, time, ahead, behind, gone, false);
    }

    /** It as the default branch. */
    public LogRefTip asPrimary() {
        return new LogRefTip(ref, id, time, ahead, behind, gone, true);
    }

    /** Its ahead and behind as "↑2 ↓1", or "gone"; "" for neither. */
    public String track() {
        if (gone)
            return "gone";
        final StringBuilder sb = new StringBuilder();
        if (ahead > 0)
            sb.append('↑').append(ahead);
        if (behind > 0)
            sb.append(sb.isEmpty() ? "" : " ").append('↓').append(behind);
        return sb.toString();
    }
}
