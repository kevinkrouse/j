/*
 * MergeCommands.java
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.armedbear.j.Editor;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.Transient.Switch;
import org.armedbear.j.transients.TransientArgs;
import org.armedbear.j.transients.TransientPopup;
import org.armedbear.j.transients.Transients;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.CommitOption;
import org.armedbear.j.vcs.MergeOption;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.status.BranchCommands.Context;

/**
 * The merge popup (m), after Neogit's: its switches, and merging a branch
 * asked for, the ref at the caret first, as the branch popup has it; or,
 * with a merge in progress, committing or giving it up.
 */
public final class MergeCommands {
    private MergeCommands() {}

    static final String ID = "vcs.merge";

    private static final String FF_ONLY = "--ff-only";
    private static final String NO_FF = "--no-ff";

    /** The popup: with merging, finishing or giving up the merge; else the switches and merging. */
    static Transient popup(Context c, boolean merging) {
        return popup(c, merging, null, false);
    }

    /**
     * The popup in a status or log of several repositories, here the
     * caret's, or null for one: -a, and where m merges, with -a in each;
     * a merge in progress is here's alone.
     */
    static Transient popup(Context c, boolean merging, String here, boolean each) {
        final String arg = " " + c.argument();
        if (merging)
            return new Transient(ID,
                    "Merge",
                    "git.html#merging",
                    List.of(
                        Group.of(
                            here == null ? "Actions" : "Actions, in " + here,
                            Command.of("m", "vcsMergeCommit" + arg, "commit merge"),
                            Command.of("a", "vcsMergeAbort" + arg, "abort merge"))));
        final List<Transient.Item> arguments = new ArrayList<>(
                List.of(Switch.of("-f", FF_ONLY, "fast-forward only"), Switch.of("-n", NO_FF, "no fast-forward")));
        if (here != null)
            arguments.add(Switch.of("-a", EachRepository.ALL, "all repositories"));
        final String merge = each ? "merge into each repository" : here != null ? "merge into " + here : "merge";
        return new Transient(ID,
                "Merge",
                "git.html#merging",
                List.of(
                    new Group("Arguments", arguments),
                    Group.of("Actions", Command.of("m", "vcsMerge" + arg, merge))));
    }

    /** The merge popup, the ref named or at the caret first in its list. */
    public static void vcsMergePopup(Editor editor, String arg) {
        BranchCommands.context(editor, arg, c -> {
            final VcsRepository r = c.repository();
            Background.then("vcs merging", () -> r.backend().isMerging(r), merging -> {
                TransientPopup.open(
                    editor,
                    () -> popup(
                        c,
                        Boolean.TRUE.equals(merging),
                        EachRepository.several(editor) ? r.name() : null,
                        EachRepository.of(editor, ID) != null));
            });
        });
    }

    /** Merges a branch, tag or revision, asked for, into what is checked out. */
    public static void vcsMerge(Editor editor, String arg) {
        final TransientArgs args = Transients.args(editor, ID);
        final Set<MergeOption> options = EnumSet.noneOf(MergeOption.class);
        if (args.isOn(FF_ONLY))
            options.add(MergeOption.FF_ONLY);
        if (args.isOn(NO_FF))
            options.add(MergeOption.NO_FF);
        if (options.size() == 2) {
            editor.status("Merge with -f or -n, not both");
            return;
        }
        final List<VcsRepository> rs = EachRepository.of(editor, ID);
        if (rs != null) {
            mergeEach(editor, rs, BranchCommands.at(editor, arg), options);
            return;
        }
        BranchCommands.context(
            editor,
            arg,
            c -> BranchCommands.choose
                    .choose(editor, BranchCommands.MERGE, "vcsMerge.branch", c.offered(c.refs(), true), name -> {
                        final VcsRepository r = c.repository();
                        final String into = c.current() == null ? "HEAD" : c.current();
                        BranchCommands.write(
                            editor,
                            c,
                            "Merging " + name,
                            "merge",
                            () -> r.backend().merge(r, c.ref(name).fullName(), options),
                            result -> result.out().contains("Already up to date")
                                    ? into + " already has " + name
                                    : "Merged " + name + " into " + into);
                    }));
    }

    // A ref by name, labeled with how many repositories have it, merged in
    // each that has it, not where it is checked out.
    private static void mergeEach(Editor editor, List<VcsRepository> rs, String at, Set<MergeOption> options) {
        AllBranchCommands.contexts(editor, rs, at, cs -> {
            final Map<String, String> labeled = AllBranchCommands.labeled(cs, Context::refs, true);
            if (labeled.isEmpty()) {
                editor.status("There is nothing to merge");
                return;
            }
            BranchCommands.choose.choose(
                editor,
                BranchCommands.MERGE_EACH,
                "vcsMerge.branch",
                List.copyOf(labeled.keySet()),
                label -> {
                    final String name = labeled.get(label);
                    EachRepository.write(editor, cs, Context::repository, "Merging " + name, "Merged " + name, c -> {
                        final VcsRepository r = c.repository();
                        if (!c.refs().contains(name))
                            return new EachRepository.Done(r, null, "no " + name);
                        if (name.equals(c.current()))
                            return new EachRepository.Done(r, null, "checked out");
                        return new EachRepository.Done(r, r.backend().merge(r, c.ref(name).fullName(), options), null);
                    });
                });
        });
    }

    /** Makes the merge's commit, with git's message, its conflicts resolved and staged. */
    public static void vcsMergeCommit(Editor editor, String arg) {
        BranchCommands.context(editor, arg, c -> {
            final VcsRepository r = c.repository();
            Background.then("vcs merging", () -> r.backend().isMerging(r), merging -> {
                if (!Boolean.TRUE.equals(merging))
                    editor.status("No merge is in progress");
                else
                    BranchCommands.write(
                        editor,
                        c,
                        "Committing the merge",
                        "commit",
                        () -> r.backend().commit(r, null, EnumSet.noneOf(CommitOption.class)),
                        result -> "Committed the merge");
            });
        });
    }

    /** Gives up the merge in progress, after asking, putting back what was there before it. */
    public static void vcsMergeAbort(Editor editor, String arg) {
        BranchCommands.context(editor, arg, c -> {
            if (!StatusActions.confirm.test(editor, "Abort the merge, losing its resolutions?", "Abort Merge"))
                return;
            final VcsRepository r = c.repository();
            BranchCommands.write(
                editor,
                c,
                "Aborting the merge",
                "merge",
                () -> r.backend().mergeAbort(r),
                result -> "Aborted the merge");
        });
    }
}
