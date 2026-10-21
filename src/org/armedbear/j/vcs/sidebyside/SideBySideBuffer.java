/*
 * SideBySideBuffer.java
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.Frame;
import org.armedbear.j.FrameView;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.TextLine;
import org.armedbear.j.VirtualText;
import org.armedbear.j.WindowLayout;
import org.armedbear.j.vcs.ViewBuffer;
import org.armedbear.j.vcs.diff.DiffAlignment;
import org.armedbear.j.vcs.diff.DiffAlignment.Change;
import org.armedbear.j.vcs.diff.DiffAlignment.Row;

/**
 * A side-by-side diff: the old text at the left and the new at the right,
 * each a read-only buffer in the file's mode, its lines of a change shaded
 * and filler rows where the other side has more, so that the lines both
 * have are level. The new side is the view, across the frame as the log
 * is, and the old side its part. Scrolling either scrolls the other to the
 * same row, and the caret moving on one takes the other's to its row.
 */
public final class SideBySideBuffer extends ViewBuffer implements FrameView {
    /**
     * A side's text: its lines as shown, and as compared, which differ in
     * that a last line with no newline is marked, so that adding one is a
     * change.
     */
    public record Text(List<String> lines, List<String> keys) {
        public Text {
            lines = List.copyOf(lines);
            keys = List.copyOf(keys);
        }
    }

    // Where a window was last, for the scroll lock to act only on a move.
    private record Seen(Line top, int pixels) {}

    // What it diffs: a file's key, so that diffing it again shows this one.
    private final String key;
    private final OldSide old;
    private DiffAlignment alignment = DiffAlignment.of(List.of(), List.of());
    private Text oldText = new Text(List.of(), List.of());
    private Text newText = new Text(List.of(), List.of());
    private List<Line> changeStarts = List.of();
    private WindowLayout.Node before;
    private boolean disposed;
    // While one side moves the other, the other leaves it alone.
    private boolean binding;
    private final SideKeys keys = new SideKeys();
    private final Map<Editor, Seen> seen = new WeakHashMap<>();

    private SideBySideBuffer(Mode mode, String key, String oldTitle, String newTitle) {
        super(mode, newTitle, "");
        this.key = key;
        old = new OldSide(mode, this, oldTitle);
    }

    /**
     * oldText and newText side by side across frame, in mode; one open
     * for key already shows them in place of what it had, in mode. With no
     * frame, as in tests, only made.
     */
    public static SideBySideBuffer open(
            Frame frame,
            Mode mode,
            String key,
            String oldTitle,
            Text oldText,
            String newTitle,
            Text newText) {
        SideBySideBuffer b = null;
        for (Buffer each : Editor.getBufferList())
            if (each instanceof SideBySideBuffer s && !s.disposed && s.key.equals(key))
                b = s;
        if (b == null)
            b = new SideBySideBuffer(mode, key, oldTitle, newTitle);
        else {
            b.setTitle(newTitle);
            b.old.setTitle(oldTitle);
            if (b.getMode() != mode) {
                b.setMode(mode);
                b.old.setMode(mode);
            }
            // Not across the frame now: the windows to come back to are these.
            if (frame != null && (frame.findEditor(b) == null || frame.findEditor(b.old) == null))
                b.setBefore(null);
        }
        b.show(oldText, newText);
        if (frame != null)
            frame.showView(b, b);
        return b;
    }

    /** Shows the two texts, aligned, their windows kept at their lines. */
    void show(Text oldText, Text newText) {
        this.oldText = oldText;
        this.newText = newText;
        alignment = DiffAlignment.of(oldText.keys(), newText.keys());
        binding = true;
        try {
            relay(old, () -> old.lay(oldText.lines(), newText.lines()));
            relay(this, () -> changeStarts = lay(this, newText.lines(), oldText.lines(), alignment, false));
        }
        finally {
            binding = false;
        }
        seen.clear();
        for (Editor ed : windows())
            scrollWith(ed, old);
    }

    /** Both sides in mode, as changing either's mode does. */
    void modeTo(Mode mode) {
        if (disposed || getMode() == mode)
            return;
        setMode(mode);
        old.setMode(mode);
        show(oldText, newText);
    }

    @Override
    public void changeMode(Mode mode) {
        modeTo(mode);
    }

    // side laid out again by lay, each window showing it put back at the
    // lines it had, or the last.
    private static void relay(ViewBuffer side, Runnable lay) {
        final List<Editor> eds = side.windows();
        final int[] dots = new int[eds.size()];
        final int[] tops = new int[eds.size()];
        for (int i = 0; i < eds.size(); i++) {
            final Editor ed = eds.get(i);
            final Line top = ed.getDisplay().getTopLine();
            dots[i] = ed.getDot() == null ? 0 : ed.getDotLineNumber();
            tops[i] = top == null ? 0 : top.lineNumber();
        }
        lay.run();
        for (int i = 0; i < eds.size(); i++)
            place(eds.get(i), lineAt(side, dots[i]), lineAt(side, tops[i]));
    }

    private static Line lineAt(ViewBuffer side, int n) {
        Line l = side.getFirstLine();
        for (int i = 0; i < n && l.next() != null; i++)
            l = l.next();
        return l;
    }

    /** The old side. */
    public OldSide getOld() {
        return old;
    }

    public DiffAlignment getAlignment() {
        return alignment;
    }

    /**
     * A side's lines, its formatter over its mode's, and its filler rows;
     * other is the other side's lines. Returns where its changes start. An empty side has a blank line as a
     * buffer must, which is its first filler.
     */
    static List<Line> lay(
            ViewBuffer side,
            List<String> lines,
            List<String> other,
            DiffAlignment alignment,
            boolean isOld) {
        final List<Line> ls = new ArrayList<>(lines.size());
        for (String s : lines)
            ls.add(new TextLine(s));
        side.setLines(ls);
        // Each line once, by number: the buffer is walked here, not per lookup.
        final List<Line> byNumber = new ArrayList<>();
        for (Line l = side.getFirstLine(); l != null; l = l.next())
            byNumber.add(l);
        // Over the mode's formatter the side has: one it had over already.
        final Formatter had = side.getFormatter();
        final SideFormatter f =
                new SideFormatter(had instanceof SideFormatter s ? s.own() : had, alignment, other, isOld);
        f.onReset(() -> fillers(side, byNumber, alignment, isOld, f));
        side.setFormatter(f);
        // Now, not on the idle thread's next pass: till then a comment's lines
        // would be formatted as code.
        f.parseBuffer();
        fillers(side, byNumber, alignment, isOld, f);
        return starts(byNumber, alignment, isOld);
    }

    private static void fillers(
            ViewBuffer side,
            List<Line> byNumber,
            DiffAlignment alignment,
            boolean isOld,
            SideFormatter f) {
        final List<VirtualText> fillers = new ArrayList<>();
        final int size = isOld ? alignment.oldSize() : alignment.newSize();
        for (int line = -1; line < size; line++) {
            int n = isOld ? alignment.fillersAfterOld(line) : alignment.fillersAfterNew(line);
            // An empty side's blank line is a row of them: the rest under it.
            final boolean empty = size == 0;
            if (empty)
                --n;
            if (n <= 0)
                continue;
            final List<List<VirtualText.Run>> rows = new ArrayList<>();
            for (int i = 0; i < n; i++)
                rows.add(List.of(VirtualText.Run.of("")));
            // Before the first line, over it; else under the line.
            final Line at = byNumber.get(Math.max(line, 0));
            final boolean above = line < 0 && !empty;
            fillers.add(
                new VirtualText(at,
                        above ? VirtualText.Placement.ABOVE : VirtualText.Placement.BELOW,
                        rows,
                        0,
                        f.fillerBackground(),
                        true));
        }
        side.getVirtualText().set("diff", fillers);
    }

    // Each change's first line on a side, or the line before it where it
    // has none there.
    private static List<Line> starts(List<Line> byNumber, DiffAlignment alignment, boolean isOld) {
        final List<Line> out = new ArrayList<>();
        for (Change c : alignment.changes()) {
            final int start = isOld ? c.oldStart() : c.newStart();
            final int count = isOld ? c.oldCount() : c.newCount();
            final int n = Math.min(count > 0 ? start : Math.max(0, start - 1), byNumber.size() - 1);
            if (n < 0)
                continue;
            final Line l = byNumber.get(n);
            if (out.isEmpty() || out.get(out.size() - 1) != l)
                out.add(l);
        }
        return List.copyOf(out);
    }

    // The view.

    @Override
    public WindowLayout.Node layout(Frame frame, Buffer focus) {
        return new WindowLayout.Split(true,
                List.of(
                    WindowLayout.Window.showing(old, focus == old),
                    WindowLayout.Window.showing(this, focus != old)),
                List.of(0.5, 0.5));
    }

    @Override
    public WindowLayout.Node before() {
        return before;
    }

    @Override
    public void setBefore(WindowLayout.Node before) {
        this.before = before;
    }

    @Override
    public FrameView getFrameView() {
        return this;
    }

    @Override
    public List<Buffer> getChildren() {
        return List.of(old);
    }

    /** Closes it: the windows from before come back, and both sides go. */
    void close(Frame frame) {
        if (disposed)
            return;
        disposed = true;
        if (frame != null)
            frame.leaveView(this, List.of(this, old));
        for (Buffer b : new Buffer[] { old, this })
            if (Editor.getBufferList().contains(b))
                b.kill();
    }

    /** Whether it has been closed. */
    boolean isClosed() {
        return disposed;
    }

    // A side killed on its own closes both, as q does.
    @Override
    public boolean partKilled(Buffer part) {
        if (disposed)
            return false;
        kill();
        return true;
    }

    @Override
    public synchronized void kill() {
        if (!disposed)
            close(shownIn());
        else
            super.kill();
    }

    // The frame a side is shown in, or null.
    private Frame shownIn() {
        for (ViewBuffer side : List.of(this, old))
            for (Editor ed : side.windows())
                if (ed.getFrame() != null)
                    return ed.getFrame();
        return null;
    }

    SideKeys keys() {
        return keys;
    }

    // Keys, change starts and the lock, for this side.

    @Override
    protected KeyMap keyMapOver(KeyMap map, boolean modal) {
        return keys.over(map, modal);
    }

    @Override
    public List<Line> getChangeStarts() {
        return changeStarts;
    }

    @Override
    public void scrolled(Editor ed) {
        scrollWith(ed, old);
    }

    @Override
    public void afterCommand(Editor editor) {
        caretWith(editor, old, false);
    }

    /**
     * The other side's window in ed's frame scrolled to the height ed's is
     * at, if ed's has moved: this runs on each update of it.
     */
    void scrollWith(Editor ed, ViewBuffer other) {
        if (disposed)
            return;
        final Seen now = new Seen(ed.getDisplay().getTopLine(), ed.getDisplay().getPixelsAboveTopLine());
        if (now.equals(seen.put(ed, now)) || binding || now.top() == null)
            return;
        final Editor partner = windowIn(ed.getFrame(), other);
        if (partner == null || partner.getDisplay().getTopLine() == null)
            return;
        final int dy = y(ed) - y(partner);
        if (dy == 0)
            return;
        binding = true;
        try {
            // Not painted by the scroll, whose blit leaves the rest to its own paint.
            partner.getDisplay().scrollByPixels(dy);
            partner.getDisplay().repaint();
            partner.updateScrollBars();
        }
        finally {
            binding = false;
        }
    }

    /**
     * The other side's window to where ed's is, and its caret to the line
     * at the row of ed's: or, where that row is a filler there, to the line
     * over it, if that is in view, as a long change's first line may not be.
     */
    void caretWith(Editor ed, ViewBuffer other, boolean fromOld) {
        if (binding || disposed || ed.getDot() == null)
            return;
        final Editor partner = windowIn(ed.getFrame(), other);
        if (partner == null)
            return;
        scrollWith(ed, other);
        final int line = ed.getDotLineNumber();
        final int size = fromOld ? alignment.oldSize() : alignment.newSize();
        if (line >= size)
            return;
        final Row row = alignment.rows().get(fromOld ? alignment.rowOfOld(line) : alignment.rowOfNew(line));
        final boolean filler = (fromOld ? row.newLine() : row.oldLine()) < 0;
        final int target = Math.max(0, alignment.otherLine(line, fromOld));
        final Line l = other.getLine(target);
        if (l == null || partner.getDotLine() == l)
            return;
        if (filler && !inView(partner, l))
            return;
        binding = true;
        try {
            partner.setDot(l, Math.min(ed.getDotOffset(), l.length()));
            partner.moveCaretToDotCol();
            partner.updateDisplay();
        }
        finally {
            binding = false;
        }
    }

    // Whether l's text is in ed's window, by height: its top line may be
    // scrolled past, into the rows under it.
    private static boolean inView(Editor ed, Line l) {
        if (ed.getDisplay().getTopLine() == null)
            return false;
        final int top = y(ed);
        final int y = l.isHidden() ? -1 : ed.getBuffer().getY(l);
        return y >= top && y + l.getHeight() <= top + ed.getDisplay().getHeight();
    }

    // Where ed's window is down its buffer, in pixels.
    private static int y(Editor ed) {
        return ed.getBuffer().getY(ed.getDisplay().getTopLine()) + ed.getDisplay().getPixelsAboveTopLine();
    }

    // b's window in frame; with none, as in tests, b's window in none.
    private static Editor windowIn(Frame frame, ViewBuffer b) {
        if (frame != null)
            return frame.findEditor(b);
        for (Editor e : b.windows())
            if (e.getFrame() == null)
                return e;
        return null;
    }

    /** The side-by-side diff editor's buffer is a side of, or null. */
    public static SideBySideBuffer of(Buffer b) {
        return b instanceof SideBySideBuffer s ? s : b instanceof OldSide o ? o.getOwner() : null;
    }
}
