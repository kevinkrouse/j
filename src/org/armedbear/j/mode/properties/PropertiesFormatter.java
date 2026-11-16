/*
 * PropertiesFormatter.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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

package org.armedbear.j.mode.properties;

import java.awt.Color;
import java.util.Locale;
import java.util.Optional;
import org.armedbear.j.Buffer;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.LexicalCursor;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.util.Colors;

public final class PropertiesFormatter extends Formatter {
    private static final byte PROPERTIES_FORMAT_TEXT = 0;
    static final byte PROPERTIES_FORMAT_COMMENT = 1;
    private static final byte PROPERTIES_FORMAT_SECTION = 2;
    private static final byte PROPERTIES_FORMAT_KEY = 3;
    private static final byte PROPERTIES_FORMAT_VALUE = 4;
    private static final byte PROPERTIES_FORMAT_DELIMITER = 5;

    // Line-flag bit: this line continues the previous one, set by
    // parseBuffer and read by the queries. Properties has no string
    // syntax and comments don't carry across lines, so the continuation
    // is the only state that crosses a line boundary.
    static final int PROPERTIES_CONTINUATION = 0x0100;

    public PropertiesFormatter(Buffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public LineSegmentList formatLine(Line line) {
        clearSegmentList();
        final String text = getDetabbedText(line);
        // Classify on the raw text: with Show Tabs on, getDetabbedText
        // turns a tab into a visible ^I glyph, so a tab-indented comment's
        // first non-whitespace char would be the caret, not the marker.
        // The classifier reads raw so highlighting agrees with the lexical
        // query; rendered text is used only for segment content.
        final String raw = line.getText() == null ? "" : line.getText();
        final boolean continuation = (line.flags() & PROPERTIES_CONTINUATION) != 0;
        if (continuation) {
            // A continuation of the previous line is value text, even
            // when it starts with a comment marker.
            addSegment(text, PROPERTIES_FORMAT_VALUE);
            return segmentList;
        }
        if (PropertiesLexicon.isComment(raw, false)) {
            addSegment(text, PROPERTIES_FORMAT_COMMENT);
            return segmentList;
        }
        if (text.length() > 0) {
            switch (text.charAt(0)) {
                case '[':
                case '<':
                    addSegment(text, PROPERTIES_FORMAT_SECTION);
                    break;
                default:
                    int index = text.indexOf('=');
                    if (index < 0)
                        index = text.indexOf(':');
                    if (index >= 0) {
                        addSegment(text, 0, index, PROPERTIES_FORMAT_KEY);
                        addSegment(text, index, index + 1, PROPERTIES_FORMAT_DELIMITER);
                        addSegment(text, index + 1, PROPERTIES_FORMAT_VALUE);
                    } else
                        addSegment(text, PROPERTIES_FORMAT_TEXT);
                    break;
            }
        } else
            addSegment(text, PROPERTIES_FORMAT_TEXT);
        return segmentList;
    }

    @Override
    public boolean parseBuffer() {
        boolean changed = false;
        boolean continuation = false;
        Line line = buffer.getFirstLine();
        while (line != null) {
            final int oldflags = line.flags();
            final int newflags = continuation ? PROPERTIES_CONTINUATION : 0;
            if (newflags != oldflags) {
                line.setFlags(newflags);
                changed = true;
            }
            // The next line continues this one on an unescaped trailing
            // backslash; a comment line never continues. The incoming
            // continuation state is passed so a marker at the start of a
            // continuation is value text, not a fresh comment: in
            // key=v \ / #middle \ / #tail the last two lines are both
            // continuations. Raw text: detabbing can't affect the result.
            final String text = line.getText();
            continuation = !PropertiesLexicon.isComment(text, continuation)
                    && PropertiesLexicon.continuesFrom(text);
            line = line.next();
        }
        buffer.setNeedsParsing(false);
        return changed;
    }

    @Override
    public Optional<LexicalContext> lexicalContextAt(Line line, int offset) {
        final int upto = Math.min(offset, line.length());
        Cursor cursor = new Cursor(line, line.flags(), upto, null);
        LexicalContext ctx = cursor.run();
        scanSteps += cursor.steps;
        return Optional.of(ctx);
    }

    @Override
    public Optional<LexicalContext[]> lexicalContexts(Line line, int end) {
        final int upto = Math.min(end, line.length());
        final LexicalContext[] out = new LexicalContext[upto + 1];
        Cursor cursor = new Cursor(line, line.flags(), upto, out);
        cursor.run();
        scanSteps += cursor.steps;
        return Optional.of(out);
    }

    // Total characters scanned by lexical queries since the last reset.
    // The guard reads it through the real mode.lexicalContexts /
    // lexicalContext entry points: a bulk query secretly looping point
    // queries would accumulate O(len^2) here and blow the linear bound.
    long scanSteps;

    /**
     * The single source of Properties lexical context for the pairing
     * query: a comment line is COMMENT from its marker to end of line;
     * everything else — continuations, key/value text with literal quotes
     * — is NEUTRAL, since properties has no string syntax. With an output
     * array it records every offset (bulk); without one it stops at
     * {@code upto} (point).
     */
    static final class Cursor extends LexicalCursor {
        long steps;
        private final int enteringState;

        Cursor(Line line, int enteringState, int upto, LexicalContext[] out) {
            super(line, upto, out);
            this.enteringState = enteringState;
        }

        LexicalContext run() {
            final boolean continuation = (enteringState & PROPERTIES_CONTINUATION) != 0;
            final boolean comment = PropertiesLexicon.isComment(text, continuation);
            // COMMENT from start to end of line, matching the highlighting
            // that paints leading whitespace too; the marker position is
            // not a pairing boundary.
            final LexicalContext ctx =
                comment ? LexicalContext.COMMENT : LexicalContext.NEUTRAL;
            while (i <= upto) {
                if (out != null)
                    out[i] = ctx;
                if (i == upto)
                    return ctx;
                steps++;
                ++i;
            }
            return ctx;
        }
    }

    /**
     * The color a line sets, "color.text = 0 255 0" or
     * "JavaMode.color.comment = #808080", for a swatch in the gutter.
     */
    @Override
    public Color getGutterColor(Line line) {
        final String text = line.getText().trim();
        if (text.isEmpty())
            return null;
        switch (text.charAt(0)) {
            case '#':
            case ';':
            case '!':
                return null;
        }
        // As a properties file separates them: '=', ':' or whitespace.
        int index = 0;
        while (index < text.length() && "=: \t".indexOf(text.charAt(index)) < 0)
            ++index;
        if (index == text.length())
            return null;
        if (!text.substring(0, index).toLowerCase(Locale.ROOT).contains("color"))
            return null;
        String value = text.substring(index).trim();
        if (!value.isEmpty() && (value.charAt(0) == '=' || value.charAt(0) == ':'))
            value = value.substring(1);
        return Colors.parseColor(value);
    }

    @Override
    public FormatTable getFormatTable() {
        if (formatTable == null) {
            formatTable = new FormatTable("PropertiesMode");
            formatTable.addEntryFromPrefs(PROPERTIES_FORMAT_TEXT, "text");
            formatTable.addEntryFromPrefs(PROPERTIES_FORMAT_COMMENT, "comment");
            formatTable.addEntryFromPrefs(PROPERTIES_FORMAT_SECTION, "section");
            formatTable.addEntryFromPrefs(PROPERTIES_FORMAT_KEY, "key", "function");
            formatTable.addEntryFromPrefs(PROPERTIES_FORMAT_VALUE, "value", "text");
            formatTable.addEntryFromPrefs(PROPERTIES_FORMAT_DELIMITER, "delimiter", "operator");
        }
        return formatTable;
    }
}
