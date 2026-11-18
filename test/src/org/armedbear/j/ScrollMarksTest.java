/*
 * ScrollMarksTest.java
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.armedbear.j.ScrollMarks.Kind;
import org.armedbear.j.ScrollMarks.Mark;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Marks on the vertical scroll bar: where they are, and when they are made again. */
public class ScrollMarksTest {
    private EditorHarness h;
    private int ch;

    @AfterEach
    public void tearDown() {
        Editor.setTemporaryMarker(null);
        if (h != null)
            h.close();
    }

    private void lines(int n) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
            sb.append("line ").append(i).append('\n');
        h = EditorHarness.create(sb.toString()).rows(10);
        ch = Display.getCharHeight();
    }

    private Line line(int n) {
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < n; i++)
            l = l.next();
        return l;
    }

    private ScrollMarks marks() {
        return ScrollMarks.build(h.editor());
    }

    private static String describe(List<Mark> marks) {
        final StringBuilder sb = new StringBuilder();
        for (Mark m : marks)
            sb.append(sb.length() == 0 ? "" : " ")
                    .append(m.kind())
                    .append('@')
                    .append(m.lineNumber())
                    .append('+')
                    .append(m.lines());
        return sb.toString();
    }

    private void signs(Object... kindAndLine) {
        final Map<Line, Signs.Sign> map = new IdentityHashMap<>();
        for (int i = 0; i < kindAndLine.length; i += 2)
            map.put(line((Integer) kindAndLine[i + 1]), new Signs.Sign((Signs.Kind) kindAndLine[i], false, false));
        h.buffer().setSigns(new Signs(map, List.of()));
    }

    @Test
    public void bookmarksAtTheirLinesAndOffsets() {
        lines(20);
        Editor.setBookmark('1', new Marker(h.buffer(), new Position(line(4), 2)));
        Editor.setBookmark('B', new Marker(h.buffer(), new Position(line(4), 5)));
        final List<Mark> marks = marks().marks;
        assertEquals("BOOKMARK@4+1 BOOKMARK@4+1", describe(marks));
        assertEquals(4 * ch, marks.get(0).y());
        assertEquals(2, marks.get(0).offset(), "each its own offset, on the same line");
        assertEquals(5, marks.get(1).offset());
        assertEquals("Bookmark B", marks.get(1).text());
    }

    @Test
    public void anotherBuffersBookmarkIsNotMarked() {
        lines(5);
        final Buffer other = new Buffer(File.getInstance(System.getProperty("java.io.tmpdir") + "/j-scroll-other.txt"));
        other.setText("x\n");
        Editor.setBookmark('2', new Marker(other, new Position(other.getFirstLine(), 0)));
        assertEquals("", describe(marks().marks));
    }

    @Test
    public void consecutiveSignsOfAKindAreOneSpan() {
        lines(20);
        signs(Signs.Kind.ADDED, 3, Signs.Kind.ADDED, 4, Signs.Kind.CHANGED, 5, Signs.Kind.DELETED, 9);
        final List<Mark> marks = marks().marks;
        assertEquals("ADDED@3+2 CHANGED@5+1 DELETED@9+1", describe(marks));
        assertEquals(2 * ch, marks.get(0).height());
        assertEquals(10 * ch, marks.get(2).y(), "a deletion at the boundary under its line");
        assertEquals(0, marks.get(2).height());
    }

    @Test
    public void aSpanEndingInAFoldKeepsItsShownLines() {
        lines(20);
        signs(Signs.Kind.ADDED, 2, Signs.Kind.ADDED, 3, Signs.Kind.ADDED, 4);
        FoldCommands.hideLines(h.editor(), line(3), line(5));
        final Mark span = marks().marks.get(0);
        assertEquals("ADDED@2+3", describe(List.of(span)));
        assertEquals(ch, span.height(), "line 2's, the fold's");
    }

    @Test
    public void noChangeMarksWhenTheGutterShowsNone() {
        lines(10);
        signs(Signs.Kind.ADDED, 3);
        h.buffer().setProperty(Property.SHOW_CHANGE_MARKS, false);
        assertEquals("", describe(marks().marks));
    }

    @Test
    public void aHiddenLineIsMarkedAtItsFold() {
        lines(20);
        FoldCommands.hideLines(h.editor(), line(4), line(8));
        Editor.setBookmark('1', new Marker(h.buffer(), new Position(line(6), 0)));
        Editor.setBookmark('2', new Marker(h.buffer(), new Position(line(10), 0)));
        final ScrollMarks marks = marks();
        assertEquals(3 * ch, marks.marks.get(0).y(), "the line over the hidden ones");
        assertEquals(6, marks.marks.get(0).lineNumber(), "but still its own line");
        assertEquals(6 * ch, marks.marks.get(1).y(), "lines 4 to 7 take no height");
        assertEquals(h.buffer().getDisplayHeight(), marks.height);
    }

    @Test
    public void virtualRowsMoveTheLinesUnder() {
        lines(20);
        h.buffer()
                .getVirtualText()
                .set(
                    "t",
                    List.of(
                        VirtualText.above(
                            line(2),
                            List.of(List.of(VirtualText.Run.of("a")), List.of(VirtualText.Run.of("b"))))));
        flush();
        Editor.setBookmark('1', new Marker(h.buffer(), new Position(line(2), 0)));
        Editor.setBookmark('2', new Marker(h.buffer(), new Position(line(5), 0)));
        final ScrollMarks marks = marks();
        assertEquals(4 * ch, marks.marks.get(0).y(), "the text under its own rows");
        assertEquals(7 * ch, marks.marks.get(1).y());
        assertEquals(h.buffer().getDisplayHeight(), marks.height);
    }

    private static void flush() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {});
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void inTheScrollBarsProportions() {
        lines(1000);
        Editor.setBookmark('1', new Marker(h.buffer(), new Position(line(500), 0)));
        final ScrollMarks marks = marks();
        final Rectangle track = new Rectangle(0, 16, 16, 400);
        final Rectangle r = marks.bounds(marks.marks.get(0), track, 10 * ch, true);
        final int center = 16 + (int) ((500 * ch + ch / 2L) * 400 / (1000 * ch));
        assertTrue(Math.abs(r.y + r.height / 2 - center) <= 1, r + " centered on " + center);
        assertEquals(ScrollMarks.lineMarkHeight(), r.height, "a line in a long buffer");
    }

    @Test
    public void aShortBufferIsNotStretchedNorItsLinesMarksTall() {
        lines(5);
        h.rows(40);
        signs(Signs.Kind.ADDED, 1, Signs.Kind.ADDED, 2, Signs.Kind.ADDED, 3);
        Editor.setBookmark('1', new Marker(h.buffer(), new Position(line(4), 0)));
        final ScrollMarks marks = marks();
        final Rectangle track = new Rectangle(0, 0, 16, 400);
        final int window = h.editor().getDisplay().getHeight();
        final Rectangle r = marks.bounds(marks.marks.get(1), track, window, true);
        assertTrue(r.y < 100, "where line 4 of a window 40 lines high is: " + r);
        assertEquals(ScrollMarks.lineMarkHeight(), r.height, "a line's mark as thin as in a long buffer");
        final Rectangle span = marks.bounds(marks.marks.get(0), track, window, true);
        assertEquals(3 * 400 / 40, span.height, "a span of lines as tall as they are");
    }

    @Test
    public void aSpanOfLinesInALongBufferIsTallerThanALine() {
        lines(5000);
        signs(Signs.Kind.CHANGED, 10, Signs.Kind.CHANGED, 11);
        final ScrollMarks marks = marks();
        final Rectangle span = marks.bounds(marks.marks.get(0), new Rectangle(0, 0, 16, 400), 10 * ch, true);
        assertEquals(2 * ScrollMarks.lineMarkHeight(), span.height);
    }

    @Test
    public void changesOfDifferentKindsSideBySideDontOverlap() {
        lines(5000);
        signs(
            Signs.Kind.ADDED,
            3051,
            Signs.Kind.ADDED,
            3052,
            Signs.Kind.CHANGED,
            3053,
            Signs.Kind.CHANGED,
            3054,
            Signs.Kind.CHANGED,
            3055);
        final ScrollMarks marks = marks();
        final Rectangle track = new Rectangle(0, 0, 16, 400);
        final Rectangle added = marks.bounds(marks.marks.get(0), track, 10 * ch, true);
        final Rectangle changed = marks.bounds(marks.marks.get(1), track, 10 * ch, true);
        assertEquals("ADDED@3051+2 CHANGED@3053+3", describe(marks.marks));
        assertTrue(changed.y >= added.y + added.height, added + " over " + changed);
    }

    // A track 16 pixels wide painted with opaque colors: changes green, bookmarks blue.
    private java.awt.image.BufferedImage painted(ScrollMarks marks, boolean textLeft) {
        final java.awt.Color[] colors = new java.awt.Color[Kind.values().length];
        java.util.Arrays.fill(colors, java.awt.Color.GREEN);
        colors[Kind.BOOKMARK.ordinal()] = java.awt.Color.BLUE;
        final java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(16, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final java.awt.Graphics g = image.getGraphics();
        marks.paint(g, new Rectangle(0, 0, 16, 400), 20 * ch, textLeft, colors);
        g.dispose();
        return image;
    }

    @Test
    public void changesAreANarrowLaneBesideTheTextUnderTheMarksAcrossTheBar() {
        lines(20);
        signs(Signs.Kind.ADDED, 2, Signs.Kind.ADDED, 3, Signs.Kind.ADDED, 4);
        Editor.setBookmark('1', new Marker(h.buffer(), new Position(line(3), 0)));
        final ScrollMarks marks = marks();
        final Rectangle bookmark = marks.bounds(marks.marks.get(1), new Rectangle(0, 0, 16, 400), 20 * ch, true);
        final int middle = bookmark.y + bookmark.height / 2;
        final int lane = ScrollMarks.changeLaneWidth(16);
        final int green = java.awt.Color.GREEN.getRGB();
        final int blue = java.awt.Color.BLUE.getRGB();
        final java.awt.image.BufferedImage left = painted(marks, true);
        assertEquals(green, left.getRGB(lane - 1, bookmark.y - 2), "the change at the text's side");
        assertEquals(0xff000000, left.getRGB(lane + 1, bookmark.y - 2), "and only there");
        assertEquals(blue, left.getRGB(lane - 1, middle), "the bookmark over it");
        assertEquals(blue, left.getRGB(16 - lane, middle), "across the bar");
        final java.awt.image.BufferedImage right = painted(marks, false);
        assertEquals(green, right.getRGB(16 - lane, bookmark.y - 2), "the old side's text at the right");
        assertEquals(0xff000000, right.getRGB(lane - 1, bookmark.y - 2));
    }

    @Test
    public void madeAgainOnlyWhenWhatTheyShowChanges() {
        lines(20);
        final ScrollMarks first = ScrollMarks.of(h.editor(), null);
        assertSame(first, ScrollMarks.of(h.editor(), first), "nothing changed");
        Editor.setBookmark('1', new Marker(h.buffer(), new Position(line(3), 0)));
        final ScrollMarks second = ScrollMarks.of(h.editor(), first);
        assertNotSame(first, second, "a bookmark set");
        assertEquals("BOOKMARK@3+1", describe(second.marks));
        Editor.setTemporaryMarker(new Marker(h.buffer(), new Position(line(7), 0)));
        final ScrollMarks third = ScrollMarks.of(h.editor(), second);
        assertEquals("BOOKMARK@3+1 BOOKMARK@7+1", describe(third.marks), "the temporary marker too");
        h.cursor(0, 0).keys("<Enter>");
        assertEquals(
            "MODIFIED@0+1 BOOKMARK@4+1 BOOKMARK@8+1",
            describe(ScrollMarks.of(h.editor(), third).marks),
            "an edit");
    }

    @Test
    public void aBookmarkDroppedAgainWhereItWasFirstIsMovedThere() {
        lines(20);
        Editor.setBookmark('1', new Marker(h.buffer(), new Position(line(3), 0)));
        final ScrollMarks first = ScrollMarks.of(h.editor(), null);
        h.cursor(0, 0).keys("<Enter><Enter>");
        final ScrollMarks moved = ScrollMarks.of(h.editor(), first);
        assertEquals(5, moved.marks.get(moved.marks.size() - 1).lineNumber());
        Editor.setBookmark('1', new Marker(h.buffer(), new Position(line(3), 0)));
        final ScrollMarks again = ScrollMarks.of(h.editor(), moved);
        assertEquals(3, again.marks.get(again.marks.size() - 1).lineNumber());
    }

    @Test
    public void aClosedBuffersBookmarkIsMarkedInItsFileOpenedAgain() {
        lines(20);
        final Marker m = new Marker(h.buffer(), new Position(line(6), 4));
        Editor.setBookmark('1', m);
        m.invalidate();
        final Mark mark = marks().marks.get(0);
        assertEquals("BOOKMARK@6+1", describe(List.of(mark)));
        assertEquals(4, mark.offset());
    }

    @Test
    public void theNearestMarksFirstThenTheHigherKind() {
        lines(20);
        signs(Signs.Kind.ADDED, 10);
        Editor.setBookmark('1', new Marker(h.buffer(), new Position(line(10), 0)));
        Editor.setBookmark('2', new Marker(h.buffer(), new Position(line(11), 0)));
        final ScrollMarks marks = marks();
        final Rectangle track = new Rectangle(0, 0, 16, 200);
        final int window = 10 * ch;
        final Rectangle on10 = marks.bounds(marks.marks.get(0), track, window, true);
        final List<Mark> at = marks.near(on10.y + 1, 2, track, window, true);
        assertEquals("BOOKMARK@10+1 ADDED@10+1", describe(at), "on line 10, the bookmark over the change");
        assertEquals("", describe(marks.near(150, 2, track, window, true)), "nothing near");
    }

    @Test
    public void aToolTipNamesOneBasedLinesAndSaysHowManyMore() {
        lines(20);
        signs(Signs.Kind.CHANGED, 2, Signs.Kind.CHANGED, 3);
        for (char c = '1'; c <= '7'; c++)
            Editor.setBookmark(c, new Marker(h.buffer(), new Position(line(9), 0)));
        final String tip = ScrollMarks.toolTip(marks().marks, 6).replace("&nbsp;", " ");
        assertTrue(tip.contains("lines 3-4"), tip);
        assertTrue(tip.contains("line 10"), tip);
        assertTrue(tip.contains("and 2 more"), tip);
    }

    // The bar laid out as a window's, 400 pixels high, its model set as after a command.
    private VerticalScrollBar bar() {
        final VerticalScrollBar bar = h.editor().getVerticalScrollBar();
        bar.setSize(16, 400);
        bar.doLayout();
        h.editor().updateVerticalScrollBar();
        return bar;
    }

    private void press(VerticalScrollBar bar, int y) {
        final long now = System.currentTimeMillis();
        bar.dispatchEvent(
            new MouseEvent(bar,
                    MouseEvent.MOUSE_PRESSED,
                    now,
                    InputEvent.BUTTON1_DOWN_MASK,
                    8,
                    y,
                    1,
                    false,
                    MouseEvent.BUTTON1));
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_RELEASED, now, 0, 8, y, 1, false, MouseEvent.BUTTON1));
    }

    // Where the bar draws the bookmark on line.
    private int markY(VerticalScrollBar bar, int line) {
        final ScrollMarks marks = marks();
        for (Mark m : marks.marks)
            if (m.kind() == Kind.BOOKMARK && m.lineNumber() == line) {
                final Rectangle r = marks.bounds(m, bar.trackBounds(), h.editor().getDisplay().getHeight(), true);
                return r.y + r.height / 2;
            }
        throw new AssertionError("no bookmark on " + line);
    }

    @Test
    public void aClickOnAMarkGoesToItsLineOpeningItsFold() {
        lines(1000);
        Editor.setBookmark('1', new Marker(h.buffer(), new Position(line(800), 3)));
        FoldCommands.hideLines(h.editor(), line(790), line(810));
        final VerticalScrollBar bar = bar();
        final List<Integer> paged = new java.util.ArrayList<>();
        bar.addAdjustmentListener(e -> {
            if (!h.editor().inScrollBarUpdate)
                paged.add(e.getValue());
        });
        press(bar, markY(bar, 800));
        h.assertCursorAt(800, 3);
        assertFalse(line(800).isHidden(), "its fold opened");
        assertEquals(List.of(), paged, "the track didn't page too");
        assertEquals(
            0,
            JumpList.getEntries().get(JumpList.getEntries().size() - 1).getLineNumber(),
            "where it was, a jump to go back to");
    }

    @Test
    public void aClickOnTheThumbDragsItInstead() {
        lines(1000);
        Editor.setBookmark('1', new Marker(h.buffer(), new Position(line(3), 0)));
        final VerticalScrollBar bar = bar();
        press(bar, markY(bar, 3));
        h.assertCursorAt(0, 0);
    }

    private static List<Mark> searchMarks(ScrollMarks marks) {
        return marks.marks.stream().filter(m -> m.kind() == Kind.SEARCH).toList();
    }

    @Test
    public void theLinesASearchHighlightsAreMarked() {
        lines(30);
        h.buffer().setProperty(Property.HIGHLIGHT_SEARCH_MATCHES, true);
        h.editor().setLastSearch(new Search("2", false, false));
        final List<Mark> marks = searchMarks(marks());
        assertEquals(
            "SEARCH@2+1 SEARCH@12+1 SEARCH@20+1 SEARCH@21+1 SEARCH@22+1 SEARCH@23+1 SEARCH@24+1 SEARCH@25+1 "
                    + "SEARCH@26+1 SEARCH@27+1 SEARCH@28+1 SEARCH@29+1",
            describe(marks));
        assertEquals(5, marks.get(0).offset(), "at the first match");
        assertTrue(marks.get(4).text().contains("2 matches"), marks.get(4).text());
        h.buffer().setProperty(Property.HIGHLIGHT_SEARCH_MATCHES, false);
        assertEquals("", describe(searchMarks(marks())), "none when matches aren't highlighted");
    }

    @Test
    public void madeAgainWhenTheSearchChanges() {
        lines(30);
        h.buffer().setProperty(Property.HIGHLIGHT_SEARCH_MATCHES, true);
        final Search search = new Search("line 3", false, false);
        h.editor().setLastSearch(search);
        final ScrollMarks first = ScrollMarks.of(h.editor(), null);
        assertEquals("SEARCH@3+1", describe(searchMarks(first)));
        assertSame(first, ScrollMarks.of(h.editor(), first), "the search unchanged");
        search.setPattern("line 4");
        final ScrollMarks edited = ScrollMarks.of(h.editor(), first);
        assertEquals("SEARCH@4+1", describe(searchMarks(edited)), "the same search, its pattern changed");
        h.editor().clearSearchHighlight();
        assertEquals("", describe(searchMarks(ScrollMarks.of(h.editor(), edited))), "its highlight cleared");
    }

    @Test
    public void vimsPatternBeingTypedIsMarked() {
        lines(30);
        h.vim().keys(":set hls<CR>").keys("/").searchTyped("line 7");
        final ScrollMarks typed = ScrollMarks.of(h.editor(), null);
        assertEquals("SEARCH@7+1", describe(searchMarks(typed)));
        h.searchTyped("line 8");
        final ScrollMarks retyped = ScrollMarks.of(h.editor(), typed);
        assertEquals("SEARCH@8+1", describe(searchMarks(retyped)), "as it is typed");
        h.searchPattern("line 8").keys(":noh<CR>");
        assertEquals("", describe(searchMarks(ScrollMarks.of(h.editor(), retyped))), ":noh hides them");
    }

    @Test
    public void aSearchTooSlowToMarkIsNotTriedAgainUntilItChanges() {
        lines(30);
        h.buffer().setProperty(Property.HIGHLIGHT_SEARCH_MATCHES, true);
        final Search search = new Search("line", false, false);
        h.editor().setLastSearch(search);
        final ScrollMarks slow = ScrollMarks.build(h.editor(), null, 0);
        assertTrue(slow.searchTooSlow);
        assertEquals("", describe(searchMarks(slow)), "none rather than some");
        h.cursor(0, 0).keys("x");
        final ScrollMarks edited = ScrollMarks.of(h.editor(), slow);
        assertTrue(edited.searchTooSlow, "an edit: the same search, not tried again");
        search.setPattern("line 5");
        assertEquals("SEARCH@5+1", describe(searchMarks(ScrollMarks.of(h.editor(), edited))), "another search");
    }

    @Test
    public void aSearchTooSlowInOneBufferIsTriedInAnother() {
        lines(30);
        h.buffer().setProperty(Property.HIGHLIGHT_SEARCH_MATCHES, true);
        h.editor().setLastSearch(new Search("line 5", false, false));
        final ScrollMarks slow = ScrollMarks.build(h.editor(), null, 0);
        final Buffer other = new Buffer(File.getInstance(System.getProperty("java.io.tmpdir") + "/j-scroll-other.txt"));
        other.setText("line 5\n");
        other.setProperty(Property.HIGHLIGHT_SEARCH_MATCHES, true);
        h.showBuffer(other);
        assertEquals("SEARCH@0+1", describe(searchMarks(ScrollMarks.of(h.editor(), slow))));
    }

    // A buffer that knows its changes, as a comparison's side does.
    private void showWithChanges(int n, List<Buffer.ChangeSpan> spans) {
        lines(1);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
            sb.append("line ").append(i).append('\n');
        final Buffer side = new Buffer(File.getInstance(System.getProperty("java.io.tmpdir") + "/j-scroll-side.txt")) {
            @Override
            public List<ChangeSpan> getChangeSpans() {
                return spans;
            }
        };
        side.setText(sb.toString());
        h.showBuffer(side);
    }

    @Test
    public void aComparisonsChangesAreMarkedOnItsSide() {
        showWithChanges(
            10,
            List.of(
                new Buffer.ChangeSpan(Signs.Kind.CHANGED, 1, 2),
                new Buffer.ChangeSpan(Signs.Kind.DELETED, 5, 0),
                new Buffer.ChangeSpan(Signs.Kind.ADDED, 8, 2),
                new Buffer.ChangeSpan(Signs.Kind.DELETED, 10, 0)));
        // The filler rows of the lines deleted at 5, under line 4, as aligned.
        h.editor()
                .getBuffer()
                .getVirtualText()
                .set(
                    "diff",
                    List.of(
                        VirtualText.below(
                            h.editor().getBuffer().getLine(4),
                            List.of(List.of(VirtualText.Run.of("")), List.of(VirtualText.Run.of(""))))));
        flush();
        final List<Mark> marks = marks().marks;
        assertEquals("CHANGED@1+2 DELETED@5+1 ADDED@8+2 DELETED@9+1", describe(marks), "the last lines' span once");
        assertEquals(
            5 * ch,
            marks.get(1).y(),
            "lines none of which are here: over the filler rows, as the track has it");
        assertEquals(0, marks.get(1).height());
        assertEquals(12 * ch, marks.get(3).y(), "after the last line, at its bottom");
    }

    @Test
    public void aConflictIsOneMarkFromItsOpenerToItsCloser(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        final org.armedbear.j.vcs.git.GitFixture git = org.armedbear.j.vcs.git.GitFixture.init(tmp);
        try {
            git.git("config", "merge.conflictStyle", "merge");
            git.write("a.txt", "one\ntwo\nthree\n");
            git.commitAll("base");
            git.git("checkout", "-q", "-b", "other");
            git.write("a.txt", "one\nTHEIRS\nthree\n");
            git.commitAll("theirs");
            git.git("checkout", "-q", "main");
            git.write("a.txt", "one\nOURS\nthree\n");
            git.commitAll("ours");
            git.run("merge", "other");
            final Buffer[] file = new Buffer[1];
            try {
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    lines(1);
                    file[0] = Editor.getBuffer(File.getInstance(git.root.resolve("a.txt").toString()));
                    if (!file[0].initialized())
                        file[0].initialize();
                    file[0].load();
                    h.showBuffer(file[0]);
                });
                final List<Mark>[] marks = new List[1];
                for (int i = 0; i < 400 && (marks[0] == null || marks[0].isEmpty()); i++) {
                    Thread.sleep(25);
                    javax.swing.SwingUtilities.invokeAndWait(
                        () -> marks[0] = file[0].getConflicts() == null
                                ? null
                                : marks().marks.stream().filter(m -> m.kind() == Kind.CONFLICT).toList());
                }
                assertEquals("CONFLICT@1+5", describe(marks[0]), "<<<<<<<, OURS, =======, THEIRS, >>>>>>>");
                assertTrue(marks[0].get(0).text().contains("HEAD / other"), marks[0].get(0).text());
            }
            finally {
                javax.swing.SwingUtilities.invokeAndWait(() -> Editor.getBufferList().remove(file[0]));
            }
        }
        finally {
            org.armedbear.j.vcs.git.GitFixture.reset();
        }
    }
}
