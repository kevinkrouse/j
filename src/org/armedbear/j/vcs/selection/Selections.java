/*
 * Selections.java
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

package org.armedbear.j.vcs.selection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.diff.Patch;
import org.armedbear.j.vcs.status.StatusData;
import org.armedbear.j.vcs.status.StatusData.FileItem;

/** Selection identity and line coordinates, without I/O or presentation state. */
public final class Selections {
    private Selections() {}

    // Removals identify a base row. Additions identify the gap before the
    // next base row. Context width and working-tree row shifts do not matter.
    private static List<Integer> baseRows(Hunk hunk, BitSet selected) {
        final List<Integer> rows = new ArrayList<>();
        int row = hunk.oldRow();
        for (int i = 0; i < hunk.lines().size(); i++) {
            final DiffLine line = hunk.lines().get(i);
            if (!line.isContext() && (selected == null || selected.get(i)))
                rows.add(row);
            if (line.isOld())
                row++;
        }
        return rows;
    }

    /** Collects one selection without cloning a growing line set for each selected row. */
    public static final class Builder {
        private final Map<FileKey.Section, Map<String, FileItem>> files = new LinkedHashMap<>();
        private final Map<FileKey, Map<Hunk, Lines>> parts = new HashMap<>();
        private final Map<FileDiff, Map<String, Hunk>> ids = new IdentityHashMap<>();
        private final List<Target> order = new ArrayList<>();
        private final Function<FileDiff, Map<String, Hunk>> lookup;

        public Builder() {
            this(diff -> index(diff).hunks());
        }

        public Builder(Function<FileDiff, Map<String, Hunk>> lookup) {
            this.lookup = Objects.requireNonNull(lookup);
        }

        private static final class Lines {
            final BitSet selected = new BitSet();
            boolean whole;

        }

        public Builder file(VcsRepository repository, String section, FileItem file) {
            final FileKey.Section key = new FileKey.Section(repository, section);
            final Map<String, FileItem> group = files.computeIfAbsent(key, k -> {
                order.add(new Target.Files(repository, section, List.of()));
                return new LinkedHashMap<>();
            });
            group.putIfAbsent(file.path(), file);
            return this;
        }

        private Lines part(VcsRepository repository, String section, FileItem file, Hunk hunk) {
            final FileKey key = new FileKey(repository, section, file.path());
            return parts.computeIfAbsent(key, k -> new IdentityHashMap<>()).computeIfAbsent(hunk, h -> {
                final Target.Part target = new Target.Part(repository, section, file, hunk, null);
                order.add(target);
                return new Lines();
            });
        }

        /** null lines takes the whole hunk. */
        public Builder lines(VcsRepository repository, String section, FileItem file, Hunk hunk, BitSet lines) {
            final Lines part = part(repository, section, file, hunk);
            if (lines == null)
                part.whole = true;
            else if (!part.whole)
                part.selected.or(lines);
            return this;
        }

        public Builder line(VcsRepository repository, String section, FileItem file, Hunk hunk, int index) {
            if (index < 0 || index >= hunk.lines().size() || hunk.lines().get(index).isContext())
                return this;
            final Lines part = part(repository, section, file, hunk);
            if (!part.whole)
                part.selected.set(index);
            return this;
        }

        /** A whole file, or its split changelist membership by hunk id. */
        public Builder member(VcsRepository repository, String section, Target.Member member) {
            final Map<String, BitSet> only = member.only();
            if (only == null)
                return file(repository, section, member.file());
            final FileDiff diff = member.file().diff();
            if (diff != null) {
                final Map<String, Hunk> hunks = ids.computeIfAbsent(diff, lookup);
                for (Map.Entry<String, BitSet> selected : only.entrySet()) {
                    final Hunk hunk = hunks.get(selected.getKey());
                    if (hunk != null)
                        lines(repository, section, member.file(), hunk, selected.getValue());
                }
            }
            return this;
        }

        public List<Target> build() {
            final List<Target> result = new ArrayList<>();
            for (Target target : order) {
                final FileKey.Section scope = new FileKey.Section(target.repository(), target.section());
                final Map<String, FileItem> whole = files.getOrDefault(scope, Map.of());
                if (target instanceof Target.Files) {
                    result.add(new Target.Files(target.repository(), target.section(), List.copyOf(whole.values())));
                    continue;
                }
                final Target.Part part = (Target.Part) target;
                if (whole.containsKey(part.file().path()))
                    continue;
                final Lines selected =
                        parts.get(new FileKey(part.repository(), part.section(), part.file().path())).get(part.hunk());
                if (selected.whole)
                    result.add(part);
                else {
                    final BitSet bits = (BitSet) selected.selected.clone();
                    Patch.pairChanges(part.hunk(), bits);
                    result.add(new Target.Part(part.repository(), part.section(), part.file(), part.hunk(), bits));
                }
            }
            return List.copyOf(result);
        }
    }

    public record Problem(FileKey file, String message) {}

    /**
     * Valid targets retain input order and may be kept by picks. A command
     * requires ready() before taking any; then there is one result per input
     * target, including one group for each whole-file group.
     */
    public record Resolution(List<Target> targets, Set<FileKey> unread, List<Problem> problems) {
        public Resolution {
            targets = List.copyOf(targets);
            unread = Collections.unmodifiableSet(new LinkedHashSet<>(unread));
            problems = List.copyOf(problems);
        }

        public boolean ready() {
            return unread.isEmpty() && problems.isEmpty();
        }

        public String failure() {
            if (!problems.isEmpty()) {
                final Problem first = problems.get(0);
                return first.file().path() + ": " + first.message()
                        + (problems.size() > 1 ? " (and " + (problems.size() - 1) + " more)" : "");
            }
            return unread.isEmpty() ? null : "Reading the selected files' diffs";
        }

        /** A caller's bounded read did not supply all the requested diffs. */
        public Resolution readsFailed() {
            final List<Problem> failed = new ArrayList<>(problems);
            for (FileKey file : unread)
                failed.add(new Problem(file, "Could not read the selected diff"));
            return new Resolution(targets, Set.of(), failed);
        }
    }

    /**
     * Resolves captured targets against existing status, without reads or GUI state.
     * read tells whether a null diff was requested in this status (rather than an
     * earlier read). Backends report failed reads in FileItem.diffError, and a
     * successful read without textual changes as an empty FileDiff.
     */
    public static Resolution resolve(
            List<Target> captured,
            Map<VcsRepository, StatusData> status,
            Predicate<FileKey> read) {
        return resolve(captured, status, read, Selections::index, key -> false);
    }

    /**
     * As resolve, sharing the caller's immutable-diff index cache, and
     * requiring whole-file diffs where requireDiff says, as moves to local
     * changelists do for their anchors.
     */
    public static Resolution resolve(
            List<Target> captured,
            Map<VcsRepository, StatusData> status,
            Predicate<FileKey> read,
            Function<FileDiff, Hunks> index,
            Predicate<FileKey> requireDiff) {
        final List<Target> resolved = new ArrayList<>();
        final Set<FileKey> unread = new LinkedHashSet<>();
        final List<Problem> problems = new ArrayList<>();
        final Map<FileKey, FileItem> files = new HashMap<>();
        status.forEach((repository, data) -> {
            if (data != null)
                for (StatusData.Section section : data.sections())
                    for (StatusData.Item item : section.items())
                        if (item instanceof FileItem file)
                            files.put(new FileKey(repository, section.id(), file.path()), file);
        });
        final Map<FileDiff, Hunks> indexes = new IdentityHashMap<>();
        final Map<FileDiff, Map<Hunk, String>> originalIds = new IdentityHashMap<>();
        final Map<FileDiff, Set<Hunk>> unchanged = new IdentityHashMap<>();
        for (Target target : captured) {
            final List<FileItem> selected = target.files();
            if (selected.isEmpty()) {
                problems.add(
                    new Problem(new FileKey(target.repository(), target.section(), ""), "No files were selected"));
                continue;
            }
            final List<FileItem> currentFiles = new ArrayList<>();
            for (FileItem selectedFile : selected) {
                final FileKey key = new FileKey(target.repository(), target.section(), selectedFile.path());
                final StatusData data = status.get(target.repository());
                final FileItem current = files.get(key);
                if (data == null || data.error() != null) {
                    problems.add(new Problem(key, data == null ? "The status is unavailable" : data.error()));
                    continue;
                }
                if (current == null) {
                    problems.add(new Problem(key, "The file is no longer in this section"));
                    continue;
                }
                if (!sameChange(selectedFile, current)) {
                    problems.add(new Problem(key, "The selected file change has changed"));
                    continue;
                }
                if (target instanceof Target.Part || requireDiff.test(key) && current.hasDiff()) {
                    if (current.diffError() != null) {
                        problems.add(new Problem(key, current.diffError()));
                        continue;
                    }
                    if (current.diff() == null) {
                        if (read.test(key))
                            problems.add(new Problem(key, "The requested diff was not returned"));
                        else if (!current.hasDiff())
                            problems.add(new Problem(key, "This change cannot be selected in part"));
                        else
                            unread.add(key);
                        continue;
                    }
                }
                if (target instanceof Target.Files) {
                    currentFiles.add(current);
                    continue;
                }
                final Target.Part part = (Target.Part) target;
                if (current.diff().isBinary() || current.diff().isCombined()) {
                    problems.add(new Problem(key, "This change cannot be selected in part"));
                    continue;
                }
                if (part.file().diff() == null) {
                    problems.add(new Problem(key, "The selected file change has changed"));
                    continue;
                }
                // An immutable diff still used by the current read needs no
                // content hashing. Check hunk membership by identity once per
                // diff, keeping many targets from one file linear as well.
                if (current.diff() == part.file().diff()) {
                    final Set<Hunk> hunks = unchanged.computeIfAbsent(current.diff(), diff -> {
                        final Set<Hunk> present = Collections.newSetFromMap(new IdentityHashMap<>());
                        present.addAll(diff.hunks());
                        return present;
                    });
                    if (hunks.contains(part.hunk())) {
                        resolved.add(
                            new Target.Part(target
                                    .repository(), target.section(), current, part.hunk(), part.selection()));
                        continue;
                    }
                }
                final Hunks before = indexes.computeIfAbsent(part.file().diff(), index);
                final Map<Hunk, String> sourceIds = originalIds.computeIfAbsent(part.file().diff(), diff -> {
                    final Map<Hunk, String> reverse = new IdentityHashMap<>();
                    before.hunks().forEach((id, hunk) -> reverse.put(hunk, id));
                    return reverse;
                });
                final String id = sourceIds.get(part.hunk());
                if (id == null) {
                    problems.add(new Problem(key, "The selected hunk is unavailable"));
                    continue;
                }
                final String fingerprint = id.substring(0, id.lastIndexOf('#'));
                final Hunks now = indexes.computeIfAbsent(current.diff(), index);
                final Hunk hunk = match(now, part.hunk(), fingerprint);
                if (hunk == null) {
                    problems.add(
                        new Problem(key,
                                now.count(fingerprint) > 0
                                        ? "The selected hunk moved: select it again"
                                        : "The selected hunk has changed: select it again"));
                    continue;
                }
                resolved.add(
                    new Target.Part(target.repository(),
                            target.section(),
                            current,
                            hunk,
                            part.selection() == null
                                    ? null
                                    : hunkLines(hunk, changedLines(part.hunk(), part.selection()))));
            }
            if (target instanceof Target.Files && !currentFiles.isEmpty())
                resolved.add(new Target.Files(target.repository(), target.section(), currentFiles));
        }
        return new Resolution(resolved, unread, problems);
    }

    private static boolean sameChange(FileItem before, FileItem now) {
        return before.letter() == now.letter()
                && before.staged() == now.staged()
                && Objects.equals(before.change().origPath(), now.change().origPath());
    }

    /**
     * A hunk's identity: a SHA-256 of its changed lines in order, with their
     * newline and combined-diff marks. Context and rows are left out.
     */
    public static String fingerprint(Hunk hunk) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (DiffLine line : hunk.lines()) {
            if (line.isContext())
                continue;
            digest.update((byte) line.kind());
            // Diff text is the original bytes represented as ISO-8859-1.
            text(digest, line.text());
            digest.update((byte) (line.noNewline() ? 1 : 0));
            text(digest, line.marks());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // Length-prefix each field, distinguishing null and empty without
    // treating embedded delimiters as part of the encoding's structure.
    private static void text(MessageDigest digest, String text) {
        final byte[] bytes = text == null ? null : text.getBytes(StandardCharsets.ISO_8859_1);
        final int length = bytes == null ? -1 : bytes.length;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        if (bytes != null)
            digest.update(bytes);
    }

    /** One diff's hunks by id: a fingerprint, # and its occurrence from 1. */
    public record Hunks(Map<String, Hunk> hunks) {
        public Hunks {
            hunks = Collections.unmodifiableMap(new LinkedHashMap<>(hunks));
        }

        /** How many hunks have this fingerprint. */
        public int count(String fingerprint) {
            int n = 0;
            while (hunks.containsKey(fingerprint + "#" + (n + 1)))
                n++;
            return n;
        }
    }

    public static Hunks index(FileDiff diff) {
        final Map<String, Hunk> hunks = new LinkedHashMap<>();
        final Map<String, Integer> counts = new HashMap<>();
        for (Hunk hunk : diff.hunks()) {
            final String digest = fingerprint(hunk);
            hunks.put(digest + "#" + counts.merge(digest, 1, Integer::sum), hunk);
        }
        return new Hunks(hunks);
    }

    /**
     * The same change now: a hunk of the same content whose changed lines
     * keep their base rows (an edit elsewhere in the working file), their
     * working rows (a write elsewhere in the base) or the lines around them
     * (both, as a line added above and committed). Null if none or several
     * do, so a change undone and made again elsewhere isn't followed.
     */
    public static Hunk match(Hunks current, Hunk before, String fingerprint) {
        final List<Integer> base = baseRows(before, null);
        final List<Integer> working = workingRows(before);
        Hunk found = null;
        final int count = current.count(fingerprint);
        for (int i = 1; i <= count; i++) {
            final Hunk candidate = current.hunks().get(fingerprint + "#" + i);
            final boolean sameBase = base.equals(baseRows(candidate, null));
            final boolean sameWorking = working.equals(workingRows(candidate));
            if (sameBase && sameWorking)
                return candidate;
            if (sameBase || sameWorking || sameSurroundings(before, candidate)) {
                if (found != null)
                    return null;
                found = candidate;
            }
        }
        return found;
    }

    // The context lines next to the changes, as far as both hunks have
    // them, all equal, and at least one of them.
    private static boolean sameSurroundings(Hunk a, Hunk b) {
        final List<DiffLine> x = a.lines(), y = b.lines();
        final int leadA = leading(x), leadB = leading(y);
        final int trailA = trailing(x), trailB = trailing(y);
        final int lead = Math.min(leadA, leadB), trail = Math.min(trailA, trailB);
        if (lead + trail == 0)
            return false;
        for (int i = 1; i <= lead; i++)
            if (!x.get(leadA - i).text().equals(y.get(leadB - i).text()))
                return false;
        for (int i = 1; i <= trail; i++)
            if (!x.get(x.size() - trailA - 1 + i).text().equals(y.get(y.size() - trailB - 1 + i).text()))
                return false;
        return true;
    }

    private static int leading(List<DiffLine> lines) {
        int n = 0;
        while (n < lines.size() && lines.get(n).isContext())
            n++;
        return n;
    }

    private static int trailing(List<DiffLine> lines) {
        int n = 0;
        while (n < lines.size() && lines.get(lines.size() - 1 - n).isContext())
            n++;
        return n;
    }

    // As baseRows, on the working side.
    private static List<Integer> workingRows(Hunk hunk) {
        final List<Integer> rows = new ArrayList<>();
        int row = hunk.newRow();
        for (DiffLine line : hunk.lines()) {
            if (!line.isContext())
                rows.add(row);
            if (line.isNew())
                row++;
        }
        return rows;
    }

    /** Hunk indexes expressed as offsets among changed lines, excluding context. */
    public static BitSet changedLines(Hunk hunk, BitSet indexes) {
        final BitSet offsets = new BitSet();
        int changed = 0;
        for (int i = 0; i < hunk.lines().size(); i++)
            if (!hunk.lines().get(i).isContext()) {
                if (indexes.get(i))
                    offsets.set(changed);
                changed++;
            }
        return offsets;
    }

    /** Offsets among changed lines expressed in a current hunk's indexes. */
    public static BitSet hunkLines(Hunk hunk, BitSet offsets) {
        final BitSet indexes = new BitSet();
        int changed = 0;
        for (int i = 0; i < hunk.lines().size(); i++)
            if (!hunk.lines().get(i).isContext()) {
                if (offsets.get(changed))
                    indexes.set(i);
                changed++;
            }
        return indexes;
    }
}
