/*
 * SideBySideTest.java
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

package org.armedbear.j.vcs.sidebyside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Constants;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.KeyMap;
import org.armedbear.j.KeyMapping;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.vcs.signs.EditorHunks.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class SideBySideTest {
    private static final String OLD = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n";
    // b changed, three lines added after it, e and f removed.
    private static final String NEW = "a\nB\nx\ny\nz\nc\nd\ng\nh\ni\nj\n";

    private SideBySideBuffer diff;
    private EditorHarness left;
    private EditorHarness right;

    @AfterEach
    public void tearDown() throws Exception {
        onEdt(() -> {
            if (left != null) {
                left.showBuffer(left.buffer());
                left.close();
            }
            if (right != null) {
                right.showBuffer(right.buffer());
                right.close();
            }
            if (diff != null && Editor.getBufferList().contains(diff))
                diff.kill();
        });
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private void open() throws Exception {
        onEdt(
            () -> diff =
                    SideBySideBuffer.open(null, JavaMode.getMode(), "Demo.java", "old", side(OLD), "new", side(NEW)));
    }

    private static SideBySideBuffer.Text side(String text) {
        final Side s = Side.of(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        return new SideBySideBuffer.Text(s.lines(), s.keys());
    }

    // The two sides each in a window, as across a frame: here none, so both
    // in no frame, which is the same one.
    private void windows() throws Exception {
        open();
        onEdt(() -> {
            left = EditorHarness.create().rows(5);
            right = EditorHarness.create().rows(5);
            left.showBuffer(diff.getOld());
            right.showBuffer(diff);
            left.editor().setDot(diff.getOld().getFirstLine(), 0);
            right.editor().setDot(diff.getFirstLine(), 0);
            left.editor().setTopLine(diff.getOld().getFirstLine());
            right.editor().setTopLine(diff.getFirstLine());
        });
    }

    private static List<String> text(Buffer b) {
        final List<String> out = new ArrayList<>();
        for (Line l = b.getFirstLine(); l != null; l = l.next())
            out.add(l.getText());
        return out;
    }

    @Test
    public void eachSideIsItsTextInTheFilesModeWithFillersWhereTheOtherHasMore() throws Exception {
        open();
        onEdt(() -> {
            assertEquals(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"), text(diff.getOld()));
            assertEquals(List.of("a", "B", "x", "y", "z", "c", "d", "g", "h", "i", "j"), text(diff));
            assertSame(JavaMode.getMode(), diff.getMode(), "the file's colors");
            assertSame(JavaMode.getMode(), diff.getOld().getMode());
            // Three rows under b at the left; two under d at the right.
            assertEquals(3, diff.getOld().getVirtualText().rowsBelow(diff.getOld().getLine(1)));
            assertEquals(2, diff.getVirtualText().rowsBelow(diff.getLine(6)));
            // Both as tall, row for row.
            assertEquals(diff.getOld().getDisplayHeight(), diff.getDisplayHeight());
            assertTrue(diff.isReadOnly() && diff.getOld().isReadOnly());
        });
    }

    @Test
    public void aChangesLinesAreShadedOnTheirSide() throws Exception {
        open();
        onEdt(() -> {
            final SideFormatter left = (SideFormatter) diff.getOld().getFormatter();
            final SideFormatter right = (SideFormatter) diff.getFormatter();
            assertNull(left.getLineBackground(diff.getOld().getLine(0)), "a: the same");
            assertNotNull(left.getLineBackground(diff.getOld().getLine(1)), "b, changed");
            assertEquals(
                left.getLineBackground(diff.getOld().getLine(1)),
                right.getLineBackground(diff.getLine(1)),
                "changed, alike on both sides");
            assertNotNull(right.getLineBackground(diff.getLine(2)), "x, added");
            assertNotNull(left.getLineBackground(diff.getOld().getLine(4)), "e, removed");
            assertFalse(
                right.getLineBackground(diff.getLine(2)).equals(left.getLineBackground(diff.getOld().getLine(4))),
                "added and removed apart");
        });
    }

    @Test
    public void nAndPGoByChangeAndQIsAKeyOverTheFilesModes() throws Exception {
        open();
        onEdt(() -> {
            assertEquals(
                List.of(diff.getLine(1), diff.getLine(6)),
                diff.getChangeStarts(),
                "B; then d, before the removed");
            assertEquals(List.of(diff.getOld().getLine(1), diff.getOld().getLine(4)), diff.getOld().getChangeStarts());
            assertEquals("vcsDiffQuit", diff.getKeyMapForMode().getKeyMapping("vcsDiffQuit").getCommand());
            assertTrue(
                diff.getKeyMapForMode().getMappings().length > JavaMode.getMode().getKeyMap(diff).getMappings().length
                        - 1,
                "the file's mode's keys still, and these");
            assertNotNull(diff.getKeyMapForMode().getKeyMapping("nextChange"));
        });
    }

    @Test
    public void scrollingOneSideScrollsTheOtherToTheSameRow() throws Exception {
        windows();
        onEdt(() -> {
            final int ch = Display.getCharHeight();
            left.editor().getDisplay().scrollByPixels(6 * ch);
            left.editor().updateScrollBars();
            // Row 6 at the top of both: a, b and its three fillers, c, then d at each.
            assertEquals("d", left.editor().getDisplay().getTopLine().getText());
            assertEquals("d", right.editor().getDisplay().getTopLine().getText());
            right.editor().getDisplay().scrollByPixels(-2 * ch);
            right.editor().updateScrollBars();
            assertEquals(
                diff.getY(right.editor().getDisplay().getTopLine())
                        + right.editor().getDisplay().getPixelsAboveTopLine(),
                diff.getOld().getY(left.editor().getDisplay().getTopLine())
                        + left.editor().getDisplay().getPixelsAboveTopLine(),
                "level again");
        });
    }

    @Test
    public void theCaretOnOneSideTakesTheOthersToItsRow() throws Exception {
        windows();
        onEdt(() -> {
            right.editor().setDot(diff.getLine(5), 0);
            diff.afterCommand(right.editor());
            assertEquals("c", left.editor().getDotLine().getText(), "c at both");
            right.editor().setDot(diff.getLine(3), 0);
            diff.afterCommand(right.editor());
            assertEquals("b", left.editor().getDotLine().getText(), "y, a filler at the left: the line over it");
            // f in view at the left, as the caret's line is: d at the top, at row 6.
            left.editor().setTopLine(diff.getOld().getLine(3));
            left.editor().setDot(diff.getOld().getLine(5), 0);
            diff.getOld().afterCommand(left.editor());
            assertEquals("d", right.editor().getDotLine().getText(), "f, a filler at the right: d");
        });
    }

    @Test
    public void qTypedInASideClosesTheDiff() throws Exception {
        windows();
        // q, as typed in the right side's window. Its windows, which have no
        // frame here, can't be switched off it, so it is only closed.
        onEdt(() -> Editor.setCurrentEditor(right.editor()));
        right.keys("q");
        onEdt(() -> assertTrue(diff.isClosed(), "q ran vcsDiffQuit"));
    }

    @Test
    public void closingRemovesBothSides() throws Exception {
        open();
        onEdt(() -> {
            diff.close(null);
            assertFalse(Editor.getBufferList().contains(diff));
            assertFalse(Editor.getBufferList().contains(diff.getOld()));
        });
    }

    @Test
    public void inVimNAndPAreItsSearchsAndTheChangeKeysBracketC() throws Exception {
        open();
        onEdt(() -> {
            final KeyMapping n =
                    diff.getModalKeyMapForMode() == null ? null : diff.getModalKeyMapForMode().lookup('n', 0, 0);
            assertTrue(n == null || !"nextChange".equals(n.getCommand()), "n is vim's");
            assertEquals("nextChange", diff.getKeyMapForMode().lookup('n', 0, 0).getCommand(), "outside vim, n");
        });
    }

    @Test
    public void anEmptySideIsAsTallAsTheOther() throws Exception {
        onEdt(() -> {
            diff = SideBySideBuffer
                    .open(null, JavaMode.getMode(), "New.java", "old", side(""), "new", side("a\nb\nc\n"));
            assertEquals(diff.getDisplayHeight(), diff.getOld().getDisplayHeight(), "its blank line a filler");
            assertNotNull(diff.getOld().getFormatter().getLineBackground(diff.getOld().getFirstLine()));
        });
    }

    @Test
    public void theCaretInALongAddedRunLeavesTheOthersWhereItIsOutOfView() throws Exception {
        final StringBuilder added = new StringBuilder("a\n");
        for (int i = 0; i < 30; i++)
            added.append("new").append(i).append('\n');
        added.append("b\n");
        onEdt(() -> {
            diff = SideBySideBuffer
                    .open(null, JavaMode.getMode(), "Long.java", "old", side("a\nb\n"), "new", side(added.toString()));
            left = EditorHarness.create().rows(5);
            right = EditorHarness.create().rows(5);
            left.showBuffer(diff.getOld());
            right.showBuffer(diff);
            left.editor().setDot(diff.getOld().getLine(1), 0);
            right.editor().setDot(diff.getFirstLine(), 0);
            left.editor().setTopLine(diff.getOld().getFirstLine());
            right.editor().setTopLine(diff.getFirstLine());
            // Far down the added lines: a, at the left, is far above the rows shown.
            right.editor().setDot(diff.getLine(25), 0);
            right.editor().setTopLine(diff.getLine(23));
            right.editor().updateScrollBars();
            diff.afterCommand(right.editor());
            assertEquals("b", left.editor().getDotLine().getText(), "left where it was, not taken to a, out of view");
        });
    }

    @Test
    public void openingItAgainKeepsItsWindowsAtTheirLines() throws Exception {
        windows();
        final SideBySideBuffer first = diff;
        onEdt(() -> {
            right.editor().setDot(diff.getLine(6), 0);
            right.editor().moveCaretToDotCol();
            diff.afterCommand(right.editor());
        });
        open();
        onEdt(() -> {
            assertSame(first, diff, "the diff open for the key, shown again");
            assertEquals(6, right.editor().getDotLineNumber());
            assertNotNull(right.editor().getDisplay().getTopLine());
            assertNotNull(left.editor().getDot(), "the left's caret placed too");
            assertNotNull(left.editor().getDisplay().getTopLine());
        });
    }

    @Test
    public void changingASidesModeChangesBothAndKeepsTheShading() throws Exception {
        open();
        onEdt(() -> {
            final Mode plain = Editor.getModeList().getMode(Constants.PLAIN_TEXT_MODE);
            diff.getOld().changeMode(plain);
            assertSame(plain, diff.getMode());
            assertSame(plain, diff.getOld().getMode());
            assertTrue(diff.getFormatter() instanceof SideFormatter);
            assertTrue(diff.getOld().getFormatter() instanceof SideFormatter);
        });
        final SideBySideBuffer first = diff;
        open();
        onEdt(() -> {
            assertSame(first, diff, "in Java mode again, not a second diff");
            assertSame(JavaMode.getMode(), diff.getOld().getMode());
        });
    }

    @Test
    public void aKeyRebound() {
        final KeyMap map = new KeyMap();
        map.mapKey('x', "one");
        final SideKeys keys = new SideKeys();
        assertEquals("one", keys.over(map, false).lookup('x', 0, 0).getCommand());
        map.mapKey('x', "two");
        assertEquals("two", keys.over(map, false).lookup('x', 0, 0).getCommand(), "the copy made again");
        assertEquals("vcsDiffQuit", keys.over(map, false).lookup('q', 0, 0).getCommand());
    }
}
