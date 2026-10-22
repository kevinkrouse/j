/*
 * CompareTest.java
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

package org.armedbear.j.mode.compare;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.armedbear.j.FoldCommands;
import org.armedbear.j.KeyMap;
import org.armedbear.j.KeyMapping;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.MotionCommands;
import org.armedbear.j.Position;
import org.armedbear.j.Preferences;
import org.armedbear.j.Property;
import org.armedbear.j.VirtualText;
import org.armedbear.j.WindowLayout;
import org.armedbear.j.mode.compare.DiffAlignment.Kind;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.text.PlainTextMode;
import org.armedbear.j.vcs.signs.EditorHunks.Side;
import org.armedbear.j.vim.VimRegisters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class CompareTest {
    private static final String OLD = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n";
    // b changed, three lines added after it, e and f removed.
    private static final String NEW = "a\nB\nx\ny\nz\nc\nd\ng\nh\ni\nj\n";

    private CompareBuffer diff;
    private EditorHarness left;
    private EditorHarness right;

    // What compareAlign was before aligned() set it, if it did.
    private boolean alignSet;
    private String alignWas;

    // The comparisons opened after this aligned, with filler rows.
    private void aligned() {
        alignWas = Editor.preferences().getStringProperty(Property.COMPARE_ALIGN.key());
        alignSet = true;
        Editor.preferences().setProperty(Property.COMPARE_ALIGN, "true");
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (alignSet)
            restore(Property.COMPARE_ALIGN, alignWas);
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
            () -> diff = CompareBuffer.open(null, JavaMode.getMode(), "Demo.java", "old", side(OLD), "new", side(NEW)));
    }

    private static CompareBuffer.Text side(String text) {
        final Side s = Side.of(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        return new CompareBuffer.Text(s.lines(), s.keys());
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

    @Test
    public void theTrackBandsEachChangeFromItsLinesAtTheLeftToItsLinesAtTheRight() throws Exception {
        aligned();
        windows();
        onEdt(() -> {
            final CompareTrack track = (CompareTrack) diff.besideDisplay(right.editor());
            assertSame(track, diff.besideDisplay(right.editor()), "one per window");
            final int ch = Display.getCharHeight();
            // b changed into B and three lines after it: one row at the left,
            // four at the right; then e and f removed, under d at the right,
            // which is three filler rows lower at the left.
            assertEquals(
                List.of(
                    new TrackGeometry.Band(Kind.CHANGED, ch, 2 * ch, ch, 5 * ch),
                    new TrackGeometry.Band(Kind.REMOVED, 7 * ch, 9 * ch, 7 * ch, 7 * ch)),
                track.bands(20 * ch));
            right.editor().getDisplay().setTopLine(diff.getLine(5));
            left.editor().getDisplay().setTopLine(diff.getOld().getLine(2));
            assertEquals(2 * ch, track.bands(20 * ch).get(1).leftTop(), "where the windows are scrolled to");
        });
    }

    @Test
    public void unalignedTheSidesHaveNoFillersAndABandsEdgesSlope() throws Exception {
        windows();
        onEdt(() -> {
            assertEquals(0, diff.getOld().getVirtualText().rowsBelow(diff.getOld().getLine(1)), "no fillers");
            final int ch = Display.getCharHeight();
            // e and f, old lines 4 and 5, removed under new line 6, d.
            final TrackGeometry.Band removed =
                    ((CompareTrack) diff.besideDisplay(right.editor())).bands(20 * ch).get(1);
            assertEquals(new TrackGeometry.Band(Kind.REMOVED, 4 * ch, 6 * ch, 7 * ch, 7 * ch), removed);
        });
    }

    // Sixty lines, twenty added after l29: new lines 30 to 49.
    private void openInserted() throws Exception {
        final StringBuilder a = new StringBuilder();
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            a.append('l').append(i).append('\n');
            b.append('l').append(i).append('\n');
            if (i == 29)
                for (int j = 0; j < 20; j++)
                    b.append('n').append(j).append('\n');
        }
        onEdt(
            () -> diff = CompareBuffer
                    .open(null, JavaMode.getMode(), "Ins.java", "old", side(a.toString()), "new", side(b.toString())));
        onEdt(() -> {
            diff.getFolds().setAllFolds(null, true);
            left = EditorHarness.create().rows(10);
            right = EditorHarness.create().rows(10);
            left.showBuffer(diff.getOld());
            right.showBuffer(diff);
            left.editor().setTopLine(diff.getOld().getFirstLine());
            right.editor().setTopLine(diff.getFirstLine());
        });
    }

    // The place at the middle of ed's window.
    private static double middle(Editor ed) {
        return CompareBuffer.geometryOf(ed).placeAt(CompareBuffer.y(ed) + ed.getDisplay().getHeight() / 2);
    }

    @Test
    public void unalignedScrollingOneSideTakesTheOthersMiddleThroughTheChanges() throws Exception {
        openInserted();
        onEdt(() -> {
            right.editor().getDisplay().setTopLine(diff.getLine(40));
            diff.scrolled(right.editor());
            // New 45, the middle, is in the added run, which old line 29 spans.
            final double expected = ScrollMap.of(diff.getAlignment().changes(), 60).toOld(middle(right.editor()));
            assertEquals(29 + 16 / 21.0, expected, 1e-9);
            assertEquals(expected, middle(left.editor()), 1.0 / Display.getCharHeight());
            assertEquals(24, left.editor().getDisplay().getTopLine().lineNumber(), "slower: 16 lines behind");
            right.editor().getDisplay().setTopLine(diff.getFirstLine());
            diff.scrolled(right.editor());
            assertEquals(0, CompareBuffer.y(left.editor()), "both at the top");
        });
    }

    @Test
    public void anUpdateKeepsTheCaretTheSelectionAndAnUnchangedSidesLines() throws Exception {
        windows();
        onEdt(() -> {
            final Editor ed = right.editor();
            ed.setDot(diff.getLine(5), 1);
            ed.setMark(new Position(diff.getLine(6), 0));
            final Line oldFirst = diff.getOld().getFirstLine();
            final CompareBuffer.Text newText = side(NEW.replace("j\n", "J\n"));
            diff.update(side(OLD), newText, DiffAlignment.of(side(OLD).keys(), newText.keys()));
            assertEquals(5, ed.getDotLineNumber());
            assertEquals(1, ed.getDotOffset(), "its column");
            assertNotNull(ed.getMark(), "the selection");
            assertEquals(6, ed.getMark().lineNumber());
            assertSame(oldFirst, diff.getOld().getFirstLine(), "the old side, unchanged, kept as it was");
            assertEquals("J", diff.getLine(10).getText());
        });
    }

    // Sixty lines, twenty added after l1: new lines 2 to 21; both windows
    // ten rows, at the top.
    private void openAddedNearTheTop() throws Exception {
        final StringBuilder a = new StringBuilder();
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            a.append('l').append(i).append('\n');
            b.append('l').append(i).append('\n');
            if (i == 1)
                for (int j = 0; j < 20; j++)
                    b.append('n').append(j).append('\n');
        }
        onEdt(
            () -> diff = CompareBuffer
                    .open(null, JavaMode.getMode(), "Top.java", "old", side(a.toString()), "new", side(b.toString())));
        onEdt(() -> {
            diff.getFolds().setAllFolds(null, true);
            left = EditorHarness.create().rows(10);
            right = EditorHarness.create().rows(10);
            left.showBuffer(diff.getOld());
            right.showBuffer(diff);
            left.editor().setDot(diff.getOld().getFirstLine(), 0);
            right.editor().setDot(diff.getFirstLine(), 0);
            left.editor().setTopLine(diff.getOld().getFirstLine());
            right.editor().setTopLine(diff.getFirstLine());
        });
    }

    @Test
    public void unalignedLeavingTheTopMovesTheOtherSideALittleNotAWindowsWorth() throws Exception {
        openAddedNearTheTop();
        onEdt(() -> {
            final int ch = Display.getCharHeight();
            // Two pixels down at the left: a few at the right, not the twenty
            // lines its middle would be past.
            left.editor().getDisplay().setPixelsAboveTopLine(2);
            diff.getOld().scrolled(left.editor());
            final int moved = CompareBuffer.y(right.editor());
            assertTrue(moved >= 0 && moved < ch, "two pixels at the left: " + moved + " at the right");
        });
    }

    @Test
    public void unalignedTheOtherCaretDoesNotPullItsWindowAway() throws Exception {
        openAddedNearTheTop();
        onEdt(() -> {
            // The left far down, the right where the lock puts it.
            left.editor().getDisplay().setTopLine(diff.getOld().getLine(40));
            diff.getOld().scrolled(left.editor());
            final Line rightTop = right.editor().getDisplay().getTopLine();
            left.editor().setDot(diff.getOld().getLine(1), 0);
            diff.getOld().afterCommand(left.editor());
            assertSame(rightTop, right.editor().getDisplay().getTopLine(), "not framed back to the top");
        });
    }

    @Test
    public void closingTheBottomFoldFromItsEndLeavesTheTopLineOutOfTheTopFold() throws Exception {
        // Three hundred lines, l30 changed: folds over l0 to l26 and l34 on.
        final StringBuilder a = new StringBuilder();
        for (int i = 0; i < 300; i++)
            a.append('l').append(i).append('\n');
        final String b = a.toString().replace("l30\n", "changed\n");
        onEdt(
            () -> diff = CompareBuffer
                    .open(null, JavaMode.getMode(), "Bottom.java", "old", side(a.toString()), "new", side(b)));
        final boolean ready = Editor.displayReady();
        Editor.setDisplayReady(true);
        try {
            onEdt(() -> {
                right = EditorHarness.create().rows(10);
                right.showBuffer(diff);
                final Editor ed = right.editor();
                diff.getFolds().setFoldAt(ed, diff.getLine(33), true);
                ed.setDot(diff.getLine(299), 0);
                ed.setTopLine(diff.getLine(290));
                // What is shown now fits the window: the reframe that takes the
                // caret to the fold scrolls back to the first line shown.
                diff.getFolds().setFoldAt(ed, diff.getLine(299), false);
                assertTrue(diff.getLine(299).isHidden(), "closed");
                assertFalse(ed.getDisplay().getTopLine().isHidden(), "not in the top fold");
                assertEquals(27, ed.getDisplay().getTopLine().lineNumber());
            });
        }
        finally {
            Editor.setDisplayReady(ready);
        }
    }

    // The twenty lines of openLong, with lines before l0 in the new side.
    private static CompareBuffer.Text longWith(String before) {
        final StringBuilder b = new StringBuilder(before);
        for (int i = 0; i < 20; i++)
            b.append(i == 10 ? "changed" : "l" + i).append('\n');
        return side(b.toString());
    }

    private static CompareBuffer.Text longOld() {
        final StringBuilder a = new StringBuilder();
        for (int i = 0; i < 20; i++)
            a.append('l').append(i).append('\n');
        return side(a.toString());
    }

    @Test
    public void aFoldOpenStaysOpenWhenAnEditAboveItMovesIt() throws Exception {
        openLong();
        onEdt(() -> {
            diff.getFolds().setFoldAt(null, diff.getLine(13), true);
            assertFalse(diff.getLine(15).isHidden(), "the bottom fold open");
            final CompareBuffer.Text edited = longWith("x\n");
            diff.update(longOld(), edited, DiffAlignment.of(longOld().keys(), edited.keys()));
            // Its lines one further down now.
            assertFalse(diff.getLine(16).isHidden(), "still open");
        });
    }

    @Test
    public void aSideCutShortFramesItsWindowAgain() throws Exception {
        windows();
        final boolean ready = Editor.displayReady();
        Editor.setDisplayReady(true);
        try {
            onEdt(() -> {
                right.editor().setDot(diff.getLine(9), 0);
                right.editor().setTopLine(diff.getLine(8));
                final CompareBuffer.Text cut = side("a\nB\n");
                diff.update(side(OLD), cut, DiffAlignment.of(side(OLD).keys(), cut.keys()));
                assertEquals(0, right.editor().getDisplay().getTopLine().lineNumber(), "not left on its last line");
            });
        }
        finally {
            Editor.setDisplayReady(ready);
        }
    }

    @Test
    public void anEditAboveTheViewLeavesTheUnchangedSideWhereItIs() throws Exception {
        openInserted();
        onEdt(() -> {
            left.editor().getDisplay().setTopLine(diff.getOld().getLine(40));
            diff.getOld().scrolled(left.editor());
            final Line leftTop = left.editor().getDisplay().getTopLine();
            final StringBuilder b = new StringBuilder("x\ny\nz\n");
            for (int i = 0; i < 60; i++) {
                b.append('l').append(i).append('\n');
                if (i == 29)
                    for (int j = 0; j < 20; j++)
                        b.append('n').append(j).append('\n');
            }
            final StringBuilder a = new StringBuilder();
            for (int i = 0; i < 60; i++)
                a.append('l').append(i).append('\n');
            final CompareBuffer.Text edited = side(b.toString());
            diff.update(side(a.toString()), edited, DiffAlignment.of(side(a.toString()).keys(), edited.keys()));
            assertSame(leftTop, left.editor().getDisplay().getTopLine(), "the old side, unchanged, not dragged");
        });
    }

    @Test
    public void theTrackHasNoBandsWithoutTheOldSidesWindow() throws Exception {
        open();
        onEdt(() -> {
            right = EditorHarness.create().rows(5);
            right.showBuffer(diff);
            right.editor().setTopLine(diff.getFirstLine());
            assertEquals(List.of(), ((CompareTrack) diff.besideDisplay(right.editor())).bands(1000));
            assertNull(diff.getOld().besideDisplay(right.editor()), "only the new side's windows have one");
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
        aligned();
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
            assertEquals("compareQuit", diff.getKeyMapForMode().getKeyMapping("compareQuit").getCommand());
            assertTrue(
                diff.getKeyMapForMode().getMappings().length > JavaMode.getMode().getKeyMap(diff).getMappings().length
                        - 1,
                "the file's mode's keys still, and these");
            assertNotNull(diff.getKeyMapForMode().getKeyMapping("nextChange"));
        });
    }

    @Test
    public void scrollingOneSideScrollsTheOtherToTheSameRow() throws Exception {
        aligned();
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
            // As the dispatcher does: the display, which reports the scroll, then the command's end.
            left.editor().updateDisplay();
            diff.getOld().afterCommand(left.editor());
            assertEquals("d", right.editor().getDotLine().getText(), "f, a filler at the right: d");
        });
    }

    @Test
    public void anUpdateThatDoesNotScrollLeavesTheOtherSideWhereItIs() throws Exception {
        windows();
        onEdt(() -> {
            left.editor().setTopLine(diff.getOld().getLine(4));
            right.editor().updateScrollBars();
            assertEquals(4, left.editor().getDisplay().getTopLine().lineNumber());
            right.editor().setTopLine(diff.getLine(1));
            right.editor().updateScrollBars();
            assertNotEquals(4, left.editor().getDisplay().getTopLine().lineNumber(), "a scroll moves it");
        });
    }

    @Test
    public void qTypedInASideClosesTheDiff() throws Exception {
        windows();
        // q, as typed in the right side's window. Its windows, which have no
        // frame here, can't be switched off it, so it is only closed.
        onEdt(() -> Editor.setCurrentEditor(right.editor()));
        right.keys("q");
        onEdt(() -> assertTrue(diff.isClosed(), "q ran compareQuit"));
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
    public void unalignedARuleMarksWhereTheOtherSidesLinesAre() throws Exception {
        open();
        onEdt(() -> {
            final SideFormatter left = (SideFormatter) diff.getOld().getFormatter();
            final SideFormatter right = (SideFormatter) diff.getFormatter();
            assertEquals(right.edge(Kind.REMOVED), right.getLineRule(diff.getLine(7), false), "over g: e f");
            assertNull(right.getLineRule(diff.getLine(6), true), "not under d too");
            assertNull(left.getLineRule(diff.getOld().getLine(4), false), "e, on its own side: none");
            assertNull(left.getLineRule(diff.getOld().getLine(2), false), "b to B x y z, both sided: none");
        });
    }

    @Test
    public void unalignedARuleUnderTheLastLineOrOverAnEmptySidesBlankLine() throws Exception {
        onEdt(() -> {
            diff = CompareBuffer.open(null, JavaMode.getMode(), "End.java", "old", side("a\n"), "new", side("a\nb\n"));
            final SideFormatter f = (SideFormatter) diff.getOld().getFormatter();
            assertEquals(f.edge(Kind.ADDED), f.getLineRule(diff.getOld().getLine(0), true), "under a");
            assertNull(f.getLineRule(diff.getOld().getLine(0), false));
            diff.kill();
            diff = CompareBuffer.open(null, JavaMode.getMode(), "New.java", "old", side(""), "new", side("a\n"));
            assertNotNull(diff.getOld().getFormatter().getLineRule(diff.getOld().getFirstLine(), false));
        });
    }

    @Test
    public void alignedFillersInPlaceOfRules() throws Exception {
        aligned();
        open();
        onEdt(() -> assertNull(diff.getOld().getFormatter().getLineRule(diff.getOld().getLine(2), false)));
    }

    @Test
    public void anEmptySideIsAsTallAsTheOther() throws Exception {
        aligned();
        onEdt(() -> {
            diff = CompareBuffer.open(null, JavaMode.getMode(), "New.java", "old", side(""), "new", side("a\nb\nc\n"));
            assertEquals(diff.getDisplayHeight(), diff.getOld().getDisplayHeight(), "its blank line a filler");
            assertNotNull(diff.getOld().getFormatter().getLineBackground(diff.getOld().getFirstLine()));
        });
    }

    @Test
    public void theCaretInALongAddedRunLeavesTheOthersWhereItIsOutOfView() throws Exception {
        aligned();
        final StringBuilder added = new StringBuilder("a\n");
        for (int i = 0; i < 30; i++)
            added.append("new").append(i).append('\n');
        added.append("b\n");
        onEdt(() -> {
            diff = CompareBuffer
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
        final CompareBuffer first = diff;
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
        final CompareBuffer first = diff;
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
        assertEquals("compareQuit", keys.over(map, false).lookup('q', 0, 0).getCommand());
    }

    @Test
    public void aChangedLinesWordsThatDifferAreSpans() throws Exception {
        onEdt(() -> {
            diff = CompareBuffer.open(
                null,
                JavaMode.getMode(),
                "Words.java",
                "old",
                side("int width = 1;\nkeep\n"),
                "new",
                side("int height = 1;\nkeep\n"));
            assertArrayEquals(new int[] { 4, 9 }, diff.getOld().getFormatter().getSpans(diff.getOld().getFirstLine()));
            assertArrayEquals(new int[] { 4, 10 }, diff.getFormatter().getSpans(diff.getFirstLine()));
            assertNotNull(diff.getFormatter().getSpanColor(diff.getFirstLine()));
            assertNull(diff.getFormatter().getSpans(diff.getLine(1)), "an unchanged line has none");
        });
    }

    @Test
    public void eachSideIsParsedBeforeItIsShown() throws Exception {
        onEdt(() -> {
            diff = CompareBuffer.open(
                null,
                JavaMode.getMode(),
                "Comment.java",
                "old",
                side("/*\n * a comment\n */\nclass A {}\n"),
                "new",
                side("/*\n * a comment\n */\nclass B {}\n"));
            // A comment's middle line is formatted as one from the start,
            // not as code until the idle parse.
            assertFalse(diff.needsParsing());
            assertFalse(diff.getOld().needsParsing());
            assertTrue(diff.getLine(1).flags() != 0, "in the comment");
            assertTrue(diff.getOld().getLine(1).flags() != 0, "in the comment");
        });
    }

    // Twenty lines, l10 changed: an unchanged run before it and one after.
    private void openLong() throws Exception {
        final StringBuilder a = new StringBuilder();
        for (int i = 0; i < 20; i++)
            a.append('l').append(i).append('\n');
        final String b = a.toString().replace("l10\n", "changed\n");
        onEdt(
            () -> diff = CompareBuffer
                    .open(null, JavaMode.getMode(), "Long.java", "old", side(a.toString()), "new", side(b)));
    }

    // Thirty lines, l2 and l25 changed: l6 to l21 folded under l5.
    private void openTwoChanges() throws Exception {
        final StringBuilder a = new StringBuilder();
        for (int i = 0; i < 30; i++)
            a.append('l').append(i).append('\n');
        final String b = a.toString().replace("l2\n", "x\n").replace("l25\n", "y\n");
        onEdt(
            () -> diff = CompareBuffer
                    .open(null, JavaMode.getMode(), "Two.java", "old", side(a.toString()), "new", side(b)));
    }

    @Test
    public void zaInAMiddleFoldClosesItWithTheCaretWhereZaOpensItAgain() throws Exception {
        openTwoChanges();
        onEdt(() -> {
            right = EditorHarness.create().rows(5);
            right.showBuffer(diff);
            right.vim();
        });
        inVimPrefs(() -> {
            onEdt(() -> right.editor().setDot(diff.getLine(5), 0));
            right.keys("za");
            onEdt(() -> {
                assertFalse(diff.getLine(12).isHidden(), "open");
                right.editor().setDot(diff.getLine(12), 0);
            });
            right.keys("za");
            onEdt(() -> {
                assertTrue(diff.getLine(12).isHidden(), "closed");
                assertEquals(5, right.editor().getDotLineNumber(), "on the line its count is on");
            });
            right.keys("za");
            onEdt(() -> assertFalse(diff.getLine(12).isHidden(), "open again"));
        });
    }

    @Test
    public void vimsGWithACountIntoAFoldStopsAtItsCount() throws Exception {
        inVim(() -> {
            right.keys("3G");
            onEdt(() -> assertEquals(7, right.editor().getDotLineNumber(), "the top fold's count is under it"));
            right.keys("9G");
            onEdt(() -> assertEquals(8, right.editor().getDotLineNumber()));
            right.keys("18G");
            onEdt(() -> assertEquals(13, right.editor().getDotLineNumber(), "the bottom fold's is over it"));
        });
    }

    @Test
    public void vimsColonNIntoAFoldStopsAtItsCount() throws Exception {
        inVim(() -> {
            right.exCommand("3");
            onEdt(() -> {
                assertEquals(7, right.editor().getDotLineNumber());
                assertEquals("hhhhhhh.......hhhhhh", hidden(diff), "still closed");
            });
        });
    }

    @Test
    public void jumpToLineIntoAFoldStopsAtItsCount() throws Exception {
        openLong();
        onEdt(() -> {
            right = EditorHarness.create().rows(5);
            right.showBuffer(diff);
            right.editor().jumpToLine(16);
            right.editor().updateDisplay();
            assertEquals(13, right.editor().getDotLineNumber());
            assertEquals("hhhhhhh.......hhhhhh", hidden(diff), "still closed");
        });
    }

    @Test
    public void vimsYggTakesInTheFoldedLines() throws Exception {
        inVim(() -> {
            onEdt(() -> right.editor().setDot(diff.getLine(10), 0));
            right.keys("ygg");
            onEdt(() -> {
                final String yanked = VimRegisters.getInstance().get('"').text();
                assertTrue(yanked.startsWith("l0\n"), yanked);
                assertEquals(11, yanked.split("\n").length);
            });
        });
    }

    @Test
    public void aFoldOpenStaysOpenWhenTheModeChanges() throws Exception {
        openLong();
        onEdt(() -> {
            diff.getFolds().setFoldAt(null, diff.getLine(7), true);
            assertFalse(diff.getLine(0).isHidden());
            diff.modeTo(PlainTextMode.getMode());
            assertFalse(diff.getLine(0).isHidden(), "still open");
            assertTrue(diff.getLine(15).isHidden(), "the other still closed");
        });
    }

    @Test
    public void aRenumberingThatChangesNoFoldLeavesTheLabels() throws Exception {
        openLong();
        onEdt(() -> {
            final VirtualText before = diff.getVirtualText().on(diff.getLine(13), VirtualText.Placement.EOL).get(0);
            diff.renumber();
            assertSame(before, diff.getVirtualText().on(diff.getLine(13), VirtualText.Placement.EOL).get(0));
        });
    }

    private static String hidden(Buffer b) {
        final StringBuilder sb = new StringBuilder();
        for (Line l = b.getFirstLine(); l != null; l = l.next())
            sb.append(l.isHidden() ? 'h' : '.');
        return sb.toString();
    }

    private static String label(Buffer b, Line at, VirtualText.Placement placement) {
        final List<VirtualText> on = b.getVirtualText().on(at, placement);
        return on.isEmpty() ? null : on.get(0).rows().get(0).get(0).text();
    }

    @Test
    public void aFoldsLabelHasTheFoldSymbolPreferencesSymbol() throws Exception {
        final Preferences prefs = Editor.preferences();
        final String was = prefs.getStringProperty(Property.FOLD_SYMBOL.key());
        prefs.setProperty(Property.FOLD_SYMBOL, "\u22ef");
        try {
            openLong();
            onEdt(() -> assertEquals("\u22ef 6 lines", label(diff, diff.getLine(13), VirtualText.Placement.EOL)));
        }
        finally {
            restore(Property.FOLD_SYMBOL, was);
        }
    }

    @Test
    public void unchangedRunsAreFoldedOnBothSidesButForTheContext() throws Exception {
        openLong();
        onEdt(() -> {
            // l0 to l6 and l14 to l19 hidden; three lines kept each side of l10.
            final String folded = "hhhhhhh.......hhhhhh";
            assertEquals(folded, hidden(diff));
            assertEquals(folded, hidden(diff.getOld()));
            assertEquals(
                "\u2026 7 lines above",
                label(diff, diff.getLine(7), VirtualText.Placement.EOL),
                "at the top, after the line under it");
            assertEquals("\u2026 6 lines", label(diff, diff.getLine(13), VirtualText.Placement.EOL));
            assertEquals("\u2026 6 lines", label(diff.getOld(), diff.getOld().getLine(13), VirtualText.Placement.EOL));
        });
    }

    @Test
    public void tabOrZaOpensAndClosesAFoldOnBothSides() throws Exception {
        openLong();
        onEdt(() -> {
            right = EditorHarness.create().rows(5);
            right.showBuffer(diff);
            right.editor().setDot(diff.getLine(13), 0);
            FoldCommands.toggleFold(right.editor());
            assertEquals("hhhhhhh.............", hidden(diff));
            assertEquals("hhhhhhh.............", hidden(diff.getOld()), "the old side's too");
            assertNull(label(diff.getOld(), diff.getOld().getLine(13), VirtualText.Placement.EOL), "its label gone");
            assertTrue(diff.getFolds().isFoldStart(diff, diff.getLine(13)), "its marker kept, open");
            assertFalse(diff.getFolds().isFolded(diff.getLine(13)));
            // zc in it closes it again, on both.
            right.editor().setDot(diff.getLine(16), 0);
            FoldCommands.fold(right.editor());
            assertEquals("hhhhhhh.......hhhhhh", hidden(diff.getOld()));
            assertEquals(13, right.editor().getDotLineNumber(), "the caret on the line over it");
            // The one at the top, at the line under it.
            right.editor().setDot(diff.getLine(7), 0);
            FoldCommands.toggleFold(right.editor());
            assertEquals("..............hhhhhh", hidden(diff.getOld()));
        });
    }

    @Test
    public void theFilesModesFoldsAreNotTheDiffs() throws Exception {
        openLong();
        onEdt(() -> {
            left = EditorHarness.create().rows(5);
            left.showBuffer(diff.getOld());
            left.editor().setDot(diff.getOld().getLine(8), 0);
            // A fold made as the mode would, on one side: put back.
            FoldCommands.hideLines(left.editor(), diff.getOld().getLine(8), diff.getOld().getLine(10));
            assertEquals("hhhhhhh.......hhhhhh", hidden(diff.getOld()));
            assertEquals("hhhhhhh.......hhhhhh", hidden(diff));
            assertFalse(diff.getOld().getFolds().isFoldStart(diff.getOld(), diff.getOld().getLine(8)));
            // A click past a closed fold's line opens it on both, as unfold does.
            FoldCommands.unfold(left.editor(), diff.getOld().getLine(15));
            assertEquals("hhhhhhh.............", hidden(diff));
            // zR and zM, on both.
            FoldCommands.unfoldAll(left.editor());
            assertEquals("....................", hidden(diff));
            FoldCommands.foldAll(left.editor());
            assertEquals("hhhhhhh.......hhhhhh", hidden(diff));
        });
    }

    @Test
    public void nothingIsFoldedWhereNothingChanged() throws Exception {
        onEdt(() -> {
            diff = CompareBuffer.open(
                null,
                JavaMode.getMode(),
                "Same.java",
                "old",
                side("a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n"),
                "new",
                side("a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n"));
            assertEquals("..........", hidden(diff));
        });
    }

    @Test
    public void itsWindowsOpenOnLinesShownNotInTheTopFold() throws Exception {
        openLong();
        onEdt(() -> {
            for (WindowLayout.Window w : WindowLayout.windows(diff.layout(null, diff))) {
                assertEquals(7, w.dotLine(), "under the top fold, which a caret in would open");
                assertEquals(7, w.topLine());
            }
        });
    }

    @Test
    public void vimsZcOnTheFirstLineClosesTheTopFoldOnBothSides() throws Exception {
        inVim(this::zcOnTheFirstLine);
    }

    @Test
    public void vimsGgAndGStopAtTheFoldsWithoutOpeningThem() throws Exception {
        inVim(() -> {
            right.keys("G");
            onEdt(() -> assertEquals(13, right.editor().getDotLineNumber()));
            right.keys("gg");
            onEdt(() -> {
                assertEquals(7, right.editor().getDotLineNumber());
                assertEquals("hhhhhhh.......hhhhhh", hidden(diff));
            });
        });
    }

    @Test
    public void bobAndEobStopAtTheFoldsWithoutOpeningThem() throws Exception {
        openLong();
        onEdt(() -> {
            right = EditorHarness.create().rows(5);
            right.showBuffer(diff);
            final Editor ed = right.editor();
            MotionCommands.eob(ed);
            ed.updateDisplay();
            assertEquals(13, ed.getDotLineNumber());
            MotionCommands.bob(ed);
            ed.updateDisplay();
            assertEquals(7, ed.getDotLineNumber());
            assertEquals("hhhhhhh.......hhhhhh", hidden(diff));
        });
    }

    // property as it was set in the prefs, or not.
    private static void restore(Property property, String was) {
        if (was == null)
            Editor.preferences().removeProperty(property.key());
        else
            Editor.preferences().setProperty(property.key(), was);
    }

    private interface Steps {
        void run() throws Exception;
    }

    // steps in vim, both sides in windows, the caret under the top fold.
    private void inVim(Steps steps) throws Exception {
        openLong();
        onEdt(() -> {
            left = EditorHarness.create().rows(5);
            right = EditorHarness.create().rows(5);
            left.showBuffer(diff.getOld());
            right.showBuffer(diff);
            right.vim();
            right.editor().setDot(diff.getLine(7), 0);
        });
        inVimPrefs(steps);
    }

    // steps with vim the edit mode.
    private static void inVimPrefs(Steps steps) throws Exception {
        final Preferences prefs = Editor.preferences();
        final String was = prefs.getStringProperty(Property.EDIT_MODE.key());
        prefs.setProperty(Property.EDIT_MODE, "vim");
        try {
            steps.run();
        }
        finally {
            restore(Property.EDIT_MODE, was);
        }
    }

    private void zcOnTheFirstLine() throws Exception {
        right.keys("zo");
        onEdt(
            () -> assertEquals(
                "..............hhhhhh",
                hidden(diff),
                "open: " + right.status() + " at " + right.editor().getDotLineNumber()));
        right.keys("gg");
        onEdt(() -> assertEquals(0, right.editor().getDotLineNumber()));
        right.keys("zc");
        onEdt(() -> {
            assertEquals("hhhhhhh.......hhhhhh", hidden(diff.getOld()));
            assertEquals("hhhhhhh.......hhhhhh", hidden(diff), "the right too");
            assertEquals(7, right.editor().getDotLineNumber(), "the caret under it");
        });
    }
}
