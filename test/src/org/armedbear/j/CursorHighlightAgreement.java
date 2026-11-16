/*
 * CursorHighlightAgreement.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the agreement between the cursor and the highlighting parser:
 * two loops over the same text that must agree on comment and string
 * runs. For every maximal comment/string run the highlighting produced,
 * the cursor must report the matching context strictly inside the run,
 * and vice versa. The opening marker's interior is exempt in the
 * forward direction: a caret between the two characters of {@code /*},
 * {@code //} or {@code #|} reads as before the marker by the boundary
 * rule while the highlighting counts it as comment.
 */
public final class CursorHighlightAgreement {
    private CursorHighlightAgreement() {
    }

    /**
     * Assert that the cursor agrees with the highlighting on every
     * comment and string run in {@code text} under {@code mode}.
     *
     * @param commentFormat  the formatter's comment segment format id
     * @param stringFormat   the formatter's string segment format id
     * @param twoCharOpeners the language's two-character comment openers
     *                       ({@code //}, {@code /*}, {@code #|}); a run
     *                       beginning with one has its opening-marker
     *                       interior exempt. A single-character opener
     *                       like {@code #} has none.
     */
    public static void assertAgrees(
            Mode mode, String text, int commentFormat, int stringFormat, String... twoCharOpeners) {
        EditorHarness h = EditorHarness.create(text).mode(mode);
        try {
            Formatter formatter = h.buffer().getFormatter();
            formatter.parseBuffer();
            for (Line line = h.buffer().getFirstLine(); line != null; line = line.next()) {
                int len = line.length();
                if (len == 0)
                    continue;
                int[] fmt = new int[len];
                java.util.Arrays.fill(fmt, -1);
                LineSegmentList segs = formatter.formatLine(line);
                int pos = 0;
                for (int s = 0; s < segs.size(); s++) {
                    LineSegment sg = segs.getSegment(s);
                    String t = sg.getText();
                    for (int k = 0; k < t.length() && pos + k < len; k++)
                        fmt[pos + k] = sg.getFormat();
                    pos += t.length();
                }
                for (int i = 0; i < len; i++) {
                    int f0 = fmt[i];
                    if (f0 != commentFormat && f0 != stringFormat)
                        continue;
                    int a = i;
                    int b = i;
                    while (b + 1 < len && fmt[b + 1] == f0)
                        b++;
                    LexicalContext want = f0 == commentFormat
                            ? LexicalContext.COMMENT
                            : LexicalContext.STRING;
                    // A run opening with a two-character marker has one
                    // exempt position: the caret between the marker's
                    // characters reads as before the marker by the
                    // boundary rule. A single-character opener has none.
                    int from = a + 1;
                    if (f0 == commentFormat && startsWithTwoCharOpener(line, a, twoCharOpeners))
                        from = a + 2;
                    for (int j = from; j <= b; j++) {
                        LexicalContext got = mode.lexicalContext(h.buffer(), new Position(line, j));
                        assertEquals(want, got,
                                mode.getDisplayName() + " [" + text + "] run[" + a + "," + b + "]"
                                        + " fmt=" + f0 + " off=" + j);
                    }
                    i = b;
                }
            }
            // The reverse direction: every offset the cursor calls a
            // comment or string must be highlighted as one. The forward
            // check alone lets the cursor over-report — e.g. lingering
            // past a closer (the offset just after */ still read as
            // COMMENT). No opening-marker exemption here: the cursor
            // reports NEUTRAL there, imposing no highlighting requirement.
            for (Line line = h.buffer().getFirstLine(); line != null; line = line.next()) {
                int len = line.length();
                if (len == 0)
                    continue;
                int[] fmt = new int[len];
                java.util.Arrays.fill(fmt, -1);
                LineSegmentList segs = formatter.formatLine(line);
                int pos = 0;
                for (int s = 0; s < segs.size(); s++) {
                    LineSegment sg = segs.getSegment(s);
                    String t = sg.getText();
                    for (int k = 0; k < t.length() && pos + k < len; k++)
                        fmt[pos + k] = sg.getFormat();
                    pos += t.length();
                }
                for (int j = 0; j < len; j++) {
                    LexicalContext got = mode.lexicalContext(h.buffer(), new Position(line, j));
                    if (got == LexicalContext.COMMENT)
                        assertEquals(commentFormat, fmt[j],
                                mode.getDisplayName() + " [" + text + "] cursor COMMENT at " + j
                                        + " but fmt=" + fmt[j]);
                    else if (got == LexicalContext.STRING)
                        assertEquals(stringFormat, fmt[j],
                                mode.getDisplayName() + " [" + text + "] cursor STRING at " + j
                                        + " but fmt=" + fmt[j]);
                }
            }
        }
        finally {
            h.close();
        }
    }

    private static boolean startsWithTwoCharOpener(Line line, int a, String[] openers) {
        for (String opener : openers) {
            if (line.length() >= a + 2
                    && line.charAt(a) == opener.charAt(0)
                    && line.charAt(a + 1) == opener.charAt(1))
                return true;
        }
        return false;
    }
}
