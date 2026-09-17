/*
 * GitEntry.java
 *
 * Copyright (C) 2012 Kevin Krouse
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

package org.armedbear.j.vcs.git;

import org.armedbear.j.Buffer;
import org.armedbear.j.Constants;
import java.lang.StringBuilder;
import org.armedbear.j.File;
import org.armedbear.j.Log;
import org.armedbear.j.vcs.VersionControlEntry;

public class GitEntry extends VersionControlEntry
{
    private String xy;
    private String status;

    protected GitEntry(Buffer buffer, String xy, String status)
    {
        super(buffer, "");
        this.xy = xy;
        this.status = status;
    }

    @Override
    public int getVersionControl()
    {
        return Constants.VC_GIT;
    }

    @Override
    public String getStatusText()
    {
        return statusText(true);
    }

    @Override
    public String getLongStatusText()
    {
        return statusText(false);
    }

    /**
     * Maps git's two letter porcelain code to a status kind.
     *
     * <p>The first letter is the index, the second the working tree. A conflict
     * shows as matching letters on both sides, or a D or A paired with a U, and
     * has to be tested before anything else because those letters would
     * otherwise read as an ordinary add or delete.
     */
    @Override
    public int getStatusKind()
    {
        if (xy == null || xy.length() < 2)
            return Constants.VCS_UNKNOWN;
        final char index = xy.charAt(0);
        final char tree = xy.charAt(1);

        if (index == 'U' || tree == 'U'
            || (index == 'D' && tree == 'D')
            || (index == 'A' && tree == 'A'))
            return Constants.VCS_CONFLICT;
        if (index == '?' || tree == '?' || index == 'A' || tree == 'A')
            return Constants.VCS_NEW;
        if (index == 'D' || tree == 'D')
            return Constants.VCS_DELETED;
        if (index == 'M' || tree == 'M' || index == 'R' || tree == 'R')
            return Constants.VCS_MODIFIED;
        if (index == ' ' && tree == ' ')
            return Constants.VCS_UNCHANGED;
        return Constants.VCS_UNKNOWN;
    }

    private String statusText(boolean brief)
    {
        StringBuilder sb = new StringBuilder("git");
        if (status != null) {
            sb.append(" ").append(brief ? xy : status);
        }

        // TODO: add branch name
        // TODO: add last change date or output of 'git describe'
        // TODO: add last author

        return sb.toString();
    }

    public static GitEntry getEntry(Buffer buffer)
    {
        if (!Git.haveGit())
            return null;

        final File file = buffer.getFile();
        final String xy = GitStatusCache.statusFor(file);
        if (xy == null || xy.length() < 2)
            return null;
        final char x = xy.charAt(0);
        final char y = xy.charAt(1);

        String status;
        switch (x) {
            case ' ':
                if (y == 'M')
                    status = "modified";
                else if (y == 'D')
                    status = "deleted";
                else if (y == 'T')
                    status = "type changed";
                else if (y == 'U')
                    status = "conflict";
                else {
                    Log.debug("Unexpected git xy status = |" + xy + "|");
                    return null;
                }
                break;

            case 'M':
                if (y == ' ' || y == 'M' || y == 'T')
                    status = "modified";
                else if (y == 'D')
                    status = "deleted";
                else {
                    Log.debug("Unexpected git xy status = |" + xy + "|");
                    return null;
                }
                break;

            case 'C':
                if (y == ' ' || y == 'M' || y == 'T')
                    status = "copied";
                else if (y == 'D')
                    status = "deleted";
                else {
                    Log.debug("Unexpected git xy status = |" + xy + "|");
                    return null;
                }
                break;

            case 'A':
                if (y == ' ' || y == 'M' || y == 'T')
                    status = "added";
                else if (y == 'D')
                    status = "added+deleted"; // no change?
                else if (y == 'U')
                    status = "unmerged, added by us";
                else if ( y == 'A')
                    status = "unmerged, both added";
                else {
                    Log.debug("Unexpected git xy status = |" + xy + "|");
                    return null;
                }
                break;

            case 'D':
                if (y == ' ' || y == 'M' || y == 'T')
                    status = "deleted";
                else if (y == 'U')
                    status = "unmerged, deleted by us";
                else if ( y == 'D')
                    status = "unmerged, both deleted";
                else {
                    Log.debug("Unexpected git xy status = |" + xy + "|");
                    return null;
                }
                break;

            case 'U':
                if (y == 'U' || y == 'A' || y == 'D' || y == 'T')
                    status = "conflict";
                else {
                    Log.debug("Unexpected git xy status = |" + xy + "|");
                    return null;
                }
                break;

            case 'R':
                if (y == ' ' || y == 'M' || y == 'T')
                    status = "renamed";
                else if (y == 'D')
                    status = "deleted";
                else {
                    Log.debug("Unexpected git xy status = |" + xy + "|");
                    return null;
                }
                break;

            case 'T':
                if (y == ' ' || y == 'M')
                    status = "type changed";
                else if (y == 'D')
                    status = "deleted";
                else {
                    Log.debug("Unexpected git xy status = |" + xy + "|");
                    return null;
                }
                break;

            case '?':
                status = "untracked";
                break;

            case '!':
                status = "ignored";
                break;

            default:
                Log.debug("Unexpected git xy status = |" + xy + "|");
                return null;
        }

        return new GitEntry(buffer, xy, status);
    }
}
