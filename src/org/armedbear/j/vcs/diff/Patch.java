/*
 * Patch.java
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

package org.armedbear.j.vcs.diff;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Patches of part of a diff, for `git apply` (or `svn patch`) to stage,
 * unstage or discard.
 *
 * <p>Forward, a patch applies to the diff's old side: lines left out are
 * taken back to the old side, a '-' kept as context and a '+' dropped. In
 * reverse it applies with -R to the new side, so a '+' left out is kept as
 * context and a '-' dropped. Staging an unstaged hunk is forward; unstaging
 * a staged hunk, or discarding an unstaged one, is reverse.
 */
public final class Patch {
    private Patch() {}

    /** The whole hunk, or null if it changes nothing. */
    public static byte[] forHunk(FileDiff file, Hunk hunk, boolean reverse) {
        BitSet all = new BitSet();
        all.set(0, hunk.lines().size());
        return forLines(file, hunk, all, reverse);
    }

    /** The hunk's lines from (inclusive) to (exclusive), or null if they change nothing. */
    public static byte[] forLines(FileDiff file, Hunk hunk, int from, int to, boolean reverse) {
        BitSet selected = new BitSet();
        int start = Math.max(0, from);
        int end = Math.min(to, hunk.lines().size());
        if (start < end)
            selected.set(start, end);
        return forLines(file, hunk, selected, reverse);
    }

    /** The hunk's selected lines (indexes into its lines), or null if they change nothing. */
    public static byte[] forLines(FileDiff file, Hunk hunk, BitSet selected, boolean reverse) {
        if (file.isBinary())
            return null;
        Hunk part = select(hunk, selected, reverse);
        if (part == null)
            return null;
        StringBuilder sb = new StringBuilder();
        // Only a patch of all the file's changes takes its rename or mode change.
        boolean whole = file.hunks().size() == 1 && part.lines().size() == hunk.lines().size() && sameKinds(part, hunk);
        for (String line : header(file, whole, reverse))
            sb.append(line).append('\n');
        part.appendTo(sb);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The part of hunk the selection makes, or null if it changes nothing. */
    static Hunk select(Hunk hunk, BitSet selected, boolean reverse) {
        List<DiffLine> out = new ArrayList<>();
        List<DiffLine> lines = hunk.lines();
        for (int k = 0; k < lines.size(); k++) {
            DiffLine line = lines.get(k);
            if (line.isContext() || selected.get(k)) {
                out.add(line);
            } else if (line.isRemoved()) {
                if (!reverse)
                    out.add(line.as(' ', line.noNewline()));
            } else if (reverse) {
                out.add(line.as(' ', line.noNewline()));
            }
        }
        fixNoNewline(out);
        Hunk part;
        if (reverse) {
            // Applied with -R to the new side, so its start is the one kept.
            int newCount = count(out, true);
            part = Hunk.of(start(hunk.newStart(), newCount, count(out, false)), hunk.newStart(), hunk.section(), out);
        } else {
            int oldCount = count(out, false);
            part = Hunk.of(hunk.oldStart(), start(hunk.oldStart(), oldCount, count(out, true)), hunk.section(), out);
        }
        return part.hasChanges() ? part : null;
    }

    /*
     * Leaving lines out can leave a line that ends its file without a newline
     * followed by others on its side, which no file can be. That side is the
     * result, never the side the patch must match, so the line gains its
     * newline there: a '-' or '+' line loses the mark, and a context line is
     * split into a '-' and a '+' that differ only in the newline.
     */
    private static void fixNoNewline(List<DiffLine> lines) {
        for (int k = 0; k < lines.size(); k++) {
            DiffLine line = lines.get(k);
            if (!line.noNewline())
                continue;
            boolean notLastOld = line.isOld() && hasAfter(lines, k, true);
            boolean notLastNew = line.isNew() && hasAfter(lines, k, false);
            if (line.isRemoved() && notLastOld) {
                lines.set(k, line.as('-', false));
            } else if (line.isAdded() && notLastNew) {
                lines.set(k, line.as('+', false));
            } else if (notLastNew) {
                lines.set(k, line.as('-', true));
                lines.add(k + 1, line.as('+', false));
                k++;
            } else if (notLastOld) {
                lines.set(k, line.as('-', false));
                lines.add(k + 1, line.as('+', true));
                k++;
            }
        }
    }

    private static boolean hasAfter(List<DiffLine> lines, int k, boolean old) {
        for (int j = k + 1; j < lines.size(); j++) {
            DiffLine line = lines.get(j);
            if (old ? line.isOld() : line.isNew())
                return true;
        }
        return false;
    }

    private static int count(List<DiffLine> lines, boolean newSide) {
        int n = 0;
        for (DiffLine line : lines) {
            if (newSide ? line.isNew() : line.isOld())
                n++;
        }
        return n;
    }

    /*
     * The other side's start, for a hunk with no change before it: the same
     * line, except that an empty side starts at the line before.
     */
    private static int start(int start, int count, int otherCount) {
        if (count == 0 && otherCount > 0)
            return start + 1;
        if (otherCount == 0 && count > 0)
            return start - 1;
        return start;
    }

    private static boolean sameKinds(Hunk a, Hunk b) {
        for (int k = 0; k < a.lines().size(); k++) {
            if (a.lines().get(k).kind() != b.lines().get(k).kind())
                return false;
        }
        return true;
    }

    /*
     * All of a file: its header, without the "index" line, whose hashes a
     * patch need not match. Part of it: a change to the file under the path
     * it has on the side the patch applies to, with no rename or mode change.
     * Part of a new file, staged, still makes the file, and part of a
     * deletion, unstaged, still brings it back; part of a deletion, staged,
     * or of a new file, unstaged, leaves the file in place.
     */
    private static List<String> header(FileDiff file, boolean whole, boolean reverse) {
        List<String> out = new ArrayList<>();
        if (whole) {
            for (String line : file.header()) {
                if (!line.startsWith("index "))
                    out.add(line);
            }
            return out;
        }
        final boolean git = !file.header().isEmpty() && file.header().get(0).startsWith("diff --git ");
        final String a = git ? "a/" : "";
        final String b = git ? "b/" : "";
        final String path = file.rawPath();
        final boolean creates = file.isNew() && !reverse;
        final boolean deletes = file.isDeleted() && reverse;
        if (git)
            out.add("diff --git " + quote(a + path) + " " + quote(b + path));
        for (String line : file.header()) {
            if (creates && line.startsWith("new file mode") || deletes && line.startsWith("deleted file mode"))
                out.add(line);
        }
        out.add("--- " + (creates ? "/dev/null" : quote(a + path)));
        out.add("+++ " + (deletes ? "/dev/null" : quote(b + path)));
        return out;
    }

    // Git's quoting, for the characters it always quotes.
    static String quote(String path) {
        boolean plain = true;
        for (int k = 0; k < path.length() && plain; k++) {
            char c = path.charAt(k);
            plain = c >= ' ' && c != '"' && c != '\\' && c != 0x7f;
        }
        if (plain)
            return path;
        StringBuilder sb = new StringBuilder("\"");
        for (int k = 0; k < path.length(); k++) {
            char c = path.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ' || c == 0x7f)
                        sb.append('\\').append(String.format("%03o", (int) c));
                    else
                        sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
