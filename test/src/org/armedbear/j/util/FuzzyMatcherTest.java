/*
 * FuzzyMatcherTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.armedbear.j.util.FuzzyMatcher.Match;
import org.armedbear.j.util.FuzzyMatcher.Query;
import org.armedbear.j.util.FuzzyMatcher.Ranked;
import org.junit.jupiter.api.Test;

public class FuzzyMatcherTest {
    private static List<String> rank(String query, String... candidates) {
        List<String> out = new ArrayList<>();
        for (Ranked<String> r : FuzzyMatcher.rank(List.of(candidates), Function.identity(), Query.parse(query), 50))
            out.add(r.item());
        return out;
    }

    private static int score(String candidate, String query) {
        Integer s = FuzzyMatcher.score(candidate, Query.parse(query));
        assertNotNull(s, query + " should match " + candidate);
        return s;
    }

    @Test
    public void subsequenceRequired() {
        assertNull(FuzzyMatcher.match("KeyMap.java", Query.parse("kmx")));
        assertNotNull(FuzzyMatcher.match("KeyMap.java", Query.parse("kmj")));
        assertNull(FuzzyMatcher.match("ab", Query.parse("abc")));
    }

    @Test
    public void emptyQueryMatchesEverythingInOrder() {
        assertEquals(List.of("b", "a", "c"), rank("  ", "b", "a", "c"));
    }

    @Test
    public void positionsPreferBoundaries() {
        Match m = FuzzyMatcher.match("KeyMap.java", Query.parse("kmj"));
        assertArrayEquals(new int[] { 0, 3, 7 }, m.positions());
        // The file name's M, not the directory's.
        m = FuzzyMatcher.match("src/main/MainFrame.java", Query.parse("mf"));
        assertArrayEquals(new int[] { 9, 13 }, m.positions());
    }

    @Test
    public void abbreviationFindsCamelHumps() {
        List<String> r = rank(
            "ofth",
            "doc/other/fetch.html",
            "test/src/org/armedbear/j/OpenFileTextFieldHandlerTest.java",
            "src/org/armedbear/j/OpenFileTextFieldHandler.java"
        );
        assertEquals("src/org/armedbear/j/OpenFileTextFieldHandler.java", r.get(0));
        assertEquals("doc/other/fetch.html", r.get(2));
    }

    @Test
    public void basenameBeatsDirectory() {
        List<String> r = rank("keymap", "src/keymap/Foo.java", "src/org/KeyMap.java");
        assertEquals("src/org/KeyMap.java", r.get(0));
    }

    @Test
    public void consecutiveBeatsScattered() {
        assertTrue(score("KeyMap.java", "map") > score("mxaxp.java", "map"));
    }

    @Test
    public void boundaryBeatsMiddleOfWord() {
        assertTrue(score("src/map.c", "map") > score("src/bitmap.c", "map"));
    }

    @Test
    public void smartCase() {
        assertNotNull(FuzzyMatcher.match("KeyMap.java", Query.parse("KM")));
        assertNull(FuzzyMatcher.match("kmart.txt", Query.parse("KM")));
        assertNotNull(FuzzyMatcher.match("KMART.txt", Query.parse("km")));
    }

    @Test
    public void termsMatchInAnyOrder() {
        assertEquals(List.of("KeyMap.java"), rank("java key", "KeyMap.java", "KeyMap.txt", "Other.java"));
        Match m = FuzzyMatcher.match("KeyMap.java", Query.parse("java key"));
        assertArrayEquals(new int[] { 0, 1, 2, 7, 8, 9, 10 }, m.positions());
    }

    @Test
    public void tiesGoToShorterThenAlphabetical() {
        assertEquals(List.of("ab", "abx", "aby"), rank("ab", "aby", "abx", "ab"));
    }

    @Test
    public void limitKeepsTheBest() {
        List<String> r = rank("map", "a/bitmap.c", "map.c", "x/m/a/p.c");
        assertEquals("map.c", r.get(0));
        List<Ranked<String>> one = FuzzyMatcher.rank(
            List.of("a/bitmap.c", "map.c", "x/m/a/p.c"),
            Function.identity(),
            Query.parse("map"),
            1
        );
        assertEquals(1, one.size());
        assertEquals("map.c", one.get(0).item());
    }

    @Test
    public void scoreAgreesWithMatch() {
        String[] candidates = { "src/org/armedbear/j/KeyMap.java", "a_b-c.d", "FooBarBaz", "x" };
        for (String c : candidates) {
            for (String q : new String[] { "k", "kmj", "ab", "fbb", "x", "a c" }) {
                Match m = FuzzyMatcher.match(c, Query.parse(q));
                Integer s = FuzzyMatcher.score(c, Query.parse(q));
                if (m == null)
                    assertNull(s, c + " / " + q);
                else
                    assertEquals(m.score(), s, c + " / " + q);
            }
        }
    }

    @Test
    public void longCandidatesMatchGreedily() {
        String c = "a/".repeat(600) + "KeyMap.java";
        Match m = FuzzyMatcher.match(c, Query.parse("kmj"));
        assertNotNull(m);
        assertEquals(3, m.positions().length);
    }

    @Test
    public void topLevelFileBeatsNestedOne() {
        assertEquals("KeyMap.java", rank("keymap", "src/org/KeyMap.java", "KeyMap.java").get(0));
    }

    @Test
    public void directoryNameCountsAsBasename() {
        assertTrue(score("src/keymap/", "keymap") > score("src/keymap/Foo.java", "keymap"));
    }

    @Test
    public void longCandidatesScoreLikeShortOnes() {
        assertEquals(score("x/".repeat(10) + "FooBarBaz", "foobar"), score("x/".repeat(600) + "FooBarBaz", "foobar"));
    }

    @Test
    public void nullTextIsSkippedEvenForAnEmptyQuery() {
        List<String> items = new ArrayList<>();
        items.add(null);
        items.add("a");
        assertEquals(1, FuzzyMatcher.rank(items, Function.identity(), Query.parse(""), 10).size());
    }

    @Test
    public void unboundedLimit() {
        assertEquals(
            2,
            FuzzyMatcher.rank(List.of("ab", "abc"), Function.identity(), Query.parse("ab"), Integer.MAX_VALUE).size()
        );
    }

    @Test
    public void anySpaceSeparatesTerms() {
        assertNotNull(FuzzyMatcher.match("KeyMap.java", Query.parse("map\u00A0key")));
    }

    @Test
    public void rankingManyPathsIsQuick() {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 100_000; i++)
            paths.add("src/org/armedbear/j/mode/m" + (i % 97) + "/sub" + (i % 13) + "/File" + i + "Handler.java");
        // Warm up, then time.
        FuzzyMatcher.rank(paths, Function.identity(), Query.parse("fh"), 50);
        long start = System.nanoTime();
        List<Ranked<String>> r = FuzzyMatcher.rank(paths, Function.identity(), Query.parse("m5 f12h"), 50);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(50, r.size());
        // Generous, for slow machines; typically tens of milliseconds.
        assertTrue(ms < 10_000, "ranking 100k paths took " + ms + " ms");
    }

    @Test
    public void spacesStripLeavesAreNotEmptyTerms() {
        // U+00A0 isn't white space to strip(), but it does separate terms.
        assertNotNull(FuzzyMatcher.match("KeyMap.java", Query.parse("\u00A0key")));
        assertEquals(
            1,
            FuzzyMatcher.rank(List.of("KeyMap.java"), Function.identity(), Query.parse("\u00A0key\u00A0"), 5).size()
        );
    }
}
