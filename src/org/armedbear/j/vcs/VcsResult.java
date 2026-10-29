/*
 * VcsResult.java
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

import java.nio.charset.StandardCharsets;

/**
 * What a version control command did. exitCode is -1 if it did not start,
 * timed out or was interrupted; stderr then says why.
 */
public record VcsResult(VcsCommand command, int exitCode, byte[] stdout, String stderr, long millis) {
    public boolean succeeded() {
        return exitCode == 0;
    }

    /** Standard output as UTF-8. */
    public String out() {
        return new String(stdout, StandardCharsets.UTF_8);
    }

    /** Standard error's last line, git's last word on a failure; "" for none. */
    public String lastError() {
        return stderr.strip().lines().reduce((x, y) -> y).orElse("");
    }
}
