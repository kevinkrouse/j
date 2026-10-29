/*
 * GitRelations.java
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

package org.armedbear.j.vcs.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.armedbear.j.Log;
import org.armedbear.j.vcs.Snapshot.Relation;
import org.armedbear.j.vcs.VcsResult;

/**
 * A branch's push target and parent, read after the status, which has its
 * upstream. The parent is the first of GIT-TOOLING.md's "Parent branch"
 * that names a branch there is: no key of j's own.
 */
final class GitRelations {
    private static final String HEADS = "refs/heads/";
    private static final String REMOTES = "refs/remotes/";
    private static final String ORIGIN = "origin/";
    private static final String ORIGIN_HEAD = "refs/remotes/origin/HEAD";

    private GitRelations() {}

    // A parent's name as some record has it, and the record; the
    // reflog's name, null, is read when it is reached.
    private record Candidate(String name, String source) {}

    // A ref for-each-ref listed: its push target and how far it is, and
    // the ref it points at, for origin/HEAD.
    private record Ref(String push, String pushTrack, String symref) {}

    static GitSnapshot read(Path root, GitDirs dirs, GitSnapshot s) {
        final String branch = s.branch();
        if (branch == null || s.head() == null)
            return s;
        final Map<String, String> config = config(root);
        final String gh = config.get("branch." + branch + ".gh-merge-base");
        final String town = config.get("git-town-branch." + branch + ".parent");
        final String machete = dirs == null ? null : machete(dirs.commonDir().resolve("machete"), branch);
        final String vscode = config.get("branch." + branch + ".vscode-merge-base");
        final Relation upstream = s.upstream() == null || s.upstream().gone() ? null : s.upstream();

        final Set<String> listed =
                new LinkedHashSet<>(List.of(HEADS + branch, ORIGIN_HEAD, HEADS + "main", HEADS + "master"));
        for (String name : new String[] { gh, town, machete, vscode, upstream == null ? null : upstream.name() })
            if (name != null && !name.isBlank())
                listed.addAll(patterns(name.strip()));
        final Map<String, Ref> refs = refs(root, listed);
        final Ref own = refs.get(HEADS + branch);
        Relation push = null;
        if (own != null && !own.push().isEmpty()) {
            final int[] ab = track(own.pushTrack());
            push = new Relation(own.push(), null, ab[0], ab[1]);
        }

        final List<Candidate> candidates = new ArrayList<>();
        add(candidates, gh, "gh-merge-base");
        // The upstream unless the branch pushes to it: then it is the
        // branch's own copy.
        if (upstream != null && (push == null || !push.name().equals(upstream.name())))
            add(candidates, upstream.name(), "upstream");
        add(candidates, town, "git-town");
        add(candidates, machete, "machete");
        add(candidates, vscode, "vscode");
        candidates.add(new Candidate(null, "reflog")); // Read only if it is reached.
        final Ref originHead = refs.get(ORIGIN_HEAD);
        if (originHead != null && !originHead.symref().isEmpty())
            add(candidates, originHead.symref(), "default");
        add(candidates, "main", "default");
        add(candidates, "master", "default");

        for (Candidate c : candidates) {
            final String name = c.name() != null ? c.name() : reflog(root, branch);
            if (name == null)
                continue;
            final List<String> more = patterns(name);
            more.removeAll(listed);
            if (!more.isEmpty()) {
                listed.addAll(more);
                refs.putAll(refs(root, new LinkedHashSet<>(more)));
            }
            final String ref = resolve(name, refs);
            if (ref == null || ref.equals(HEADS + branch))
                continue;
            return s.with(push, parent(root, shortName(ref), ref, c.source(), upstream, push));
        }
        return s.with(push, null);
    }

    private static void add(List<Candidate> candidates, String name, String source) {
        if (name != null && !name.isBlank())
            candidates.add(new Candidate(name.strip(), source));
    }

    // The refs a branch's name could be.
    private static List<String> patterns(String name) {
        if (name.startsWith("refs/"))
            return new ArrayList<>(List.of(name));
        return new ArrayList<>(List.of(HEADS + name, REMOTES + name, REMOTES + ORIGIN + name));
    }

    // The full name of the branch name names, or null if there is none:
    // a local branch before a remote one, and a bare name, as GitHub's CLI
    // records one, origin's.
    private static String resolve(String name, Map<String, Ref> refs) {
        if (name.startsWith("refs/"))
            return refs.containsKey(name) ? name : null;
        for (String ref : patterns(name))
            if (refs.containsKey(ref))
                return ref;
        return null;
    }

    private static String shortName(String ref) {
        if (ref.startsWith(HEADS))
            return ref.substring(HEADS.length());
        if (ref.startsWith(REMOTES))
            return ref.substring(REMOTES.length());
        return ref;
    }

    // The parent with its counts: the upstream's or push target's when it
    // is one of them, else counted; null if git can't.
    private static Relation parent(
            Path root,
            String name,
            String ref,
            String source,
            Relation upstream,
            Relation push) {
        if (upstream != null && upstream.name().equals(name))
            return new Relation(name, source, upstream.ahead(), upstream.behind());
        if (push != null && !push.gone() && push.name().equals(name))
            return new Relation(name, source, push.ahead(), push.behind());
        final VcsResult r = GitCli.run(GitCli.read(root, "rev-list", "--left-right", "--count", "HEAD..." + ref));
        if (!r.succeeded())
            return null;
        final String[] f = r.out().strip().split("\\s+");
        if (f.length != 2)
            return null;
        try {
            return new Relation(name, source, Integer.parseInt(f[0]), Integer.parseInt(f[1]));
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    // The branch and remote config there is, by key as git prints it:
    // section and name lower case, the branch's name as it is.
    private static Map<String, String> config(Path root) {
        final Map<String, String> config = new HashMap<>();
        final VcsResult r =
                GitCli.run(GitCli.read(root, "config", "-z", "--get-regexp", "^(branch|git-town-branch)\\."));
        if (!r.succeeded())
            return config; // None.
        for (String entry : r.out().split("\0")) {
            final int nl = entry.indexOf('\n');
            if (nl > 0)
                config.putIfAbsent(entry.substring(0, nl), entry.substring(nl + 1));
        }
        return config;
    }

    // The refs of patterns there are; a pattern also matches the refs
    // under it, which are left out.
    private static Map<String, Ref> refs(Path root, Set<String> patterns) {
        final List<String> args = new ArrayList<>(
                List.of("for-each-ref", "--format=%(refname)%00%(push:short)%00%(push:track)%00%(symref:short)"));
        args.addAll(patterns);
        final Map<String, Ref> refs = new HashMap<>();
        final VcsResult r = GitCli.run(GitCli.read(root, args.toArray(new String[0])));
        if (!r.succeeded())
            return refs;
        for (String line : r.out().split("\n")) {
            final String[] f = line.split("\0", -1);
            if (f.length == 4 && patterns.contains(f[0]))
                refs.put(f[0], new Ref(f[1], f[2], f[3]));
        }
        return refs;
    }

    // "[ahead 1, behind 2]", "[ahead 1]", "" or "[gone]", as {ahead, behind}.
    static int[] track(String track) {
        if (track.equals("[gone]"))
            return new int[] { -1, -1 };
        int ahead = 0;
        int behind = 0;
        final String s = track.replace("[", "").replace("]", "");
        for (String part : s.split(",")) {
            final String[] f = part.strip().split(" ");
            if (f.length != 2)
                continue;
            try {
                if (f[0].equals("ahead"))
                    ahead = Integer.parseInt(f[1]);
                else if (f[0].equals("behind"))
                    behind = Integer.parseInt(f[1]);
            }
            catch (NumberFormatException e) {
                // Left at 0.
            }
        }
        return new int[] { ahead, behind };
    }

    /** branch's parent in a git-machete file: the nearest line above it indented less. */
    static String machete(Path file, String branch) {
        if (!Files.isRegularFile(file))
            return null;
        final List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            Log.debug(e);
            return null;
        }
        // The branches above this line, each indented more than the last.
        final ArrayDeque<Line> above = new ArrayDeque<>();
        for (String line : lines) {
            final String stripped = line.strip();
            if (stripped.isEmpty())
                continue;
            final int indent = line.length() - line.stripLeading().length();
            while (!above.isEmpty() && above.peek().indent() >= indent)
                above.pop();
            final String name = stripped.split("\\s+", 2)[0];
            if (name.equals(branch))
                return above.isEmpty() ? null : above.peek().name();
            above.push(new Line(indent, name));
        }
        return null;
    }

    private record Line(int indent, String name) {}

    // The branch it was made from, as its reflog says: "branch: Created
    // from X", or when X is HEAD, the checkout that first moved to it.
    private static String reflog(Path root, String branch) {
        final String created = oldest(root, HEADS + branch, "branch: Created from ", "");
        if (created == null || !created.equals("HEAD"))
            return created;
        return oldest(root, "HEAD", "checkout: moving from ", " to " + branch);
    }

    // What is between prefix and end in ref's oldest reflog entry that has
    // them, or null.
    private static String oldest(Path root, String ref, String prefix, String end) {
        final VcsResult r = GitCli
                .run(GitCli.read(root, "reflog", "show", "--format=%gs", "-F", "--grep-reflog=" + prefix, ref, "--"));
        if (!r.succeeded())
            return null;
        final String[] lines = r.out().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            final String line = lines[i];
            if (line.startsWith(prefix) && line.endsWith(end) && line.length() > prefix.length() + end.length())
                return line.substring(prefix.length(), line.length() - end.length());
        }
        return null;
    }
}
