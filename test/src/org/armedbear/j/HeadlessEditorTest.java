/*
 * HeadlessEditorTest.java
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An editor with no window still edits.
 *
 * These are the tests that keep the headless path working. The modal editing
 * work is built on the ability to press a key in a test and see the buffer
 * change, so if this class goes red nothing above it can be trusted.
 */
public class HeadlessEditorTest {
    private EditorHarness h;

    @BeforeEach
    public void setUp() {
        h = EditorHarness.create("alpha bravo\ncharlie delta\necho foxtrot\n");
    }

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    @Test
    public void editorIsUsableWithoutAFrame() {
        assertNull(h.editor().getFrame(), "a harness editor has no frame");
        assertNotNull(h.editor().getDisplay());
        assertNotNull(h.editor().getDispatcher());
        assertEquals(3, h.buffer().getLineCount());
        // The methods that reach for the frame must tolerate its absence.
        h.editor().status("a message with nowhere to go");
        assertNull(h.editor().getStatusBar());
        assertNull(h.editor().getSidebar());
        h.editor().setFocusToDisplay();
        h.editor().ensureActive();
    }

    // The harness is the instrument every other test reads; if it lies, they
    // all lie. It did: value() used to drop a leading empty line.

    @Test
    public void valueRoundTripsThroughTheCodeMirrorLineModel() {
        final String[] values = {
            "", "a", "a\nb", "\na", "a\n", "\n", "\n\n",
            "\na\n", "a\n\nb", "  \n   \n",
        };
        for (String value : values) {
            final EditorHarness harness = EditorHarness.create();
            try {
                harness.value(value);
                assertEquals(value, harness.value(), "round trip of " + value.replace("\n", "\\n"));
                assertEquals(
                    value.split("\n", -1).length,
                    harness.buffer().getLineCount(),
                    "line count of " + value.replace("\n", "\\n")
                );
            }
            finally {
                harness.close();
            }
        }
    }

    @Test
    public void aLeadingEmptyLineSurvives() {
        h.text("\nabc\n");
        assertEquals(2, h.buffer().getLineCount());
        assertEquals("\nabc", h.value());
        assertEquals("\nabc\n", h.text());
    }

    @Test
    public void typingInsertsThroughTheRealDispatcher() {
        h.cursor(0, 0).keys("XY");
        h.assertText("XYalpha bravo\ncharlie delta\necho foxtrot\n");
        h.assertCursorAt(0, 2);
    }

    @Test
    public void typingInTheMiddleOfALine() {
        h.cursor(1, 7).keys("!");
        h.assertText("alpha bravo\ncharlie! delta\necho foxtrot\n");
        h.assertCursorAt(1, 8);
    }

    @Test
    public void undoRestoresWhatWasTyped() {
        h.cursor(0, 0).keys("XY");
        h.assertText("XYalpha bravo\ncharlie delta\necho foxtrot\n");

        h.editor().undo();
        h.assertText("alpha bravo\ncharlie delta\necho foxtrot\n");
    }

    @Test
    public void enterSplitsTheLine() {
        h.cursor(0, 5).keys("<CR>");
        h.assertText("alpha\n bravo\ncharlie delta\necho foxtrot\n");
    }

    @Test
    public void backspaceDeletesBackwards() {
        h.cursor(0, 5).keys("<BS>");
        h.assertText("alph bravo\ncharlie delta\necho foxtrot\n");
        h.assertCursorAt(0, 4);
    }

    @Test
    public void arrowKeysMoveWithoutInserting() {
        h.cursor(0, 0).keys("<Right><Right><Down>");
        h.assertText("alpha bravo\ncharlie delta\necho foxtrot\n");
        h.assertCursorAt(1, 2);
    }

    @Test
    public void aBoundModifiedKeyRunsItsCommand() {
        // Ctrl-Right is wordRight in the default global key map. The point is
        // that the chord reaches a command and never lands in the buffer as
        // the character 'Right' would.
        h.cursor(0, 0).keys("<C-Right>");
        h.assertText("alpha bravo\ncharlie delta\necho foxtrot\n");
        assertEquals(6, h.offset(), "moved to the next word");
    }

    @Test
    public void aModifiedLetterIsNotSelfInserted() {
        // Whatever <C-d> happens to be bound to, a 'd' must not appear.
        final String before = h.text();
        h.cursor(0, 0).keys("<C-d>");
        assertEquals(before, h.text());
    }

    @Test
    public void anUnboundControlChordChangesNothing() {
        final String before = h.text();
        h.cursor(0, 0).keys("<C-F9>");
        assertEquals(before, h.text());
    }
}
