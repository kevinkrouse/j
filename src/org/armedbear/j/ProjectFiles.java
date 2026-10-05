/*
 * ProjectFiles.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The files under a project root, as root-relative paths with '/'
 * separators. Scanned in the background and cached per root; a caller gets
 * the last snapshot at once and asks for a rescan when it's stale.
 *
 * <p>The scan skips what .gitignore files ignore (no "!" negation), a
 * default list of build and tool directories, names matching
 * filenameCompletionsExcludePattern, and nested git worktrees.
 */
public final class ProjectFiles {
    static final Set<String> DEFAULT_SKIPS = Set.of(
        ".git",
        ".hg",
        ".svn",
        "node_modules",
        "build",
        "target",
        "dist",
        "out",
        ".gradle",
        ".idea",
        ".cpcache",
        ".clj-kondo",
        "__pycache__",
        ".venv"
    );

    // Partial results are published at least this often during a scan.
    private static final int PUBLISH_EVERY_FILES = 5000;
    private static final long PUBLISH_EVERY_MILLIS = 200;

    // The projects most recently asked for; an older one's list is dropped.
    private static final int MAX_CACHED = 8;
    private static final Map<Path, ProjectFiles> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, ProjectFiles> eldest) {
            return size() > MAX_CACHED;
        }
    };

    private static synchronized List<ProjectFiles> cached() {
        return new ArrayList<>(cache.values());
    }

    private static final ExecutorService scanner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ProjectFiles");
        t.setDaemon(true);
        return t;
    });

    private final Path root;
    private final AtomicInteger generation = new AtomicInteger();
    private final List<Consumer<ProjectFiles>> listeners = new CopyOnWriteArrayList<>();

    private volatile List<String> files = List.of();
    private volatile boolean complete; // A scan has finished.
    private volatile long freshSince; // When the last finished scan started.
    private volatile long invalidatedAt;
    private volatile boolean scanning;
    private volatile boolean truncated;

    private ProjectFiles(Path root) {
        this.root = root;
    }

    /** The cached list for root, which may not have been scanned yet. */
    public static ProjectFiles forRoot(File root) {
        Path p = Path.of(root.canonicalPath());
        synchronized (ProjectFiles.class) {
            return cache.computeIfAbsent(p, ProjectFiles::new);
        }
    }

    public Path getRoot() {
        return root;
    }

    /** The latest snapshot, sorted; partial while a first scan runs. */
    public List<String> files() {
        return files;
    }

    public boolean isScanning() {
        return scanning;
    }

    /** Whether the last scan stopped at finderMaxFiles. */
    public boolean isTruncated() {
        return truncated;
    }

    /** Called, on the scanning thread, with each new snapshot. */
    public void addListener(Consumer<ProjectFiles> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<ProjectFiles> listener) {
        listeners.remove(listener);
    }

    /** Rescans if never scanned, invalidated, or older than maxAgeSeconds. */
    public void refreshIfStale(int maxAgeSeconds) {
        if (scanning)
            return;
        long since = freshSince;
        if (since == 0 || invalidatedAt >= since || System.currentTimeMillis() - since > maxAgeSeconds * 1000L)
            rescan();
    }

    /**
     * Starts a scan, abandoning any running one. Until the first scan
     * finishes, partial results are published as they're found; after that,
     * the old list stands until the new one is complete.
     */
    public void rescan() {
        final int gen = generation.incrementAndGet();
        final int max = maxFiles();
        final Pattern excludes = FilenameCompletion.excludesPattern();
        scanning = true;
        scanner.execute(() -> {
            if (gen != generation.get())
                return;
            final long start = System.currentTimeMillis();
            try {
                Consumer<List<String>> partial = complete ? null : snapshot -> {
                    if (gen == generation.get())
                        publish(snapshot);
                };
                Walker walker =
                    new Walker(root, max, excludes, Walker.globalIgnoreFile(), () -> gen != generation.get(), partial);
                List<String> result = walker.walk();
                if (gen != generation.get())
                    return;
                truncated = walker.truncated;
                freshSince = start;
                complete = true;
                scanning = false;
                publish(result);
                Log.debug(
                    "ProjectFiles " + root + ": " + result.size() + " files in " +
                        (System.currentTimeMillis() - start) + " ms" + (truncated ? " (truncated)" : "")
                );
            }
            catch (RuntimeException e) {
                Log.error(e);
            }
            finally {
                if (gen == generation.get())
                    scanning = false;
            }
        });
    }

    // Takes ownership of list.
    private void publish(List<String> list) {
        Collections.sort(list);
        setFiles(list);
    }

    private void setFiles(List<String> sorted) {
        files = Collections.unmodifiableList(sorted);
        for (Consumer<ProjectFiles> listener : listeners) {
            try {
                listener.accept(this);
            }
            catch (RuntimeException e) {
                Log.error(e);
            }
        }
    }

    private static int maxFiles() {
        Preferences preferences = Editor.preferences();
        if (preferences != null)
            return preferences.getIntegerProperty(Property.FINDER_MAX_FILES);
        return (Integer) Property.FINDER_MAX_FILES.getDefaultValue();
    }

    /**
     * Adds a saved local file to the cached projects it's in, unless a scan
     * would have skipped it. Queued behind any running scan.
     */
    public static void fileSaved(File file) {
        if (file == null || !file.isLocal())
            return;
        final Path p = Path.of(file.canonicalPath());
        for (ProjectFiles pf : cached()) {
            if (p.startsWith(pf.root) && !p.equals(pf.root))
                scanner.execute(() -> pf.add(p));
        }
    }

    private void add(Path p) {
        if (!complete || !Files.isRegularFile(p))
            return;
        List<String> current = files;
        if (current.size() >= maxFiles())
            return;
        String rel = relative(root, p);
        int index = Collections.binarySearch(current, rel);
        if (index >= 0)
            return;
        Walker walker =
            new Walker(
                root,
                Integer.MAX_VALUE,
                FilenameCompletion.excludesPattern(),
                Walker.globalIgnoreFile(),
                () -> false,
                null
            );
        if (!walker.accepts(p))
            return;
        List<String> next = new ArrayList<>(current.size() + 1);
        next.addAll(current);
        next.add(-index - 1, rel);
        setFiles(next);
    }

    /** Marks every cached project stale, after files were deleted or moved. */
    public static void invalidate() {
        long now = System.currentTimeMillis();
        for (ProjectFiles pf : cached())
            pf.invalidatedAt = now;
    }

    // Runs after every task queued so far; for tests.
    static void awaitScanner() throws Exception {
        scanner.submit(() -> {}).get();
    }

    static String relative(Path root, Path p) {
        String rel = root.relativize(p).toString();
        return java.io.File.separatorChar == '/' ? rel : rel.replace(java.io.File.separatorChar, '/');
    }

    /** One scan of a tree. */
    static final class Walker {
        private final Path root;
        private final int max;
        private final Pattern excludes;
        private final Path globalIgnore;
        private final BooleanSupplier cancelled;
        private final Consumer<List<String>> partial;
        private final List<String> found = new ArrayList<>();
        // The .gitignore rules in effect, innermost last.
        private final Deque<Ignore> ignores = new ArrayDeque<>();
        private long lastPublish = System.currentTimeMillis();
        private int lastPublishCount;
        boolean truncated;

        Walker(
            Path root,
            int max,
            Pattern excludes,
            Path globalIgnore,
            BooleanSupplier cancelled,
            Consumer<List<String>> partial
        ) {
            this.root = root;
            this.max = max;
            this.excludes = excludes;
            this.globalIgnore = globalIgnore;
            this.cancelled = cancelled;
            this.partial = partial;
        }

        List<String> walk() {
            if (globalIgnore != null)
                ignores.add(Ignore.read(globalIgnore, root));
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (cancelled.getAsBoolean())
                            return FileVisitResult.TERMINATE;
                        if (!dir.equals(root) && skipDirectory(dir))
                            return FileVisitResult.SKIP_SUBTREE;
                        enter(dir);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                        ignores.removeLast();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        // Links to directories aren't followed, or listed.
                        if (attrs.isDirectory() || (attrs.isSymbolicLink() && Files.isDirectory(file)))
                            return FileVisitResult.CONTINUE;
                        if (skipFile(file))
                            return FileVisitResult.CONTINUE;
                        found.add(relative(root, file));
                        if (found.size() >= max) {
                            truncated = true;
                            return FileVisitResult.TERMINATE;
                        }
                        maybePublish();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            catch (IOException e) {
                Log.error(e);
            }
            return found;
        }

        /** Whether a walk would list file, which is under root. */
        boolean accepts(Path file) {
            if (globalIgnore != null)
                ignores.add(Ignore.read(globalIgnore, root));
            Path dir = root;
            enter(dir);
            // A file in root itself has no directories between: an empty path
            // would still iterate once, as "".
            Path between = root.relativize(file.getParent());
            for (Path name : between.toString().isEmpty() ? List.<Path>of() : between) {
                dir = dir.resolve(name);
                if (skipDirectory(dir))
                    return false;
                enter(dir);
            }
            return !skipFile(file);
        }

        private void enter(Path dir) {
            Ignore ignore = Ignore.read(dir.resolve(".gitignore"), dir);
            if (Files.isDirectory(dir.resolve(".git")))
                ignore = ignore.with(Ignore.read(dir.resolve(".git/info/exclude"), dir));
            ignores.addLast(ignore);
        }

        private boolean skipDirectory(Path dir) {
            String name = dir.getFileName().toString();
            return DEFAULT_SKIPS.contains(name) || excluded(name) || ignored(dir, true) || isWorktree(dir);
        }

        private boolean skipFile(Path file) {
            String name = file.getFileName().toString();
            return name.equals(".git") || excluded(name) || ignored(file, false);
        }

        private void maybePublish() {
            if (partial == null)
                return;
            long now = System.currentTimeMillis();
            if (found.size() - lastPublishCount >= PUBLISH_EVERY_FILES || now - lastPublish >= PUBLISH_EVERY_MILLIS) {
                lastPublish = now;
                lastPublishCount = found.size();
                partial.accept(new ArrayList<>(found));
            }
        }

        private boolean excluded(String name) {
            return excludes != null && excludes.matcher(name).matches();
        }

        private boolean ignored(Path p, boolean isDirectory) {
            for (Ignore ignore : ignores) {
                if (ignore.matches(p, isDirectory))
                    return true;
            }
            return false;
        }

        // A linked worktree's .git is a file naming .git/worktrees/NAME in the main
        // repository; listing it would repeat the main tree. A submodule's names
        // .../modules/NAME, and is kept.
        private static boolean isWorktree(Path dir) {
            Path git = dir.resolve(".git");
            if (!Files.isRegularFile(git))
                return false;
            try {
                String s = Files.readString(git, StandardCharsets.UTF_8).trim();
                return s.startsWith("gitdir:") && WORKTREE_GITDIR.matcher(s.replace('\\', '/')).find();
            }
            catch (IOException e) {
                return false;
            }
        }

        private static final Pattern WORKTREE_GITDIR = Pattern.compile("/worktrees/[^/]+/*$");

        static Path globalIgnoreFile() {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            Path base =
                xdg != null && !xdg.isEmpty() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
            Path p = base.resolve("git/ignore");
            return Files.isRegularFile(p) ? p : null;
        }
    }

    /** The rules of one .gitignore file, relative to the directory it applies to. */
    static final class Ignore {
        private record Rule(Pattern pattern, boolean anchored, boolean directoryOnly) {}

        private final Path base;
        private final List<Rule> rules;

        private Ignore(Path base, List<Rule> rules) {
            this.base = base;
            this.rules = rules;
        }

        static Ignore read(Path file, Path base) {
            List<String> lines = List.of();
            if (Files.isRegularFile(file)) {
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                }
                catch (IOException e) {
                    Log.debug(e);
                }
            }
            return parse(lines, base);
        }

        static Ignore parse(List<String> lines, Path base) {
            List<Rule> rules = new ArrayList<>();
            for (String line : lines) {
                Rule rule = parseRule(line);
                if (rule != null)
                    rules.add(rule);
            }
            return new Ignore(base, rules);
        }

        Ignore with(Ignore other) {
            List<Rule> all = new ArrayList<>(rules);
            all.addAll(other.rules);
            return new Ignore(base, all);
        }

        private static Rule parseRule(String line) {
            String s = line.stripTrailing();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("!"))
                return null;
            if (s.startsWith("\\#") || s.startsWith("\\!"))
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
                return new Rule(Pattern.compile(globToRegex(s)), anchored, directoryOnly);
            }
            catch (PatternSyntaxException e) {
                return null;
            }
        }

        static String globToRegex(String glob) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                if (c == '*') {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            i++;
                            sb.append("(?:.*/)?"); // "**/": any number of directories.
                        } else {
                            sb.append(".*");
                        }
                    } else {
                        sb.append("[^/]*");
                    }
                } else if (c == '?') {
                    sb.append("[^/]");
                } else if (c == '[') {
                    int close = glob.indexOf(']', i + 1);
                    if (close < 0) {
                        sb.append("\\[");
                    } else {
                        String set = glob.substring(i + 1, close);
                        if (set.startsWith("!"))
                            set = "^" + set.substring(1);
                        sb.append('[').append(set.replace("\\", "\\\\")).append(']');
                        i = close;
                    }
                } else if (c == '\\' && i + 1 < glob.length()) {
                    sb.append(Pattern.quote(String.valueOf(glob.charAt(++i))));
                } else {
                    sb.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return sb.toString();
        }

        boolean matches(Path p, boolean isDirectory) {
            if (rules.isEmpty() || !p.startsWith(base) || p.equals(base))
                return false;
            String rel = null;
            String name = p.getFileName().toString();
            for (Rule rule : rules) {
                if (rule.directoryOnly && !isDirectory)
                    continue;
                if (rule.anchored) {
                    if (rel == null)
                        rel = relative(base, p);
                    if (rule.pattern.matcher(rel).matches())
                        return true;
                } else if (rule.pattern.matcher(name).matches()) {
                    return true;
                }
            }
            return false;
        }
    }
}
