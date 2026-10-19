/*
 * Ignores.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs;

import java.nio.file.Path;
import java.util.regex.Pattern;
import org.armedbear.j.File;

/**
 * What a repository's version control ignores, by its own rules: git's
 * .gitignore files, svn's svn:ignore properties. Every check j makes of
 * whether a file is ignored comes here.
 */
public interface Ignores {
    /**
     * Whether path, under the repository's root, is ignored, a directory or
     * not: by a rule, or by being in a directory that is, as git and svn
     * list nothing in one.
     */
    boolean ignored(Path path, boolean isDirectory);

    /**
     * Runs r on the event thread once rules still being read are read, as
     * svn's, so what was shown without them can be shown again; at once if
     * there are none.
     */
    default void whenRead(Runnable r) {}

    /** Nothing ignored, as outside any repository. */
    Ignores NONE = (path, isDirectory) -> false;

    /**
     * The rules of the repository whose root is dir: the first backend's
     * there that has any; null if none has.
     */
    static Ignores atRoot(Path dir) {
        final File f = File.getInstance(dir.toString());
        if (f == null)
            return null;
        for (VcsBackend backend : VcsBackends.all())
            if (backend.isRoot(f)) {
                final Ignores ignores = backend.ignores(dir);
                if (ignores != null)
                    return ignores;
            }
        return null;
    }

    /**
     * The rules for what is in dir: those of the nearest repository at or
     * over it, unless they ignore dir itself, as git would not list it, when
     * none apply; NONE outside any repository.
     */
    static Ignores holding(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            final Ignores ignores = atRoot(d);
            if (ignores != null)
                return !d.equals(dir) && ignores.ignored(dir, true) ? NONE : ignores;
        }
        return NONE;
    }

    /**
     * A glob as a regex: * and ? within a name, ** across directories, and
     * [set], [!set] as [^set]; git's and svn's patterns both.
     */
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
}
