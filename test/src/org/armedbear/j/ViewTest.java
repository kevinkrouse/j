/*
 * ViewTest.java
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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ViewTest {
    private EditorHarness h;
    private EditorHarness other;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
        if (other != null)
            other.close();
    }

    private static String lines(int n) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
            sb.append("line ").append(i).append('\n');
        return sb.toString();
    }

    // A view of twenty lines, its caret at 12:3, its mark at 10:1 and its
    // top line 8, scrolled by 5 pixels, invalidated keeping its mark.
    private View invalidated() {
        return invalidated(true);
    }

    private View invalidated(boolean keepMark) {
        h = EditorHarness.create(lines(20));
        final Editor ed = h.editor();
        final Buffer b = h.buffer();
        ed.setDot(b.getLine(12), 3);
        ed.setMark(new Position(b.getLine(10), 1));
        ed.setTopLine(b.getLine(8));
        ed.getDisplay().setPixelsAboveTopLine(5);
        ed.saveView();
        final View view = ed.getView(b);
        if (keepMark)
            view.invalidateKeepingMark();
        else
            view.invalidate();
        return view;
    }

    @Test
    public void invalidatedAViewHasItsCaretMarkTopAndPixelsBackByLineNumber() {
        final View view = invalidated();
        view.resolve(h.buffer());
        assertEquals(12, view.getDot().lineNumber());
        assertEquals(3, view.getDot().getOffset());
        assertEquals(10, view.getMark().lineNumber());
        assertEquals(1, view.getMark().getOffset());
        assertEquals(8, view.getTopLine().lineNumber());
        assertEquals(5, view.getPixelsAboveTopLine());
    }

    @Test
    public void invalidatedAsWhenItsLinesAreReplacedAViewLosesItsMark() {
        final View view = invalidated(false);
        view.resolve(h.buffer());
        assertEquals(12, view.getDot().lineNumber());
        assertNull(view.getMark(), "what it selected may be gone");
    }

    @Test
    public void aMarkKeptStaysThroughAnotherInvalidate() {
        // As the buffer's own invalidate after a comparison's.
        final View view = invalidated(true);
        view.invalidate();
        view.resolve(h.buffer());
        assertEquals(10, view.getMark().lineNumber());
    }

    @Test
    public void linesPastTheEndAreTheLastAndOffsetsPastALinesEndItsEnd() {
        final View view = invalidated();
        other = EditorHarness.create("a\nbc\n");
        view.resolve(other.buffer());
        final Line last = other.buffer().getLastLine();
        assertEquals(last, view.getDot().getLine());
        assertEquals(last.length(), view.getDot().getOffset());
        assertEquals(last, view.getTopLine());
        assertEquals(0, view.getPixelsAboveTopLine(), "not the line it was scrolled on");
    }

    @Test
    public void aHiddenTopLineIsTheNextOneShown() {
        final View view = invalidated();
        h.buffer().getLine(8).setHidden(1);
        h.buffer().getLine(9).setHidden(1);
        view.resolve(h.buffer());
        assertEquals(10, view.getTopLine().lineNumber());
        assertEquals(0, view.getPixelsAboveTopLine());
    }

    @Test
    public void restoringAnInvalidatedViewKeepsItsTopLineNotTheFirst() {
        invalidated();
        h.editor().setTopLine(h.buffer().getFirstLine());
        h.buffer().restoreView(h.editor());
        assertEquals(8, h.editor().getDisplay().getTopLine().lineNumber());
        assertEquals(12, h.editor().getDotLineNumber());
        assertEquals(10, h.editor().getMark().lineNumber());
    }
}
