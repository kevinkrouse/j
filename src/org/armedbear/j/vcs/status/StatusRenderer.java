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
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import org.armedbear.j.mode.compare.WordDiff;
import org.armedbear.j.vcs.FileChange;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.Changelists.Changelist;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.status.StatusData.CommitItem;
import org.armedbear.j.vcs.status.StatusData.Field;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusData.Item;
import org.armedbear.j.vcs.status.StatusData.Section;
import org.armedbear.j.vcs.status.StatusData.StashItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/** The lines of a status buffer: its repositories' data, with what is expanded shown. */
public final class StatusRenderer {
    /** Before a file, a hunk, a diff line or a commit. */
    static final String INDENT = "  ";

    /** After a section's key, its line saying how many more it has than it shows. */
    static final String MORE = "|more";
    /** The key's end of a repository's line of what it has in progress. */
    static final String PROGRESS = "|progress";

    /**
     * A repository and what was read of it; colorIndex is -1 when it is the
     * only one; its unstaged files in its changelists, null to show them as
     * one section.
     */
    record Part(VcsRepository repository, StatusData data, int colorIndex, ListedChanges listed) {
        Part(VcsRepository repository, StatusData data, int colorIndex) {
            this(repository, data, colorIndex, null);
        }
    }

    /** The section of the work tree's changes, which changelists sort. */
    static final String UNSTAGED = "unstaged";

    /**
     * @param expanded whether a node, by key and kind, is shown open
     * @param diffRead whether a file's diff, by key, has been read, so that
     *     none means it has none
     * @param ids a diff's hunks by their ids, as {@link #hunkIds}, read once
     */
    static List<StatusLine> render(
            List<Part> parts,
            BiPredicate<String, Kind> expanded,
            Predicate<String> diffRead,
            Function<FileDiff, Map<String, Hunk>> ids) {
        final StatusRenderer r = new StatusRenderer(expanded, diffRead, ids);
        for (Part part : parts)
            r.part(part, parts.size() > 1);
        return r.lines;
    }

    /**
     * Files' lines under key, as a status buffer's section has them, each
     * open to its hunks or not as expanded says: the log's files, and its
     * diff of one, in no section; the status's diff beside it, in the
     * status's section, so that staging there acts on what it would there.
     */
    public static List<StatusLine> renderFiles(
            VcsRepository repository,
            String key,
            String section,
            List<FileItem> files,
            BiPredicate<String, Kind> expanded) {
        final StatusRenderer r = new StatusRenderer(expanded, k -> true, StatusRenderer::hunkIds);
        for (FileItem f : files)
            r.file(repository, key, section, f);
        return r.lines;
    }

    private final BiPredicate<String, Kind> expanded;
    private final Predicate<String> diffRead;
    private final Function<FileDiff, Map<String, Hunk>> ids;
    private final List<StatusLine> lines = new ArrayList<>();
    private int blanks;

    private StatusRenderer(
            BiPredicate<String, Kind> expanded,
            Predicate<String> diffRead,
            Function<FileDiff, Map<String, Hunk>> ids) {
        this.expanded = expanded;
        this.diffRead = diffRead;
        this.ids = ids;
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
            add(new StatusLine(Kind.MESSAGE, data.inProgress(), rk + PROGRESS, null, repository, null));
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
            if (part.listed() != null && s.id().equals(UNSTAGED))
                changelists(repository, rk, s, part.listed());
            else
                section(repository, rk, s);
    }

    /**
     * After a split file's key, under another changelist than its first,
     * that list: in marks no path or name has.
     */
    static String elsewhere(String list) {
        return "|" + ELSEWHERE + list + ELSEWHERE;
    }

    /** The mark before and after a changelist's name in a split file's key, under another list. */
    static final char ELSEWHERE = '\u0001';

    /** key, a file's or hunk's under another changelist than its file's first, as under that first. */
    static String first(String key) {
        final int at = key.indexOf("|" + ELSEWHERE);
        if (at < 0)
            return key;
        final int end = key.indexOf(ELSEWHERE, at + 2);
        return end < 0 ? key : key.substring(0, at) + key.substring(end + 1);
    }

    /**
     * The key of the heading of r's changelist name, in the section of
     * the work tree's changes; no path being empty, unlike any file's.
     */
    static String listKey(VcsRepository r, String name) {
        return sectionKey(r, UNSTAGED) + "||" + name;
    }

    // The work tree's changes, s, as changelists: a heading for each, with
    // its files, a file split across lists in each with its lines there,
    // its first's lines keyed as the section's, the others' apart.
    private void changelists(VcsRepository repository, String rk, Section s, ListedChanges listed) {
        blank(rk);
        final String sk = rk + "|" + s.id();
        add(
            new StatusLine(Kind.SECTION, "Changelists (" + s.total() + ")", sk, sk, repository, s.id()).expandable(true)
                    .open(expanded.test(sk, Kind.SECTION)));
        if (!expanded.test(sk, Kind.SECTION))
            return;
        final Set<String> shown = new HashSet<>();
        for (Changelist l : listed.lists()) {
            final List<StatusLine.Member> members = listed.members().get(l.name());
            final String lk = listKey(repository, l.name());
            final boolean open = expanded.test(lk, Kind.LIST);
            final String text = INDENT + l.name() + " (" + members.size() + ")"
                    + (l.name().equals(listed.active()) ? "  active" : "");
            add(
                new StatusLine(Kind.LIST, text, lk, lk, repository, s.id()).members(members)
                        .expandable(true)
                        .open(open));
            // Keyed in the lists' order, open or not, so a key stays its file's.
            for (StatusLine.Member m : members) {
                final String suffix = shown.add(m.file().path()) ? "" : elsewhere(l.name());
                if (open)
                    file(repository, sk, s.id(), m.file(), INDENT, suffix, m.only());
            }
        }
    }

    private void section(VcsRepository repository, String rk, Section s) {
        blank(rk);
        final String sk = rk + "|" + s.id();
        add(
            new StatusLine(Kind.SECTION, s.title() + " (" + s.total() + ")", sk, sk, repository, s.id())
                    .expandable(true)
                    .open(expanded.test(sk, Kind.SECTION)));
        if (!expanded.test(sk, Kind.SECTION))
            return;
        for (Item item : s.items()) {
            switch (item) {
                case FileItem f -> file(repository, sk, s.id(), f);
                case CommitItem c -> {
                    add(
                        new StatusLine(Kind.COMMIT, INDENT + c.text(), sk + "|" + c.hash(), null, repository, s.id())
                                .commit(c));
                }
                case StashItem st -> stash(repository, sk, s.id(), st);
            }
        }
        if (s.total() > s.items().size())
            add(
                new StatusLine(Kind.MESSAGE,
                        INDENT + "… " + (s.total() - s.items().size()) + " more",
                        sk + MORE,
                        null,
                        repository,
                        s.id()));
    }

    // A stash's line, a commit's that opens to its files, read when first opened.
    private void stash(VcsRepository repository, String sk, String section, StashItem st) {
        final String key = sk + "|" + st.hash();
        final boolean open = expanded.test(key, Kind.COMMIT);
        add(
            new StatusLine(Kind.COMMIT, INDENT + st.name() + ": " + st.subject(), key, key, repository, section)
                    .commit(st.commit())
                    .stash(st)
                    .expandable(true)
                    .open(open));
        if (!open)
            return;
        if (st.files() == null) {
            final String message = diffRead.test(key) ? "No diff: see vcsCommandLog" : "Reading the stash…";
            add(new StatusLine(Kind.MESSAGE, INDENT + message, key + "|reading", key, repository, section));
            return;
        }
        for (FileItem f : st.files())
            file(repository, key, section, f);
    }

    private void file(VcsRepository repository, String sk, String section, FileItem f) {
        file(repository, sk, section, f, "", "", null);
    }

    /**
     * A file's line, and its hunks' as it is open: in, more indent, under
     * a changelist; suffix after its key, a file split across lists shown
     * under another; only, its lines in the list, by hunk id, null for all.
     */
    private void file(
            VcsRepository repository,
            String sk,
            String section,
            FileItem f,
            String in,
            String suffix,
            Map<String, BitSet> only) {
        final String fk = sk + "|" + f.path() + suffix;
        final String orig = f.change().origPath();
        final String name = orig != null && f.staged() ? orig + " -> " + f.path() : f.path();
        final String shown = f.change().conflict() == null ? name : name + " (" + f.change().conflict().words() + ")";
        add(
            new StatusLine(Kind.FILE, in + INDENT + f.letter() + "  " + shown, fk, fk, repository, section).file(f)
                    .only(only)
                    .expandable(f.hasDiff())
                    .open(f.hasDiff() && expanded.test(fk, Kind.FILE)));
        if (!f.hasDiff() || !expanded.test(fk, Kind.FILE))
            return;
        final FileDiff diff = f.diff();
        if (diff == null) {
            final String message = diffRead.test(fk) ? "No diff: see vcsCommandLog" : "Reading the diff…";
            add(new StatusLine(Kind.MESSAGE, in + INDENT + message, fk + "|reading", fk, repository, section).file(f));
            return;
        }
        final String none = diff.isBinary() ? "Binary file"
                : !diff.hunks().isEmpty() ? null
                : diff.isCombined() ? noCombined(f.change().conflict())
                : diff.onlyWhitespace() ? "Only whitespace changed, which is ignored"
                : diff.isRename() ? "Renamed, with no change to its content"
                : "No change to its content";
        if (none != null) {
            add(new StatusLine(Kind.MESSAGE, in + INDENT + none, fk + "|none", fk, repository, section).file(f));
            return;
        }
        for (Map.Entry<String, Hunk> e : ids.apply(diff).entrySet())
            if (only == null)
                hunk(repository, fk, section, f, e.getValue(), e.getKey(), in, null);
            else if (only.containsKey(e.getKey()))
                hunk(repository, fk, section, f, e.getValue(), e.getKey(), in, only.get(e.getKey()));
    }

    /** The key of r's section's line. */
    static String sectionKey(VcsRepository r, String section) {
        return r.root() + "|" + section;
    }

    /** The key of the line of r's section's file at path. */
    static String fileKey(VcsRepository r, String section, String path) {
        return sectionKey(r, section) + "|" + path;
    }

    /** The key of the line of the hunk id of the file whose line's key is fk. */
    static String hunkKey(String fk, String id) {
        return fk + HUNK + id;
    }

    /** The key of the file's line from that of its hunk's, hk; hk if it is not a hunk's. */
    static String fileKeyOf(String hk) {
        final int at = hk.lastIndexOf(HUNK);
        return at < 0 ? hk : hk.substring(0, at);
    }

    /** The id of the hunk whose line's key is hk. */
    static String hunkId(String hk) {
        return hk.substring(hk.lastIndexOf(HUNK) + HUNK.length());
    }

    // Between a file's line's key and a hunk's id; an id has no '|', so the
    // last of these in a key, whatever the path, ends the file's.
    private static final String HUNK = "|@";

    /**
     * diff's hunks by their ids, in order: a hunk is known by what it
     * changes, not where it is, so that a change above it leaves it open
     * or closed, the caret in it, and its pick; {@link #hunkKey} makes the
     * hunk's line's key of one.
     */
    static Map<String, Hunk> hunkIds(FileDiff diff) {
        final Map<String, Hunk> out = new LinkedHashMap<>();
        final Map<String, Integer> seen = new HashMap<>();
        for (Hunk h : diff.hunks()) {
            final String id = changes(h);
            out.put(id + "#" + seen.merge(id, 1, Integer::sum), h);
        }
        return out;
    }

    // Why an unmerged file has no combined diff.
    private static String noCombined(FileChange.Conflict kind) {
        if (kind == FileChange.Conflict.BOTH_DELETED)
            return "Deleted by both: nothing to show";
        if (kind == FileChange.Conflict.BOTH_MODIFIED || kind == FileChange.Conflict.BOTH_ADDED)
            return "No line differs from both sides";
        return "A side has no file: = shows the other";
    }

    // What a hunk removes and adds, as a short key.
    private static String changes(Hunk hunk) {
        final StringBuilder sb = new StringBuilder();
        for (DiffLine d : hunk.lines())
            if (!d.isContext())
                sb.append(d.kind()).append(d.text()).append('\n');
        return Integer.toHexString(sb.toString().hashCode());
    }

    // A hunk's line, and its lines as it is open; under a changelist, in
    // more, mine its lines in it, null for all, the others dimmed.
    private void hunk(
            VcsRepository repository,
            String fk,
            String section,
            FileItem f,
            Hunk hunk,
            String id,
            String in,
            BitSet mine) {
        final String hk = hunkKey(fk, id);
        add(
            new StatusLine(Kind.HUNK, in + INDENT + hunk.header(), hk, hk, repository, section).file(f)
                    .hunk(hunk)
                    .only(mine == null ? null : Map.of(id, mine))
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
                    in + INDENT + d.prefix() + detab(stripCr(FileDiff.decode(d.text()))),
                    hk + "|" + i,
                    hk,
                    repository,
                    section).file(f).hunk(hunk).foreign(mine != null && !d.isContext() && !mine.get(i));
            // On the old side, or a combined diff's first parent, ours.
            line.diff(d, i, d.isOld() ? oldLine++ : 0, d.isNew() ? newLine++ : newLine);
            shown.add(line);
            add(line);
        }
        // A combined diff's two columns of markers: no words to pair.
        if (!hunk.isCombined())
            words(shown, in.length() + INDENT.length() + 1);
    }

    // Pairs each run of removed lines with the run of added lines after it,
    // line by line, and marks the words that differ, skip into the text.
    private static void words(List<StatusLine> hunk, int skip) {
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
