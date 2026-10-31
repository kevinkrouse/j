/*
 * LogFilesBuffer.java
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.ViewPane;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.TimeStyle;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine;
import org.armedbear.j.vcs.status.StatusLine.Kind;
import org.armedbear.j.vcs.status.StatusRenderer;

/**
 * The log's selected commit: its id, author, date and message, then the
 * files it changed. The diff pane follows the file at the caret.
 */
final class LogFilesBuffer extends ViewPane<Buffer> {
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final CommitDetails details;
    private LogCommit commit;
    private List<CommitChange> changes = List.of();
    private CommitChange shown;
    private final Timer follow;

    LogFilesBuffer(Buffer owner, CommitDetails details) {
        super(VcsLogMode.getMode(), owner, "files", "");
        this.details = details;
        follow = new Timer(LogBuffer.FOLLOW_DELAY, e -> showDiff());
        follow.setRepeats(false);
    }

    @Override
    public void dispose() {
        follow.stop();
        super.dispose();
    }

    LogCommit commit() {
        return commit;
    }

    CommitDetails details() {
        return details;
    }

    /** The key of the line of the commit's badges: its repository's, of several, and its refs'. */
    static final String BADGES = "badges";

    // The shown commit's badges.
    private List<LogDetailsFormatter.Badge> badges = List.of();

    List<LogDetailsFormatter.Badge> badges() {
        return badges;
    }

    /** The change whose diff it last asked for, or null. */
    CommitChange shownChange() {
        return shown;
    }

    /** The files the commit shown changed. For tests. */
    List<CommitChange> changes() {
        return changes;
    }

    /** Reads a commit's message and files in the background, then shows them, and the first file's diff. */
    void show(LogCommit c) {
        final int at = ++generation;
        final VcsRepository r = details.host().repositoryOf(c);
        Background.start("vcs log files", () -> {
            String message;
            List<CommitChange> read;
            try {
                message = r.backend().readMessage(r, c.id());
                read = r.backend().readCommitChanges(r, c.id(), c.firstParent());
            }
            catch (RuntimeException e) {
                Log.error(e);
                message = null;
                read = null;
            }
            final String m = message != null ? message : c.subject();
            final List<CommitChange> files = read != null ? read : List.of();
            final boolean failed = read == null;
            SwingUtilities.invokeLater(() -> {
                if (at == generation && !details.host().isDisposed())
                    shown(c, m, files, failed);
            });
        });
    }

    private void shown(LogCommit c, String message, List<CommitChange> files, boolean failed) {
        commit = c;
        changes = files;
        shown = null;
        final List<StatusLine> lines = new ArrayList<>();
        badges = LogDetailsFormatter.badges(details.host(), c);
        if (!badges.isEmpty())
            lines.add(StatusLine.of(Kind.TEXT, LogDetailsFormatter.text(badges), BADGES));
        lines.add(StatusLine.of(Kind.FIELD, field("Commit:", c.id()), "commit"));
        lines.add(StatusLine.of(Kind.FIELD, field("Author:", c.author() + " <" + c.email() + ">"), "author"));
        lines.add(StatusLine.of(Kind.FIELD, field("Date:", date(c)), "date"));
        if (details.host().isStash(c))
            lines.add(StatusLine.of(Kind.FIELD, field("Stash:", stashParents(c.parents())), "merge"));
        else if (c.isMerge())
            lines.add(StatusLine.of(Kind.FIELD, field("Merge:", String.join(" ", shortIds(c.parents()))), "merge"));
        lines.add(StatusLine.of(Kind.BLANK, "", "blank1"));
        int n = 0;
        for (String s : message.split("\n", -1))
            lines.add(StatusLine.of(Kind.TEXT, "  " + s, "message" + n++));
        lines.add(StatusLine.of(Kind.BLANK, "", "blank2"));
        final List<FileItem> items = new ArrayList<>();
        for (CommitChange ch : files)
            items.add(item(ch));
        final List<StatusLine> fileLines =
                StatusRenderer.renderFiles(details.host().repositoryOf(c), "files", null, items, (k, kind) -> false);
        lines.addAll(fileLines);
        // A failed read is not a commit of no files.
        if (failed)
            lines.add(StatusLine.of(Kind.MESSAGE, "  Its files couldn't be read: see vcsCommandLog", "none"));
        else if (files.isEmpty())
            lines.add(StatusLine.of(Kind.MESSAGE, "  No files changed", "none"));
        replace(lines, selectPreferred(c, fileLines));
        showDiff();
    }

    // The line of the file preferred for c, by its path then or before a
    // rename; the first file when none is preferred or it isn't in the
    // commit.
    private StatusLine selectPreferred(LogCommit c, List<StatusLine> fileLines) {
        final String p = details.takePreferred(c);
        if (p != null)
            for (CommitChange ch : changes)
                if (p.equals(ch.path()) || p.equals(ch.origPath()))
                    for (StatusLine l : fileLines)
                        if (l.file() != null && l.file().change().path().equals(ch.path()))
                            return l;
        return fileLines.isEmpty() ? null : fileLines.get(0);
    }

    // In the log's time style; its age with the date in full.
    private String date(LogCommit c) {
        final String style = details.host().getTimeStyle();
        final long millis = c.time() * 1000;
        final String full = DATE.format(Instant.ofEpochMilli(millis));
        if (style.equals(TimeStyle.AGE))
            return full + " (" + TimeStyle.age(c.time(), System.currentTimeMillis() / 1000) + ")";
        return style.equals(TimeStyle.LONG_ISO)
                ? full
                : TimeStyle.format(style, millis, System.currentTimeMillis()).strip();
    }

    /** The date written again, in the log's time style now. */
    void restyle() {
        if (commit == null)
            return;
        for (Line l = getFirstLine(); l != null; l = l.next())
            if (l instanceof StatusLine s && "date".equals(s.key())) {
                s.setText(field("Date:", date(commit)));
                for (Editor ed : Editor.getEditorList())
                    if (ed.getBuffer() == this)
                        ed.repaintDisplay();
                return;
            }
    }

    // "Commit:" padded to the column values start at, as the status buffer's header.
    private static String field(String label, String value) {
        return String.format("%-10s%s", label, value);
    }

    // A stash's parents, as git makes them: the commit it was made on, the
    // index, and the untracked files when it has them.
    static String stashParents(List<String> parents) {
        final String[] labels = { "on", "index", "untracked" };
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < parents.size(); i++)
            out.add(LogCommit.shortId(parents.get(i)) + (i < labels.length ? " (" + labels[i] + ")" : ""));
        return String.join("  ", out);
    }

    private static List<String> shortIds(List<String> ids) {
        final List<String> out = new ArrayList<>();
        for (String id : ids)
            out.add(LogCommit.shortId(id));
        return out;
    }

    /** A change as the status buffer's file item, so its lines are drawn alike. */
    static FileItem item(CommitChange ch) {
        final char letter = ch.kind().letter();
        return new FileItem(new FileChange(ch.path(), ch.origPath(), ch.kind(), null, letter + " "),
                letter,
                true,
                null);
    }

    /** The change at editor's caret, or null. */
    CommitChange changeAt(Editor editor) {
        if (editor == null || editor.getDot() == null || !(editor.getDotLine() instanceof StatusLine s))
            return null;
        if (s.kind() != Kind.FILE || s.file() == null)
            return null;
        for (CommitChange ch : changes)
            if (ch.path().equals(s.file().path()))
                return ch;
        return null;
    }

    /** The first line of the file after or before the caret's, or null. */
    Line nextFile(Editor editor, boolean forward) {
        if (editor == null || editor.getDot() == null)
            return null;
        for (Line l = forward ? editor.getDotLine().next() : editor.getDotLine().previous();
                l != null;
                l = forward ? l.next() : l.previous())
            if (l instanceof StatusLine s && s.kind() == Kind.FILE)
                return l;
        return null;
    }

    @Override
    public void afterCommand(Editor editor) {
        if (changeAt(editor) != null && changeAt(editor) != shown)
            follow.restart();
    }

    /** Shows the diff of the file at the caret, or of the first file, unified or side by side. */
    void showDiff() {
        if (commit == null)
            return;
        CommitChange ch = changeAt(Editor.windowShowing(this));
        if (ch == null && !changes.isEmpty())
            ch = changes.get(0);
        if (ch == shown && ch != null)
            return;
        shown = ch;
        if (details.sides != null)
            details.compareChange(commit, ch);
        else
            details.diff.show(commit, ch, () -> {});
    }
}
