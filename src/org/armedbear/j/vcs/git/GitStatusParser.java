/*
 * GitStatusParser.java
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.FileChange.Conflict;
import org.armedbear.j.vcs.FileChange.Kind;
import org.armedbear.j.vcs.Snapshot.Relation;

/**
 * Reads `git status --porcelain=v2 -z --branch --show-stash`: "# " headers,
 * then an entry per file, each ended by NUL, a rename's source path in a
 * record of its own after it.
 */
final class GitStatusParser {
    private GitStatusParser() {}

    /** An unmerged file's kind from its XY; null if xy is no conflict's. */
    static Conflict conflict(String xy) {
        return switch (xy) {
            case "UU" -> Conflict.BOTH_MODIFIED;
            case "AA" -> Conflict.BOTH_ADDED;
            case "DD" -> Conflict.BOTH_DELETED;
            case "AU" -> Conflict.ADDED_BY_US;
            case "UA" -> Conflict.ADDED_BY_THEM;
            case "DU" -> Conflict.DELETED_BY_US;
            case "UD" -> Conflict.DELETED_BY_THEM;
            default -> null;
        };
    }

    /** stashHeader: whether this git writes "# stash"; if not, the count is unknown (-1). */
    static GitSnapshot parse(byte[] output, GitSnapshot.Operation operation, boolean stashHeader) {
        List<FileChange> changes = new ArrayList<>();
        String head = null;
        String branch = null;
        String upstream = null;
        int ahead = -1;
        int behind = -1;
        int stashes = stashHeader ? 0 : -1;
        List<String> records = split(output);
        for (int i = 0; i < records.size(); i++) {
            String r = records.get(i);
            if (r.startsWith("# ")) {
                String[] f = r.split(" ", 3);
                if (f.length < 3)
                    continue;
                switch (f[1]) {
                    case "branch.oid" -> head = f[2].equals("(initial)") ? null : f[2];
                    case "branch.head" -> branch = f[2].equals("(detached)") ? null : f[2];
                    case "branch.upstream" -> upstream = f[2];
                    case "branch.ab" -> {
                        String[] ab = f[2].split(" ");
                        if (ab.length == 2) {
                            ahead = parseInt(ab[0].substring(1));
                            behind = parseInt(ab[1].substring(1));
                        }
                    }
                    case "stash" -> stashes = parseInt(f[2]);
                    default -> {}
                }
            } else if (r.startsWith("1 ")) {
                String[] f = r.split(" ", 9);
                if (f.length == 9)
                    changes.add(change(f[8], null, f[1]));
            } else if (r.startsWith("2 ")) {
                String[] f = r.split(" ", 10);
                String orig = i + 1 < records.size() ? records.get(++i) : null;
                if (f.length == 10)
                    changes.add(change(f[9], orig, f[1]));
            } else if (r.startsWith("u ")) {
                String[] f = r.split(" ", 11);
                if (f.length == 11)
                    changes.add(new FileChange(f[10], null, Kind.CONFLICTED, Kind.CONFLICTED, f[1], conflict(f[1])));
            } else if (r.startsWith("? ")) {
                changes.add(new FileChange(r.substring(2), null, null, Kind.UNTRACKED, "??"));
            } else if (r.startsWith("! ")) {
                changes.add(new FileChange(r.substring(2), null, null, Kind.IGNORED, "!!"));
            }
        }
        // No "branch.ab" when the upstream is gone.
        final Relation up = upstream == null ? null : new Relation(upstream, null, ahead, behind);
        return new GitSnapshot(changes, head, branch, up, null, null, stashes, operation);
    }

    // v2 writes '.' for an unchanged side; the short format, which the
    // status bar shows, a space.
    private static FileChange change(String path, String orig, String xy) {
        String code = xy.replace('.', ' ');
        return new FileChange(path, orig, Kind.of(xy.charAt(0)), Kind.of(xy.charAt(1)), code);
    }

    private static List<String> split(byte[] output) {
        List<String> records = new ArrayList<>();
        int start = 0;
        for (int k = 0; k <= output.length; k++) {
            if (k == output.length || output[k] == 0) {
                if (k > start)
                    records.add(new String(output, start, k - start, StandardCharsets.UTF_8));
                start = k + 1;
            }
        }
        return records;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }
}
