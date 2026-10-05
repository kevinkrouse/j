/*
 * VimKeyMapTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The key map table: the file a user edits to change what keys do.
 */
public class VimKeyMapTest {
    private static List<String> keys(String s) {
        return KeyNotation.tokenize(s);
    }

    // ------------------------------------------------------------- parsing

    @Test
    public void aRowBecomesACommand() {
        final VimCommand c =
            VimKeyMap.parse("n,v,o  w  motion  moveByWords  forward,count=3");
        assertEquals("w", c.getKeys());
        assertSame(VimCommand.Kind.MOTION, c.getKind());
        assertEquals("moveByWords", c.getCommand());
        assertTrue(c.getBoolean("forward"));
        assertEquals(3, c.getInt("count", 0));
        assertEquals(3, c.getModes().size());
        assertTrue(c.getModes().contains(MappingMode.NORMAL));
        assertTrue(c.getModes().contains(MappingMode.OP_PENDING));
    }

    @Test
    public void aBareArgumentMeansTrue() {
        final VimCommand c = VimKeyMap.parse("n  x  motion  m  inclusive");
        assertTrue(c.getBoolean("inclusive"));
        assertFalse(c.getBoolean("linewise"));
    }

    @Test
    public void aDashMeansNoArguments() {
        final VimCommand c = VimKeyMap.parse("n  0  motion  moveToStartOfLine  -");
        assertTrue(c.getArgs().isEmpty());
    }

    @Test
    public void argumentsAreOptional() {
        final VimCommand c = VimKeyMap.parse("n  0  motion  moveToStartOfLine");
        assertTrue(c.getArgs().isEmpty());
    }

    @Test
    public void badRowsAreRejected() {
        final String[] bad = {
            "n  w", // no kind
            "n  w  wobble  moveByWords", // no such kind
            "zz  w  motion  moveByWords", // no such mode
            "n  w  motion", // a motion with no name
        };
        for (String row : bad) {
            try {
                VimKeyMap.parse(row);
                fail("expected a rejection: " + row);
            }
            catch (RuntimeException expected) {}
        }
    }

    @Test
    public void oneBadRowDoesNotStopTheRest() throws Exception {
        final VimKeyMap keyMap = new VimKeyMap();
        keyMap.load(
            new StringReader(
                "# a comment\n"
                    + "\n"
                    + "n  w  motion  moveByWords  forward\n"
                    + "n  ?  nonsense  whatever\n"
                    + "n  b  motion  moveByWords  forward=false\n"
            )
        );
        assertSame(
            KeyStrokeTrie.Status.FULL,
            keyMap.getTrie(MappingMode.NORMAL).match(keys("w")).status
        );
        assertSame(
            KeyStrokeTrie.Status.FULL,
            keyMap.getTrie(MappingMode.NORMAL).match(keys("b")).status,
            "the good row after the bad one still loaded"
        );
    }

    @Test
    public void aLaterRowOverridesAnEarlierOne() {
        final VimKeyMap keyMap = new VimKeyMap();
        keyMap.add(VimKeyMap.parse("n  w  motion  moveByWords  forward"));
        keyMap.add(VimKeyMap.parse("n  w  motion  moveToEol  -"));
        assertEquals(
            "moveToEol",
            keyMap.getTrie(MappingMode.NORMAL)
                .match(keys("w")).value.getCommand()
        );
    }

    @Test
    public void aBindingLandsOnlyInTheModesItNames() {
        final VimKeyMap keyMap = new VimKeyMap();
        keyMap.add(VimKeyMap.parse("i  <C-w>  action  deleteWordBefore"));
        assertSame(
            KeyStrokeTrie.Status.FULL,
            keyMap.getTrie(MappingMode.INSERT).match(keys("<C-w>")).status
        );
        assertSame(
            KeyStrokeTrie.Status.NONE,
            keyMap.getTrie(MappingMode.NORMAL).match(keys("<C-w>")).status
        );
    }

    // ------------------------------------------------------------- the trie

    @Test
    public void aPrefixOfALongerBindingIsPartial() {
        final VimKeyMap keyMap = new VimKeyMap();
        keyMap.add(VimKeyMap.parse("n  gg  motion  moveToLine"));
        final KeyStrokeTrie<VimCommand> trie = keyMap.getTrie(MappingMode.NORMAL);
        assertSame(KeyStrokeTrie.Status.PARTIAL, trie.match(keys("g")).status);
        assertSame(KeyStrokeTrie.Status.FULL, trie.match(keys("gg")).status);
        assertSame(KeyStrokeTrie.Status.NONE, trie.match(keys("gx")).status);
    }

    @Test
    public void theCharacterPlaceholderMatchesAnyKeyAndReportsIt() {
        final VimKeyMap keyMap = new VimKeyMap();
        keyMap.add(VimKeyMap.parse("n  f<character>  motion  moveToCharacter  forward"));
        final KeyStrokeTrie<VimCommand> trie = keyMap.getTrie(MappingMode.NORMAL);
        assertSame(KeyStrokeTrie.Status.PARTIAL, trie.match(keys("f")).status);

        final KeyStrokeTrie.Match<VimCommand> match = trie.match(keys("fq"));
        assertSame(KeyStrokeTrie.Status.FULL, match.status);
        assertEquals("q", match.character);
    }

    @Test
    public void anExactBindingWinsOverThePlaceholder() {
        final VimKeyMap keyMap = new VimKeyMap();
        keyMap.add(VimKeyMap.parse("n  g<character>  motion  moveToCharacter"));
        keyMap.add(VimKeyMap.parse("n  gg  motion  moveToLine"));
        assertEquals(
            "moveToLine",
            keyMap.getTrie(MappingMode.NORMAL)
                .match(keys("gg")).value.getCommand()
        );
    }

    @Test
    public void aTableCanBeReadFromAFile() throws Exception {
        // What the vimKeyMap preference does: a whole table of one's own,
        // replacing the built-in rather than adding to it.
        final VimKeyMap keyMap = new VimKeyMap();
        keyMap.load(
            new StringReader(
                "# my own bindings\n"
                    + "n  q  motion  moveToEol  inclusive\n"
            )
        );
        assertSame(
            KeyStrokeTrie.Status.FULL,
            keyMap.getTrie(MappingMode.NORMAL).match(keys("q")).status
        );
        assertSame(
            KeyStrokeTrie.Status.NONE,
            keyMap.getTrie(MappingMode.NORMAL).match(keys("w")).status,
            "nothing else came with it"
        );
    }

    // ------------------------------------------------------- the built-in map

    @Test
    public void theBuiltInMapLoads() {
        final VimKeyMap keyMap = VimKeyMap.getDefault();
        for (MappingMode mode : MappingMode.values()) {
            assertNotNull(keyMap.getTrie(mode));
        }
        assertFalse(keyMap.getTrie(MappingMode.NORMAL).isEmpty(), "the resource should not be empty");
    }

    @Test
    public void everyBuiltInCommandNameResolves() {
        // A typo in the table would otherwise only show up as a key that
        // silently does nothing.
        final VimKeyMap keyMap = VimKeyMap.getDefault();
        for (String binding : new String[] {
            "h", "l", "j", "k", "0", "^", "$", "gg", "G", "|",
            "w", "W", "b", "B", "e", "E", "ge", "gE", ";", ",",
            "i", "a", "I", "A", "o", "O" }) {
            final KeyStrokeTrie.Match<VimCommand> match =
                keyMap.getTrie(MappingMode.NORMAL).match(keys(binding));
            assertSame(
                KeyStrokeTrie.Status.FULL,
                match.status,
                binding + " is not bound"
            );
            final VimCommand c = match.value;
            switch (c.getKind()) {
                case MOTION:
                    assertNotNull(VimMotions.get(c.getCommand()), c.getCommand() + " is not a known motion");
                    break;
                case ACTION:
                    assertNotNull(VimActions.get(c.getCommand()), c.getCommand() + " is not a known action");
                    break;
                default:
                    break;
            }
        }
    }

    // ------------------------------------------------------- keys for a command

    @Test
    public void keysForAJCommand() throws Exception {
        final VimKeyMap map = new VimKeyMap();
        map.load(
            new StringReader(
                "n,v  <C-w>v  command  vsplitWindow  param=vim\n"
                    + "n,v  <C-w><C-v>  command  vsplitWindow  param=vim\n"
                    + "v  zz  command  toCenter  -\n"
                    + "n  i  action  enterInsertMode  -\n"
            )
        );
        assertEquals("<C-w>v", map.keysFor("vsplitWindow"));
        assertEquals("<C-w>v", map.keysFor("VSPLITWINDOW"));
        assertNull(map.keysFor("toCenter")); // Not in normal mode.
        assertNull(map.keysFor("enterInsertMode")); // Not a j command.
        // Remapped keys no longer run it; the next binding does.
        map.add(VimKeyMap.parse("n  <C-w>v  command  splitWindow  -"));
        assertEquals("<C-w><C-v>", map.keysFor("vsplitWindow"));
    }

    @Test
    public void builtInTableNamesWindowCommands() {
        assertEquals("<C-w>s", VimKeyMap.getDefault().keysFor("splitWindow"));
    }

    @Test
    public void effectiveRowsLeaveOutReplacedOnes() throws Exception {
        final VimKeyMap map = new VimKeyMap();
        map.load(new StringReader("n  gd  command  findDefinitionAtDot  -\nn  gx  command  followLink  -\n"));
        map.add(VimKeyMap.parse("n  gd  command  findTag  -"));
        List<String> commands = map.getEffectiveRows().stream().map(VimCommand::getCommand).toList();
        assertEquals(List.of("followLink", "findTag"), commands);
    }

    @Test
    public void gdGoesToTheDefinition() {
        assertEquals("gd", VimKeyMap.getDefault().keysFor("findDefinitionAtDot"));
    }
}
