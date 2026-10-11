/*
 * VcsCommand.java
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

package org.armedbear.j.vcs;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A version control program to run: its arguments, the directory it runs in,
 * what it adds to the environment, and what it reads on standard input.
 * A write changes the repository, and writes to one repository run one at a
 * time.
 */
public record VcsCommand(Path directory, List<String> argv, Map<String, String> environment, byte[] stdin,
        boolean write, Duration timeout) {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    public VcsCommand {
        argv = List.copyOf(argv);
        environment = Map.copyOf(environment);
        if (timeout == null)
            timeout = DEFAULT_TIMEOUT;
    }

    /** The arguments as a shell would show them, quoted where needed. */
    public String commandLine() {
        StringBuilder sb = new StringBuilder();
        for (String arg : argv) {
            if (sb.length() > 0)
                sb.append(' ');
            if (!arg.isEmpty() && arg.chars().allMatch(c -> c > ' ' && "'\"\\$`!*?&;|<>()[]{}#~".indexOf(c) < 0))
                sb.append(arg);
            else
                sb.append('\'').append(arg.replace("'", "'\\''")).append('\'');
        }
        return sb.toString();
    }
}
