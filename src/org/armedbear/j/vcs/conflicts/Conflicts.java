/*
 * Conflicts.java
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

package org.armedbear.j.vcs.conflicts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.ChangeColors;
import org.armedbear.j.Constants;
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.FormatTable;
import org.armedbear.j.KeyMapping;
import org.armedbear.j.Line;
import org.armedbear.j.Preferences;
import org.armedbear.j.SystemBuffer;
import org.armedbear.j.VirtualText;
import org.armedbear.j.vim.VimInputHandler;
import org.armedbear.j.vim.VimKeyMap;

/**
 * A buffer's conflicts, while its file is unmerged: each region's lines
 * shaded ours, base and theirs, and above each opener a row of links to
 * take a side. Kept on the buffer as {@link org.armedbear.j.vcs.blame.Blame}
 * is, by the repository's word, not wherever markers appear, since a file
 * may hold them as text. Scanned again on each change, coalesced; the
 * links hold the region's own lines, so they follow edits. The markers
 * are dimmed, but for those of the side the caret is in. On the event
 * thread.
 */
public final class Conflicts implements Buffer.Follower {
    /** The owner of all of it in the buffer's layer. */
    public static final String OWNER = "vcs.conflicts";

    private static final int LABEL_MAX = 30;

    static {
        // The theme changed: each buffer's shown in its colors.
        final Preferences p = Editor.preferences();
        if (p != null)
            p.addPreferencesChangeListener(() -> SwingUtilities.invokeLater(() -> {
                for (Buffer b : Editor.getBufferList())
                    if (b.getConflicts() != null)
                        b.getConflicts().show();
            }));
    }

    /** The region's lines, as the buffer's own, so they follow edits above. */
    record Kept(ConflictMarkers.Region region, Line opener, Line baseMarker, Line separator, Line closer,
            List<Line> ours, List<Line> base, List<Line> theirs) {}

    private final Buffer buffer;
    private List<Kept> kept = List.of();
    // The kept regions' openers, made once per scan.
    private List<Line> openers = List.of();
    // Followers are told on whatever thread changes the text.
    private final AtomicBoolean pending = new AtomicBoolean();
    // The caret's line when last seen, and the markers shown bright for it.
    private Line caret;
    private List<Line> lit = List.of();

    private Conflicts(Buffer buffer) {
        this.buffer = buffer;
    }

    /**
     * buffer has conflicts shown while its file is unmerged; none, and
     * what was shown cleared, otherwise. From any thread.
     */
    public static void attach(Buffer buffer) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> attach(buffer));
            return;
        }
        final Conflicts c = buffer.getConflicts();
        if (wants(buffer)) {
            if (c == null) {
                final Conflicts made = new Conflicts(buffer);
                buffer.setConflicts(made);
                buffer.addFollower(made);
            }
            buffer.getConflicts().scan();
        } else if (c != null) {
            buffer.removeFollower(c);
            buffer.getVirtualText().clear(OWNER);
            buffer.setConflicts(null);
        }
    }

    // A local text file the repository reports unmerged.
    private static boolean wants(Buffer buffer) {
        final File file = buffer.getFile();
        return file != null
                && file.isLocal()
                && buffer.getType() == SystemBuffer.TYPE_NORMAL
                && buffer.getModeId() != Constants.BINARY_MODE
                && buffer.getVCStatusKind() == Constants.VCS_CONFLICT;
    }

    Buffer buffer() {
        return buffer;
    }

    /** The region whose opener-to-closer holds line; null if none. */
    public ConflictMarkers.Region regionAt(Line line) {
        final Kept k = keptAt(line);
        return k == null ? null : k.region();
    }

    /** The regions' opener lines, in buffer order: where next and previous go. */
    public List<Line> openers() {
        return openers;
    }

    // The opener of the region after k, or of the first if k is the last;
    // null when k is the only one.
    Line nextOpener(Kept k) {
        final int i = kept.indexOf(k);
        if (i < 0 || kept.size() < 2)
            return null;
        return kept.get(i + 1 < kept.size() ? i + 1 : 0).opener();
    }

    // The kept region holding line, by its lines' numbers.
    Kept keptAt(Line line) {
        for (Kept k : kept)
            if (line.lineNumber() >= k.opener().lineNumber() && line.lineNumber() <= k.closer().lineNumber())
                return k;
        return null;
    }

    // The kept region whose opener is this very line; null if it is gone.
    Kept byOpener(Line opener) {
        for (Kept k : kept)
            if (k.opener() == opener)
                return k;
        return null;
    }

    @Override
    public void textChanged() {
        if (!pending.compareAndSet(false, true))
            return;
        SwingUtilities.invokeLater(() -> {
            pending.set(false);
            scan();
        });
    }

    @Override
    public void vcsChecked() {
        // Buffer.checkVCS attaches, and so scans, before telling followers.
    }

    /** A view watcher: the caret's side of a conflict lights its markers. */
    public static void viewChanged(Editor editor) {
        if (editor == null || editor.getDot() == null)
            return;
        final Conflicts c = editor.getBuffer() == null ? null : editor.getBuffer().getConflicts();
        if (c == null)
            return;
        c.caret = editor.getDotLine();
        if (!c.litFor(c.caret).equals(c.lit))
            c.show();
    }

    // The buffer's lines, parsed, kept with their Line objects, shown if
    // anything shown changed.
    void scan() {
        final List<Line> lines = new ArrayList<>();
        buffer.withReadLock(() -> {
            for (Line l = buffer.getFirstLine(); l != null; l = l.next())
                lines.add(l);
        });
        final List<String> texts = new ArrayList<>();
        for (Line l : lines)
            texts.add(l.getText());
        final List<Kept> found = new ArrayList<>();
        for (ConflictMarkers.Region r : ConflictMarkers.regions(texts))
            found.add(keep(r, lines));
        if (found.equals(kept) && litFor(caret).equals(lit))
            return;
        kept = found;
        openers = found.stream().map(Kept::opener).toList();
        show();
    }

    // The two markers around the side of a region line is in: ours is
    // between the opener and the base's marker, or the separator with no
    // base; the base, its marker and the separator; theirs, the separator
    // and the closer.
    private List<Line> litFor(Line line) {
        if (line == null)
            return List.of();
        for (Kept k : kept) {
            if (line == k.opener() || contains(k.ours(), line))
                return List.of(k.opener(), k.baseMarker() != null ? k.baseMarker() : k.separator());
            if (k.baseMarker() != null && (line == k.baseMarker() || contains(k.base(), line)))
                return List.of(k.baseMarker(), k.separator());
            if (line == k.separator() || line == k.closer() || contains(k.theirs(), line))
                return List.of(k.separator(), k.closer());
        }
        return List.of();
    }

    private static boolean contains(List<Line> lines, Line line) {
        for (Line l : lines)
            if (l == line)
                return true;
        return false;
    }

    // r's indexes resolved to lines: ours between the opener and the base
    // or separator, base and theirs likewise.
    private static Kept keep(ConflictMarkers.Region r, List<Line> lines) {
        final int base = r.hasBase() ? r.base() : r.separator();
        return new Kept(r,
                lines.get(r.start()),
                r.hasBase() ? lines.get(r.base()) : null,
                lines.get(r.separator()),
                lines.get(r.end()),
                List.copyOf(lines.subList(r.start() + 1, base)),
                r.hasBase() ? List.copyOf(lines.subList(r.base() + 1, r.separator())) : List.of(),
                List.copyOf(lines.subList(r.separator() + 1, r.end())));
    }

    // The shades, the markers dim or lit, and the link rows, in the
    // theme's colors now.
    private void show() {
        lit = litFor(caret);
        final Colors colors = colors();
        final List<VirtualText> texts = new ArrayList<>();
        for (Kept k : kept) {
            texts.add(marker(k.opener(), colors.ours, colors));
            for (Line l : k.ours())
                texts.add(VirtualText.background(l, colors.ours));
            if (k.baseMarker() != null)
                texts.add(marker(k.baseMarker(), colors.base, colors));
            for (Line l : k.base())
                texts.add(VirtualText.background(l, colors.base));
            texts.add(marker(k.separator(), colors.theirs, colors));
            for (Line l : k.theirs())
                texts.add(VirtualText.background(l, colors.theirs));
            texts.add(marker(k.closer(), colors.theirs, colors));
            texts.add(VirtualText.above(k.opener(), List.of(row(k))));
        }
        buffer.getVirtualText().set(OWNER, texts);
    }

    // A marker line on its side's shade, its text dim unless lit.
    private VirtualText marker(Line line, Color shade, Colors colors) {
        final boolean bright = contains(lit, line);
        return VirtualText.background(line, shade, bright ? colors.text : DefaultTheme.blend(shade, colors.text, 0.45));
    }

    // The links over one region's opener, named from its markers' own
    // labels: a rebase swaps ours and theirs, so those would mislead.
    private List<VirtualText.Run> row(Kept k) {
        final Editor editor = Editor.currentEditor();
        final List<VirtualText.Run> row = new ArrayList<>();
        link(row, "Keep " + label(k.region().ours(), "ours"), ConflictCommands.Take.OURS, k, editor);
        link(row, "Keep " + label(k.region().theirs(), "theirs"), ConflictCommands.Take.THEIRS, k, editor);
        link(row, "Both", ConflictCommands.Take.BOTH, k, editor);
        if (k.region().hasBase())
            link(row, "Base", ConflictCommands.Take.BASE, k, editor);
        return row;
    }

    // A link added to row after a dot, and its key, dimmed in parentheses, if it has one.
    private void link(List<VirtualText.Run> row, String text, ConflictCommands.Take take, Kept k, Editor editor) {
        if (!row.isEmpty())
            row.add(VirtualText.Run.of(" \u00b7 "));
        final Color color = buffer.getFormatter().getFormatTable().resolveColor("link", true);
        row.add(
            new VirtualText.Run(text,
                    color,
                    0,
                    () -> ConflictCommands.takeLink(Editor.currentEditor(), this, k.opener(), take),
                    take.command));
        final String key = keyFor(editor, take.command);
        if (key != null)
            row.add(VirtualText.Run.of(" (" + key + ")"));
    }

    // The key that runs command in editor: vim's in vim edit mode, else
    // j's; null for none.
    private static String keyFor(Editor editor, String command) {
        if (editor == null)
            return null;
        if (editor.getInputHandler() instanceof VimInputHandler)
            return VimKeyMap.getShared().keysFor(command);
        return editor.getKeyMapping(command)[0] instanceof KeyMapping m ? m.getKeyText() : null;
    }

    // The label cut short with an ellipsis; the fallback when empty.
    static String label(String s, String fallback) {
        if (s.isEmpty())
            return fallback;
        return s.length() > LABEL_MAX ? s.substring(0, LABEL_MAX - 1) + "\u2026" : s;
    }

    private record Colors(Color ours, Color base, Color theirs, Color text) {}

    // The theme's shades, or blends as the compare's sides makes them.
    private Colors colors() {
        final FormatTable t = buffer.getFormatter().getFormatTable();
        final Color bg = buffer.getFormatter().getBackgroundColor();
        final Color text = buffer.getFormatter().getColor(0);
        final boolean dark = DefaultTheme.isDark(bg);
        final double amount = dark ? 0.16 : 0.12;
        final Color ours = shade(t, "ConflictOurs", DefaultTheme.blend(bg, ChangeColors.added(dark), amount));
        final Color theirs = shade(t, "ConflictTheirs", DefaultTheme.blend(bg, ChangeColors.modified(dark), amount));
        final Color base = shade(t, "ConflictBase", DefaultTheme.blend(bg, text, 0.05));
        return new Colors(ours, base, theirs, text);
    }

    private static Color shade(FormatTable t, String key, Color fallback) {
        final Color c = t.resolveColor(key, false);
        return c != null ? c : fallback;
    }
}
