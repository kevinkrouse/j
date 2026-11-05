/*
 * Signs.java
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

package org.armedbear.j;

import java.awt.Color;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * A buffer's change marks as its version control sees them: each line
 * added, changed, or with lines deleted below it, against what is staged,
 * or staged against the last commit. They are drawn in place of j's own
 * marks of what changed since the file was loaded.
 */
public final class Signs {
    public enum Kind {
        ADDED, CHANGED, DELETED
    }

    /**
     * @param staged whether the change is in the index, not only in the file
     * @param above for DELETED on the first line: the lines went above it,
     *     not below
     * @param picked whether the change is picked, as in the git status
     */
    public record Sign(Kind kind, boolean staged, boolean above, boolean picked) {
        public Sign(Kind kind, boolean staged, boolean above) {
            this(kind, staged, above, false);
        }
    }

    /** A kind's color, from its preference or the default for a light or dark background. */
    public static Color color(Kind kind, Color background) {
        final boolean dark = DefaultTheme.isDark(background);
        final String name = switch (kind) {
            case ADDED -> "color.vcsAdded";
            case CHANGED -> "color.vcsChanged";
            case DELETED -> "color.vcsDeleted";
        };
        final Color c = Editor.preferences() != null ? Editor.preferences().getColorProperty(name) : null;
        if (c != null)
            return c;
        return switch (kind) {
            case ADDED -> ChangeColors.added(dark);
            case CHANGED -> ChangeColors.modified(dark);
            case DELETED -> ChangeColors.removed(dark);
        };
    }

    private final Map<Line, Sign> signs;
    private final List<Line> starts;

    /** starts: the first line of each hunk, in order, for moving by hunk. */
    public Signs(Map<Line, Sign> signs, List<Line> starts) {
        this.signs = new IdentityHashMap<>(signs);
        this.starts = List.copyOf(starts);
    }

    /** Line's sign, or null if it is unchanged. */
    public Sign get(Line line) {
        return signs.get(line);
    }

    /** The first line of each hunk, in order. */
    public List<Line> starts() {
        return starts;
    }

    public boolean isEmpty() {
        return signs.isEmpty();
    }

    /** These signs, those of lines not staged whose number picked takes picked. */
    public Signs picked(IntPredicate picked) {
        final Map<Line, Sign> out = new IdentityHashMap<>();
        for (Map.Entry<Line, Sign> e : signs.entrySet()) {
            final Sign s = e.getValue();
            out.put(
                e.getKey(),
                new Sign(s.kind(), s.staged(), s.above(), !s.staged() && picked.test(e.getKey().lineNumber())));
        }
        return new Signs(out, starts);
    }
}
