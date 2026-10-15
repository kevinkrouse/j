/*
 * StatusActions.java
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

package org.armedbear.j.vcs.status;

import static org.armedbear.j.Constants.*;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import javax.swing.SwingUtilities;
import org.armedbear.j.ConfirmDialog;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Position;
import org.armedbear.j.Region;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.Capability;
import org.armedbear.j.vcs.CommandLog;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.diff.Patch;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;
import org.armedbear.j.vim.VimInputHandler;

/**
 * Staging, unstaging and discarding in a status buffer: of the thing at the
 * caret (a section's files, a file, a hunk), or of what the selection takes
 * in, down to single lines of a hunk. Git runs in the background; a
 * failure says so and shows the command log.
 */
public final class StatusActions {
    private StatusActions() {}

    public enum Op {
        STAGE, UNSTAGE, DISCARD
    }

    /** What an action takes: whole files of a section, or a hunk or some of its lines. */
    sealed interface Target permits Files, Part {
        VcsRepository repository();

        String section();
    }

    record Files(VcsRepository repository, String section, List<FileItem> files) implements Target {}

    /** lines: indexes into the hunk's lines, or null for all of it. */
    record Part(VcsRepository repository, String section, FileItem file, Hunk hunk, BitSet lines) implements Target {}

    /** Asks before something is lost; tests answer it themselves. */
    static BiPredicate<Editor, String> confirm =
            (editor, text) -> ConfirmDialog.showConfirmDialog(editor, text, "Discard") == RESPONSE_YES;

    /** What the selection takes in, or with none what the caret is on. */
    static List<Target> targets(VcsStatusBuffer buffer, Editor editor) {
        final List<StatusLine> lines = new ArrayList<>();
        final Position mark = editor.getMark();
        final boolean visual =
                editor.getInputHandler() instanceof VimInputHandler vim && vim.getState().getMode().isVisual();
        if (mark != null && (visual || !mark.equals(editor.getDot()))) {
            final Region r = new Region(buffer, mark, editor.getDot());
            for (Line l = r.getBeginLine(); l != null; l = l.next()) {
                // A selection takes the lines it has text of; vim's visual
                // one, every line from end to end.
                final boolean one = r.getBeginLine() == r.getEndLine();
                if (!visual && !one && l == r.getEndLine() && r.getEndOffset() == 0)
                    break;
                final boolean skip = !visual && !one && l == r.getBeginLine() && r.getBeginOffset() == l.length();
                if (!skip && l instanceof StatusLine s)
                    lines.add(s);
                if (l == r.getEndLine())
                    break;
            }
        } else if (editor.getDotLine() instanceof StatusLine s) {
            // The caret in a hunk acts on the hunk, as on its heading.
            lines.add(s.kind() == Kind.DIFF ? buffer.head(s) : s);
        }

        final Map<String, Files> files = new LinkedHashMap<>();
        final Map<Hunk, Part> parts = new IdentityHashMap<>();
        final List<Target> targets = new ArrayList<>();
        for (StatusLine l : lines) {
            if (l == null || l.repository() == null || l.section() == null)
                continue;
            switch (l.kind()) {
                case SECTION -> {
                    for (FileItem f : buffer.files(l.repository(), l.section()))
                        addFile(files, targets, l.repository(), l.section(), f);
                }
                case FILE, MESSAGE -> {
                    if (l.file() != null)
                        addFile(files, targets, l.repository(), l.section(), l.file());
                }
                case HUNK -> {
                    if (!covers(files, l) && !parts.containsKey(l.hunk())) {
                        final Part p = new Part(l.repository(), l.section(), l.file(), l.hunk(), null);
                        parts.put(l.hunk(), p);
                        targets.add(p);
                    }
                }
                case DIFF -> {
                    if (covers(files, l))
                        continue;
                    Part p = parts.get(l.hunk());
                    if (p == null) {
                        p = new Part(l.repository(), l.section(), l.file(), l.hunk(), new BitSet());
                        parts.put(l.hunk(), p);
                        targets.add(p);
                    }
                    if (p.lines() != null)
                        p.lines().set(l.diffIndex());
                }
                default -> {}
            }
        }
        for (Part p : parts.values())
            if (p.lines() != null)
                Patch.pairChanges(p.hunk(), p.lines());
        // A file taken whole takes its hunks.
        targets.removeIf(
            t -> t instanceof Part p
                    && files.containsKey(p.repository().root() + "|" + p.section())
                    && files.get(p.repository().root() + "|" + p.section()).files().contains(p.file()));
        return targets;
    }

    private static boolean covers(Map<String, Files> files, StatusLine l) {
        final Files f = files.get(l.repository().root() + "|" + l.section());
        return f != null && f.files().contains(l.file());
    }

    private static void addFile(
            Map<String, Files> files,
            List<Target> targets,
            VcsRepository repository,
            String section,
            FileItem file) {
        final String key = repository.root() + "|" + section;
        Files f = files.get(key);
        if (f == null) {
            f = new Files(repository, section, new ArrayList<>());
            files.put(key, f);
            targets.add(f);
        }
        if (!f.files().contains(file))
            f.files().add(file);
    }

    /** Does op to what the caret or selection is on. */
    public static void run(Editor editor, Op op) {
        if (!(editor.getBuffer() instanceof VcsStatusBuffer buffer) || editor.getDot() == null)
            return;
        // What the buffer shows is out of date until the last write's refresh.
        if (buffer.isWriting()) {
            editor.status("Wait: the last change is still being made");
            return;
        }
        final List<Target> targets = targets(buffer, editor);
        if (targets.isEmpty()) {
            editor.status("Nothing to " + verb(op) + " here");
            return;
        }
        for (Target t : targets) {
            final String why = refuse(op, t);
            if (why != null) {
                editor.status(why);
                return;
            }
        }
        if (op == Op.DISCARD && !confirm.test(editor, discardQuestion(targets)))
            return;
        if (targets.stream().anyMatch(t -> t.section().equals("unmerged") && op == Op.STAGE)
                && !confirm.test(editor, "Mark " + names(targets) + " resolved?"))
            return;
        editor.setMark(null);
        editor.status(switch (op) {
            case STAGE -> "Staging ";
            case UNSTAGE -> "Unstaging ";
            case DISCARD -> "Discarding ";
        } + names(targets) + "…");
        buffer.startWriting();
        Background.start("vcs " + verb(op), () -> {
            String failed = null;
            // Whatever happens, the buffer is read again and takes keys again.
            try {
                if (op == Op.DISCARD)
                    failed = discard(targets);
                else
                    for (Target t : targets) {
                        failed = perform(op, t);
                        if (failed != null)
                            break;
                    }
            }
            catch (RuntimeException e) {
                Log.error(e);
                failed = "Failed: " + e;
            }
            finish(editor, buffer, failed);
        });
    }

    /** Stages every change to a tracked file, or with unstage takes everything staged out. */
    public static void all(Editor editor, boolean stage) {
        if (!(editor.getBuffer() instanceof VcsStatusBuffer buffer))
            return;
        if (buffer.isWriting()) {
            editor.status("Wait: the last change is still being made");
            return;
        }
        if (!VcsStatusMode.stages(buffer)) {
            editor.status("Nothing here stages");
            return;
        }
        // git reset ends a merge, or a rebase's step, keeping its conflict markers.
        if (!stage && buffer.getRepositories().stream().anyMatch(r -> {
            final StatusData d = buffer.getData(r);
            return d != null && d.inProgress() != null;
        })) {
            editor.status("Not while a merge or rebase is in progress: it would end it. u unstages a file");
            return;
        }
        buffer.startWriting();
        Background.start("vcs " + (stage ? "stage" : "unstage") + " all", () -> {
            String failed = null;
            try {
                for (VcsRepository r : buffer.getRepositories()) {
                    final VcsBackend b = r.backend();
                    failed = failure(b, stage ? b.stageAll(r) : b.unstageAll(r));
                    if (failed != null)
                        break;
                }
            }
            catch (RuntimeException e) {
                Log.error(e);
                failed = "Failed: " + e;
            }
            finish(editor, buffer, failed);
        });
    }

    private static void finish(Editor editor, VcsStatusBuffer buffer, String failed) {
        buffer.wrote();
        for (VcsRepository r : buffer.getRepositories()) {
            r.invalidate();
            r.refresh(); // Tells the buffer, which reads again.
        }
        SwingUtilities.invokeLater(() -> {
            if (failed == null) {
                editor.status("");
                return;
            }
            editor.status(failed);
            if (editor.getFrame() != null)
                CommandLog.vcsCommandLog();
        });
    }

    // Why op can't be done to t, or null.
    static String refuse(Op op, Target t) {
        final String section = t.section();
        if (op != Op.DISCARD && !t.repository().backend().capabilities().contains(Capability.STAGING))
            return t.repository().backend().name() + " has no index to stage to";
        return switch (op) {
            case STAGE -> section.equals("staged") ? "Already staged"
                    : section.equals("recent") ? "Nothing to stage here"
                    : t instanceof Part && !section.equals("unstaged") ? "Only an unstaged hunk is staged in part"
                    : null;
            case UNSTAGE -> section.equals("staged") ? null : "Not staged";
            case DISCARD -> section.equals("recent") || section.equals("unmerged") ? "Nothing to discard here" : null;
        };
    }

    // Does it, off the event thread; what went wrong, or null.
    static String perform(Op op, Target t) {
        final VcsRepository r = t.repository();
        final VcsBackend b = r.backend();
        if (t instanceof Files f) {
            final List<String> paths = f.files().stream().map(FileItem::path).toList();
            return switch (op) {
                case STAGE -> failure(b, b.stage(r, paths));
                case UNSTAGE -> failure(b, b.unstage(r, f.files().stream().map(FileItem::change).toList()));
                case DISCARD -> discard(List.of(t));
            };
        }
        final Part p = (Part) t;
        if (op == Op.DISCARD)
            return discard(List.of(t));
        final FileDiff diff = p.file().diff();
        if (diff == null)
            return "Open " + p.file().path() + " first";
        // The hunk as shown must still be there: a patch from an old diff
        // would stage what the file no longer holds.
        if (!stillThere(r, p))
            return p.file().path() + " changed since it was shown; look again";
        final byte[] patch = patch(p, op != Op.STAGE);
        if (patch == null)
            return "Those lines change nothing";
        return failure(b, b.apply(r, patch, true, false, op == Op.UNSTAGE));
    }

    private static byte[] patch(Part p, boolean reverse) {
        return p.lines() == null
                ? Patch.forHunk(p.file().diff(), p.hunk(), reverse)
                : Patch.forLines(p.file().diff(), p.hunk(), p.lines(), reverse);
    }

    private static boolean stillThere(VcsRepository r, Part p) {
        final FileChange c = p.file().change();
        final List<String> paths =
                c.origPath() != null && p.file().staged() ? List.of(c.origPath(), c.path()) : List.of(c.path());
        for (FileDiff d : r.backend().readDiff(r, p.file().staged(), paths))
            if (d.hunks().contains(p.hunk()))
                return true;
        return false;
    }

    // A discard is worked out whole, and checked, before any of it is done.
    private sealed interface Step permits Trash, Checkout, Apply {}

    /** A file not in HEAD, to the trash; taken out of the index first if it is there. */
    private record Trash(VcsRepository repository, FileItem file, boolean inIndex) implements Step {}

    /** Files back as they are staged. */
    private record Checkout(VcsRepository repository, List<String> paths) implements Step {}

    /** A patch taken back out of the working tree, and with index the index too. */
    private record Apply(VcsRepository repository, String path, byte[] patch, boolean index) implements Step {}

    static String discard(List<Target> targets) {
        final List<Step> steps = new ArrayList<>();
        for (Target t : targets) {
            final String why = steps(t, steps);
            if (why != null)
                return why;
        }
        for (Step s : steps) {
            if (!(s instanceof Apply a))
                continue;
            final VcsBackend b = a.repository().backend();
            if (!b.applies(a.repository(), a.patch(), false, true, true))
                return a.index()
                        ? a.path() + " has unstaged changes where the staged ones are: stage or discard those first"
                        : a.path() + " changed since it was shown; look again";
            if (a.index() && !b.applies(a.repository(), a.patch(), true, false, true))
                return a.path() + " changed since it was shown; look again";
        }
        for (Step s : steps) {
            final String failed = switch (s) {
                case Trash t -> {
                    final VcsBackend b = t.repository().backend();
                    final String unstaged =
                            t.inIndex() ? failure(b, b.unstage(t.repository(), List.of(t.file().change()))) : null;
                    yield unstaged != null ? unstaged : trash(t.repository(), List.of(t.file().path()));
                }
                case Checkout c ->
                        failure(c.repository().backend(), c.repository().backend().discard(c.repository(), c.paths()));
                case Apply a -> {
                    final VcsBackend b = a.repository().backend();
                    final String tree = failure(b, b.apply(a.repository(), a.patch(), false, true, true));
                    yield tree != null || !a.index()
                            ? tree
                            : failure(b, b.apply(a.repository(), a.patch(), true, false, true));
                }
            };
            if (failed != null)
                return failed;
        }
        return null;
    }

    // What discarding t takes, added to steps; or why it can't be.
    private static String steps(Target t, List<Step> steps) {
        final VcsRepository r = t.repository();
        final boolean staged = t.section().equals("staged");
        if (t instanceof Files f) {
            final List<String> checkout = new ArrayList<>();
            for (FileItem i : f.files()) {
                final FileChange c = i.change();
                if (t.section().equals("untracked"))
                    steps.add(new Trash(r, i, false));
                else if (isNew(i))
                    steps.add(new Trash(r, i, true));
                else if (staged) {
                    final String why = stagedSteps(r, i, steps);
                    if (why != null)
                        return why;
                } else if (c.unstaged() != null)
                    checkout.add(i.path());
            }
            if (!checkout.isEmpty())
                steps.add(new Checkout(r, checkout));
            return null;
        }
        final Part p = (Part) t;
        if (p.file().diff() == null)
            return "Open " + p.file().path() + " first";
        if (p.lines() == null && p.file().diff().isNew()) {
            steps.add(new Trash(r, p.file(), true));
            return null;
        }
        if (staged && p.file().diff().isRename())
            return "Unstage the rename of " + p.file().path() + " (u) before discarding it";
        final byte[] patch = patch(p, true);
        if (patch == null)
            return "Those lines change nothing";
        steps.add(new Apply(r, p.file().path(), patch, staged));
        return null;
    }

    // A file new to the index: staged as added, or noted with git add -N.
    private static boolean isNew(FileItem i) {
        final FileChange c = i.change();
        return i.staged()
                ? c.staged() == FileChange.Kind.ADDED
                : c.staged() == null && c.unstaged() == FileChange.Kind.ADDED;
    }

    // A staged file's changes, hunk by hunk from the bottom, so each still
    // finds its lines.
    private static String stagedSteps(VcsRepository r, FileItem f, List<Step> steps) {
        if (f.change().origPath() != null)
            return "Unstage the rename of " + f.path() + " (u) before discarding it";
        FileDiff diff = f.diff();
        if (diff == null) {
            final List<FileDiff> read = r.backend().readDiff(r, true, List.of(f.path()));
            diff = read.isEmpty() ? null : read.get(0);
        }
        if (diff == null || diff.hunks().isEmpty())
            return "Can't discard " + f.path() + ": it has no change to its content";
        final List<Hunk> hunks = new ArrayList<>(diff.hunks());
        hunks.sort(Comparator.comparingInt(Hunk::newStart).reversed());
        for (Hunk h : hunks) {
            final byte[] patch = Patch.forHunk(diff, h, true);
            if (patch != null)
                steps.add(new Apply(r, f.path(), patch, true));
        }
        return null;
    }

    // Untracked files to the desktop's trash, where there is one; deleted where not.
    private static String trash(VcsRepository r, List<String> paths) {
        final boolean trash = canTrash();
        for (String path : paths) {
            final Path p = r.root().resolve(path);
            try {
                if (trash) {
                    if (!Desktop.getDesktop().moveToTrash(p.toFile()))
                        return "Couldn't move " + path + " to the trash";
                } else
                    java.nio.file.Files.deleteIfExists(p);
            }
            catch (IOException | RuntimeException e) {
                Log.debug(e);
                return "Couldn't remove " + path + ": " + e.getMessage();
            }
        }
        return null;
    }

    static boolean canTrash() {
        try {
            return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    private static String failure(VcsBackend backend, VcsResult result) {
        if (result == null)
            return backend.name() + " can't do that";
        if (result.succeeded())
            return null;
        final String err = result.stderr().strip();
        final String first = err.isEmpty() ? "exit code " + result.exitCode() : err.split("\n", 2)[0];
        return backend.name() + ": " + first;
    }

    static String discardQuestion(List<Target> targets) {
        // Files not in HEAD go whole: to the trash, or deleted.
        final List<String> untracked = new ArrayList<>();
        final List<Target> changes = new ArrayList<>();
        for (Target t : targets) {
            if (t instanceof Files f) {
                final List<FileItem> kept = new ArrayList<>();
                for (FileItem i : f.files())
                    if (t.section().equals("untracked") || isNew(i))
                        untracked.add(i.path());
                    else
                        kept.add(i);
                if (!kept.isEmpty())
                    changes.add(new Files(f.repository(), f.section(), kept));
            } else if (t instanceof Part p && p.lines() == null && p.file().diff() != null && p.file().diff().isNew())
                untracked.add(p.file().path());
            else
                changes.add(t);
        }
        final boolean staged = changes.stream().anyMatch(t -> t.section().equals("staged"));
        final StringBuilder sb = new StringBuilder();
        if (!changes.isEmpty()) {
            sb.append("Discard ").append(names(changes));
            sb.append(staged ? ", staged and in the working tree?" : "?");
            sb.append(" The changes are lost.");
        }
        if (!untracked.isEmpty()) {
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(canTrash() ? "Move " : "Delete ").append(list(untracked));
            sb.append(canTrash() ? " to the trash?" : "? They are not in a commit, and are lost.");
        }
        return sb.toString();
    }

    // "a.txt", "a hunk of a.txt", "2 lines of a.txt", "a.txt and 3 more".
    static String names(List<Target> targets) {
        final List<String> names = new ArrayList<>();
        for (Target t : targets) {
            if (t instanceof Files f)
                for (FileItem i : f.files())
                    names.add(i.path());
            else if (t instanceof Part p) {
                final int n = p.lines() == null ? 0 : p.lines().cardinality();
                names.add(
                    (p.lines() == null ? "a hunk of " : n == 1 ? "a line of " : n + " lines of ") + p.file().path());
            }
        }
        return list(names);
    }

    private static String list(List<String> names) {
        if (names.size() <= 3)
            return String.join(", ", names);
        return String.join(", ", names.subList(0, 2)) + " and " + (names.size() - 2) + " more";
    }

    private static String verb(Op op) {
        return switch (op) {
            case STAGE -> "stage";
            case UNSTAGE -> "unstage";
            case DISCARD -> "discard";
        };
    }
}
