/*
 * JavadocLinks.java
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


package org.armedbear.j.mode.java;

import org.armedbear.j.Buffer;
import org.armedbear.j.File;
import org.armedbear.j.FollowLink;
import org.armedbear.j.Line;
import org.armedbear.j.TextLink;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Javadoc's links, for followLink: {@link Foo#bar(int)}, {@linkplain ...},
 * and @see, @throws and @exception. A reference's class is found
 * as findClass finds one, through the imports, the package and the source
 * paths; its member is one of the class's tags (JavaTag.isNamedBy).
 */
final class JavadocLinks
{
    // A reference: a class, qualified or not, a "#member", a parameter list.
    private static final String REFERENCE =
        "([\\w$.]*(?:#[\\w$]+(?:\\([^)]*\\))?)?)";
    private static final Pattern INLINE =
        Pattern.compile("\\{@(?:link|linkplain)\\s+" + REFERENCE);
    private static final Pattern BLOCK =
        Pattern.compile("@(?:see|throws|exception)\\s+" + REFERENCE);

    private JavadocLinks() {}

    /** The Javadoc reference at offset in line, or null. */
    static TextLink find(Buffer buffer, Line line, int offset)
    {
        final String text = line.getText();
        for (Pattern pattern : new Pattern[] { INLINE, BLOCK }) {
            final Matcher m = pattern.matcher(text);
            while (m.find()) {
                if (m.group(1).isEmpty())
                    continue;
                if (m.start(1) <= offset && offset < m.end(1))
                    return resolve(buffer, m.group(1), m.start(1), m.end(1));
            }
        }
        return null;
    }

    // "Foo#bar(int)" as "/path/Foo.java#bar(int)"; "#bar" as itself.
    private static TextLink resolve(Buffer buffer, String reference, int begin, int end)
    {
        final int hash = reference.indexOf('#');
        final String className = hash < 0 ? reference : reference.substring(0, hash);
        final String member = hash < 0 ? null : reference.substring(hash + 1);
        if (className.isEmpty())
            return new TextLink("#" + member, begin, end);
        // A nested class is in its outer class's file: Map.Entry in Map's.
        String name = className;
        File file = JavaSource.findSource(buffer, name, false);
        while (file == null && name.lastIndexOf('.') > 0) {
            name = name.substring(0, name.lastIndexOf('.'));
            file = JavaSource.findSource(buffer, name, false);
        }
        if (file == null)
            return TextLink.broken("No source for " + className, begin, end);
        final String simpleName = className.substring(className.lastIndexOf('.') + 1);
        return new TextLink(FollowLink.fileTarget(file, member != null ? member : simpleName),
                            begin, end);
    }
}
