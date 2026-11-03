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
import java.util.function.Consumer;
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
import org.armedbear.j.Property;
import org.armedbear.j.SystemBuffer;
import org.armedbear.j.VirtualText;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vim.VimInputHandler;
import org.armedbear.j.vim.VimKeyMap;

/**
 * A buffer's conflicts, while its file is unmerged: each region's lines
 * shaded ours, base and theirs, and above each opener a row of links to
 * take a side. Kept on the buffer as {@link org.armedbear.j.vcs.blame.Blame}
 * is, by the repository's word, not wherever markers appear, since a file
 * may hold them as text. Scanned again on each change, coalesced; the
 * links hold the region's own lines, so they follow edits. The markers
 * are dimmed, but for those of the side the caret is in. Kept on once
 * the file is staged while the merge or rebase it came from goes on, so
 * markers an undo brings back show, with the conflict offered back. On
 * the event thread.
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
    // The caret's region, and whether the buffer was modified, as last
    // shown: the row's count and its "Save and mark resolved" follow them.
    private int caretRegion = -1;
    private boolean modified;
    // Staged since, its operation still going on.
    private boolean resolved;

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
        final boolean unmerged = wants(buffer);
        if (unmerged || c != null && inProgress(buffer)) {
            final Conflicts made = c != null ? c : new Conflicts(buffer);
            if (c == null) {
                buffer.setConflicts(made);
                buffer.addFollower(made);
            }
            if (made.resolved == unmerged) {
                made.resolved = !unmerged;
                made.show();
            }
            made.scan();
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

    // Whether the file's repository is in the middle of the merge, rebase
    // or the like its conflicts came from.
    private static boolean inProgress(Buffer buffer) {
        final VcsRepository r = Repositories.forFile(buffer.getFile());
        return r != null && r.inProgress() != null;
    }

    Buffer buffer() {
        return buffer;
    }

    /** Whether the file has been staged resolved since its conflicts were shown. */
    public boolean resolved() {
        return resolved;
    }

    // The kept regions, in buffer order: for take-all and the tests.
    List<Kept> kept() {
        return kept;
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

    // The index of the region holding line, or -1 outside every one.
    private int regionIndex(Line line) {
        return kept.indexOf(keptAt(line));
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

    /** A view watcher: the caret's side of a conflict lights its markers, and the row's count follows it. */
    public static void viewChanged(Editor editor) {
        if (editor == null || editor.getDot() == null)
            return;
        final Conflicts c = editor.getBuffer() == null ? null : editor.getBuffer().getConflicts();
        if (c == null)
            return;
        c.caret = editor.getDotLine();
        final int region = c.regionIndex(c.caret);
        if (!c.litFor(c.caret).equals(c.lit)
                || region != c.caretRegion
                || (c.kept.isEmpty() && c.buffer.isModified() != c.modified))
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
        // The row's modified-dependent text ("Save and mark resolved")
        // shows only with no regions left; with regions, the row is the
        // count and links, so a modified flip alone need not rebuild.
        if (found.equals(kept) && litFor(caret).equals(lit) && (!found.isEmpty() || buffer.isModified() == modified))
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

    // The shades, the markers dim or lit, the link rows, and the row
    // over the first line, in the theme's colors now.
    private void show() {
        lit = litFor(caret);
        caretRegion = caret == null ? -1 : regionIndex(caret);
        modified = buffer.isModified();
        final Colors colors = colors();
        final List<VirtualText> texts = new ArrayList<>();
        if (!resolved || !kept.isEmpty())
            texts.add(VirtualText.top(List.of(hintRow())));
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

    // The row over the first line: the count, next and previous, the
    // take-all links, or "Mark resolved" with none left. The keys, as
    // the mode's hint writes them, bold before each action; vcsHints off
    // drops the keyboard items but keeps the count and the links.
    private List<VirtualText.Run> hintRow() {
        final Editor editor = Editor.currentEditor();
        final boolean hints = buffer.getBooleanProperty(Property.VCS_HINTS);
        final List<VirtualText.Run> row = new ArrayList<>();
        final int total = kept.size();
        if (resolved) {
            // Markers back in a file staged resolved: an undo, say.
            row.add(
                VirtualText.Run.of(total + (total == 1 ? " conflict" : " conflicts") + " in a file marked resolved"));
            sep(row);
            row.add(
                linkRun(
                    "Mark unresolved",
                    () -> ConflictCommands.vcsConflictUnresolved(Editor.currentEditor()),
                    "vcsConflictUnresolved"));
            return row;
        }
        row.add(VirtualText.Run.of(countText(total)));
        if (total > 0) {
            if (hints) {
                sep(row);
                keyAction(row, editor, "vcsConflictNext", "next");
                sep(row);
                keyAction(row, editor, "vcsConflictPrevious", "previous");
            }
            row.add(VirtualText.Run.of(" \u00b7 All: "));
            allLink(row, "ours", "vcsConflictAllOurs", ConflictCommands::vcsConflictAllOurs, editor, hints);
            sep(row);
            allLink(row, "theirs", "vcsConflictAllTheirs", ConflictCommands::vcsConflictAllTheirs, editor, hints);
            sep(row);
            allLink(row, "both", "vcsConflictAllBoth", ConflictCommands::vcsConflictAllBoth, editor, hints);
        } else {
            sep(row);
            row.add(
                linkRun(
                    modified ? "Save and mark resolved" : "Mark resolved",
                    () -> ConflictCommands.vcsConflictResolved(Editor.currentEditor()),
                    "vcsConflictResolved"));
        }
        return row;
    }

    // The count: "2 of 3 conflicts" in the second, "3 conflicts" outside
    // one, "1 conflict" for a single one, "No conflicts left" with none.
    private String countText(int total) {
        if (total == 0)
            return "No conflicts left";
        if (total == 1)
            return "1 conflict";
        return caretRegion >= 0 ? (caretRegion + 1) + " of " + total + " conflicts" : total + " conflicts";
    }

    // A keyboard item: the key bold, then the word; the word alone with no key.
    private void keyAction(List<VirtualText.Run> row, Editor editor, String command, String word) {
        final String key = keyFor(editor, command);
        if (key != null)
            row.add(VirtualText.Run.bold(key));
        row.add(VirtualText.Run.of((key != null ? " " : "") + word));
    }

    // A take-all link running command, its key, if it has one and hints
    // are on, after it in parentheses as the link row writes its keys.
    private void allLink(
            List<VirtualText.Run> row,
            String word,
            String command,
            Consumer<Editor> run,
            Editor editor,
            boolean hints) {
        row.add(linkRun(word, () -> run.accept(Editor.currentEditor()), command));
        if (hints)
            keyAfter(row, editor, command);
    }

    // command's key, if it has one, after a link in parentheses.
    private static void keyAfter(List<VirtualText.Run> row, Editor editor, String command) {
        final String key = keyFor(editor, command);
        if (key != null)
            row.add(VirtualText.Run.of(" (" + key + ")"));
    }

    // A link run in the link color, running action, tooltip the command.
    private VirtualText.Run linkRun(String text, Runnable action, String command) {
        final Color color = buffer.getFormatter().getFormatTable().resolveColor("link", true);
        return new VirtualText.Run(text, color, 0, action, command);
    }

    private static void sep(List<VirtualText.Run> row) {
        row.add(VirtualText.Run.of(" \u00b7 "));
    }

    // A link added to row after a dot, and its key, dimmed in parentheses, if it has one.
    private void link(List<VirtualText.Run> row, String text, ConflictCommands.Take take, Kept k, Editor editor) {
        if (!row.isEmpty())
            sep(row);
        row.add(
            linkRun(
                text,
                () -> ConflictCommands.takeLink(Editor.currentEditor(), this, k.opener(), take),
                take.command));
        keyAfter(row, editor, take.command);
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
