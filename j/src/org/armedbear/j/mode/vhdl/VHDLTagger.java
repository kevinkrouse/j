/*
 * VHDLTagger.java
 *
 * Copyright (C) 2002 Peter Graves
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

package org.armedbear.j.mode.vhdl;

import org.armedbear.j.Line;
import org.armedbear.j.LocalTag;
import org.armedbear.j.SystemBuffer;
import org.armedbear.j.Tagger;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;

public final class VHDLTagger extends Tagger
{
    // We trim before matching, so "entity" will appear without any preceding
    // whitespace.
    private static final Pattern entityRE =
        Pattern.compile("^entity\\s+([a-z][a-z0-9_]*[a-z0-9])", Pattern.CASE_INSENSITIVE);

    public VHDLTagger(SystemBuffer buffer)
    {
        super(buffer);
    }

    public void run()
    {
        ArrayList<LocalTag> tags = new ArrayList<LocalTag>();
        Line line = buffer.getFirstLine();
        while (line != null) {
            String s = line.trim();
            if (s != null && s.length() > 0) {
                char c = s.charAt(0);
                if (c == 'e' || c == 'E') {
                    Matcher matcher = entityRE.matcher(s);
                    if (matcher.find()) {
                        String name = matcher.group(1);
                        tags.add(new LocalTag(name, line));
                    }
                }
            }
            line = line.next();
        }
        buffer.setTags(tags);
    }
}
