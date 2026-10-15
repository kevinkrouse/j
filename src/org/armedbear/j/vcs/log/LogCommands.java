/*
 * LogCommands.java
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.ChangeMarks;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Frame;
import org.armedbear.j.Line;
import org.armedbear.j.MotionCommands;
import org.armedbear.j.Property;
import org.armedbear.j.SessionProperties;
import org.armedbear.j.WindowLayout.Split;
import org.armedbear.j.WindowLayout.Window;
import org.armedbear.j.transients.FilterBar;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Option;
import org.armedbear.j.transients.TransientPopup;
import org.armedbear.j.transients.Transients;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.status.StatusLine;
import org.armedbear.j.vcs.status.VcsStatusMode;

/** The log's commands. */
public final class LogCommands {
    private LogCommands() {}

    // Opening.

    /** The log of the current buffer's repository, taking the whole frame; from the quick log, it grows. */
    public static void vcsLog(Editor editor) {
        if (editor.getBuffer() instanceof LogBuffer log) {
            showFull(editor, log);
            return;
        }
        final VcsRepository r = repository(editor);
        if (r == null)
            return;
        showFull(editor, LogBuffer.open(r, startingQuery(editor, LogQuery.ALL, r)));
    }

    // query narrowed by the filters set in r's log popup, as editor's buffer keeps them.
    private static LogQuery startingQuery(Editor editor, LogQuery query, VcsRepository r) {
        return LogFilters.withArguments(query, Transients.args(editor, LogFilters.openingId(r)), r);
    }

    /** l in a status buffer: the popup that opens a log, its filters set first. */
    public static void vcsLogPopup(Editor editor) {
        TransientPopup.open(editor, openingPopup(editor));
    }

    /** The popup that opens a log from editor, the names its filters offer read meanwhile. */
    public static Transient openingPopup(Editor editor) {
        final VcsRepository r = repositoryOf(editor);
        if (r != null)
            LogNames.of(r).read(false);
        return LogFilters.openingPopup(r);
    }

    /** The log alone in a window under this one, with no details. */
    public static void vcsLogQuick(Editor editor) {
        final VcsRepository r = repository(editor);
        if (r == null)
            return;
        final LogBuffer log = LogBuffer.open(r, startingQuery(editor, LogQuery.ALL, r));
        final Frame frame = editor.getFrame();
        if (frame == null) {
            editor.show(log);
            return;
        }
        // Kept for when it grows to the full log.
        log.before = frame.captureLayout();
        frame.openBelow(editor, log);
    }

    /** The current file's log, through its renames. */
    public static void vcsLogFile(Editor editor) {
        final File file = editor.getBuffer().getFile();
        final VcsRepository r = file != null && file.isLocal() ? Repositories.forFile(file) : null;
        if (r == null) {
            editor.status("Not a file in a repository");
            return;
        }
        showFull(editor, LogBuffer.open(r, startingQuery(editor, LogQuery.file(r.relativePath(file)), r)));
    }

    // The repository a log opens from editor, or says there is none.
    private static VcsRepository repository(Editor editor) {
        final VcsRepository r = repositoryOf(editor);
        if (r == null)
            editor.status("Not in a repository");
        return r;
    }

    /** The log's repository, the one of the thing at the caret in a status buffer, or the current buffer's; else null. */
    static VcsRepository repositoryOf(Editor editor) {
        final LogBuffer log = LogBuffers.owner(editor.getBuffer());
        if (log != null)
            return log.getRepository();
        if (editor.getDot() != null && editor.getDotLine() instanceof StatusLine s && s.repository() != null)
            return s.repository();
        final List<VcsRepository> rs = VcsStatusMode.repositoriesFor(editor.getBuffer());
        return rs.isEmpty() ? null : rs.get(0);
    }

    /**
     * Gives the log the whole frame, with its details, the windows before
     * kept to put back. Without a frame, as in tests, only the details.
     */
    static void showFull(Editor editor, LogBuffer log) {
        final Frame frame = editor.getFrame();
        if (frame != null && log.before == null)
            log.before = frame.captureLayout();
        if (log.files == null) {
            log.files = new LogFilesBuffer(log);
            log.diff = new LogDiffBuffer(log);
        }
        log.forgetShown();
        if (frame != null) {
            if (log.right == null)
                log.right = detailsAtRight(frame);
            layOut(frame, log);
        }
        log.showDetails();
    }

    // Where D last put the details, kept in the session.
    private static final String DETAILS_KEY = "vcsLog.details";

    static boolean detailsAtRight(Frame frame) {
        final SessionProperties session = Editor.getSessionProperties();
        String where = session != null ? session.getStringProperty(DETAILS_KEY, null) : null;
        if (where == null)
            where = Editor.preferences().getStringProperty(Property.VCS_LOG_DETAILS);
        if ("right".equals(where))
            return true;
        if ("bottom".equals(where))
            return false;
        if (frame == null)
            return false;
        final int w = frame.getEditorPane().getWidth();
        final int h = frame.getEditorPane().getHeight();
        return h > 0 && w > 1.5 * h;
    }

    // The list, then the files over or beside the diff, as the details go.
    private static void layOut(Frame frame, LogBuffer log) {
        final Window list = window(log, true);
        final Window files = window(log.files, false);
        final Window diff = window(log.diff, false);
        final Split details;
        final Split shape;
        if (log.right) {
            // The files on top, a third of the height at most.
            details = new Split(false, List.of(files, diff), List.of(1 / 3.0, 2 / 3.0));
            shape = new Split(true, List.of(list, details), List.of(0.5, 0.5));
        } else {
            final int columns = frame.getEditorPane().getWidth() / Math.max(1, Display.getCharWidth());
            final boolean stacked = columns < Editor.preferences().getIntegerProperty(Property.VCS_LOG_STACKED_WIDTH);
            details = new Split(!stacked, List.of(files, diff), List.of(0.3, 0.7));
            shape = new Split(false, List.of(list, details), List.of(0.55, 0.45));
        }
        frame.restoreLayout(shape);
    }

    // A window of buffer with its caret and top where a window showing it has them now.
    private static Window window(Buffer buffer, boolean focused) {
        final Editor ed = LogBuffers.windowOf(buffer);
        if (ed == null || ed.getDot() == null)
            return new Window(buffer, 0, 0, 0, focused);
        final Line top = ed.getTopLine();
        return new Window(buffer,
                ed.getDotLineNumber(),
                ed.getDotOffset(),
                top == null ? 0 : top.lineNumber(),
                focused);
    }

    // Closing.

    /** Closes the log: the windows before it come back, or the quick log's window goes. */
    public static void vcsLogQuit(Editor editor) {
        final LogBuffer log = LogBuffers.owner(editor.getBuffer());
        if (log != null)
            log.close(editor.getFrame());
    }

    // Moving about.

    /**
     * Enter: on a commit in the quick log, the full log with it; in the
     * full log, the files; on a file, its diff; on a diff line, the file in
     * the working tree at that line.
     */
    public static void vcsLogVisit(Editor editor) {
        final Buffer b = editor.getBuffer();
        if (b instanceof LogBuffer log) {
            if (!log.isFull())
                showFull(editor, log);
            else
                focus(log.files);
        } else if (b instanceof LogFilesBuffer f) {
            f.showDiff();
            focus(f.owner.diff);
        } else if (b instanceof LogDiffBuffer d)
            visitLine(editor, d);
    }

    private static void focus(Buffer buffer) {
        final Editor ed = LogBuffers.windowOf(buffer);
        if (ed == null)
            return;
        Editor.setCurrentEditor(ed);
        ed.setFocusToDisplay();
    }

    /** n: the next commit, file or hunk, in the pane at hand. */
    public static void vcsLogNext(Editor editor) {
        step(editor, true);
    }

    public static void vcsLogPrevious(Editor editor) {
        step(editor, false);
    }

    private static void step(Editor editor, boolean forward) {
        switch (editor.getBuffer()) {
            case LogBuffer l -> {
                if (forward)
                    MotionCommands.down(editor);
                else
                    MotionCommands.up(editor);
            }
            case LogFilesBuffer f -> {
                final Line to = f.nextFile(editor, forward);
                if (to != null)
                    editor.moveDotTo(to, 0);
            }
            case LogDiffBuffer d -> {
                if (forward)
                    ChangeMarks.nextChange(editor);
                else
                    ChangeMarks.previousChange(editor);
            }
            default -> {}
        }
    }

    /** Space: the diff a page on, from any pane. */
    public static void vcsLogScrollDiff(Editor editor) {
        scrollDiff(editor, true);
    }

    public static void vcsLogScrollDiffBack(Editor editor) {
        scrollDiff(editor, false);
    }

    private static void scrollDiff(Editor editor, boolean forward) {
        final LogBuffer log = LogBuffers.owner(editor.getBuffer());
        final Editor ed = log == null ? null : LogBuffers.windowOf(log.diff);
        if (ed == null)
            return;
        if (forward)
            MotionCommands.pageDown(ed);
        else
            MotionCommands.pageUp(ed);
        ed.updateDisplay();
    }

    /** Alt Down: the next file's diff, the focus staying. */
    public static void vcsLogNextFile(Editor editor) {
        stepFile(editor, true);
    }

    public static void vcsLogPreviousFile(Editor editor) {
        stepFile(editor, false);
    }

    private static void stepFile(Editor editor, boolean forward) {
        final LogBuffer log = LogBuffers.owner(editor.getBuffer());
        if (log == null || log.files == null)
            return;
        final Editor ed = LogBuffers.windowOf(log.files);
        final Line to = log.files.nextFile(ed, forward);
        if (ed == null || to == null)
            return;
        ed.moveDotTo(to, 0);
        ed.updateDisplay();
        log.files.showDiff();
    }

    /** Tab: opens or closes the diff's hunk at the caret, as in the status buffer. */
    public static void vcsLogToggle(Editor editor) {
        if (editor.getBuffer() instanceof LogDiffBuffer d
                && editor.getDot() != null
                && editor.getDotLine() instanceof StatusLine s
                && d.toggle(s))
            return;
        editor.status("No hunk here");
    }

    /** Shift Tab: closes every hunk of the diff, or opens them all when none is open. */
    public static void vcsLogToggleAll(Editor editor) {
        if (!(editor.getBuffer() instanceof LogDiffBuffer d))
            return;
        boolean anyOpen = false;
        for (Line l : StatusLine.hunks(d))
            anyOpen |= ((StatusLine) l).isOpen();
        d.setAll(!anyOpen);
    }

    /** D: the details at the bottom, or at the right. */
    public static void vcsLogToggleDetails(Editor editor) {
        final LogBuffer log = LogBuffers.owner(editor.getBuffer());
        final Frame frame = editor.getFrame();
        if (log == null || !log.isFull())
            return;
        log.right = !Boolean.TRUE.equals(log.right);
        final SessionProperties session = Editor.getSessionProperties();
        if (session != null)
            session.setStringProperty(DETAILS_KEY, log.right ? "right" : "bottom");
        if (frame != null) {
            final Buffer focused = editor.getBuffer();
            layOut(frame, log);
            focus(focused);
        }
    }

    /** l: the popup of the log's filters. */
    public static void vcsLogFilter(Editor editor) {
        vcsLogFilter(editor, null);
    }

    /**
     * -a and the rest: the filter named ("author"), its value typed in the
     * field over its chip in the list's filter bar; the popup for none.
     */
    public static void vcsLogFilter(Editor editor, String name) {
        final LogBuffer log = LogBuffers.owner(editor.getBuffer());
        if (log == null)
            return;
        final Option option = name == null ? null : LogFilters.named(name.strip());
        final Editor list = LogBuffers.windowOf(log);
        final FilterBar bar = list == null ? null : list.getFilterBar();
        if (option == null || bar == null) {
            if (name != null && option == null)
                editor.status("No filter " + name.strip());
            TransientPopup.open(editor, LogFilters.popup());
            return;
        }
        bar.choose(option);
    }

    /** x: every filter cleared, a file's log's path too. */
    public static void vcsLogClearFilters(Editor editor) {
        final LogBuffer log = LogBuffers.owner(editor.getBuffer());
        if (log != null) {
            log.setQuery(LogQuery.ALL);
            FilterBar.refreshAll(log);
        }
    }

    public static void vcsLogRefresh(Editor editor) {
        final LogBuffer log = LogBuffers.owner(editor.getBuffer());
        if (log != null)
            log.refresh();
    }

    // To the file.

    /**
     * o: the file at the caret in the working tree, from the files or the
     * diff, at the diff line's line as it is now; the log closes.
     */
    public static void vcsLogOpenFile(Editor editor) {
        if (editor.getBuffer() instanceof LogDiffBuffer d)
            visitLine(editor, d);
        else if (editor.getBuffer() instanceof LogFilesBuffer f && f.commit() != null && f.changeAt(editor) != null)
            visit(editor, f.owner, f.commit(), f.changeAt(editor), 0);
        else
            editor.status("No file here");
    }

    // The diff line's file in the working tree, at the line the diff line is now.
    private static void visitLine(Editor editor, LogDiffBuffer d) {
        if (d.commit() == null || d.change() == null || editor.getDot() == null)
            return;
        visit(editor, d.owner, d.commit(), d.change(), diffLine(editor));
    }

    // The change's file in the working tree, at the commit's line n mapped to now; 0 for its top.
    private static void visit(Editor editor, LogBuffer log, LogCommit commit, CommitChange change, int n) {
        final VcsRepository r = log.getRepository();
        final File file = File.getInstance(r.root().resolve(change.path()).toString());
        if (file == null || !file.isFile()) {
            editor.status(change.path() + " is not in the working tree");
            return;
        }
        Background.start("vcs log visit", () -> {
            final FileDiff since = n > 0 ? r.backend().readDiffToWorkingTree(r, commit.id(), change.path()) : null;
            final int line = since == null ? n : VcsStatusMode.throughHunks(since.hunks(), n);
            SwingUtilities.invokeLater(() -> {
                // Closed meanwhile, as by q: given up.
                if (log.isDisposed())
                    return;
                log.close(editor.getFrame());
                final Buffer buffer = Editor.getBuffer(file);
                if (buffer != null)
                    VcsStatusMode.showAt(Editor.currentEditor(), buffer, line);
            });
        });
    }

    // The commit's line the diff line at the caret shows, from 1; 0 for none.
    private static int diffLine(Editor editor) {
        return editor.getDot() != null && editor.getDotLine() instanceof StatusLine s ? VcsStatusMode.lineNumber(s) : 0;
    }

    /** Shift Enter: the file at the caret as the commit has it, read-only. */
    public static void vcsLogRevision(Editor editor) {
        final LogBuffer log = LogBuffers.owner(editor.getBuffer());
        if (log == null)
            return;
        LogCommit commit = null;
        CommitChange change = null;
        if (editor.getBuffer() instanceof LogDiffBuffer d) {
            commit = d.commit();
            change = d.change();
        } else if (editor.getBuffer() instanceof LogFilesBuffer f) {
            commit = f.commit();
            change = f.changeAt(editor);
        }
        if (commit == null || change == null) {
            editor.status("No file here");
            return;
        }
        if (change.kind() == org.armedbear.j.vcs.FileChange.Kind.DELETED) {
            editor.status(change.path() + " is deleted in this commit");
            return;
        }
        final int n = editor.getBuffer() instanceof LogDiffBuffer ? diffLine(editor) : 0;
        final VcsRepository r = log.getRepository();
        final String id = commit.id();
        final String path = change.path();
        Background.start("vcs log revision", () -> {
            final byte[] text = r.backend().readFileAt(r, id, path);
            SwingUtilities.invokeLater(() -> {
                if (log.isDisposed())
                    return;
                if (text == null) {
                    editor.status("Can't read " + path + " at " + id);
                    return;
                }
                log.close(editor.getFrame());
                final RevisionBuffer buffer = new RevisionBuffer(path, id, new String(text, StandardCharsets.UTF_8));
                VcsStatusMode.showAt(Editor.currentEditor(), buffer, n);
            });
        });
    }
}
