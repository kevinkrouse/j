/*
 * GitIgnores.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.armedbear.j.Log;
import org.armedbear.j.vcs.Ignores;

/**
 * What git ignores in a repository: its .gitignore files, .git/info/exclude
 * and the global ignore file, read as git reads them. A deeper .gitignore
 * outranks a shallower one, all of them info/exclude, and it the global
 * file; in each the last rule to match decides, a "!" one un-ignoring.
 * What is in an ignored directory is ignored, and no "!" takes it back, as
 * git's. Files git tracks are not told apart: a rule matches them too.
 */
public final class GitIgnores implements Ignores {
    private final Path root;
    private final Rules exclude;
    private final Rules global;
    // Each directory's .gitignore, read once, and whether each directory is ignored.
    private final Map<Path, Rules> perDirectory = new ConcurrentHashMap<>();
    private final Map<Path, Boolean> directories = new ConcurrentHashMap<>();

    /** The rules of the repository at root, with the global ignore file if globalIgnore isn't null. */
    public GitIgnores(Path root, Path globalIgnore) {
        this.root = root;
        exclude = Rules.read(root.resolve(".git/info/exclude"), root);
        global = globalIgnore != null ? Rules.read(globalIgnore, root) : Rules.EMPTY;
    }

    @Override
    public boolean ignored(Path path, boolean isDirectory) {
        if (!path.startsWith(root) || path.equals(root))
            return false;
        final Path parent = path.getParent();
        if (parent != null && !parent.equals(root) && directoryIgnored(parent))
            return true;
        return byRules(path, isDirectory);
    }

    private boolean directoryIgnored(Path dir) {
        final Boolean known = directories.get(dir);
        if (known != null)
            return known;
        final Path parent = dir.getParent();
        final boolean ignored =
                parent != null && !parent.equals(root) && directoryIgnored(parent) || byRules(dir, true);
        directories.put(dir, ignored);
        return ignored;
    }

    // By the rules alone, whatever the directories it is in.
    private boolean byRules(Path path, boolean isDirectory) {
        for (Path dir = path.getParent(); dir != null && dir.startsWith(root); dir = dir.getParent()) {
            final Rules rules = perDirectory.computeIfAbsent(dir, d -> Rules.read(d.resolve(".gitignore"), d));
            final Boolean decided = rules.decide(path, isDirectory);
            if (decided != null)
                return decided;
        }
        final Boolean excluded = exclude.decide(path, isDirectory);
        if (excluded != null)
            return excluded;
        final Boolean globally = global.decide(path, isDirectory);
        return globally != null && globally;
    }

    /** $XDG_CONFIG_HOME/git/ignore, or ~/.config/git/ignore, if it's there. */
    public static Path globalIgnoreFile() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isEmpty() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        Path p = base.resolve("git/ignore");
        return Files.isRegularFile(p) ? p : null;
    }

    /** The rules of one ignore file, relative to the directory it applies to. */
    static final class Rules {
        private record Rule(Pattern pattern, boolean anchored, boolean directoryOnly, boolean negated) {}

        static final Rules EMPTY = new Rules(null, List.of());

        private final Path base;
        private final List<Rule> rules;

        private Rules(Path base, List<Rule> rules) {
            this.base = base;
            this.rules = rules;
        }

        static Rules read(Path file, Path base) {
            if (!Files.isRegularFile(file))
                return EMPTY;
            try {
                return parse(Files.readAllLines(file, StandardCharsets.UTF_8), base);
            }
            catch (IOException e) {
                Log.debug(e);
                return EMPTY;
            }
        }

        static Rules parse(List<String> lines, Path base) {
            List<Rule> rules = new ArrayList<>();
            for (String line : lines) {
                Rule rule = parseRule(line);
                if (rule != null)
                    rules.add(rule);
            }
            return rules.isEmpty() ? EMPTY : new Rules(base, rules);
        }

        private static Rule parseRule(String line) {
            String s = line.stripTrailing();
            if (s.isEmpty() || s.startsWith("#"))
                return null;
            final boolean negated = s.startsWith("!");
            if (negated)
                s = s.substring(1);
            else if (s.startsWith("\\#") || s.startsWith("\\!"))
                s = s.substring(1);
            boolean directoryOnly = s.endsWith("/");
            if (directoryOnly)
                s = s.substring(0, s.length() - 1);
            if (s.isEmpty())
                return null;
            // A slash anywhere but the end ties the pattern to the base directory.
            boolean anchored = s.indexOf('/') >= 0;
            if (s.startsWith("/"))
                s = s.substring(1);
            if (s.startsWith("**/")) {
                s = s.substring(3);
                anchored = s.indexOf('/') >= 0;
                if (anchored)
                    s = "**/" + s;
            }
            try {
                return new Rule(Pattern.compile(Ignores.globToRegex(s)), anchored, directoryOnly, negated);
            }
            catch (PatternSyntaxException e) {
                return null;
            }
        }

        /** Ignored or not by the last rule that matches p, or null if none does. */
        Boolean decide(Path p, boolean isDirectory) {
            if (rules.isEmpty() || !p.startsWith(base) || p.equals(base))
                return null;
            String rel = null;
            final String name = p.getFileName().toString();
            for (int i = rules.size() - 1; i >= 0; i--) {
                final Rule rule = rules.get(i);
                if (rule.directoryOnly && !isDirectory)
                    continue;
                final boolean matches;
                if (rule.anchored) {
                    if (rel == null)
                        rel = base.relativize(p).toString().replace('\\', '/');
                    matches = rule.pattern.matcher(rel).matches();
                } else
                    matches = rule.pattern.matcher(name).matches();
                if (matches)
                    return !rule.negated;
            }
            return null;
        }
    }
}
