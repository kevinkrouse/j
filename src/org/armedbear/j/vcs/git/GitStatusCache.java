/*
 * GitStatusCache.java
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

import java.util.HashMap;
import java.util.Map;
import org.armedbear.j.File;
import org.armedbear.j.Log;
import org.armedbear.j.ShellCommand;

/**
 * The status of every file in a repository, from one run of git.
 *
 * <p>The answers are a snapshot. {@link #invalidate} drops them, and callers
 * that have just changed the working tree -- saving a file, say -- are expected
 * to say so.
 */
public final class GitStatusCache {
    /** Repository root path -> (path relative to that root -> two letter code). */
    private static final Map<String, Map<String, String>> repositories =
        new HashMap<>();

    /** Directories already resolved to a repository root, "" meaning none. */
    private static final Map<String, String> roots = new HashMap<>();

    private GitStatusCache() {}

    /**
     * The two letter porcelain code for a file, or null if git says nothing
     * about it -- unchanged, or not in a repository at all.
     */
    public static synchronized String statusFor(File file) {
        if (file == null || file.isRemote())
            return null;
        final String root = rootFor(file);
        if (root == null)
            return null;
        Map<String, String> status = repositories.get(root);
        if (status == null) {
            status = read(root);
            repositories.put(root, status);
        }
        return status.get(relativePath(root, file));
    }

    /** Forgets every snapshot, so the next question re-runs git. */
    public static synchronized void invalidate() {
        repositories.clear();
    }

    private static String rootFor(File file) {
        File parent = file.getParentFile();
        if (parent == null)
            return null;
        final String key = parent.canonicalPath();
        if (roots.containsKey(key)) {
            String cached = roots.get(key);
            return cached.length() == 0 ? null : cached;
        }
        File root = Git.findRoot(parent);
        String value = root != null ? root.canonicalPath() : "";
        roots.put(key, value);
        return value.length() == 0 ? null : value;
    }

    private static String relativePath(String root, File file) {
        String path = file.canonicalPath();
        if (path.startsWith(root)) {
            path = path.substring(root.length());
            while (path.startsWith("/"))
                path = path.substring(1);
        }
        return path;
    }

    private static Map<String, String> read(String root) {
        Map<String, String> status = new HashMap<>();
        try {
            // -z so paths arrive verbatim: without it git quotes and escapes
            // anything unusual, which would have to be undone here.
            ShellCommand cmd = new ShellCommand(
                "git status -z --porcelain --ignored --untracked",
                File.getInstance(root)
            );
            cmd.run();
            parse(cmd.getOutput(), status);
        }
        catch (RuntimeException e) {
            Log.error(e);
        }
        return status;
    }

    /**
     * Reads "XY &lt;path&gt;" records separated by NUL.
     *
     * <p>A rename or copy carries a second path -- the source -- in its own
     * record straight after the first. It has to be stepped over, or every
     * entry after the first rename is read as a status.
     */
    static void parse(String output, Map<String, String> status) {
        if (output == null)
            return;
        int i = 0;
        final int length = output.length();
        while (i < length) {
            int end = output.indexOf('\0', i);
            if (end < 0)
                end = length;
            final String record = output.substring(i, end);
            i = end + 1;
            if (record.length() < 4)
                continue;
            final String xy = record.substring(0, 2);
            final String path = record.substring(3);
            status.put(path, xy);
            if (
                xy.charAt(0) == 'R'
                    || xy.charAt(0) == 'C'
                    || xy.charAt(1) == 'R'
                    || xy.charAt(1) == 'C'
            ) {
                int source = output.indexOf('\0', i);
                i = (source < 0) ? length : source + 1;
            }
        }
    }
}
