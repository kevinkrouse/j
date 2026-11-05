/*
 * StatusPicks.java
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
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.armedbear.j.util.PickState;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.diff.Patch;
import org.armedbear.j.vcs.signs.StatusView;
import org.armedbear.j.vcs.status.StatusActions.Files;
import org.armedbear.j.vcs.status.StatusActions.Part;
import org.armedbear.j.vcs.status.StatusActions.Target;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/**
 * What is picked in a status, for the commands that take targets to take
 * with no selection, in place of the caret: files whole, hunks, and lines of
 * hunks, each in its section. By file, as the status keys their lines, a
 * hunk by what it changes, so a pick survives a refresh and edits
 * elsewhere. In memory, never saved. On the event thread.
 */
final class StatusPicks {
    /** A file picked, whole or in part: its repository, section and path. */
    record Pick(VcsRepository repository, String section, String path) {}

    // A file's picks: whole, or its hunks' by id in the order picked.
    private static final class FilePicks {
        final Pick file;
        boolean whole;
        final Map<String, HunkPick> hunks = new LinkedHashMap<>();

        FilePicks(Pick file) {
            this.file = file;
        }
    }

    // A hunk's pick: its lines' indexes, null for all of it; twins, the
    // hunk's and those then with the same changes, whose ids tell them
    // apart only by their order.
    private record HunkPick(BitSet lines, int twins) {}

    // A diff's hunks by id, and how many have each id's changes.
    private record Ids(Map<String, Hunk> hunks, Map<String, Integer> twins) {
        static Ids of(FileDiff diff) {
            final Map<String, Hunk> hunks = StatusRenderer.hunkIds(diff);
            final Map<String, Integer> byChanges = new HashMap<>();
            for (String id : hunks.keySet())
                byChanges.merge(changes(id), 1, Integer::sum);
            final Map<String, Integer> twins = new HashMap<>();
            for (String id : hunks.keySet())
                twins.put(id, byChanges.get(changes(id)));
            return new Ids(hunks, twins);
        }

        // What an id's hunk changes, its order among its twins left off.
        private static String changes(String id) {
            return id.substring(0, id.lastIndexOf('#'));
        }
    }

    // By the key of the file's line.
    private final Map<String, FilePicks> files = new LinkedHashMap<>();
    // Worked out from what is picked, and the status; null until asked for
    // again after either changes: the keys of the files picked in part,
    // the picks as targets and the keys of those each target takes.
    private Set<String> parts;
    private List<Target> targets;
    private Map<Target, Set<String>> targetKeys;
    // Each diff's hunks' ids, and each section's files by path, by the
    // section's key, as the status has them now.
    private final Map<FileDiff, Ids> ids = new IdentityHashMap<>();
    private final Map<String, Map<String, FileItem>> items = new HashMap<>();

    boolean isEmpty() {
        return files.isEmpty();
    }

    void clear() {
        dirty();
        files.clear();
    }

    /** The keys of the picks each of targets, as targets made them, takes. */
    Map<Target, Set<String>> used(List<Target> targets) {
        final Map<Target, Set<String>> out = new IdentityHashMap<>();
        for (Target t : targets)
            out.put(t, targetKeys == null ? Set.of() : targetKeys.getOrDefault(t, Set.of()));
        return out;
    }

    /** Unpicks those of keys, files' and hunks', still picked. */
    void remove(Set<String> keys) {
        dirty();
        for (String k : keys) {
            final FilePicks whole = files.get(k);
            if (whole != null) {
                if (whole.whole)
                    files.remove(k);
                continue;
            }
            final String fk = StatusRenderer.fileKeyOf(k);
            final FilePicks fp = files.get(fk);
            if (fp != null && !fp.whole) {
                fp.hunks.remove(StatusRenderer.hunkId(k));
                prune(fk, fp);
            }
        }
    }

    /** The status is read again: its files and their hunks' ids are looked up again. */
    void newRead() {
        ids.clear();
        items.clear();
        targets = null;
        targetKeys = null;
    }

    // What is picked changed: what is worked out from it is again.
    private void dirty() {
        parts = null;
        targets = null;
        targetKeys = null;
    }

    /** diff's hunks by their ids, as {@link StatusRenderer#hunkIds}, read once a render. */
    Map<String, Hunk> ids(FileDiff diff) {
        return idsOf(diff).hunks();
    }

    private Ids idsOf(FileDiff diff) {
        return ids.computeIfAbsent(diff, Ids::of);
    }

    // How many of d's hunks have the changes the hunk id has, it among them.
    private int twins(FileDiff d, String id) {
        return d == null ? 0 : idsOf(d).twins().getOrDefault(id, 0);
    }

    /**
     * Whether l is a thing that can be picked: a file, a hunk, a changed
     * line, not of a combined diff's nor a stash's.
     */
    static boolean pickable(StatusLine l) {
        if (StatusData.STASHES.equals(l.section()))
            return false;
        return switch (l.kind()) {
            case FILE -> l.file() != null && l.repository() != null && l.section() != null;
            case HUNK -> l.hunk() != null && !l.hunk().isCombined();
            case DIFF -> l.hunk() != null && !l.hunk().isCombined() && !l.diffLine().isContext();
            default -> false;
        };
    }

    /** How much of what l shows is picked. */
    PickState state(StatusLine l) {
        if (!pickable(l))
            return PickState.NONE;
        return switch (l.kind()) {
            case FILE -> fileState(l);
            case HUNK -> hunkState(StatusRenderer.fileKeyOf(l.key()), StatusRenderer.hunkId(l.key()));
            case DIFF -> linePicked(StatusRenderer.fileKeyOf(l.node()), StatusRenderer.hunkId(l.node()), l.diffIndex())
                    ? PickState.ALL
                    : PickState.NONE;
            default -> PickState.NONE;
        };
    }

    // A file's line's: picked whole, or each of its hunks, as its diff is
    // read, is; else picked in part, or not.
    private PickState fileState(StatusLine file) {
        final FilePicks fp = files.get(file.key());
        if (fp == null)
            return PickState.NONE;
        if (fp.whole)
            return PickState.ALL;
        final FileDiff d = file.file().diff();
        if (d == null || d.hunks().isEmpty() || fp.hunks.size() != d.hunks().size())
            return PickState.SOME;
        for (String id : ids(d).keySet()) {
            final HunkPick hp = fp.hunks.get(id);
            if (hp == null || hp.lines() != null)
                return PickState.SOME;
        }
        return PickState.ALL;
    }

    /**
     * Picks each of ls that can be, or, if each is picked already, unpicks
     * them; a file's line among them takes its hunks and lines.
     */
    void toggle(List<StatusLine> ls) {
        set(ls, ls.stream().filter(StatusPicks::pickable).anyMatch(l -> state(l) != PickState.ALL));
    }

    /**
     * Picks each of ls that can be, if on, else unpicks it; a file's line
     * among them takes its hunks and lines, and a hunk's lines are set
     * together.
     */
    void set(List<StatusLine> ls, boolean on) {
        final Set<String> whole = new HashSet<>();
        for (StatusLine l : ls)
            if (l.kind() == Kind.FILE && pickable(l)) {
                whole.add(l.key());
                setFile(l, on);
            }
        // The lines of each hunk, by its line's key: the first, and their indexes.
        final Map<String, StatusLine> firsts = new LinkedHashMap<>();
        final Map<String, BitSet> idx = new HashMap<>();
        for (StatusLine l : ls) {
            if (l.kind() == Kind.FILE || !pickable(l))
                continue;
            final String hk = l.kind() == Kind.HUNK ? l.key() : l.node();
            final String fk = StatusRenderer.fileKeyOf(hk);
            if (whole.contains(fk))
                continue;
            if (l.kind() == Kind.HUNK)
                setHunk(fk, StatusRenderer.hunkId(hk), l.repository(), l.section(), l.file(), on);
            else {
                firsts.putIfAbsent(hk, l);
                idx.computeIfAbsent(hk, k -> new BitSet()).set(l.diffIndex());
            }
        }
        for (Map.Entry<String, StatusLine> e : firsts.entrySet()) {
            final StatusLine l = e.getValue();
            setLines(
                StatusRenderer.fileKeyOf(e.getKey()),
                StatusRenderer.hunkId(e.getKey()),
                l.repository(),
                l.section(),
                l.file(),
                l.hunk(),
                idx.get(e.getKey()),
                on);
        }
    }

    // A file's line's pick set, its hunks' with it, or cleared.
    private void setFile(StatusLine l, boolean on) {
        dirty();
        if (!on) {
            files.remove(l.key());
            return;
        }
        final FilePicks fp = of(l.key(), l.repository(), l.section(), l.file());
        fp.whole = true;
        fp.hunks.clear();
    }

    /** Whether anything of fk's file is picked. */
    boolean has(String fk) {
        return files.containsKey(fk);
    }

    /** How much of the hunk id of fk's file is picked. */
    PickState hunkState(String fk, String id) {
        final FilePicks fp = files.get(fk);
        if (fp == null)
            return PickState.NONE;
        if (fp.whole)
            return PickState.ALL;
        final HunkPick hp = fp.hunks.get(id);
        return hp == null ? PickState.NONE : hp.lines() == null ? PickState.ALL : PickState.SOME;
    }

    /** Whether line i of the hunk id of fk's file is picked. */
    boolean linePicked(String fk, String id, int i) {
        final FilePicks fp = files.get(fk);
        if (fp == null)
            return false;
        if (fp.whole)
            return true;
        final HunkPick hp = fp.hunks.get(id);
        return hp != null && (hp.lines() == null || hp.lines().get(i));
    }

    /** How much of the lines of fk's file that idx gives by hunk id is picked. */
    PickState linesState(String fk, Map<String, BitSet> idx) {
        boolean any = false;
        boolean all = true;
        for (Map.Entry<String, BitSet> e : idx.entrySet())
            for (int i = e.getValue().nextSetBit(0); i >= 0; i = e.getValue().nextSetBit(i + 1)) {
                final boolean picked = linePicked(fk, e.getKey(), i);
                any |= picked;
                all &= picked;
            }
        return all ? PickState.ALL : any ? PickState.SOME : PickState.NONE;
    }

    /** Picks the hunk id of r's section's file f, fk its line's key, or unpicks it if it is picked whole. */
    void toggleHunk(String fk, String id, VcsRepository r, String section, FileItem f) {
        setHunk(fk, id, r, section, f, hunkState(fk, id) != PickState.ALL);
    }

    /**
     * Picks the hunk id of r's section's file f, fk its line's key, if on,
     * else unpicks it; unpicked in a file picked whole, the file's other
     * hunks stay picked.
     */
    void setHunk(String fk, String id, VcsRepository r, String section, FileItem f, boolean on) {
        dirty();
        final FilePicks fp = files.get(fk);
        if (on) {
            if (fp == null || !fp.whole)
                of(fk, r, section, f).hunks.put(id, new HunkPick(null, twins(f.diff(), id)));
        } else if (fp != null) {
            if (fp.whole)
                split(fp, f, id, null);
            else
                fp.hunks.remove(id);
            prune(fk, fp);
        }
    }

    /**
     * Picks the lines of r's section's file f, fk its line's key, that
     * idx gives by hunk id, or, each picked already, unpicks them.
     */
    void toggleLines(String fk, VcsRepository r, String section, FileItem f, Map<String, BitSet> idx) {
        boolean on = false;
        for (Map.Entry<String, BitSet> e : idx.entrySet())
            for (int i = e.getValue().nextSetBit(0); i >= 0; i = e.getValue().nextSetBit(i + 1))
                on |= !linePicked(fk, e.getKey(), i);
        setLines(fk, r, section, f, idx, on);
    }

    /** Picks the lines of r's section's file f, fk its line's key, that idx gives by hunk id if on, else unpicks them. */
    void setLines(String fk, VcsRepository r, String section, FileItem f, Map<String, BitSet> idx, boolean on) {
        final Map<String, Hunk> hs = ids(f.diff());
        for (Map.Entry<String, BitSet> e : idx.entrySet())
            if (hs.containsKey(e.getKey()))
                setLines(fk, e.getKey(), r, section, f, hs.get(e.getKey()), e.getValue(), on);
    }

    // The lines idx of hunk h, id, of fk's file picked or unpicked, a
    // modified line's removal with its addition; all its changed lines
    // picked, the hunk is.
    private void setLines(
            String fk,
            String id,
            VcsRepository r,
            String section,
            FileItem f,
            Hunk h,
            BitSet some,
            boolean on) {
        dirty();
        final BitSet idx = (BitSet) some.clone();
        Patch.pairChanges(h, idx);
        FilePicks fp = files.get(fk);
        if (on) {
            if (fp != null && (fp.whole || hunkState(fk, id) == PickState.ALL))
                return;
            fp = of(fk, r, section, f);
            final BitSet changed = changed(h);
            final HunkPick hp = fp.hunks.computeIfAbsent(id, k -> new HunkPick(new BitSet(), twins(f.diff(), id)));
            hp.lines().or(idx);
            hp.lines().and(changed);
            if (hp.lines().equals(changed))
                fp.hunks.put(id, new HunkPick(null, hp.twins()));
            else if (hp.lines().isEmpty())
                fp.hunks.remove(id);
            prune(fk, fp);
            return;
        }
        if (fp == null)
            return;
        final BitSet left = changed(h);
        left.andNot(idx);
        if (fp.whole)
            split(fp, f, id, left);
        else {
            final HunkPick hp = fp.hunks.get(id);
            if (hp == null)
                return;
            if (hp.lines() == null)
                fp.hunks.put(id, new HunkPick(left, hp.twins()));
            else
                hp.lines().andNot(idx);
            if (fp.hunks.get(id).lines().isEmpty())
                fp.hunks.remove(id);
        }
        prune(fk, fp);
    }

    // A file picked whole made its hunks' picks, but the hunk id's, which
    // is some of its lines if given, or nothing.
    private void split(FilePicks fp, FileItem f, String id, BitSet some) {
        fp.whole = false;
        fp.hunks.clear();
        if (f.diff() == null)
            return;
        for (String other : ids(f.diff()).keySet())
            if (!other.equals(id))
                fp.hunks.put(other, new HunkPick(null, twins(f.diff(), other)));
            else if (some != null && !some.isEmpty())
                fp.hunks.put(other, new HunkPick(some, twins(f.diff(), other)));
    }

    // fk's picks, made if there are none.
    private FilePicks of(String fk, VcsRepository r, String section, FileItem f) {
        return files.computeIfAbsent(fk, k -> new FilePicks(new Pick(r, section, f.path())));
    }

    // fk's picks gone if nothing of the file is left picked.
    private void prune(String fk, FilePicks fp) {
        if (!fp.whole && fp.hunks.isEmpty())
            files.remove(fk);
    }

    // h's changed lines' indexes.
    private static BitSet changed(Hunk h) {
        final BitSet out = new BitSet();
        for (int i = 0; i < h.lines().size(); i++)
            if (!h.lines().get(i).isContext())
                out.set(i);
        return out;
    }

    /**
     * The picks as the status has them now, as targets: files whole, by
     * section, then hunks and lines of them, a modified line's removal with
     * its addition; one whose file or hunk is gone left out. Worked out
     * once until what is picked, or the status, changes.
     */
    List<Target> targets(VcsStatusBuffer status) {
        if (targets != null)
            return targets;
        final Map<Target, Set<String>> keys = new IdentityHashMap<>();
        // Files picked whole, by section's key, with their keys.
        final Map<String, List<FileItem>> wholes = new LinkedHashMap<>();
        final Map<String, Pick> sections = new HashMap<>();
        final Map<String, Set<String>> wholeKeys = new HashMap<>();
        final List<Target> parts = new ArrayList<>();
        for (Map.Entry<String, FilePicks> e : files.entrySet()) {
            final FilePicks fp = e.getValue();
            final FileItem f = item(status, fp.file);
            if (f == null)
                continue;
            if (fp.whole) {
                final String sk = StatusRenderer.sectionKey(fp.file.repository(), fp.file.section());
                wholes.computeIfAbsent(sk, k -> new ArrayList<>()).add(f);
                sections.putIfAbsent(sk, fp.file);
                wholeKeys.computeIfAbsent(sk, k -> new HashSet<>()).add(e.getKey());
                continue;
            }
            if (f.diff() == null)
                continue;
            for (Map.Entry<String, HunkPick> h : fp.hunks.entrySet()) {
                final Hunk hunk = ids(f.diff()).get(h.getKey());
                if (hunk == null)
                    continue;
                final BitSet picked = h.getValue().lines() == null ? null : (BitSet) h.getValue().lines().clone();
                if (picked != null)
                    Patch.pairChanges(hunk, picked);
                final Part p = new Part(fp.file.repository(), fp.file.section(), f, hunk, picked);
                parts.add(p);
                keys.put(p, Set.of(StatusRenderer.hunkKey(e.getKey(), h.getKey())));
            }
        }
        final List<Target> out = new ArrayList<>();
        for (Map.Entry<String, List<FileItem>> e : wholes.entrySet()) {
            final Pick s = sections.get(e.getKey());
            final Files t = new Files(s.repository(), s.section(), List.copyOf(e.getValue()));
            out.add(t);
            keys.put(t, wholeKeys.get(e.getKey()));
        }
        out.addAll(parts);
        targets = List.copyOf(out);
        targetKeys = keys;
        return targets;
    }

    /**
     * The rows, from 0, of f, fk its line's key, as the working tree has it,
     * of its changes picked: every row, the file picked whole; an added
     * line's own, and a deletion's above where it was, as signs draw them;
     * null with nothing picked or the hunks picked unread. A removed line
     * with lines added in its place marks no row: its sign is theirs.
     */
    StatusView.Picked rows(String fk, FileItem f) {
        final FilePicks fp = files.get(fk);
        if (fp == null)
            return null;
        if (fp.whole)
            return new StatusView.Picked(true, new BitSet());
        if (f.diff() == null)
            return null;
        final BitSet out = new BitSet();
        for (Map.Entry<String, HunkPick> e : fp.hunks.entrySet()) {
            final Hunk h = ids(f.diff()).get(e.getKey());
            if (h == null)
                continue;
            final BitSet lines = e.getValue().lines();
            final int[] rows = signRows(h);
            for (Hunk.Run r : h.runs())
                for (int i = r.addedCount() > 0 ? r.added() : r.start(); i < r.end(); i++)
                    if (lines == null || lines.get(i))
                        out.set(rows[i]);
        }
        return new StatusView.Picked(false, out);
    }

    /**
     * The row, from 0, of the working tree's file, of each of h's lines'
     * sign, as signs draw them: an added line's own; a removed one's that
     * of the line added for it, or past those, the last added; with none
     * added, the row above where it was, the first if none; -1 for a
     * context line.
     */
    static int[] signRows(Hunk h) {
        final int[] out = new int[h.lines().size()];
        Arrays.fill(out, -1);
        for (Hunk.Run r : h.runs()) {
            // Paired, the added line's; past those, the last added's.
            for (int k = 0; k < r.removedCount(); k++)
                out[r.start() + k] =
                        r.addedCount() > 0 ? r.newRow() + Math.min(k, r.paired() - 1) : Math.max(0, r.newRow() - 1);
            for (int k = 0; k < r.addedCount(); k++)
                out[r.added() + k] = r.newRow() + k;
        }
        return out;
    }

    /** Whether a hunk or lines of a file are picked, not the file whole. */
    boolean hasParts() {
        return !partlyPicked().isEmpty();
    }

    /** The files picked whole, by the keys of their lines, in order. */
    Map<String, Pick> wholeFiles() {
        final Map<String, Pick> out = new LinkedHashMap<>();
        for (Map.Entry<String, FilePicks> e : files.entrySet())
            if (e.getValue().whole)
                out.put(e.getKey(), e.getValue().file);
        return out;
    }

    /** The paths picked, whole or in part, with their repositories, in order, each once. */
    List<Pick> paths() {
        final Map<String, Pick> out = new LinkedHashMap<>();
        for (FilePicks fp : files.values())
            out.putIfAbsent(fp.file.repository().root() + "|" + fp.file.path(), fp.file);
        return List.copyOf(out.values());
    }

    /**
     * Drops the picks whose file the status no longer has in their
     * section, or whose hunk its file's diff, read, no longer has, or
     * whose file's diff a read asked for, by its key in asked, did not
     * come: how many were dropped, a file whole's or each hunk's one. None
     * while a write runs, which may be using them.
     */
    int retain(VcsStatusBuffer status, Set<String> asked) {
        if (status.isWriting())
            return 0;
        dirty();
        int dropped = 0;
        for (var it = files.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<String, FilePicks> e = it.next();
            final FilePicks fp = e.getValue();
            final FileItem f = item(status, fp.file);
            if (f == null) {
                dropped += fp.whole ? 1 : fp.hunks.size();
                it.remove();
                continue;
            }
            if (fp.whole)
                continue;
            final int before = fp.hunks.size();
            if (f.diff() == null) {
                if (asked.contains(e.getKey()))
                    fp.hunks.clear();
            } else {
                final Ids now = idsOf(f.diff());
                // A hunk's twin come or gone, its id may now be another's.
                fp.hunks.entrySet()
                        .removeIf(
                            h -> !now.hunks().containsKey(h.getKey())
                                    || now.twins().get(h.getKey()) != h.getValue().twins());
            }
            dropped += before - fp.hunks.size();
            if (fp.hunks.isEmpty())
                it.remove();
        }
        return dropped;
    }

    /** Of a file's line partly picked, its hunks picked in whole or part, and all its hunks; null otherwise. */
    int[] counts(StatusLine file) {
        if (file.kind() != Kind.FILE
                || file.file() == null
                || file.file().diff() == null
                || state(file) != PickState.SOME)
            return null;
        return new int[] { files.get(file.key()).hunks.size(), ids(file.file().diff()).size() };
    }

    /** Whether a hunk or lines are picked of a file whose diff the status has not read. */
    boolean unread(VcsStatusBuffer status) {
        for (FilePicks fp : files.values())
            if (!fp.whole) {
                final FileItem f = item(status, fp.file);
                if (f != null && f.diff() == null)
                    return true;
            }
        return false;
    }

    /** The keys of the files some of whose hunks or lines are picked, whose diffs a refresh must read. */
    Set<String> partlyPicked() {
        if (parts == null) {
            final Set<String> out = new HashSet<>();
            for (Map.Entry<String, FilePicks> e : files.entrySet())
                if (!e.getValue().whole)
                    out.add(e.getKey());
            parts = Set.copyOf(out);
        }
        return parts;
    }

    /** The file p is of, as the status has it now, or null; looked up by path once a read. */
    FileItem item(VcsStatusBuffer status, Pick p) {
        return items.computeIfAbsent(StatusRenderer.sectionKey(p.repository(), p.section()), k -> {
            final Map<String, FileItem> byPath = new HashMap<>();
            for (FileItem f : status.files(p.repository(), p.section()))
                byPath.put(f.path(), f);
            return byPath;
        }).get(p.path());
    }
}
