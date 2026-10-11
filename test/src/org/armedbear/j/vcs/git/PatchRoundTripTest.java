/*
 * PatchRoundTripTest.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import org.armedbear.j.vcs.diff.DiffLine;
import org.armedbear.j.vcs.diff.FileDiff;
import org.armedbear.j.vcs.diff.Hunk;
import org.armedbear.j.vcs.diff.Patch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Patches of hunks and lines, applied by git. Staging every change bit by bit
 * must leave the index as the working tree; unstaging, as HEAD; discarding,
 * the working tree as the index.
 */
public class PatchRoundTripTest {
    @TempDir
    Path tmp;

    @AfterEach
    public void resetGit() {
        GitFixture.reset();
    }

    private GitFixture repo(String path, String committed) throws IOException {
        GitFixture f = GitFixture.init(tmp);
        if (committed != null)
            f.write(path, committed);
        f.commitAll("base");
        return f;
    }

    private static FileDiff only(List<FileDiff> diffs) {
        assertEquals(1, diffs.size(), diffs::toString);
        return diffs.get(0);
    }

    // The index of the n-th changed line ('+' or '-') of the hunk.
    private static int changed(Hunk h, int n) {
        for (int k = 0; k < h.lines().size(); k++) {
            if (!h.lines().get(k).isContext() && n-- == 0)
                return k;
        }
        throw new AssertionError("no changed line " + n);
    }

    private static void applied(String error) {
        assertNull(error, error);
    }

    @Test
    public void stagesAndUnstagesOneHunk() throws IOException {
        StringBuilder base = new StringBuilder();
        for (int i = 1; i <= 30; i++)
            base.append("line ").append(i).append('\n');
        GitFixture f = repo("a.txt", base.toString());
        String work = base.toString().replace("line 2\n", "line two\n").replace("line 28\n", "line 28\nnew\n");
        f.write("a.txt", work);
        FileDiff d = only(f.unstaged());
        assertEquals(2, d.hunks().size());

        applied(f.apply(Patch.forHunk(d, d.hunks().get(1), false), true, false));
        assertEquals(base.toString().replace("line 28\n", "line 28\nnew\n"), f.index("a.txt"));

        FileDiff s = only(f.staged());
        applied(f.apply(Patch.forHunk(s, s.hunks().get(0), true), true, true));
        assertEquals(base.toString(), f.index("a.txt"));
    }

    @Test
    public void stagesOneLineOfAChange() throws IOException {
        GitFixture f = repo("a.txt", "a\nb\nc\n");
        f.write("a.txt", "a\nB\nc\nd\n");
        FileDiff d = only(f.unstaged());
        Hunk h = d.hunks().get(0);
        // Only the added "d".
        int k = h.lines().size() - 1;
        assertEquals("d", h.lines().get(k).text());
        applied(f.apply(Patch.forLines(d, h, k, k + 1, false), true, false));
        assertEquals("a\nb\nc\nd\n", f.index("a.txt"));
        // Only the removal of "b": the index loses it, the "B" stays unstaged.
        d = only(f.unstaged());
        h = d.hunks().get(0);
        int minus = changed(h, 0);
        assertEquals("b", h.lines().get(minus).text());
        applied(f.apply(Patch.forLines(d, h, minus, minus + 1, false), true, false));
        assertEquals("a\nc\nd\n", f.index("a.txt"));
    }

    @Test
    public void stagesALineAddedAfterAnEndWithoutNewline() throws IOException {
        GitFixture f = repo("a.txt", "a\nb");
        f.write("a.txt", "a\nb\nc\n");
        FileDiff d = only(f.unstaged());
        Hunk h = d.hunks().get(0);
        int c = h.lines().size() - 1;
        assertEquals("c", h.lines().get(c).text());
        // "c" alone needs "b" to gain its newline.
        applied(f.apply(Patch.forLines(d, h, c, c + 1, false), true, false));
        assertEquals("a\nb\nc\n", f.index("a.txt"));
    }

    @Test
    public void stagesTheNewlineAloneAtTheEnd() throws IOException {
        GitFixture f = repo("a.txt", "a\nb");
        f.write("a.txt", "a\nb\n");
        FileDiff d = only(f.unstaged());
        applied(f.apply(Patch.forHunk(d, d.hunks().get(0), false), true, false));
        assertEquals("a\nb\n", f.index("a.txt"));
        FileDiff s = only(f.staged());
        applied(f.apply(Patch.forHunk(s, s.hunks().get(0), true), true, true));
        assertEquals("a\nb", f.index("a.txt"));
    }

    @Test
    public void stagesPartOfANewFile() throws IOException {
        GitFixture f = repo("keep.txt", "k\n");
        f.write("n.txt", "one\ntwo\nthree\n");
        f.git("add", "-N", "n.txt");
        FileDiff d = only(f.unstaged());
        assertTrue(d.isNew());
        Hunk h = d.hunks().get(0);
        BitSet sel = new BitSet();
        sel.set(0);
        sel.set(2);
        applied(f.apply(Patch.forLines(d, h, sel, false), true, false));
        assertEquals("one\nthree\n", f.index("n.txt"));
        // Unstaging part of it keeps the file staged with the rest.
        FileDiff s = only(f.staged());
        assertTrue(s.isNew());
        applied(f.apply(Patch.forLines(s, s.hunks().get(0), 0, 1, true), true, true));
        assertEquals("three\n", f.index("n.txt"));
        // Unstaging all of it takes it out of the index.
        s = only(f.staged());
        applied(f.apply(Patch.forHunk(s, s.hunks().get(0), true), true, true));
        assertNull(f.index("n.txt"));
    }

    @Test
    public void stagesPartOfADeletion() throws IOException {
        GitFixture f = repo("d.txt", "one\ntwo\nthree\n");
        Files.delete(f.root.resolve("d.txt"));
        FileDiff d = only(f.unstaged());
        assertTrue(d.isDeleted());
        applied(f.apply(Patch.forLines(d, d.hunks().get(0), 1, 2, false), true, false));
        assertEquals("one\nthree\n", f.index("d.txt"));
        d = only(f.unstaged());
        applied(f.apply(Patch.forHunk(d, d.hunks().get(0), false), true, false));
        assertNull(f.index("d.txt"));
        // Unstaging part of the deletion brings that part back.
        FileDiff s = only(f.staged());
        assertTrue(s.isDeleted());
        applied(f.apply(Patch.forLines(s, s.hunks().get(0), 0, 1, true), true, true));
        assertEquals("one\n", f.index("d.txt"));
    }

    @Test
    public void unstagesAHunkOfAStagedRenameAndKeepsTheRename() throws IOException {
        StringBuilder base = new StringBuilder();
        for (int i = 1; i <= 30; i++)
            base.append("line ").append(i).append('\n');
        GitFixture f = repo("old.txt", base.toString());
        f.git("mv", "old.txt", "new.txt");
        String work = base.toString().replace("line 2\n", "line two\n").replace("line 29\n", "line 29!\n");
        f.write("new.txt", work);
        f.git("add", "-A");
        FileDiff s = only(f.staged());
        assertTrue(s.isRename(), s::toString);
        assertEquals(2, s.hunks().size());
        applied(f.apply(Patch.forHunk(s, s.hunks().get(0), true), true, true));
        // Still renamed, with only the second change staged.
        assertNull(f.index("old.txt"));
        assertEquals(base.toString().replace("line 29\n", "line 29!\n"), f.index("new.txt"));
        // Then all of it: the rename is undone with its last change.
        s = only(f.staged());
        assertTrue(s.isRename(), s::toString);
        applied(f.apply(Patch.forHunk(s, s.hunks().get(0), true), true, true));
        assertEquals(base.toString(), f.index("old.txt"));
        assertNull(f.index("new.txt"));
    }

    @Test
    public void stagesAHunkOfAModeChangeWithoutTheModeChange() throws IOException {
        StringBuilder base = new StringBuilder();
        for (int i = 1; i <= 30; i++)
            base.append("line ").append(i).append('\n');
        GitFixture f = repo("run.sh", base.toString());
        f.git("update-index", "--chmod=+x", "run.sh");
        f.git("commit", "-q", "-m", "x");
        f.write("run.sh", base.toString().replace("line 2\n", "two\n").replace("line 29\n", "29\n"));
        f.root.resolve("run.sh").toFile().setExecutable(false);
        FileDiff d = only(f.unstaged());
        assertEquals(2, d.hunks().size());
        applied(f.apply(Patch.forHunk(d, d.hunks().get(0), false), true, false));
        assertTrue(f.git("ls-files", "-s", "run.sh").startsWith("100755"), "the mode is not part of one hunk");
    }

    @Test
    public void discardsARemovalOfALineWithoutANewline() throws IOException {
        GitFixture f = repo("a.txt", "x\ny");
        f.write("a.txt", "x\nz\n");
        FileDiff d = only(f.unstaged());
        Hunk h = d.hunks().get(0);
        int y = changed(h, 0);
        assertTrue(h.lines().get(y).noNewline());
        // Bring back "y", keep "z": no line runs into the next.
        applied(f.apply(Patch.forLines(d, h, y, y + 1, true), false, true));
        assertEquals("x\ny\nz\n", f.read("a.txt"));
    }

    @Test
    public void unstagesARemovalOfALineWithoutANewline() throws IOException {
        GitFixture f = repo("a.txt", "x\ny");
        f.write("a.txt", "x\nz\n");
        f.git("add", "a.txt");
        FileDiff s = only(f.staged());
        Hunk h = s.hunks().get(0);
        int y = changed(h, 0);
        applied(f.apply(Patch.forLines(s, h, y, y + 1, true), true, true));
        assertEquals("x\ny\nz\n", f.index("a.txt"));
    }

    @Test
    public void stagingThenUnstagingAPartLeavesTheDiffAsItWas() throws IOException {
        GitFixture f = repo("a.txt", "1\n2\n3\n4\n5\n6\n");
        f.write("a.txt", "1\nTWO\n3\n4\nfive\n6\nseven\n");
        String before = f.git("diff", "--no-ext-diff");
        FileDiff d = only(f.unstaged());
        Hunk h = d.hunks().get(0);
        BitSet sel = new BitSet();
        sel.set(changed(h, 1));
        sel.set(changed(h, 2));
        applied(f.apply(Patch.forLines(d, h, sel, false), true, false));
        // The staged part, all of it, unstaged.
        FileDiff s = only(f.staged());
        applied(f.apply(Patch.forHunk(s, s.hunks().get(0), true), true, true));
        assertEquals(before, f.git("diff", "--no-ext-diff"));
        assertTrue(f.staged().isEmpty());
    }

    @Test
    public void keepsCarriageReturns() throws IOException {
        GitFixture f = repo("w.txt", "a\r\nb\r\nc\r\n");
        f.write("w.txt", "a\r\nB\r\nc\r\nd\r\n");
        FileDiff d = only(f.unstaged());
        Hunk h = d.hunks().get(0);
        int last = h.lines().size() - 1;
        applied(f.apply(Patch.forLines(d, h, last, last + 1, false), true, false));
        assertEquals("a\r\nb\r\nc\r\nd\r\n", f.index("w.txt"));
    }

    @Test
    public void keepsBytesThatAreNotUtf8() throws IOException {
        GitFixture f = repo("l.txt", "café\n");
        f.write("l.txt", "café\nnaïve\n");
        FileDiff d = only(f.unstaged());
        applied(f.apply(Patch.forHunk(d, d.hunks().get(0), false), true, false));
        assertEquals("café\nnaïve\n", f.index("l.txt"));
    }

    @Test
    public void handlesAPathWithSpacesAndUnicode() throws IOException {
        String name = "dir with space/été.txt";
        GitFixture f = repo(name, "x\n");
        f.write(name, "x\ny\n");
        FileDiff d = only(f.unstaged());
        assertEquals(name, d.path());
        applied(f.apply(Patch.forHunk(d, d.hunks().get(0), false), true, false));
        assertEquals(0, f.unstaged().size());
    }

    @Test
    public void reportsAPatchGitRejects() throws IOException {
        GitFixture f = repo("a.txt", "a\nb\nc\n");
        f.write("a.txt", "a\nB\nc\n");
        FileDiff d = only(f.unstaged());
        byte[] patch = Patch.forHunk(d, d.hunks().get(0), false);
        applied(f.apply(patch, true, false));
        // The same patch again no longer matches the index.
        assertNotNull(f.apply(patch, true, false));
    }

    @Test
    public void selectingOnlyContextMakesNoPatch() throws IOException {
        GitFixture f = repo("a.txt", "a\nb\nc\nd\n");
        f.write("a.txt", "a\nb\nC\nd\n");
        FileDiff d = only(f.unstaged());
        Hunk h = d.hunks().get(0);
        assertNull(Patch.forLines(d, h, 0, 1, false));
    }

    // Random files and edits, staged, unstaged and discarded in random parts.

    private static final String[] WORDS = { "a", "b", "c", "x", "", "  indented", "}" };

    private static List<String> randomLines(Random r, int max) {
        List<String> lines = new ArrayList<>();
        int n = r.nextInt(max + 1);
        for (int i = 0; i < n; i++)
            lines.add(WORDS[r.nextInt(WORDS.length)] + (r.nextInt(3) == 0 ? "" : Integer.toString(r.nextInt(5))));
        return lines;
    }

    private static String text(List<String> lines, boolean finalNewline) {
        String s = String.join("\n", lines);
        return lines.isEmpty() ? "" : finalNewline ? s + "\n" : s;
    }

    private static List<String> edit(Random r, List<String> lines) {
        List<String> out = new ArrayList<>(lines);
        int edits = 1 + r.nextInt(6);
        for (int e = 0; e < edits; e++) {
            int at = out.isEmpty() ? 0 : r.nextInt(out.size() + 1);
            switch (r.nextInt(3)) {
                case 0 -> out.addAll(at, randomLines(r, 3));
                case 1 -> {
                    int n = Math.min(out.size() - at, 1 + r.nextInt(3));
                    for (int k = 0; k < n; k++)
                        out.remove(at);
                }
                default -> {
                    if (at < out.size())
                        out.set(at, out.get(at) + "!");
                }
            }
        }
        return out;
    }

    private static BitSet randomSelection(Random r, Hunk h) {
        BitSet sel = new BitSet();
        for (int k = 0; k < h.lines().size(); k++) {
            if (r.nextBoolean())
                sel.set(k);
        }
        return sel;
    }

    private enum Step {
        STAGE, UNSTAGE, DISCARD
    }

    // Patches of part of a hunk that applied, so the run is known to test parts.
    private int partsApplied;

    /*
     * What applying the selection should leave, worked out from the hunk alone:
     * the target's lines with the hunk's region replaced by the lines the
     * selection keeps. A line ends without a newline only if it was marked so
     * and is the file's last.
     */
    static String expected(String target, Hunk h, BitSet sel, boolean reverse) {
        boolean finalNewline = target.endsWith("\n");
        List<String> lines = new ArrayList<>();
        if (!target.isEmpty()) {
            String body = finalNewline ? target.substring(0, target.length() - 1) : target;
            lines.addAll(List.of(body.split("\n", -1)));
        }
        int start = reverse ? h.newStart() : h.oldStart();
        int count = reverse ? h.newCount() : h.oldCount();
        int from = count == 0 ? start : start - 1;
        List<DiffLine> kept = new ArrayList<>();
        for (int k = 0; k < h.lines().size(); k++) {
            DiffLine line = h.lines().get(k);
            boolean selected = sel.get(k);
            boolean keep = line.isContext() || (line.isRemoved() ? selected == reverse : selected != reverse);
            if (keep)
                kept.add(line);
        }
        List<String> out = new ArrayList<>(lines.subList(0, from));
        for (DiffLine line : kept)
            out.add(line.text());
        List<String> suffix = lines.subList(from + count, lines.size());
        out.addAll(suffix);
        boolean newline;
        if (!suffix.isEmpty())
            newline = finalNewline;
        else if (!kept.isEmpty())
            newline = !kept.get(kept.size() - 1).noNewline();
        else
            newline = true;
        if (out.isEmpty())
            return "";
        return String.join("\n", out) + (newline ? "\n" : "");
    }

    private static String content(GitFixture f, String path, Step step) throws IOException {
        if (step != Step.DISCARD) {
            String s = f.index(path);
            return s == null ? "" : s;
        }
        return Files.exists(f.root.resolve(path)) ? f.read(path) : "";
    }

    private static boolean changedPart(Hunk h, BitSet sel) {
        int changed = 0;
        int chosen = 0;
        for (int k = 0; k < h.lines().size(); k++) {
            if (!h.lines().get(k).isContext()) {
                changed++;
                if (sel.get(k))
                    chosen++;
            }
        }
        return chosen > 0 && chosen < changed;
    }

    // Applies random parts until nothing is left, checking each against expected().
    private void drain(GitFixture f, Random r, Step step, String what) throws IOException {
        for (int round = 0; round < 200; round++) {
            List<FileDiff> diffs = step == Step.UNSTAGE ? f.staged() : f.unstaged();
            if (diffs.isEmpty())
                return;
            FileDiff d = diffs.get(0);
            if (d.hunks().isEmpty())
                fail(what + ": a change with no hunks: " + d);
            Hunk h = d.hunks().get(r.nextInt(d.hunks().size()));
            // Every fifth round takes the whole hunk, so it ends.
            final BitSet sel;
            if (round % 5 == 4) {
                sel = new BitSet();
                sel.set(0, h.lines().size());
            } else {
                sel = randomSelection(r, h);
            }
            boolean reverse = step != Step.STAGE;
            byte[] patch = Patch.forLines(d, h, sel, reverse);
            if (patch == null)
                continue;
            String path = d.path();
            String want = expected(content(f, path, step), h, sel, reverse);
            String error = f.apply(patch, step != Step.DISCARD, reverse);
            if (error != null)
                fail(what + " " + step + ": " + error + "\n--- hunk lines: " + h.lines());
            String got = content(f, path, step);
            assertEquals(
                want,
                got,
                () -> what + " " + step + ": wrong result of\n" + new String(patch, StandardCharsets.ISO_8859_1)
                        + "--- selected " + sel);
            if (changedPart(h, sel))
                partsApplied++;
        }
        fail(what + " " + step + ": did not finish");
    }

    @Test
    public void randomPartsRoundTrip() throws IOException {
        GitFixture f = GitFixture.init(tmp);
        // -Dvcs.patchSeeds=500 for a longer run.
        final int seeds = Integer.getInteger("vcs.patchSeeds", 20);
        for (int seed = 0; seed < seeds; seed++) {
            Random r = new Random(seed);
            String path = "f" + seed + ".txt";
            List<String> base = randomLines(r, 12);
            boolean isNew = r.nextInt(6) == 0;
            if (!isNew) {
                f.write(path, text(base, r.nextInt(4) != 0));
                f.commitAll("base " + seed);
            }
            String committed = isNew ? null : f.read(path);
            // A new or deleted file with no lines has no hunk; `git add` takes it whole.
            boolean delete = !isNew && !committed.isEmpty() && r.nextInt(8) == 0;
            String work = delete ? null : text(edit(r, base), r.nextInt(4) != 0);
            if (work != null && (work.equals(committed) || isNew && work.isEmpty()))
                work = work + "tail\n";
            if (delete)
                Files.delete(f.root.resolve(path));
            else
                f.write(path, work);
            if (isNew)
                f.git("add", "-N", path);
            String what = "seed " + seed + " (" + (isNew ? "new" : delete ? "deleted" : "edited") + ")";

            drain(f, r, Step.STAGE, what);
            assertEquals(work, f.index(path), what + ": index after staging everything");

            drain(f, r, Step.UNSTAGE, what);
            assertEquals(committed, f.index(path), what + ": index after unstaging everything");

            // Discarding a new file is deleting it, which is not a patch.
            if (!isNew) {
                drain(f, r, Step.DISCARD, what);
                assertEquals(committed, f.read(path), what + ": working tree after discarding everything");
            }
            f.git("reset", "-q", "--hard");
            f.git("clean", "-q", "-fd");
            assertTrue(f.unstaged().isEmpty() && f.staged().isEmpty(), what);
        }
        assertTrue(partsApplied >= seeds * 2, "only " + partsApplied + " partial patches applied");
    }

    @Test
    public void everyLineOfTheHunkIsAccountedFor() throws IOException {
        // A selection's patch, then the rest, is the hunk.
        GitFixture f = repo("a.txt", "1\n2\n3\n4\n5\n");
        f.write("a.txt", "1\nTWO\n3\nfour\n5\nsix\n");
        FileDiff d = only(f.unstaged());
        Hunk h = d.hunks().get(0);
        List<DiffLine> lines = h.lines();
        BitSet sel = new BitSet();
        for (int k = 0; k < lines.size(); k += 2)
            sel.set(k);
        applied(f.apply(Patch.forLines(d, h, sel, false), true, false));
        d = only(f.unstaged());
        applied(f.apply(Patch.forHunk(d, d.hunks().get(0), false), true, false));
        assertEquals(f.read("a.txt"), f.index("a.txt"));
    }
}
