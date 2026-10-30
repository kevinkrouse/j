/*
 * BranchCommands.java
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.armedbear.j.ConfirmDialog;
import org.armedbear.j.Editor;
import org.armedbear.j.Finders;
import org.armedbear.j.InputDialog;
import org.armedbear.j.LocationBar;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.Transient.Switch;
import org.armedbear.j.transients.TransientPopup;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.Repositories;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.log.LogBuffer;
import org.armedbear.j.vcs.log.LogCommit;
import org.armedbear.j.vcs.log.LogRef;
import org.armedbear.j.vcs.log.LogRefTip;

/**
 * The branch popup (b) and its commands, after Neogit's: each asks for
 * the branch, or where a new one starts, from a list, the ref at the
 * caret first: in the status or the log, a commit's branch or the commit;
 * in the log's refs tree, the ref clicked. The commands take that ref and
 * the repository's root, "main /path/to/repo", as the popup gives them,
 * "-" for no ref; or the ref alone, in the status's or log's repository.
 */
public final class BranchCommands {
    private BranchCommands() {}

    static final String ID = "vcs.branch";

    // The unmerged commits a delete names at most.
    private static final int SHOWN = 10;

    /** Asks yes or no; replaced in tests. */
    interface Confirm {
        boolean test(Editor editor, String text, String title);
    }

    static Confirm confirm =
            (editor, text, title) -> ConfirmDialog.showConfirmDialog(editor, text, title) == RESPONSE_YES;

    /** Asks for a name, offering one; null for none. Replaced in tests. */
    interface Ask {
        String ask(Editor editor, String prompt, String title, String offered);
    }

    static Ask ask = InputDialog::showInputDialog;

    /** A list's label, its empty field's hint, and whether what is typed matching none is taken. */
    record Question(int prompt, String hint, boolean typed) {}

    static final Question START = new Question(LocationBar.PROMPT_START, "select a branch or enter a ref", true);
    static final Question CHECKOUT =
            new Question(LocationBar.PROMPT_CHECKOUT, "select a branch or tag, or enter a commit", true);
    static final Question CHECKOUT_LOCAL = new Question(LocationBar.PROMPT_CHECKOUT, "select a local branch", false);
    static final Question CHECKOUT_RECENT =
            new Question(LocationBar.PROMPT_CHECKOUT, "select a branch checked out before", false);
    static final Question MERGE = new Question(LocationBar.PROMPT_MERGE, "select a branch or enter a ref", true);
    static final Question RENAME = new Question(LocationBar.PROMPT_RENAME, "select the branch to rename", false);
    static final Question DELETE = new Question(LocationBar.PROMPT_DELETE, "select the branch to delete", false);

    // In every repository, a ref by a name one has, as listed: nothing
    // typed, a revision being one repository's.
    static final Question START_EACH = new Question(LocationBar.PROMPT_START, "select a branch or tag", false);
    static final Question CHECKOUT_EACH = new Question(LocationBar.PROMPT_CHECKOUT, "select a branch or tag", false);
    static final Question MERGE_EACH = new Question(LocationBar.PROMPT_MERGE, "select a branch or tag", false);

    static Question parent(String branch) {
        return new Question(LocationBar.PROMPT_PARENT, "select the parent of " + branch, false);
    }

    /** Asks q, from names listed in their order; replaced in tests. */
    interface Choose {
        void choose(Editor editor, Question q, String history, List<String> names, Consumer<String> chosen);
    }

    static Choose choose = (editor, q, history, names, chosen) -> Finders
            .choose(editor, q.prompt(), q.hint(), q.typed(), history, names, chosen);

    /**
     * A repository's refs, with the ref at the caret, null for none, and
     * the branch checked out, null when HEAD is detached.
     */
    record Context(VcsRepository repository, String at, String current, List<LogRefTip> tips) {
        /** The names of kind's refs, by name; not a remote's HEAD. */
        List<String> names(LogRef.Kind kind) {
            final List<String> out = new ArrayList<>();
            for (LogRefTip t : tips)
                if (t.ref().kind() == kind && !(kind == LogRef.Kind.REMOTE && t.ref().name().endsWith("/HEAD")))
                    out.add(t.ref().name());
            out.sort(null);
            return out;
        }

        /** The branches, local and the remotes', then the tags. */
        List<String> refs() {
            final List<String> out = new ArrayList<>(names(LogRef.Kind.BRANCH));
            out.addAll(names(LogRef.Kind.REMOTE));
            out.addAll(names(LogRef.Kind.TAG));
            return out;
        }

        /**
         * names without the branch checked out, the ref at the caret first
         * if it is one of them, or with any, whatever it is.
         */
        List<String> offered(List<String> names, boolean any) {
            final Set<String> out = new LinkedHashSet<>();
            if (at != null && (any || names.contains(at)))
                out.add(at);
            out.addAll(names);
            if (current != null)
                out.remove(current);
            return List.copyOf(out);
        }

        /** The ref named: a local branch, else a remote's, else a tag, else a revision. */
        LogRef ref(String name) {
            for (LogRef.Kind kind : List.of(LogRef.Kind.BRANCH, LogRef.Kind.REMOTE, LogRef.Kind.TAG))
                if (names(kind).contains(name))
                    return new LogRef(name, kind, false);
            return new LogRef(name, LogRef.Kind.HEAD, false);
        }

        /** The local branches, and the remotes' that have none of their name. */
        List<String> locals() {
            final List<String> locals = names(LogRef.Kind.BRANCH);
            final List<String> out = new ArrayList<>(locals);
            for (String remote : names(LogRef.Kind.REMOTE))
                if (!locals.contains(bare(remote)))
                    out.add(remote);
            return out;
        }

        /** The local branches, and the remotes' that have none, by their bare names, as l with -a lists them. */
        List<String> localNames() {
            final Set<String> out = new TreeSet<>(names(LogRef.Kind.BRANCH));
            for (String remote : names(LogRef.Kind.REMOTE))
                out.add(bare(remote));
            return List.copyOf(out);
        }

        /** The local branch name, else a remote's of that bare name, origin's first; null for none. */
        String local(String name) {
            if (names(LogRef.Kind.BRANCH).contains(name))
                return name;
            final List<String> remotes = names(LogRef.Kind.REMOTE);
            if (remotes.contains("origin/" + name))
                return "origin/" + name;
            for (String remote : remotes)
                if (bare(remote).equals(name))
                    return remote;
            return null;
        }

        /** What checking out name checks out: a remote's branch with a local one of its name, that one. */
        LogRef checkout(String name) {
            final LogRef ref = ref(name);
            return ref.kind() == LogRef.Kind.REMOTE && names(LogRef.Kind.BRANCH).contains(bare(name))
                    ? new LogRef(bare(name), LogRef.Kind.BRANCH, false)
                    : ref;
        }

        /** Its commands' argument. */
        String argument() {
            return (at == null ? "-" : at) + " " + repository.root();
        }
    }

    /** The popup, its commands given the ref at the caret; with several repositories, -a. */
    static Transient popup(Context c, boolean several) {
        final String arg = " " + c.argument();
        final List<Group> groups = new ArrayList<>();
        if (several)
            groups.add(Group.of("Arguments", Switch.of("-a", EachRepository.ALL, "all repositories")));
        groups.addAll(
            List.of(
                Group.of(
                    "Checkout",
                    Command.of("b", "vcsBranchCheckout" + arg, "branch/revision"),
                    Command.of("l", "vcsBranchCheckoutLocal" + arg, "local branch"),
                    Command.of("r", "vcsBranchCheckoutRecent" + arg, "recent branch"),
                    Command.of("c", "vcsBranchCheckoutNew" + arg, "new branch")),
                Group.of("Create", Command.of("n", "vcsBranchCreate" + arg, "new branch")),
                Group.of(
                    "Do",
                    Command.of("m", "vcsBranchRename" + arg, "rename"),
                    Command.of("D", "vcsBranchDelete" + arg, "delete"),
                    c.current() == null
                            ? Command.unavailable("p", "parent, not on a branch")
                            : Command.of("p", "vcsBranchSetParent" + arg, "parent"))));
        return new Transient(ID, "Branch", "git.html#branches", groups);
    }

    /** The branch popup, the ref named or at the caret first in its lists. */
    public static void vcsBranchPopup(Editor editor, String arg) {
        final boolean several = EachRepository.several(editor);
        context(editor, arg, c -> TransientPopup.open(editor, popup(c, several)));
    }

    /** Checks out a branch, a remote's as a local one tracking it, or a tag or revision detached. */
    public static void vcsBranchCheckout(Editor editor, String arg) {
        if (AllBranchCommands.checkout(editor, at(editor, arg), false))
            return;
        context(
            editor,
            arg,
            c -> choose.choose(
                editor,
                CHECKOUT,
                "vcsBranch.checkout",
                c.offered(c.refs(), true),
                name -> switchTo(editor, c, name)));
    }

    /**
     * Checks out a local branch, or a remote's that has none of its name as
     * a new local one tracking it.
     */
    public static void vcsBranchCheckoutLocal(Editor editor, String arg) {
        if (AllBranchCommands.checkout(editor, at(editor, arg), true))
            return;
        context(
            editor,
            arg,
            c -> choose.choose(
                editor,
                CHECKOUT_LOCAL,
                "vcsBranch.checkout",
                c.offered(c.locals(), false),
                name -> switchTo(editor, c, name)));
    }

    /** Checks out a branch checked out before, the latest first. */
    public static void vcsBranchCheckoutRecent(Editor editor, String arg) {
        context(editor, arg, c -> {
            final VcsRepository r = c.repository();
            Background.then("vcs recent branches", () -> r.backend().readRecentBranches(r), recent -> {
                final List<String> locals = c.names(LogRef.Kind.BRANCH);
                final List<String> names = new ArrayList<>();
                for (String name : recent == null ? List.<String>of() : recent)
                    if (locals.contains(name) && !name.equals(c.current()))
                        names.add(name);
                if (names.isEmpty())
                    editor.status("No other branch was checked out");
                else
                    choose.choose(
                        editor,
                        CHECKOUT_RECENT,
                        "vcsBranch.checkout",
                        names,
                        name -> switchTo(editor, c, name));
            });
        });
    }

    /** A branch made where it is asked to start, and checked out. */
    public static void vcsBranchCheckoutNew(Editor editor, String arg) {
        if (AllBranchCommands.create(editor, at(editor, arg), true))
            return;
        context(editor, arg, c -> create(editor, c, true));
    }

    /** A branch made where it is asked to start. */
    public static void vcsBranchCreate(Editor editor, String arg) {
        if (AllBranchCommands.create(editor, at(editor, arg), false))
            return;
        context(editor, arg, c -> create(editor, c, false));
    }

    public static void vcsBranchRename(Editor editor, String arg) {
        if (AllBranchCommands.rename(editor, at(editor, arg)))
            return;
        context(editor, arg, c -> {
            final List<String> locals = c.names(LogRef.Kind.BRANCH);
            final Set<String> names = new LinkedHashSet<>();
            if (c.at() != null && locals.contains(c.at()))
                names.add(c.at());
            if (c.current() != null)
                names.add(c.current());
            names.addAll(locals);
            choose.choose(editor, RENAME, "vcsBranch.rename", List.copyOf(names), branch -> {
                final String name = asked(editor, "New name:", "Rename " + branch, branch);
                if (name == null || name.equals(branch))
                    return;
                final VcsRepository r = c.repository();
                write(
                    editor,
                    c,
                    "Renaming " + branch,
                    "rename branches",
                    () -> r.backend().renameBranch(r, branch, name),
                    result -> "Renamed " + branch + " to " + name);
            });
        });
    }

    /**
     * Deletes a local branch, not the one checked out, after asking, and
     * asking again, naming them, if it has commits on no other ref, which
     * go with it.
     */
    public static void vcsBranchDelete(Editor editor, String arg) {
        if (AllBranchCommands.delete(editor, at(editor, arg)))
            return;
        context(editor, arg, c -> {
            final List<String> names = c.offered(c.names(LogRef.Kind.BRANCH), false);
            if (names.isEmpty())
                editor.status("There is no other branch");
            else
                choose.choose(editor, DELETE, "vcsBranch.delete", names, branch -> delete(editor, c, branch));
        });
    }

    private static void delete(Editor editor, Context c, String branch) {
        final VcsRepository r = c.repository();
        String tip = "";
        for (LogRefTip t : c.tips())
            if (t.ref().kind() == LogRef.Kind.BRANCH && t.ref().name().equals(branch))
                tip = LogCommit.shortId(t.id());
        final String id = tip;
        Background.then("vcs branch unmerged", () -> r.backend().readOnlyOn(r, branch), only -> {
            if (!confirm.test(editor, "Delete branch " + branch + "?", "Delete Branch"))
                return;
            // Read: -D, the commits it alone has asked about; none lost
            // otherwise, as -d would refuse one merged elsewhere but not
            // into HEAD. Unread: -d, git's own check deciding.
            final boolean force = only != null;
            if (only != null && !only.isEmpty() && !confirm.test(editor, lost(branch, only), "Delete Branch"))
                return;
            write(
                editor,
                c,
                "Deleting " + branch,
                "delete branches",
                () -> r.backend().deleteBranch(r, branch, force),
                result -> "Deleted " + branch + " (" + id + "): git branch " + branch + " " + id + " brings it back");
        });
    }

    // The second question: the commits lost.
    static String lost(String branch, List<String> only) {
        final StringBuilder sb = new StringBuilder();
        sb.append(branch)
                .append(only.size() == 1 ? " has a commit" : " has " + only.size() + " commits")
                .append(" on no other branch or tag, lost with it:\n\n");
        for (String c : only.subList(0, Math.min(SHOWN, only.size())))
            sb.append("    ").append(c).append('\n');
        if (only.size() > SHOWN)
            sb.append("    … ").append(only.size() - SHOWN).append(" more\n");
        return sb.append("\nDelete it anyway?").toString();
    }

    /** Makes a branch, asked for, the parent of the one checked out; a remote's by its bare name. */
    public static void vcsBranchSetParent(Editor editor, String arg) {
        context(editor, arg, c -> {
            if (c.current() == null) {
                editor.status("Not on a branch");
                return;
            }
            final VcsRepository r = c.repository();
            Background.then("vcs parent branches", () -> ParentCommands.branches(r), branches -> {
                if (branches == null || branches.names().isEmpty()) {
                    editor.status("There is no other branch");
                    return;
                }
                final List<String> names = c.offered(List.copyOf(branches.names().keySet()), false);
                choose.choose(
                    editor,
                    parent(c.current()),
                    "gitSetParent.branch",
                    names,
                    picked -> ParentCommands.set(editor, r, c.current(), branches.names().get(picked)));
            });
        });
    }

    // Asks where it starts, the ref at the caret, else the branch checked
    // out, offered first, then its name, and makes it there.
    private static void create(Editor editor, Context c, boolean checkout) {
        final Set<String> starts = new LinkedHashSet<>();
        if (c.at() != null)
            starts.add(c.at());
        starts.add(c.current() != null ? c.current() : "HEAD");
        starts.addAll(c.refs());
        choose.choose(editor, START, "vcsBranch.start", List.copyOf(starts), start -> {
            final LogRef from = c.ref(start);
            // A remote's branch: its name offered.
            final String offered = from.kind() == LogRef.Kind.REMOTE ? bare(start) : "";
            final String title = (checkout ? "Create and Check Out" : "Create") + " a Branch from " + start;
            final String name = asked(editor, "Name:", title, offered);
            if (name == null)
                return;
            final VcsRepository r = c.repository();
            write(
                editor,
                c,
                "Creating " + name,
                "make branches",
                () -> r.backend().createBranch(r, name, from.fullName(), checkout),
                result -> "Created " + name + " from " + start + (checkout ? ", checked out" : ""));
        });
    }

    private static void switchTo(Editor editor, Context c, String name) {
        final VcsRepository r = c.repository();
        final LogRef to = c.checkout(name);
        write(
            editor,
            c,
            "Checking out " + name,
            "check out",
            () -> r.backend().switchTo(r, to),
            result -> said(result, "Checked out " + to.name()));
    }

    // A remote's branch's name without its remote's.
    static String bare(String remote) {
        return remote.substring(remote.indexOf('/') + 1);
    }

    // The context.

    /**
     * The repository and ref arg names, else at the caret, read with its
     * refs and given to then; the status bar says if there is none.
     */
    static void context(Editor editor, String arg, Consumer<Context> then) {
        final At at = arg == null || arg.isBlank() ? atCaret(editor) : named(editor, arg.strip());
        if (at == null) {
            editor.status("Not in a repository");
            return;
        }
        final VcsRepository r = at.repository();
        Background.then("vcs branch refs", () -> r.backend().readRefTips(r), tips -> {
            if (tips == null) {
                editor.status("Can't read the branches: see the log");
                return;
            }
            String current = null;
            for (LogRefTip t : tips)
                if (t.ref().kind() == LogRef.Kind.BRANCH && t.ref().head())
                    current = t.ref().name();
            then.accept(new Context(r, at.ref(), current, tips));
        });
    }

    private record At(VcsRepository repository, String ref) {}

    // The ref arg names, else the one at the caret; null for none.
    static String at(Editor editor, String arg) {
        final At at = arg == null || arg.isBlank() ? atCaret(editor) : named(editor, arg.strip());
        return at == null ? null : at.ref();
    }

    private static At named(Editor editor, String arg) {
        final int space = arg.indexOf(' ');
        final String ref = space < 0 ? arg : arg.substring(0, space);
        final VcsRepository r =
                space < 0 ? repository(editor) : Repositories.forDirectory(Path.of(arg.substring(space + 1)));
        return r == null ? null : new At(r, ref.equals("-") ? null : ref);
    }

    // In the log's list or on a status's commit, its ref or the commit;
    // else none.
    private static At atCaret(Editor editor) {
        if (editor.getBuffer() instanceof LogBuffer) {
            final LogCommit c = LogBuffer.commitAt(editor);
            if (c != null && c.repository() != null)
                return new At(c.repository(), refOf(c));
        }
        final VcsStatusBuffer status = VcsStatusMode.owner(editor);
        if (status != null
                && editor.getBuffer() == status
                && editor.getDot() != null
                && editor.getDotLine() instanceof StatusLine s
                && s.repository() != null) {
            final StatusLine c = VcsStatusBuffer.commitOf(s);
            final boolean commit = c != null && c.commit() != null && c.stash() == null;
            return new At(s.repository(), commit ? refOf(c.commit().hash(), c.commit().refs()) : null);
        }
        final VcsRepository r = repository(editor);
        return r == null ? null : new At(r, null);
    }

    // A log row's: a branch not checked out, else a remote's, a tag, the
    // branch checked out, or the commit.
    private static String refOf(LogCommit c) {
        return refOf(LogCommit.shortId(c.id()), c.refs());
    }

    /**
     * A commit's ref, by kind, whatever order git lists them in: a branch
     * but the one checked out, else a remote's branch, but a remote's
     * HEAD, else a tag, else that branch, else the commit.
     */
    static String refOf(String hash, List<LogRef> refs) {
        for (LogRef.Kind kind : List.of(LogRef.Kind.BRANCH, LogRef.Kind.REMOTE, LogRef.Kind.TAG))
            for (LogRef ref : refs)
                if (ref.kind() == kind && !ref.head() && !ref.name().endsWith("/HEAD"))
                    return ref.name();
        for (LogRef ref : refs)
            if (ref.kind() == LogRef.Kind.BRANCH)
                return ref.name();
        return hash;
    }

    // Writing.

    static void write(
            Editor editor,
            Context c,
            String doing,
            String cant,
            Supplier<VcsResult> write,
            Function<VcsResult, String> done) {
        StatusWrites.write(editor, c.repository(), doing, cant, write, result -> {
            LogBuffer.refreshAll(c.repository());
            return done.apply(result);
        }, result -> failed(doing, result));
    }

    /** What went wrong: a merge's conflict, or git's error, as changes in the way of a checkout. */
    static String failed(String doing, VcsResult result) {
        if (result.out().contains("CONFLICT"))
            return doing + " conflicted: the conflicts are in Unmerged";
        String why = "";
        for (String line : result.stderr().strip().split("\n"))
            if (line.startsWith("error: ") || line.startsWith("fatal: ")) {
                why = line.substring(line.indexOf(' ') + 1);
                break;
            }
        if (why.isEmpty())
            why = result.lastError();
        if (why.endsWith(":"))
            why = why.substring(0, why.length() - 1);
        return doing + " failed: " + (why.isEmpty() ? "exit code " + result.exitCode() : why);
    }

    // git's last word on a checkout, as "Switched to a new branch 'x'"; else plain.
    private static String said(VcsResult result, String plain) {
        final String last = result.lastError();
        return last.isEmpty() ? plain : last;
    }

    // A name asked for; null for none.
    private static String asked(Editor editor, String prompt, String title, String offered) {
        final String name = ask.ask(editor, prompt, title, offered);
        return name == null || name.isBlank() ? null : name.strip();
    }

    // The status's repository at the caret, or the log's, or the buffer's.
    private static VcsRepository repository(Editor editor) {
        final VcsRepository at = VcsStatusMode.repositoryAt(editor);
        if (at != null)
            return at;
        final List<VcsRepository> rs = VcsStatusMode.repositoriesFor(editor.getBuffer());
        return rs.isEmpty() ? null : rs.get(0);
    }
}
