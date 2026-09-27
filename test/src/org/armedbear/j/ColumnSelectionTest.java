/*
 * ColumnSelectionTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

/** j's own column selection -- cut, copy and pasteColumn -- on Block. */
public class ColumnSelectionTest
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        if (h != null)
            h.close();
    }

    /** A column selection from the mark to the caret. */
    private Editor select(String text, int markLine, int markOffset,
                          int dotLine, int dotOffset)
    {
        h = EditorHarness.create().value(text);
        final Editor editor = h.editor();
        editor.setMark(new Position(h.buffer().getLine(markLine), markOffset));
        h.cursor(dotLine, dotOffset);
        // Region takes the caret's column from the display.
        editor.moveCaretToDotCol();
        editor.setColumnSelection(true);
        return editor;
    }

    private String caret()
    {
        return h.lineNumber() + "," + h.offset();
    }

    @Test
    public void cutTakesTheColumnsAndPasteColumnPutsThemBack()
    {
        final Editor editor = select("abcd\nefgh\nijkl", 0, 1, 2, 3);
        editor.killRegion();
        assertEquals("ad\neh\nil", h.value());
        assertEquals("0,1", caret());
        editor.pasteColumn();
        assertEquals("abcd\nefgh\nijkl", h.value());
        // After the last piece.
        assertEquals("2,3", caret());
    }

    @Test
    public void aColumnMayBeMarkedFromItsTopRight()
    {
        final Editor editor = select("abcd\nefgh\nijkl", 0, 3, 2, 1);
        editor.killRegion();
        assertEquals("ad\neh\nil", h.value());
    }

    @Test
    public void cutIsOneUndoStep()
    {
        final Editor editor = select("abcd\nefgh\nijkl", 0, 1, 2, 3);
        editor.killRegion();
        editor.undo();
        assertEquals("abcd\nefgh\nijkl", h.value());
    }

    @Test
    public void aTabOutsideTheColumnsStaysATab()
    {
        final Editor editor = select("x\ty\nabcdefghij", 0, 0, 1, 1);
        editor.killRegion();
        assertEquals("\ty\nbcdefghij", h.value());
    }

    @Test
    public void aTabTheEdgeCutsIsSplit()
    {
        // Columns 1 and 2 of a tab covering 1 to 7.
        final Editor editor = select("a\tb\nabcdefghij", 0, 1, 1, 3);
        editor.killRegion();
        assertEquals("a     b\nadefghij", h.value());
    }

    @Test
    public void pasteColumnPadsShortLinesAndAddsLines()
    {
        final Editor editor = select("abcd\nefgh", 0, 0, 1, 2);
        editor.copyRegion();
        editor.setColumnSelection(false);
        editor.setMark(null);
        h.value("x");
        h.cursor(0, 1);
        editor.moveCaretToDotCol();
        editor.pasteColumn();
        assertEquals("xab\n ef", h.value());
        assertEquals("1,3", caret());
    }

    @Test
    public void pastedColumnsStayInLine()
    {
        final Editor editor = select("a\nbcd", 0, 0, 1, 3);
        editor.copyRegion();
        editor.setColumnSelection(false);
        editor.setMark(null);
        h.value("12\n34");
        h.cursor(0, 1);
        editor.moveCaretToDotCol();
        editor.pasteColumn();
        // "a" is padded to the width of "bcd", so the 2 stays over the 4.
        assertEquals("1a  2\n3bcd4", h.value());
    }
}
