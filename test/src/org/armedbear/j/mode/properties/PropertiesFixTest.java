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
 * Three Properties regressions: a comment-marker continuation chain
 * stays value text; matching right after deleting a continuation
 * backslash sees fresh flags; a tab-indented comment is still a
 * comment under Show Tabs.
 */
public class PropertiesFixTest {
    private LexicalContext ctx(EditorHarness h, int lineNo, int off) {
        Line line = h.buffer().getLine(lineNo);
        return PropertiesMode.getMode().lexicalContext(h.buffer(), new Position(line, off));
    }

    // Fix 1: key=v \ -> #middle \ -> #tail. Each line enters as a
    // continuation, so the # markers are value text, not comments.
    @Test
    public void markerPrefixedContinuationChainStaysValue() {
        EditorHarness h = EditorHarness.create(
                "key = v \\\n#middle \\\n#tail").mode(PropertiesMode.getMode());
        try {
            h.buffer().refreshParse();
            assertTrue((h.buffer().getLine(1).flags() & PropertiesFormatter.PROPERTIES_CONTINUATION) != 0,
                    "middle line did not continue");
            assertTrue((h.buffer().getLine(2).flags() & PropertiesFormatter.PROPERTIES_CONTINUATION) != 0,
                    "tail line did not continue");
            assertEquals(NEUTRAL, ctx(h, 2, 0));
            assertEquals(NEUTRAL, ctx(h, 2, "#tail".length()));
            assertEquals(NEUTRAL, ctx(h, 1, 0));
        }
        finally {
            h.close();
        }
    }

    // Fix 2: with the backslash, line 1 is a continuation so the ) is
    // code and % matches it; after deleting the backslash, line 1 is a
    // fresh comment and % must find no match.
    @Test
    public void deleteBackslashThenMatchSeesFreshFlags() {
        // key = v ( \   # ) tail   -- ( at offset 8, backslash at 10.
        // No explicit refreshParse between delete and match: the
        // command path must refresh on its own.
        EditorHarness h = EditorHarness.create(
                "key = v ( \\\n# ) tail").mode(PropertiesMode.getMode());
        try {
            Editor.setCurrentEditor(h.editor());
            h.buffer().refreshParse();
            assertTrue((h.buffer().getLine(1).flags() & PropertiesFormatter.PROPERTIES_CONTINUATION) != 0);
            h.editor().setDot(h.buffer().getLine(0), 8);
            h.editor().moveCaretToDotCol();
            CaretCommands.findMatchingPair(h.editor());
            assertEquals(1, h.editor().getDotLineNumber());
            h.editor().setDot(h.buffer().getLine(0), 10);
            h.editor().moveCaretToDotCol();
            EditCommands.delete(h.editor());
            h.editor().setDot(h.buffer().getLine(0), 8);
            h.editor().moveCaretToDotCol();
            CaretCommands.findMatchingPair(h.editor());
            // No match: the dot stays put.
            assertEquals(0, h.editor().getDotLineNumber());
            assertEquals(8, h.editor().getDotOffset());
        }
        finally {
            h.close();
        }
    }

    @Test
    public void vimPercentRefreshesFlagsAfterDeletingBackslash() {
        assertVimMotionRefreshesFlags("%");
    }

    @Test
    public void vimUnmatchedBracketRefreshesFlagsAfterDeletingBackslash() {
        assertVimMotionRefreshesFlags("])");
    }

    private void assertVimMotionRefreshesFlags(String motion) {
        EditorHarness h = EditorHarness.create("key=( \\\n#continued )\nother=)").mode(PropertiesMode.getMode()).vim();
        try {
            h.buffer().refreshParse();
            h.cursor(0, 0).keys("$x");
            assertTrue(h.buffer().needsParsing());
            // Vim dispatches these motions directly to the matching
            // helpers, bypassing the findMatchingPair command entirely.
            h.cursor(0, 4).keys(motion);
            assertEquals(2, h.lineNumber());
            assertEquals(6, h.offset());
        }
        finally {
            h.close();
        }
    }

    // Fix 3: under Show Tabs the rendered text starts with the ^I
    // glyph; the classifier reads raw, so the # marker is still found.
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
                assertEquals(COMMENT, ctx(h, 0, 1));
                // Highlighting agrees: one comment segment.
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
