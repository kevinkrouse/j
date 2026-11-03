/*
 * DiffLine.java
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

package org.armedbear.j.vcs.diff;

/**
 * One line of a hunk: kind is ' ', '-' or '+'. The text is the line's bytes
 * as ISO-8859-1, without its newline but with any '\r'. noNewline is set when
 * the line ends its file without a newline ("\ No newline at end of file").
 */
public record DiffLine(char kind, String text, boolean noNewline, String marks) {
    public DiffLine {
        if (kind != ' ' && kind != '-' && kind != '+')
            throw new IllegalArgumentException("kind " + kind);
    }

    public DiffLine(char kind, String text, boolean noNewline) {
        this(kind, text, noNewline, null);
    }

    /**
     * A combined diff's line, marks a column for each parent: removed if
     * any column says so, else added if any does, else context.
     */
    static DiffLine combined(String marks, String text) {
        final char kind = marks.indexOf('-') >= 0 ? '-' : marks.indexOf('+') >= 0 ? '+' : ' ';
        return new DiffLine(kind, text, false, marks);
    }

    /** Its markers as the diff writes them: kind, or a combined diff's columns. */
    public String prefix() {
        return marks != null ? marks : String.valueOf(kind);
    }

    public boolean isContext() {
        return kind == ' ';
    }

    public boolean isRemoved() {
        return kind == '-';
    }

    public boolean isAdded() {
        return kind == '+';
    }

    /** Whether it is on the old side: context or removed; a combined line, in the first parent. */
    public boolean isOld() {
        return marks != null ? inParent(0) : kind != '+';
    }

    /**
     * Whether a combined line is in parent p, from 0: removed from the
     * result there ('-'), or in the result and not added to it (' ' with
     * no '-' in any column, as git writes a removed line's other columns).
     */
    public boolean inParent(int p) {
        final char c = marks.charAt(p);
        return c == '-' || c == ' ' && marks.indexOf('-') < 0;
    }

    /** Whether it is on the new side: context or added. */
    public boolean isNew() {
        return kind != '-';
    }

    // Its marks only while its kind is theirs: a patch's line is two-way.
    DiffLine as(char newKind, boolean newNoNewline) {
        return new DiffLine(newKind, text, newNoNewline, newKind == kind ? marks : null);
    }
}
