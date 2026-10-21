/*
 * EditorHunks.java
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

package org.armedbear.j.vcs.signs;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.armedbear.j.util.Diff;
import org.armedbear.j.util.Diff.Edit;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.diff.Patch;

/**
 * The hunks between two texts of a file, as patches git takes: the
 * buffer's against what is staged, to stage, or what is staged against the
 * last commit, to take back out. Lines are compared without their line
 * ends, and the patch keeps the old side's bytes, CRs and all.
 */
public final class EditorHunks {
    static final int CONTEXT = 3;

    /** A side's lines: for comparing, and as the patch writes them. */
    public record Side(List<String> lines, List<String> raw, boolean noNewline) {
        public static Side of(byte[] bytes, Charset charset) {
            final List<String> raw = new ArrayList<>();
            int start = 0;
            while (start < bytes.length) {
                int end = start;
                while (end < bytes.length && bytes[end] != '\n')
                    end++;
                raw.add(new String(bytes, start, end - start, StandardCharsets.ISO_8859_1));
                start = end + 1;
            }
            final boolean noNewline = bytes.length > 0 && bytes[bytes.length - 1] != '\n';
            final List<String> lines = new ArrayList<>();
            for (String r : raw) {
                final byte[] b = r.getBytes(StandardCharsets.ISO_8859_1);
                String s = new String(b, charset);
                if (s.endsWith("\r"))
                    s = s.substring(0, s.length() - 1);
                lines.add(s);
            }
            return new Side(lines, raw, noNewline);
        }

        /**
         * A buffer's lines, written as the file would be: in charset, each
         * with a CR if crlf. j keeps a file's last newline as an empty last
         * line, which git does not count; without it, the file ends with no
         * newline.
         */
        public static Side ofBuffer(List<String> bufferLines, Charset charset, boolean crlf) {
            final int n = bufferLines.size();
            final boolean noNewline = n > 0 && !bufferLines.get(n - 1).isEmpty();
            final List<String> lines = List.copyOf(noNewline || n == 0 ? bufferLines : bufferLines.subList(0, n - 1));
            final List<String> raw = new ArrayList<>();
            for (String l : lines)
                raw.add(new String((crlf ? l + "\r" : l).getBytes(charset), StandardCharsets.ISO_8859_1));
            return new Side(lines, raw, noNewline);
        }

        /**
         * Its lines as they are compared: the last one marked if it has no
         * newline, so that adding or dropping one is a change, as git has it.
         */
        public List<String> keys() {
            if (!noNewline || lines.isEmpty())
                return lines;
            final List<String> keys = new ArrayList<>(lines);
            keys.set(keys.size() - 1, keys.get(keys.size() - 1) + "\u0000no newline");
            return keys;
        }

        /** Whether its lines end in CR LF, as most of them do. */
        public boolean crlf() {
            int cr = 0;
            for (String r : raw)
                if (r.endsWith("\r"))
                    cr++;
            return !raw.isEmpty() && cr * 2 > raw.size();
        }
    }

    private EditorHunks() {}

    /** The edits from old to now. */
    public static List<Edit> edits(Side old, Side now) {
        return Diff.diff(old.keys(), now.keys());
    }

    /**
     * e, one of edits, as a hunk with up to CONTEXT lines around it: only
     * lines no edit changes, the same on both sides, so that the patch
     * applies to either.
     */
    public static Hunk hunk(Side old, Side now, Edit e, List<Edit> edits) {
        // The edits are in order: the ones either side bound the context.
        final int at = edits.indexOf(e);
        final int previousEnd = at > 0 ? edits.get(at - 1).oldStart() + edits.get(at - 1).oldCount() : 0;
        final int nextStart = at >= 0 && at + 1 < edits.size() ? edits.get(at + 1).oldStart() : old.lines().size();
        final int before = Math.min(CONTEXT, e.oldStart() - previousEnd);
        final int after = Math.min(CONTEXT, nextStart - (e.oldStart() + e.oldCount()));
        final List<DiffLine> lines = new ArrayList<>();
        final int oldLast = old.raw().size() - 1;
        final int newLast = now.raw().size() - 1;
        for (int i = e.oldStart() - before; i < e.oldStart(); i++)
            lines.add(new DiffLine(' ', old.raw().get(i), false));
        for (int i = e.oldStart(); i < e.oldStart() + e.oldCount(); i++)
            lines.add(new DiffLine('-', old.raw().get(i), i == oldLast && old.noNewline()));
        for (int i = e.newStart(); i < e.newStart() + e.newCount(); i++)
            lines.add(new DiffLine('+', now.raw().get(i), i == newLast && now.noNewline()));
        final int afterStart = e.oldStart() + e.oldCount();
        for (int i = afterStart; i < afterStart + after; i++)
            lines.add(new DiffLine(' ', old.raw().get(i), i == oldLast && old.noNewline()));
        int oldCount = 0;
        int newCount = 0;
        for (DiffLine d : lines) {
            if (d.isOld())
                oldCount++;
            if (d.isNew())
                newCount++;
        }
        // 1-based; a side with no lines names the line before it.
        final int oldStart = oldCount == 0 ? e.oldStart() - before : e.oldStart() - before + 1;
        final int newStart = newCount == 0 ? e.newStart() - before : e.newStart() - before + 1;
        return new Hunk(oldStart, oldCount, newStart, newCount, "", lines);
    }

    /** The file's diff of hunk, by its path in the repository. */
    public static FileDiff file(String path, Hunk hunk) {
        final String raw = new String(path.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        // git's header, which a patch of the whole of it is written with.
        final List<String> header = List.of(
            "diff --git " + Patch.quote("a/" + raw) + " " + Patch.quote("b/" + raw),
            "--- " + Patch.quote("a/" + raw),
            "+++ " + Patch.quote("b/" + raw));
        return new FileDiff(header, raw, raw, false, false, false, false, List.of(hunk));
    }

    /**
     * The patch of e, or with the lines of the new side between first and
     * last (0-based, inclusive) only those and what they replace; null if
     * that changes nothing.
     */
    public static byte[] patch(
            String path,
            Side old,
            Side now,
            List<Edit> edits,
            Edit e,
            int first,
            int last,
            boolean reverse) {
        final Hunk hunk = hunk(old, now, e, edits);
        final FileDiff file = file(path, hunk);
        if (first < 0)
            return Patch.forHunk(file, hunk, reverse);
        final BitSet selected = new BitSet();
        final List<DiffLine> lines = hunk.lines();
        int newLine = e.newStart();
        boolean any = false;
        for (int k = 0; k < lines.size(); k++) {
            final DiffLine d = lines.get(k);
            if (d.isAdded()) {
                if (newLine >= first && newLine <= last) {
                    selected.set(k);
                    any = true;
                }
                newLine++;
            }
        }
        // A deletion is taken whole when the line it is under is selected.
        if (!any && e.newCount() == 0 && e.newStart() - 1 <= last && e.newStart() >= first)
            for (int k = 0; k < lines.size(); k++)
                if (lines.get(k).isRemoved())
                    selected.set(k);
        Patch.pairChanges(hunk, selected);
        return Patch.forLines(file, hunk, selected, reverse);
    }
}
