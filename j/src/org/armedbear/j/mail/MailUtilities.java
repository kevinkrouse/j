/*
 * MailUtilities.java
 *
 * Copyright (C) 2000-2003 Peter Graves
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

package org.armedbear.j.mail;

import java.util.List;
import java.lang.StringBuilder;
import org.armedbear.j.util.Utilities;

public class MailUtilities
{
    public static String constructAddressHeader(String prefix, List<MailAddress> list)
    {
        return constructAddressHeader(prefix, list, 8);
    }

    public static String constructAddressHeader(String prefix, List<MailAddress> list,
        int indent)
    {
        StringBuilder sb = new StringBuilder(prefix);
        int length = prefix.length();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                MailAddress a = list.get(i);
                String s = a.toEncodedString();
                if (i > 0 && length + s.length() > 74) {
                    // Won't fit on current line.
                    sb.append(',');
                    sb.append('\n');
                    sb.append(Utilities.spaces(indent)); // Continuation.
                    sb.append(s);
                    length = indent + s.length();
                } else {
                    if (i > 0) {
                        sb.append(", ");
                        length += 2;
                    }
                    sb.append(s);
                    length += s.length();
                }
            }
        }
        return sb.toString();
    }
}
