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
import java.util.Locale;
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
import org.armedbear.j.vcs.Finish;
import org.armedbear.j.vcs.InProgress;
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

    /** The popup: with something in progress, finishing it; else the switches and merging. */
    static Transient popup(Context c, InProgress doing) {
        return popup(c, doing, null, false);
    }

    /**
     * The popup in a status or log of several repositories, here the
     * caret's, or null for one: -a, and where m merges, or what is in
     * progress is finished, with -a in each.
     */
    static Transient popup(Context c, InProgress doing, String here, boolean each) {
        final String arg = " " + c.argument();
        final List<Transient.Item> arguments = new ArrayList<>();
        if (here != null)
            arguments.add(Switch.of("-a", EachRepository.ALL, "all repositories"));
        if (doing != null) {
            final String where = each ? ", in each repository" : here == null ? "" : ", in " + here;
            final List<Transient.Item> actions = new ArrayList<>();
            for (Finish how : FinishCommands.offered(doing))
                actions.add(
                    doing == InProgress.MERGE
                            ? how == Finish.CONTINUE
                                    ? Command.of("m", "vcsMergeCommit" + arg, "commit merge")
                                    : Command.of("a", "vcsMergeAbort" + arg, "abort merge")
                            : Command.of(
                                how.name().substring(0, 1).toLowerCase(Locale.ROOT),
                                FinishCommands.command(how) + arg,
                                how == Finish.ABORT && each ? "abort or forget"
                                        : how == Finish.ABORT && doing.isStash() ? "forget"
                                        : how.name().toLowerCase(Locale.ROOT)));
            final List<Group> groups = new ArrayList<>();
            if (!arguments.isEmpty())
                groups.add(new Group("Arguments", arguments));
            groups.add(
                new Group(
                        Character.toUpperCase(doing.label.charAt(0)) + doing.label.substring(1) + " in progress"
                                + where,
                        actions));
            return new Transient(ID, "Merge", "git.html#finishing", groups);
        }
        arguments.add(0, Switch.of("-f", FF_ONLY, "fast-forward only"));
        arguments.add(1, Switch.of("-n", NO_FF, "no fast-forward"));
        final String merge = each ? "merge into each repository" : here != null ? "merge into " + here : "merge";
        return new Transient(ID,
                "Merge",
                "git.html#merging",
                List.of(
                    new Group("Arguments", arguments),
                    Group.of("Actions", Command.of("m", "vcsMerge" + arg, merge))));
    }

    /** The merge popup, the ref named or at the caret first in its list; with something in progress, its finishing. */
    public static void vcsMergePopup(Editor editor, String arg) {
        BranchCommands.context(editor, arg, c -> {
            final VcsRepository r = c.repository();
            Background.then("vcs in progress", () -> FinishCommands.state(r), state -> {
                TransientPopup.open(
                    editor,
                    () -> popup(
                        c,
                        state == null ? null : state.doing(),
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
        FinishCommands.finish(editor, Finish.CONTINUE, InProgress.MERGE, BranchCommands.repository(editor, arg));
    }

    /** Gives up the merge in progress, after asking, putting back what was there before it. */
    public static void vcsMergeAbort(Editor editor, String arg) {
        FinishCommands.finish(editor, Finish.ABORT, InProgress.MERGE, BranchCommands.repository(editor, arg));
    }
}
