/*
 * HiddenNames.java
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

package org.armedbear.j.mode.dir;

import java.nio.file.Path;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import org.armedbear.j.File;
import org.armedbear.j.FilenameCompletion;
import org.armedbear.j.vcs.Ignores;

/**
 * Which names in a directory a listing leaves out unless dirShowHidden:
 * dotfiles and names matching filenameCompletionsExcludePattern, hidden;
 * what its version control ignores there ({@link Ignores}), ignored.
 */
final class HiddenNames {
    static final int SHOWN = 0;
    static final int HIDDEN = 1;
    static final int IGNORED = 2;

    private final Pattern excludes;
    private final BiPredicate<String, Boolean> ignored;
    private final Ignores rules;

    HiddenNames(File dir) {
        excludes = FilenameCompletion.excludesPattern();
        if (dir.isLocal()) {
            final Path here = Path.of(dir.canonicalPath());
            rules = Ignores.holding(here);
            ignored = (name, isDirectory) -> rules.ignored(here.resolve(name), isDirectory);
        } else {
            rules = Ignores.NONE;
            ignored = null;
        }
    }

    /** Runs r once rules still being read are, so the listing can be made again with them. */
    void whenRead(Runnable r) {
        rules.whenRead(r);
    }

    int kind(String name, boolean isDirectory) {
        if (name == null || name.equals(".") || name.equals(".."))
            return SHOWN;
        if (ignored != null && ignored.test(name, isDirectory))
            return IGNORED;
        if (name.startsWith(".") || (excludes != null && excludes.matcher(name).matches()))
            return HIDDEN;
        return SHOWN;
    }
}
