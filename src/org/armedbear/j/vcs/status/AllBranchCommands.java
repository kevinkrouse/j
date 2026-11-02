/*
 * AllBranchCommands.java
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.armedbear.j.Editor;
import org.armedbear.j.Log;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.log.LogRef;
import org.armedbear.j.vcs.log.LogRefTip;
import org.armedbear.j.vcs.status.BranchCommands.Context;

/**
 * The branch popup's commands in every repository of the status or log at
 * once, with its -a on, as IntelliJ's "Execute branch operations on all
 * roots": a branch asked for by name from those any repository has, each
 * saying how many have it, acted on in each that has it; a new one made
 * in each, as {@link EachRepository} does them.
 */
final class AllBranchCommands {
    private AllBranchCommands() {}

    // The repositories, with the branch popup's -a on and several.
    private static List<VcsRepository> repositories(Editor editor) {
        return EachRepository.of(editor, BranchCommands.ID);
    }

    // The commands, each false, doing nothing, without -a or with one repository.

    /** Checks out a branch by name, local, a remote's or a tag, in each that has it; with locals, as l lists them. */
    static boolean checkout(Editor editor, String at, boolean locals) {
        final List<VcsRepository> rs = repositories(editor);
        if (rs == null)
            return false;
        // l's by bare name, local in one repository, a remote's in another.
        final Function<Context, List<String>> names = locals ? Context::localNames : Context::refs;
        contexts(
            editor,
            rs,
            at,
            cs -> pick(
                editor,
                locals ? BranchCommands.CHECKOUT_LOCAL : BranchCommands.CHECKOUT_EACH,
                "vcsBranch.checkout",
                labeled(cs, names, true),
                name -> {
                    EachRepository.write(
                        editor,
                        cs,
                        Context::repository,
                        "Checking out " + name,
                        "Checked out " + name,
                        c -> {
                            if (!names.apply(c).contains(name))
                                return new EachRepository.Done(c.repository(), null, "no " + name);
                            if (name.equals(c.current()))
                                return new EachRepository.Done(c.repository(), null, "checked out");
                            final LogRef to = c.checkout(locals ? c.local(name) : name);
                            return new EachRepository.Done(c.repository(),
                                    c.repository().backend().switchTo(c.repository(), to),
                                    null);
                        });
                }));
        return true;
    }

    /**
     * Makes a branch in each, asking where it starts, by a name any has,
     * HEAD in those that haven't it, the ref at the caret offered first,
     * else HEAD, and its name; with checkout, checks it out.
     */
    static boolean create(Editor editor, String at, boolean checkout) {
        final List<VcsRepository> rs = repositories(editor);
        if (rs == null)
            return false;
        contexts(editor, rs, at, cs -> {
            final Map<String, String> refs = labeled(cs, Context::refs, false);
            final Map<String, String> starts = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : refs.entrySet())
                if (e.getValue().equals(at))
                    starts.put(e.getKey(), at);
            starts.put("HEAD", "HEAD");
            starts.putAll(refs);
            BranchCommands.choose
                    .choose(editor, BranchCommands.START_EACH, "vcsBranch.start", List.copyOf(starts.keySet()), l -> {
                        final String start = starts.get(l);
                        final String title =
                                (checkout ? "Create and Check Out" : "Create") + " a Branch in Each from " + l;
                        final String offered = start.equals("HEAD") || cs.get(0).ref(start).kind() != LogRef.Kind.REMOTE
                                ? ""
                                : BranchCommands.bare(start);
                        final String asked = BranchCommands.ask.ask(editor, "Name:", title, offered);
                        if (asked == null || asked.isBlank())
                            return;
                        final String name = asked.strip();
                        EachRepository
                                .write(editor, cs, Context::repository, "Creating " + name, "Created " + name, c -> {
                                    final String from = c.refs().contains(start) ? c.ref(start).fullName() : "HEAD";
                                    return new EachRepository.Done(c.repository(),
                                            c.repository().backend().createBranch(c.repository(), name, from, checkout),
                                            null);
                                });
                    });
        });
        return true;
    }

    /** Renames a local branch in each that has it. */
    static boolean rename(Editor editor, String at) {
        final List<VcsRepository> rs = repositories(editor);
        if (rs == null)
            return false;
        final Function<Context, List<String>> locals = c -> c.names(LogRef.Kind.BRANCH);
        contexts(
            editor,
            rs,
            at,
            cs -> pick(editor, BranchCommands.RENAME, "vcsBranch.rename", labeled(cs, locals, false), branch -> {
                final String asked =
                        BranchCommands.ask.ask(editor, "New name:", "Rename " + branch + " in Each", branch);
                if (asked == null || asked.isBlank() || asked.strip().equals(branch))
                    return;
                final String name = asked.strip();
                EachRepository.write(
                    editor,
                    cs,
                    Context::repository,
                    "Renaming " + branch,
                    "Renamed " + branch + " to " + name,
                    c -> {
                        if (!locals.apply(c).contains(branch))
                            return new EachRepository.Done(c.repository(), null, "no " + branch);
                        return new EachRepository.Done(c.repository(),
                                c.repository().backend().renameBranch(c.repository(), branch, name),
                                null);
                    });
            }));
        return true;
    }

    /**
     * Deletes a local branch in each that has it but has it checked out,
     * after asking, and asking again, naming them by repository, if any
     * has commits on no other ref there.
     */
    static boolean delete(Editor editor, String at) {
        final List<VcsRepository> rs = repositories(editor);
        if (rs == null)
            return false;
        final Function<Context, List<String>> locals = c -> c.names(LogRef.Kind.BRANCH);
        contexts(
            editor,
            rs,
            at,
            cs -> pick(editor, BranchCommands.DELETE, "vcsBranch.delete", labeled(cs, locals, true), branch -> {
                Background.then("vcs branch unmerged", () -> {
                    final Map<Context, List<String>> only = new LinkedHashMap<>();
                    for (Context c : cs)
                        if (locals.apply(c).contains(branch) && !branch.equals(c.current())) {
                            List<String> commits;
                            try {
                                commits = c.repository().backend().readOnlyOn(c.repository(), branch);
                            }
                            catch (RuntimeException e) {
                                // Unread: -d, git's check deciding.
                                Log.error(e);
                                commits = null;
                            }
                            only.put(c, commits);
                        }
                    return only;
                }, only -> {
                    if (only == null) {
                        editor.status("Can't read the branches: see the log");
                        return;
                    }
                    if (only.isEmpty()) {
                        editor.status(branch + " is checked out where it is");
                        return;
                    }
                    final List<String> in = new ArrayList<>();
                    for (Context c : only.keySet())
                        in.add(c.repository().name());
                    if (!StatusActions.confirm.test(
                        editor,
                        "Delete branch " + branch + " in " + String.join(", ", in) + "?",
                        "Delete Branch"))
                        return;
                    final String lost = lost(branch, only);
                    if (lost != null && !StatusActions.confirm.test(editor, lost, "Delete Branch"))
                        return;
                    EachRepository
                            .write(editor, cs, Context::repository, "Deleting " + branch, "Deleted " + branch, c -> {
                                if (!only.containsKey(c))
                                    return new EachRepository.Done(c.repository(),
                                            null,
                                            locals.apply(c).contains(branch) ? "checked out" : "no " + branch);
                                // Read: -D, asked about; unread: -d, git's check deciding.
                                final boolean force = only.get(c) != null;
                                return new EachRepository.Done(c.repository(),
                                        c.repository().backend().deleteBranch(c.repository(), branch, force),
                                        null);
                            });
                });
            }));
        return true;
    }

    // The second question, the commits lost by repository; null for none.
    static String lost(String branch, Map<Context, List<String>> only) {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<Context, List<String>> e : only.entrySet()) {
            final List<String> commits = e.getValue();
            if (commits == null || commits.isEmpty())
                continue;
            sb.append(e.getKey().repository().name()).append(":\n");
            for (String c : commits.subList(0, Math.min(SHOWN, commits.size())))
                sb.append("    ").append(c).append('\n');
            if (commits.size() > SHOWN)
                sb.append("    … ").append(commits.size() - SHOWN).append(" more\n");
        }
        if (sb.isEmpty())
            return null;
        return branch + " has commits on no other branch or tag, lost with it:\n\n" + sb + "\nDelete it anyway?";
    }

    // The unmerged commits a delete names at most in each repository.
    private static final int SHOWN = 10;

    // One of labeled's labels asked for, its name given to chosen.
    private static void pick(
            Editor editor,
            BranchCommands.Question q,
            String history,
            Map<String, String> labeled,
            Consumer<String> chosen) {
        if (labeled.isEmpty())
            editor.status("There is no other branch");
        else
            BranchCommands.choose
                    .choose(editor, q, history, List.copyOf(labeled.keySet()), l -> chosen.accept(labeled.get(l)));
    }

    /** Each repository's refs, the ref at the caret at, given to then on the event thread. */
    static void contexts(Editor editor, List<VcsRepository> rs, String at, Consumer<List<Context>> then) {
        Background.then("vcs branch refs", () -> {
            final List<Context> out = new ArrayList<>();
            for (VcsRepository r : rs) {
                final List<LogRefTip> tips = r.backend().readRefTips(r);
                String current = null;
                for (LogRefTip t : tips)
                    if (t.ref().kind() == LogRef.Kind.BRANCH && t.ref().head())
                        current = t.ref().name();
                out.add(new Context(r, at, current, tips));
            }
            return out;
        }, cs -> {
            if (cs == null)
                editor.status("Can't read the branches: see the log");
            else
                then.accept(cs);
        });
    }

    /**
     * The names in any of cs, by name, each labeled with how many have it,
     * "main (3)", "feature (1 of 3)": the label each is listed by, to its
     * name. The ref at the caret first if any has it; with others, not one
     * checked out in every one that has it.
     */
    static Map<String, String> labeled(List<Context> cs, Function<Context, List<String>> names, boolean others) {
        final Map<String, Integer> counts = new TreeMap<>();
        final Map<String, Integer> current = new TreeMap<>();
        for (Context c : cs)
            for (String name : names.apply(c)) {
                counts.merge(name, 1, Integer::sum);
                if (name.equals(c.current()))
                    current.merge(name, 1, Integer::sum);
            }
        final List<String> order = new ArrayList<>(counts.keySet());
        final String at = cs.get(0).at();
        if (at != null && order.remove(at))
            order.add(0, at);
        final Map<String, String> out = new LinkedHashMap<>();
        for (String name : order) {
            final int n = counts.get(name);
            if (others && current.getOrDefault(name, 0) == n)
                continue;
            out.put(name + (n == cs.size() ? " (" + n + ")" : " (" + n + " of " + cs.size() + ")"), name);
        }
        return out;
    }
}
