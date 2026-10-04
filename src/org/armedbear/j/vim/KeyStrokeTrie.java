/*
 * KeyStrokeTrie.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Key sequences to whatever they mean, matched one keystroke at a time.
 *
 * Vim commands are prefixes of one another -- {@code g} is the start of
 * {@code gg} and {@code ge} but is nothing by itself -- so after each key the
 * answer is one of three things, not two: this is a command, this could still
 * become one, or this is nothing. A trie gives all three in one lookup, which
 * a flat list of bindings cannot.
 *
 * A step of a sequence may be the placeholder {@code &lt;character&gt;}, which
 * stands for any single key: that is how {@code f} in {@code f&lt;character&gt;}
 * waits for the character to find without every character needing its own
 * binding. The key it matched comes back in the result.
 */
public final class KeyStrokeTrie<T> {
    /** The placeholder that matches any one keystroke. */
    public static final String ANY_CHARACTER = "<character>";

    /** What a sequence of keys amounts to so far. */
    public enum Status {
        /** A complete command. */
        FULL,
        /** Not yet a command, but the start of at least one. */
        PARTIAL,
        /** Not the start of anything. */
        NONE
    }

    public static final class Match<T> {
        public final Status status;
        public final T value;
        /** The key a {@code <character>} placeholder matched, or null. */
        public final String character;
        /**
         * A complete command here that a longer one is still being waited for.
         *
         * When {@code ,} is a command and {@code ,d} is a mapping, typing
         * {@code ,} is both. The status is {@link Status#PARTIAL}, because the
         * longer one may still arrive, and this is what to fall back to when
         * it does not.
         */
        public final T fallback;

        Match(Status status, T value, String character, T fallback) {
            this.status = status;
            this.value = value;
            this.character = character;
            this.fallback = fallback;
        }
    }

    private static final class Node<T> {
        final Map<String, Node<T>> children = new HashMap<>();
        Node<T> anyCharacter;
        T value;
    }

    private final Node<T> root = new Node<>();

    /**
     * Binds a key sequence.
     *
     * A later binding of the same sequence replaces the earlier one, so a user
     * map can override a built-in by being added after it.
     */
    public void put(List<String> keys, T value) {
        Node<T> node = root;
        for (String key : keys) {
            if (key.equals(ANY_CHARACTER)) {
                if (node.anyCharacter == null)
                    node.anyCharacter = new Node<>();
                node = node.anyCharacter;
            } else {
                Node<T> child = node.children.get(key);
                if (child == null) {
                    child = new Node<>();
                    node.children.put(key, child);
                }
                node = child;
            }
        }
        node.value = value;
    }

    public void remove(List<String> keys) {
        final Node<T> node = walk(keys);
        if (node != null)
            node.value = null;
    }

    public boolean isEmpty() {
        return root.children.isEmpty() && root.anyCharacter == null;
    }

    /** What this sequence of keys amounts to. */
    public Match<T> match(List<String> keys) {
        Node<T> node = root;
        String character = null;
        for (String key : keys) {
            Node<T> next = node.children.get(key);
            if (next == null && node.anyCharacter != null) {
                next = node.anyCharacter;
                character = key;
            }
            if (next == null)
                return new Match<>(Status.NONE, null, null, null);
            node = next;
        }
        // A command that is also the start of a longer one waits: the longer
        // one wins if it arrives, and this is the fallback if it does not.
        if (node.value != null && hasChildren(node))
            return new Match<>(Status.PARTIAL, null, character, node.value);
        if (node.value != null)
            return new Match<>(Status.FULL, node.value, character, null);
        return hasChildren(node)
            ? new Match<T>(Status.PARTIAL, null, character, null)
            : new Match<T>(Status.NONE, null, null, null);
    }

    private static boolean hasChildren(Node<?> node) {
        return !node.children.isEmpty() || node.anyCharacter != null;
    }

    private Node<T> walk(List<String> keys) {
        Node<T> node = root;
        for (String key : keys) {
            node = key.equals(ANY_CHARACTER)
                ? node.anyCharacter
                : node.children.get(key);
            if (node == null)
                return null;
        }
        return node;
    }
}
