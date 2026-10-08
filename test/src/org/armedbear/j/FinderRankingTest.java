/*
 * FinderRankingTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.armedbear.j.util.FuzzyMatcher.Query;
import org.junit.jupiter.api.Test;

/** A finder lists what matches in an item's primary text before what matches only in the rest. */
public class FinderRankingTest {
    private record Item(String primary, String rest) implements FinderItem {
        @Override
        public String matchText() {
            return rest.isEmpty() ? primary : primary + " " + rest;
        }

        @Override
        public String primaryMatchText() {
            return primary;
        }

        @Override
        public String label() {
            return primary;
        }

        @Override
        public int labelOffset() {
            return 0;
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {}
    }

    private static List<String> ranked(String query, Item... items) {
        return FinderTextFieldHandler.rank(List.of(items), Query.parse(query))
                .stream()
                .map(row -> row.item().label())
                .toList();
    }

    @Test
    public void aNameMatchComesBeforeADescriptionMatch() {
        final Item described = new Item("cancelBackgroundProcess", "Stops the split window search");
        final Item named = new Item("splitWindow", "Splits the window in two");
        assertEquals(List.of("splitWindow", "cancelBackgroundProcess"), ranked("split", described, named));
    }

    @Test
    public void theHighlightedPositionsAreInTheName() {
        final Item named = new Item("splitWindow", "split split split");
        final int[] positions = FinderTextFieldHandler.rank(List.of(named), Query.parse("sw")).get(0).positions();
        assertEquals(0, positions[0]);
        assertEquals(5, positions[1]);
    }
}
