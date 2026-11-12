/*
 * SvnFixture.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.svn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.util.ProcessRunner;
import org.armedbear.j.vcs.VcsResult;

/**
 * An svn repository and a working copy of it in a temporary directory,
 * svn reading only the fixture's own config, and commits by Tester.
 */
public final class SvnFixture {
    static final String USER = "Tester";
    // svn:date as svn writes it.
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /** The directory that holds the repository, the config and the working copies. */
    public final Path dir;
    /** The repository's file:// URL. */
    public final String url;
    /** The working copy's root. */
    public final Path root;

    private SvnFixture(Path dir, String url, Path root) {
        this.dir = dir;
        this.url = url;
        this.root = root;
    }

    /** Whether svn and svnadmin are on the PATH. */
    public static boolean available() {
        return ProcessRunner.exists("svn") && ProcessRunner.exists("svnadmin");
    }

    /**
     * A new repository at dir/repo, checked out at dir/wc; skips the test
     * if svn is missing. Revision properties may be changed, as commitAt does.
     */
    public static SvnFixture init(Path dir) throws IOException {
        assumeTrue(available(), "svn is not installed");
        final Path real = dir.toRealPath();
        SvnCli.testConfigDir = real.resolve("config");
        final Path repo = real.resolve("repo");
        final ProcessRunner.Result r = ProcessRunner.of("svnadmin", "create", repo.toString()).run();
        assertEquals(0, r.exitValue(), () -> "svnadmin create: " + r.output());
        final Path hook = repo.resolve("hooks/pre-revprop-change");
        Files.writeString(hook, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
        final String url = repo.toUri().toString().replaceAll("/$", "");
        final SvnFixture f = new SvnFixture(real, url, real.resolve("wc"));
        f.checkout(f.root);
        return f;
    }

    /** Puts back the user's svn config, after a test. */
    public static void reset() {
        SvnCli.testConfigDir = null;
    }

    /** Another working copy of the same repository, at dir/wc2, dir/wc3 and so on. */
    public SvnFixture second() {
        Path wc;
        for (int i = 2;; i++)
            if (!Files.exists(wc = dir.resolve("wc" + i)))
                break;
        final SvnFixture f = new SvnFixture(dir, url, wc);
        f.checkout(wc);
        return f;
    }

    private void checkout(Path wc) {
        final VcsResult r = SvnCli.run(SvnCli.write(dir, "checkout", "-q", "--", url, SvnCli.target(wc.toString())));
        assertEquals(0, r.exitCode(), () -> "svn checkout: " + r.stderr());
    }

    public VcsResult run(String... args) {
        return SvnCli.run(SvnCli.write(root, args));
    }

    /** Runs svn in the working copy, which must succeed, and returns its output. */
    public String svn(String... args) {
        final VcsResult r = run(args);
        assertEquals(0, r.exitCode(), () -> "svn " + List.of(args) + ": " + r.stderr());
        return r.out();
    }

    public void write(String path, String content) throws IOException {
        final Path p = root.resolve(path);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.ISO_8859_1));
    }

    public String read(String path) throws IOException {
        return new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.ISO_8859_1);
    }

    public void add(String... paths) {
        svn(withTargets(List.of("add", "-q", "--parents"), paths));
    }

    /** Commits paths, or the whole working copy if none; returns the new revision. */
    public int commit(String message, String... paths) {
        final List<String> args = new ArrayList<>(List.of("commit", "-q", "--username", USER, "-m", message));
        svn(withTargets(args, paths));
        // A commit leaves the working copy's directories at their old revision: update.
        update();
        return revision();
    }

    /** As commit, the new revision dated at. */
    public int commitAt(String message, Instant at, String... paths) {
        final int revision = commit(message, paths);
        svn("propset", "--revprop", "-r", String.valueOf(revision), "svn:date", DATE.format(at));
        return revision;
    }

    public void propset(String name, String value, String path) {
        svn("propset", "-q", name, value, "--", SvnCli.target(path));
    }

    /** Moves paths to the changelist name, or out of any if name is null. */
    public void changelist(String name, String... paths) {
        final List<String> args = new ArrayList<>(List.of("changelist", "-q"));
        if (name == null)
            args.add("--remove");
        else
            args.add(name);
        svn(withTargets(args, paths));
    }

    public void update() {
        svn("update", "-q");
    }

    /** The working copy's revision, as svn info has it. */
    public int revision() {
        return Integer.parseInt(svn("info", "--show-item", "revision").strip());
    }

    private static String[] withTargets(List<String> args, String... paths) {
        final List<String> all = new ArrayList<>(args);
        if (paths.length > 0) {
            all.add("--");
            for (String p : paths)
                all.add(SvnCli.target(p));
        }
        return all.toArray(new String[0]);
    }
}
