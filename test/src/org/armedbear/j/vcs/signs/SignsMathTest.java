/*
 * SignsMathTest.java
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.Line;
import org.armedbear.j.Signs;
import org.armedbear.j.TextLine;
import org.armedbear.j.mode.compare.Side;
import org.armedbear.j.util.Diff;
import org.junit.jupiter.api.Test;

public class SignsMathTest {
    private static List<String> split(String s) {
        return Side.of(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).lines();
    }

    // Each line's sign as one letter: . none, A added, C changed, D deleted
    // below (^ above), lower case when staged.
    private static String signs(String buffer, String index, String head) {
        final List<String> texts = split(buffer);
        final List<Line> lines = new ArrayList<>();
        for (String t : texts)
            lines.add(new TextLine(t));
        final Signs s = SignsMath.compute(lines, texts, split(index), head == null ? null : split(head));
        final StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            final Signs.Sign sign = s.get(l);
            char c = sign == null ? '.'
                    : sign.kind() == Signs.Kind.ADDED ? 'A'
                    : sign.kind() == Signs.Kind.CHANGED ? 'C'
                    : sign.above() ? '^'
                    : 'D';
            if (sign != null && sign.staged())
                c = Character.toLowerCase(c);
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    public void linesSplitAsTheBufferHasThem() {
        assertEquals(List.of("a", "b"), split("a\nb\n"));
        assertEquals(List.of("a", "b"), split("a\r\nb"));
        assertEquals(List.of("a", ""), split("a\n\n"));
        assertEquals(List.of(), split(""));
    }

    @Test
    public void unstagedChangesAgainstTheIndex() {
        final String base = "1\n2\n3\n4\n";
        assertEquals("....", signs(base, base, base));
        assertEquals(".C..", signs("1\nX\n3\n4\n", base, base));
        assertEquals(".A...", signs("1\nnew\n2\n3\n4\n", base, base));
        assertEquals("D..", signs("1\n3\n4\n", base, base), "deleted below line 1");
        assertEquals("^..", signs("2\n3\n4\n", base, base), "deleted above the first");
    }

    @Test
    public void aBlankLineChangedAndALineDeletedBelowMarkOnlyThose() {
        final String base = "test\nhello\n\n\nand\n\n\n\n\n## header1\nchanged text\n\nsome\n";
        final String edited = "test\nhello\n\n\nand\n\nchanged\n\n\n## header1\n\nsome\n";
        assertEquals("......C..D..", signs(edited, base, base));
    }

    @Test
    public void stagedChangesAreOnTheBuffersLinesAndDimmed() {
        final String head = "1\n2\n3\n4\n";
        final String index = "1\nTWO\n3\n4\nfive\n";
        assertEquals(".c..a", signs(index, index, head));
        // The buffer changed a line more, and a staged one again: unstaged wins.
        assertEquals(".C.Ca", signs("1\nTwo\n3\nFOUR\nfive\n", index, head));
        // A line added above shifts the staged ones down.
        assertEquals("A.c..a", signs("0\n" + index, index, head));
    }

    @Test
    public void aFileNewToTheIndexIsAllStagedAdded() {
        assertEquals("aa", signs("x\ny\n", "x\ny\n", null));
        assertEquals("aA", signs("x\nz\n", "x\n", null));
    }

    @Test
    public void indexLinesMapThroughTheBuffersEdits() {
        final List<Diff.Edit> edits = Diff.diff(split("a\nb\nc\n"), split("a\nX\nY\nc\n"));
        assertArrayEquals(new int[] { 0, -1, 3 }, SignsMath.indexToBuffer(3, edits));
    }
}
