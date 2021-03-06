/*
 * OccurrenceLine.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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

import java.lang.StringBuilder;

public final class OccurrenceLine extends TextLine
{
    private Line sourceLine;
    private int sourceLineNumber; // 1-based.

    public OccurrenceLine(Line sourceLine)
    {
        super();
        StringBuilder sb = new StringBuilder();
        sb.append(sourceLine.lineNumber()+1);
        sb.append(':');
        sb.append(sourceLine.getText());
        init(sb.toString());
        this.sourceLine = sourceLine;
        // sourceLineNumber is 1-based.
        sourceLineNumber = sourceLine.lineNumber() + 1;
    }

    // sourceLineNumber is 1-based.
    public OccurrenceLine(String s, int sourceLineNumber)
    {
        super();
        StringBuilder sb = new StringBuilder();
        sb.append(sourceLineNumber);
        sb.append(':');
        sb.append(s);
        init(sb.toString());
        this.sourceLineNumber = sourceLineNumber;
    }

    public OccurrenceLine(Tag tag)
    {
        super();
        String s = tag.getCanonicalSignature();
        if (s == null)
            s = tag.getLongName();
        init(s);
    }

    public final Line getSourceLine()
    {
        return sourceLine;
    }

    // sourceLineNumber is 1-based.
    public final int getSourceLineNumber()
    {
        return sourceLineNumber;
    }
}
