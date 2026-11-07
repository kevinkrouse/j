/*
 * ChangelistCommands.java
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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.LocationBar;
import org.armedbear.j.Property;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.TransientPopup;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.Anchors;
import org.armedbear.j.vcs.changelists.Anchors.Placed;
import org.armedbear.j.vcs.changelists.ChangeTracker;
import org.armedbear.j.vcs.changelists.Changelists;
import org.armedbear.j.vcs.changelists.Changelists.Assignment;
import org.armedbear.j.vcs.changelists.Changelists.Changelist;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.signs.ChangelistMenu;
import org.armedbear.j.vcs.signs.SignsUpdater;
import org.armedbear.j.vcs.status.StatusActions.Files;
import org.armedbear.j.vcs.status.StatusActions.Part;
import org.armedbear.j.vcs.status.StatusActions.Target;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/**
 * Changelists from the status: making one, making one active, renaming and
 * deleting one, and moving changes of the work tree to one: the
 * selection's, else what is picked, else the thing at the caret, a list's
 * heading, a file, a hunk, or its lines; a file whose diff isn't read, or
 * that has none, as a binary file, moved whole. A prompt lists what a
 * command can't take dimmed, with why. None while vcsChangelists is false.
 */
public final class ChangelistCommands {
    private ChangelistCommands() {}

    /** Asks for a name; replaced in tests. */
    static BranchCommands.Ask ask = BranchCommands.ask;

    // Why a changelist can't be chosen: the move's, the active's, the default's.
    private static final String HERE = ChangelistMenu.HERE;
    private static final String ACTIVE_NOW = "active";
    private static final String NO_RENAME = "can't be renamed";
    private static final String KEPT = "always kept";

    private static final String NOT_KEPT = ChangelistMenu.NOT_KEPT;

    private static final BranchCommands.Question MOVE =
            new BranchCommands.Question(LocationBar.PROMPT_CHANGELIST, "select a changelist or enter a new one", true);
    private static final BranchCommands.Question ACTIVE =
            new BranchCommands.Question(LocationBar.PROMPT_CHANGELIST, "select the changelist to make active", false);
    private static final BranchCommands.Question RENAME =
            new BranchCommands.Question(LocationBar.PROMPT_CHANGELIST, "select the changelist to rename", false);
    private static final BranchCommands.Question DELETE =
            new BranchCommands.Question(LocationBar.PROMPT_CHANGELIST, "select the changelist to delete", false);

    private static final String ONLY_DEFAULT = "Only " + Changelists.DEFAULT + ", which stays: n makes a list";

    /** vcsChangelistNew: makes a changelist, of the repository at the caret, asking its name. */
    public static void vcsChangelistNew(Editor editor) {
        final VcsRepository r = repository(editor);
        final RepositoryLists lists = lists(editor, r);
        if (lists == null)
            return;
        final String name = ask.ask(editor, "Name:", "New Changelist", "");
        if (name == null || name.isBlank())
            return;
        editor.status(make(r, lists, name.strip()));
    }

    /**
     * vcsChangelistActive: makes the changelist at the caret active, where
     * an edit outside the others' changes goes; else asks which.
     */
    public static void vcsChangelistActive(Editor editor) {
        onList(
            editor,
            ACTIVE,
            "vcsChangelistActive",
            c -> Map.of(c.active(), ACTIVE_NOW),
            c -> "Only " + c.active() + ", which is active: n makes a list",
            ChangelistCommands::activate);
    }

    /** vcsChangelistRename: renames the changelist whose heading the caret is on, else one asked for, but the default. */
    public static void vcsChangelistRename(Editor editor) {
        onList(
            editor,
            RENAME,
            "vcsChangelistRename",
            c -> Map.of(Changelists.DEFAULT, NO_RENAME),
            c -> ONLY_DEFAULT,
            (r, lists, name) -> {
                final String to = ask.ask(editor, "New name:", "Rename Changelist", name);
                if (to == null || to.isBlank() || to.strip().equals(name))
                    return "";
                final String now = to.strip();
                if (lists.lists().refusedName(now) != null)
                    return lists.lists().refusedName(now);
                // Read again, another writer's may have it, or not the old.
                if (!lists.update(c -> c.list(now) != null || c.list(name) == null ? c : c.renamed(name, now)))
                    return NOT_KEPT;
                if (lists.lists().list(now) == null)
                    return "There is a changelist " + now + " already, or no " + name;
                relisted(r, name, now);
                return "Renamed " + name + " to " + now;
            });
    }

    /**
     * vcsChangelistDelete: deletes the changelist whose heading the caret
     * is on, else one asked for, after asking, its changes the default's.
     */
    public static void vcsChangelistDelete(Editor editor) {
        onList(
            editor,
            DELETE,
            "vcsChangelistDelete",
            c -> Map.of(Changelists.DEFAULT, KEPT),
            c -> ONLY_DEFAULT,
            (r, lists, name) -> {
                if (!StatusActions.confirm.test(
                    editor,
                    "Delete changelist " + name + "? Its changes go to " + Changelists.DEFAULT + ".",
                    "Delete Changelist"))
                    return "";
                if (!lists.update(c -> c.list(name) == null ? c : c.without(name)))
                    return NOT_KEPT;
                relisted(r, name, Changelists.DEFAULT);
                return "Deleted changelist " + name;
            });
    }

    /**
     * vcsChangelistMove: moves the work tree's changes the selection takes,
     * else those picked, else the thing at the caret, to a changelist asked
     * for, made if it is new.
     */
    public static void vcsChangelistMove(Editor editor) {
        move(editor, true);
    }

    // The move, the diffs of the files it takes read first if read and
    // they aren't, then it again; a file whose diff isn't read then, or
    // that has none, moved whole.
    private static void move(Editor editor, boolean read) {
        final VcsStatusBuffer status = VcsStatusMode.owner(editor);
        if (status == null) {
            moveInFile(editor);
            return;
        }
        final boolean picked = StatusActions.takesPicks(status, editor);
        final List<Target> targets = StatusActions.targets(status, editor);
        if (targets.isEmpty()) {
            editor.status("Nothing here to move");
            return;
        }
        if (targets.stream().anyMatch(t -> !StatusRenderer.UNSTAGED.equals(t.section()))) {
            editor.status("Changelists sort the changes not staged: unstage it first");
            return;
        }
        final VcsRepository r = targets.get(0).repository();
        if (targets.stream().anyMatch(t -> t.repository() != r)) {
            editor.status("A move takes one repository's changes");
            return;
        }
        final RepositoryLists lists = lists(editor, r);
        if (lists == null)
            return;
        final Set<String> used = new HashSet<>();
        if (picked)
            for (Set<String> keys : status.picks().used(targets).values())
                used.addAll(keys);
        final List<Taken> taken = taken(targets);
        // A file moves by its changes: their diffs read first.
        final Set<String> unreadKeys = new HashSet<>();
        for (Taken t : taken)
            if (t.file().diff() == null)
                unreadKeys.add(StatusRenderer.fileKey(r, StatusRenderer.UNSTAGED, t.file().path()));
        if (read && !unreadKeys.isEmpty()) {
            editor.status("Reading the diffs to move…");
            // Then, if editor still shows it, its caret where it was.
            final org.armedbear.j.Buffer shown = editor.getBuffer();
            final String at = editor.getDotLine() instanceof StatusLine s ? s.key() : null;
            status.readDiffsThen(unreadKeys, () -> {
                if (editor.getBuffer() == shown && editor.getDotLine() instanceof StatusLine s && s.key().equals(at))
                    move(editor, false);
                else
                    editor.status("Moved on while the diffs were read: move again");
            });
            return;
        }
        final Changelists c = lists.lists();
        final Map<String, List<Placed>> placed = placed(c, taken);
        // A list a move to changes nothing, the move's from.
        final Map<String, String> unavailable = new HashMap<>();
        for (Changelist l : c.lists())
            if (moved(c, taken, placed, l.name()).equals(c))
                unavailable.put(l.name(), HERE);
        final List<String> names = c.lists().stream().map(Changelist::name).toList();
        BranchCommands.choose.choose(editor, MOVE, "vcsChangelistMove", names, unavailable, name -> {
            final String to = name.strip();
            if (rejected(editor, to, unavailable))
                return;
            if (!moveTo(r, lists, taken, to)) {
                editor.status(NOT_KEPT);
                return;
            }
            if (picked)
                status.unpick(used);
            editor.status("Moved " + StatusActions.names(targets) + " to " + to);
        });
    }

    // taken to the list to, made if new, of r's lists: shown, and their
    // open files placed again; false if the lists couldn't be kept.
    private static boolean moveTo(VcsRepository r, RepositoryLists lists, List<Taken> taken, String to) {
        if (!lists.update(cc -> moved(cc.withList(to), taken, null, to)))
            return false;
        final Set<String> paths = new HashSet<>();
        for (Taken t : taken)
            paths.add(t.file().path());
        shown(r);
        replaced(r, paths);
        return true;
    }

    /**
     * Moves r's unstaged file at path in status, or with only, its lines
     * there by hunk id, to the list to, as a file dragged onto a list in
     * the sidebar's Changes tab: its diff read first if it isn't; what is
     * said to said.
     */
    static void moveFile(
            RepositoryStatus status,
            VcsRepository r,
            String path,
            Map<String, BitSet> only,
            String to,
            Consumer<String> said) {
        moveFile(status, r, path, only, to, said, true);
    }

    private static void moveFile(
            RepositoryStatus status,
            VcsRepository r,
            String path,
            Map<String, BitSet> only,
            String to,
            Consumer<String> said,
            boolean read) {
        final RepositoryLists lists = RepositoryLists.shown(r);
        final FileItem f = status.unstaged(r, path);
        if (lists == null || f == null) {
            said.accept("Nothing here to move");
            return;
        }
        if (read && f.diff() == null && f.hasDiff()) {
            said.accept("Reading the diff to move…");
            status.readDiffsThen(
                Set.of(StatusRenderer.fileKey(r, StatusRenderer.UNSTAGED, path)),
                () -> moveFile(status, r, path, only, to, said, false));
            return;
        }
        // Its lines in a list, which an unread diff can't tell.
        if (only != null && f.diff() == null) {
            said.accept("Couldn't read the diff of " + path + ": move again");
            return;
        }
        final Map<Hunk, BitSet> lines = new IdentityHashMap<>();
        if (only != null) {
            final Map<String, Hunk> ids = status.picks().ids(f.diff());
            for (Map.Entry<String, BitSet> e : only.entrySet())
                if (ids.containsKey(e.getKey()))
                    lines.put(ids.get(e.getKey()), e.getValue());
        }
        final Taken t = new Taken(f, only == null, Set.of(), lines);
        // Its lines there gone since, as after a read, or all in to already.
        if (moved(lists.lists().withList(to), List.of(t), null, to).equals(lists.lists().withList(to))) {
            said.accept("Nothing of " + path + " to move to " + to);
            return;
        }
        said.accept(moveTo(r, lists, List.of(t), to) ? "Moved " + path + " to " + to : NOT_KEPT);
    }

    /**
     * In a file's own buffer: moves the change at the caret, or the lines
     * of the selection, to a changelist asked for, made if new; kept as the
     * file is saved, or at once if it is.
     */
    private static void moveInFile(Editor editor) {
        final SignsUpdater u = editor.getBuffer().getSignsUpdater();
        final ChangeTracker tracker = u == null ? null : u.tracker();
        final RepositoryLists lists = tracker == null ? null : lists(editor, u.getRepository());
        if (lists == null) {
            if (tracker == null)
                editor.status("No changelists here");
            return;
        }
        final Set<Line> lines = ChangelistMenu.linesAt(editor, tracker);
        if (lines.isEmpty()) {
            editor.status("No change here to move");
            return;
        }
        final Map<String, String> unavailable = new HashMap<>();
        for (Map.Entry<String, String> e : ChangelistMenu.choices(u, lines).entrySet())
            if (e.getValue() != null)
                unavailable.put(e.getKey(), e.getValue());
        final List<String> names = lists.lists().lists().stream().map(Changelist::name).toList();
        BranchCommands.choose.choose(editor, MOVE, "vcsChangelistMove", names, unavailable, name -> {
            final String to = name.strip();
            if (!rejected(editor, to, unavailable))
                editor.status(ChangelistMenu.move(editor, u, lines, to));
        });
    }

    /**
     * vcsChangelistPopup: the changelists' commands, in the status or the
     * diff beside it, taking what is selected or picked there; from a file,
     * in a status of its repository.
     */
    public static void vcsChangelistPopup(Editor editor) {
        final Editor at = VcsStatusMode.owner(editor) != null ? editor : CommitCommands.statusWindow(editor);
        if (at == null)
            return;
        TransientPopup.open(at, ChangelistCommands::popup);
    }

    static Transient popup() {
        return new Transient("vcs.changelists",
                "Changelists",
                "git.html#changelists",
                List.of(
                    Group.of(
                        "Changelist",
                        Command.of("m", "vcsChangelistMove", "move to…"),
                        Command.of("a", "vcsChangelistActive", "make active"),
                        Command.of("n", "vcsChangelistNew", "new"),
                        Command.of("r", "vcsChangelistRename", "rename"),
                        Command.of("d", "vcsChangelistDelete", "delete"))));
    }

    // Whether to can't be moved to, said: a name refused, or unavailable.
    private static boolean rejected(Editor editor, String to, Map<String, String> unavailable) {
        if (to.isEmpty())
            return true;
        if (refused(to) != null)
            editor.status(refused(to));
        else if (unavailable.containsKey(to))
            editor.status(to + ": " + unavailable.get(to));
        else
            return false;
        return true;
    }

    // What a command does with a list: what is said.
    private interface OnList {
        String run(VcsRepository r, RepositoryLists lists, String name);
    }

    // does run on the list whose heading the caret is on, else on one
    // asked for, in the repository at the caret, those unavailable dimmed
    // with why; none saying so if all are.
    private static void onList(
            Editor editor,
            BranchCommands.Question q,
            String history,
            Function<Changelists, Map<String, String>> unavailableOf,
            Function<Changelists, String> none,
            OnList does) {
        final VcsRepository r = repository(editor);
        final RepositoryLists lists = lists(editor, r);
        if (lists == null)
            return;
        final Changelists c = lists.lists();
        final Map<String, String> unavailable = unavailableOf.apply(c);
        final String at = listAt(editor);
        if (at != null) {
            editor.status(unavailable.containsKey(at) ? at + ": " + unavailable.get(at) : does.run(r, lists, at));
            return;
        }
        final List<String> names = c.lists().stream().map(Changelist::name).toList();
        if (names.stream().allMatch(unavailable::containsKey)) {
            editor.status(none.apply(c));
            return;
        }
        BranchCommands.choose
                .choose(editor, q, history, names, unavailable, name -> editor.status(does.run(r, lists, name)));
    }

    // A file a move takes: whole, or hunks of it whole, or lines of hunks.
    private record Taken(FileItem file, boolean whole, Set<Hunk> hunks, Map<Hunk, BitSet> lines) {}

    // What targets take of each file, by file, in order.
    private static List<Taken> taken(List<Target> targets) {
        final Map<String, Taken> out = new LinkedHashMap<>();
        for (Target t : targets)
            for (FileItem f : files(t)) {
                final Taken k = out.computeIfAbsent(
                    f.path(),
                    p -> new Taken(f,
                            false,
                            Collections.newSetFromMap(new IdentityHashMap<>()),
                            new IdentityHashMap<>()));
                if (t instanceof Files)
                    out.put(f.path(), new Taken(f, true, k.hunks(), k.lines()));
                else if (t instanceof Part p && p.lines() == null)
                    k.hunks().add(p.hunk());
                else if (t instanceof Part p)
                    k.lines().computeIfAbsent(p.hunk(), h -> new BitSet()).or(p.lines());
            }
        return List.copyOf(out.values());
    }

    // Whether f's changes aren't read: no diff, or one with none, as a binary file's.
    private static boolean unread(FileItem f) {
        return f.diff() == null || f.diff().hunks().isEmpty();
    }

    // Each taken file's changes placed in c's lists, by path, those read.
    private static Map<String, List<Placed>> placed(Changelists c, List<Taken> taken) {
        final Map<String, List<Placed>> out = new HashMap<>();
        for (Taken t : taken)
            if (!unread(t.file()))
                out.put(
                    t.file().path(),
                    Anchors.place(c, t.file().path(), t.file().change().origPath(), t.file().diff()));
        return out;
    }

    /**
     * c with taken's changes in the list to: a file whole, each of its
     * changes; a hunk, its changes; lines, the changes they are of, or of
     * those that add lines, those lines alone, as parts; a file unread,
     * whole. placed, c's placing of them, or null to place them.
     */
    private static Changelists moved(Changelists c, List<Taken> taken, Map<String, List<Placed>> placed, String to) {
        final Map<String, List<Placed>> in = placed != null ? placed : placed(c, taken);
        Changelists out = c;
        for (Taken t : taken) {
            final FileItem f = t.file();
            final String orig = f.change().origPath();
            if (unread(f)) {
                // Where it is shown already, whole or not kept, unchanged.
                final Assignment a = c.assignment(f.path());
                if ((a == null || a.whole() != null) && orig == null && ListedChanges.listOf(c, f).equals(to))
                    continue;
                if (orig != null)
                    out = out.withFile(orig, null);
                out = out.withFile(f.path(), Assignment.whole(to));
                continue;
            }
            final List<Placed> before = in.get(f.path());
            final List<Placed> after = new ArrayList<>();
            for (Placed p : before)
                after.add(
                    t.whole() || t.hunks().contains(p.hunk()) ? new Placed(p.hunk(), p.run(), to, List.of())
                            : t.lines().containsKey(p.hunk()) ? movedTo(p, t.lines().get(p.hunk()), to)
                            : p);
            if (after.equals(before) && orig == null)
                continue;
            // A renamed file's kept under its path now alone.
            if (orig != null)
                out = out.withFile(orig, null);
            out = out.withFile(f.path(), Anchors.assignment(after));
        }
        return out;
    }

    // p, with those of its lines in bits, indexes into its hunk's, in to.
    private static Placed movedTo(Placed p, BitSet bits, String to) {
        final Hunk.Run r = p.run();
        if (bits.get(r.start(), r.end()).isEmpty())
            return p;
        final String[] lists = new String[r.addedCount()];
        boolean any = false;
        for (int k = 0; k < lists.length; k++) {
            final boolean taken = bits.get(r.added() + k) || k < r.paired() && bits.get(r.start() + k);
            lists[k] = taken ? to : Anchors.listOf(p, k);
            any |= taken;
        }
        // Lines only removed, or only removed lines with none added for
        // them taken, which have no row of their own: all of it.
        if (!any)
            return new Placed(p.hunk(), r, to, List.of());
        return Anchors.withLists(p.hunk(), r, lists);
    }

    // The files t is of.
    private static List<FileItem> files(Target t) {
        return t instanceof Files f ? f.files() : List.of(((Part) t).file());
    }

    // Why name can't name a changelist, or null if it can.
    static String refused(String name) {
        return Changelists.badName(name);
    }

    // name made in r's lists: what is said.
    private static String make(VcsRepository r, RepositoryLists lists, String name) {
        if (lists.lists().refusedName(name) != null)
            return lists.lists().refusedName(name);
        if (!lists.update(c -> c.withList(name)))
            return NOT_KEPT;
        shown(r);
        return "Made changelist " + name;
    }

    // name made active in r's lists: what is said.
    private static String activate(VcsRepository r, RepositoryLists lists, String name) {
        if (!lists.update(c -> c.list(name) == null ? c : c.withActive(name)))
            return NOT_KEPT;
        if (!lists.lists().active().equals(name))
            return "There is no changelist " + name;
        shown(r);
        return name + " is the active changelist";
    }

    // r's lists, if changelists are on and its version control keeps j's; else null, said.
    private static RepositoryLists lists(Editor editor, VcsRepository r) {
        if (!Editor.preferences().getBooleanProperty(Property.VCS_CHANGELISTS)) {
            editor.status("Changelists are off: vcsChangelists is false");
            return null;
        }
        final RepositoryLists lists = r == null ? null : RepositoryLists.of(r);
        if (lists == null)
            editor.status("No changelists here");
        return lists;
    }

    // r's statuses show its lists again, and its files' signs mark theirs.
    private static void shown(VcsRepository r) {
        SignsUpdater.listsChanged(r);
    }

    // r's list from renamed to, or deleted, to the default: shown again, and
    // its open files' changes in to, edits unsaved and all.
    private static void relisted(VcsRepository r, String from, String to) {
        for (Buffer b : Editor.getBufferList()) {
            final SignsUpdater u = b.getSignsUpdater();
            if (u != null && u.getRepository() == r && u.tracker() != null)
                u.tracker().relist(from, to);
        }
        shown(r);
    }

    // r's files at paths, open, placed again from the lists as kept, as a
    // move in the status changed them.
    private static void replaced(VcsRepository r, Set<String> paths) {
        for (Buffer b : Editor.getBufferList()) {
            final SignsUpdater u = b.getSignsUpdater();
            if (u != null && u.getRepository() == r && paths.contains(u.getPath()) && u.tracker() != null) {
                u.tracker().reload();
                u.changed();
            }
        }
    }

    // The changelist whose heading the caret is on, or null.
    private static String listAt(Editor editor) {
        if (!(editor.getDotLine() instanceof StatusLine s) || s.kind() != Kind.LIST)
            return null;
        final RepositoryLists lists = RepositoryLists.of(s.repository());
        if (lists == null)
            return null;
        for (Changelist l : lists.lists().lists())
            if (s.key().equals(StatusRenderer.listKey(s.repository(), l.name())))
                return l.name();
        return null;
    }

    // The repository of the thing at the caret in a status, or of the file editor shows.
    private static VcsRepository repository(Editor editor) {
        if (VcsStatusMode.owner(editor) != null)
            return VcsStatusMode.repositoryAt(editor);
        final SignsUpdater u = editor.getBuffer().getSignsUpdater();
        return u == null ? null : u.getRepository();
    }
}
