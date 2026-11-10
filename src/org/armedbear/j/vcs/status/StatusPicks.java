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
import org.armedbear.j.vcs.selection.FileKey;
import org.armedbear.j.vcs.selection.Selections;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.selection.Target.Files;
import org.armedbear.j.vcs.selection.Target.Part;
import org.armedbear.j.vcs.signs.StatusView;
import org.armedbear.j.vcs.status.StatusData.FileItem;
import org.armedbear.j.vcs.status.StatusLine.Kind;

/**
 * What is picked in a status, for the commands that take targets to take
 * with no selection, in place of the caret: files whole, hunks, and lines of
 * hunks, each in its section. By typed file keys and captured changes, so
 * a pick survives a refresh and edits
 * elsewhere. In memory, never saved. On the event thread.
 */
final class StatusPicks {
    /** A consumed pick; null hunk is the whole file. No presentation keys are parsed. */
    record Key(FileKey file, String hunk) {}

    // A file's picks: whole, or its hunks' by id in the order picked.
    private static final class FilePicks {
        final FileKey file;
        FileItem source;
        boolean whole;
        final Map<String, HunkPick> hunks = new LinkedHashMap<>();

        FilePicks(FileKey file, FileItem source) {
            this.file = file;
            this.source = source;
        }
    }

    // Mutable picks keep the immutable file/hunk they were captured from.
    // The shared resolver owns their identity and context remapping.
    private record HunkPick(BitSet lines, FileItem file, Hunk source) {
        HunkPick withLines(BitSet next) {
            return new HunkPick(next, file, source);
        }

        Part target(FileKey key) {
            return Part.of(key.repository(), key.section(), file, source, lines);
        }
    }

    // By repository, opaque section and literal path.
    private final Map<FileKey, FilePicks> files = new LinkedHashMap<>();
    // Worked out from what is picked, and the status; null until asked for
    // again after either changes: the keys of the files picked in part,
    // the picks as targets and the keys of those each target takes.
    private Set<FileKey> parts;
    private List<Target> targets;
    private Map<Target, Set<Key>> targetKeys;
    // Each diff's hunks' ids, and each section's files by path, by the
    // section's key, as the status has them now.
    private final Map<FileDiff, Selections.Hunks> ids = new IdentityHashMap<>();
    private final Map<FileDiff, Map<Hunk, String>> reverse = new IdentityHashMap<>();
    private List<Selections.Problem> droppedProblems = List.of();
    private final Map<FileKey.Section, Map<String, FileItem>> items = new HashMap<>();

    boolean isEmpty() {
        return files.isEmpty();
    }

    void clear() {
        dirty();
        files.clear();
    }

    /** The keys of the picks each of targets, as targets made them, takes. */
    Map<Target, Set<Key>> used(List<Target> targets) {
        final Map<Target, Set<Key>> out = new IdentityHashMap<>();
        for (Target t : targets)
            out.put(t, targetKeys == null ? Set.of() : targetKeys.getOrDefault(t, Set.of()));
        return out;
    }

    /** Unpicks those of keys, files' and hunks', still picked. */
    void remove(Set<Key> keys) {
        dirty();
        for (Key key : keys) {
            final FilePicks picked = files.get(key.file());
            if (picked == null)
                continue;
            if (key.hunk() == null) {
                if (picked.whole)
                    files.remove(key.file());
            } else if (!picked.whole) {
                picked.hunks.remove(key.hunk());
                prune(key.file(), picked);
            }
        }
    }

    /** The status is read again: its files and their hunks' ids are looked up again. */
    void newRead() {
        ids.clear();
        reverse.clear();
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

    /** diff's hunks by their ids, as {@link Selections#hunkIds}, read once a render. */
    Map<String, Hunk> ids(FileDiff diff) {
        return idsOf(diff).hunks();
    }

    Selections.Hunks idsOf(FileDiff diff) {
        return ids.computeIfAbsent(diff, Selections::index);
    }

    String hunkId(FileItem file, Hunk hunk) {
        if (file.diff() == null)
            return null;
        return reverse.computeIfAbsent(file.diff(), diff -> {
            final Map<Hunk, String> out = new IdentityHashMap<>();
            ids(diff).forEach((id, value) -> out.put(value, id));
            return out;
        }).get(hunk);
    }

    private HunkPick picked(FileItem file, String id, BitSet lines) {
        return new HunkPick(lines, file, ids(file.diff()).get(id));
    }

    /**
     * Whether l is a thing that can be picked: a file, a hunk, a changed
     * line, not of a combined diff's nor a stash's.
     */
    static boolean pickable(StatusLine l) {
        if (l.repository() == null || l.section() == null || l.file() == null || StatusData.STASHES.equals(l.section()))
            return false;
        return switch (l.kind()) {
            case FILE -> true;
            case HUNK -> l.hunk() != null && !l.hunk().isCombined();
            case DIFF -> l.hunk() != null && !l.hunk().isCombined() && !l.diffLine().isContext() && !l.isForeign();
            default -> false;
        };
    }

    /**
     * The domain key of l's file. A split file has the same key under each list.
     */
    static FileKey fileKey(StatusLine l) {
        return fileKey(l, l.file());
    }

    /** The key of the line of file in l's section. */
    static FileKey fileKey(StatusLine l, FileItem file) {
        return new FileKey(l.repository(), l.section(), file.path());
    }

    /** How much of what l shows is picked. */
    PickState state(StatusLine l) {
        if (!pickable(l))
            return PickState.NONE;
        // Under a changelist, of a file or hunk split across lists, its lines there.
        if (l.only() != null)
            return linesState(fileKey(l), l.only());
        return switch (l.kind()) {
            case FILE -> fileState(l);
            case HUNK -> hunkState(fileKey(l), hunkId(l.file(), l.hunk()));
            case DIFF ->
                    linePicked(fileKey(l), hunkId(l.file(), l.hunk()), l.diffIndex()) ? PickState.ALL : PickState.NONE;
            default -> PickState.NONE;
        };
    }

    // A file's line's: picked whole, or each of its hunks, as its diff is
    // read, is; else picked in part, or not.
    private PickState fileState(StatusLine line) {
        return fileState(fileKey(line), line.file());
    }

    private PickState fileState(FileKey key, FileItem file) {
        final FilePicks fp = files.get(key);
        if (fp == null)
            return PickState.NONE;
        if (fp.whole)
            return PickState.ALL;
        final FileDiff d = file.diff();
        if (d == null || d.hunks().isEmpty() || fp.hunks.size() != d.hunks().size())
            return PickState.SOME;
        for (String id : ids(d).keySet()) {
            final HunkPick hp = fp.hunks.get(id);
            if (hp == null || hp.lines() != null)
                return PickState.SOME;
        }
        return PickState.ALL;
    }

    PickState state(FileKey key, Target.Member member) {
        final Map<String, BitSet> only = member.only();
        return only == null ? fileState(key, member.file()) : linesState(key, only);
    }

    /**
     * Picks each of ls that can be, or, if each is picked already, unpicks
     * them; a file's line among them takes its hunks and lines.
     */
    void toggle(List<Target> targets) {
        set(targets, targets.stream().anyMatch(target -> state(target) != PickState.ALL));
    }

    void set(List<Target> targets, boolean on) {
        for (Target target : targets) {
            if (target instanceof Files whole) {
                for (FileItem file : whole.files()) {
                    final FileKey key = new FileKey(whole.repository(), whole.section(), file.path());
                    dirty();
                    if (!on)
                        files.remove(key);
                    else {
                        final FilePicks picked = of(key, file);
                        picked.source = file;
                        picked.whole = true;
                        picked.hunks.clear();
                    }
                }
            } else {
                final Part part = (Part) target;
                final FileKey key = new FileKey(part.repository(), part.section(), part.file().path());
                final String id = hunkId(part.file(), part.hunk());
                if (id == null)
                    continue;
                if (part.selection() == null)
                    setHunk(key, id, part.file(), on);
                else
                    setLines(key, id, part.file(), part.hunk(), part.lines(), on);
            }
        }
    }

    PickState state(Target target) {
        boolean any = false, all = true;
        if (target instanceof Files whole) {
            for (FileItem file : whole.files()) {
                final PickState picked = fileState(new FileKey(whole.repository(), whole.section(), file.path()), file);
                any |= picked != PickState.NONE;
                all &= picked == PickState.ALL;
            }
        } else {
            final Part part = (Part) target;
            final FileKey key = new FileKey(part.repository(), part.section(), part.file().path());
            final String id = hunkId(part.file(), part.hunk());
            return id == null ? PickState.NONE
                    : part.selection() == null ? hunkState(key, id)
                    : linesState(key, Map.of(id, part.lines()));
        }
        return all ? PickState.ALL : any ? PickState.SOME : PickState.NONE;
    }

    /** Whether anything of fk's file is picked. */
    boolean has(FileKey fk) {
        return files.containsKey(fk);
    }

    /** How much of the hunk id of fk's file is picked. */
    PickState hunkState(FileKey fk, String id) {
        final FilePicks fp = files.get(fk);
        if (fp == null)
            return PickState.NONE;
        if (fp.whole)
            return PickState.ALL;
        final HunkPick hp = fp.hunks.get(id);
        return hp == null ? PickState.NONE : hp.lines() == null ? PickState.ALL : PickState.SOME;
    }

    /** Whether line i of the hunk id of fk's file is picked. */
    boolean linePicked(FileKey fk, String id, int i) {
        final FilePicks fp = files.get(fk);
        if (fp == null)
            return false;
        if (fp.whole)
            return true;
        final HunkPick hp = fp.hunks.get(id);
        return hp != null && (hp.lines() == null || hp.lines().get(i));
    }

    /** How much of the lines of fk's file that idx gives by hunk id is picked. */
    PickState linesState(FileKey fk, Map<String, BitSet> idx) {
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

    /**
     * Picks hunk id of the file keyed fk if on,
     * else unpicks it; unpicked in a file picked whole, the file's other
     * hunks stay picked.
     */
    private void setHunk(FileKey fk, String id, FileItem f, boolean on) {
        dirty();
        final FilePicks fp = files.get(fk);
        if (on) {
            if (fp == null || !fp.whole)
                of(fk, f).hunks.put(id, picked(f, id, null));
        } else if (fp != null) {
            if (fp.whole)
                split(fp, f, id, null);
            else
                fp.hunks.remove(id);
            prune(fk, fp);
        }
    }

    // The lines idx of hunk h, id, of fk's file picked or unpicked, a
    // modified line's removal with its addition; all its changed lines
    // picked, the hunk is.
    private void setLines(FileKey fk, String id, FileItem f, Hunk h, BitSet some, boolean on) {
        dirty();
        final BitSet idx = (BitSet) some.clone();
        Patch.pairChanges(h, idx);
        FilePicks fp = files.get(fk);
        if (on) {
            if (fp != null && (fp.whole || hunkState(fk, id) == PickState.ALL))
                return;
            fp = of(fk, f);
            final BitSet changed = changed(h);
            final HunkPick hp = fp.hunks.computeIfAbsent(id, k -> picked(f, id, new BitSet()));
            hp.lines().or(idx);
            hp.lines().and(changed);
            if (hp.lines().equals(changed))
                fp.hunks.put(id, hp.withLines(null));
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
                fp.hunks.put(id, hp.withLines(left));
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
                fp.hunks.put(other, picked(f, other, null));
            else if (some != null && !some.isEmpty())
                fp.hunks.put(other, picked(f, other, some));
    }

    // fk's picks, made if there are none.
    private FilePicks of(FileKey fk, FileItem f) {
        return files.computeIfAbsent(fk, k -> new FilePicks(fk, f));
    }

    // fk's picks gone if nothing of the file is left picked.
    private void prune(FileKey fk, FilePicks fp) {
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
     * The retained picks as targets: files whole, by
     * section, then hunks and lines of them, a modified line's removal with
     * its addition. Retention owns matching against status. Worked out
     * once until what is picked, or the status, changes.
     */
    List<Target> targets() {
        if (targets != null)
            return targets;
        final Map<Target, Set<Key>> keys = new IdentityHashMap<>();
        // Files picked whole, by section's key, with their keys.
        final Map<FileKey.Section, List<FileItem>> wholes = new LinkedHashMap<>();
        final Map<FileKey.Section, FileKey> sections = new HashMap<>();
        final Map<FileKey.Section, Set<Key>> wholeKeys = new HashMap<>();
        final List<Target> parts = new ArrayList<>();
        for (Map.Entry<FileKey, FilePicks> e : files.entrySet()) {
            final FilePicks fp = e.getValue();
            final FileItem f = fp.source;
            if (fp.whole) {
                final FileKey.Section sk = fp.file.scope();
                wholes.computeIfAbsent(sk, k -> new ArrayList<>()).add(f);
                sections.putIfAbsent(sk, fp.file);
                wholeKeys.computeIfAbsent(sk, k -> new HashSet<>()).add(new Key(e.getKey(), null));
                continue;
            }
            for (Map.Entry<String, HunkPick> h : fp.hunks.entrySet()) {
                final Part part = h.getValue().target(fp.file);
                parts.add(part);
                keys.put(part, Set.of(new Key(e.getKey(), h.getKey())));
            }
        }
        final List<Target> out = new ArrayList<>();
        for (Map.Entry<FileKey.Section, List<FileItem>> e : wholes.entrySet()) {
            final FileKey s = sections.get(e.getKey());
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
    StatusView.Picked rows(FileKey fk, FileItem f) {
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
    Map<Key, FileKey> wholeFiles() {
        final Map<Key, FileKey> out = new LinkedHashMap<>();
        for (Map.Entry<FileKey, FilePicks> e : files.entrySet())
            if (e.getValue().whole)
                out.put(new Key(e.getKey(), null), e.getValue().file);
        return out;
    }

    /** The paths picked, whole or in part, with their repositories, in order, each once. */
    List<FileKey> paths() {
        final Map<VcsRepository, Set<String>> seen = new IdentityHashMap<>();
        final List<FileKey> out = new ArrayList<>();
        for (FilePicks fp : files.values())
            if (seen.computeIfAbsent(fp.file.repository(), r -> new HashSet<>()).add(fp.file.path()))
                out.add(fp.file);
        return List.copyOf(out);
    }

    /**
     * Drops the picks whose file the status no longer has in their
     * section, or whose captured change is no longer valid. Unread targets
     * remain pending. A whole file or each hunk counts as one dropped pick.
     */
    List<Target> captured() {
        final List<Target> captured = new ArrayList<>();
        for (FilePicks picked : files.values()) {
            if (picked.whole)
                captured.add(new Files(picked.file.repository(), picked.file.section(), List.of(picked.source)));
            else
                picked.hunks.values().forEach(hunk -> captured.add(hunk.target(picked.file)));
        }
        return List.copyOf(captured);
    }

    int retain(RepositoryStatus status) {
        return status.isWriting() ? 0 : retain(status.resolveTargets(captured()));
    }

    int retain(Selections.Resolution resolution) {
        dirty();
        final int before = files.values().stream().mapToInt(picked -> picked.whole ? 1 : picked.hunks.size()).sum();
        droppedProblems = resolution.problems();
        final Map<FileKey, FilePicks> retained = new LinkedHashMap<>();
        for (Target target : resolution.targets()) {
            if (target instanceof Files whole) {
                for (FileItem file : whole.files()) {
                    final FileKey key = new FileKey(whole.repository(), whole.section(), file.path());
                    final FilePicks picked = new FilePicks(key, file);
                    picked.whole = true;
                    retained.put(key, picked);
                }
            } else {
                final Part part = (Part) target;
                final FileKey key = new FileKey(part.repository(), part.section(), part.file().path());
                retained.computeIfAbsent(key, k -> new FilePicks(k, part.file())).hunks
                        .put(hunkId(part.file(), part.hunk()), new HunkPick(part.lines(), part.file(), part.hunk()));
            }
        }
        final Map<FileKey, FilePicks> ordered = new LinkedHashMap<>();
        int after = 0;
        for (Map.Entry<FileKey, FilePicks> original : files.entrySet()) {
            final FilePicks picked = resolution.unread().contains(original.getKey())
                    ? original.getValue()
                    : retained.get(original.getKey());
            if (picked != null) {
                ordered.put(original.getKey(), picked);
                after += picked.whole ? 1 : picked.hunks.size();
            }
        }
        files.clear();
        files.putAll(ordered);
        return before - after;
    }

    List<Selections.Problem> droppedProblems() {
        return droppedProblems;
    }

    /** Of a file's line partly picked, its hunks picked in whole or part, and all its hunks; null otherwise. */
    int[] counts(StatusLine file) {
        if (file.kind() != Kind.FILE
                || file.file() == null
                || file.file().diff() == null
                || state(file) != PickState.SOME)
            return null;
        return new int[] { files.get(fileKey(file)).hunks.size(), ids(file.file().diff()).size() };
    }

    /** Whether a hunk or lines are picked of a file whose diff the status has not read. */
    boolean unread(RepositoryStatus status) {
        for (FilePicks fp : files.values())
            if (!fp.whole) {
                final FileItem f = item(status, fp.file);
                if (f != null && f.diff() == null)
                    return true;
            }
        return false;
    }

    /** The keys of the files some of whose hunks or lines are picked, whose diffs a refresh must read. */
    Set<FileKey> partlyPicked() {
        if (parts == null) {
            final Set<FileKey> out = new HashSet<>();
            for (Map.Entry<FileKey, FilePicks> e : files.entrySet())
                if (!e.getValue().whole)
                    out.add(e.getKey());
            parts = Set.copyOf(out);
        }
        return parts;
    }

    /** The file p is of, as the status has it now, or null; looked up by path once a read. */
    FileItem item(RepositoryStatus status, FileKey p) {
        return items.computeIfAbsent(p.scope(), k -> {
            final Map<String, FileItem> byPath = new HashMap<>();
            for (FileItem f : status.files(p.repository(), p.section()))
                byPath.put(f.path(), f);
            return byPath;
        }).get(p.path());
    }
}
