/*
 * GitCli.java
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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.armedbear.j.util.Whitespace;
import org.armedbear.j.vcs.VcsCommand;
import org.armedbear.j.vcs.VcsResult;
import org.armedbear.j.vcs.VcsRunner;

/**
 * Git command lines, as the tools run them: no colors, paths
 * unquoted and with git's default a/ and b/ prefixes, messages in English,
 * and never a prompt for a password.
 */
public final class GitCli {
    // What every git command runs with. Not --no-pager: git pages only to a
    // terminal, and its output here is a pipe.
    static final List<String> OPTIONS = List.of(
        // color.ui does not override a color.diff or color.status the user set.
        "-c",
        "color.ui=false",
        "-c",
        "color.diff=false",
        "-c",
        "color.status=false",
        "-c",
        "color.branch=false",
        "-c",
        "color.grep=false",
        "-c",
        "color.showBranch=false",
        "-c",
        "core.quotepath=false",
        "-c",
        "diff.noprefix=false",
        "-c",
        "diff.mnemonicPrefix=false");

    // Reads take no optional locks, so they never rewrite the index and
    // set off the watcher.
    private static final Map<String, String> READ_ENV =
            Map.of("LC_ALL", "C", "GIT_TERMINAL_PROMPT", "0", "GIT_OPTIONAL_LOCKS", "0");
    private static final Map<String, String> WRITE_ENV = Map.of("LC_ALL", "C", "GIT_TERMINAL_PROMPT", "0");

    // Tests' isolation from the user's git config.
    static volatile Map<String, String> testEnvironment = Map.of();

    private GitCli() {}

    /** A command that only reads, run in dir. */
    public static VcsCommand read(Path dir, String... args) {
        return new VcsCommand(dir, argv(args), OPTIONS, env(READ_ENV), null, false, null);
    }

    /** A command that only reads, run in dir, reading stdin. */
    public static VcsCommand read(Path dir, byte[] stdin, String... args) {
        return new VcsCommand(dir, argv(args), OPTIONS, env(READ_ENV), stdin, false, null);
    }

    // The index this thread's writes take in place of the repository's, or null.
    private static final ThreadLocal<Path> privateIndex = new ThreadLocal<>();

    /**
     * Runs work with this thread's writes, as staging, taking index in
     * place of the repository's own index; reads still take the real one.
     */
    static <T> T withIndex(Path index, Supplier<T> work) {
        final Path was = privateIndex.get();
        privateIndex.set(index);
        try {
            return work.get();
        }
        finally {
            privateIndex.set(was);
        }
    }

    // The environment of a write: with the private index, if there is one.
    private static Map<String, String> writeEnv() {
        final Path index = privateIndex.get();
        if (index == null)
            return env(WRITE_ENV);
        final Map<String, String> env = new HashMap<>(env(WRITE_ENV));
        env.put("GIT_INDEX_FILE", index.toString());
        return env;
    }

    /** A command that changes the repository at root, reading stdin if not null. */
    public static VcsCommand write(Path root, byte[] stdin, String... args) {
        return new VcsCommand(root, argv(args), OPTIONS, writeEnv(), stdin, true, null);
    }

    /** As write, with a timeout of its own and more environment, as a commit's hooks want. */
    public static VcsCommand write(
            Path root,
            byte[] stdin,
            Duration timeout,
            Map<String, String> environment,
            String... args) {
        Map<String, String> env = new HashMap<>(writeEnv());
        env.putAll(environment);
        return new VcsCommand(root, argv(args), OPTIONS, env, stdin, true, timeout);
    }

    /** args with git diff's option to ignore ignored's whitespace, if any. */
    static List<String> ignoring(Whitespace ignored, List<String> args) {
        final String option = switch (ignored) {
            case NONE -> null;
            case TRAILING -> "--ignore-space-at-eol";
            case AMOUNT -> "--ignore-space-change";
            case ALL -> "--ignore-all-space";
        };
        if (option != null)
            args.add(option);
        return args;
    }

    public static VcsResult run(VcsCommand command) {
        return VcsRunner.run(command);
    }

    /** As run, its output given to stdout as it comes, on another thread. */
    static VcsResult run(VcsCommand command, Consumer<byte[]> stdout) {
        return VcsRunner.run(command, stdout);
    }

    private static volatile int[] version;
    private static volatile String versionString;

    /** Whether git's version is at least {major, minor}. */
    static boolean versionAtLeast(int[] wanted) {
        int[] v = version();
        for (int i = 0; i < wanted.length; i++) {
            int have = i < v.length ? v[i] : 0;
            if (have != wanted[i])
                return have > wanted[i];
        }
        return true;
    }

    static String versionString() {
        version();
        return versionString;
    }

    // "git version 2.43.0", or "2.39.3 (Apple Git-146)"; asked once.
    private static int[] version() {
        int[] v = version;
        if (v != null)
            return v;
        VcsResult r = run(read(Path.of(System.getProperty("user.dir")), "version"));
        String s = r.succeeded() ? r.out().strip() : "";
        if (s.startsWith("git version "))
            s = s.substring("git version ".length());
        versionString = s;
        String[] parts = s.split("[^0-9]+", 4);
        v = new int[Math.min(3, parts.length)];
        for (int i = 0; i < v.length; i++) {
            try {
                v[i] = Integer.parseInt(parts[i]);
            }
            catch (NumberFormatException e) {
                v[i] = 0;
            }
        }
        version = v;
        return v;
    }

    private static Map<String, String> env(Map<String, String> env) {
        if (testEnvironment.isEmpty())
            return env;
        Map<String, String> m = new HashMap<>(env);
        m.putAll(testEnvironment);
        return m;
    }

    private static List<String> argv(String... args) {
        List<String> argv = new ArrayList<>(List.of("git"));
        argv.addAll(OPTIONS);
        argv.addAll(List.of(args));
        return argv;
    }
}
