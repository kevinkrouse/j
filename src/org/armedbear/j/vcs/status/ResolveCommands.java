/*
 * ResolveCommands.java
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

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.armedbear.j.Directories;
import org.armedbear.j.Editor;
import org.armedbear.j.Log;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.TransientPopup;
import org.armedbear.j.vcs.FileChange.Conflict;
import org.armedbear.j.vcs.InProgress;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.status.StatusActions.Files;
import org.armedbear.j.vcs.status.StatusActions.Op;
import org.armedbear.j.vcs.status.StatusActions.Target;
import org.armedbear.j.vcs.status.StatusData.FileItem;

/**
 * Taking a side of a conflict from a status buffer: the resolve popup
 * (x on an unmerged file) and its commands. Ours, theirs, keep and delete
 * take that version and stage the file, one step; "conflict again" puts the
 * markers back and leaves it unmerged. What ours, theirs and conflict again
 * write over, and delete deletes, is copied aside first, hand edits and
 * all: to the desktop's trash, or where it has none to j's state
 * directory's resolved folder.
 */
public final class ResolveCommands {
    private ResolveCommands() {}

    static final String ID = "vcs.resolve";

    /** A way to resolve a conflict, with the kinds of conflict it fits. */
    enum Take {
        OURS("o", "vcsResolveOurs", "take ours (HEAD)", Conflict.BOTH_MODIFIED, Conflict.BOTH_ADDED),
        THEIRS("t", "vcsResolveTheirs", "take theirs", Conflict.BOTH_MODIFIED, Conflict.BOTH_ADDED),
        CONFLICT("c", "vcsResolveRestore", "conflict again", Conflict.BOTH_MODIFIED, Conflict.BOTH_ADDED),
        KEEP("k", "vcsResolveKeep", "keep it", Conflict.DELETED_BY_THEM, Conflict.DELETED_BY_US, Conflict.ADDED_BY_US,
                Conflict.ADDED_BY_THEM),
        DELETE("d", "vcsResolveDelete", "delete it", Conflict.DELETED_BY_THEM, Conflict.DELETED_BY_US,
                Conflict.ADDED_BY_US, Conflict.ADDED_BY_THEM, Conflict.BOTH_DELETED);

        final String key;
        final String command;
        final String description;
        private final Set<Conflict> fits;

        Take(String key, String command, String description, Conflict first, Conflict... rest) {
            this.key = key;
            this.command = command;
            this.description = description;
            this.fits = EnumSet.of(first, rest);
        }

        /** Whether this fits each of targets' files. */
        boolean fits(List<Target> targets) {
            return files(targets).stream().allMatch(f -> fits.contains(f.change().conflict()));
        }
    }

    /** The choices that fit each of the unmerged targets' files. */
    static List<Take> offered(List<Target> targets) {
        return Stream.of(Take.values()).filter(t -> t.fits(targets)).toList();
    }

    /**
     * The resolve popup for the unmerged targets, of the choices that fit
     * them all; null if none does. With several repositories in the status,
     * each file says which it is in.
     */
    static Transient popup(List<Target> targets, boolean several) {
        // In a rebase, ours is what it rebases onto and theirs your commit.
        final VcsRepository r = targets.isEmpty() ? null : targets.get(0).repository();
        final boolean rebase = r != null && r.inProgress() == InProgress.REBASE;
        final List<Transient.Item> offers = new ArrayList<>();
        for (Take t : offered(targets))
            offers.add(
                Command.of(
                    t.key,
                    t.command,
                    !rebase ? t.description
                            : t == Take.OURS ? "take ours (the branch rebased onto)"
                            : t == Take.THEIRS ? "take theirs (your commit)"
                            : t.description));
        if (offers.isEmpty())
            return null;
        final List<String> names = new ArrayList<>();
        for (Target t : targets)
            if (t instanceof Files f)
                for (FileItem i : f.files())
                    names.add(several ? i.path() + " in " + f.repository().name() : i.path());
        return new Transient(ID,
                "Resolve " + StatusActions.list(names),
                "git.html#conflicts",
                List.of(new Group("Actions", offers)));
    }

    /** Opens the resolve popup for the unmerged targets, which StatusActions has let through. */
    static void open(Editor editor, List<Target> targets) {
        final boolean several = EachRepository.several(editor);
        TransientPopup.open(editor, () -> popup(targets, several));
    }

    public static void vcsResolveOurs(Editor editor) {
        resolveAt(editor, Take.OURS);
    }

    public static void vcsResolveTheirs(Editor editor) {
        resolveAt(editor, Take.THEIRS);
    }

    public static void vcsResolveRestore(Editor editor) {
        resolveAt(editor, Take.CONFLICT);
    }

    public static void vcsResolveKeep(Editor editor) {
        resolveAt(editor, Take.KEEP);
    }

    public static void vcsResolveDelete(Editor editor) {
        resolveAt(editor, Take.DELETE);
    }

    // Runs take on the unmerged files at the caret or selected, with x's
    // refusals, if it fits them all; of the picks, the unmerged files'
    // alone, the rest left picked.
    private static void resolveAt(Editor editor, Take take) {
        final VcsStatusBuffer buffer = VcsStatusMode.owner(editor);
        if (buffer == null || editor.getDot() == null)
            return;
        final boolean picked = StatusActions.takesPicks(buffer, editor);
        final List<Target> all = StatusActions.targets(buffer, editor, picked);
        final List<Target> targets = picked ? all.stream().filter(StatusActions::isUnmerged).toList() : all;
        if (targets.stream().noneMatch(StatusActions::isUnmerged)) {
            editor.status(picked ? "No conflict is picked" : "Nothing to resolve here");
            return;
        }
        final String why = StatusActions.refusal(editor, buffer, Op.DISCARD, targets, picked);
        if (why != null) {
            editor.status(why);
            return;
        }
        if (!take.fits(targets)) {
            editor.status("That choice doesn't fit these conflicts");
            return;
        }
        final Map<Target, Set<String>> used = picked ? buffer.picks().used(targets) : Map.of();
        StatusActions.start(editor, buffer, "Resolving", targets, used, did -> resolve(targets, take, did));
    }

    /** As resolve, telling did of each target resolved. */
    static String resolve(List<Target> targets, Take take) {
        return resolve(targets, take, t -> {});
    }

    /**
     * Resolves the unmerged targets each take, off the event thread,
     * telling did of each done: what went wrong, or null. A repository
     * that fails doesn't stop the others; with several, each failure names
     * its repository.
     */
    static String resolve(List<Target> targets, Take take, Consumer<Target> did) {
        final List<String> failures = new ArrayList<>();
        final long repositories = targets.stream().map(Target::repository).distinct().count();
        for (Target t : targets) {
            if (!(t instanceof Files f))
                continue;
            final String failed = resolve(f, take);
            if (failed != null)
                failures.add(repositories > 1 ? f.repository().name() + ": " + failed : failed);
            else
                did.accept(t);
        }
        return failures.isEmpty() ? null : String.join("; ", failures);
    }

    private static String resolve(Files f, Take take) {
        final VcsRepository r = f.repository();
        final VcsBackend b = r.backend();
        final List<String> paths = f.files().stream().map(FileItem::path).toList();
        // What git writes over, or is deleted, is copied aside first, all
        // of it before any is touched.
        final List<String> present = present(r, paths);
        final String kept = take == Take.KEEP ? null : keepAside(r, present);
        if (kept != null)
            return kept;
        final String failed = switch (take) {
            case OURS, THEIRS -> StatusActions.failure(b, b.checkoutSide(r, paths, take == Take.OURS));
            case CONFLICT -> StatusActions.failure(b, b.restoreConflict(r, paths));
            case DELETE -> delete(r, present);
            case KEEP -> null;
        };
        if (failed != null || take == Take.CONFLICT)
            return failed;
        return StatusActions.failure(b, b.stage(r, paths));
    }

    /** Where keepAside copies files, by the time it did, under j's state directory. */
    static Path keptDirectory() {
        return Path.of(Directories.getStateDirectory().canonicalPath(), "resolved");
    }

    // Those of paths there are in r's working tree, a link as itself, dangling or not.
    private static List<String> present(VcsRepository r, List<String> paths) {
        return paths.stream()
                .filter(p -> java.nio.file.Files.exists(r.root().resolve(p), LinkOption.NOFOLLOW_LINKS))
                .toList();
    }

    // Copies paths, there, aside, as they are before a choice writes over
    // them, a link as itself: to the desktop's trash where it has one, else
    // to keptDirectory's folder of now. What went wrong, or null; nothing
    // is touched if a copy fails.
    private static String keepAside(VcsRepository r, List<String> paths) {
        if (paths.isEmpty())
            return null;
        final Path dir =
                keptDirectory().resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")))
                        .resolve(r.name());
        final boolean trash = StatusActions.canTrash();
        try {
            for (String path : paths) {
                final Path copy = dir.resolve(path);
                java.nio.file.Files.createDirectories(copy.getParent());
                java.nio.file.Files.copy(
                    r.root().resolve(path),
                    copy,
                    StandardCopyOption.COPY_ATTRIBUTES,
                    LinkOption.NOFOLLOW_LINKS);
                if (trash && !Desktop.getDesktop().moveToTrash(copy.toFile()))
                    return "Couldn't move a copy of " + path + " to the trash";
            }
        }
        catch (IOException | RuntimeException e) {
            Log.debug(e);
            return "Couldn't copy aside before resolving: " + e.getMessage();
        }
        // In the trash, the copies leave their folders empty.
        if (trash)
            try (Stream<Path> all = java.nio.file.Files.walk(dir.getParent())) {
                for (Path p : all.sorted(Comparator.reverseOrder()).toList())
                    java.nio.file.Files.deleteIfExists(p);
            }
            catch (IOException e) {
                Log.debug(e);
            }
        return null;
    }

    // Deletes paths, kept aside already; what went wrong, or null.
    private static String delete(VcsRepository r, List<String> paths) {
        for (String path : paths)
            try {
                java.nio.file.Files.deleteIfExists(r.root().resolve(path));
            }
            catch (IOException e) {
                return "Couldn't delete " + path + ": " + e.getMessage();
            }
        return null;
    }

    /**
     * Puts paths' conflicts back in the index as they were before they
     * were staged, the files as they are, while the merge or rebase they
     * came from goes on; what went wrong, or null. Off the event thread.
     */
    public static String unresolve(VcsRepository r, List<String> paths) {
        r.snapshotNow();
        if (r.inProgress() == null)
            return "No merge, rebase or stash pop in progress to put a conflict back in";
        final VcsBackend b = r.backend();
        final Set<String> back = b.resolvedConflicts(r, paths);
        final List<String> missing = paths.stream().filter(p -> !back.contains(p)).toList();
        if (!missing.isEmpty())
            return "No conflict of " + StatusActions.list(missing) + " to put back";
        return StatusActions.failure(b, b.unresolve(r, paths));
    }

    // The files of targets, unmerged or not.
    private static List<FileItem> files(List<Target> targets) {
        final List<FileItem> files = new ArrayList<>();
        for (Target t : targets)
            if (t instanceof Files f)
                files.addAll(f.files());
        return files;
    }
}
