/*
 * ScrollMarks.java
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

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.armedbear.j.vcs.conflicts.ConflictMarkers;
import org.armedbear.j.vcs.conflicts.Conflicts;

/**
 * Where a window's buffer has bookmarks, changes, conflicts and search
 * matches, as marks drawn over its vertical scroll bar. A snapshot, made
 * again when what it was made from changes.
 */
final class ScrollMarks {
    /** In ascending priority: a later kind is drawn over an earlier one. */
    enum Kind {
        MODIFIED("Changed"),
        SAVED("Changed, saved"),
        ADDED("Added"),
        CHANGED("Changed"),
        DELETED("Deleted"),
        CONFLICT("Conflict"),
        SEARCH("Search"),
        BOOKMARK("Bookmark");

        final String label;

        Kind(String label) {
            this.label = label;
        }

        /** Whether it is drawn in the narrow lane beside the text, as the gutter's change bar. */
        boolean changeLane() {
            return this != SEARCH && this != BOOKMARK;
        }
    }

    /**
     * @param line the line to go to, the first of a span
     * @param lineNumber line's, zero-based
     * @param lines how many lines a span covers; 1 for a mark on a line
     * @param y the top of line's text, in the scroll bar's model's pixels
     * @param height the span's height in those pixels; 0 for a boundary
     *     between lines, as a deletion's
     * @param text what it is, as a tool tip names it
     */
    record Mark(Kind kind, Line line, int offset, int lineNumber, int lines, int y, int height, String text) {}

    // How long finding a search's matches may take, on the event thread.
    private static final long SEARCH_TIME = 20_000_000;

    private static final Comparator<Mark> PAINT_ORDER =
            Comparator.comparingInt((Mark m) -> m.kind().ordinal()).thenComparingInt(Mark::y);

    // What the marks were made from.
    private final Buffer buffer;
    // Buffer.empty and setText replace every line without a modCount.
    private final Line firstLine;
    private final int modCount;
    private final int foldVersion;
    private final int rowsVersion;
    private final int charHeight;
    private final long fileChangedAt;
    private final Signs signs;
    private final boolean changes;
    private final Marker[] bookmarks;
    private final Object searchKey;
    private final List<Buffer.ChangeSpan> spans;
    private final List<Line> openers;

    /** In buffer order, which is y order: near() relies on it. */
    final List<Mark> marks;
    /** The buffer's height in the scroll bar's model. */
    final int height;
    /**
     * Whether finding the search's matches took too long, so none are
     * marked: not tried again until the search changes.
     */
    final boolean searchTooSlow;
    private final List<Mark> paintOrder;

    private ScrollMarks(Editor editor, List<Mark> marks, int height, Object searchKey, boolean searchTooSlow) {
        buffer = editor.getBuffer();
        firstLine = buffer.getFirstLine();
        modCount = buffer.getModCount();
        foldVersion = buffer.getFoldVersion();
        rowsVersion = rowsVersion(buffer);
        charHeight = Display.getCharHeight();
        fileChangedAt = buffer.getFileChangedAt();
        changes = changesShown(buffer);
        signs = changes ? buffer.getSigns() : null;
        bookmarks = Editor.getBookmarks().clone();
        spans = buffer.getChangeSpans();
        openers = openers(buffer);
        this.searchKey = searchKey;
        this.searchTooSlow = searchTooSlow;
        this.marks = marks;
        this.height = height;
        paintOrder = new ArrayList<>(marks);
        paintOrder.sort(PAINT_ORDER);
    }

    /** last if it still holds for editor, else new marks; null when it shows none. */
    static ScrollMarks of(Editor editor, ScrollMarks last) {
        if (!wanted(editor))
            return null;
        if (last != null && last.current(editor))
            return last;
        return build(editor, last, SEARCH_TIME);
    }

    /** Whether marks made from last would be made again, or made at all, for editor. */
    static boolean stale(Editor editor, ScrollMarks last) {
        return wanted(editor) ? last == null || !last.current(editor) : last != null;
    }

    private static boolean wanted(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        return buffer != null && buffer.getFirstLine() != null && buffer.getBooleanProperty(Property.SCROLL_BAR_MARKS);
    }

    private boolean current(Editor editor) {
        final Buffer b = editor.getBuffer();
        return b == buffer
                && b.getFirstLine() == firstLine
                && b.getModCount() == modCount
                && b.getFoldVersion() == foldVersion
                && rowsVersion(b) == rowsVersion
                && Display.getCharHeight() == charHeight
                && b.getFileChangedAt() == fileChangedAt
                && changesShown(b) == changes
                && (changes ? b.getSigns() : null) == signs
                && sameBookmarks()
                && b.getChangeSpans() == spans
                && openers(b) == openers
                && Objects.equals(searchKey(editor.getSearchMatcher()), searchKey);
    }

    // By identity: Marker.equals matches a moved bookmark dropped again
    // where it was first dropped.
    private boolean sameBookmarks() {
        final Marker[] now = Editor.getBookmarks();
        for (int i = 0; i < now.length; i++)
            if (now[i] != bookmarks[i])
                return false;
        return true;
    }

    private static Object searchKey(SearchMatcher matcher) {
        return matcher == null ? null : matcher.key();
    }

    private static int rowsVersion(Buffer buffer) {
        final VirtualTextLayer layer = buffer.virtualTextIfAny();
        return layer == null ? -1 : layer.rowsVersion();
    }

    // A new list each time the conflicts are scanned.
    private static List<Line> openers(Buffer buffer) {
        final Conflicts conflicts = buffer.getConflicts();
        return conflicts == null ? null : conflicts.openers();
    }

    // As the gutter shows them.
    private static boolean changesShown(Buffer buffer) {
        return buffer.getBooleanProperty(Property.SHOW_CHANGE_MARKS) && !buffer.isNewFile();
    }

    static ScrollMarks build(Editor editor) {
        return build(editor, null, SEARCH_TIME);
    }

    /**
     * @param last the marks before, whose search, if too slow, is not tried
     *     again
     * @param searchTime how long finding the search's matches may take, in
     *     nanoseconds
     */
    static ScrollMarks build(Editor editor, ScrollMarks last, long searchTime) {
        final Buffer buffer = editor.getBuffer();
        try {
            buffer.lockRead();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return null;
        }
        try {
            return new Walk(editor, last, searchTime).run();
        }
        finally {
            buffer.unlockRead();
        }
    }

    // One pass over the lines gives each mark its y and line number:
    // Buffer.getY walks the lines itself when folded, getLine(int) always.
    private static final class Walk {
        private final Editor editor;
        private final Buffer buffer;
        private final Signs signs;
        private final boolean changes;
        private final Map<Line, int[]> rows;
        private final int topRows;
        private final Marker[] all = Editor.getBookmarks();
        private final Map<Line, List<Integer>> bookmarks = new IdentityHashMap<>();
        // Those of a closed buffer, known by file and line number, as
        // Marker.gotoMarker finds them in the file opened again.
        private final Map<Integer, List<Integer>> closedBookmarks = new HashMap<>();
        private final List<Mark> marks = new ArrayList<>();
        private final SearchMatcher matcher;
        private final long searchTime;
        private boolean searching;
        private boolean searchTooSlow;
        private final List<Mark> searchMarks = new ArrayList<>();
        // A comparison side's changes, and the next not yet passed.
        private final List<Buffer.ChangeSpan> changeSpans;
        private int nextChange;
        private final Map<Line, ConflictMarkers.Region> conflicts = new IdentityHashMap<>();
        // The lines left in the conflict being passed.
        private int conflictLines;
        private String conflictText;

        // The change span being extended.
        private Kind spanKind;
        private String spanText;
        private Line spanLine;
        private int spanNumber;
        private int spanLines;
        private int spanTop;
        private int spanBottom;

        Walk(Editor editor, ScrollMarks last, long searchTime) {
            this.editor = editor;
            this.searchTime = searchTime;
            buffer = editor.getBuffer();
            matcher = editor.getSearchMatcher();
            searchTooSlow = matcher != null
                    && last != null
                    && last.buffer == buffer
                    && last.searchTooSlow
                    && matcher.key().equals(last.searchKey);
            searching = matcher != null && !searchTooSlow;
            changes = changesShown(buffer);
            signs = changes ? buffer.getSigns() : null;
            changeSpans = buffer.getChangeSpans();
            final List<Line> openers = openers(buffer);
            if (openers != null)
                for (Line opener : openers) {
                    final ConflictMarkers.Region region = buffer.getConflicts().regionAt(opener);
                    if (region != null)
                        conflicts.put(opener, region);
                }
            final VirtualTextLayer layer = buffer.virtualTextIfAny();
            rows = layer == null ? Map.of() : layer.rowsByLine();
            topRows = layer == null ? 0 : layer.topRows();
            final File file = buffer.getFile();
            for (int i = 0; i < all.length; i++) {
                final Marker m = all[i];
                if (m == null)
                    continue;
                if (m.getBuffer() == buffer && m.getLine() != null)
                    bookmarks.computeIfAbsent(m.getLine(), l -> new ArrayList<>(1)).add(i);
                else if (m.getBuffer() == null && file != null && file.equals(m.getFile()))
                    closedBookmarks.computeIfAbsent(m.getLineNumber(), n -> new ArrayList<>(1)).add(i);
            }
        }

        ScrollMarks run() {
            final Line first = buffer.firstVisibleLine();
            final long searchDeadline = System.nanoTime() + searchTime;
            int y = 0;
            // A hidden line is marked on its fold's visible first line.
            int shownTop = 0;
            int shownBottom = 0;
            int n = 0;
            for (Line line = buffer.getFirstLine(); line != null; line = line.next(), n++) {
                // Where a change with no lines here goes, as the comparison
                // track draws it: under the line before, over its fillers.
                final int boundary = n == 0 ? 0 : shownBottom;
                if (!line.isHidden()) {
                    final int[] r = rows.get(line);
                    y += ((r != null ? r[0] : 0) + (line == first ? topRows : 0)) * Display.getCharHeight();
                    shownTop = y;
                    y += line.getHeight();
                    shownBottom = y;
                    if (r != null)
                        y += r[1] * Display.getCharHeight();
                }
                change(line, n, shownTop, shownBottom, boundary);
                if (searching) {
                    if ((n & 15) == 0 && System.nanoTime() > searchDeadline) {
                        // Some of the matches would say the rest have none.
                        searching = false;
                        searchTooSlow = true;
                        searchMarks.clear();
                    } else
                        search(line, n, shownTop, shownBottom);
                }
                List<Integer> indices = bookmarks.isEmpty() ? null : bookmarks.get(line);
                if (indices == null && !closedBookmarks.isEmpty())
                    indices = closedBookmarks.get(n);
                if (indices != null)
                    for (int i : indices)
                        marks.add(
                            new Mark(Kind.BOOKMARK,
                                    line,
                                    all[i].getOffset(),
                                    n,
                                    1,
                                    shownTop,
                                    shownBottom - shownTop,
                                    bookmarkName(i)));
            }
            endSpan();
            // Changes with no lines here after this side's last.
            if (changeSpans != null)
                for (; nextChange < changeSpans.size(); nextChange++)
                    if (changeSpans.get(nextChange).count() == 0)
                        point(changeSpans.get(nextChange), buffer.getLastLine(), n - 1, shownBottom);
            marks.addAll(searchMarks);
            // By y within a line too: near() stops at the first mark past the pointer.
            marks.sort(Comparator.comparingInt(Mark::lineNumber).thenComparingInt(Mark::y));
            return new ScrollMarks(editor, marks, y, searchKey(matcher), searchTooSlow);
        }

        private void search(Line line, int n, int top, int bottom) {
            final int[] spans = matcher.matches().apply(line);
            if (spans == null)
                return;
            final int count = spans.length / 2;
            searchMarks.add(
                new Mark(Kind.SEARCH,
                        line,
                        spans[0],
                        n,
                        1,
                        top,
                        bottom - top,
                        count == 1 ? Kind.SEARCH.label : Kind.SEARCH.label + " (" + count + " matches)"));
        }

        private void change(Line line, int n, int top, int bottom, int boundary) {
            Kind kind = null;
            String text = null;
            Signs.Sign sign = null;
            final Kind compared = changeSpans != null ? compared(line, n, boundary) : null;
            final ConflictMarkers.Region region = conflicts.isEmpty() ? null : conflicts.get(line);
            if (region != null) {
                conflictLines = region.end() - region.start() + 1;
                conflictText = Kind.CONFLICT.label + ", " + Conflicts.label(region.ours(), "ours") + " / "
                        + Conflicts.label(region.theirs(), "theirs");
            }
            if (conflictLines > 0) {
                conflictLines--;
                kind = Kind.CONFLICT;
                text = conflictText;
            } else if (compared != null) {
                kind = compared;
                text = kind.label;
            } else if (signs != null) {
                sign = signs.get(line);
                if (sign != null) {
                    kind = kind(sign.kind());
                    text = sign.staged() ? kind.label + ", staged" : kind.label;
                }
            } else if (changes && line.isModified()) {
                kind = line.isSaved() ? Kind.SAVED : Kind.MODIFIED;
                text = kind.label;
            }
            if (kind == Kind.DELETED && sign != null) {
                // The sign is on the line beside the lines deleted.
                endSpan();
                marks.add(new Mark(kind, line, 0, n, 1, sign.above() ? top : bottom, 0, text));
            } else if (kind == null) {
                endSpan();
            } else if (kind == spanKind && text.equals(spanText) && region == null) {
                spanLines++;
                spanBottom = bottom;
            } else {
                endSpan();
                spanKind = kind;
                spanText = text;
                spanLine = line;
                spanNumber = n;
                spanLines = 1;
                spanTop = top;
                spanBottom = bottom;
            }
        }

        // The kind of the comparison change line n is in, if any, its
        // changes with no lines here before n marked at boundary.
        private Kind compared(Line line, int n, int boundary) {
            for (; nextChange < changeSpans.size(); nextChange++) {
                final Buffer.ChangeSpan c = changeSpans.get(nextChange);
                if (c.start() > n)
                    return null;
                if (c.count() == 0)
                    point(c, line, n, boundary);
                else if (n < c.start() + c.count())
                    return kind(c.kind());
            }
            return null;
        }

        private void point(Buffer.ChangeSpan c, Line line, int n, int y) {
            final Kind kind = kind(c.kind());
            marks.add(new Mark(kind, line, 0, n, 1, y, 0, kind.label));
        }

        private static Kind kind(Signs.Kind kind) {
            return switch (kind) {
                case ADDED -> Kind.ADDED;
                case CHANGED -> Kind.CHANGED;
                case DELETED -> Kind.DELETED;
            };
        }

        private void endSpan() {
            if (spanKind != null)
                marks.add(
                    new Mark(spanKind, spanLine, 0, spanNumber, spanLines, spanTop, spanBottom - spanTop, spanText));
            spanKind = null;
        }
    }

    private static String bookmarkName(int index) {
        if (index < 10)
            return "Bookmark " + index;
        if (index == 10)
            return "Temporary marker";
        return "Bookmark " + (char) ('A' + index - 11);
    }

    /** The change lane's width in a track this wide: a thin vertical line, as IntelliJ's. */
    static int changeLaneWidth(int width) {
        return Math.min(Math.max(2, UIScale.scale(3)), width / 3);
    }

    /** A one-line mark's height, whatever the buffer's length. */
    static int lineMarkHeight() {
        return Math.max(2, UIScale.scale(2));
    }

    // Where the marks went for the track, window and side last asked for.
    private Rectangle placedTrack;
    private int placedWindow;
    private boolean placedTextLeft;
    private Map<Mark, Rectangle> places;

    /**
     * Where m is drawn in track, into r: in the scroll bar's proportions,
     * so a mark is inside the thumb exactly when its line is in the window.
     * Changes in a narrow lane beside the text; the others across the
     * track, over that lane.
     *
     * @param window the window's height: a buffer shorter than it keeps its
     *     lines' places rather than being stretched over the track
     * @param textLeft whether the text is at the track's left, the change
     *     lane with it
     * @return false if there is nowhere to draw it
     */
    boolean bounds(Mark m, Rectangle track, int window, boolean textLeft, Rectangle r) {
        final Rectangle place = places(track, window, textLeft).get(m);
        if (place == null)
            return false;
        r.setBounds(place);
        return true;
    }

    Rectangle bounds(Mark m, Rectangle track, int window, boolean textLeft) {
        final Rectangle r = new Rectangle();
        return bounds(m, track, window, textLeft, r) ? r : null;
    }

    private Map<Mark, Rectangle> places(Rectangle track, int window, boolean textLeft) {
        if (places != null && track.equals(placedTrack) && window == placedWindow && textLeft == placedTextLeft)
            return places;
        places = new IdentityHashMap<>();
        placedTrack = new Rectangle(track);
        placedWindow = window;
        placedTextLeft = textLeft;
        final int gap = Math.max(1, UIScale.scale(1));
        Kind laneKind = null;
        int laneBottom = Integer.MIN_VALUE;
        for (Mark m : marks) {
            final Rectangle r = new Rectangle();
            if (!place(m, track, window, textLeft, r))
                break;
            if (m.kind().changeLane()) {
                // Changes of different kinds side by side, each grown to
                // its least height, would overlap: the later one moves
                // down under the earlier, its height kept, at most to the
                // track's end. Those of a kind are painted as one.
                if (m.kind() != laneKind && r.y < laneBottom + gap)
                    r.y = Math.min(laneBottom + gap, track.y + track.height - r.height);
                laneKind = m.kind();
                laneBottom = Math.max(laneBottom, r.y + r.height);
            }
            places.put(m, r);
        }
        return places;
    }

    private boolean place(Mark m, Rectangle track, int window, boolean textLeft, Rectangle r) {
        final int total = Math.max(height, window);
        final int min = lineMarkHeight();
        if (total <= 0 || track.height < 2 * min)
            return false;
        if (m.kind().changeLane()) {
            final int lane = changeLaneWidth(track.width);
            r.x = textLeft ? track.x : track.x + track.width - lane;
            r.width = lane;
        } else {
            final int padding = track.width > 6 ? UIScale.scale(2) : 0;
            r.x = track.x + padding;
            r.width = track.width - 2 * padding;
        }
        int top = track.y + (int) ((long) m.y() * track.height / total);
        int bottom = track.y + (int) ((long) (m.y() + m.height()) * track.height / total);
        // A line is a thin mark however tall it is on the track; a span of
        // lines at least twice that, to read as a span.
        final int want = m.lines() > 1 ? Math.max(bottom - top, 2 * min) : min;
        if (bottom - top != want) {
            top = (top + bottom - want) / 2;
            bottom = top + want;
        }
        top = Math.max(track.y, Math.min(top, track.y + track.height - want));
        bottom = Math.min(track.y + track.height, top + want);
        r.y = top;
        r.height = bottom - top;
        return true;
    }

    // Changes first, under the others.
    void paint(Graphics g, Rectangle track, int window, boolean textLeft, Color[] colors) {
        final Map<Mark, Rectangle> at = places(track, window, textLeft);
        Kind kind = null;
        int x = 0;
        int width = 0;
        int top = 0;
        int bottom = Integer.MIN_VALUE;
        for (Mark m : paintOrder) {
            final Rectangle r = at.get(m);
            if (r == null)
                continue;
            // One fill for a kind's overlapping marks: translucent fills
            // darken where they overlap, and thousands fall on a few
            // hundred pixels.
            if (m.kind() == kind && r.y <= bottom) {
                bottom = Math.max(bottom, r.y + r.height);
                continue;
            }
            if (kind != null)
                g.fillRect(x, top, width, bottom - top);
            if (m.kind() != kind) {
                kind = m.kind();
                g.setColor(colors[kind.ordinal()]);
            }
            x = r.x;
            width = r.width;
            top = r.y;
            bottom = r.y + r.height;
        }
        if (kind != null)
            g.fillRect(x, top, width, bottom - top);
    }

    /**
     * The marks drawn within slack pixels of y in track, across its width,
     * nearest first and of those the highest priority.
     */
    List<Mark> near(int y, int slack, Rectangle track, int window, boolean textLeft) {
        record Hit(Mark mark, int distance) {}
        final Map<Mark, Rectangle> at = places(track, window, textLeft);
        final List<Hit> hits = new ArrayList<>();
        for (Mark m : marks) {
            final Rectangle r = at.get(m);
            if (r == null)
                continue;
            final int distance = y < r.y ? r.y - y : Math.max(0, y - (r.y + r.height - 1));
            if (distance <= slack)
                hits.add(new Hit(m, distance));
        }
        hits.sort(Comparator.comparingInt(Hit::distance).thenComparing(h -> -h.mark().kind().ordinal()));
        return hits.stream().map(Hit::mark).toList();
    }

    /** What marks are, a line each, as a tool tip shows them: at most max, then how many more. */
    static String toolTip(List<Mark> marks, int max) {
        final StringBuilder sb = new StringBuilder("<html>");
        for (int i = 0; i < marks.size() && i < max; i++) {
            final Mark m = marks.get(i);
            if (i > 0)
                sb.append("<br>");
            final int first = m.lineNumber() + 1;
            final String where = m.lines() > 1 ? "lines " + first + "-" + (first + m.lines() - 1) : "line " + first;
            sb.append(Display.escapeHtml(m.text() + ", " + where));
        }
        if (marks.size() > max)
            sb.append("<br>and ").append(marks.size() - max).append(" more");
        return sb.append("</html>").toString();
    }

    // Of 255: subdued on the track, the thumb and the marks under showing through.
    private static final int ALPHA = 140;

    /** Each kind's color, by ordinal, for marks on a track this color. */
    static Color[] colors(Editor editor, Color track) {
        final boolean dark = DefaultTheme.isDark(track);
        final Color[] colors = new Color[Kind.values().length];
        final Mode mode = editor.getMode();
        for (Kind kind : Kind.values()) {
            final Color c = switch (kind) {
                case MODIFIED -> orElse(mode.getColorProperty(Property.COLOR_CHANGE), DefaultTheme.getColor("change"));
                case SAVED -> orElse(
                    mode.getColorProperty(Property.COLOR_SAVED_CHANGE),
                    DefaultTheme.getColor("savedChange"));
                case ADDED -> Signs.color(Signs.Kind.ADDED, track);
                case CHANGED -> Signs.color(Signs.Kind.CHANGED, track);
                case DELETED -> Signs.color(Signs.Kind.DELETED, track);
                case CONFLICT ->
                        preference("color.scrollMarkConflict", dark ? new Color(0xf0883e) : new Color(0xbc4c00));
                case SEARCH -> preference(
                    "color.scrollMarkSearch",
                    vivid(editor.getFormatter().getSearchMatchBackgroundColor(), dark));
                case BOOKMARK ->
                        preference("color.scrollMarkBookmark", dark ? new Color(0xc297f0) : new Color(0x8250df));
            };
            colors[kind.ordinal()] = new Color(c.getRed(), c.getGreen(), c.getBlue(), ALPHA);
        }
        return colors;
    }

    private static Color preference(String key, Color fallback) {
        final Preferences prefs = Editor.preferences();
        return orElse(prefs != null ? prefs.getColorProperty(key) : null, fallback);
    }

    // A search highlight is pale, to sit behind text; on the track it would
    // not show.
    private static Color vivid(Color highlight, boolean dark) {
        final float[] hsb = highlight == null
                ? null
                : Color.RGBtoHSB(highlight.getRed(), highlight.getGreen(), highlight.getBlue(), null);
        // A grey has no hue to make vivid: its hue 0 would be red.
        if (hsb == null || hsb[1] < 0.1f)
            return dark ? new Color(0xe3b341) : new Color(0xbf8700);
        return Color
                .getHSBColor(hsb[0], Math.max(hsb[1], 0.75f), dark ? Math.max(hsb[2], 0.85f) : Math.min(hsb[2], 0.8f));
    }

    private static Color orElse(Color c, Color fallback) {
        return c != null ? c : fallback;
    }
}
