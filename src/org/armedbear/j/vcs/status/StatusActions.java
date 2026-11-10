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
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.armedbear.j.ConfirmDialog;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Region;
import org.armedbear.j.mode.compare.DiffWhitespace;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Capability;
import org.armedbear.j.vcs.CommandLog;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.conflicts.ConflictMarkers;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.diff.Patch;
import org.armedbear.j.vcs.selection.FileKey;
import org.armedbear.j.vcs.selection.Selections;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.selection.Target.Files;
import org.armedbear.j.vcs.selection.Target.Part;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/**
 * Staging, unstaging and discarding in a status buffer, or in its diff
 * beside it: of the thing at the caret (a section's files, a file, a hunk),
 * or of what the selection takes in, down to single lines of a hunk. Git
 * runs in the background; a failure says so and shows the command log.
 */
public final class StatusActions {
    private StatusActions() {}

    public enum Op {
        STAGE, UNSTAGE, DISCARD
    }

    /** A yes-or-no question under a title. */
    public interface Confirm {
        boolean test(Editor editor, String text, String title);
    }

    /** Asks before something is lost or staged; tests answer it themselves. */
    public static Confirm confirm =
            (editor, text, title) -> ConfirmDialog.showConfirmDialog(editor, text, title) == RESPONSE_YES;

    /**
     * The lines the selection takes, those it has text of, or vim's visual
     * one's every line; else the caret's, a diff line's hunk's heading, as
     * the caret in a hunk acts on the hunk.
     */
    static List<StatusLine> selected(Editor editor) {
        final List<StatusLine> lines = new ArrayList<>();
        if (Region.isSelection(editor)) {
            for (Line l : Region.selectedLines(editor))
                if (l instanceof StatusLine s)
                    lines.add(s);
        } else if (editor.getDotLine() instanceof StatusLine s) {
            // The caret in a hunk acts on the hunk, as on its heading.
            lines.add(s.kind() == Kind.DIFF ? StatusLine.head(s) : s);
        }
        return lines;
    }

    /**
     * Whether the targets in editor are what is picked: something is, and
     * no selection, which is always meant, comes before it.
     */
    static boolean takesPicks(RepositoryStatus model, Editor editor) {
        return !model.picks().isEmpty() && !Region.isSelection(editor);
    }

    /**
     * What the selection takes in; else what is picked; else what the
     * caret is on, in editor's buffer: the status's, or its diff's, whose
     * lines are keyed as the status's are.
     */
    static List<Target> targets(VcsStatusBuffer buffer, Editor editor) {
        return targets(buffer.model(), editor);
    }

    /** As targets, of the model: what the selection takes, else what is picked, else the caret's. */
    static List<Target> targets(RepositoryStatus model, Editor editor) {
        return targets(model, editor, takesPicks(model, editor));
    }

    /** As targets, picked whether it takes the picks, as takesPicks says. */
    static List<Target> targets(RepositoryStatus model, Editor editor, boolean picked) {
        return picked ? model.picks().targets(model) : targetsOf(model, selected(editor));
    }

    /**
     * What these status lines take, as the status's own lines would: whole
     * files, a changelist heading its members, a hunk or some of its
     * lines a Part. What the Changes tab builds from its nodes.
     */
    static List<Target> targetsOf(RepositoryStatus model, List<StatusLine> lines) {
        final Map<FileKey.Section, FileGroup> files = new LinkedHashMap<>();
        final Map<Hunk, Part> parts = new IdentityHashMap<>();
        final List<Target> targets = new ArrayList<>();
        final Map<Hunk, BitSet> selectedLines = new IdentityHashMap<>();
        final Function<FileDiff, Map<String, Hunk>> ids = memoIds();
        for (StatusLine l : lines) {
            if (l == null || l.repository() == null || l.section() == null)
                continue;
            switch (l.kind()) {
                case SECTION -> {
                    for (FileItem f : model.files(l.repository(), l.section()))
                        addFile(files, targets, l.repository(), l.section(), f);
                }
                // A changelist's files, those split across lists their lines in it.
                case LIST -> addMembers(files, parts, targets, ids, l.repository(), l.section(), l.members());
                case FILE, MESSAGE -> {
                    if (l.file() != null && l.only() != null)
                        addLines(parts, targets, ids, l.repository(), l.section(), l.file(), l.only());
                    else if (l.file() != null)
                        addFile(files, targets, l.repository(), l.section(), l.file());
                }
                case HUNK -> {
                    if (l.only() != null)
                        addLines(parts, targets, ids, l.repository(), l.section(), l.file(), l.only());
                    else if (!covers(files, l) && !parts.containsKey(l.hunk())) {
                        final Part p = Part.of(l.repository(), l.section(), l.file(), l.hunk(), null);
                        parts.put(l.hunk(), p);
                        targets.add(p);
                    }
                }
                case DIFF -> {
                    if (covers(files, l) || l.isForeign())
                        continue;
                    Part p = parts.get(l.hunk());
                    if (p == null) {
                        p = Part.of(l.repository(), l.section(), l.file(), l.hunk(), new BitSet());
                        parts.put(l.hunk(), p);
                        targets.add(p);
                    }
                    if (p.selection() != null) {
                        final Part current = p;
                        selectedLines.computeIfAbsent(l.hunk(), h -> current.lines()).set(l.diffIndex());
                    }
                }
                default -> {}
            }
        }
        finishTargets(files, targets, selectedLines);
        // A file taken whole takes its hunks.
        targets.removeIf(
            t -> t instanceof Part p
                    && files.containsKey(new FileKey.Section(p.repository(), p.section()))
                    && files.get(new FileKey.Section(p.repository(), p.section()))
                            .members()
                            .containsKey(p.file().path()));
        return targets;
    }

    /**
     * What a changelist's members, members, in r's section, take: whole
     * files, and a file split across lists its lines there, a changed
     * line's removal with its addition; as s on the list's heading takes
     * them. A split file whose diff isn't read takes nothing.
     */
    static List<Target> listTargets(VcsRepository r, String section, List<Target.Member> members) {
        final Map<FileKey.Section, FileGroup> files = new LinkedHashMap<>();
        final Map<Hunk, Part> parts = new IdentityHashMap<>();
        final List<Target> targets = new ArrayList<>();
        addMembers(files, parts, targets, memoIds(), r, section, members);
        finishTargets(files, targets, Map.of());
        return targets;
    }

    // A changelist's members as targets, added to those already.
    private static void addMembers(
            Map<FileKey.Section, FileGroup> files,
            Map<Hunk, Part> parts,
            List<Target> targets,
            Function<FileDiff, Map<String, Hunk>> ids,
            VcsRepository r,
            String section,
            List<Target.Member> members) {
        for (Target.Member m : members)
            if (m.only() == null)
                addFile(files, targets, r, section, m.file());
            else
                addLines(parts, targets, ids, r, section, m.file(), m.only());
    }

    // A caller's hunk-id lookup, hashing each diff's changed bytes once:
    // keyed on the diff's identity, which is immutable, so the memo is
    // exact for the caller's lifetime.
    private static Function<FileDiff, Map<String, Hunk>> memoIds() {
        final Map<FileDiff, Map<String, Hunk>> memo = new IdentityHashMap<>();
        return diff -> memo.computeIfAbsent(diff, Selections::hunkIds);
    }

    // Of file, the lines only gives by hunk id, as parts, added to those
    // already of their hunks. ids, the diff's hunk ids, hashed once and
    // kept by the caller: many members share a file's diff, and hashing
    // it again per member would repeat a SHA-256 pass over the same
    // changed bytes.
    private static void addLines(
            Map<Hunk, Part> parts,
            List<Target> targets,
            Function<FileDiff, Map<String, Hunk>> ids,
            VcsRepository repository,
            String section,
            FileItem file,
            Map<String, BitSet> only) {
        if (file.diff() == null)
            return;
        final Map<String, Hunk> byId = ids.apply(file.diff());
        for (Map.Entry<String, BitSet> e : only.entrySet()) {
            final Hunk h = byId.get(e.getKey());
            if (h == null)
                continue;
            final Part p = parts.get(h);
            if (p == null) {
                final Part made = Part.of(repository, section, file, h, (BitSet) e.getValue().clone());
                parts.put(h, made);
                targets.add(made);
            } else if (p.lines() != null) {
                final BitSet bits = p.lines();
                bits.or(e.getValue());
                putPart(parts, targets, p.withLines(bits));
            }
        }
    }

    private static void putPart(Map<Hunk, Part> parts, List<Target> targets, Part next) {
        final Part before = parts.put(next.hunk(), next);
        if (before == null)
            targets.add(next);
        else
            targets.set(targets.indexOf(before), next);
    }

    // Mutable builders stay here; the targets handed to commands are immutable.
    // Membership is by literal path within a repository's section. Hashing
    // a FileItem would traverse its entire diff on every covered line.
    private record FileGroup(Map<String, FileItem> members) {}

    private static void finishTargets(
            Map<FileKey.Section, FileGroup> files,
            List<Target> targets,
            Map<Hunk, BitSet> selectedLines) {
        targets.replaceAll(t -> {
            if (t instanceof Files f)
                return new Files(f.repository(),
                        f.section(),
                        List.copyOf(files.get(new FileKey.Section(f.repository(), f.section())).members().values()));
            final Part p = (Part) t;
            if (p.selection() == null)
                return p;
            final BitSet bits = p.lines();
            if (selectedLines.containsKey(p.hunk()))
                bits.or(selectedLines.get(p.hunk()));
            Patch.pairChanges(p.hunk(), bits);
            return p.withLines(bits);
        });
    }

    private static boolean covers(Map<FileKey.Section, FileGroup> files, StatusLine l) {
        final FileGroup f = files.get(new FileKey.Section(l.repository(), l.section()));
        return f != null && f.members().containsKey(l.file().path());
    }

    private static void addFile(
            Map<FileKey.Section, FileGroup> files,
            List<Target> targets,
            VcsRepository repository,
            String section,
            FileItem file) {
        final FileKey.Section key = new FileKey.Section(repository, section);
        FileGroup group = files.get(key);
        if (group == null) {
            final Files target = new Files(repository, section, List.of());
            group = new FileGroup(new LinkedHashMap<>());
            files.put(key, group);
            targets.add(target);
        }
        group.members().putIfAbsent(file.path(), file);
    }

    /**
     * Whether editor's buffer is the status's diff beside it, where the
     * hunk commands of a file being edited (vim's {@code <leader>hs},
     * {@code hu} and {@code hr}) stage, unstage and discard as {@link #run} does.
     */
    public static boolean takesHunkCommands(Editor editor) {
        return editor.getBuffer() instanceof StatusDiffBuffer;
    }

    /** Does op to what the caret or selection is on, in the status or its diff. */
    public static void run(Editor editor, Op op) {
        final VcsStatusBuffer buffer = VcsStatusMode.owner(editor);
        if (buffer == null || editor.getDot() == null)
            return;
        final StatusLine stash = stashDropped(editor, op);
        if (stash != null) {
            StashCommands.drop(editor, stash.repository(), stash.stash());
            return;
        }
        final boolean picked = takesPicks(buffer.model(), editor);
        final List<Target> targets = targets(buffer.model(), editor, picked);
        // The picks the targets are, if any: those picked after stay.
        final Map<Target, Set<String>> used = picked ? buffer.picks().used(targets) : Map.of();
        runOn(editor, buffer.model(), op, targets, used, picked);
    }

    /**
     * Does op to targets in model, asked where op asks, as run does: what
     * the Changes tab's menu does with the targets of its nodes, and the
     * status's menu with those of its lines, without the caret being
     * where they came from.
     */
    static void runOn(
            Editor editor,
            RepositoryStatus model,
            Op op,
            List<Target> targets,
            Map<Target, Set<String>> used,
            boolean picked) {
        final String why = refusal(editor, model, op, targets, picked);
        if (why != null) {
            // A read that left a picked file's diff unread, as one that
            // failed, is tried again.
            if (picked && model.picks().unread(model))
                model.refresh();
            editor.status(why);
            return;
        }
        if (model.resolveTargets(targets, resolved -> {
            if (!resolved.ready()) {
                editor.status(resolved.failure());
                return;
            }
            final Map<Target, Set<String>> currentUsed = new IdentityHashMap<>();
            for (int i = 0; i < targets.size(); i++)
                if (used.containsKey(targets.get(i)))
                    currentUsed.put(resolved.targets().get(i), used.get(targets.get(i)));
            runResolved(editor, model, op, resolved.targets(), currentUsed, picked);
        }))
            editor.status("Reading the selected files' diffs…");
    }

    private static void runResolved(
            Editor editor,
            RepositoryStatus model,
            Op op,
            List<Target> targets,
            Map<Target, Set<String>> used,
            boolean picked) {
        final String why = refusal(editor, model, op, targets, picked);
        if (why != null) {
            editor.status(why);
            return;
        }
        if (op == Op.DISCARD && allUnmerged(targets)) {
            ResolveCommands.open(editor, targets);
            return;
        }
        if (op == Op.DISCARD && !confirm.test(editor, discardQuestion(targets), "Discard"))
            return;
        if (op == Op.STAGE) {
            final List<Target> unmerged = targets.stream().filter(StatusActions::isUnmerged).toList();
            // Read and counted off the event thread; the question and the
            // write in the callback, if the status is still ours. An
            // unmerged file is always asked about, another only with
            // markers in what it stages. The count holds the gate, as the
            // write after it does; a no gives it back.
            final boolean shownWhole = model.readIgnoring() == Whitespace.NONE;
            model.startWriting();
            Background.then("vcs count conflicts", () -> markersStaged(targets, shownWhole), conflicts -> {
                final int left = conflicts == null ? 0 : conflicts;
                if (!unmerged.isEmpty()
                        ? !confirm.test(
                            editor,
                            leftQuestion("Mark " + names(unmerged) + " resolved?", left),
                            "Mark Resolved")
                        : left > 0
                                && !confirm
                                        .test(editor, leftQuestion("Stage " + names(targets) + "?", left), "Stage")) {
                    model.gaveUpWriting();
                    return;
                }
                start(editor, model, "Staging", targets, used, did -> performAll(op, targets, did));
            });
            return;
        }
        start(editor, model, switch (op) {
            case STAGE -> "Staging";
            case UNSTAGE -> "Unstaging";
            case DISCARD -> "Discarding";
        }, targets, used, did -> op == Op.DISCARD ? discard(targets, did) : performAll(op, targets, did));
    }

    // Does op to every target, telling did of each done; the first thing
    // that went wrong, or null.
    private static String performAll(Op op, List<Target> targets, Consumer<Target> did) {
        for (Target t : targets) {
            final String failed = perform(op, t);
            if (failed != null)
                return failed;
            did.accept(t);
        }
        return null;
    }

    /**
     * Says what is being done to targets and does work, as write does,
     * which tells its argument of each target done, if it can. The picks
     * used, by target, are unpicked: all once the work is done, else
     * those of the targets done.
     */
    static void start(
            Editor editor,
            RepositoryStatus model,
            String doing,
            List<Target> targets,
            Map<Target, Set<String>> used,
            Function<Consumer<Target>, String> work) {
        editor.setMark(null);
        editor.status(doing + " " + names(targets) + "…");
        final List<Target> did = Collections.synchronizedList(new ArrayList<>());
        write(editor, model, "vcs " + doing.toLowerCase(Locale.ROOT), () -> work.apply(did::add), failed -> {
            final Set<String> keys = new HashSet<>();
            for (Map.Entry<Target, Set<String>> e : used.entrySet())
                if (failed == null || did.contains(e.getKey()))
                    keys.addAll(e.getValue());
            model.unpick(keys);
        });
    }

    // Runs work, what went wrong or null, off the event thread, then reads the buffer again.
    static void write(Editor editor, RepositoryStatus model, String name, Supplier<String> work) {
        write(editor, model, name, work, null);
    }

    // As write, done given what went wrong or null on the event thread,
    // before the read after.
    private static void write(
            Editor editor,
            RepositoryStatus model,
            String name,
            Supplier<String> work,
            Consumer<String> done) {
        model.startWriting();
        Background.start(name, () -> {
            String failed = null;
            // Whatever happens, the buffer is read again and takes keys again.
            try {
                failed = work.get();
            }
            catch (RuntimeException e) {
                Log.error(e);
                failed = "Failed: " + e;
            }
            finish(editor, model, failed, done);
        });
    }

    // Whether t is files of the unmerged section.
    static boolean isUnmerged(Target t) {
        return t instanceof Files && t.section().equals(StatusData.UNMERGED);
    }

    // Whether every target is files of the unmerged section.
    static boolean allUnmerged(List<Target> targets) {
        return !targets.isEmpty() && targets.stream().allMatch(StatusActions::isUnmerged);
    }

    /** question, then how many conflicts are left, if any. */
    public static String leftQuestion(String question, int left) {
        return question + (left == 0 ? "" : left == 1 ? " 1 conflict is left." : " " + left + " conflicts are left.");
    }

    // Conflict markers staging targets would put in the index: an
    // unmerged or untracked file's whole, else the lines it adds, from
    // the diff shown when it shows all of it.
    private static int markersStaged(List<Target> targets, boolean shownWhole) {
        int n = 0;
        final Map<VcsRepository, List<String>> changed = new LinkedHashMap<>();
        for (Target t : targets)
            if (t instanceof Files f) {
                for (FileItem i : f.files())
                    if (i.change().isConflicted() || i.change().isUntracked())
                        n += ConflictMarkers.count(f.repository().root().resolve(i.path()));
                    else if (shownWhole && i.diff() != null && !i.diff().isCombined())
                        n += markersAdded(i.diff());
                    else
                        changed.computeIfAbsent(f.repository(), r -> new ArrayList<>()).add(i.path());
            } else if (t instanceof Part p) {
                n += markersAdded(p.hunk(), p.lines());
            }
        for (Map.Entry<VcsRepository, List<String>> e : changed.entrySet())
            n += markersAdded(e.getKey(), e.getValue());
        return n;
    }

    // Markers in the lines the working tree adds to paths, or with none
    // to every tracked file; an unmerged file's combined diff is skipped.
    private static int markersAdded(VcsRepository r, List<String> paths) {
        final List<FileDiff> diffs = r.backend().readDiff(r, false, paths, Whitespace.NONE);
        int n = 0;
        if (diffs != null)
            for (FileDiff d : diffs)
                if (!d.isCombined())
                    n += markersAdded(d);
        return n;
    }

    private static int markersAdded(FileDiff d) {
        int n = 0;
        for (Hunk h : d.hunks())
            n += markersAdded(h, null);
        return n;
    }

    // Markers in hunk's added lines, those of lines alone if not null.
    private static int markersAdded(Hunk hunk, BitSet lines) {
        final List<String> added = new ArrayList<>();
        for (int i = 0; i < hunk.lines().size(); i++)
            if (hunk.lines().get(i).kind() == '+' && (lines == null || lines.get(i)))
                added.add(hunk.lines().get(i).text());
        return ConflictMarkers.count(added);
    }

    /** Whether op would do something to what the caret or selection is on, as its menu item is enabled. */
    public static boolean can(Editor editor, Op op) {
        return able(editor).contains(op);
    }

    /** Whether r's system can be told to ignore a file, as git's .gitignore. */
    static boolean canIgnore(VcsRepository r) {
        return r != null && r.backend().canIgnore();
    }

    /**
     * Tells r's system to ignore path, relative to its root, then reads
     * the status again so it leaves Untracked: Add to .gitignore. What
     * went wrong is said in the status bar.
     */
    static void ignore(Editor editor, RepositoryStatus model, VcsRepository r, String path) {
        if (!canIgnore(r)) {
            editor.status(r == null ? "No repository here" : r.backend().name() + " can't ignore a file here");
            return;
        }
        write(editor, model, "vcs ignore", () -> {
            final VcsResult result = r.backend().writeIgnore(r, path);
            return result != null && !result.succeeded() ? result.lastError() : null;
        });
    }

    /** vcsIgnore: adds the file at the caret to the repository's ignore file. */
    public static void vcsIgnore(Editor editor) {
        final VcsStatusBuffer buffer = VcsStatusMode.owner(editor);
        if (buffer == null || !(editor.getDotLine() instanceof StatusLine s) || s.file() == null) {
            editor.status("No file here to ignore");
            return;
        }
        ignore(editor, buffer.model(), s.repository(), s.file().path());
    }

    /** vcsCopyPath: the file at the caret, relative to its repository, on the clipboard and in the kill ring. */
    public static void vcsCopyPath(Editor editor) {
        if (!(editor.getDotLine() instanceof StatusLine s) || s.file() == null) {
            editor.status("No file here");
            return;
        }
        VcsContextMenus.copyPath(editor, s.file().path());
    }

    /** vcsCopyHash: the commit at the caret's hash, on the clipboard and in the kill ring. */
    public static void vcsCopyHash(Editor editor) {
        if (!(editor.getDotLine() instanceof StatusLine s) || s.commit() == null) {
            editor.status("No commit here");
            return;
        }
        VcsContextMenus.copyPath(editor, s.commit().hash());
    }

    /** What would do something to what the caret or selection is on, the targets worked out once. */
    public static Set<Op> able(Editor editor) {
        final Set<Op> out = EnumSet.noneOf(Op.class);
        final VcsStatusBuffer buffer = editor == null ? null : VcsStatusMode.owner(editor);
        if (buffer == null || editor.getDot() == null)
            return out;
        if (!(editor.getBuffer() instanceof VcsStatusBuffer || editor.getBuffer() instanceof StatusDiffBuffer))
            return out;
        final boolean picked = takesPicks(buffer.model(), editor);
        final List<Target> targets = targets(buffer.model(), editor, picked);
        for (Op op : Op.values())
            if (stashDropped(editor, op) != null
                    ? !buffer.isWriting()
                    : refusal(editor, buffer.model(), op, targets, picked) == null)
                out.add(op);
        return out;
    }

    // x on a stash's line drops it, whatever is picked, a stash being no
    // pick: the line, or null.
    private static StatusLine stashDropped(Editor editor, Op op) {
        return op == Op.DISCARD
                && editor.getMark() == null
                && editor.getDotLine() instanceof StatusLine s
                && s.kind() == Kind.COMMIT
                && s.stash() != null ? s : null;
    }

    // Why op can't be done to targets in model, seen from editor,
    // picked if they are the picks, or null.
    static String refusal(Editor editor, RepositoryStatus model, Op op, List<Target> targets, boolean picked) {
        if (model.isDisposed())
            return "The status was closed: select the changes again";
        // The diff beside shows what was there before the last write until it is read again.
        if (editor.getBuffer() instanceof StatusDiffBuffer d && d.isStale())
            return "Wait: the diff is being read again";
        // What the buffer shows is out of date until the last write's refresh.
        if (model.isWriting())
            return "Wait: the last change is still being made";
        if (picked && model.picks().unread(model))
            return "Wait: the picked files' diffs are being read";
        if (targets.isEmpty())
            return "Nothing to " + verb(op) + " here";
        for (Target t : targets) {
            final String why = refuse(op, t);
            if (why != null)
                return why;
        }
        // x on unmerged files opens the resolve popup, which takes them all
        // the same way; a mix of kinds has no single answer.
        if (op == Op.DISCARD && targets.stream().anyMatch(StatusActions::isUnmerged)) {
            if (!allUnmerged(targets))
                return "Resolve the conflicts on their own";
            if (ResolveCommands.offered(targets).isEmpty())
                return "Those conflicts differ: resolve them one at a time";
        }
        if (hunksIgnoreWhitespace(model, targets))
            return "Not a hunk while whitespace is ignored: a file is " + switch (op) {
                case STAGE -> "staged";
                case UNSTAGE -> "unstaged";
                case DISCARD -> "discarded";
            } + " whole";
        return null;
    }

    // Whether targets take a hunk or lines of one, while the diffs shown,
    // or those to come, ignore whitespace: a patch of them can't apply.
    static boolean hunksIgnoreWhitespace(RepositoryStatus model, List<Target> targets) {
        return (DiffWhitespace.ignored() != Whitespace.NONE || model.readIgnoring() != Whitespace.NONE)
                && targets.stream().anyMatch(t -> t instanceof Part);
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
        // git reset ends a merge, a rebase's step, or a stash pop's
        // conflicts, keeping their conflict markers.
        if (!stage && buffer.getRepositories().stream().anyMatch(r -> {
            final StatusData d = buffer.getData(r);
            return d != null && d.inProgress() != null;
        })) {
            editor.status("Not while a merge, rebase or stash pop is in progress: it would end it. u unstages a file");
            return;
        }
        final Runnable all =
                () -> write(editor, buffer.model(), "vcs " + (stage ? "stage" : "unstage") + " all", () -> {
                    for (VcsRepository r : buffer.getRepositories()) {
                        final VcsBackend b = r.backend();
                        final String failed = failure(b, stage ? b.stageAll(r) : b.unstageAll(r));
                        if (failed != null)
                            return failed;
                    }
                    return null;
                });
        if (!stage) {
            all.run();
            return;
        }
        // Asked first with conflict markers in what it stages, as s asks;
        // the count holds the gate, as below.
        final List<Target> targets = new ArrayList<>();
        for (VcsRepository r : buffer.getRepositories()) {
            final StatusData d = buffer.getData(r);
            if (d != null)
                for (StatusData.Section section : d.sections())
                    if (section.id().equals(StatusData.UNMERGED))
                        targets.add(
                            new Files(r,
                                    section.id(),
                                    section.items()
                                            .stream()
                                            .filter(i -> i instanceof FileItem)
                                            .map(i -> (FileItem) i)
                                            .toList()));
        }
        buffer.startWriting();
        Background.then("vcs count conflicts", () -> {
            int n = markersStaged(targets, false);
            for (VcsRepository r : buffer.getRepositories())
                n += markersAdded(r, List.of());
            return n;
        }, left -> {
            if (left != null && left > 0 && !confirm.test(editor, leftQuestion("Stage every change?", left), "Stage")) {
                buffer.gaveUpWriting();
                return;
            }
            all.run();
        });
    }

    private static void finish(Editor editor, RepositoryStatus model, String failed, Consumer<String> done) {
        // Before the read after, which would drop the picks as gone.
        if (done != null)
            SwingUtilities.invokeLater(() -> done.accept(failed));
        model.wrote();
        for (VcsRepository r : model.getRepositories()) {
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
        if (t instanceof Part p && p.hunk().isCombined())
            return "Not in part: x on the unmerged file resolves it";
        if (op != Op.DISCARD && !t.repository().backend().capabilities().contains(Capability.STAGING))
            return t.repository().backend().name() + " has no index to stage to";
        return switch (op) {
            case STAGE -> section.equals("staged") ? "Already staged"
                    : isHistory(section) ? "Nothing to stage here"
                    : t instanceof Part && !section.equals("unstaged") ? "Only an unstaged hunk is staged in part"
                    : null;
            case UNSTAGE -> section.equals("staged") ? null : "Not staged";
            case DISCARD -> isHistory(section) ? "Nothing to discard here" : null;
        };
    }

    // A section of commits or stashes, not of the working tree's changes.
    private static boolean isHistory(String section) {
        return StatusData.isCommits(section) || section.equals(StatusData.STASHES);
    }

    // files out of the index. A conflict staged resolved while its merge
    // or rebase goes on is put back instead, its file as it is: u undoes s.
    private static String unstage(VcsRepository r, List<FileItem> files) {
        final VcsBackend b = r.backend();
        final Set<String> back =
                r.inProgress() != null ? b.resolvedConflicts(r, files.stream().map(FileItem::path).toList()) : Set.of();
        if (!back.isEmpty()) {
            final String failed = failure(b, b.unresolve(r, List.copyOf(back)));
            if (failed != null)
                return failed;
        }
        final List<FileChange> rest =
                files.stream().filter(i -> !back.contains(i.path())).map(FileItem::change).toList();
        return rest.isEmpty() ? null : failure(b, b.unstage(r, rest));
    }

    // Does it, off the event thread; what went wrong, or null.
    static String perform(Op op, Target t) {
        final VcsRepository r = t.repository();
        final VcsBackend b = r.backend();
        if (t instanceof Files f) {
            final List<String> paths = f.files().stream().map(FileItem::path).toList();
            return switch (op) {
                case STAGE -> failure(b, b.stage(r, paths));
                case UNSTAGE -> unstage(r, f.files());
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
        final List<FileDiff> read = r.backend().readDiff(r, p.file().staged(), paths, Whitespace.NONE);
        if (read != null)
            for (FileDiff d : read)
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
        return discard(targets, t -> {});
    }

    // As discard, telling did of each target done, its steps all done.
    private static String discard(List<Target> targets, Consumer<Target> did) {
        final List<Step> steps = new ArrayList<>();
        // Each target's steps' end in steps.
        final int[] ends = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            final String why = steps(targets.get(i), steps);
            if (why != null)
                return why;
            ends[i] = steps.size();
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
        for (int k = 0; k < steps.size(); k++) {
            final String failed = switch (steps.get(k)) {
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
            if (failed != null) {
                for (int i = 0; i < targets.size() && ends[i] <= k; i++)
                    did.accept(targets.get(i));
                return failed;
            }
        }
        targets.forEach(did);
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
        // Read again with no whitespace ignored, as the one shown may: its
        // hunks would leave changes to whitespace.
        final List<FileDiff> read = r.backend().readDiff(r, true, List.of(f.path()), Whitespace.NONE);
        if (read == null)
            return "Couldn't read the diff of " + f.path() + ": see vcsCommandLog";
        final FileDiff diff = read.isEmpty() ? null : read.get(0);
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
    static String trash(VcsRepository r, List<String> paths) {
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

    public static String failure(VcsBackend backend, VcsResult result) {
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

    static String list(List<String> names) {
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
