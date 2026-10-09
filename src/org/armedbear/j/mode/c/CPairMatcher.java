/*
 * CPairMatcher.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.c;

import org.armedbear.j.BracketPairMatcher;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * Brackets, and the preprocessor's #if, #else and #endif, which vim's % goes
 * between when the caret is at or before the line's '#'.
 */
public final class CPairMatcher extends BracketPairMatcher {
    public static final CPairMatcher INSTANCE = new CPairMatcher();

    private CPairMatcher() {}

    @Override
    public Position findMatch(Editor editor, Position pos) {
        final Line line = pos.getLine();
        final int hash = line.getText().indexOf('#');
        final boolean directive = hash >= 0 && line.substring(0, hash).isBlank();
        if (directive && pos.getOffset() <= hash) {
            final Position match = findDirective(line);
            if (match != null)
                return match;
        }
        final Position match = super.findMatch(editor, pos);
        return match != null || !directive ? match : findDirective(line);
    }

    // The '#' of the directive that pairs with line's.
    private static Position findDirective(Line line) {
        final Line match = CMode.findMatchPreprocessor(line);
        return match == null ? null : new Position(match, match.getText().indexOf('#'));
    }
}
