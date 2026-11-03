/*
 * FinishCommands.java
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

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.VirtualText;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.Finish;
import org.armedbear.j.vcs.InProgress;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.log.LogBuffer;

/**
 * Continuing, skipping or aborting what is in progress: a merge, rebase,
 * patch series, cherry-pick or revert stopped at a conflict, or a stash's
 * pop or apply that conflicted. A pop's continue drops its stash; a
 * stash's abort takes its changes back, the stash kept, only while its
 * conflicts are, as after them it would take back other staged work too.
 * The merge popup's actions while one is, and the status header's links;
 * with the popup's -a, in each repository with one.
 */
public final class FinishCommands {
    private FinishCommands() {}

    /** Goes on with what is in progress, its conflicts resolved: a merge's commit, a rebase's next step. */
    public static void vcsContinue(Editor editor, String arg) {
        finish(editor, Finish.CONTINUE, null, BranchCommands.repository(editor, arg));
    }

    /** Skips the step a rebase, patch series, cherry-pick or revert stopped at, dropping it. */
    public static void vcsSkip(Editor editor, String arg) {
        finish(editor, Finish.SKIP, null, BranchCommands.repository(editor, arg));
    }

    /** Gives up what is in progress, after asking, putting back what was there before it. */
    public static void vcsAbort(Editor editor, String arg) {
        finish(editor, Finish.ABORT, null, BranchCommands.repository(editor, arg));
    }

    /** What a repository has in progress, and its unmerged files. */
    record State(InProgress doing, List<String> unmerged) {}

    /** r's, read fresh: resolved and staged elsewhere, perhaps, unseen yet. Off the event thread. */
    static State state(VcsRepository r) {
        r.invalidateFiles();
        return new State(r.inProgress(), unmerged(r.snapshotNow()));
    }

    private static List<String> unmerged(Snapshot s) {
        return s == null
                ? List.of()
                : s.changes().stream().filter(FileChange::isConflicted).map(FileChange::path).toList();
    }

    /**
     * What can be done to doing, with or without files unmerged, in
     * order: continue but for an apply, over with its last conflict; skip
     * where there are steps; abort but for a stash's whose conflicts are
     * resolved. Continue is offered while files are unmerged, to say so.
     */
    static List<Finish> offered(InProgress doing, boolean unmerged) {
        final List<Finish> out = new ArrayList<>();
        if (doing != InProgress.STASH_APPLY)
            out.add(Finish.CONTINUE);
        if (doing.steps)
            out.add(Finish.SKIP);
        if (!doing.isStash() || unmerged)
            out.add(Finish.ABORT);
        return out;
    }

    // Why how can't be done to doing, with those files unmerged, or null.
    private static String refusal(InProgress doing, Finish how, List<String> unmerged) {
        if (!offered(doing, !unmerged.isEmpty()).contains(how))
            return switch (how) {
                case CONTINUE -> "A " + doing.label + " is over with its last conflict";
                case SKIP -> "A " + doing.label + " has no step to skip";
                case ABORT -> "The " + doing.label
                        + "'s conflicts are resolved: continue it, or take its changes back by hand";
            };
        if (how == Finish.CONTINUE && !unmerged.isEmpty())
            return "Not while " + StatusActions.list(unmerged)
                    + (unmerged.size() == 1 ? " is unmerged" : " are unmerged");
        return null;
    }

    // Why how can't be done to what state has in progress, only's if given, or null.
    private static String refusal(State state, Finish how, InProgress only) {
        if (state.doing() == null || only != null && only != state.doing())
            return only == null ? "Nothing is in progress" : "No " + only.label + " is in progress";
        return refusal(state.doing(), how, state.unmerged());
    }

    /**
     * Does how to what r has in progress, only if it is only when only is
     * given; with the merge popup's -a, in each repository with something
     * in progress.
     */
    static void finish(Editor editor, Finish how, InProgress only, VcsRepository r) {
        final List<VcsRepository> rs = EachRepository.of(editor, MergeCommands.ID);
        if (rs != null)
            finishEach(editor, rs, how, only);
        else
            finishIn(editor, how, only, r);
    }

    // In r alone.
    private static void finishIn(Editor editor, Finish how, InProgress only, VcsRepository r) {
        if (r == null) {
            editor.status("Not in a repository");
            return;
        }
        Background.then("vcs in progress", () -> state(r), state -> {
            if (state == null) {
                editor.status("Can't read the repository: see the log");
                return;
            }
            final String why = refusal(state, how, only);
            if (why != null) {
                editor.status(why);
                return;
            }
            final InProgress doing = state.doing();
            if (how == Finish.ABORT && !StatusActions.confirm.test(editor, abortQuestion(doing), "Abort"))
                return;
            final String doingIt = verb(how, doing) + " the " + doing.label;
            StatusWrites.write(
                editor,
                r,
                doingIt,
                how.name().toLowerCase(Locale.ROOT),
                () -> perform(r, doing, how),
                result -> {
                    LogBuffer.refreshAll(r);
                    return past(how, doing) + " the " + doing.label;
                },
                result -> BranchCommands.failed(doingIt, result));
        });
    }

    private static final Map<Finish, String> COMMANDS =
            Map.of(Finish.CONTINUE, "vcsContinue", Finish.SKIP, "vcsSkip", Finish.ABORT, "vcsAbort");

    /** The command that does how. */
    static String command(Finish how) {
        return COMMANDS.get(how);
    }

    /** The owner of a status's links in its layer. */
    static final String OWNER = "vcs.status.finish";

    /**
     * The links after a status's line of what r has in progress, those
     * offered, a merge's continue its commit, in r alone; none with
     * nothing in progress.
     */
    static List<VirtualText.Run> links(Buffer buffer, VcsRepository r) {
        final InProgress doing = r.inProgress();
        if (doing == null)
            return List.of();
        final Color color = buffer.getFormatter().getFormatTable().resolveColor("link", true);
        final List<VirtualText.Run> row = new ArrayList<>();
        row.add(VirtualText.Run.of("   "));
        for (Finish how : offered(doing, !unmerged(r.snapshot()).isEmpty())) {
            if (row.size() > 1)
                row.add(VirtualText.Run.of(" \u00b7 "));
            final String word = how == Finish.CONTINUE && doing == InProgress.MERGE
                    ? "commit"
                    : how.name().toLowerCase(Locale.ROOT);
            row.add(
                new VirtualText.Run(word,
                        color,
                        0,
                        () -> finishIn(Editor.currentEditor(), how, null, r),
                        command(how)));
        }
        return row;
    }

    // In each repository with something in progress, after asking once to abort.
    private static void finishEach(Editor editor, List<VcsRepository> rs, Finish how, InProgress only) {
        if (how == Finish.ABORT
                && !StatusActions.confirm
                        .test(editor, "Abort what is in progress in each repository, losing its resolutions?", "Abort"))
            return;
        EachRepository.write(editor, rs, r -> r, verb(how, only), past(how, only), r -> {
            final State state = state(r);
            final String why = refusal(state, how, only);
            if (why != null)
                return new EachRepository.Done(r, null, Character.toLowerCase(why.charAt(0)) + why.substring(1));
            return new EachRepository.Done(r, perform(r, state.doing(), how), null);
        });
    }

    // Off the event thread: a stash's by j, the rest by the backend.
    static VcsResult perform(VcsRepository r, InProgress doing, Finish how) {
        if (!doing.isStash())
            return r.backend().finish(r, how);
        final VcsRepository.StashConflict note = r.stashConflict();
        if (note == null)
            return null;
        // A pop's continue drops its stash wherever it is now.
        final VcsResult result =
                how == Finish.ABORT ? r.backend().resetMerge(r) : r.backend().stashDrop(r, null, note.hash());
        if (result != null && result.succeeded())
            r.stashConflicted(null);
        return result;
    }

    private static String abortQuestion(InProgress doing) {
        return doing.isStash()
                ? "Abort the " + doing.label + ", taking its changes back? The stash is kept."
                : "Abort the " + doing.label + ", losing its resolutions?";
    }

    // What doing is called, as "Committing" a merge; with doing null, in
    // each of several repositories, words that stand alone.
    private static String verb(Finish how, InProgress doing) {
        return switch (how) {
            case CONTINUE -> doing == InProgress.MERGE ? "Committing" : "Continuing";
            case SKIP -> doing == null ? "Skipping a step" : "Skipping a step of";
            case ABORT -> "Aborting";
        };
    }

    private static String past(Finish how, InProgress doing) {
        return switch (how) {
            case CONTINUE -> doing == InProgress.MERGE ? "Committed" : "Continued";
            case SKIP -> doing == null ? "Skipped a step" : "Skipped a step of";
            case ABORT -> "Aborted";
        };
    }
}
