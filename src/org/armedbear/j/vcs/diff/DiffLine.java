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
public record DiffLine(char kind, String text, boolean noNewline) {
    public DiffLine {
        if (kind != ' ' && kind != '-' && kind != '+')
            throw new IllegalArgumentException("kind " + kind);
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

    /** Whether it is on the old side: context or removed. */
    public boolean isOld() {
        return kind != '+';
    }

    /** Whether it is on the new side: context or added. */
    public boolean isNew() {
        return kind != '-';
    }

    DiffLine as(char newKind, boolean newNoNewline) {
        return new DiffLine(newKind, text, newNoNewline);
    }
}
