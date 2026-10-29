/*
 * ParentCommands.java
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.armedbear.j.Editor;
import org.armedbear.j.Finders;
import org.armedbear.j.LocationBar;
import org.armedbear.j.util.Background;
import org.armedbear.j.vcs.CommandLog;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.git.GitSnapshot;
import org.armedbear.j.vcs.log.LogRef;
import org.armedbear.j.vcs.log.LogRefTip;

/**
 * Setting the branch's parent, the branch it is to be merged into: the
 * only thing in j that writes it. A remote's branch is written by its
 * bare name, as GitHub's CLI takes it.
 */
public final class ParentCommands {
    private ParentCommands() {}

    /** The parent asked for from a list of the branches. */
    public static void gitSetParent(Editor editor) {
        gitSetParent(editor, null);
    }

    /** The parent set to the branch named, or with "-" its record removed. */
    public static void gitSetParent(Editor editor, String name) {
        final VcsRepository r = repository(editor);
        if (r == null) {
            editor.status("Not in a repository");
            return;
        }
        final String arg = name == null || name.isBlank() ? null : name.strip();
        Background.then("vcs parent branches", () -> branches(r), branches -> {
            if (branches == null) {
                editor.status("Can't read the branches: see the log");
                return;
            }
            if (branches.branch() == null) {
                editor.status("Not on a branch");
                return;
            }
            if ("-".equals(arg)) {
                set(editor, r, branches.branch(), null);
                return;
            }
            if (arg != null) {
                final String recorded = recorded(arg, branches.names());
                if (recorded == null)
                    editor.status("No branch " + arg);
                else
                    set(editor, r, branches.branch(), recorded);
                return;
            }
            if (branches.names().isEmpty()) {
                editor.status("There is no other branch");
                return;
            }
            Finders.choose(
                editor,
                LocationBar.PROMPT_BRANCH,
                "gitSetParent.branch",
                List.copyOf(branches.names().keySet()),
                picked -> set(editor, r, branches.branch(), branches.names().get(picked)));
        });
    }

    /**
     * The branch checked out, and the others, local and remote, each by the
     * name it is listed under and the name it is recorded as.
     */
    record Branches(String branch, Map<String, String> names) {}

    static Branches branches(VcsRepository r) {
        final Snapshot s = r.snapshotNow();
        final String branch = s instanceof GitSnapshot g ? g.branch() : null;
        final List<String> remotes = r.backend().readRemotes(r);
        final Map<String, String> names = new LinkedHashMap<>();
        for (LogRefTip t : r.backend().readRefTips(r)) {
            final String n = t.ref().name();
            if (t.ref().kind() == LogRef.Kind.BRANCH && !n.equals(branch))
                names.put(n, n);
            else if (t.ref().kind() == LogRef.Kind.REMOTE && !n.endsWith("/HEAD")) {
                final String remote = FetchCommands.remoteOf(n, remotes);
                // Not its own copy on a remote.
                if (remote != null && !n.substring(remote.length() + 1).equals(branch))
                    names.put(n, n.substring(remote.length() + 1));
            }
        }
        return new Branches(branch, names);
    }

    /** The name name is recorded as, listed or bare; null for no branch. */
    static String recorded(String name, Map<String, String> names) {
        if (names.containsKey(name))
            return names.get(name);
        // A remote's branch by its bare name.
        return names.containsValue(name) ? name : null;
    }

    private static void set(Editor editor, VcsRepository r, String branch, String parent) {
        Background.then("vcs set parent", () -> r.backend().setParent(r, branch, parent), result -> {
            if (result == null)
                editor.status(r.backend().name() + " can't record a parent");
            else if (!result.succeeded()) {
                editor.status("Setting the parent failed: " + lastLine(result));
                if (editor.getFrame() != null)
                    CommandLog.vcsCommandLog();
            } else
                editor.status(
                    parent == null
                            ? "The recorded parent of " + branch + " is removed"
                            : "The parent of " + branch + " is " + parent);
            r.invalidate();
            r.refresh();
        });
    }

    private static String lastLine(VcsResult result) {
        return result.stderr().strip().lines().reduce((a, b) -> b).orElse("");
    }

    // The status's repository at the caret, or the buffer's.
    private static VcsRepository repository(Editor editor) {
        final VcsStatusBuffer status = VcsStatusMode.owner(editor);
        if (status != null
                && editor.getBuffer() == status
                && editor.getDotLine() instanceof StatusLine s
                && s.repository() != null)
            return s.repository();
        final List<VcsRepository> rs = VcsStatusMode.repositoriesFor(editor.getBuffer());
        return rs.isEmpty() ? null : rs.get(0);
    }
}
