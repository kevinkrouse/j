/*
 * PairTable.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A mode's electric pairs: which opener inserts which closer, and which
 * characters are closers. Built from the mode's pairs string, two
 * characters to a pair: the opener then its closer. A symmetric
 * delimiter pairs with itself, so {@code ""} is the two-character pair
 * {@code "}. Lookups scan the string; it is short, so no index is worth
 * building. Tables are cached by their string, so a mode's table is
 * built once.
 */
public final class PairTable {
    private static final Map<String, PairTable> CACHE = new ConcurrentHashMap<>();

    private final String pairs;

    /** The table for a pairs string, built once and shared. */
    public static PairTable forPairs(String pairs) {
        if (pairs.length() % 2 != 0)
            throw new IllegalArgumentException("pairs must be whole pairs: \"" + pairs + "\"");
        return CACHE.computeIfAbsent(pairs, PairTable::new);
    }

    private PairTable(String pairs) {
        this.pairs = pairs;
    }

    /** The closer an opener inserts, or 0 if c is not an opener. */
    public char closerFor(char c) {
        for (int i = 0; i + 1 < pairs.length(); i += 2)
            if (pairs.charAt(i) == c)
                return pairs.charAt(i + 1);
        return 0;
    }

    /** Whether c opens a pair. */
    public boolean isOpener(char c) {
        return closerFor(c) != 0;
    }

    /** Whether c closes a pair. A symmetric delimiter is both. */
    public boolean isCloser(char c) {
        for (int i = 1; i < pairs.length(); i += 2)
            if (pairs.charAt(i) == c)
                return true;
        return false;
    }

    /** A normalized pair: the opener and its closer, in that order. */
    public record Pair(char opener, char closer) {}

    /**
     * The pair a delimiter belongs to, resolved from either end: {@code (}
     * and {@code )} both give the same {@code (}, {@code )}. A closer
     * typed over, or a selection wrapped by a closer, resolves its pair
     * through here. Null if c is in no pair.
     */
    public Pair pairFor(char c) {
        for (int i = 0; i + 1 < pairs.length(); i += 2)
            if (pairs.charAt(i) == c || pairs.charAt(i + 1) == c)
                return new Pair(pairs.charAt(i), pairs.charAt(i + 1));
        return null;
    }
}
