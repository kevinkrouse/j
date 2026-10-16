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
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.util.Background;
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
final class LogFilesBuffer extends LogPane {
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private LogCommit commit;
    private List<CommitChange> changes = List.of();
    private CommitChange shown;
    private final Timer follow;

    LogFilesBuffer(LogBuffer owner) {
        super(owner, "files");
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

    /** The key of the line of the commit's badges: its repository's, of several, and its refs'. */
    static final String BADGES = "badges";

    // The shown commit's badges.
    private List<LogDetailsFormatter.Badge> badges = List.of();

    List<LogDetailsFormatter.Badge> badges() {
        return badges;
    }

    /** The files the commit shown changed. For tests. */
    List<CommitChange> changes() {
        return changes;
    }

    /** Reads a commit's message and files in the background, then shows them, and the first file's diff. */
    void show(LogCommit c) {
        final int at = ++generation;
        final VcsRepository r = owner.repositoryOf(c);
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
            SwingUtilities.invokeLater(() -> {
                if (at == generation && !owner.isDisposed())
                    shown(c, m, files);
            });
        });
    }

    private void shown(LogCommit c, String message, List<CommitChange> files) {
        commit = c;
        changes = files;
        shown = null;
        final List<StatusLine> lines = new ArrayList<>();
        badges = LogDetailsFormatter.badges(owner, c);
        if (!badges.isEmpty())
            lines.add(StatusLine.of(Kind.TEXT, LogDetailsFormatter.text(badges), BADGES));
        lines.add(StatusLine.of(Kind.FIELD, field("Commit:", c.id()), "commit"));
        lines.add(StatusLine.of(Kind.FIELD, field("Author:", c.author() + " <" + c.email() + ">"), "author"));
        lines.add(StatusLine.of(Kind.FIELD, field("Date:", DATE.format(Instant.ofEpochSecond(c.time()))), "date"));
        if (c.isMerge())
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
                StatusRenderer.renderFiles(owner.repositoryOf(c), "files", items, (k, kind) -> false);
        lines.addAll(fileLines);
        if (files.isEmpty())
            lines.add(StatusLine.of(Kind.MESSAGE, "  No files changed", "none"));
        replace(lines, fileLines.isEmpty() ? null : fileLines.get(0));
        showDiff();
    }

    // "Commit:" padded to the column values start at, as the status buffer's header.
    private static String field(String label, String value) {
        return String.format("%-10s%s", label, value);
    }

    private static List<String> shortIds(List<String> ids) {
        final List<String> out = new ArrayList<>();
        for (String id : ids)
            out.add(id.length() > 10 ? id.substring(0, 10) : id);
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

    /** Shows the diff of the file at the caret, or of the first file. */
    void showDiff() {
        if (owner.diff == null || commit == null)
            return;
        CommitChange ch = changeAt(LogBuffers.windowOf(this));
        if (ch == null && !changes.isEmpty())
            ch = changes.get(0);
        if (ch == shown && ch != null)
            return;
        shown = ch;
        owner.diff.show(commit, ch);
    }
}
