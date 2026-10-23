/*
 * HunkCommands.java
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

package org.armedbear.j.vcs.signs;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Position;
import org.armedbear.j.Region;
import org.armedbear.j.mode.compare.Side;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Diff.Edit;
import org.armedbear.j.vcs.Bases;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.status.StatusActions;

/**
 * The editor's hunks: staging, resetting and previewing the hunk at the
 * caret, or the lines of the selection, and taking a staged hunk back out.
 * Staging takes the buffer's text as it is, saved or not.
 */
public final class HunkCommands {
    private HunkCommands() {}

    // The buffer as it is now, and what of it the command takes: the
    // caret's line, or the lines of the selection (first > -1).
    private record Snapshot(Buffer buffer, List<String> lines, int modCount, int caret, int first, int last) {
        boolean takes(Edit e) {
            return first < 0 ? covers(e, caret, caret) : covers(e, first, last);
        }

        // Whether editor shows the buffer as it was: the same one, unchanged.
        boolean current(Editor editor) {
            return editor.getBuffer() == buffer && buffer.getModCount() == modCount;
        }
    }

    // Whether e's lines in the buffer, or the line a deletion is under, meet lines from to to.
    private static boolean covers(Edit e, int from, int to) {
        final int start = e.newCount() == 0 ? Math.max(0, e.newStart() - 1) : e.newStart();
        final int end = e.newCount() == 0 ? start : e.newStart() + e.newCount() - 1;
        return end >= from && start <= to;
    }

    private static SignsUpdater updater(Editor editor) {
        if (editor.getDot() != null && editor.getBuffer().getSignsUpdater() != null)
            return editor.getBuffer().getSignsUpdater();
        editor.status("No version control's changes here");
        return null;
    }

    private static Snapshot snapshot(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        // Line numbers, as the caret's, are stale after a split until renumbered.
        if (buffer.needsRenumbering())
            buffer.renumber();
        final List<String> lines = new ArrayList<>();
        for (Line l = buffer.getFirstLine(); l != null; l = l.next())
            lines.add(l.getText());
        int first = -1;
        int last = -1;
        if (editor.getMark() != null && !editor.getMark().equals(editor.getDot())) {
            final Region r = new Region(editor);
            first = r.getBeginLine().lineNumber();
            last = r.getEndLine().lineNumber();
            if (r.getEndOffset() == 0 && last > first)
                last--;
        }
        return new Snapshot(buffer, lines, buffer.getModCount(), editor.getDotLineNumber(), first, last);
    }

    // The bases as they are now, and the buffer's side against the index.
    private record Sides(Side head, Side index, Side now) {}

    private static Sides sides(SignsUpdater u, Snapshot s) {
        final VcsRepository r = u.getRepository();
        final Bases bases = r.backend().readBases(r, u.getPath());
        if (bases == null || bases.index() == null)
            return null;
        final Charset cs = s.buffer().getCharset();
        final Side index = Side.of(bases.index(), cs);
        final Side head = bases.head() == null ? null : Side.of(bases.head(), cs);
        final Side now = Side.ofBuffer(s.lines(), cs, index.crlf());
        return new Sides(head, index, now);
    }

    /**
     * Stages the hunk at the caret, or the lines of the selection, from the
     * buffer's text; in the status's diff, from the diff.
     */
    public static void vcsStageHunk(Editor editor) {
        if (StatusActions.takesHunkCommands(editor)) {
            StatusActions.run(editor, StatusActions.Op.STAGE);
            return;
        }
        final SignsUpdater u = updater(editor);
        if (u == null)
            return;
        final Snapshot s = snapshot(editor);
        editor.setMark(null);
        run(editor, u, "Staging…", () -> {
            final Sides sides = sides(u, s);
            if (sides == null)
                return "Not in the index";
            final List<Edit> edits = EditorHunks.edits(sides.index(), sides.now());
            final List<Edit> taken = taken(edits, s);
            if (taken.isEmpty())
                return "No unstaged change here";
            // From the bottom, so each patch still finds its lines.
            for (Edit e : taken) {
                final byte[] patch = EditorHunks
                        .patch(u.getPath(), sides.index(), sides.now(), edits, e, s.first(), s.last(), false);
                if (patch != null) {
                    final String failed =
                            failure(u, u.getRepository().backend().apply(u.getRepository(), patch, true, false, false));
                    if (failed != null)
                        return failed;
                }
            }
            return null;
        });
    }

    /**
     * Takes the staged hunk at the caret, or under the selection, back out
     * of the index; in the status's diff, as the diff shows it.
     */
    public static void vcsUnstageHunk(Editor editor) {
        if (StatusActions.takesHunkCommands(editor)) {
            StatusActions.run(editor, StatusActions.Op.UNSTAGE);
            return;
        }
        final SignsUpdater u = updater(editor);
        if (u == null)
            return;
        final Snapshot s = snapshot(editor);
        editor.setMark(null);
        run(editor, u, "Unstaging…", () -> {
            final Sides sides = sides(u, s);
            if (sides == null)
                return "Not in the index";
            final VcsRepository r = u.getRepository();
            if (sides.head() == null)
                // New to the index: the whole file comes out.
                return failure(
                    u,
                    r.backend().unstage(r, List.of(new FileChange(u.getPath(), null, Kind.ADDED, null, "A "))));
            final List<Edit> unstaged = EditorHunks.edits(sides.index(), sides.now());
            final int[] map = SignsMath.indexToBuffer(sides.index().lines().size(), unstaged);
            final List<Edit> staged = EditorHunks.edits(sides.head(), sides.index());
            final List<Edit> taken = new ArrayList<>();
            for (Edit e : staged)
                if (s.takes(onBuffer(e, map)))
                    taken.add(e);
            if (taken.isEmpty())
                return "No staged change here";
            taken.sort(Comparator.comparingInt(Edit::newStart).reversed());
            for (Edit e : taken) {
                final byte[] patch =
                        EditorHunks.patch(u.getPath(), sides.head(), sides.index(), staged, e, -1, -1, true);
                if (patch != null) {
                    final String failed = failure(u, r.backend().apply(r, patch, true, false, true));
                    if (failed != null)
                        return failed;
                }
            }
            return null;
        });
    }

    // A staged edit's place on the buffer's lines, through the unstaged
    // edits: the buffer lines its index lines are at.
    private static Edit onBuffer(Edit staged, int[] map) {
        int start = -1;
        int end = -1;
        for (int i = staged.newStart(); i < staged.newStart() + staged.newCount(); i++) {
            if (map[i] < 0)
                continue;
            if (start < 0)
                start = map[i];
            end = map[i];
        }
        if (start < 0) {
            // Deleted in the index, or all changed again: under the nearest
            // line before it the buffer still has, as its sign is.
            return new Edit(0, 0, SignsMath.atOrBefore(map, staged.newStart() - 1) + 1, 0);
        }
        return new Edit(0, 0, start, end - start + 1);
    }

    // The edits the snapshot takes, from the bottom.
    private static List<Edit> taken(List<Edit> edits, Snapshot s) {
        final List<Edit> taken = new ArrayList<>();
        for (Edit e : edits)
            if (s.takes(e))
                taken.add(e);
        taken.sort(Comparator.comparingInt(Edit::newStart).reversed());
        return taken;
    }

    /**
     * Puts the hunk at the caret, or the hunks under the selection, back as
     * they are staged: an edit of the buffer, which undo takes back.
     * revertLines, in a file the version control tracks.
     */
    public static void resetHunk(Editor editor) {
        final SignsUpdater u = updater(editor);
        if (u == null)
            return;
        final Snapshot s = snapshot(editor);
        Background.start("vcs reset hunk", () -> {
            final Sides sides = sides(u, s);
            SwingUtilities.invokeLater(() -> {
                if (sides == null) {
                    editor.status("Not in the index");
                    return;
                }
                if (!s.current(editor)) {
                    editor.status("The buffer changed; try again");
                    return;
                }
                final List<Edit> taken = taken(EditorHunks.edits(sides.index(), sides.now()), s);
                if (taken.isEmpty()) {
                    editor.status("No unstaged change here");
                    return;
                }
                editor.setMark(null);
                final CompoundEdit compound = editor.beginCompoundEdit();
                for (Edit e : taken)
                    reset(editor, e, sides.index(), sides.now().lines().size());
                editor.endCompoundEdit(compound);
                HunkPreview.close(editor);
            });
        });
    }

    /**
     * The buffer's lines of e replaced by the index's, gitLength of the
     * buffer's lines counting as git counts them. A change that reaches the
     * end takes the index's last newline, or its lack, too.
     */
    static void reset(Editor editor, Edit e, Side index, int gitLength) {
        final Buffer buffer = editor.getBuffer();
        final Line last = buffer.getLastLine();
        final Position end = new Position(last, last.length());
        final boolean toEnd = e.newStart() + e.newCount() >= gitLength;
        final Position from = e.newStart() < gitLength ? new Position(buffer.getLine(e.newStart()), 0) : end;
        final Position to = toEnd ? end : new Position(buffer.getLine(e.newStart() + e.newCount()), 0);
        final List<String> old = index.lines().subList(e.oldStart(), e.oldStart() + e.oldCount());
        final StringBuilder text = new StringBuilder(String.join("\n", old));
        if (!old.isEmpty() && !(toEnd && index.noNewline()))
            text.append('\n');
        // A buffer with no last newline gets one before what goes after its end.
        if (from == end && last.length() > 0)
            text.insert(0, '\n');
        if (!from.equals(to))
            editor.deleteRegion(from, to);
        else
            editor.setDot(from);
        if (text.length() > 0)
            editor.insertString(text.toString());
    }

    /**
     * Shows the hunk at the caret, or the staged one there, its removed and
     * added lines under it, until the next command. previewChange, in a
     * file the version control tracks.
     */
    public static void previewHunk(Editor editor) {
        final SignsUpdater u = updater(editor);
        if (u == null) {
            HunkPreview.close(editor);
            return;
        }
        final Snapshot s = snapshot(editor);
        Background.start("vcs preview hunk", () -> {
            final Sides sides = sides(u, s);
            SwingUtilities.invokeLater(() -> {
                // Moved on meanwhile: a later preview's, or none.
                if (!s.current(editor) || editor.getDotLineNumber() != s.caret())
                    return;
                if (sides == null || !preview(editor, sides, s.caret())) {
                    HunkPreview.close(editor);
                    editor.status("No change here");
                }
            });
        });
    }

    // Shows the unstaged hunk at line, else the staged one there; false if neither.
    private static boolean preview(Editor editor, Sides sides, int line) {
        final List<Edit> unstaged = EditorHunks.edits(sides.index(), sides.now());
        for (Edit e : unstaged) {
            if (covers(e, line, line)) {
                HunkPreview.show(
                    editor,
                    true,
                    false,
                    slice(sides.index(), e.oldStart(), e.oldCount()),
                    slice(sides.now(), e.newStart(), e.newCount()));
                return true;
            }
        }
        // New to the index: all of it staged, against nothing.
        final Side head = sides.head() != null ? sides.head() : Side.NONE;
        final int[] map = SignsMath.indexToBuffer(sides.index().lines().size(), unstaged);
        for (Edit e : EditorHunks.edits(head, sides.index())) {
            if (covers(onBuffer(e, map), line, line)) {
                HunkPreview.show(
                    editor,
                    true,
                    true,
                    slice(head, e.oldStart(), e.oldCount()),
                    slice(sides.index(), e.newStart(), e.newCount()));
                return true;
            }
        }
        return false;
    }

    private static List<String> slice(Side side, int start, int count) {
        return side.lines().subList(start, start + count);
    }

    // A write in the background; then the repository read again, and with it the signs.
    private static void run(Editor editor, SignsUpdater u, String doing, Supplier<String> write) {
        editor.status(doing);
        Background.start("vcs hunk", () -> {
            String failed;
            try {
                failed = write.get();
            }
            catch (RuntimeException e) {
                Log.error(e);
                failed = "Failed: " + e;
            }
            final String result = failed;
            final VcsRepository r = u.getRepository();
            r.invalidate();
            r.refresh();
            SwingUtilities.invokeLater(() -> {
                editor.status(result == null ? "" : result);
                HunkPreview.close(editor);
            });
        });
    }

    private static String failure(SignsUpdater u, VcsResult result) {
        final VcsBackend b = u.getRepository().backend();
        if (result == null)
            return b.name() + " can't do that";
        if (result.succeeded())
            return null;
        final String err = result.stderr().strip();
        return b.name() + ": " + (err.isEmpty() ? "exit code " + result.exitCode() : err.split("\n", 2)[0]);
    }

}
