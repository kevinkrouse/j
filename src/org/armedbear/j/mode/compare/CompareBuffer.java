/*
 * CompareBuffer.java
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

import static org.armedbear.j.Constants.REFRAME;
import static org.armedbear.j.Constants.REPAINT;
import static org.armedbear.j.Constants.SIDEBAR_BUFFER_LIST_CHANGED;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Folds;
import org.armedbear.j.Formatter;
import org.armedbear.j.Frame;
import org.armedbear.j.FrameView;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.Menu;
import org.armedbear.j.Mode;
import org.armedbear.j.Position;
import org.armedbear.j.Property;
import org.armedbear.j.SessionProperties;
import org.armedbear.j.Sidebar;
import org.armedbear.j.TextLine;
import org.armedbear.j.ToolBar;
import org.armedbear.j.View;
import org.armedbear.j.ViewBuffer;
import org.armedbear.j.VirtualText;
import org.armedbear.j.WindowLayout;
import org.armedbear.j.mode.compare.DiffAlignment.Change;
import org.armedbear.j.mode.compare.DiffAlignment.Row;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Whitespace;

/**
 * A comparison: the old text at the left and the new at the right, each a
 * read-only buffer in the file's mode, its changes shaded. The new side is
 * the view, across the frame as the log is, and the old side its part.
 * Scrolling either scrolls the other: row for row with compareAlign, which
 * keeps the sides level with filler rows, else through the changes
 * ({@link ScrollMap}). The caret moving on one takes the other's to its
 * line, but with the scroll lock off, kept in the session. Its alignment
 * ignores the {@link Whitespace} diffs ignore. Its folds are its own,
 * {@link CompareFolds}.
 */
public final class CompareBuffer extends ViewBuffer implements FrameView, DiffWhitespace.Listener {
    /** Where its texts come from, kept up with while it is open. */
    public interface Source {
        /** It is shown again, as after being out of every window. */
        void shown();

        /** It closed, or another source took over. */
        void stop();
    }

    // What it compares: a file's key, so that comparing it again shows this one.
    private final String key;
    private final OldSide old;
    private DiffAlignment alignment = DiffAlignment.of(List.of(), List.of());
    // Whether the sides are kept level by filler rows; else scrolled
    // together through map.
    private boolean aligned;
    private ScrollMap map = ScrollMap.of(List.of(), 0);
    private Side oldText = Side.NONE;
    private Side newText = Side.NONE;
    private List<Line> changeStarts = List.of();
    private WindowLayout.Node before;
    private Source source;
    private boolean disposed;
    // While one side moves the other, the other leaves it alone.
    private boolean binding;
    private final SideKeys keys = new SideKeys();
    private final CompareFolds folds = new CompareFolds(this);
    private final Map<Editor, CompareTrack> tracks = new WeakHashMap<>();
    private SideGeometry oldGeometry;
    private SideGeometry newGeometry;

    private CompareBuffer(Mode mode, String key, String oldTitle, String newTitle) {
        super(mode, newTitle, "");
        this.key = key;
        old = new OldSide(mode, this, oldTitle);
    }

    /**
     * oldText and newText side by side across frame, in mode; one open
     * for key already shows them in place of what it had, and none is for a
     * null key. With no frame, as in tests, only made.
     */
    public static CompareBuffer open(
            Frame frame,
            Mode mode,
            String key,
            String oldTitle,
            Side oldText,
            String newTitle,
            Side newText) {
        CompareBuffer b = null;
        for (CompareBuffer each : openComparisons())
            if (key != null && key.equals(each.key))
                b = each;
        final DiffAlignment alignment = DiffAlignment.of(oldText, newText, DiffWhitespace.ignored());
        if (b == null) {
            b = new CompareBuffer(mode, key, oldTitle, newTitle);
            b.show(oldText, newText, alignment, true);
        } else {
            // Not across the frame now: the windows to come back to are these.
            if (frame != null && (frame.findEditor(b) == null || frame.findEditor(b.old) == null))
                b.setBefore(null);
            b.set(mode, oldTitle, oldText, newTitle, newText, alignment);
        }
        if (frame != null)
            frame.showView(b, b);
        return b;
    }

    /**
     * The two texts again, as after either changed, alignment theirs: nothing
     * if neither did. A side whose text is the same keeps its lines.
     */
    public void update(Side oldText, Side newText, DiffAlignment alignment) {
        if (disposed || oldText.equals(this.oldText) && newText.equals(this.newText))
            return;
        show(oldText, newText, alignment, false);
    }

    /**
     * It is a part of owner, whose view lays it out with its own windows,
     * keys mapping that view's keys over its sides'.
     */
    public void embed(Buffer owner, Consumer<KeyMap> keys) {
        setOwner(owner);
        this.keys.setExtra(keys);
    }

    /**
     * The caret on side's line, by number, or on its first change, or first
     * line, for a negative one; the other side's caret and scroll to match.
     */
    public void place(ViewBuffer side, int line) {
        final List<Line> starts = side == old ? old.getChangeStarts() : changeStarts;
        final Line l = line >= 0 ? side.getLine(Math.min(line, side.getLastLine().lineNumber()))
                : starts.isEmpty() ? side.getFirstLine()
                : starts.get(0);
        final Line dot = jumpTarget(side, l);
        final ViewBuffer other = side == old ? this : old;
        for (Editor ed : side.windows()) {
            ed.setDot(dot, 0);
            ed.moveCaretToDotCol();
            ed.setMark(null);
            ed.setTopLine(side.getFirstLine().shown());
            ed.getDisplay().setPixelsAboveTopLine(0);
            ed.setUpdateFlag(REFRAME | REPAINT);
            ed.updateDisplay();
            scrollWith(ed, other);
            caretWith(ed, other, side == old);
        }
    }

    /**
     * The new side's line, by number, at the row of side's line, or the one
     * before it there: the line itself on the new side.
     */
    public int newLine(Buffer side, int line) {
        if (side != old)
            return line;
        return line < alignment.oldSize() ? Math.max(0, alignment.otherLine(line, true)) : 0;
    }

    /** s keeps it up to date until it closes, in place of the one before; null for none. */
    public void follow(Source s) {
        if (source != null && source != s)
            source.stop();
        source = s;
    }

    /** Whether a window shows either side. */
    public boolean isShown() {
        return !windows().isEmpty() || !old.windows().isEmpty();
    }

    /**
     * oldText and newText, titled so, in mode, alignment theirs, in place of
     * what it shows: only the titles change if neither text did and the mode
     * is the same.
     */
    public void set(Mode mode, String oldTitle, Side oldText, String newTitle, Side newText, DiffAlignment alignment) {
        if (disposed)
            return;
        retitle(old, oldTitle);
        retitle(this, newTitle);
        if (getMode() != mode) {
            setMode(mode);
            old.setMode(mode);
            show(oldText, newText, alignment, true);
        } else
            update(oldText, newText, alignment);
    }

    /** title for the old side, shown in its windows. */
    public void setOldTitle(String title) {
        retitle(old, title);
    }

    // side's title, shown in its windows, the frame's title and the sidebar.
    private static void retitle(ViewBuffer side, String title) {
        if (title.equals(side.getTitle()))
            return;
        side.setTitle(title);
        for (Editor ed : side.windows()) {
            ed.updateLocation();
            if (ed.getFrame() != null)
                ed.getFrame().updateTitle();
        }
        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_BUFFER_LIST_CHANGED);
    }

    // The two texts shown, their windows kept where they were. A side whose
    // text is the same keeps its lines and their parse, unless relay, as
    // for a new mode; and the other side scrolls to match it.
    private void show(Side oldText, Side newText, DiffAlignment alignment, boolean relay) {
        final boolean sameOld = oldText.equals(this.oldText);
        final boolean sameNew = newText.equals(this.newText);
        this.oldText = oldText;
        this.newText = newText;
        this.alignment = alignment;
        aligned = Editor.preferences().getBooleanProperty(Property.COMPARE_ALIGN);
        map = ScrollMap.of(this.alignment.changes(), this.alignment.oldSize());
        binding = true;
        try {
            final Runnable placeOld = kept(old);
            final Runnable placeNew = kept(this);
            old.setChangeStarts(lay(old, oldText.lines(), newText.lines(), true, sameOld && !relay));
            changeStarts = lay(this, newText.lines(), oldText.lines(), false, sameNew && !relay);
            folds.reset(this.alignment, sameOld, sameNew);
            placeOld.run();
            placeNew.run();
        }
        finally {
            binding = false;
        }
        // Unlocked, each side stays where it is scrolled to.
        if (relay || scrollLocked()) {
            final ViewBuffer from = sameOld && !sameNew ? old : this;
            for (Editor ed : from.windows())
                scrollWith(ed, from == old ? this : old);
        }
        // Worked out before the whitespace ignored changed: again, as it is now.
        if (isShown())
            realign();
    }

    // The scroll lock, kept in the session.
    private static final String SCROLL_LOCK_KEY = "compare.scrollLock";
    // What whitespace an alignment is being worked out again ignoring, or null.
    private Whitespace realigning;
    // Where alignments are worked out again; tests' at once.
    static Executor realigner = r -> Background.start("compare realign", r);

    /** Whether the sides scroll together and their carets follow each other, as compareScrollLock last left them. */
    public static boolean scrollLocked() {
        final SessionProperties session = Editor.getSessionProperties();
        return session == null || session.getBooleanProperty(SCROLL_LOCK_KEY, true);
    }

    /** The scroll lock off, or on again, each comparison's other side matching the one in use. */
    static boolean toggleScrollLock() {
        final SessionProperties session = Editor.getSessionProperties();
        if (session == null)
            return true;
        final boolean locked = !scrollLocked();
        session.setBooleanProperty(SCROLL_LOCK_KEY, locked);
        if (locked)
            for (CompareBuffer c : openComparisons())
                c.resync();
        return locked;
    }

    // Shown, aligned again; else once shown again.
    @Override
    public void diffWhitespaceChanged() {
        if (isShown())
            realign();
    }

    // Aligned again in the background, if the whitespace ignored has changed
    // and that is not being worked out already.
    private void realign() {
        final Whitespace ignored = DiffWhitespace.ignored();
        if (disposed || alignment.ignored() == ignored || realigning == ignored)
            return;
        realigning = ignored;
        final Side o = oldText;
        final Side n = newText;
        realigner.execute(() -> {
            DiffAlignment a = null;
            try {
                a = DiffAlignment.of(o, n, ignored);
            }
            finally {
                final DiffAlignment done = a;
                SwingUtilities.invokeLater(() -> {
                    if (realigning == ignored)
                        realigning = null;
                    if (done == null || disposed)
                        return;
                    // Either text or the whitespace ignored changed since: again, as they are now.
                    if (o != oldText || n != newText || DiffWhitespace.ignored() != ignored) {
                        if (isShown())
                            realign();
                    } else if (alignment.ignored() != ignored)
                        show(o, n, done, false);
                });
            }
        });
    }

    // The comparisons not closed.
    private static List<CompareBuffer> openComparisons() {
        final List<CompareBuffer> out = new ArrayList<>();
        for (Buffer b : Editor.getBufferList())
            if (b instanceof CompareBuffer c && !c.disposed)
                out.add(c);
        return out;
    }

    /** Both sides in mode, as changing either's mode does. */
    void modeTo(Mode mode) {
        if (disposed || getMode() == mode)
            return;
        setMode(mode);
        old.setMode(mode);
        show(oldText, newText, alignment, true);
    }

    @Override
    public void changeMode(Mode mode) {
        modeTo(mode);
    }

    // What puts each window showing side back where it is now, once side is
    // laid out again, by line number. One whose caret had to move, into a
    // fold or off the end, is framed again; the rest stay scrolled where
    // they were.
    private static Runnable kept(ViewBuffer side) {
        final List<Editor> eds = side.windows();
        final List<View> views = new ArrayList<>();
        for (Editor ed : eds) {
            ed.saveView();
            final View view = ed.getView(side);
            view.invalidate();
            views.add(view);
        }
        return () -> {
            for (int i = 0; i < eds.size(); i++) {
                final Editor ed = eds.get(i);
                final View view = views.get(i);
                view.resolve(side);
                final Line was = view.getDot().getLine();
                final Line dot = side.jumpTarget(was);
                final boolean moved = dot != was || was.lineNumber() != view.getDotLineNumber();
                final Line top = moved && view.getTopLine().lineNumber() > dot.lineNumber() ? dot : view.getTopLine();
                ed.setDot(dot, dot != was ? 0 : view.getDot().getOffset());
                ed.moveCaretToDotCol();
                final Position mark = view.getMark();
                ed.setMark(moved || mark == null || mark.getLine().isHidden() ? null : mark);
                ed.setTopLine(top);
                ed.getDisplay().setPixelsAboveTopLine(top == view.getTopLine() ? view.getPixelsAboveTopLine() : 0);
                ed.setUpdateFlag(moved ? REFRAME | REPAINT : REPAINT);
                ed.updateDisplay();
            }
        };
    }

    /** side's lines, by number. */
    static List<Line> lines(ViewBuffer side) {
        final List<Line> out = new ArrayList<>();
        for (Line l = side.getFirstLine(); l != null; l = l.next())
            out.add(l);
        return out;
    }

    // Folds: the comparison's own, not the mode's.

    @Override
    public Folds getFolds() {
        // None while it is made.
        return folds != null ? folds.of(false) : super.getFolds();
    }

    /** The old side's folds. */
    Folds oldFolds() {
        return folds.of(true);
    }

    @Override
    protected void renumbered() {
        sideRenumbered(this);
    }

    /** side's lines numbered again, as after a fold opens or closes there by any way. */
    void sideRenumbered(ViewBuffer side) {
        // Not while it is made or laid out, which sets both sides' folds.
        if (folds != null && !binding && !disposed)
            folds.read(side);
    }

    @Override
    public Line jumpTarget(Line line) {
        // None while it is made.
        return folds != null ? folds.handle(this, line) : line;
    }

    /** Where a jump to side's line lands. */
    Line jumpTarget(ViewBuffer side, Line line) {
        return folds.handle(side, line);
    }

    @Override
    public void restoreView(Editor editor) {
        super.restoreView(editor);
        sideShown(editor);
    }

    /** ed shows a side again: off lines folded away, aligned as now, and the source told. */
    void sideShown(Editor ed) {
        offFolds(ed);
        realign();
        if (source != null)
            source.shown();
    }

    /**
     * ed's caret and top line moved off lines folded away: to be there would
     * open the fold. Whether the caret moved.
     */
    static boolean offFolds(Editor ed) {
        boolean moved = false;
        if (ed.getDot() != null && ed.getDotLine().isHidden()) {
            ed.setDot(ed.getBuffer().jumpTarget(ed.getDotLine()), 0);
            ed.setMark(null);
            ed.moveCaretToDotCol();
            moved = true;
        }
        final Line top = ed.getDisplay().getTopLine();
        if (top != null && top.isHidden()) {
            ed.getDisplay().setTopLine(top.shown());
            ed.getDisplay().setPixelsAboveTopLine(0);
        }
        return moved;
    }

    // The track.

    @Override
    public JComponent besideDisplay(Editor ed) {
        pruneTracks();
        return tracks.computeIfAbsent(ed, e -> new CompareTrack(this, e));
    }

    // The tracks of windows that show it still: a track holds its window, so
    // the map's weak keys alone would keep them all.
    private void pruneTracks() {
        tracks.keySet().removeIf(e -> e.getBuffer() != this || !Editor.getEditorList().contains(e));
    }

    /** The tracks painted again, as after either side scrolls or a fold opens or closes. */
    void repaintTracks() {
        pruneTracks();
        for (CompareTrack t : tracks.values())
            t.repaint();
    }

    /** The old side. */
    public OldSide getOld() {
        return old;
    }

    public DiffAlignment getAlignment() {
        return alignment;
    }

    /** The line positions of the side ed shows. */
    static SideGeometry geometryOf(Editor ed) {
        final CompareBuffer c = of(ed.getBuffer());
        if (ed.getBuffer() == c.old)
            return c.oldGeometry = SideGeometry.of(c.old, c.oldGeometry);
        return c.newGeometry = SideGeometry.of(c, c.newGeometry);
    }

    // A side's lines, its formatter over its mode's, and, aligned, its
    // filler rows; other is the other side's lines. Returns where its
    // changes start. With keep, its lines are the ones it has, parsed.
    private List<Line> lay(ViewBuffer side, List<String> lines, List<String> other, boolean isOld, boolean keep) {
        if (!keep) {
            final List<Line> ls = new ArrayList<>(lines.size());
            for (String s : lines)
                ls.add(new TextLine(s));
            side.setLines(ls);
        }
        final List<Line> byNumber = lines(side);
        // Over the mode's formatter the side has: one it had over already.
        final Formatter had = side.getFormatter();
        final SideFormatter f =
                new SideFormatter(had instanceof SideFormatter s ? s.own() : had, alignment, other, isOld, aligned);
        final DiffAlignment a = alignment;
        final boolean withFillers = aligned;
        // A new theme: the fillers and fold labels in its colors.
        f.onReset(() -> {
            fillers(side, byNumber, withFillers ? a : null, isOld, f);
            if (!binding)
                folds.relabel(side);
        });
        side.setFormatter(f);
        // Now, not on the idle thread's next pass: till then a comment's lines
        // would be formatted as code.
        if (!keep)
            f.parseBuffer();
        fillers(side, byNumber, aligned ? alignment : null, isOld, f);
        return starts(byNumber, alignment, isOld);
    }

    // A side's filler rows as alignment has them, or none for null. An empty
    // side's blank line is the first of them.
    private static void fillers(
            ViewBuffer side,
            List<Line> byNumber,
            DiffAlignment alignment,
            boolean isOld,
            SideFormatter f) {
        final List<VirtualText> fillers = new ArrayList<>();
        final int size = alignment == null ? 0 : isOld ? alignment.oldSize() : alignment.newSize();
        for (int line = -1; alignment != null && line < size; line++) {
            int n = isOld ? alignment.fillersAfterOld(line) : alignment.fillersAfterNew(line);
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
        return panes(focus == old ? old : this);
    }

    /** Its two sides' windows, side by side, the one of focus focused, if either is. */
    public WindowLayout.Node panes(Buffer focus) {
        return new WindowLayout.Split(true,
                List.of(window(old, focus == old), window(this, focus == this)),
                List.of(0.5, 0.5));
    }

    // side's window as it is or was last, on lines shown: one on a line
    // folded away would open its fold.
    private static WindowLayout.Window window(ViewBuffer side, boolean focused) {
        final WindowLayout.Window w = WindowLayout.Window.showing(side, focused);
        final Line dot = side.getLine(w.dotLine());
        final Line top = side.getLine(w.topLine());
        if ((dot == null || !dot.isHidden()) && (top == null || !top.isHidden()))
            return w;
        final int dotLine = dot == null ? w.dotLine() : side.jumpTarget(dot).lineNumber();
        return new WindowLayout.Window(side,
                dotLine,
                dot == null || dot.isHidden() ? 0 : w.dotOffset(),
                top == null ? w.topLine() : Math.min(top.shown().lineNumber(), dotLine),
                focused);
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
        return getOwner() != null ? super.getFrameView() : this;
    }

    @Override
    public List<Buffer> getChildren() {
        return List.of(old);
    }

    /** Closes it: the windows from before come back, and both sides go. */
    public void close(Frame frame) {
        if (disposed)
            return;
        disposed = true;
        if (source != null)
            source.stop();
        source = null;
        tracks.clear();
        if (frame != null && getOwner() == null)
            frame.leaveView(this, List.of(this, old));
        // A part of no view now, so that killing it is not the owner's.
        setOwner(null);
        for (Buffer b : new Buffer[] { old, this })
            if (Editor.getBufferList().contains(b))
                b.kill();
    }

    /** Whether it has been closed. */
    public boolean isClosed() {
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

    // Killed on its own, it closes, or its owner closes it.
    @Override
    public synchronized void kill() {
        if (disposed)
            super.kill();
        else if (!closedByOwner())
            close(shownIn());
    }

    /** The frame a side is shown in, or null. */
    Frame shownIn() {
        for (ViewBuffer side : List.of(this, old))
            for (Editor ed : side.windows())
                if (ed.getFrame() != null)
                    return ed.getFrame();
        return null;
    }

    SideKeys keys() {
        return keys;
    }

    // Its own toolbar and Mode menu, not its file's mode's, when it is the frame's view.

    @Override
    public ToolBar getToolBar(Frame frame) {
        return CompareCommands.toolBar(frame);
    }

    @Override
    public void populateModeMenu(Editor editor, Menu menu) {
        CompareCommands.populateModeMenu(editor, menu);
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
        scrolledBy(ed, old);
    }

    @Override
    public void afterCommand(Editor editor) {
        caretMovedBy(editor, old, false);
    }

    /** ed's side scrolled: the other with it, with the scroll lock on. */
    void scrolledBy(Editor ed, ViewBuffer other) {
        if (scrollLocked())
            scrollWith(ed, other);
        else if (!disposed)
            repaintTracks();
    }

    /** ed's caret moved: the other side's with it, with the scroll lock on. */
    void caretMovedBy(Editor ed, ViewBuffer other, boolean fromOld) {
        if (scrollLocked())
            caretWith(ed, other, fromOld);
    }

    /** r run with the scroll lock held, as while a fold change moves the windows. */
    void whileBinding(Runnable r) {
        final boolean was = binding;
        binding = true;
        try {
            r.run();
        }
        finally {
            binding = was;
        }
    }

    /** The other side's window in ed's frame scrolled to match ed's. */
    void scrollWith(Editor ed, ViewBuffer other) {
        if (disposed)
            return;
        repaintTracks();
        if (binding || ed.getDisplay().getTopLine() == null)
            return;
        final Editor partner = windowIn(ed.getFrame(), other);
        if (partner == null || partner.getDisplay().getTopLine() == null)
            return;
        final int dy = (aligned ? y(ed) : mapped(ed, partner, other != old)) - y(partner);
        if (dy == 0)
            return;
        whileBinding(() -> {
            // Not painted by the scroll, whose blit leaves the rest to its own paint.
            partner.getDisplay().scrollByPixels(dy);
            partner.getDisplay().repaint();
            partner.updateScrollBars();
        });
    }

    /**
     * The other side's caret to the line at the row of ed's caret, or, where
     * that row is a filler there, to the line over it; only to a line in
     * view of it where framing its window to show the line would pull it
     * from where the scroll lock put it.
     */
    void caretWith(Editor ed, ViewBuffer other, boolean fromOld) {
        if (binding || disposed || ed.getDot() == null)
            return;
        final Editor partner = windowIn(ed.getFrame(), other);
        if (partner == null)
            return;
        final int line = ed.getDotLineNumber();
        final int size = fromOld ? alignment.oldSize() : alignment.newSize();
        if (line >= size)
            return;
        final Row row = alignment.rows().get(fromOld ? alignment.rowOfOld(line) : alignment.rowOfNew(line));
        final boolean filler = (fromOld ? row.newLine() : row.oldLine()) < 0;
        final Line l = other.getLine(Math.max(0, alignment.otherLine(line, fromOld)));
        if (l == null || partner.getDotLine() == l)
            return;
        if ((filler || !aligned) && !inView(partner, l))
            return;
        whileBinding(() -> {
            partner.setDot(l, Math.min(ed.getDotOffset(), l.length()));
            partner.moveCaretToDotCol();
            partner.updateDisplay();
        });
    }

    // Whether all of l's text is in ed's window.
    private static boolean inView(Editor ed, Line l) {
        if (ed.getDisplay().getTopLine() == null || l.isHidden())
            return false;
        final int top = y(ed);
        final int y = geometryOf(ed).top(l.lineNumber());
        return y >= top && y + l.getHeight() <= top + ed.getDisplay().getHeight();
    }

    /**
     * Where partner's window goes, unaligned: the place at the middle of
     * ed's, through the changes, at the middle of partner's. The place
     * slides to the window's top over its first half window down, and to its
     * bottom over its last, so that both reach the ends together and leave
     * them smoothly.
     */
    private int mapped(Editor ed, Editor partner, boolean fromOld) {
        final SideGeometry g = geometryOf(ed);
        final SideGeometry pg = geometryOf(partner);
        final int y = y(ed);
        final int height = Math.max(1, ed.getDisplay().getHeight());
        final int max = Math.max(0, g.height() - height);
        final int down = Math.max(0, Math.min(height, Math.min(y, height / 2) + Math.max(0, y - (max - height / 2))));
        final double place = g.placeAt(y + down);
        final double there = fromOld ? map.toNew(place) : map.toOld(place);
        final int partnerHeight = partner.getDisplay().getHeight();
        final int partnerDown = (int) Math.round(down * (double) partnerHeight / height);
        final int partnerMax = Math.max(0, pg.height() - partnerHeight);
        return Math.max(0, Math.min(partnerMax, pg.yOf(there) - partnerDown));
    }

    /**
     * With the scroll lock on, the other side scrolled to match the window
     * in use again, as after a fold opens or closes, which can move what is
     * at its middle; with neither side's window in use, the new side's.
     */
    void resync() {
        if (disposed || !scrollLocked())
            return;
        final Editor current = Editor.currentEditor();
        final List<Editor> shown = windows();
        final Editor ed = current != null && (current.getBuffer() == this || current.getBuffer() == old) ? current
                : shown.isEmpty() ? null
                : shown.get(0);
        if (ed != null && ed.getDisplay().getTopLine() != null)
            scrollWith(ed, ed.getBuffer() == old ? this : old);
    }

    /** Where ed's window is down its side, in pixels. */
    static int y(Editor ed) {
        final Line top = ed.getDisplay().getTopLine();
        return geometryOf(ed).start(top.lineNumber()) + ed.getDisplay().getPixelsAboveTopLine();
    }

    // b's window in frame; with none, as in tests, b's window in none.
    static Editor windowIn(Frame frame, ViewBuffer b) {
        if (frame != null)
            return frame.findEditor(b);
        for (Editor e : b.windows())
            if (e.getFrame() == null)
                return e;
        return null;
    }

    /** The comparison buffer b is a side of, or null. */
    public static CompareBuffer of(Buffer b) {
        return b instanceof CompareBuffer s ? s : b instanceof OldSide o ? o.getOwner() : null;
    }
}
