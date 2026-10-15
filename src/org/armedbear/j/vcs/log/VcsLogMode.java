/*
 * VcsLogMode.java
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

package org.armedbear.j.vcs.log;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.Position;
import org.armedbear.j.Property;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.vcs.status.StatusLine;
import org.armedbear.j.vcs.status.StatusLine.Kind;
import org.armedbear.j.vcs.status.VcsStatusFormatter;

/**
 * The log's mode, for its list and its details' files and diff. Nothing in
 * them is edited, so the same letters work in both edit modes, but for
 * what vim keeps for moving: refresh is r there.
 */
public final class VcsLogMode extends AbstractMode implements Mode {
    private static final VcsLogMode mode = new VcsLogMode();

    private VcsLogMode() {
        super(VCS_LOG_MODE, VCS_LOG_MODE_NAME);
        setProperty(Property.VERTICAL_RULE, 0);
        setProperty(Property.SHOW_LINE_NUMBERS, false);
        setProperty(Property.SHOW_CHANGE_MARKS, false);
        setProperty(Property.HIGHLIGHT_MATCHING_BRACKET, false);
        setProperty(Property.HIGHLIGHT_BRACKETS, false);
        setProperty(Property.RAINBOW_DELIMITERS, false);
    }

    public static VcsLogMode getMode() {
        return mode;
    }

    @Override
    public Formatter getFormatter(Buffer buffer) {
        return buffer instanceof LogBuffer ? new LogFormatter(buffer) : new VcsStatusFormatter(buffer);
    }

    @Override
    public String getHelpTopic() {
        return "git.html#log";
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        common(km);
        km.mapKey('g', "vcsLogRefresh");
    }

    private static KeyMap modalKeyMap;

    @Override
    public synchronized KeyMap getModalKeyMap() {
        if (modalKeyMap == null) {
            final KeyMap km = new KeyMap();
            common(km);
            km.mapKey('r', "vcsLogRefresh");
            modalKeyMap = km;
        }
        return modalKeyMap;
    }

    // As the status buffer's where they mean the same: Enter visits, Tab
    // opens or closes a hunk, n and p go to the next thing; the diff
    // scrolls from any pane as magit's revision buffer does from its log.
    private static void common(KeyMap km) {
        km.mapKey(KeyEvent.VK_ENTER, 0, "vcsLogVisit");
        km.mapKey(VK_DOUBLE_MOUSE_1, 0, "vcsLogVisit");
        km.mapKey(KeyEvent.VK_ENTER, SHIFT_MASK, "vcsLogRevision");
        km.mapKey(KeyEvent.VK_TAB, 0, "vcsLogToggle");
        km.mapKey(KeyEvent.VK_TAB, SHIFT_MASK, "vcsLogToggleAll");
        km.mapKey('n', "vcsLogNext");
        km.mapKey('p', "vcsLogPrevious");
        km.mapKey(' ', "vcsLogScrollDiff");
        km.mapKey(KeyEvent.VK_SPACE, SHIFT_MASK, "vcsLogScrollDiffBack");
        km.mapKey(KeyEvent.VK_BACK_SPACE, 0, "vcsLogScrollDiffBack");
        km.mapKey(KeyEvent.VK_DOWN, ALT_MASK, "vcsLogNextFile");
        km.mapKey(KeyEvent.VK_UP, ALT_MASK, "vcsLogPreviousFile");
        km.mapKey('f', "vcsLogNextFile");
        km.mapKey('F', "vcsLogPreviousFile");
        km.mapKey('d', "vcsLogScrollDiff");
        km.mapKey('u', "vcsLogScrollDiffBack");
        km.mapKey('o', "vcsLogOpenFile");
        km.mapKey('O', "vcsLogRevision");
        km.mapKey('D', "vcsLogToggleDetails");
        km.mapKey('$', "vcsCommandLog");
        km.mapKey('q', "vcsLogQuit");
        km.mapKey('?', "modePopup");
    }

    // Each pane's main keys, with what Enter does there.
    private static final String[][] LIST_HINTS = {
        { "vcsLogVisit", "files" },
        { "vcsLogToggleDetails", "layout" },
        { "vcsLogQuit", "quit" },
        { "modePopup", "more" }, };

    private static final String[][] FILES_HINTS = {
        { "vcsLogVisit", "diff" },
        { "vcsLogOpenFile", "open" },
        { "vcsLogRevision", "as of the commit" },
        { "modePopup", "more" }, };

    private static final String[][] DIFF_HINTS = {
        { "vcsLogVisit", "open at this line" },
        { "vcsLogRevision", "as of the commit" },
        { "vcsLogToggle", "fold" },
        { "modePopup", "more" }, };

    @Override
    public String[][] getHints(Buffer buffer) {
        if (!buffer.getBooleanProperty(Property.VCS_HINTS))
            return null;
        return switch (buffer) {
            case LogBuffer l -> LIST_HINTS;
            case LogFilesBuffer f -> FILES_HINTS;
            case LogDiffBuffer d -> DIFF_HINTS;
            default -> null;
        };
    }

    @Override
    public Transient getTransient(Editor editor) {
        final boolean vim = editor != null && editor.getInputHandler() != null;
        final List<Group> groups = new ArrayList<>();
        // The list has no file under the caret.
        if (editor != null && !(editor.getBuffer() instanceof LogBuffer))
            groups.add(
                Group.of(
                    "File",
                    Command.of("o", "vcsLogOpenFile", "open it at this line"),
                    Command.of("O", "vcsLogRevision", "open it as of the commit")));
        groups.add(
            Group.of(
                "Go",
                new Command("n", "vcsLogNext", "next", true),
                new Command("p", "vcsLogPrevious", "previous", true),
                new Command("f", "vcsLogNextFile", "next file", true),
                new Command("F", "vcsLogPreviousFile", "previous file", true)));
        groups.add(
            Group.of(
                "Diff",
                new Command("d", "vcsLogScrollDiff", "scroll down", true),
                new Command("u", "vcsLogScrollDiffBack", "scroll up", true),
                new Command("D", "vcsLogToggleDetails", "at the bottom or right", true)));
        groups.add(
            Group.of(
                "Log",
                Command.of(vim ? "r" : "g", "vcsLogRefresh", "refresh"),
                Command.of("$", "vcsCommandLog", "command log"),
                Command.of("q", "vcsLogQuit", "close")));
        return new Transient("vcs.log", "Log", "git.html#log", groups);
    }

    @Override
    public List<Line> getChangeStarts(Buffer buffer) {
        return buffer instanceof LogDiffBuffer ? StatusLine.hunks(buffer) : null;
    }

    // Folding the diff's hunks.

    @Override
    public boolean isFoldStart(Buffer buffer, Line line) {
        return buffer instanceof LogDiffBuffer && line instanceof StatusLine s && s.kind() == Kind.HUNK;
    }

    @Override
    public boolean isFolded(Line line) {
        return line instanceof StatusLine s && s.kind() == Kind.HUNK && !s.isOpen();
    }

    @Override
    public void toggleFoldAt(Editor editor, Line line) {
        if (editor.getBuffer() instanceof LogDiffBuffer d && line instanceof StatusLine s)
            d.toggle(s);
    }

    @Override
    public boolean setFoldAt(Editor editor, Line line, Boolean open) {
        if (!(editor.getBuffer() instanceof LogDiffBuffer d) || !(line instanceof StatusLine s))
            return false;
        final String hunk = s.kind() == Kind.HUNK ? s.key() : s.kind() == Kind.DIFF ? s.node() : null;
        if (hunk != null)
            d.setExpanded(hunk, open != null ? open : !s.isOpen());
        return true;
    }

    @Override
    public boolean setAllFolds(Editor editor, boolean open) {
        if (!(editor.getBuffer() instanceof LogDiffBuffer d))
            return false;
        d.setAll(open);
        return true;
    }

    @Override
    public void foldAll(Editor editor) {
        setAllFolds(editor, false);
    }

    // A cut row, whole.

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Override
    public String getToolTipText(Editor editor, MouseEvent e) {
        final Position pos = editor.getDisplay().positionFromPoint(e.getX(), e.getY());
        if (pos == null || !(pos.getLine() instanceof LogLine l) || !l.row().cut())
            return super.getToolTipText(editor, e);
        final LogCommit c = l.commit();
        final StringBuilder sb = new StringBuilder(c.subject());
        for (LogRef r : c.refs())
            sb.append("  [").append(r.name()).append(']');
        sb.append("  ").append(c.author()).append(", ").append(DATE.format(Instant.ofEpochSecond(c.time())));
        return sb.toString();
    }
}
