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
import org.armedbear.j.transients.Transient.Option;
import org.armedbear.j.vcs.ViewPane;
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
        return switch (buffer) {
            case LogBuffer l -> new LogFormatter(l);
            case LogFilesBuffer f -> new LogDetailsFormatter(f);
            default -> new VcsStatusFormatter(buffer);
        };
    }

    @Override
    public String getHelpTopic() {
        return "git.html#log";
    }

    // The list's keys; the files and diff have all but its filters'.
    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        common(km, false);
        listKeys(km);
    }

    private static KeyMap modalKeyMap;
    private static KeyMap paneKeyMap;
    private static KeyMap paneModalKeyMap;

    @Override
    public synchronized KeyMap getModalKeyMap() {
        if (modalKeyMap == null) {
            modalKeyMap = new KeyMap();
            common(modalKeyMap, true);
            listKeys(modalKeyMap);
        }
        return modalKeyMap;
    }

    @Override
    public synchronized KeyMap getKeyMap(Buffer buffer) {
        if (!(buffer instanceof ViewPane))
            return getKeyMap();
        if (paneKeyMap == null) {
            paneKeyMap = new KeyMap();
            common(paneKeyMap, false);
        }
        return paneKeyMap;
    }

    @Override
    public synchronized KeyMap getModalKeyMap(Buffer buffer) {
        if (!(buffer instanceof ViewPane))
            return getModalKeyMap();
        if (paneModalKeyMap == null) {
            paneModalKeyMap = new KeyMap();
            common(paneModalKeyMap, true);
        }
        return paneModalKeyMap;
    }

    // The filters' keys, in the list alone: in the files and diff vim's
    // - moves, and x out of habit would clear every filter.
    private static void listKeys(KeyMap km) {
        // -b, -a and the rest, as in the popup's Filters.
        final KeyMap filters = new KeyMap();
        for (Option o : LogFilters.ALL)
            filters.mapKey(o.key().charAt(1), "vcsLogFilter " + o.description());
        filters.mapKey(LogFilters.TAGS.key().charAt(1), "vcsLogToggleTags");
        filters.mapKey(LogFilters.TIME_STYLE.key().charAt(1), "vcsLogTimeStyle");
        filters.mapKey(LogFilters.COLLAPSE.key().charAt(1), "vcsLogCollapseRuns");
        km.mapKey('-', filters);
        km.mapKey('x', "vcsLogClearFilters");
    }

    // As the status buffer's where they mean the same: Enter visits, Tab
    // opens or closes a hunk, n and p go to the next thing; the diff
    // scrolls from any pane as magit's revision buffer does from its log.
    private static void common(KeyMap km, boolean vim) {
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
        km.mapKey(vim ? 'r' : 'g', "vcsLogRefresh");
        km.mapKey('$', "vcsCommandLog");
        km.mapKey('q', "vcsLogQuit");
        km.mapKey('?', "modePopup");
    }

    // Each pane's main keys, with what Enter does there.
    private static final String[][] LIST_HINTS = {
        { "vcsLogVisit", "files" },
        { "vcsLogFilter", "filter" },
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
        // The filters' keys are the list's alone.
        if (editor == null || editor.getBuffer() instanceof LogBuffer) {
            groups.add(0, LogFilters.group(editor == null ? null : LogCommands.scopeOf(editor)));
            groups.add(1, Group.of("View", LogFilters.TIME_STYLE, LogFilters.COLLAPSE));
        }
        final List<Transient.Item> log = new ArrayList<>();
        log.add(Command.of(vim ? "r" : "g", "vcsLogRefresh", "refresh"));
        log.add(Command.of("$", "vcsCommandLog", "command log"));
        log.add(Command.of("q", "vcsLogQuit", "close"));
        groups.add(new Group("Log", log));
        return new Transient("vcs.log", "Log", "git.html#log", groups);
    }

    @Override
    public List<Option> getFilters(Editor editor) {
        return editor.getBuffer() instanceof LogBuffer log ? LogFilters.of(log.getScope()) : null;
    }

    @Override
    public List<Line> getChangeStarts(Buffer buffer) {
        return buffer instanceof LogDiffBuffer ? StatusLine.hunks(buffer) : null;
    }

    // Folding the diff's hunks, and the list's runs of plain commits.

    @Override
    public boolean isFoldStart(Buffer buffer, Line line) {
        if (buffer instanceof LogBuffer log)
            return log.isRunHead(line);
        return buffer instanceof LogDiffBuffer && line instanceof StatusLine s && s.kind() == Kind.HUNK;
    }

    @Override
    public boolean isFolded(Line line) {
        if (line instanceof LogLine l)
            return l.headsFolded();
        return line instanceof StatusLine s && s.kind() == Kind.HUNK && !s.isOpen();
    }

    @Override
    public void toggleFoldAt(Editor editor, Line line) {
        if (editor.getBuffer() instanceof LogBuffer log)
            log.setRun(line, null);
        else if (editor.getBuffer() instanceof LogDiffBuffer d && line instanceof StatusLine s)
            d.toggle(s);
    }

    @Override
    public boolean setFoldAt(Editor editor, Line line, Boolean open) {
        // In the list, the run line is or is in; none there, nothing to fold.
        if (editor.getBuffer() instanceof LogBuffer log) {
            if (!log.setRun(line, open))
                editor.status("No run of commits here");
            return true;
        }
        if (!(editor.getBuffer() instanceof LogDiffBuffer d) || !(line instanceof StatusLine s))
            return false;
        d.setFold(s, open);
        return true;
    }

    @Override
    public boolean setAllFolds(Editor editor, boolean open) {
        if (editor.getBuffer() instanceof LogBuffer log) {
            log.setCollapse(!open);
            return true;
        }
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
