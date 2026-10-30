/*
 * FetchCommands.java
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.armedbear.j.Editor;
import org.armedbear.j.Finders;
import org.armedbear.j.LocationBar;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.Transient.Switch;
import org.armedbear.j.transients.TransientArgs;
import org.armedbear.j.transients.TransientPopup;
import org.armedbear.j.transients.Transients;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.CommandLog;
import org.armedbear.j.vcs.FetchOption;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.Snapshot.Relation;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.log.LogRef;
import org.armedbear.j.vcs.log.LogRefTip;

/**
 * Fetching from a status buffer: the fetch popup (f), after magit's and
 * Neogit's, and its commands. A fetch runs in the background, the status
 * bar saying so, then what it fetched or why it failed.
 */
public final class FetchCommands {
    private FetchCommands() {}

    static final String ID = "vcs.fetch";

    /** A branch's remotes: every one, and its push target's and its upstream's, null for none. */
    record Remotes(List<String> all, String push, String upstream) {}

    /** repository's remotes, as its branch has them. Off the event thread. */
    static Remotes remotes(VcsRepository repository) {
        final List<String> all = repository.backend().readRemotes(repository);
        final Snapshot s = repository.snapshotNow();
        return new Remotes(all, remoteOf(s.push(), all), remoteOf(s.upstream(), all));
    }

    // The remote a remote branch's name starts with, the longest of them;
    // null for none, as for a local branch.
    private static String remoteOf(Relation relation, List<String> remotes) {
        return relation == null ? null : remoteOf(relation.name(), remotes);
    }

    static String remoteOf(String branch, List<String> remotes) {
        String best = null;
        for (String r : remotes)
            if (branch.startsWith(r + "/") && (best == null || r.length() > best.length()))
                best = r;
        return best;
    }

    /** The fetch popup: its switches, and where to fetch from, each it has no remote for dimmed. */
    static Transient popup(Remotes remotes) {
        return popup(remotes, null, false);
    }

    /**
     * The popup in a status of several repositories, here the caret's, or
     * null for one: -a, and what each line fetches in, with -a in each.
     */
    static Transient popup(Remotes remotes, String here, boolean each) {
        final boolean any = !remotes.all().isEmpty();
        final List<Transient.Item> arguments = new ArrayList<>(List
                .of(Switch.of("-p", "--prune", "prune deleted branches"), Switch.of("-t", "--tags", "fetch all tags")));
        if (here != null)
            arguments.add(Switch.of("-a", EachRepository.ALL, "all repositories"));
        if (each)
            return new Transient(ID,
                    "Fetch",
                    "git.html#fetching",
                    List.of(
                        new Group("Arguments", arguments),
                        Group.of(
                            "Fetch in each repository from",
                            Command.of("p", "vcsFetchPushRemote", "its push remote"),
                            Command.of("u", "vcsFetchUpstream", "its upstream's remote"),
                            Command.of("a", "vcsFetchAll", "all its remotes")),
                        Group.of(
                            "Fetch in " + here + " alone from",
                            Command.of("e", "vcsFetchElsewhere", any ? "elsewhere" : "a URL"),
                            any
                                    ? Command.of("o", "vcsFetchBranch", "another branch")
                                    : Command.unavailable("o", "another branch, no remote set"))));
        return new Transient(ID,
                "Fetch",
                "git.html#fetching",
                List.of(
                    new Group("Arguments", arguments),
                    Group.of(
                        here == null ? "Fetch from" : "Fetch in " + here + " from",
                        remotes.push() != null
                                ? Command.of("p", "vcsFetchPushRemote", remotes.push())
                                : Command.unavailable("p", "the push remote, not set"),
                        remotes.upstream() != null
                                ? Command.of("u", "vcsFetchUpstream", remotes.upstream())
                                : Command.unavailable("u", "the upstream, not set"),
                        any
                                ? Command.of("a", "vcsFetchAll", "all remotes")
                                : Command.unavailable("a", "all remotes, none set"),
                        Command.of("e", "vcsFetchElsewhere", any ? "elsewhere" : "a URL"),
                        any
                                ? Command.of("o", "vcsFetchBranch", "another branch")
                                : Command.unavailable("o", "another branch, no remote set"))));
    }

    /** The fetch popup of the status, from another buffer in a repository its status opened first. */
    public static void vcsFetchPopup(Editor editor) {
        final Editor at = CommitCommands.statusWindow(editor);
        final VcsRepository r = at == null ? null : VcsStatusMode.repositoryAt(at);
        if (r != null)
            Background.then("vcs fetch remotes", () -> remotes(r), remotes -> {
                if (remotes == null)
                    at.status("Can't read the remotes: see the log");
                else
                    TransientPopup.open(
                        at,
                        () -> popup(
                            remotes,
                            EachRepository.several(at) ? r.name() : null,
                            EachRepository.of(at, ID) != null));
            });
    }

    public static void vcsFetchPushRemote(Editor editor) {
        if (!fetchEach(editor, "its push remote", Remotes::push, "no push remote"))
            fetchFrom(editor, Remotes::push, "There is no push remote");
    }

    public static void vcsFetchUpstream(Editor editor) {
        if (!fetchEach(editor, "its upstream's remote", Remotes::upstream, "no upstream on a remote"))
            fetchFrom(editor, Remotes::upstream, "There is no upstream on a remote");
    }

    public static void vcsFetchAll(Editor editor) {
        if (fetchEach(editor, "all its remotes", null, null))
            return;
        final VcsRepository r = VcsStatusMode.repositoryAt(editor);
        if (r != null)
            fetch(editor, r, null, null);
    }

    // With -a and several repositories, fetches in each from the remote
    // which says, null for all of them, one with none left, saying why;
    // false, doing nothing, otherwise.
    private static boolean fetchEach(Editor editor, String from, Function<Remotes, String> which, String none) {
        final List<VcsRepository> rs = EachRepository.of(editor, ID);
        if (rs == null)
            return false;
        final Set<FetchOption> options = options(editor);
        EachRepository.write(editor, rs, r -> r, "Fetching from " + from, "Fetched", r -> {
            final String remote = which == null ? null : which.apply(remotes(r));
            if (which != null && remote == null)
                return new EachRepository.Done(r, null, none);
            return new EachRepository.Done(r, r.backend().fetch(r, remote, null, options), null);
        });
        return true;
    }

    /** A remote asked for, from a list of them. */
    public static void vcsFetchElsewhere(Editor editor) {
        final VcsRepository r = VcsStatusMode.repositoryAt(editor);
        if (r == null)
            return;
        Background.then("vcs fetch remotes", () -> r.backend().readRemotes(r), remotes -> {
            if (remotes == null)
                editor.status("Can't read the remotes: see the log");
            else
                Finders.choose(
                    editor,
                    LocationBar.PROMPT_REMOTE,
                    "select a remote or enter a URL",
                    true,
                    "vcsFetch.remote",
                    remotes,
                    remote -> fetch(editor, r, remote, null, !remotes.contains(remote)));
        });
    }

    /** A remote's branch asked for, from a list of them: only it fetched. */
    public static void vcsFetchBranch(Editor editor) {
        final VcsRepository r = VcsStatusMode.repositoryAt(editor);
        if (r == null)
            return;
        Background.then("vcs fetch branches", () -> branches(r), branches -> {
            if (branches == null || branches.isEmpty())
                editor.status("There is no remote branch");
            else
                Finders.choose(
                    editor,
                    LocationBar.PROMPT_FETCH,
                    "select a remote's branch",
                    false,
                    "vcsFetch.branch",
                    List.copyOf(branches.keySet()),
                    branch -> {
                        final String remote = branches.get(branch);
                        fetch(editor, r, remote, branch.substring(remote.length() + 1));
                    });
        });
    }

    // The remotes' branches, but their HEADs, each with its remote.
    private static Map<String, String> branches(VcsRepository r) {
        final List<String> remotes = r.backend().readRemotes(r);
        final Map<String, String> branches = new LinkedHashMap<>();
        for (LogRefTip t : r.backend().readRefTips(r)) {
            final String remote = remoteOf(t.ref().name(), remotes);
            if (t.ref().kind() == LogRef.Kind.REMOTE && !t.ref().name().endsWith("/HEAD") && remote != null)
                branches.put(t.ref().name(), remote);
        }
        return branches;
    }

    // From the remote which says, or why there is none.
    private static void fetchFrom(Editor editor, Function<Remotes, String> which, String none) {
        final VcsRepository r = VcsStatusMode.repositoryAt(editor);
        if (r == null)
            return;
        Background.then("vcs fetch remotes", () -> which.apply(remotes(r)), remote -> {
            if (remote == null)
                editor.status(none);
            else
                fetch(editor, r, remote, null);
        });
    }

    // Fetches in the background, the popup's switches on, saying so.
    private static void fetch(Editor editor, VcsRepository r, String remote, String branch) {
        fetch(editor, r, remote, branch, false);
    }

    // With url, remote a URL, or a path, fetched into FETCH_HEAD alone.
    private static void fetch(Editor editor, VcsRepository r, String remote, String branch, boolean url) {
        final Set<FetchOption> options = options(editor);
        final String from = remote == null ? "all remotes" : branch == null ? remote : remote + "/" + branch;
        editor.status("Fetching from " + from + "…");
        Background.then("vcs fetch", () -> r.backend().fetch(r, remote, branch, options), result -> {
            if (result == null)
                editor.status(r.backend().name() + " can't fetch");
            else if (!result.succeeded()) {
                final String why = result.lastError();
                editor.status("Fetching from " + from + " failed: " + why);
                if (editor.getFrame() != null)
                    CommandLog.vcsCommandLog();
            } else if (url)
                editor.status("Fetched from " + from + " into FETCH_HEAD");
            else
                editor.status(fetched(from, r.backend().fetchedRefs(result)));
            r.invalidate();
            r.refresh();
        });
    }

    /** What a fetch from from did, given how many refs it moved, -1 if not known. */
    static String fetched(String from, int refs) {
        if (refs < 0)
            return "Fetched from " + from;
        if (refs == 0)
            return "Fetched from " + from + ": nothing new";
        return "Fetched from " + from + ": " + refs + (refs == 1 ? " ref" : " refs") + " updated";
    }

    // The popup's switches, as options.
    static Set<FetchOption> options(Editor editor) {
        final TransientArgs args = Transients.args(editor, ID);
        final Set<FetchOption> options = EnumSet.noneOf(FetchOption.class);
        if (args.isOn("--prune"))
            options.add(FetchOption.PRUNE);
        if (args.isOn("--tags"))
            options.add(FetchOption.TAGS);
        return options;
    }
}
