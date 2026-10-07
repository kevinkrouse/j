/*
 * ProjectRoot.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsBackends;

/**
 * The directory a buffer's project lives in. In order: the projectRoot
 * property, an absolute path; the outermost ancestor holding a .j-project
 * directory; the nearest version control root; the buffer's own directory,
 * if it is under the home directory.
 * Ancestors are searched up to, but not including, the home directory, which
 * is never a project. Local files only.
 */
public final class ProjectRoot {
    /** Marks a project root, and will hold its settings. */
    public static final String MARKER = ".j-project";

    private ProjectRoot() {}

    /** The buffer's project root, or null for a remote or file-less buffer, or one whose only root is home. */
    public static File find(Buffer buffer) {
        File file = buffer.getFile();
        if (file == null || !file.isLocal())
            return null;
        File dir = file.isDirectory() ? file : file.getParentFile();
        return find(dir, buffer.getStringProperty(Property.PROJECT_ROOT), File.getInstance(Utilities.getUserHome()));
    }

    // Asked on every repaint of the buffer list and directory tree: by directory
    // and projectRoot, for a few seconds, so a new .git or .j-project shows soon.
    private static final int MAX_CACHED = 256;
    private static final long CACHE_MILLIS = 5000;

    private record Cached(boolean root, long at) {}

    private static final Map<String, Cached> rootCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHED;
        }
    };

    /**
     * Whether dir is a project's root in its own right: the projectRoot
     * property names it, or it's the root found by its .j-project directory or
     * version control; not a directory that's its own project only for want of
     * those.
     */
    public static boolean isRoot(Buffer buffer, File dir) {
        if (dir == null || !dir.isLocal())
            return false;
        String configured = buffer.getStringProperty(Property.PROJECT_ROOT);
        String key = dir.canonicalPath() + '\0' + (configured == null ? "" : configured);
        long now = System.currentTimeMillis();
        synchronized (rootCache) {
            Cached c = rootCache.get(key);
            if (c != null && now - c.at() < CACHE_MILLIS)
                return c.root();
        }
        boolean root = computeIsRoot(dir, configured);
        synchronized (rootCache) {
            rootCache.put(key, new Cached(root, now));
        }
        return root;
    }

    private static boolean computeIsRoot(File dir, String configured) {
        File root = find(dir, configured, File.getInstance(Utilities.getUserHome()));
        if (root == null || !root.equals(dir))
            return false;
        if (configured != null && !configured.isBlank())
            return true;
        File marker = File.getInstance(dir, MARKER);
        return (marker != null && marker.isDirectory()) || isVcsRoot(dir);
    }

    /** dir's project root; configured wins if it's an absolute path to a directory. */
    static File find(File dir, String configured, File home) {
        if (configured != null && !configured.isBlank()) {
            String s = configured.trim();
            File f = Utilities.isFilenameAbsolute(s) || s.startsWith("~") ? File.getInstance(s) : null;
            if (f != null && f.isLocal() && f.isDirectory())
                return f;
            Log.warn("projectRoot " + s + " is not an absolute path to a directory");
        }
        // The nearest existing directory, for a new file in a directory not yet made.
        while (dir != null && !dir.isDirectory())
            dir = dir.getParentFile();
        if (dir == null || !dir.isLocal() || dir.equals(home))
            return null;
        File marked = null;
        File vcs = null;
        for (File d = dir; d != null && !d.equals(home); d = d.getParentFile()) {
            File marker = File.getInstance(d, MARKER);
            if (marker != null && marker.isDirectory())
                marked = d;
            if (vcs == null && isVcsRoot(d))
                vcs = d;
        }
        if (marked != null)
            return marked;
        if (vcs != null)
            return vcs;
        // A directory of its own only under home: not /etc, /tmp or /.
        return home != null && Path.of(dir.canonicalPath()).startsWith(Path.of(home.canonicalPath())) ? dir : null;
    }

    private static boolean isVcsRoot(File dir) {
        for (VcsBackend backend : VcsBackends.all()) {
            if (backend.isRoot(dir))
                return true;
        }
        return false;
    }
}
