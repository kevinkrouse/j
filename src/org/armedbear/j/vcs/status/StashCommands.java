/*
 * StashCommands.java
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

import static org.armedbear.j.Constants.RESPONSE_YES;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import org.armedbear.j.ConfirmDialog;
import org.armedbear.j.Editor;
import org.armedbear.j.Region;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Field;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.Transient.Option;
import org.armedbear.j.transients.Transient.Switch;
import org.armedbear.j.transients.TransientArgs;
import org.armedbear.j.transients.TransientPopup;
import org.armedbear.j.transients.Transients;
import org.armedbear.j.vcs.Capability;
import org.armedbear.j.vcs.StashOption;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Item;
import org.armedbear.j.vcs.status.StatusData.Section;
import org.armedbear.j.vcs.status.StatusData.StashItem;

/**
 * Stashing from a status buffer: the stash popup (z, or Z with the vim
 * keys), after magit's, and its commands. Applying, popping and dropping
 * act on the stash at the caret, else the newest.
 */
public final class StashCommands {
    private StashCommands() {}

    static final String ID = "vcs.stash";

    private static final String MESSAGE = "--message=";

    /** Asks before a stash is dropped; replaced in tests. */
    static BiPredicate<Editor, String> confirm =
            (editor, text) -> ConfirmDialog.showConfirmDialog(editor, text, "Drop Stash") == RESPONSE_YES;

    /** The stash popup, applying, popping and dropping target, or dimmed for null. */
    static Transient popup(StashItem target) {
        return popup(target, null, false, null, false);
    }

    /**
     * The popup in a status of several repositories, here the caret's, or
     * null for one: -a, and where its lines stash, with -a in each; or, of
     * files selected or picked, taking, what it stashes, with no -a, or why
     * it stashes nothing if refused.
     */
    static Transient popup(StashItem target, String here, boolean each, String taking, boolean refused) {
        final String name = target == null ? null : target.name();
        final List<Transient.Item> arguments =
                new ArrayList<>(List.of(new Option("-m", MESSAGE, "message", new Field("vcsStash.message"), null)));
        if (here != null && taking == null)
            arguments.add(Switch.of("-a", EachRepository.ALL, "all repositories"));
        return new Transient(ID,
                "Stash",
                "git.html#stashing",
                List.of(
                    new Group("Arguments", arguments),
                    Group.of(
                        taking != null ? taking
                                : each ? "Stash in each repository"
                                : here != null ? "Stash in " + here
                                : "Stash",
                        stash(refused, "z", "vcsStashSave", "the changes"),
                        stash(refused, "i", "vcsStashKeepIndex", "keeping the index"),
                        stash(refused, "u", "vcsStashUntracked", "with untracked files"),
                        stash(refused, "s", "vcsStashStaged", "what is staged")),
                    Group.of(
                        here != null ? "Use, in " + here : "Use",
                        name != null
                                ? Command.of("a", "vcsStashApply", "apply " + name)
                                : Command.unavailable("a", "apply, no stash"),
                        name != null
                                ? Command.of("p", "vcsStashPop", "pop " + name)
                                : Command.unavailable("p", "pop, no stash"),
                        name != null
                                ? Command.of("k", "vcsStashDrop", "drop " + name)
                                : Command.unavailable("k", "drop, no stash"))));
    }

    // A stash command, unavailable if refused.
    private static Command stash(boolean refused, String key, String command, String description) {
        return refused ? Command.unavailable(key, description) : Command.of(key, command, description);
    }

    /** The stash popup of the status, from another buffer in a repository its status opened first. */
    public static void vcsStashPopup(Editor editor) {
        final Editor at = CommitCommands.statusWindow(editor);
        final VcsRepository r = at == null ? null : VcsStatusMode.repositoryAt(at);
        if (r == null)
            return;
        if (!r.backend().capabilities().contains(Capability.STASH)) {
            at.status(r.backend().name() + " has no stash");
            return;
        }
        TransientPopup.open(at, () -> popupOf(at));
    }

    // The popup as at's status shows it.
    static Transient popupOf(Editor at) {
        final VcsRepository r = VcsStatusMode.repositoryAt(at);
        final VcsStatusBuffer status = VcsStatusMode.owner(at);
        final boolean selected = status != null && Region.isSelection(at);
        final boolean picked = !selected && status != null && !status.picks().isEmpty();
        final boolean refused = selected
                ? StatusActions.targets(status.model(), at, false).stream().anyMatch(t -> !(t instanceof StatusActions.Files))
                : picked && status.picks().hasParts();
        final String taking = refused
                ? "Stash: hunks or lines are " + (selected ? "selected" : "picked") + ", a stash takes whole files"
                : selected ? "Stash the selected files"
                : picked ? "Stash the " + count(status.picks().wholeFiles().values())
                : null;
        return popup(
            r == null ? null : target(at, r),
            r != null && EachRepository.several(at) ? r.name() : null,
            EachRepository.of(at, ID) != null,
            taking,
            refused);
    }

    // "picked file", or "n picked files", each repository's its own.
    private static String count(java.util.Collection<StatusPicks.Pick> files) {
        final long n = files.stream().map(p -> List.of(p.repository(), p.path())).distinct().count();
        return n == 1 ? "picked file" : n + " picked files";
    }

    public static void vcsStashSave(Editor editor) {
        save(editor, EnumSet.noneOf(StashOption.class));
    }

    public static void vcsStashKeepIndex(Editor editor) {
        save(editor, EnumSet.of(StashOption.KEEP_INDEX));
    }

    public static void vcsStashUntracked(Editor editor) {
        save(editor, EnumSet.of(StashOption.UNTRACKED));
    }

    public static void vcsStashStaged(Editor editor) {
        save(editor, EnumSet.of(StashOption.STAGED));
    }

    public static void vcsStashApply(Editor editor) {
        apply(editor, false);
    }

    public static void vcsStashPop(Editor editor) {
        apply(editor, true);
    }

    /** Drops the stash at the caret, else the newest, after asking. */
    public static void vcsStashDrop(Editor editor) {
        final VcsRepository r = VcsStatusMode.repositoryAt(editor);
        final StashItem st = r == null ? null : target(editor, r);
        if (st == null) {
            editor.status("There is no stash");
            return;
        }
        drop(editor, r, st);
    }

    /** Drops st, after asking: x on its line. */
    static void drop(Editor editor, VcsRepository r, StashItem st) {
        if (!confirm.test(editor, "Drop " + st.name() + ": " + st.subject() + "?"))
            return;
        // Its hash said, so that it can be brought back.
        final String id = LogCommit.shortId(st.hash());
        final String done = "Dropped " + st.name() + " (" + id + "): git stash apply " + id + " brings it back";
        write(editor, r, "Dropping " + st.name(), () -> r.backend().stashDrop(r, st.name(), st.hash()), result -> done);
    }

    private static void save(Editor editor, Set<StashOption> options) {
        final VcsStatusBuffer status = VcsStatusMode.owner(editor);
        // A selection's files, before the picks.
        if (status != null && Region.isSelection(editor)) {
            saveSelected(editor, status, options);
            return;
        }
        if (status != null && !status.picks().isEmpty()) {
            savePicked(editor, status, options);
            return;
        }
        if (saveEach(editor, options))
            return;
        final VcsRepository r = VcsStatusMode.repositoryAt(editor);
        if (r == null)
            return;
        final TransientArgs args = Transients.args(editor, ID);
        final String message = args.get(MESSAGE);
        write(editor, r, "Stashing", () -> r.backend().stash(r, message, options), result -> {
            // For this stash alone: kept to try again if it failed.
            args.set(MESSAGE, null);
            // "Saved working directory and index state On main: x", or "No local changes to save".
            final String said = result.out().strip();
            return said.isEmpty() ? "Stashed" : said.split("\n", 2)[0];
        });
    }

    // Stashes the files the selection covers, whole, as savePicked does
    // the picked; hunks or lines refused.
    private static void saveSelected(Editor editor, VcsStatusBuffer status, Set<StashOption> options) {
        final Map<String, StatusPicks.Pick> files = new LinkedHashMap<>();
        for (StatusActions.Target t : StatusActions.targets(status.model(), editor, false)) {
            if (!(t instanceof StatusActions.Files f)) {
                editor.status("A stash takes whole files: hunks or lines are selected");
                return;
            }
            for (FileItem item : f.files())
                files.put(
                    StatusRenderer.fileKey(f.repository(), f.section(), item.path()),
                    new StatusPicks.Pick(f.repository(), f.section(), item.path()));
        }
        if (files.isEmpty()) {
            editor.status("No file selected to stash");
            return;
        }
        editor.setMark(null);
        saveFiles(editor, status, files, false, options);
    }

    // Stashes the files picked, whole, and unpicks those stashed. Hunks or
    // lines picked are refused: git stashes no part of a file without
    // asking which.
    private static void savePicked(Editor editor, VcsStatusBuffer status, Set<StashOption> options) {
        if (status.picks().hasParts()) {
            editor.status("A stash takes whole files: hunks or lines are picked");
            return;
        }
        saveFiles(editor, status, status.picks().wholeFiles(), true, options);
    }

    // Stashes files, by their lines' keys, in each repository they are in,
    // an untracked one with the untracked files; unpicks those stashed if
    // unpick. A repository with none of them to stash is left alone.
    private static void saveFiles(
            Editor editor,
            VcsStatusBuffer status,
            Map<String, StatusPicks.Pick> files,
            boolean unpick,
            Set<StashOption> options) {
        final Map<VcsRepository, List<StatusPicks.Pick>> byRepository = new LinkedHashMap<>();
        for (StatusPicks.Pick p : files.values())
            byRepository.computeIfAbsent(p.repository(), k -> new ArrayList<>()).add(p);
        final TransientArgs args = Transients.args(editor, ID);
        final String message = args.get(MESSAGE);
        EachRepository.write(editor, new ArrayList<>(byRepository.keySet()), r -> r, "Stashing", "Stashed", r -> {
            final List<StatusPicks.Pick> ps = byRepository.get(r);
            final Set<StashOption> o = EnumSet.noneOf(StashOption.class);
            o.addAll(options);
            if (ps.stream().anyMatch(p -> "untracked".equals(p.section())))
                o.add(StashOption.UNTRACKED);
            final List<String> paths = ps.stream().map(StatusPicks.Pick::path).distinct().toList();
            final VcsResult result = r.backend().stash(r, message, o, paths);
            if (result == null)
                return new EachRepository.Done(r, null, null, r.backend().name() + " can't stash some files");
            // git says so, and succeeds, with nothing of them to stash.
            if (result.succeeded() && result.out().contains("No local changes to save"))
                return new EachRepository.Done(r, null, "nothing of them to stash");
            return new EachRepository.Done(r, result, null);
        }, done -> {
            final Set<VcsRepository> stashed = new HashSet<>();
            for (EachRepository.Done d : done)
                if (!d.failed() && d.skipped() == null)
                    stashed.add(d.repository());
            if (unpick) {
                final Set<String> keys = new HashSet<>();
                for (Map.Entry<String, StatusPicks.Pick> e : files.entrySet())
                    if (stashed.contains(e.getValue().repository()))
                        keys.add(e.getKey());
                status.unpick(keys);
            }
            if (stashed.size() == done.size())
                args.set(MESSAGE, null);
        });
    }

    // With -a and several repositories, stashes in each with something to
    // stash, as its status was last read; false, doing nothing, otherwise.
    private static boolean saveEach(Editor editor, Set<StashOption> options) {
        final List<VcsRepository> rs = EachRepository.of(editor, ID);
        final VcsStatusBuffer status = VcsStatusMode.owner(editor);
        if (rs == null || status == null)
            return false;
        final TransientArgs args = Transients.args(editor, ID);
        final String message = args.get(MESSAGE);
        // Decided here, on the event thread, which reads the status.
        final Set<VcsRepository> has = new HashSet<>();
        for (VcsRepository r : rs)
            if (hasToStash(status, r, options))
                has.add(r);
        EachRepository.write(editor, rs, r -> r, "Stashing", "Stashed", r -> {
            if (!has.contains(r))
                return new EachRepository.Done(r, null, "nothing to stash");
            return new EachRepository.Done(r, r.backend().stash(r, message, options), null);
        }, done -> {
            // For these stashes alone: kept to try again, in those left, if any failed.
            if (done.stream().noneMatch(EachRepository.Done::failed))
                args.set(MESSAGE, null);
        });
        return true;
    }

    // Whether r has what a stash with options takes: what is staged, or
    // that or the tracked changes, or those or the untracked files.
    static boolean hasToStash(VcsStatusBuffer status, VcsRepository r, Set<StashOption> options) {
        if (!status.files(r, "staged").isEmpty())
            return true;
        if (options.contains(StashOption.STAGED))
            return false;
        return !status.files(r, "unstaged").isEmpty()
                || options.contains(StashOption.UNTRACKED) && !status.files(r, "untracked").isEmpty();
    }

    private static void apply(Editor editor, boolean pop) {
        final VcsRepository r = VcsStatusMode.repositoryAt(editor);
        final StashItem st = r == null ? null : target(editor, r);
        if (st == null) {
            editor.status("There is no stash");
            return;
        }
        write(
            editor,
            r,
            (pop ? "Popping " : "Applying ") + st.name(),
            () -> applyStash(r, st, pop),
            result -> (pop ? "Popped " : "Applied ") + st.name());
    }

    // Applies or pops st, noting on r a conflict it leaves.
    static VcsResult applyStash(VcsRepository r, StashItem st, boolean pop) {
        // HEAD as the note has it: moved, by a commit of the resolution, say, it ends.
        final String head = r.snapshotNow().head();
        final VcsResult result = r.backend().stashApply(r, st.name(), st.hash(), pop);
        if (result != null && StatusWrites.conflicted(result))
            r.stashConflicted(new VcsRepository.StashConflict(st.name(), st.hash(), pop, head));
        return result;
    }

    private static void write(
            Editor editor,
            VcsRepository r,
            String doing,
            Supplier<VcsResult> write,
            Function<VcsResult, String> done) {
        StatusWrites.write(editor, r, doing, "stash", write, done, result -> failed(doing, result));
    }

    /** What went wrong: a conflict, which leaves the stash, or git's error. */
    static String failed(String doing, VcsResult result) {
        return StatusWrites.failed(doing, result, "the stash is kept, and the conflicts are in Unmerged");
    }

    // The stash at the caret, else the newest; null for none.
    static StashItem target(Editor editor, VcsRepository r) {
        final VcsStatusBuffer status = VcsStatusMode.owner(editor);
        if (status == null)
            return null;
        if (editor.getBuffer() == status && editor.getDot() != null && editor.getDotLine() instanceof StatusLine s) {
            final StatusLine c = VcsStatusBuffer.commitOf(s);
            if (c != null && c.stash() != null)
                return c.stash();
        }
        final StatusData d = status.getData(r);
        if (d != null)
            for (Section s : d.sections())
                if (s.id().equals(StatusData.STASHES))
                    for (Item i : s.items())
                        if (i instanceof StashItem st)
                            return st;
        return null;
    }
}
