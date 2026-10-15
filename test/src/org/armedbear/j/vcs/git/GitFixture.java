/*
 * GitFixture.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.armedbear.j.util.ProcessRunner;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.diff.DiffParser;
import org.armedbear.j.vcs.diff.FileDiff;

/**
 * A git repository in a temporary directory, with the user's and system's
 * config out of the way and fixed names and dates, so runs are repeatable.
 */
public final class GitFixture {
    static final Map<String, String> ENV = Map.of(
        "GIT_CONFIG_GLOBAL",
        "/dev/null",
        "GIT_CONFIG_NOSYSTEM",
        "1",
        "GIT_AUTHOR_NAME",
        "Tester",
        "GIT_AUTHOR_EMAIL",
        "tester@example.com",
        "GIT_COMMITTER_NAME",
        "Tester",
        "GIT_COMMITTER_EMAIL",
        "tester@example.com",
        "GIT_AUTHOR_DATE",
        "2026-01-01T00:00:00Z",
        "GIT_COMMITTER_DATE",
        "2026-01-01T00:00:00Z");

    public final Path root;

    private GitFixture(Path root) {
        this.root = root;
    }

    /** A new repository at dir, on branch main; skips the test if git is missing. */
    public static GitFixture init(Path dir) throws IOException {
        assumeTrue(ProcessRunner.exists("git"), "git is not installed");
        GitCli.testEnvironment = ENV;
        Path root = dir.toRealPath();
        GitFixture f = new GitFixture(root);
        f.git("init", "-q", "-b", "main");
        return f;
    }

    /** Puts back the user's git config, after a test. */
    public static void reset() {
        GitCli.testEnvironment = Map.of();
    }

    /** An existing repository at root. */
    public static GitFixture at(Path root) throws IOException {
        return new GitFixture(root.toRealPath());
    }

    public VcsResult run(String... args) {
        return GitCli.run(GitCli.write(root, null, args));
    }

    /** Runs git, which must succeed, and returns its output. */
    public String git(String... args) {
        VcsResult r = run(args);
        assertEquals(0, r.exitCode(), () -> "git " + List.of(args) + ": " + r.stderr());
        return r.out();
    }

    byte[] gitBytes(String... args) {
        VcsResult r = run(args);
        assertEquals(0, r.exitCode(), () -> "git " + List.of(args) + ": " + r.stderr());
        return r.stdout();
    }

    public void write(String path, String content) throws IOException {
        Path p = root.resolve(path);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.ISO_8859_1));
    }

    public String read(String path) throws IOException {
        return new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.ISO_8859_1);
    }

    public void commitAll(String message) {
        git("add", "-A");
        git("commit", "-q", "--allow-empty", "-m", message);
    }

    /** The file as the index has it, or null if it is not in the index. */
    public String index(String path) {
        VcsResult r = run("cat-file", "blob", ":" + path);
        return r.succeeded() ? new String(r.stdout(), StandardCharsets.ISO_8859_1) : null;
    }

    /** The file as HEAD has it, or null. */
    public String head(String path) {
        VcsResult r = run("cat-file", "blob", "HEAD:" + path);
        return r.succeeded() ? new String(r.stdout(), StandardCharsets.ISO_8859_1) : null;
    }

    List<FileDiff> unstaged() {
        return DiffParser.parse(gitBytes("diff", "--no-ext-diff"));
    }

    List<FileDiff> staged() {
        return DiffParser.parse(gitBytes("diff", "--no-ext-diff", "--cached"));
    }

    /** Applies a patch; returns git's error, or null if it applied. */
    String apply(byte[] patch, boolean cached, boolean reverse) {
        List<String> args = new ArrayList<>(List.of("apply", "--whitespace=nowarn"));
        if (cached)
            args.add("--cached");
        if (reverse)
            args.add("--reverse");
        args.add("-");
        VcsResult r = GitCli.run(GitCli.write(root, patch, args.toArray(new String[0])));
        return r.succeeded() ? null : r.stderr() + "\n--- patch:\n" + new String(patch, StandardCharsets.ISO_8859_1);
    }
}
