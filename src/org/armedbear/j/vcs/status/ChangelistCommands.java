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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.LocationBar;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.Anchors;
import org.armedbear.j.vcs.changelists.Anchors.Placed;
import org.armedbear.j.vcs.changelists.Changelists;
import org.armedbear.j.vcs.changelists.Changelists.Assignment;
import org.armedbear.j.vcs.changelists.Changelists.Changelist;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.signs.SignsUpdater;
import org.armedbear.j.vcs.status.StatusActions.Files;
import org.armedbear.j.vcs.status.StatusActions.Part;
import org.armedbear.j.vcs.status.StatusActions.Target;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/**
 * Changelists from the status: making one, making one active, and moving
 * changes of the work tree to one: the selection's, else what is picked,
 * else the thing at the caret, a list's heading, a file, a hunk, or its
 * lines; a file whose diff isn't read moved whole.
 */
public final class ChangelistCommands {
    private ChangelistCommands() {}

    /** Asks for a name; replaced in tests. */
    static BranchCommands.Ask ask = BranchCommands.ask;

    /** Asks which of names, or with typed, a new one; replaced in tests. */
    static BranchCommands.Choose choose = BranchCommands.choose;

    private static final BranchCommands.Question MOVE =
            new BranchCommands.Question(LocationBar.PROMPT_CHANGELIST, "select a changelist or enter a new one", true);
    private static final BranchCommands.Question ACTIVE =
            new BranchCommands.Question(LocationBar.PROMPT_CHANGELIST, "select the changelist to make active", false);

    /** vcsChangelistNew: makes a changelist, of the repository at the caret, asking its name. */
    public static void vcsChangelistNew(Editor editor) {
        final VcsRepository r = repository(editor);
        final RepositoryLists lists = r == null ? null : RepositoryLists.of(r);
        if (lists == null) {
            editor.status("No changelists here");
            return;
        }
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
        final VcsRepository r = repository(editor);
        final RepositoryLists lists = r == null ? null : RepositoryLists.of(r);
        if (lists == null) {
            editor.status("No changelists here");
            return;
        }
        final String at = listAt(editor);
        if (at != null) {
            editor.status(activate(r, lists, at));
            return;
        }
        final List<String> names = lists.lists().lists().stream().map(Changelist::name).toList();
        choose.choose(editor, ACTIVE, "vcsChangelistActive", names, name -> editor.status(activate(r, lists, name)));
    }

    /**
     * vcsChangelistMove: moves the work tree's changes the selection takes,
     * else those picked, else the thing at the caret, to a changelist asked
     * for, made if it is new.
     */
    public static void vcsChangelistMove(Editor editor) {
        final VcsStatusBuffer status = VcsStatusMode.owner(editor);
        if (status == null) {
            editor.status("Not in a git status");
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
        final RepositoryLists lists = RepositoryLists.of(r);
        if (lists == null) {
            editor.status("No changelists here");
            return;
        }
        final Set<String> used = new HashSet<>();
        if (picked)
            for (Set<String> keys : status.picks().used(targets).values())
                used.addAll(keys);
        final List<String> names = lists.lists().lists().stream().map(Changelist::name).toList();
        choose.choose(editor, MOVE, "vcsChangelistMove", names, name -> {
            final String to = name.strip();
            if (to.isEmpty())
                return;
            if (refused(to) != null) {
                editor.status(refused(to));
                return;
            }
            final Set<String> paths = new HashSet<>();
            for (Target t : targets)
                paths.addAll(paths(t));
            if (!lists.update(c -> moved(c.withList(to), targets, to))) {
                editor.status("Couldn't keep the changelists: see the log");
                return;
            }
            if (picked)
                status.unpick(used);
            changed(r, paths);
            editor.status("Moved " + StatusActions.names(targets) + " to " + to);
        });
    }

    /**
     * c with targets' changes in the list to: a file whole, each of its
     * changes; a hunk, its changes; lines, the changes they are of, or of
     * those that add lines, those lines alone, as parts.
     */
    static Changelists moved(Changelists c, List<Target> targets, String to) {
        // Each file's targets, by path.
        final Map<String, FileItem> files = new LinkedHashMap<>();
        final Map<String, List<Target>> byPath = new LinkedHashMap<>();
        for (Target t : targets)
            for (FileItem f : files(t)) {
                files.putIfAbsent(f.path(), f);
                byPath.computeIfAbsent(f.path(), k -> new ArrayList<>()).add(t);
            }
        Changelists out = c;
        for (Map.Entry<String, FileItem> e : files.entrySet()) {
            final FileItem f = e.getValue();
            if (f.diff() == null) {
                if (f.change().origPath() != null)
                    out = out.withFile(f.change().origPath(), null);
                out = out.withFile(f.path(), Assignment.whole(to));
                continue;
            }
            final boolean whole = byPath.get(f.path()).stream().anyMatch(t -> t instanceof Files);
            final Map<Hunk, BitSet> lines = new IdentityHashMap<>();
            final Set<Hunk> hunks = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (Target t : byPath.get(f.path()))
                if (t instanceof Part p && p.lines() == null)
                    hunks.add(p.hunk());
                else if (t instanceof Part p)
                    lines.computeIfAbsent(p.hunk(), k -> new BitSet()).or(p.lines());
            final List<Placed> placed = new ArrayList<>();
            for (Placed p : Anchors.place(out, f.path(), f.change().origPath(), f.diff()))
                placed.add(
                    whole || hunks.contains(p.hunk()) ? new Placed(p.hunk(), p.run(), to, List.of())
                            : lines.containsKey(p.hunk()) ? moved(p, lines.get(p.hunk()), to)
                            : p);
            // A renamed file's kept under its path now alone.
            if (f.change().origPath() != null)
                out = out.withFile(f.change().origPath(), null);
            out = out.withFile(f.path(), Anchors.assignment(placed, null));
        }
        return out;
    }

    // p, with those of its lines in bits, indexes into its hunk's, in to.
    private static Placed moved(Placed p, BitSet bits, String to) {
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

    private static Set<String> paths(Target t) {
        final Set<String> out = new HashSet<>();
        for (FileItem f : files(t))
            out.add(f.path());
        return out;
    }

    // Why name can't name a changelist, or null if it can.
    static String refused(String name) {
        return name.chars().anyMatch(Character::isISOControl)
                ? "A changelist's name can't have control characters"
                : null;
    }

    // name made in r's lists: what is said.
    private static String make(VcsRepository r, RepositoryLists lists, String name) {
        if (refused(name) != null)
            return refused(name);
        if (lists.lists().list(name) != null)
            return "There is a changelist " + name + " already";
        if (!lists.update(c -> c.withList(name)))
            return "Couldn't keep the changelists: see the log";
        changed(r, null);
        return "Made changelist " + name;
    }

    // name made active in r's lists, or made and active if new: what is said.
    private static String activate(VcsRepository r, RepositoryLists lists, String name) {
        if (refused(name) != null)
            return refused(name);
        if (!lists.update(c -> c.withList(name).withActive(name)))
            return "Couldn't keep the changelists: see the log";
        changed(r, null);
        return name + " is the active changelist";
    }

    /**
     * r's changelists changed: its statuses show them again, and its files'
     * buffers place their changes again, those of paths, or all for null.
     */
    static void changed(VcsRepository r, Set<String> paths) {
        for (Buffer b : Editor.getBufferList()) {
            if (b instanceof VcsStatusBuffer s && s.getRepositories().contains(r))
                s.listsChanged();
            final SignsUpdater u = b.getSignsUpdater();
            if (u != null
                    && u.getRepository() == r
                    && (paths == null || paths.contains(u.getPath()))
                    && u.tracker() != null) {
                u.tracker().reload();
                u.changed();
            }
        }
    }

    // The changelist whose heading the caret is on, or null.
    private static String listAt(Editor editor) {
        if (!(editor.getDotLine() instanceof StatusLine s) || s.kind() != Kind.LIST)
            return null;
        for (Changelist l : RepositoryLists.of(s.repository()).lists().lists())
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
