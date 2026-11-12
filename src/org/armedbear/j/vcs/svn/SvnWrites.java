/*
 * SvnWrites.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.svn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.armedbear.j.Log;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.Snapshot;
import org.armedbear.j.vcs.VcsBackend.ChangeVersion;
import org.armedbear.j.vcs.VcsBackend.CommitPreview;
import org.armedbear.j.vcs.VcsBackend.FileVersion;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.VcsResult;
import org.w3c.dom.Element;

/** What j changes in a working copy: reverts, patches taken back out, and commits of whole files. */
final class SvnWrites {
    // The codes svn says a commit is out of date with, by access method and kind.
    private static final List<String> OUT_OF_DATE = List.of("E155011", "E160028", "E160024", "E170004");

    private SvnWrites() {}

    /**
     * Puts paths back as last updated: svn revert, an added file left
     * unversioned; the deepest first, as svn reverts an added directory
     * only after what is in it, and an added directory with all in it,
     * which is added too, as when it went to the trash whole.
     */
    static VcsResult discard(VcsRepository repository, List<String> given) {
        final List<String> paths = new ArrayList<>(given);
        paths.sort((a, b) -> Long.compare(depth(b), depth(a)));
        final List<String> addedDirectories = addedDirectories(repository, paths);
        paths.removeAll(addedDirectories);
        VcsResult last = revert(repository, paths, false);
        if (last != null && !last.succeeded())
            return last;
        final VcsResult dirs = revert(repository, addedDirectories, true);
        if (dirs != null)
            last = dirs;
        return last != null ? last : new VcsResult(null, 0, new byte[0], "", 0);
    }

    private static long depth(String path) {
        return path.chars().filter(c -> c == '/').count();
    }

    // svn revert of paths, in batches; null for none.
    private static VcsResult revert(VcsRepository repository, List<String> paths, boolean infinity) {
        VcsResult last = null;
        for (int from = 0; from < paths.size(); from += SvnStatus.BATCH) {
            final List<String> args = new ArrayList<>(List.of("revert"));
            if (infinity)
                args.addAll(List.of("--depth", "infinity"));
            args.add("--");
            for (String p : paths.subList(from, Math.min(from + SvnStatus.BATCH, paths.size())))
                args.add(SvnCli.target(p));
            last = SvnCli.run(SvnCli.write(repository.root(), args.toArray(new String[0])));
            if (!last.succeeded())
                return last;
        }
        return last;
    }

    // Those of paths that are directories scheduled to be added, as svn info has them.
    private static List<String> addedDirectories(VcsRepository repository, List<String> paths) {
        final List<String> out = new ArrayList<>();
        for (int from = 0; from < paths.size(); from += SvnStatus.BATCH) {
            final List<String> batch = paths.subList(from, Math.min(from + SvnStatus.BATCH, paths.size()));
            final List<String> args = new ArrayList<>(List.of("info", "--xml", "--"));
            for (String p : batch)
                args.add(SvnCli.target(p));
            final VcsResult r = SvnCli.run(SvnCli.read(repository.root(), args.toArray(new String[0])));
            try {
                for (Element e : SvnCli.children(SvnCli.xml(r.stdout()).getDocumentElement(), "entry")) {
                    final List<Element> wc = SvnCli.children(e, "wc-info");
                    if (e.getAttribute("kind").equals("dir")
                            && !wc.isEmpty()
                            && "add".equals(SvnCli.childText(wc.get(0), "schedule")))
                        out.add(e.getAttribute("path").replace('\\', '/'));
                }
            }
            catch (Exception e) {
                // No info, as for a path not versioned: reverted as given.
                Log.debug(e);
            }
        }
        out.retainAll(paths);
        return out;
    }

    /** Puts paths in changelist name, or with name null in none: svn changelist. */
    static VcsResult changelist(VcsRepository repository, List<String> paths, String name) {
        VcsResult last = null;
        for (int from = 0; from < paths.size(); from += SvnStatus.BATCH) {
            // The name after --, as one may begin with -.
            final List<String> args = new ArrayList<>(List.of("changelist", "-q"));
            if (name == null)
                args.addAll(List.of("--remove", "--"));
            else
                args.addAll(List.of("--", name));
            for (String p : paths.subList(from, Math.min(from + SvnStatus.BATCH, paths.size())))
                args.add(SvnCli.target(p));
            last = SvnCli.run(SvnCli.write(repository.root(), args.toArray(new String[0])));
            if (!last.succeeded())
                return last;
        }
        return last != null ? last : new VcsResult(null, 0, new byte[0], "", 0);
    }

    /**
     * Applies patch to the working copy, or with reverse takes it out, with
     * svn patch; null for the index, which svn hasn't. svn patch applies
     * what it can and says so with success, so it is tried first.
     */
    static VcsResult apply(VcsRepository repository, byte[] patch, boolean index, boolean reverse) {
        if (index)
            return null;
        final VcsResult dry = patch(repository, patch, reverse, true);
        if (dry == null || !dry.succeeded() || !clean(dry.out()))
            return failed(dry, "The patch doesn't apply" + (dry == null ? "" : ": " + dry.out().strip()));
        final VcsResult r = patch(repository, patch, reverse, false);
        if (r == null || !r.succeeded() || !clean(r.out()))
            return failed(r, "The patch didn't apply" + (r == null ? "" : ": " + r.out().strip()));
        return r;
    }

    /** Whether apply would apply patch whole, changing nothing. */
    static boolean applies(VcsRepository repository, byte[] patch, boolean index, boolean reverse) {
        if (index)
            return false;
        final VcsResult dry = patch(repository, patch, reverse, true);
        return dry != null && dry.succeeded() && clean(dry.out());
    }

    // Whether svn patch's report is of every hunk applied where it was, if
    // with an offset: no file conflicted or skipped, no hunk rejected,
    // already applied or applied with fuzz.
    static boolean clean(String report) {
        boolean any = false;
        for (String line : report.split("\n")) {
            if (line.isBlank())
                continue;
            final char c = line.charAt(0);
            if (c == 'U' || c == 'A' || c == 'D') {
                any = true;
                continue;
            }
            if (c == '>' && line.contains("applied hunk") && line.contains("with offset") && !line.contains("fuzz"))
                continue;
            return false;
        }
        return any;
    }

    private static VcsResult patch(VcsRepository repository, byte[] patch, boolean reverse, boolean dryRun) {
        Path file = null;
        try {
            file = Files.createTempFile("j-svn-", ".patch");
            Files.write(file, patch);
            final List<String> args = new ArrayList<>(List.of("patch"));
            if (reverse)
                args.add("--reverse-diff");
            if (dryRun)
                args.add("--dry-run");
            args.addAll(List.of("--", file.toString(), "."));
            final String[] a = args.toArray(new String[0]);
            return SvnCli.run(dryRun ? SvnCli.read(repository.root(), a) : SvnCli.write(repository.root(), a));
        }
        catch (IOException e) {
            Log.debug(e);
            return null;
        }
        finally {
            delete(file);
        }
    }

    /**
     * Versions of paths as they would be committed: their bytes' SHA-256,
     * or "absent", and their versioned properties'.
     */
    static CommitPreview preview(VcsRepository repository, List<String> paths) {
        // Its files as they are, a file deleted since the last read too.
        repository.invalidateFiles();
        final Snapshot snapshot = repository.snapshotNow();
        if (snapshot.error() != null)
            return new CommitPreview(Map.of(), snapshot.error());
        final Map<String, ChangeVersion> versions = new TreeMap<>();
        for (String p : paths) {
            final FileChange c = snapshot.change(p);
            if (c == null)
                return new CommitPreview(Map.of(), p + " has no change to commit");
            if (c.isUntracked())
                return new CommitPreview(Map.of(), p + " isn't under version control: svn add it to commit it");
            if (c.isConflicted())
                return new CommitPreview(Map.of(), "Resolve the conflict in " + p + " first");
            final String props;
            if (c.unstaged() == Kind.DELETED)
                props = "";
            else {
                props = properties(repository, p);
                if (props == null)
                    return new CommitPreview(Map.of(), "Couldn't read the properties of " + p);
            }
            versions.put(p, new ChangeVersion(null, new FileVersion(content(repository.root().resolve(p)), props)));
        }
        return new CommitPreview(versions, null);
    }

    // The SHA-256 of the file's bytes, "directory", or "absent".
    private static String content(Path file) {
        try {
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))
                return "directory";
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
                return "absent";
            final byte[] bytes = Files.isSymbolicLink(file)
                    ? Files.readSymbolicLink(file).toString().getBytes(StandardCharsets.UTF_8)
                    : Files.readAllBytes(file);
            return sha256(bytes);
        }
        catch (IOException e) {
            Log.debug(e);
            return "unreadable";
        }
    }

    // The SHA-256 of path's versioned properties, names and values in
    // order; null if svn can't say.
    private static String properties(VcsRepository repository, String path) {
        final VcsResult r =
                SvnCli.run(SvnCli.read(repository.root(), "proplist", "-v", "--xml", "--", SvnCli.target(path)));
        if (!r.succeeded())
            return null;
        try {
            final StringBuilder sb = new StringBuilder();
            final Map<String, String> sorted = new TreeMap<>();
            for (Element target : SvnCli.children(SvnCli.xml(r.stdout()).getDocumentElement(), "target"))
                for (Element p : SvnCli.children(target, "property"))
                    sorted.put(p.getAttribute("name"), p.getTextContent());
            sorted.forEach((k, v) -> sb.append(k).append('\0').append(v).append('\0'));
            return sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception e) {
            Log.debug(e);
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Commits paths whole with message, refused if reviewed isn't null and
     * their versions now differ from it: with any added directories they
     * are in, each path alone and not what is under it; a missing file
     * deleted first, which svn otherwise leaves out.
     */
    static VcsResult commit(
            VcsRepository repository,
            List<String> paths,
            Map<String, ChangeVersion> reviewed,
            String message) {
        if (paths.isEmpty())
            return failed(null, "Nothing to commit");
        final CommitPreview now = preview(repository, paths);
        if (now.failure() != null)
            return failed(null, now.failure());
        if (reviewed != null && !now.versions().equals(reviewed))
            return failed(null, "What it commits changed since it was shown: look again");
        final Snapshot snapshot = repository.snapshot();
        final Set<String> targets = new LinkedHashSet<>();
        final List<String> missing = new ArrayList<>();
        for (String p : paths) {
            for (String dir = parent(p); dir != null; dir = parent(dir)) {
                final FileChange c = snapshot.change(dir);
                if (c != null && c.unstaged() == Kind.ADDED)
                    targets.add(dir);
            }
            final FileChange c = snapshot.change(p);
            if (c != null && "missing".equals(c.code())) {
                if (added(repository, p))
                    return failed(null, p + " was added, then deleted: discard it, as it was never committed");
                missing.add(p);
            }
        }
        // Parents first, as svn wants them.
        final List<String> ordered = new ArrayList<>(targets);
        ordered.sort((a, b) -> Integer.compare(a.length(), b.length()));
        for (String p : paths)
            if (!ordered.contains(p))
                ordered.add(p);
        if (!missing.isEmpty()) {
            final List<String> args = new ArrayList<>(List.of("delete", "-q", "--"));
            for (String p : missing)
                args.add(SvnCli.target(p));
            final VcsResult r = SvnCli.run(SvnCli.write(repository.root(), args.toArray(new String[0])));
            if (!r.succeeded())
                return r;
        }
        Path messageFile = null;
        Path targetsFile = null;
        try {
            messageFile = Files.createTempFile("j-svn-", ".message");
            Files.writeString(messageFile, message, StandardCharsets.UTF_8);
            final List<String> args = new ArrayList<>(
                    List.of("commit", "--depth", "empty", "--encoding", "UTF-8", "-F", messageFile.toString()));
            // svn trims a targets file's lines: a path with white space at
            // either end goes on the command line, as do all with it.
            if (ordered.stream().anyMatch(p -> !p.equals(p.strip()))) {
                args.add("--");
                for (String p : ordered)
                    args.add(SvnCli.target(p));
            } else {
                targetsFile = Files.createTempFile("j-svn-", ".targets");
                final StringBuilder sb = new StringBuilder();
                for (String p : ordered)
                    sb.append(SvnCli.target(p)).append('\n');
                Files.writeString(targetsFile, sb.toString(), StandardCharsets.UTF_8);
                args.addAll(List.of("--targets", targetsFile.toString()));
            }
            final VcsResult r = SvnCli.run(SvnCli.write(repository.root(), args.toArray(new String[0])));
            if (r.succeeded())
                return committedFirst(r);
            undelete(repository, missing);
            if (OUT_OF_DATE.stream().anyMatch(r.stderr()::contains))
                return failed(r, "Update the working copy first: svn update");
            return r;
        }
        catch (IOException e) {
            Log.debug(e);
            return failed(null, "Couldn't write the message: " + e.getMessage());
        }
        finally {
            delete(messageFile);
            delete(targetsFile);
        }
    }

    // Whether path is scheduled to be added.
    private static boolean added(VcsRepository repository, String path) {
        final VcsResult r = SvnCli
                .run(SvnCli.read(repository.root(), "info", "--show-item", "schedule", "--", SvnCli.target(path)));
        return r.succeeded() && r.out().strip().equals("add");
    }

    // The missing files deleted for a commit that failed, missing again:
    // reverted, then the text svn puts back removed.
    private static void undelete(VcsRepository repository, List<String> missing) {
        if (missing.isEmpty())
            return;
        final VcsResult r = discard(repository, missing);
        if (r == null || !r.succeeded())
            return;
        for (String p : missing)
            try {
                Files.deleteIfExists(repository.root().resolve(p));
            }
            catch (IOException e) {
                Log.debug(e);
            }
    }

    // r with its "Committed revision N." line first, what a commit says.
    private static VcsResult committedFirst(VcsResult r) {
        final String out = r.out();
        final int at = out.indexOf("Committed revision ");
        if (at <= 0)
            return r;
        final int end = out.indexOf('\n', at);
        final String line = end < 0 ? out.substring(at) : out.substring(at, end + 1);
        final String rest = out.substring(0, at) + (end < 0 ? "" : out.substring(end + 1));
        return new VcsResult(r.command(),
                r.exitCode(),
                (line.strip() + "\n" + rest).getBytes(StandardCharsets.UTF_8),
                r.stderr(),
                r.millis());
    }

    private static String parent(String path) {
        final int slash = path.lastIndexOf('/');
        return slash <= 0 ? null : path.substring(0, slash);
    }

    // A failure saying why first, then what svn said.
    private static VcsResult failed(VcsResult r, String why) {
        final String said = r == null || r.stderr().isBlank() ? "" : "\n" + r.stderr();
        return new VcsResult(r == null ? null : r.command(),
                r == null || r.exitCode() == 0 ? 1 : r.exitCode(),
                r == null ? new byte[0] : r.stdout(),
                why + said,
                r == null ? 0 : r.millis());
    }

    private static void delete(Path file) {
        if (file != null)
            try {
                Files.deleteIfExists(file);
            }
            catch (IOException e) {
                Log.debug(e);
            }
    }
}
