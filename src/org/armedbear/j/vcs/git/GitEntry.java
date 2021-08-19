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
import org.armedbear.j.ShellCommand;
import org.armedbear.j.util.Utilities;
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
        ShellCommand cmd = new ShellCommand(
                "git status -z --porcelain --ignored --untracked -- " + Utilities.maybeQuote(file.getName()),
                file.getParentFile());
        cmd.run();
        String output = cmd.getOutput();
        if (output == null || output.length() == 0)
            return null;

        // We're expecting the format: XY<space>filename
        if (output.length() < 4) {
            Log.debug("Unexpected git status = |" + output + "|");
            return null;
        }

        String xy = output.substring(0, 2);
        char x = xy.charAt(0);
        char y = xy.charAt(1);
        String filename = output.substring(2);

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
                    Log.debug("Unexpected git xy status = |" + output + "|");
                    return null;
                }
                break;

            case 'M':
                if (y == ' ' || y == 'M' || y == 'T')
                    status = "modified";
                else if (y == 'D')
                    status = "deleted";
                else {
                    Log.debug("Unexpected git xy status = |" + output + "|");
                    return null;
                }
                break;

            case 'C':
                if (y == ' ' || y == 'M' || y == 'T')
                    status = "copied";
                else if (y == 'D')
                    status = "deleted";
                else {
                    Log.debug("Unexpected git xy status = |" + output + "|");
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
                    Log.debug("Unexpected git xy status = |" + output + "|");
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
                    Log.debug("Unexpected git xy status = |" + output + "|");
                    return null;
                }
                break;

            case 'U':
                if (y == 'U' || y == 'A' || y == 'D' || y == 'T')
                    status = "conflict";
                else {
                    Log.debug("Unexpected git xy status = |" + output + "|");
                    return null;
                }
                break;

            case 'R':
                if (y == ' ' || y == 'M' || y == 'T')
                    status = "renamed";
                else if (y == 'D')
                    status = "deleted";
                else {
                    Log.debug("Unexpected git xy status = |" + output + "|");
                    return null;
                }
                break;

            case 'T':
                if (y == ' ' || y == 'M')
                    status = "type changed";
                else if (y == 'D')
                    status = "deleted";
                else {
                    Log.debug("Unexpected git xy status = |" + output + "|");
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
                Log.debug("Unexpected git xy status = |" + output + "|");
                return null;
        }

        return new GitEntry(buffer, xy, status);
    }
}
