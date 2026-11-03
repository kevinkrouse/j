/*
 * DiffParserTest.java
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class DiffParserTest {
    private static FileDiff one(String diff) {
        List<FileDiff> files = DiffParser.parse(diff);
        assertEquals(1, files.size(), files::toString);
        return files.get(0);
    }

    @Test
    public void readsAModifiedFile() {
        FileDiff d = one("""
                         diff --git a/src/A.java b/src/A.java
                         index 1234567..89abcde 100644
                         --- a/src/A.java
                         +++ b/src/A.java
                         @@ -1,3 +1,3 @@ class A {
                          one
                         -two
                         +TWO
                          three
                         """);
        assertEquals("src/A.java", d.oldPath());
        assertEquals("src/A.java", d.newPath());
        assertFalse(d.isNew() || d.isDeleted() || d.isRename() || d.isBinary());
        Hunk h = d.hunks().get(0);
        assertEquals(1, h.oldStart());
        assertEquals(3, h.oldCount());
        assertEquals(" class A {", h.section());
        assertEquals(4, h.lines().size());
        assertEquals('-', h.lines().get(1).kind());
        assertEquals("TWO", h.lines().get(2).text());
        assertEquals("@@ -1,3 +1,3 @@ class A {", h.header());
    }

    @Test
    public void readsSeveralFilesAndHunks() {
        List<FileDiff> files = DiffParser.parse("""
                                                diff --git a/a b/a
                                                --- a/a
                                                +++ b/a
                                                @@ -1 +1 @@
                                                -x
                                                +y
                                                @@ -10,0 +11,2 @@
                                                +p
                                                +q
                                                diff --git a/b b/b
                                                --- a/b
                                                +++ b/b
                                                @@ -2,2 +2 @@
                                                 k
                                                -gone
                                                """);
        assertEquals(2, files.size());
        assertEquals(2, files.get(0).hunks().size());
        Hunk second = files.get(0).hunks().get(1);
        assertEquals(10, second.oldStart());
        assertEquals(0, second.oldCount());
        assertEquals(2, second.newCount());
        assertEquals(1, files.get(1).hunks().get(0).newCount());
    }

    @Test
    public void readsNewDeletedAndRenamedFiles() {
        List<FileDiff> files = DiffParser.parse("""
                                                diff --git a/n b/n
                                                new file mode 100644
                                                index 0000000..e69de29
                                                --- /dev/null
                                                +++ b/n
                                                @@ -0,0 +1 @@
                                                +hello
                                                diff --git a/gone b/gone
                                                deleted file mode 100644
                                                --- a/gone
                                                +++ /dev/null
                                                @@ -1 +0,0 @@
                                                -bye
                                                diff --git a/old name b/new name
                                                similarity index 100%
                                                rename from old name
                                                rename to new name
                                                """);
        assertTrue(files.get(0).isNew());
        assertNull(files.get(0).oldPath());
        assertEquals("n", files.get(0).path());
        assertTrue(files.get(1).isDeleted());
        assertNull(files.get(1).newPath());
        assertEquals("gone", files.get(1).path());
        FileDiff rename = files.get(2);
        assertTrue(rename.isRename());
        assertEquals("old name", rename.oldPath());
        assertEquals("new name", rename.newPath());
        assertTrue(rename.hunks().isEmpty());
    }

    @Test
    public void readsAModeChangeAndABinaryFile() {
        List<FileDiff> files = DiffParser.parse("""
                                                diff --git a/run.sh b/run.sh
                                                old mode 100644
                                                new mode 100755
                                                diff --git a/pic.png b/pic.png
                                                index 1111111..2222222 100644
                                                Binary files a/pic.png and b/pic.png differ
                                                """);
        assertEquals("run.sh", files.get(0).path());
        assertTrue(files.get(0).hunks().isEmpty());
        assertTrue(files.get(1).isBinary());
        assertEquals("pic.png", files.get(1).path());
    }

    @Test
    public void marksALineWithoutANewline() {
        FileDiff d = one("""
                         diff --git a/a b/a
                         --- a/a
                         +++ b/a
                         @@ -1,2 +1,2 @@
                          a
                         -b
                         \\ No newline at end of file
                         +b
                         """);
        List<DiffLine> lines = d.hunks().get(0).lines();
        assertTrue(lines.get(1).noNewline());
        assertFalse(lines.get(2).noNewline());
    }

    @Test
    public void marksTheLastLineWithoutANewline() {
        FileDiff d = one("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-a\n+b\n\\ No newline at end of file\n");
        assertTrue(d.hunks().get(0).lines().get(1).noNewline());
    }

    @Test
    public void readsQuotedPaths() {
        FileDiff d = one(
            "diff --git \"a/tab\\there\" \"b/tab\\there\"\n--- \"a/tab\\there\"\n"
                    + "+++ \"b/tab\\there\"\n@@ -1 +1 @@\n-a\n+b\n");
        assertEquals("tab\there", d.path());
        // Octal bytes are UTF-8: "é" is \303\251.
        FileDiff e = one(
            "diff --git \"a/\\303\\251\" \"b/\\303\\251\"\n--- \"a/\\303\\251\"\n"
                    + "+++ \"b/\\303\\251\"\n@@ -1 +1 @@\n-a\n+b\n");
        assertEquals("é", e.path());
    }

    @Test
    public void stripsTheTabGitAddsToAPathWithASpace() {
        FileDiff d = one("diff --git a/x y b/x y\n--- a/x y\t\n+++ b/x y\t\n@@ -1 +1 @@\n-a\n+b\n");
        assertEquals("x y", d.path());
    }

    @Test
    public void readsAnEmptyContextLine() {
        // Some tools strip the space off an empty context line.
        FileDiff d = one("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1,3 +1,3 @@\n a\n\n-c\n+C\n");
        List<DiffLine> lines = d.hunks().get(0).lines();
        assertEquals(4, lines.size());
        assertTrue(lines.get(1).isContext());
        assertEquals("", lines.get(1).text());
    }

    @Test
    public void readsSvnDiffs() {
        List<FileDiff> files = DiffParser.parse("""
                                                Index: trunk/a.c
                                                ===================================================================
                                                --- trunk/a.c\t(revision 12)
                                                +++ trunk/a.c\t(working copy)
                                                @@ -1 +1 @@
                                                -int x;
                                                +int y;
                                                Index: trunk/b.c
                                                ===================================================================
                                                --- trunk/b.c\t(nonexistent)
                                                +++ trunk/b.c\t(working copy)
                                                @@ -0,0 +1 @@
                                                +new
                                                """);
        assertEquals(2, files.size());
        assertEquals("trunk/a.c", files.get(0).path());
        assertEquals("trunk/b.c", files.get(1).path());
        assertEquals(1, files.get(1).hunks().get(0).newCount());
    }

    @Test
    public void readsSvnsAddedAndDeletedFiles() {
        List<FileDiff> files = DiffParser.parse(
            "Index: n.c\n====\n--- n.c\t(nonexistent)\n+++ n.c\t(working copy)\n"
                    + "@@ -0,0 +1 @@\n+x\nIndex: d.c\n====\n--- d.c\t(revision 7)\n+++ d.c\t(nonexistent)\n"
                    + "@@ -1 +0,0 @@\n-y\n");
        assertTrue(files.get(0).isNew());
        assertNull(files.get(0).oldPath());
        assertEquals("n.c", files.get(0).path());
        assertTrue(files.get(1).isDeleted());
        assertNull(files.get(1).newPath());
        assertEquals("d.c", files.get(1).path());
    }

    @Test
    public void readsACombinedDiffsHunksWithTheirTwoColumns() {
        List<FileDiff> files = DiffParser.parse("""
                                                diff --cc both.txt
                                                index 1111111,2222222..0000000
                                                --- a/both.txt
                                                +++ b/both.txt
                                                @@@ -1,1 -1,1 +1,5 @@@
                                                ++<<<<<<< HEAD
                                                 +main
                                                --- not a header
                                                ++=======
                                                + other
                                                ++>>>>>>> other
                                                diff --git a/next.txt b/next.txt
                                                --- a/next.txt
                                                +++ b/next.txt
                                                @@ -1 +1 @@
                                                -a
                                                +b
                                                """);
        assertEquals(2, files.size(), files::toString);
        assertTrue(files.get(0).isCombined());
        assertEquals("both.txt", files.get(0).path());
        assertEquals(1, files.get(0).hunks().size());
        final Hunk h = files.get(0).hunks().get(0);
        assertTrue(h.isCombined());
        assertEquals("@@@ -1,1 -1,1 +1,5 @@@", h.header());
        assertEquals(1, h.newStart());
        assertEquals(5, h.newCount());
        assertEquals(6, h.lines().size(), h.lines()::toString);
        assertEquals("++", h.lines().get(0).prefix());
        assertEquals('+', h.lines().get(0).kind());
        assertEquals("<<<<<<< HEAD", h.lines().get(0).text());
        assertEquals("--", h.lines().get(2).prefix(), "a line starting --- is a body line");
        assertEquals('-', h.lines().get(2).kind());
        assertEquals("+ ", h.lines().get(4).prefix());
        assertEquals("next.txt", files.get(1).path());
        assertEquals(1, files.get(1).hunks().size());
    }

    @Test
    public void readsACombinedBinaryDiffAsBinary() {
        List<FileDiff> files = DiffParser.parse("""
                                                diff --cc img.png
                                                index 1111111,2222222..0000000
                                                Binary files differ
                                                """);
        assertTrue(files.get(0).isCombined());
        assertTrue(files.get(0).isBinary());
    }

    @Test
    public void aCombinedLineKnowsItsParentsAndItsNumberInEach() {
        // Ours "1 Y 3", theirs "1 X 3", the working tree with the markers.
        Hunk h = DiffParser.parse("""
                                  diff --cc a.txt
                                  @@@ -1,3 -1,3 +1,7 @@@
                                    1
                                  ++<<<<<<< ours
                                   +Y
                                  ++=======
                                  + X
                                  ++>>>>>>> theirs
                                    3
                                  \\ No newline at end of file
                                  """).get(0).hunks().get(0);
        assertEquals(1, h.parentStart(1));
        assertEquals(3, h.parentCount(1));
        DiffLine y = h.lines().get(2);
        assertTrue(y.inParent(0) && !y.inParent(1), "Y is ours alone");
        assertTrue(y.isOld(), "ours is the old side");
        DiffLine x = h.lines().get(4);
        assertTrue(!x.inParent(0) && x.inParent(1), "X is theirs alone");
        assertFalse(x.isOld());
        DiffLine marker = h.lines().get(1);
        assertTrue(!marker.inParent(0) && !marker.inParent(1), "a marker is in neither");
        assertEquals(2, h.parentLine(2, 0), "Y is ours' line 2");
        assertEquals(2, h.parentLine(4, 1), "X is theirs' line 2");
        assertEquals(3, h.parentLine(6, 1));
        assertTrue(h.lines().get(6).noNewline(), "the last line's note kept");
        assertEquals("  ", h.lines().get(6).prefix());
    }

    @Test
    public void readsAnUnmergedPathNoteAsTheEndOfAFile() {
        List<FileDiff> files = DiffParser.parse("""
                                                * Unmerged path d.txt
                                                diff --cc a.txt
                                                @@@ -1,1 -1,1 +1,1 @@@
                                                - a
                                                 -b
                                                ++c
                                                * Unmerged path e.txt
                                                """);
        assertEquals(1, files.size(), files::toString);
        assertEquals(3, files.get(0).hunks().get(0).lines().size());
    }

    @Test
    public void readsBareUnifiedDiffs() {
        List<FileDiff> files = DiffParser
                .parse("--- a.txt\n+++ a.txt\n@@ -1 +1 @@\n-1\n+2\n" + "--- b.txt\n+++ b.txt\n@@ -1 +1 @@\n-3\n+4\n");
        assertEquals(2, files.size());
        assertEquals("a.txt", files.get(0).path());
        assertEquals("b.txt", files.get(1).path());
    }

    @Test
    public void keepsBytesAsTheyAre() {
        // UTF-8 "é" as two ISO-8859-1 characters, as the parser sees bytes.
        String bytes = "Ã©";
        FileDiff d = one("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-" + bytes + "\n+x\r\n");
        assertEquals(bytes, d.hunks().get(0).lines().get(0).text());
        assertEquals("x\r", d.hunks().get(0).lines().get(1).text());
    }

    @Test
    public void toleratesNothingAndNoise() {
        assertTrue(DiffParser.parse("").isEmpty());
        assertTrue(DiffParser.parse("warning: something\n").isEmpty());
        // A hunk cut short ends where the lines stop being lines of a hunk.
        FileDiff d = one("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1,5 +1,5 @@\n a\n");
        assertEquals(1, d.hunks().get(0).lines().size());
    }
}
