/*
 * WordDiffTest.java
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

package org.armedbear.j.mode.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class WordDiffTest {
    // The differing pieces of s, "|" between.
    private static String pieces(String s, List<int[]> ranges) {
        final StringBuilder sb = new StringBuilder();
        for (int[] r : ranges) {
            if (sb.length() > 0)
                sb.append('|');
            sb.append(s, r[0], r[1]);
        }
        return sb.toString();
    }

    @Test
    public void marksTheWordsThatChanged() {
        final String a = "int count = items.size();";
        final String b = "long count = list.size() + 1;";
        final WordDiff.Result r = WordDiff.diff(a, b);
        assertEquals("int|items", pieces(a, r.removed()));
        assertEquals("long|list| + 1", pieces(b, r.added()));
    }

    @Test
    public void anInsertionMarksNothingRemoved() {
        final WordDiff.Result r = WordDiff.diff("foo(a)", "foo(a, b)");
        assertTrue(r.removed().isEmpty());
        assertEquals(", b", pieces("foo(a, b)", r.added()));
    }

    @Test
    public void linesWithLittleInCommonAreLeftWhole() {
        final WordDiff.Result r = WordDiff.diff("return x;", "throw new IllegalStateException(message);");
        assertTrue(r.removed().isEmpty());
        assertTrue(r.added().isEmpty());
    }

    @Test
    public void tokensAreWordsSpacesAndSingleCharacters() {
        assertEquals(
            List.of("a_1", "  ", "+", "+", "b"),
            WordDiff.tokens("a_1  ++b").stream().map(t -> "a_1  ++b".substring(t[0], t[1])).toList());
    }

    @Test
    public void charsMarksEachCharacterThatDiffers() {
        final WordDiff.Result r = WordDiff.chars("hello world", "hallo worlD");
        assertEquals("e|d", pieces("hello world", r.removed()));
        assertEquals("a|D", pieces("hallo worlD", r.added()));
    }

    @Test
    public void longLinesAreLeftWhole() {
        final String a = "x".repeat(300);
        final String b = "x".repeat(150) + "y" + "x".repeat(150);
        assertEquals("", pieces(a, WordDiff.chars(a, b).removed()), "300 by 301 characters: past the limit");
        assertEquals("y", pieces(b.substring(150), WordDiff.chars(a.substring(150), b.substring(150)).added()));
    }
}
