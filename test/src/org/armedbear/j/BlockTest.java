/*
 * BlockTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import org.junit.After;
import org.junit.Test;

/** Block: screen columns over lines, with a tab width of eight. */
public class BlockTest
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        if (h != null)
            h.close();
    }

    private Line line(int n)
    {
        return h.buffer().getLine(n);
    }

    /** Lines first..last, columns [start, end). */
    private Block block(String text, int first, int last, int start, int end)
    {
        h = EditorHarness.create().value(text);
        return new Block(h.buffer(), line(first), line(last), start, end,
                         false);
    }

    @Test
    public void aTabTheEdgesCutThroughIsSpacesInside()
    {
        // The tab covers columns 1 to 7.
        final Block b = block("a\tbc\ncdefghijkl", 0, 1, 3, 9);
        assertEquals(Arrays.asList("     b", "fghijk"), b.getLines());
    }

    @Test
    public void aTabWhollyInsideStaysATab()
    {
        final Block b = block("ab\tcd\nxy", 0, 1, 1, 9);
        assertEquals("b\tc\ny", b.getText());
        b.transform(h.editor(), String::toUpperCase);
        assertEquals("aB\tCd\nxY", h.value());
    }

    @Test
    public void deleteKeepsWhatACutTabCoveredOutside()
    {
        final Block b = block("a\tbc\ncdefghijkl", 0, 1, 3, 9);
        b.delete(h.editor());
        assertEquals("a  c\ncdel", h.value());
        assertEquals(0, h.lineNumber());
        assertEquals(3, h.offset());
    }

    @Test
    public void aBlockInsideOneTab()
    {
        final Block b = block("a\tb", 0, 0, 3, 5);
        assertEquals("  ", b.getText());
        b.delete(h.editor());
        assertEquals("a     b", h.value());
    }

    @Test
    public void deleteIsOneUndoStep()
    {
        final Block b = block("abcd\nefgh\nijkl", 0, 2, 1, 3);
        b.delete(h.editor());
        assertEquals("ad\neh\nil", h.value());
        h.editor().undo();
        assertEquals("abcd\nefgh\nijkl", h.value());
    }

    @Test
    public void offsetsOnEachLine()
    {
        final Block b = block("a\tbc\n\ncd", 0, 2, 1, 9);
        assertArrayEquals(new int[] {1, 3}, b.getOffsets(line(0)));
        assertNull(b.getOffsets(line(1)));
        assertArrayEquals(new int[] {1, 2}, b.getOffsets(line(2)));
    }

    @Test
    public void toEolIsRagged()
    {
        h = EditorHarness.create().value("ab\ncdef\ngh");
        final Block b = Block.between(h.buffer(), new Position(line(0), 1),
                                      new Position(line(2), 1), true);
        assertEquals("b\ndef\nh", b.getText());
    }

    @Test
    public void betweenTakesInBothCorners()
    {
        h = EditorHarness.create().value("abcd\nefgh");
        final Block b = Block.between(h.buffer(), new Position(line(1), 2),
                                      new Position(line(0), 1), false);
        assertEquals("bc\nfg", b.getText());
    }

    @Test
    public void insertSkipsALineThatEndsBeforeTheEdge()
    {
        final Block b = block("ab\n\nc\ndef", 0, 3, 1, 1);
        b.insertOnEachLine(h.editor(), "X", false);
        assertEquals("aXb\n\ncX\ndXef", h.value());
    }

    @Test
    public void appendPadsAShortLine()
    {
        final Block b = block("test\nme\nplease", 0, 2, 1, 4);
        b.insertOnEachLine(h.editor(), "X", true);
        assertEquals("testX\nme  X\npleaXse", h.value());
    }

    @Test
    public void shiftLeftTakesOnlyBlanks()
    {
        final Block b = block("a     b\na  c", 0, 1, 1, 1);
        b.shiftLeft(h.editor(), 3);
        assertEquals("a  b\nac", h.value());
    }

    @Test
    public void putPadsAndAddsLines()
    {
        h = EditorHarness.create().value("hello\nfoo");
        Block.put(h.editor(), line(0), 4, Arrays.asList("x", "yy", "z"));
        assertEquals("hellx o\nfoo yy\n    z", h.value());
        assertEquals(0, h.lineNumber());
        assertEquals(4, h.offset());
    }

    @Test
    public void positionAtIsOnTheCharacterACoveredColumnIsIn()
    {
        h = EditorHarness.create().value("a\tbc");
        assertEquals(1, Block.positionAt(h.buffer(), line(0), 5).getOffset());
        assertEquals(2, Block.positionAt(h.buffer(), line(0), 8).getOffset());
        assertEquals(4, Block.positionAt(h.buffer(), line(0), 20).getOffset());
    }
}
