/*
 * DirectoryFilenameFilter.java
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

package org.armedbear.j;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class DirectoryFilenameFilter {
    private Pattern pattern;
    private boolean ignoreCase;
    // Whether there is a pattern at all, or only hidden names are left out.
    private final boolean limited;
    private final boolean hideDotFiles;

    /** Names matching the pattern s, all if s is null; with hideDotFiles, none starting with a dot. */
    public DirectoryFilenameFilter(String s, boolean hideDotFiles) throws PatternSyntaxException {
        this.hideDotFiles = hideDotFiles;
        limited = s != null;
        if (s == null)
            return;
        ignoreCase = Platform.isFileSystemCaseInsensitive();
        if (ignoreCase)
            s = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '.':
                    sb.append("\\.");
                    break;
                case '*':
                    sb.append(".*");
                    break;
                case '?':
                    sb.append(".?");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        try {
            pattern = Pattern.compile(sb.toString());
        }
        catch (PatternSyntaxException e) {
            Log.debug(e);
            pattern = null;
        }
    }

    /** Whether name is hidden and left out, a directory or not; never "." or "..". */
    public boolean hides(String name) {
        return hideDotFiles && name != null && name.startsWith(".") && !name.equals(".") && !name.equals("..");
    }

    public boolean accepts(String name) {
        if (hides(name))
            return false;
        if (!limited)
            return true;
        if (pattern == null) // Not a pattern, such as "[": matches nothing.
            return false;
        if (ignoreCase)
            name = name.toLowerCase(Locale.ROOT);
        Matcher matcher = pattern.matcher(name);
        return matcher.matches();
    }
}
