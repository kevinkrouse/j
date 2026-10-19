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
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.armedbear.j.vcs.Ignores;

/**
 * The files under a project root, as root-relative paths with '/'
 * separators. Scanned in the background and cached per root; a caller gets
 * the last snapshot at once and asks for a rescan when it's stale.
 *
 * <p>The scan skips what each repository's version control ignores
 * ({@link Ignores}), a default list of build and tool directories, names matching
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
        ".venv");

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
                        new Walker(root, max, excludes, Ignores::atRoot, () -> gen != generation.get(), partial);
                List<String> result = walker.walk();
                if (gen != generation.get())
                    return;
                truncated = walker.truncated;
                freshSince = start;
                complete = true;
                scanning = false;
                publish(result);
                Log.debug(
                    "ProjectFiles " + root + ": " + result.size() + " files in " + (System.currentTimeMillis() - start)
                            + " ms" + (truncated ? " (truncated)" : ""));
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
        Walker walker = new Walker(root,
                Integer.MAX_VALUE,
                FilenameCompletion.excludesPattern(),
                Ignores::atRoot,
                () -> false,
                null);
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
        // The repository at a directory that is the root of one, or null.
        private final Function<Path, Ignores> repositoryAt;
        private final BooleanSupplier cancelled;
        private final Consumer<List<String>> partial;
        private final List<String> found = new ArrayList<>();
        // The rules of the repository each directory entered is in, innermost last.
        private final Deque<Ignores> ignores = new ArrayDeque<>();
        private long lastPublish = System.currentTimeMillis();
        private int lastPublishCount;
        boolean truncated;

        Walker(
                Path root,
                int max,
                Pattern excludes,
                Function<Path, Ignores> repositoryAt,
                BooleanSupplier cancelled,
                Consumer<List<String>> partial) {
            this.root = root;
            this.max = max;
            this.excludes = excludes;
            this.repositoryAt = repositoryAt;
            this.cancelled = cancelled;
            this.partial = partial;
        }

        private Consumer<String> visitor;

        /** Hands each file to visitor as it's found, rather than listing them. */
        Walker visiting(Consumer<String> visitor) {
            this.visitor = visitor;
            return this;
        }

        List<String> walk() {
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
                        if (visitor != null) {
                            visitor.accept(relative(root, file));
                            return cancelled.getAsBoolean() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }
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

        // Root takes the rules of the repository it is in, or none if they
        // ignore root itself, which git wouldn't list; a directory under it
        // its own, if it's a repository's root, or its parent's.
        private void enter(Path dir) {
            Ignores in = null;
            if (dir.equals(root)) {
                for (Path d = root; d != null && in == null; d = d.getParent()) {
                    in = repositoryAt.apply(d);
                    if (in != null && !d.equals(root) && in.ignored(root, true))
                        in = Ignores.NONE;
                }
            } else {
                in = repositoryAt.apply(dir);
                if (in == null)
                    in = ignores.peekLast();
            }
            ignores.addLast(in != null ? in : Ignores.NONE);
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
            final Ignores in = ignores.peekLast();
            return in != null && in.ignored(p, isDirectory);
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
    }
}
