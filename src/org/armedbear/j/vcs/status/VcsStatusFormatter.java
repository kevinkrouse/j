/*
 * VcsStatusFormatter.java
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

package org.armedbear.j.vcs.status;

import java.awt.Color;
import org.armedbear.j.Buffer;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.TextStyle;
import org.armedbear.j.vcs.RepositoryColors;
import org.armedbear.j.vcs.VcsColors;

/**
 * A status buffer's colors: headings bold, a file's letter by what
 * happened to it, and diffs green and red, the words that changed in a
 * line on a stronger shade.
 */
public class VcsStatusFormatter extends Formatter {
    static final int TEXT = 0;
    static final int HEADING = 1;
    static final int DIM = 2;
    static final int LABEL = 3;
    static final int BRANCH = 4;
    static final int LETTER_ADDED = 5;
    static final int LETTER_MODIFIED = 6;
    static final int LETTER_REMOVED = 7;
    static final int LETTER_OTHER = 8;
    static final int HUNK_HEADER = 9;
    static final int CONTEXT = 10;
    static final int ADDED = 11;
    static final int REMOVED = 12;
    static final int ADDED_WORD = 13;
    static final int REMOVED_WORD = 14;
    static final int HASH = 15;
    static final int REFS = 16;
    static final int ERROR = 17;
    static final int REPOSITORY = 18; // Through REPOSITORY + RepositoryColors.COUNT - 1.

    private Color addedBackground;
    private Color removedBackground;
    private Color addedWordBackground;
    private Color removedWordBackground;
    private Color hunkBackground;

    public VcsStatusFormatter(Buffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public LineSegmentList formatLine(Line line) {
        clearSegmentList();
        final String text = getDetabbedText(line);
        if (!(line instanceof StatusLine s)) {
            addSegment(text, TEXT);
            return segmentList;
        }
        switch (s.kind()) {
            case REPOSITORY -> {
                final int name = text.indexOf("  ");
                addSegment(text, 0, name < 0 ? text.length() : name, REPOSITORY + Math.max(0, s.repositoryColor()));
                if (name >= 0)
                    addSegment(text, name, DIM);
            }
            case SECTION -> {
                final int count = text.lastIndexOf(" (");
                addSegment(text, 0, count < 0 ? text.length() : count, HEADING);
                if (count >= 0)
                    addSegment(text, count, DIM);
            }
            case FIELD -> {
                addSegment(text, 0, Math.min(10, text.length()), LABEL);
                final String value = text.substring(Math.min(10, text.length()));
                final int end = value.indexOf("  ");
                addSegment(text, 10, end < 0 ? text.length() : 10 + end, BRANCH);
                if (end >= 0)
                    addSegment(text, 10 + end, TEXT);
            }
            case FILE -> {
                final int letter = StatusRenderer.INDENT.length();
                addSegment(text, 0, letter, TEXT);
                addSegment(text, letter, letter + 1, letterFormat(text.charAt(letter)));
                addSegment(text, letter + 1, TEXT);
            }
            case HUNK -> {
                final int end = text.indexOf("@@", StatusRenderer.INDENT.length() + 2);
                addSegment(text, 0, end < 0 ? text.length() : end + 2, HUNK_HEADER);
                if (end >= 0)
                    addSegment(text, end + 2, TEXT);
            }
            case DIFF -> diff(s, text);
            case COMMIT -> {
                final int start = StatusRenderer.INDENT.length();
                final int hash = text.indexOf(' ', start);
                addSegment(text, 0, hash < 0 ? text.length() : hash, HASH);
                if (hash >= 0) {
                    int rest = hash;
                    if (s.commit() != null && !s.commit().refs().isBlank()) {
                        rest = hash + s.commit().refs().length() + 3;
                        addSegment(text, hash, Math.min(rest, text.length()), REFS);
                    }
                    if (rest < text.length())
                        addSegment(text, rest, TEXT);
                }
            }
            case MESSAGE -> addSegment(text, text.startsWith("Error:") ? ERROR : DIM);
            default -> addSegment(text, TEXT);
        }
        return segmentList;
    }

    // A diff line: its kind's format, the changed words in the stronger one.
    private void diff(StatusLine s, String text) {
        final char kind = s.diffLine().kind();
        final int plain = kind == '+' ? ADDED : kind == '-' ? REMOVED : CONTEXT;
        final int word = kind == '+' ? ADDED_WORD : REMOVED_WORD;
        int at = 0;
        for (int[] r : s.words()) {
            if (r[0] > text.length())
                break;
            if (r[0] > at)
                addSegment(text, at, r[0], plain);
            addSegment(text, r[0], Math.min(r[1], text.length()), word);
            at = Math.min(r[1], text.length());
        }
        if (at < text.length() || at == 0)
            addSegment(text, at, plain);
    }

    private static int letterFormat(char c) {
        return switch (c) {
            case 'A', '?' -> LETTER_ADDED;
            case 'M' -> LETTER_MODIFIED;
            case 'D', 'U' -> LETTER_REMOVED;
            default -> LETTER_OTHER;
        };
    }

    @Override
    public Color getColor(int format) {
        final boolean dark = DefaultTheme.isDark(getBackgroundColor());
        if (format >= REPOSITORY && format < REPOSITORY + RepositoryColors.COUNT)
            return RepositoryColors.color(format - REPOSITORY, dark);
        final Color own = switch (format) {
            case LETTER_ADDED -> addedHue();
            case LETTER_REMOVED -> removedHue();
            case LETTER_MODIFIED -> themed("modified", VcsColors.modified(dark));
            default -> null;
        };
        return own != null ? own : super.getColor(format);
    }

    // Added and removed lines keep the text's color on a shade of these.
    private Color addedHue() {
        return themed("added", VcsColors.added(DefaultTheme.isDark(getBackgroundColor())));
    }

    private Color removedHue() {
        return themed("removed", VcsColors.removed(DefaultTheme.isDark(getBackgroundColor())));
    }

    @Override
    public int getStyle(int format) {
        if (format == HEADING
                || format >= REPOSITORY
                || format == LETTER_ADDED
                || format == LETTER_MODIFIED
                || format == LETTER_REMOVED
                || format == LETTER_OTHER)
            return TextStyle.BOLD;
        return super.getStyle(format);
    }

    // A color the theme or the user gives thing, or else fallback.
    private Color themed(String thing, Color fallback) {
        final Color c = getFormatTable().resolveColor(thing, false);
        return c != null ? c : fallback;
    }

    @Override
    public Color getLineBackground(Line line) {
        if (!(line instanceof StatusLine s))
            return null;
        if (s.kind() == StatusLine.Kind.HUNK)
            return hunkBackground();
        if (s.kind() != StatusLine.Kind.DIFF || s.diffLine().isContext())
            return null;
        shades();
        return s.diffLine().isAdded() ? addedBackground : removedBackground;
    }

    @Override
    public Color getRunBackground(int format) {
        if (format != ADDED_WORD && format != REMOVED_WORD)
            return null;
        shades();
        return format == ADDED_WORD ? addedWordBackground : removedWordBackground;
    }

    private Color hunkBackground() {
        if (hunkBackground == null)
            hunkBackground = DefaultTheme.blend(getBackgroundColor(), getColor(TEXT), 0.08);
        return hunkBackground;
    }

    private void shades() {
        if (addedBackground != null)
            return;
        final Color bg = getBackgroundColor();
        final boolean dark = DefaultTheme.isDark(bg);
        addedBackground = DefaultTheme.blend(bg, addedHue(), dark ? 0.16 : 0.12);
        removedBackground = DefaultTheme.blend(bg, removedHue(), dark ? 0.16 : 0.12);
        addedWordBackground = DefaultTheme.blend(bg, addedHue(), dark ? 0.38 : 0.30);
        removedWordBackground = DefaultTheme.blend(bg, removedHue(), dark ? 0.38 : 0.30);
    }

    @Override
    public void reset() {
        super.reset();
        addedBackground = null;
        hunkBackground = null;
    }

    @Override
    public FormatTable getFormatTable() {
        if (formatTable == null) {
            formatTable = new FormatTable("VcsStatusMode");
            formatTable.addEntryFromPrefs(TEXT, "text");
            formatTable.addEntryFromPrefs(HEADING, "heading", "keyword");
            formatTable.addEntryFromPrefs(DIM, "comment");
            formatTable.addEntryFromPrefs(LABEL, "label", "comment");
            formatTable.addEntryFromPrefs(BRANCH, "branch", "function");
            formatTable.addEntryFromPrefs(LETTER_ADDED, "text");
            formatTable.addEntryFromPrefs(LETTER_MODIFIED, "text");
            formatTable.addEntryFromPrefs(LETTER_REMOVED, "text");
            formatTable.addEntryFromPrefs(LETTER_OTHER, "renamed", "type");
            formatTable.addEntryFromPrefs(HUNK_HEADER, "hunkHeader", "comment");
            formatTable.addEntryFromPrefs(CONTEXT, "text");
            formatTable.addEntryFromPrefs(ADDED, "text");
            formatTable.addEntryFromPrefs(REMOVED, "text");
            formatTable.addEntryFromPrefs(ADDED_WORD, "text");
            formatTable.addEntryFromPrefs(REMOVED_WORD, "text");
            formatTable.addEntryFromPrefs(HASH, "hash", "comment");
            formatTable.addEntryFromPrefs(REFS, "refs", "type");
            formatTable.addEntryFromPrefs(ERROR, "error", "keyword");
            for (int i = 0; i < RepositoryColors.COUNT; i++)
                formatTable.addEntryFromPrefs(REPOSITORY + i, "text");
        }
        return formatTable;
    }
}
