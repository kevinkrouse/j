/*
 * StatusRenderer.java
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

package org.armedbear.j.vcs.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.status.StatusData.CommitItem;
import org.armedbear.j.vcs.status.StatusData.Field;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Item;
import org.armedbear.j.vcs.status.StatusData.Section;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/** The lines of a status buffer: its repositories' data, with what is expanded shown. */
final class StatusRenderer {
    /** Before a file, a hunk, a diff line or a commit. */
    static final String INDENT = "  ";

    /** A repository and what was read of it; colorIndex is -1 when it is the only one. */
    record Part(VcsRepository repository, StatusData data, int colorIndex) {}

    /**
     * @param expanded whether a node, by key and kind, is shown open
     * @param diffRead whether a file's diff, by key, has been read, so that
     *     none means it has none
     */
    static List<StatusLine> render(List<Part> parts, BiPredicate<String, Kind> expanded, Predicate<String> diffRead) {
        final StatusRenderer r = new StatusRenderer(expanded, diffRead);
        for (Part part : parts)
            r.part(part, parts.size() > 1);
        return r.lines;
    }

    private final BiPredicate<String, Kind> expanded;
    private final Predicate<String> diffRead;
    private final List<StatusLine> lines = new ArrayList<>();
    private int blanks;

    private StatusRenderer(BiPredicate<String, Kind> expanded, Predicate<String> diffRead) {
        this.expanded = expanded;
        this.diffRead = diffRead;
    }

    private void part(Part part, boolean several) {
        final VcsRepository repository = part.repository();
        final String rk = repository.root().toString();
        if (several) {
            if (!lines.isEmpty())
                blank(rk);
            add(
                new StatusLine(Kind.REPOSITORY,
                        repository.root().getFileName() + "  " + repository.root(),
                        rk,
                        rk,
                        repository,
                        null).expandable(true)
                        .open(expanded.test(rk, Kind.REPOSITORY))
                        .repositoryColor(part.colorIndex()));
            if (!expanded.test(rk, Kind.REPOSITORY))
                return;
        }
        final StatusData data = part.data();
        if (data.error() != null) {
            add(new StatusLine(Kind.MESSAGE, "Error: " + data.error(), rk + "|error", null, repository, null));
            return;
        }
        for (Field f : data.header())
            add(
                new StatusLine(Kind.FIELD,
                        String.format("%-10s%s", f.label() + ":", f.value()),
                        rk + "|field|" + f.label(),
                        null,
                        repository,
                        null));
        if (data.inProgress() != null) {
            blank(rk);
            add(new StatusLine(Kind.MESSAGE, data.inProgress(), rk + "|progress", null, repository, null));
        }
        if (data.sections().stream().noneMatch(s -> s.items().stream().anyMatch(i -> i instanceof FileItem))) {
            blank(rk);
            add(
                new StatusLine(Kind.MESSAGE,
                        "Nothing to commit, working tree clean",
                        rk + "|clean",
                        null,
                        repository,
                        null));
        }
        for (Section s : data.sections())
            section(repository, rk, s);
    }

    private void section(VcsRepository repository, String rk, Section s) {
        blank(rk);
        final String sk = rk + "|" + s.id();
        add(
            new StatusLine(Kind.SECTION, s.title() + " (" + s.items().size() + ")", sk, sk, repository, s.id())
                    .expandable(true)
                    .open(expanded.test(sk, Kind.SECTION)));
        if (!expanded.test(sk, Kind.SECTION))
            return;
        for (Item item : s.items()) {
            switch (item) {
                case FileItem f -> file(repository, sk, s.id(), f);
                case CommitItem c -> {
                    final String refs = c.refs().isBlank() ? "" : "(" + c.refs() + ") ";
                    add(
                        new StatusLine(Kind.COMMIT,
                                INDENT + c.hash() + " " + refs + c.subject(),
                                sk + "|" + c.hash(),
                                null,
                                repository,
                                s.id()).commit(c));
                }
            }
        }
    }

    private void file(VcsRepository repository, String sk, String section, FileItem f) {
        final String fk = sk + "|" + f.path();
        final String orig = f.change().origPath();
        final String name = orig != null && f.staged() ? orig + " -> " + f.path() : f.path();
        add(
            new StatusLine(Kind.FILE, INDENT + f.letter() + "  " + name, fk, fk, repository, section).file(f)
                    .expandable(f.hasDiff())
                    .open(f.hasDiff() && expanded.test(fk, Kind.FILE)));
        if (!f.hasDiff() || !expanded.test(fk, Kind.FILE))
            return;
        final FileDiff diff = f.diff();
        if (diff == null) {
            final String message = diffRead.test(fk) ? "No diff: see vcsCommandLog" : "Reading the diff…";
            add(new StatusLine(Kind.MESSAGE, INDENT + message, fk + "|reading", fk, repository, section).file(f));
            return;
        }
        final String none = diff.isBinary() ? "Binary file"
                : !diff.hunks().isEmpty() ? null
                : diff.isRename() ? "Renamed, with no change to its content"
                : "No change to its content";
        if (none != null) {
            add(new StatusLine(Kind.MESSAGE, INDENT + none, fk + "|none", fk, repository, section).file(f));
            return;
        }
        // A hunk is known by what it changes, not where it is, so that a
        // change above it leaves it open or closed and the caret in it.
        final Map<String, Integer> seen = new HashMap<>();
        for (Hunk h : diff.hunks()) {
            final String id = changes(h);
            final int n = seen.merge(id, 1, Integer::sum);
            hunk(repository, fk, section, f, h, id + "#" + n);
        }
    }

    // What a hunk removes and adds, as a short key.
    private static String changes(Hunk hunk) {
        final StringBuilder sb = new StringBuilder();
        for (DiffLine d : hunk.lines())
            if (!d.isContext())
                sb.append(d.kind()).append(d.text()).append('\n');
        return Integer.toHexString(sb.toString().hashCode());
    }

    private void hunk(VcsRepository repository, String fk, String section, FileItem f, Hunk hunk, String id) {
        final String hk = fk + "|@" + id;
        add(
            new StatusLine(Kind.HUNK, INDENT + hunk.header(), hk, hk, repository, section).file(f)
                    .hunk(hunk)
                    .expandable(true)
                    .open(expanded.test(hk, Kind.HUNK)));
        if (!expanded.test(hk, Kind.HUNK))
            return;
        final List<DiffLine> diffLines = hunk.lines();
        final List<StatusLine> shown = new ArrayList<>();
        int oldLine = hunk.oldStart();
        int newLine = hunk.newStart();
        for (int i = 0; i < diffLines.size(); i++) {
            final DiffLine d = diffLines.get(i);
            final StatusLine line = new StatusLine(Kind.DIFF,
                    INDENT + d.kind() + detab(stripCr(FileDiff.decode(d.text()))),
                    hk + "|" + i,
                    hk,
                    repository,
                    section).file(f).hunk(hunk);
            if (d.isContext())
                line.diff(d, oldLine++, newLine++);
            else if (d.isRemoved())
                line.diff(d, oldLine++, newLine);
            else
                line.diff(d, 0, newLine++);
            shown.add(line);
            add(line);
        }
        words(shown);
    }

    // Pairs each run of removed lines with the run of added lines after it,
    // line by line, and marks the words that differ.
    private static void words(List<StatusLine> hunk) {
        final int skip = INDENT.length() + 1;
        int i = 0;
        while (i < hunk.size()) {
            if (!hunk.get(i).diffLine().isRemoved()) {
                i++;
                continue;
            }
            int removedEnd = i;
            while (removedEnd < hunk.size() && hunk.get(removedEnd).diffLine().isRemoved())
                removedEnd++;
            int addedEnd = removedEnd;
            while (addedEnd < hunk.size() && hunk.get(addedEnd).diffLine().isAdded())
                addedEnd++;
            // A long run, as a regenerated file has, is too costly to refine.
            final int pairs = removedEnd - i > MAX_PAIRS ? 0 : Math.min(removedEnd - i, addedEnd - removedEnd);
            for (int k = 0; k < pairs; k++) {
                final StatusLine removed = hunk.get(i + k);
                final StatusLine added = hunk.get(removedEnd + k);
                final WordDiff.Result r =
                        WordDiff.diff(removed.getText().substring(skip), added.getText().substring(skip));
                removed.setWords(shift(r.removed(), skip));
                added.setWords(shift(r.added(), skip));
            }
            i = addedEnd;
        }
    }

    // Tabs as spaces, to stops of TAB_WIDTH from the start of the file's
    // text, not the line's, so its columns line up as in the file.
    static String detab(String s) {
        if (s.indexOf('\t') < 0)
            return s;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\t')
                do
                    sb.append(' ');
                while (sb.length() % TAB_WIDTH != 0);
            else
                sb.append(c);
        }
        return sb.toString();
    }

    private static final int TAB_WIDTH = 8;
    private static final int MAX_PAIRS = 200;

    // A CRLF file's lines without the CR, which would show as a character.
    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static List<int[]> shift(List<int[]> ranges, int by) {
        final List<int[]> out = new ArrayList<>();
        for (int[] r : ranges)
            out.add(new int[] { r[0] + by, r[1] + by });
        return out;
    }

    private void blank(String rk) {
        add(new StatusLine(Kind.BLANK, "", rk + "|blank|" + blanks++, null, null, null));
    }

    private void add(StatusLine line) {
        lines.add(line);
    }
}
