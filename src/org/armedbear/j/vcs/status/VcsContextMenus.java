/*
 * VcsContextMenus.java
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

import java.util.List;
import java.util.function.Function;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Property;
import org.armedbear.j.util.PickState;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.Changelists;
import org.armedbear.j.vcs.status.StatusActions.Op;
import org.armedbear.j.vcs.status.StatusActions.Target;
import org.armedbear.j.vcs.status.StatusData.FileItem;

/**
 * The right-click menus of the status buffer and the Changes tab: the
 * version control things to do with what is under the mouse, each a j
 * command with its key shown, dimmed with why where it can't apply. Both
 * act on {@link StatusActions.Target}s, so a file's items are worked out
 * once; the surface says what each command does there — the status runs
 * it by name on the caret it moved, the Changes tab on its own nodes.
 */
final class VcsContextMenus {
    private VcsContextMenus() {}

    /**
     * The file items, for a file or the files selected: commit, discard,
     * stage, unstage, move, show diff, history, blame, open, pick, copy
     * path; and for an untracked file, ignore and delete; for an
     * unmerged one, the resolves. action says what command does here.
     *
     * <p>commitTargets, what Commit file… takes: the thing under the mouse
     * or the selection, never the picks, so the item commits what its
     * label says even with picks pending. The other items take targets,
     * which in the status are what the commands they name take.
     */
    static void fileItems(
            VcsMenu menu,
            Editor editor,
            RepositoryStatus model,
            List<Target> targets,
            List<Target> commitTargets,
            StatusLine line,
            String diffLabel,
            String diffCommand,
            String diffKey,
            Function<String, Runnable> action) {
        final FileItem file = line.file();
        final VcsRepository r = line.repository();
        final boolean untracked = file != null && file.change().isUntracked();
        final boolean unmerged = StatusData.UNMERGED.equals(line.section());
        add(menu, editor, commitLabel(commitTargets), "vcsCommitFiles", commitWhy(model, commitTargets), action);
        add(
            menu,
            editor,
            untracked ? "Delete…" : "Discard…",
            "vcsDiscard",
            StatusActions.refusal(editor, model, Op.DISCARD, targets, false),
            action);
        if (untracked)
            // git add: the file joins the repository, staged for commit.
            add(
                menu,
                editor,
                "Add to VCS",
                "vcsStage",
                StatusActions.refusal(editor, model, Op.STAGE, targets, false),
                action);
        else {
            add(
                menu,
                editor,
                "Stage",
                "vcsStage",
                StatusActions.refusal(editor, model, Op.STAGE, targets, false),
                action);
            add(
                menu,
                editor,
                "Unstage",
                "vcsUnstage",
                StatusActions.refusal(editor, model, Op.UNSTAGE, targets, false),
                action);
        }
        add(menu, editor, "Move to changelist…", "vcsChangelistMove", moveWhy(model, targets), action);
        menu.add(
            diffLabel,
            diffCommand,
            diffKey,
            file != null,
            file == null ? "No file here" : null,
            e -> action.apply(diffCommand).run());
        add(menu, editor, "Show history", "vcsLogFileAt", file == null ? "No file here" : null, action);
        add(menu, editor, "Blame", "vcsBlame", blameWhy(r, file), action);
        add(menu, editor, "Open", "vcsStatusVisit", file == null ? "No file here" : null, action);
        add(
            menu,
            editor,
            picked(model, line) ? "Unpick" : "Pick",
            "vcsStatusPick",
            StatusPicks.pickable(line) ? null : "Nothing to pick here",
            action);
        add(menu, editor, "Copy path", "vcsCopyPath", file == null ? "No file here" : null, action);
        if (untracked)
            add(
                menu,
                editor,
                "Add to .gitignore",
                "vcsIgnore",
                StatusActions.canIgnore(r) ? null : r.backend().name() + " can't ignore a file here",
                action);
        if (unmerged) {
            add(
                menu,
                editor,
                "Resolve as ours",
                "vcsResolveOurs",
                resolveWhy(targets, ResolveCommands.Take.OURS),
                action);
            add(
                menu,
                editor,
                "Resolve as theirs",
                "vcsResolveTheirs",
                resolveWhy(targets, ResolveCommands.Take.THEIRS),
                action);
            add(
                menu,
                editor,
                "Mark resolved",
                "vcsResolveKeep",
                resolveWhy(targets, ResolveCommands.Take.KEEP),
                action);
        }
    }

    /**
     * The changelist items: commit it alone, make it active, new, rename,
     * delete. Default can't be renamed or deleted; the active list needs
     * no making active.
     */
    static void changelistItems(
            VcsMenu menu,
            Editor editor,
            String list,
            boolean active,
            Function<String, Runnable> action) {
        final boolean isDefault = Changelists.DEFAULT.equals(list);
        add(menu, editor, "Commit changelist…", "vcsCommitList", null, action);
        add(menu, editor, "Make active", "vcsChangelistActive", active ? "Already active" : null, action);
        add(menu, editor, "New changelist…", "vcsChangelistNew", null, action);
        add(menu, editor, "Rename…", "vcsChangelistRename", isDefault ? "Default can't be renamed" : null, action);
        add(menu, editor, "Delete…", "vcsChangelistDelete", isDefault ? "Default stays" : null, action);
    }

    // One item, dimmed with why where it can't apply.
    private static void add(
            VcsMenu menu,
            Editor editor,
            String label,
            String command,
            String why,
            Function<String, Runnable> action) {
        menu.add(label, command, why == null, why, e -> action.apply(command).run());
    }

    // Whether the file's changes are picked, so its item says Unpick.
    private static boolean picked(RepositoryStatus model, StatusLine line) {
        return StatusPicks.pickable(line) && model.picks().state(line) != PickState.NONE;
    }

    // "Commit file…", "Commit files…", "Commit alone…" for a hunk or lines.
    private static String commitLabel(List<Target> targets) {
        if (targets.stream().anyMatch(t -> t instanceof StatusActions.Part))
            return "Commit alone…";
        final long files = targets.stream().map(t -> ((StatusActions.Files) t).files().size()).reduce(0, Integer::sum);
        return files == 1 ? "Commit file…" : "Commit files…";
    }

    /** Why the targets can't be committed alone, or null. */
    static String commitWhy(RepositoryStatus model, List<Target> targets) {
        if (targets.isEmpty())
            return "Nothing to commit here";
        try {
            PartialCommit.ofTargets(model, targets).plan();
            return null;
        }
        catch (PartialCommit.Refused e) {
            return e.getMessage();
        }
    }

    /** Why the targets can't be moved to a changelist, or null. */
    static String moveWhy(RepositoryStatus model, List<Target> targets) {
        if (targets.isEmpty())
            return "Nothing here to move";
        if (targets.stream().anyMatch(t -> !StatusRenderer.UNSTAGED.equals(t.section())))
            return "Changelists sort the changes not staged: unstage it first";
        if (targets.stream().map(Target::repository).distinct().count() > 1)
            return "A move takes one repository's changes";
        if (!Editor.preferences().getBooleanProperty(Property.VCS_CHANGELISTS))
            return "Changelists are off: vcsChangelists is false";
        return null;
    }

    // Why take doesn't fit the unmerged targets, or null.
    private static String resolveWhy(List<Target> targets, ResolveCommands.Take take) {
        if (targets.isEmpty() || targets.stream().anyMatch(t -> !StatusActions.isUnmerged(t)))
            return "Nothing to resolve here";
        return take.fits(targets) ? null : "That choice doesn't fit these conflicts";
    }

    private static String blameWhy(VcsRepository r, FileItem file) {
        if (file == null)
            return "No file here";
        if (file.change().isUntracked())
            return "Not in a commit yet: nothing to blame";
        if (workingFile(r, file) == null)
            return file.path() + " is not in the working tree";
        return null;
    }

    // The working tree's file, or null.
    static File workingFile(VcsRepository r, FileItem file) {
        if (r == null || file == null)
            return null;
        final File f = File.getInstance(r.root().resolve(file.path()).toString());
        return f != null && f.isFile() ? f : null;
    }

    /** path, relative to its repository, on the clipboard and in the kill ring. */
    static void copyPath(Editor editor, String path) {
        if (path == null) {
            editor.status("No path to copy");
            return;
        }
        Editor.getKillRing().appendNew(path);
        Editor.getKillRing().copyLastKillToSystemClipboard();
        editor.status("Copied " + path);
    }
}
