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
 * pop or apply that conflicted. A pop's continue drops its stash. git
 * has no abort of a stash's, so its abort forgets it: the stash kept, no
 * file touched, what it wrote discarded in the status as any change. The
 * merge popup's actions while one is, and the status header's links;
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
     * What can be done to doing, in order: continue but for an apply,
     * which is over with its last conflict; skip where there are steps;
     * abort, which forgets a stash's. Continue is offered while files are
     * unmerged, to say so.
     */
    static List<Finish> offered(InProgress doing) {
        final List<Finish> out = new ArrayList<>();
        if (doing != InProgress.STASH_APPLY)
            out.add(Finish.CONTINUE);
        if (doing.steps)
            out.add(Finish.SKIP);
        out.add(Finish.ABORT);
        return out;
    }

    // Why how can't be done to doing, with those files unmerged, or null.
    private static String refusal(InProgress doing, Finish how, List<String> unmerged) {
        if (!offered(doing).contains(how))
            return switch (how) {
                case CONTINUE -> "A " + doing.label + " ends with its last conflict: there is nothing to continue";
                case SKIP -> "A " + doing.label + " has no step to skip";
                case ABORT -> null;
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
            final String where = EachRepository.several(editor) ? r.name() : null;
            final String question = question(how, doing, where);
            if (question != null && !StatusActions.confirm.test(editor, question, title(how, doing)))
                return;
            final String doingIt = verb(how, doing) + " the " + doing.label;
            final VcsRepository.StashConflict note = r.stashConflict();
            StatusWrites.write(
                editor,
                r,
                doingIt,
                how.name().toLowerCase(Locale.ROOT),
                () -> perform(r, doing, how),
                result -> {
                    LogBuffer.refreshAll(r);
                    // The stash dropped, by its id, so it can be had back.
                    if (doing == InProgress.STASH_POP && how == Finish.CONTINUE && note != null)
                        return "Continued the stash pop, dropping its stash " + note.hash() + ": git stash apply "
                                + note.hash() + " brings it back";
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
        for (Finish how : offered(doing)) {
            if (row.size() > 1)
                row.add(VirtualText.Run.of(" \u00b7 "));
            final String word = how == Finish.CONTINUE && doing == InProgress.MERGE ? "commit"
                    : how == Finish.ABORT && doing.isStash() ? "forget"
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
        // Read first, to ask with what each will lose; done in each as it
        // is then, which says why any is left alone.
        Background.then("vcs in progress", () -> rs.stream().map(FinishCommands::state).toList(), states -> {
            if (states == null) {
                editor.status("Can't read the repositories: see the log");
                return;
            }
            // Those it will act on, aborted or, a stash's, forgotten.
            final List<String> doing = new ArrayList<>();
            final List<String> forgotten = new ArrayList<>();
            boolean pops = false;
            boolean rebase = false;
            String first = null;
            for (int i = 0; i < rs.size(); i++) {
                final State state = states.get(i);
                final String why = refusal(state, how, only);
                if (why == null) {
                    final String named = rs.get(i).name() + " (" + state.doing().label + ")";
                    if (how == Finish.ABORT && state.doing().isStash())
                        forgotten.add(named);
                    else
                        doing.add(named);
                    pops |= state.doing() == InProgress.STASH_POP;
                    rebase |= state.doing() == InProgress.REBASE;
                } else if (first == null && state.doing() != null)
                    first = rs.get(i).name() + ": " + why;
            }
            // None to do: why, as the first that has something in progress says.
            if (doing.isEmpty() && forgotten.isEmpty()) {
                editor.status(
                    "Nothing to " + how.name().toLowerCase(Locale.ROOT) + " in any repository"
                            + (first == null ? "" : "; " + first));
                return;
            }
            final String in = StatusActions.list(doing);
            final String others = rebase ? resets(InProgress.REBASE) : "";
            final String question = switch (how) {
                case CONTINUE -> pops ? "Continue in " + in + ", dropping each stash popped? Its changes stay." : null;
                case SKIP -> "Skip the step in " + in + ", dropping its changes and your resolutions" + others + "?";
                case ABORT -> (doing.isEmpty() ? "" : "Abort in " + in + ", losing their resolutions" + others + "?")
                        + (doing.isEmpty() || forgotten.isEmpty() ? "" : " ")
                        + (forgotten.isEmpty()
                                ? ""
                                : "Forget in " + StatusActions.list(forgotten)
                                        + "? Its stash is kept, and no file is touched.");
            };
            if (question != null && !StatusActions.confirm.test(editor, question, title(how, null)))
                return;
            EachRepository.write(editor, rs, r -> r, verb(how, only), past(how, only), r -> {
                final State state = state(r);
                final String why = refusal(state, how, only);
                if (why != null)
                    return new EachRepository.Done(r, null, Character.toLowerCase(why.charAt(0)) + why.substring(1));
                return new EachRepository.Done(r, perform(r, state.doing(), how), null);
            });
        });
    }

    // Off the event thread: a stash pop's continue by j, dropping its
    // stash wherever it is now; the rest by the backend.
    static VcsResult perform(VcsRepository r, InProgress doing, Finish how) {
        if (!doing.isStash())
            return r.backend().finish(r, how);
        final VcsRepository.StashConflict note = r.stashConflict();
        if (note == null)
            return null;
        // A stash's abort forgets it: git has no abort of a stash's apply
        // or pop, and what it wrote is discarded in the status as any
        // change; the stash is kept.
        final VcsResult result = how == Finish.CONTINUE
                ? r.backend().stashDrop(r, null, note.hash())
                : new VcsResult(null, 0, new byte[0], "", 0);
        if (result != null && result.succeeded())
            r.stashConflicted(null);
        return result;
    }

    // What to ask before how, which loses work, in the repository named
    // where among several, else null; null for continue, which loses none
    // but a stash pop's stash.
    private static String question(Finish how, InProgress doing, String where) {
        final String in = where == null ? "" : " in " + where;
        return switch (how) {
            // Dropping a stash loses it, if what it wrote is gone: asked always.
            case CONTINUE -> doing == InProgress.STASH_POP
                    ? "Continue the stash pop" + in + ", dropping its stash? Its changes stay in the working tree."
                    : null;
            case SKIP -> "Skip the " + doing.label + "'s step" + in + ", dropping its changes and your resolutions"
                    + resets(doing) + "?";
            case ABORT -> doing.isStash()
                    ? "Forget the " + doing.label + in + "? Its stash is kept, and nothing in the working tree is"
                            + " touched: discard what it wrote in the status."
                    : "Abort the " + doing.label + in + ", losing its resolutions" + resets(doing) + "?";
        };
    }

    // A rebase's skip and abort reset the working tree: other edits go too.
    private static String resets(InProgress doing) {
        return doing == InProgress.REBASE ? " and any other uncommitted change to a tracked file" : "";
    }

    // The question's title.
    private static String title(Finish how, InProgress doing) {
        return switch (how) {
            case CONTINUE -> "Drop Stash";
            case SKIP -> "Skip";
            case ABORT -> doing != null && doing.isStash() ? "Forget" : "Abort";
        };
    }

    // What doing is called, as "Committing" a merge; with doing null, in
    // each of several repositories, words that stand alone.
    private static String verb(Finish how, InProgress doing) {
        return switch (how) {
            case CONTINUE -> doing == InProgress.MERGE ? "Committing" : "Continuing";
            case SKIP -> doing == null ? "Skipping a step" : "Skipping a step of";
            case ABORT -> doing != null && doing.isStash() ? "Forgetting" : "Aborting";
        };
    }

    private static String past(Finish how, InProgress doing) {
        return switch (how) {
            case CONTINUE -> doing == InProgress.MERGE ? "Committed" : "Continued";
            case SKIP -> doing == null ? "Skipped a step" : "Skipped a step of";
            case ABORT -> doing != null && doing.isStash() ? "Forgot" : "Aborted";
        };
    }
}
