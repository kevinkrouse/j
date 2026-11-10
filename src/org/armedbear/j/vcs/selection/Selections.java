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
import java.util.function.Predicate;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.status.StatusData;
import org.armedbear.j.vcs.status.StatusData.FileItem;

/** Selection identity and line coordinates, without I/O or presentation state. */
public final class Selections {
    private Selections() {}

    public enum Reason {
        GONE, CHANGED, AMBIGUOUS, UNSUPPORTED, READ_FAILED
    }

    public record Problem(FileKey file, Reason reason, String message) {}

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
                failed.add(new Problem(file, Reason.READ_FAILED, "Could not read the selected diff"));
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
            final List<FileItem> selected =
                    target instanceof Target.Files whole ? whole.files() : List.of(((Target.Part) target).file());
            if (selected.isEmpty()) {
                problems.add(
                    new Problem(new FileKey(target.repository(), target.section(), ""),
                            Reason.UNSUPPORTED,
                            "No files were selected"));
                continue;
            }
            final List<FileItem> currentFiles = new ArrayList<>();
            for (FileItem selectedFile : selected) {
                final FileKey key = new FileKey(target.repository(), target.section(), selectedFile.path());
                final StatusData data = status.get(target.repository());
                final FileItem current = files.get(key);
                if (data == null || data.error() != null) {
                    problems.add(
                        new Problem(key,
                                Reason.READ_FAILED,
                                data == null ? "The status is unavailable" : data.error()));
                    continue;
                }
                if (current == null) {
                    problems.add(new Problem(key, Reason.GONE, "The file is no longer in this section"));
                    continue;
                }
                if (target instanceof Target.Files) {
                    currentFiles.add(current);
                    continue;
                }
                final Target.Part part = (Target.Part) target;
                if (current.diffError() != null) {
                    problems.add(new Problem(key, Reason.READ_FAILED, current.diffError()));
                    continue;
                }
                if (current.diff() == null) {
                    if (read.test(key))
                        problems.add(new Problem(key, Reason.READ_FAILED, "The requested diff was not returned"));
                    else if (!current.hasDiff())
                        problems.add(new Problem(key, Reason.UNSUPPORTED, "This change cannot be selected in part"));
                    else
                        unread.add(key);
                    continue;
                }
                if (current.diff().isBinary() || current.diff().isCombined()) {
                    problems.add(new Problem(key, Reason.UNSUPPORTED, "This change cannot be selected in part"));
                    continue;
                }
                if (part.file().diff() == null
                        || part.file().letter() != current.letter()
                        || part.file().staged() != current.staged()
                        || !Objects.equals(part.file().change().origPath(), current.change().origPath())) {
                    problems.add(new Problem(key, Reason.CHANGED, "The selected file change has changed"));
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
                final Hunks before = indexes.computeIfAbsent(part.file().diff(), Selections::index);
                final Map<Hunk, String> sourceIds = originalIds.computeIfAbsent(part.file().diff(), diff -> {
                    final Map<Hunk, String> reverse = new IdentityHashMap<>();
                    before.hunks().forEach((id, hunk) -> reverse.put(hunk, id));
                    return reverse;
                });
                final String id = sourceIds.get(part.hunk());
                final Content content = id == null ? null : before.contents().get(id);
                if (content == null) {
                    problems.add(new Problem(key, Reason.CHANGED, "The selected hunk is unavailable"));
                    continue;
                }
                final Hunks now = indexes.computeIfAbsent(current.diff(), Selections::index);
                final int twins = before.twins().get(id);
                final int count = now.twins().getOrDefault(content.fingerprint() + "#1", 0);
                if (count > 0 && twins != count) {
                    problems.add(new Problem(key, Reason.AMBIGUOUS, "The number of identical hunks has changed"));
                    continue;
                }
                final Hunk hunk = match(now, id, content, twins);
                if (hunk == null) {
                    problems.add(new Problem(key, Reason.CHANGED, "The selected hunk has changed: select it again"));
                    continue;
                }
                resolved.add(
                    Target.Part.of(
                        target.repository(),
                        target.section(),
                        current,
                        hunk,
                        part.selection() == null ? null : hunkLines(hunk, changedLines(part.hunk(), part.lines()))));
            }
            if (target instanceof Target.Files && !currentFiles.isEmpty())
                resolved.add(new Target.Files(target.repository(), target.section(), currentFiles));
        }
        return new Resolution(resolved, unread, problems);
    }

    /**
     * The ordered changed bytes and their newline/combined-diff metadata.
     * Context and row numbers are deliberately absent. The digest locates
     * candidates; canonical equality confirms them. Hashing this value never
     * walks a large diff again.
     */
    public static final class Content {
        private final List<DiffLine> lines;
        private final String fingerprint;

        private Content(List<DiffLine> lines, String fingerprint) {
            this.lines = List.copyOf(lines);
            this.fingerprint = fingerprint;
        }

        public String fingerprint() {
            return fingerprint;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof Content content
                            && fingerprint.equals(content.fingerprint)
                            && lines.equals(content.lines);
        }

        @Override
        public int hashCode() {
            return fingerprint.hashCode();
        }
    }

    public static Content content(Hunk hunk) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        final List<DiffLine> lines = new ArrayList<>();
        for (DiffLine line : hunk.lines()) {
            if (line.isContext())
                continue;
            lines.add(line);
            digest.update((byte) line.kind());
            // Diff text is the original bytes represented as ISO-8859-1.
            text(digest, line.text());
            digest.update((byte) (line.noNewline() ? 1 : 0));
            text(digest, line.marks());
        }
        return new Content(lines, HexFormat.of().formatHex(digest.digest()));
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

    /** One diff's occurrence ids, canonical contents, and duplicate counts. */
    public record Hunks(Map<String, Hunk> hunks, Map<String, Content> contents, Map<String, Integer> twins) {
        public Hunks {
            hunks = Collections.unmodifiableMap(new LinkedHashMap<>(hunks));
            contents = Map.copyOf(contents);
            twins = Map.copyOf(twins);
        }
    }

    public static Hunks index(FileDiff diff) {
        final Map<String, Hunk> hunks = new LinkedHashMap<>();
        final Map<String, Content> contents = new LinkedHashMap<>();
        final Map<String, Integer> counts = new HashMap<>();
        for (Hunk hunk : diff.hunks()) {
            final Content content = content(hunk);
            final String digest = content.fingerprint();
            final String id = digest + "#" + counts.merge(digest, 1, Integer::sum);
            hunks.put(id, hunk);
            contents.put(id, content);
        }
        final Map<String, Integer> twins = new HashMap<>();
        contents.forEach((id, content) -> twins.put(id, counts.get(content.fingerprint())));
        return new Hunks(hunks, contents, twins);
    }

    public static Map<String, Hunk> hunkIds(FileDiff diff) {
        return index(diff).hunks();
    }

    /** The same occurrence, or null if gone, changed or ambiguous after a duplicate count changes. */
    public static Hunk match(Hunks current, String id, Content expected, int twins) {
        final Hunk candidate = current.hunks().get(id);
        return candidate != null
                && expected != null
                && current.twins().get(id) == twins
                && expected.equals(current.contents().get(id)) ? candidate : null;
    }

    /** Hunk indexes expressed as offsets among changed lines, excluding context. */
    public static LineSet changedLines(Hunk hunk, BitSet indexes) {
        final BitSet offsets = new BitSet();
        int changed = 0;
        for (int i = 0; i < hunk.lines().size(); i++)
            if (!hunk.lines().get(i).isContext()) {
                if (indexes.get(i))
                    offsets.set(changed);
                changed++;
            }
        return new LineSet(offsets);
    }

    /** Offsets among changed lines expressed in a current hunk's indexes. */
    public static BitSet hunkLines(Hunk hunk, LineSet selected) {
        final BitSet offsets = selected.toBitSet();
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
