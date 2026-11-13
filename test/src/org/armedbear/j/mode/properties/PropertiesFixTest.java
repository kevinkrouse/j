/*
 * PropertiesFixTest.java
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

package org.armedbear.j.mode.properties;

import static org.armedbear.j.LexicalContext.COMMENT;
import static org.armedbear.j.LexicalContext.NEUTRAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.armedbear.j.CaretCommands;
import org.armedbear.j.EditCommands;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.LexicalContext;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegment;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.Position;
import org.junit.jupiter.api.Test;

/**
 * The three Properties regressions: a continuation chain whose middle
 * lines start with a comment marker stays value text all the way
 * through; deleting a continuation backslash and immediately matching
 * sees the fresh flags, not the stale ones; and a tab-indented comment
 * is classified as a comment even when Show Tabs renders the tab as a
 * visible glyph.
 */
public class PropertiesFixTest {
    private LexicalContext ctx(EditorHarness h, int lineNo, int off) {
        Line line = h.buffer().getLine(lineNo);
        return PropertiesMode.getMode().lexicalContext(h.buffer(), new Position(line, off));
    }

    // Fix 1: the three-line chain. key=v \ continues to #middle, which
    // continues to #tail. The marker on the middle line is value text
    // because the line enters as a continuation; the tail must inherit
    // that continuation too, not restart as a fresh comment.
    @Test
    public void markerPrefixedContinuationChainStaysValue() {
        EditorHarness h = EditorHarness.create(
                "key = v \\\n#middle \\\n#tail").mode(PropertiesMode.getMode());
        try {
            h.buffer().refreshParse();
            // Both continuation lines carry the flag.
            assertTrue((h.buffer().getLine(1).flags() & PropertiesFormatter.PROPERTIES_CONTINUATION) != 0,
                    "middle line did not continue");
            assertTrue((h.buffer().getLine(2).flags() & PropertiesFormatter.PROPERTIES_CONTINUATION) != 0,
                    "tail line did not continue");
            // The tail is value text, not a comment.
            assertEquals(NEUTRAL, ctx(h, 2, 0));
            assertEquals(NEUTRAL, ctx(h, 2, "#tail".length()));
            // And the middle line's marker is value text too.
            assertEquals(NEUTRAL, ctx(h, 1, 0));
        }
        finally {
            h.close();
        }
    }

    // Fix 2: delete the continuation backslash, then match. Before the
    // delete the second line is a continuation (value). After deleting
    // the backslash the second line is a fresh comment. The % command
    // path must refresh the parse first, or it reads the stale
    // continuation flag and treats the comment's bracket as code.
    @Test
    public void deleteBackslashThenMatchSeesFreshFlags() {
        // key = v ( \
        // # ) tail
        // The ( is at offset 8, the backslash at 10. With the backslash,
        // line 1 is a continuation, so the ) is code and % on the (
        // matches it. Delete the backslash: line 1 becomes a fresh
        // comment, so % on the ( must find no match. The test calls
        // findMatchingPair without an explicit refreshParse in between:
        // the command path must refresh the parse itself, or the
        // matcher reads the stale continuation flag left by the
        // pre-delete parse and treats the comment's ) as code.
        EditorHarness h = EditorHarness.create(
                "key = v ( \\\n# ) tail").mode(PropertiesMode.getMode());
        try {
            Editor.setCurrentEditor(h.editor());
            h.buffer().refreshParse();
            // Before: line 1 is a continuation, the ) is code.
            assertTrue((h.buffer().getLine(1).flags() & PropertiesFormatter.PROPERTIES_CONTINUATION) != 0);
            h.editor().setDot(h.buffer().getLine(0), 8);
            h.editor().moveCaretToDotCol();
            CaretCommands.findMatchingPair(h.editor());
            // The ( matched the ) on line 1.
            assertEquals(1, h.editor().getDotLineNumber());
            // Delete the backslash at the end of line 0.
            h.editor().setDot(h.buffer().getLine(0), 10);
            h.editor().moveCaretToDotCol();
            EditCommands.delete(h.editor());
            // No explicit refreshParse here: the match must refresh.
            // % on the ( must find no match: the ) is now in a comment.
            h.editor().setDot(h.buffer().getLine(0), 8);
            h.editor().moveCaretToDotCol();
            CaretCommands.findMatchingPair(h.editor());
            // No match: the dot stays put on line 0.
            assertEquals(0, h.editor().getDotLineNumber());
            assertEquals(8, h.editor().getDotOffset());
        }
        finally {
            h.close();
        }
    }

    // Fix 3: a tab-indented comment under Show Tabs. The rendered text
    // starts with the ^I glyph, so classifying the rendered text would
    // miss the marker and paint the comment as ordinary text. The
    // classifier reads raw, so the comment is recognized and the
    // segment is the comment format.
    @Test
    public void tabIndentedCommentIsCommentUnderShowTabs() throws Exception {
        Field f = Editor.class.getDeclaredField("tabsAreVisible");
        f.setAccessible(true);
        boolean saved = (Boolean) f.get(null);
        f.set(null, true);
        try {
            EditorHarness h = EditorHarness.create("\t# tab-indented comment")
                    .mode(PropertiesMode.getMode());
            try {
                h.buffer().refreshParse();
                // The rendered text starts with the ^I glyph, so a
                // classifier reading the rendered text would miss the
                // marker. The raw classifier sees the tab as whitespace
                // and the marker as the first non-whitespace character.
                assertEquals(COMMENT, ctx(h, 0, 1));
                // The highlighting agrees: the segment is the comment
                // format, not text.
                LineSegmentList segs = h.buffer().getFormatter().formatLine(h.buffer().getFirstLine());
                assertEquals(1, segs.size());
                LineSegment sg = segs.getSegment(0);
                assertEquals(PropertiesFormatter.PROPERTIES_FORMAT_COMMENT, sg.getFormat(),
                        "tab-indented comment painted as format " + sg.getFormat()
                                + " text " + sg.getText());
            }
            finally {
                h.close();
            }
        }
        finally {
            f.set(null, saved);
        }
    }
}
