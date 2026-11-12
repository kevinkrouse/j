/*
 * SvnStatus.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.svn;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import org.armedbear.j.Log;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.Bases;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.diff.DiffParser;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.status.StatusData;
import org.armedbear.j.vcs.status.StatusData.Field;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Item;
import org.armedbear.j.vcs.status.StatusData.Role;
import org.armedbear.j.vcs.status.StatusData.Section;
import org.w3c.dom.Element;

/** A working copy's changes and their diffs, from svn status --xml and svn diff. */
final class SvnStatus {
    /** The section of changes in no changelist. */
    static final String WORKING = "working";
    static final String UNTRACKED = "untracked";
    // Before a changelist's name in its section's id.
    private static final String CHANGELIST = "changelist|";

    /** The svn changelist section id is of, or null. */
    static String changelist(String section) {
        return section.startsWith(CHANGELIST) ? section.substring(CHANGELIST.length()) : null;
    }

    // Paths to a command line, which has a limit.
    static final int BATCH = 100;

    private SvnStatus() {}

    static Snapshot readSnapshot(VcsRepository repository) {
        final VcsResult r = SvnCli.run(SvnCli.read(repository.root(), "status", "--xml", "--ignore-externals"));
        if (!r.succeeded())
            return Snapshot.failed(r.lastError());
        try {
            return parse(r.stdout(), repository.root());
        }
        catch (Exception e) {
            Log.debug(e);
            return Snapshot.failed("Can't read svn status: " + e.getMessage());
        }
    }

    /** svn status --xml output, run at root, as a snapshot. */
    static SvnSnapshot parse(byte[] xml, Path root) throws Exception {
        final List<FileChange> changes = new ArrayList<>();
        final Map<String, String> changelists = new HashMap<>();
        final Element status = SvnCli.xml(xml).getDocumentElement();
        for (Element target : SvnCli.children(status, "target"))
            for (Element entry : SvnCli.children(target, "entry")) {
                final FileChange c = change(entry, root);
                if (c != null)
                    changes.add(c);
            }
        for (Element list : SvnCli.children(status, "changelist"))
            for (Element entry : SvnCli.children(list, "entry")) {
                final FileChange c = change(entry, root);
                if (c != null) {
                    changes.add(c);
                    changelists.put(c.path(), list.getAttribute("name"));
                }
            }
        return new SvnSnapshot(changes, changelists);
    }

    // An entry's change, its code svn's word for it; null for one left out.
    private static FileChange change(Element entry, Path root) {
        final List<Element> ws = SvnCli.children(entry, "wc-status");
        if (ws.isEmpty())
            return null;
        final Element s = ws.get(0);
        String path = entry.getAttribute("path").replace('\\', '/');
        final String item = s.getAttribute("item");
        final String props = s.getAttribute("props");
        if (s.getAttribute("tree-conflicted").equals("true"))
            return conflicted(path, "tree-conflicted");
        // Text and properties both: svn resolve would take a side of both.
        if (item.equals("conflicted") && props.equals("conflicted"))
            return conflicted(path, "text-props-conflicted");
        if (item.equals("conflicted"))
            return conflicted(path, "conflicted");
        if (props.equals("conflicted"))
            return conflicted(path, "props-conflicted");
        final Kind kind = switch (item) {
            case "modified", "replaced" -> Kind.MODIFIED;
            case "added" -> Kind.ADDED;
            case "deleted", "missing" -> Kind.DELETED;
            case "unversioned" -> Kind.UNTRACKED;
            case "normal" -> props.equals("modified") ? Kind.MODIFIED : null;
            // ignored, external, obstructed, incomplete, none.
            default -> null;
        };
        if (kind == null)
            return null;
        // A directory, untracked, as git names one.
        if (kind == Kind.UNTRACKED && !path.endsWith("/") && Files.isDirectory(root.resolve(path)))
            path += "/";
        return new FileChange(path, null, null, kind, item.equals("normal") ? "props" : item);
    }

    private static FileChange conflicted(String path, String code) {
        return new FileChange(path, null, null, Kind.CONFLICTED, code, FileChange.Conflict.BOTH_MODIFIED);
    }

    static StatusData read(VcsRepository repository, BiPredicate<String, String> wantsDiff, Whitespace ignored) {
        final Snapshot snapshot = repository.snapshotNow();
        if (snapshot.error() != null)
            return StatusData.failed(snapshot.error());
        final List<FileChange> unmerged = new ArrayList<>();
        final List<Item> untracked = new ArrayList<>();
        // The changes in no changelist, then each list's, by name.
        final Map<String, List<FileChange>> working = new TreeMap<>();
        working.put(WORKING, new ArrayList<>());
        for (FileChange c : snapshot.changes())
            if (c.isConflicted())
                unmerged.add(c);
            else if (c.isUntracked())
                untracked.add(new FileItem(c, '?', false, null));
            else {
                final String list = snapshot instanceof SvnSnapshot svn ? svn.changelist(c.path()) : null;
                working.computeIfAbsent(list == null ? WORKING : CHANGELIST + list, k -> new ArrayList<>()).add(c);
            }
        final List<String> wanted = new ArrayList<>();
        for (FileChange c : unmerged)
            if (wantsDiff.test(StatusData.UNMERGED, c.path()))
                wanted.add(c.path());
        for (Map.Entry<String, List<FileChange>> e : working.entrySet())
            for (FileChange c : e.getValue())
                if (wantsDiff.test(e.getKey(), c.path()))
                    wanted.add(c.path());
        final Map<String, FileDiff> diffs = new HashMap<>();
        final Set<String> failed = new HashSet<>();
        readDiffs(repository, snapshot, wanted, ignored, diffs, failed);
        final List<Section> sections = new ArrayList<>();
        if (!unmerged.isEmpty())
            sections.add(new Section(StatusData.UNMERGED, "Unmerged", Role.UNMERGED, items(unmerged, diffs, failed)));
        if (!untracked.isEmpty())
            sections.add(new Section(UNTRACKED, "Untracked files", Role.UNTRACKED, untracked));
        if (!working.get(WORKING).isEmpty())
            sections.add(new Section(WORKING, "Changes", Role.WORKING, items(working.get(WORKING), diffs, failed)));
        for (Map.Entry<String, List<FileChange>> e : working.entrySet())
            if (!e.getKey().equals(WORKING))
                sections.add(
                    new Section(e.getKey(), changelist(e.getKey()), Role.WORKING, items(e.getValue(), diffs, failed)));
        return new StatusData(header(repository), null, sections, null);
    }

    private static List<Item> items(List<FileChange> changes, Map<String, FileDiff> diffs, Set<String> failed) {
        final List<Item> items = new ArrayList<>();
        for (FileChange c : changes)
            items.add(
                new FileItem(c,
                        c.isConflicted() ? 'U' : c.unstaged().letter(),
                        false,
                        diffs.get(c.path()),
                        failed.contains(c.path()) ? "Could not read the file's diff" : null));
        return items;
    }

    // The working copy's URL and revision, from svn info.
    private static List<Field> header(VcsRepository repository) {
        final List<Field> header = new ArrayList<>();
        final VcsResult r = SvnCli.run(SvnCli.read(repository.root(), "info", "--xml", "--", "."));
        if (r.succeeded())
            try {
                final Element entry = SvnCli.children(SvnCli.xml(r.stdout()).getDocumentElement(), "entry").get(0);
                header.add(new Field("URL", SvnCli.childText(entry, "url")));
                header.add(new Field("Revision", entry.getAttribute("revision")));
            }
            catch (Exception e) {
                Log.debug(e);
            }
        return header;
    }

    // The diffs of paths, by path, into diffs, in batches; a batch svn
    // fails on into failed. A path svn gives none of: no change to its
    // text, or none but to whitespace if ignoring it.
    private static void readDiffs(
            VcsRepository repository,
            Snapshot snapshot,
            List<String> paths,
            Whitespace ignored,
            Map<String, FileDiff> diffs,
            Set<String> failed) {
        for (int i = 0; i < paths.size(); i += BATCH) {
            final List<String> batch = paths.subList(i, Math.min(paths.size(), i + BATCH));
            final List<FileDiff> read = diff(repository, snapshot, batch, ignored);
            if (read == null) {
                failed.addAll(batch);
                continue;
            }
            for (FileDiff d : read)
                diffs.put(d.path(), d);
            for (String p : batch)
                diffs.putIfAbsent(
                    p,
                    svnIgnoring(ignored) != Whitespace.NONE ? FileDiff.ONLY_WHITESPACE : FileDiff.EMPTY);
        }
    }

    // What svn ignores of ignored: none of trailing white space alone,
    // which it has no option for. Its -b, unlike git's, sees white space
    // added at a line's end.
    private static Whitespace svnIgnoring(Whitespace ignored) {
        return ignored == Whitespace.TRAILING ? Whitespace.NONE : ignored;
    }

    /** The working copy's diffs of paths against BASE, in batches; null if svn fails on any. */
    static List<FileDiff> diffs(VcsRepository repository, List<String> paths, Whitespace ignored) {
        final Snapshot snapshot = repository.snapshot();
        final List<FileDiff> out = new ArrayList<>();
        for (int i = 0; i < paths.size(); i += BATCH) {
            final List<FileDiff> read =
                    diff(repository, snapshot, paths.subList(i, Math.min(paths.size(), i + BATCH)), ignored);
            if (read == null)
                return null;
            out.addAll(read);
        }
        return out;
    }

    // paths' diffs against BASE, property changes left out, a copy as an
    // add; a replaced file's in svn's own format, as a change; a missing file's, which svn doesn't give, as
    // its deletion. Null if svn fails.
    private static List<FileDiff> diff(
            VcsRepository repository,
            Snapshot snapshot,
            List<String> paths,
            Whitespace ignored) {
        final List<String> missing = new ArrayList<>();
        final List<String> replaced = new ArrayList<>();
        final List<String> rest = new ArrayList<>();
        for (String p : paths) {
            final FileChange c = snapshot == null ? null : snapshot.change(p);
            final String code = c == null ? "" : c.code();
            (code.equals("missing") ? missing : code.equals("replaced") ? replaced : rest).add(p);
        }
        final List<FileDiff> out = new ArrayList<>();
        if (!svnDiff(repository, rest, true, ignored, out) || !svnDiff(repository, replaced, false, ignored, out))
            return null;
        for (String p : missing) {
            // None of a missing directory, which has no text.
            final byte[] base = base(repository, p);
            if (base != null)
                out.addAll(DiffParser.parse(deletion(p, base)));
        }
        return out;
    }

    // svn diff of paths, with git's headers if git, into out; whether it ran.
    private static boolean svnDiff(
            VcsRepository repository,
            List<String> paths,
            boolean git,
            Whitespace ignored,
            List<FileDiff> out) {
        if (paths.isEmpty())
            return true;
        final List<String> args = new ArrayList<>(List.of("diff", "--internal-diff", "--ignore-properties"));
        // A copy as an add, without which svn fails on one; but a replaced
        // file then comes as a deletion and an add, as with --git.
        if (git)
            args.addAll(List.of("--git", "--show-copies-as-adds"));
        // -x always, so the config's diff-extensions don't apply.
        args.add("-x");
        args.add(switch (svnIgnoring(ignored)) {
            case NONE, TRAILING -> "-u";
            case AMOUNT -> "-b";
            case ALL -> "-w";
        });
        args.add("--");
        // diff takes a working copy's path as it is, with no peg revision.
        args.addAll(paths);
        final VcsResult r = SvnCli.run(SvnCli.read(repository.root(), args.toArray(new String[0])));
        if (!r.succeeded())
            return false;
        // A file whose changes were ignored has a header alone.
        for (FileDiff d : DiffParser.parse(r.stdout()))
            if (!d.hunks().isEmpty() || d.isBinary() || d.isNew() || d.isDeleted())
                out.add(d);
        return true;
    }

    /** A git-style diff deleting path, whose text was base. */
    static byte[] deletion(String path, byte[] base) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] p = path.getBytes(StandardCharsets.UTF_8);
        write(out, "diff --git a/");
        out.writeBytes(p);
        write(out, " b/");
        out.writeBytes(p);
        write(out, "\ndeleted file mode 100644\n");
        boolean binary = false;
        for (byte b : base)
            binary |= b == 0;
        if (binary) {
            write(out, "Binary files a/");
            out.writeBytes(p);
            write(out, " and /dev/null differ\n");
            return out.toByteArray();
        }
        if (base.length == 0)
            return out.toByteArray();
        final List<int[]> lines = new ArrayList<>();
        for (int from = 0, i = 0; i <= base.length; i++)
            if (i == base.length || base[i] == '\n') {
                if (i > from || i < base.length)
                    lines.add(new int[] { from, i });
                from = i + 1;
            }
        write(out, "--- a/");
        out.writeBytes(p);
        write(out, "\n+++ /dev/null\n@@ -1" + (lines.size() == 1 ? "" : "," + lines.size()) + " +0,0 @@\n");
        for (int[] l : lines) {
            out.write('-');
            out.write(base, l[0], l[1] - l[0]);
            out.write('\n');
        }
        if (base[base.length - 1] != '\n')
            write(out, "\\ No newline at end of file\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    // path's text as last updated, or null if svn has none.
    private static byte[] base(VcsRepository repository, String path) {
        final VcsResult r = SvnCli.run(SvnCli.read(repository.root(), "cat", "-r", "BASE", "--", SvnCli.target(path)));
        return r.succeeded() ? r.stdout() : null;
    }

    /**
     * What path's change marks are against: its text as last updated, for
     * both, as there is no index; none committed for an added file, and
     * neither for an unversioned one. Null for a conflict, or if svn fails.
     */
    static Bases bases(VcsRepository repository, String path) {
        final Snapshot snapshot = repository.snapshot();
        final FileChange c = snapshot == null ? null : snapshot.change(path);
        if (c != null && c.isConflicted())
            return null;
        if (c != null && c.isUntracked())
            return new Bases(null, null);
        // A replaced file has no text svn keeps until it is committed.
        if (c != null && (c.unstaged() == Kind.ADDED || "replaced".equals(c.code())))
            return new Bases(null, new byte[0]);
        final VcsResult r = SvnCli.run(SvnCli.read(repository.root(), "cat", "-r", "BASE", "--", SvnCli.target(path)));
        if (r.succeeded())
            return new Bases(r.stdout(), r.stdout());
        // Not under version control: unversioned, or ignored.
        if (r.stderr().contains("W155010") || r.stderr().contains("E155010") || r.stderr().contains("E200009"))
            return new Bases(null, null);
        return null;
    }
}
