/*
 * PartialCommit.java
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.armedbear.j.CommentRing;
import org.armedbear.j.vcs.Capability;
import org.armedbear.j.vcs.CommitOption;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.changelists.Changelists;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.selection.Selections;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.selection.Target.Files;
import org.armedbear.j.vcs.selection.Target.Member;
import org.armedbear.j.vcs.selection.Target.Part;
import org.armedbear.j.vcs.signs.SignsUpdater;
import org.armedbear.j.vcs.status.StatusActions.Op;
import org.armedbear.j.vcs.status.StatusData.FileItem;

/**
 * A commit of part of the work tree's changes, a changelist's or those
 * picked, and not of what is staged, as git's own commit of paths makes
 * one: through a private index, the repository's made HEAD's tree, they
 * staged into it as s stages them, committed; then each path committed set
 * in the repository's index as HEAD now has it, which leaves what is
 * staged staged. A file with something staged it refuses, as that would
 * take or lose it. What it takes is worked out as it commits (plan).
 */
final class PartialCommit {
    private final RepositoryStatus model;
    // The repository whose changelist list it commits, or null for the picks.
    private final VcsRepository repository;
    private final String list;
    // The changes given it to commit, as a context menu picks them: whole
    // files, or a hunk or lines; null when it commits a list or the picks.
    private final List<Target> given;

    private PartialCommit(RepositoryStatus model, VcsRepository repository, String list, List<Target> given) {
        this.model = model;
        this.repository = repository;
        this.list = list;
        this.given = given;
    }

    /** A commit of r's changelist list. */
    static PartialCommit ofList(RepositoryStatus model, VcsRepository r, String list) {
        return new PartialCommit(model, r, list, null);
    }

    /** A commit of what the model has picked. */
    static PartialCommit ofPicks(RepositoryStatus model) {
        return new PartialCommit(model, null, null, null);
    }

    /**
     * A commit of the changes given: whole files, as the Changes tab's
     * Commit file… takes a file, or a hunk or lines, as the status's
     * Commit alone… does. What is staged stays staged; a file with
     * something staged is refused, as a list's would be.
     */
    static PartialCommit ofTargets(RepositoryStatus model, List<Target> targets) {
        return new PartialCommit(model, null, null, targets);
    }

    /** What it commits, as said: "changelist Feature", "the picked changes". */
    String what() {
        return list != null ? "changelist " + list : given != null ? "the changes selected" : "the picked changes";
    }

    /**
     * What it takes, by repository, one commit each: its targets, and of
     * picks, the keys of those it takes, unpicked as each is committed.
     */
    record Plan(Map<VcsRepository, List<Target>> targets, Map<VcsRepository, Set<String>> picks) {}

    /** Why it can't be committed, as a plan finds it. */
    static final class Refused extends Exception {
        Refused(String why) {
            super(why, null, false, false);
        }
    }

    /** What it takes as the status has it now; refused, with why, if it can't be committed so. */
    Plan plan() throws Refused {
        if (model.isDisposed())
            throw new Refused("The status was closed: commit from it again");
        if (model.isWriting())
            throw new Refused("Wait: the last change is still being made");
        final Map<VcsRepository, List<Target>> targets = new LinkedHashMap<>();
        final Map<VcsRepository, Set<String>> picks = new HashMap<>();
        if (list != null) {
            // The repository's refusals first: no file's read can lift them.
            refuseRepository(repository);
            targets.put(repository, listTargets());
        } else if (given != null) {
            if (given.isEmpty())
                throw new Refused("Nothing is selected to commit");
            if (StatusActions.hunksIgnoreWhitespace(model, given))
                throw new Refused("Not a hunk while whitespace is ignored: show it to commit a hunk alone");
            final Selections.Resolution resolved = model.resolveTargets(given);
            if (!resolved.ready()) {
                if (resolved.problems().isEmpty())
                    model.resolveTargets(given, ignored -> {});
                throw new Refused(resolved.failure());
            }
            for (Target t : resolved.targets())
                targets.computeIfAbsent(t.repository(), k -> new ArrayList<>()).add(t);
            for (VcsRepository r : targets.keySet())
                refuseRepository(r);
        } else {
            if (model.picks().unread(model))
                throw new Refused("Wait: the picked files' diffs are being read");
            final List<Target> picked = model.picks().targets(model);
            if (picked.isEmpty())
                throw new Refused("Nothing is picked: ; or the boxes pick changes");
            if (StatusActions.hunksIgnoreWhitespace(model, picked))
                throw new Refused("Not a hunk while whitespace is ignored: show it to commit picked hunks");
            for (Target t : picked)
                targets.computeIfAbsent(t.repository(), k -> new ArrayList<>()).add(t);
            for (VcsRepository r : targets.keySet())
                refuseRepository(r);
            for (Map.Entry<Target, Set<String>> e : model.picks().used(picked).entrySet())
                picks.computeIfAbsent(e.getKey().repository(), k -> new HashSet<>()).addAll(e.getValue());
        }
        for (Map.Entry<VcsRepository, List<Target>> e : targets.entrySet())
            refuse(e.getKey(), e.getValue());
        return new Plan(targets, picks);
    }

    // Its changelist's targets, as s on its heading takes them.
    private List<Target> listTargets() throws Refused {
        final ListedChanges listed = model.listed(repository);
        final List<Member> members = listed == null ? null : listed.members().get(list);
        if (members == null || members.isEmpty())
            throw new Refused("Nothing in changelist " + list + " to commit");
        // A file kept change by change whose diff isn't read is listed whole
        // under the list of most of it: its other lists' lines would go too.
        final RepositoryLists lists = RepositoryLists.of(repository);
        for (Member m : members) {
            final Changelists.Assignment a = lists == null ? null : lists.lists().assignment(m.file().path());
            if (m.file().diff() == null && m.file().hasDiff() && a != null && a.whole() == null) {
                model.refresh();
                throw new Refused("Wait: " + m.file().path() + "'s diff is being read");
            }
        }
        return StatusActions.listTargets(repository, StatusRenderer.UNSTAGED, members);
    }

    // Why nothing of r can be committed alone, thrown.
    private void refuseRepository(VcsRepository r) throws Refused {
        if (!r.backend().capabilities().contains(Capability.STAGING))
            throw new Refused(r.backend().name() + " has no index to commit part of the changes through");
        // Git records a merge, or ends a cherry-pick, with the next commit, whatever it takes.
        if (r.inProgress() != null)
            throw new Refused((model.getRepositories().size() > 1 ? r.name() + ": a " : "A ") + r.inProgress().label
                    + " is in progress: finish it with c c, or abort it, first");
        if (!model.files(r, StatusData.UNMERGED).isEmpty())
            throw new Refused("Resolve the conflicts first");
    }

    // Why r's targets ts can't be committed alone, thrown.
    private void refuse(VcsRepository r, List<Target> ts) throws Refused {
        final Set<String> staged = new HashSet<>();
        for (FileItem f : model.files(r, "staged")) {
            staged.add(f.path());
            if (f.change().origPath() != null)
                staged.add(f.change().origPath());
        }
        for (Target t : ts) {
            if (t.section().equals("staged"))
                throw new Refused("Picks of what is staged aren't committed alone: c c commits what is staged");
            if (t.section().equals(StatusData.UNMERGED))
                throw new Refused("Resolve the conflicts first");
            final String why = StatusActions.refuse(Op.STAGE, t);
            if (why != null)
                throw new Refused(why);
            for (FileItem f : files(t))
                if (staged.contains(f.path()))
                    throw new Refused(f.path() + " has staged changes: c c commits them, or u unstages them");
        }
    }

    // The files t is of.
    private static List<FileItem> files(Target t) {
        return t instanceof Files f ? f.files() : List.of(((Part) t).file());
    }

    /** The message, then as # lines, which git leaves out, what plan commits in repositories. */
    String template(String message, Plan plan, Collection<VcsRepository> repositories) {
        final StringBuilder sb = new StringBuilder(message).append("\n\n");
        sb.append(LEFT_OUT).append('\n');
        sb.append("# Commits ").append(what()).append(" alone; what is staged stays staged:\n");
        for (VcsRepository r : repositories) {
            final Set<String> paths = new LinkedHashSet<>();
            for (Target t : plan.targets().getOrDefault(r, List.of()))
                for (FileItem f : files(t))
                    paths.add(f.letter() + "  " + f.path());
            for (String p : paths)
                sb.append("#   ").append(plan.targets().size() > 1 ? r.name() + ": " : "").append(p).append('\n');
        }
        return sb.toString();
    }

    // The template's first # line: what is above it, the message as written.
    private static final String LEFT_OUT = "# Lines starting with # are left out.";

    /** text, a message as written, its template's lines as plan, in repositories, has them. */
    String retemplate(String text, Plan plan, Collection<VcsRepository> repositories) {
        final String now = template("", plan, repositories);
        final int at = text.indexOf("\n" + LEFT_OUT);
        return (at < 0 ? text + "\n" : text.substring(0, at)) + now.substring(now.indexOf("\n" + LEFT_OUT));
    }

    /** What committing in a repository came to: the commit's result, and why the index wasn't set after, or null. */
    record Done(VcsResult result, String index) {}

    /** Commits plan's changes in r, each path committed then set in r's index as committed; off the event thread. */
    Done commit(Plan plan, VcsRepository r, String message, Set<CommitOption> options) {
        final List<Target> ts = plan.targets().get(r);
        // A file renamed: its old path too, as its removal.
        final Set<String> paths = new LinkedHashSet<>();
        for (Target t : ts)
            for (FileItem f : files(t)) {
                paths.add(f.path());
                if (f.change().origPath() != null)
                    paths.add(f.change().origPath());
            }
        final VcsBackend b = r.backend();
        final VcsBackend.PrivateCommit done =
                b.commitPrivately(r, List.copyOf(paths), () -> stage(r, ts), message, options);
        return done == null ? new Done(null, null) : new Done(done.result(), done.trouble());
    }

    // ts staged in r, as s stages them, a renamed file's old path with it:
    // what went wrong, or null.
    private static String stage(VcsRepository r, List<Target> ts) {
        final List<String> old = new ArrayList<>();
        for (Target t : ts) {
            final String failed = StatusActions.perform(Op.STAGE, t);
            if (failed != null)
                return failed;
            if (t instanceof Files f)
                for (FileItem i : f.files())
                    if (i.change().origPath() != null)
                        old.add(i.change().origPath());
        }
        return old.isEmpty() ? null : StatusActions.failure(r.backend(), r.backend().stage(r, old));
    }

    /**
     * Committed in r: r's picks unpicked; its changelist, all of it
     * committed, deleted if not the default, the default's draft cleared.
     * On the event thread.
     */
    void committed(Plan plan, VcsRepository r) {
        final Set<String> keys = plan.picks().get(r);
        if (keys != null && !keys.isEmpty())
            model.unpick(keys);
        if (list == null)
            return;
        final RepositoryLists lists = RepositoryLists.of(r);
        if (lists == null)
            return;
        if (Changelists.DEFAULT.equals(list)) {
            lists.update(c -> c.withComment(list, ""));
            SignsUpdater.listsChanged(r);
            return;
        }
        // Deleted once a read shows nothing left in it: a change the commit
        // didn't take, or one made meanwhile, keeps it.
        model.readDiffsThen(Set.of(), () -> {
            final ListedChanges listed = model.listed(r);
            if (listed == null || !listed.members().getOrDefault(list, List.of()).isEmpty())
                return;
            lists.update(c -> c.without(list));
            SignsUpdater.listsChanged(r);
        });
    }

    /**
     * Given up, its message text as written: kept as its changelist's
     * draft, the next commit of it starting from it; # lines of its own
     * kept, the template's left.
     */
    void giveUp(String text) {
        if (list == null)
            return;
        final int at = text.indexOf("\n" + LEFT_OUT);
        final String draft = (at < 0 ? text : text.substring(0, at)).strip();
        final RepositoryLists lists = RepositoryLists.of(repository);
        if (lists != null && lists.lists().list(list) != null)
            lists.update(c -> c.withComment(list, draft));
        else if (!draft.isEmpty())
            // Its list renamed or gone meanwhile: kept where Ctrl P finds it.
            CommentRing.getInstance().appendNew(draft);
    }

    /** r's changelist list's draft, or "". */
    static String draft(VcsRepository r, String list) {
        final RepositoryLists lists = RepositoryLists.of(r);
        final Changelists.Changelist l = lists == null ? null : lists.lists().list(list);
        return l == null || l.comment() == null ? "" : l.comment();
    }
}
