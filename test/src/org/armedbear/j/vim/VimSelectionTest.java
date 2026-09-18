/*
 * VimSelectionTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.armedbear.j.EditorHarness;
import org.junit.After;
import org.junit.Test;

/**
 * Outside visual mode there is no selection.
 *
 * j's selection is a mark and a dot, and several things set a mark without
 * meaning to start a selection: an operator sets one so it can reuse j's
 * region delete, and j's undo records the mark alongside the text. Undoing a
 * delete therefore used to restore the operator's scratch mark, leaving a
 * selection that grew with every motion and that Escape did not clear.
 */
public class VimSelectionTest
{
    private EditorHarness h;

    @After
    public void tearDown()
    {
        if (h != null)
            h.close();
    }

    private EditorHarness vim(String text)
    {
        h = EditorHarness.create(text).vim();
        return h;
    }

    private void noSelection(String what)
    {
        assertNull(what, h.editor().getMark());
    }

    @Test
    public void undoingADeleteLeavesNoSelection()
    {
        vim("alpha bravo charlie\n").cursor(0, 0).keys("dw");
        noSelection("the operator cleaned up after itself");
        h.keys("u");
        noSelection("and undo did not bring the scratch mark back");
    }

    @Test
    public void undoingACharacterDeleteLeavesNoSelection()
    {
        vim("alpha bravo\n").cursor(0, 3).keys("xu");
        noSelection("after x then u");
    }

    @Test
    public void undoingALineDeleteLeavesNoSelection()
    {
        vim("one\ntwo\nthree\n").cursor(0, 0).keys("ddu");
        noSelection("after dd then u");
    }

    @Test
    public void redoLeavesNoSelectionEither()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("dwu<C-r>");
        noSelection("after redo");
    }

    @Test
    public void aMotionAfterUndoDoesNotDragASelection()
    {
        // This is what made it feel stuck: every motion grew the selection.
        vim("alpha bravo charlie\n").cursor(0, 0).keys("dwul");
        noSelection("a motion in normal mode never extends a selection");
    }

    @Test
    public void escapeClearsASelectionInNormalMode()
    {
        vim("alpha bravo\n").cursor(0, 0);
        // A selection from somewhere other than visual mode -- a mouse drag
        // makes one the same way.
        h.editor().setMark(new org.armedbear.j.Position(
            h.buffer().getFirstLine(), 0));
        h.editor().setDot(h.buffer().getFirstLine(), 5);
        assertNotNull(h.editor().getMark());

        h.keys("<Esc>");
        noSelection("Escape means stop, selections included");
    }

    @Test
    public void escapeStillLeavesVisualModeProperly()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("vll<Esc>");
        noSelection("after leaving visual mode");
        assertEquals(VimMode.NORMAL, h.vimState().getMode());
    }

    @Test
    public void visualModeKeepsItsSelectionWhileMotionsExtendIt()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("vll");
        assertNotNull("visual mode is the one place a selection belongs",
                      h.editor().getMark());
        assertEquals(2, h.offset());
    }

    // ------------------------------------------- where undo puts the caret

    @Test
    public void undoPutsTheCaretAtTheStartOfWhatCameBack()
    {
        vim("alpha bravo charlie\n").cursor(0, 0).keys("dwu");
        assertEquals("line", 0, h.lineNumber());
        assertEquals("offset", 0, h.offset());
    }

    @Test
    public void undoOfACharacterDeletePutsTheCaretWhereItHappened()
    {
        vim("alpha bravo\n").cursor(0, 3).keys("xu");
        assertEquals(3, h.offset());
    }

    @Test
    public void undoOfALineDeletePutsTheCaretOnThatLine()
    {
        vim("one\ntwo\nthree\n").cursor(0, 0).keys("ddu");
        assertEquals(0, h.lineNumber());
        assertEquals(0, h.offset());
    }

    // ---------------------------------------- painting the selection out

    // Clearing the mark changes the model but paints nothing: j repaints by
    // line, and no line changed. Every way out of a selection has to ask for
    // the highlight to be redrawn or it stays on screen.

    @Test
    public void leavingVisualModeAsksForARepaint()
    {
        vim("alpha bravo\n").cursor(0, 0).keys("vll");
        h.clearRepaintPending();
        h.keys("<Esc>");
        assertTrue("the highlight has to be painted out", h.repaintPending());
    }

    @Test
    public void anOperatorThatLeavesVisualModeAsksForARepaint()
    {
        // Yank changes no text, so nothing else would redraw those lines.
        vim("alpha bravo\n").cursor(0, 0).keys("vll");
        h.clearRepaintPending();
        h.keys("y");
        assertTrue(h.repaintPending());
    }

    @Test
    public void droppingAStraySelectionAsksForARepaint()
    {
        // A selection from somewhere other than visual mode, as a mouse drag
        // leaves: the motion that drops it has to paint it out too.
        vim("alpha bravo\n").cursor(0, 0);
        h.editor().setMark(new org.armedbear.j.Position(
            h.buffer().getFirstLine(), 0));
        h.editor().setDot(h.buffer().getFirstLine(), 5);
        h.clearRepaintPending();
        h.keys("l");
        assertTrue(h.repaintPending());
    }

    @Test
    public void anOrdinaryMotionAsksForNothing()
    {
        // The control: without this, the three above would pass however
        // freely a repaint was requested.
        vim("alpha bravo\n").cursor(0, 0);
        h.clearRepaintPending();
        h.keys("l");
        assertFalse("moving the caret repaints two lines, not the window",
                    h.repaintPending());
    }
}
