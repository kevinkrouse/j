/*
 * CodePoints.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import org.armedbear.j.Line;

/**
 * Stepping along a line by whole characters, so that the caret never rests
 * between the two halves of a surrogate pair such as an emoji. j's offsets
 * count UTF-16 units; vim's columns count characters.
 */
final class CodePoints
{
    private CodePoints()
    {
    }

    /** The offset one character on from this one, at most the line length. */
    static int next(Line line, int offset)
    {
        final String text = line.getText();
        if (text == null || offset >= text.length())
            return offset + 1;
        return offset + Character.charCount(text.codePointAt(offset));
    }

    /** The offset one character back from this one, at least 0. */
    static int previous(Line line, int offset)
    {
        final String text = line.getText();
        if (offset <= 0)
            return 0;
        if (text == null || offset > text.length())
            return offset - 1;
        return offset - Character.charCount(text.codePointBefore(offset));
    }

    /** This offset, or the start of the pair when it is inside one. */
    static int snap(Line line, int offset)
    {
        final String text = line.getText();
        if (text != null && offset > 0 && offset < text.length()
            && Character.isLowSurrogate(text.charAt(offset))
            && Character.isHighSurrogate(text.charAt(offset - 1)))
            return offset - 1;
        return offset;
    }
}
