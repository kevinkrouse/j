/*
 * PatchTest.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

/** The text of patches; {@code PatchRoundTripTest} has git apply them. */
public class PatchTest {
    private static final String CHANGE = """
                                         diff --git a/a b/a
                                         index 1111111..2222222 100644
                                         --- a/a
                                         +++ b/a
                                         @@ -10,4 +10,4 @@ section
                                          ctx
                                         -old1
                                         -old2
                                         +new1
                                         +new2
                                          ctx2
                                         """;

    private static String patch(String diff, boolean reverse, int... lines) {
        FileDiff d = DiffParser.parse(diff).get(0);
        Hunk h = d.hunks().get(0);
        BitSet sel = new BitSet();
        for (int k : lines)
            sel.set(k);
        byte[] p = Patch.forLines(d, h, sel, reverse);
        return p == null ? null : new String(p, StandardCharsets.ISO_8859_1);
    }

    @Test
    public void forwardKeepsUnselectedRemovalsAsContextAndDropsAdditions() {
        assertEquals("""
                     diff --git a/a b/a
                     --- a/a
                     +++ b/a
                     @@ -10,4 +10,4 @@ section
                      ctx
                     -old1
                      old2
                     +new1
                      ctx2
                     """, patch(CHANGE, false, 1, 3));
    }

    @Test
    public void reverseKeepsUnselectedAdditionsAsContextAndDropsRemovals() {
        // Applied with -R to the new side, so the new start is the one that counts.
        assertEquals("""
                     diff --git a/a b/a
                     --- a/a
                     +++ b/a
                     @@ -10,4 +10,4 @@ section
                      ctx
                     -old1
                     +new1
                      new2
                      ctx2
                     """, patch(CHANGE, true, 1, 3));
    }

    @Test
    public void countsWhatIsLeft() {
        assertEquals("""
                     diff --git a/a b/a
                     --- a/a
                     +++ b/a
                     @@ -10,4 +10,3 @@ section
                      ctx
                     -old1
                      old2
                      ctx2
                     """, patch(CHANGE, false, 1));
    }

    @Test
    public void onlyContextIsNoPatch() {
        assertNull(patch(CHANGE, false, 0, 5));
        // Additions left out of a forward patch, removals out of a reverse one.
        assertNull(patch("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1,2 @@\n a\n+b\n", false));
        assertNull(patch("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1,2 +1 @@\n a\n-b\n", true));
    }

    @Test
    public void startsAnEmptySideAtTheLineBefore() {
        String add = "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -3,0 +4,2 @@\n+x\n+y\n";
        assertEquals("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -3,0 +4 @@\n+x\n", patch(add, false, 0));
        String del = "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -4,2 +3,0 @@\n-x\n-y\n";
        assertEquals("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -4,2 +4 @@\n-x\n y\n", patch(del, false, 0));
    }

    @Test
    public void givesALineItsNewlineWhenLinesComeAfterIt() {
        String diff = "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1,2 +1,3 @@\n a\n-b\n\\ No newline at end of file\n"
                + "+b\n+c\n";
        // Only "c": "b" gains its newline too, as a '-' and '+' of its own.
        assertEquals(
            "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1,2 +1,3 @@\n a\n-b\n"
                    + "\\ No newline at end of file\n+b\n+c\n",
            patch(diff, false, 3));
    }

    @Test
    public void makesPartOfADeletionAChange() {
        String diff = "diff --git a/d b/d\ndeleted file mode 100644\nindex 1111111..0000000\n--- a/d\n+++ /dev/null\n"
                + "@@ -1,2 +0,0 @@\n-x\n-y\n";
        assertEquals("diff --git a/d b/d\n--- a/d\n+++ b/d\n@@ -1,2 +1 @@\n-x\n y\n", patch(diff, false, 0));
        // All of it is still a deletion.
        assertEquals(
            "diff --git a/d b/d\ndeleted file mode 100644\n--- a/d\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-x\n-y\n",
            patch(diff, false, 0, 1));
    }

    @Test
    public void makesPartOfANewFileUnstagedAChange() {
        String diff = "diff --git a/n b/n\nnew file mode 100644\n--- /dev/null\n+++ b/n\n@@ -0,0 +1,2 @@\n+x\n+y\n";
        assertEquals("diff --git a/n b/n\n--- a/n\n+++ b/n\n@@ -1 +1,2 @@\n+x\n y\n", patch(diff, true, 0));
        // Staged, part of a new file is still a new file.
        assertEquals(
            "diff --git a/n b/n\nnew file mode 100644\n--- /dev/null\n+++ b/n\n@@ -0,0 +1 @@\n+x\n",
            patch(diff, false, 0));
    }

    @Test
    public void reverseGivesARemovedLineItsNewlineWhenLinesComeAfterIt() {
        String diff = "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1,2 +1,2 @@\n x\n-y\n\\ No newline at end of file\n"
                + "+z\n";
        assertEquals("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1,3 +1,2 @@\n x\n-y\n z\n", patch(diff, true, 1));
    }

    @Test
    public void makesPartOfARenameAChangeToTheNewPath() {
        String diff =
                "diff --git a/old b/new\nsimilarity index 90%\nrename from old\nrename to new\nindex 1..2 100644\n"
                        + "--- a/old\n+++ b/new\n@@ -1,2 +1,2 @@\n-x\n+X\n y\n@@ -20 +20 @@\n-p\n+q\n";
        assertEquals(
            "diff --git a/new b/new\n--- a/new\n+++ b/new\n@@ -1,2 +1,2 @@\n-x\n+X\n y\n",
            patch(diff, true, 0, 1));
    }

    @Test
    public void ignoresARangeOutsideTheHunk() {
        FileDiff d = DiffParser.parse(CHANGE).get(0);
        assertNull(Patch.forLines(d, d.hunks().get(0), 50, 60, false));
    }

    @Test
    public void quotesPathsGitWouldQuote() {
        assertEquals("plain name", Patch.quote("plain name"));
        assertEquals("\"a\\tb\"", Patch.quote("a\tb"));
        assertEquals("\"q\\\"\\\\\"", Patch.quote("q\"\\"));
    }
}
