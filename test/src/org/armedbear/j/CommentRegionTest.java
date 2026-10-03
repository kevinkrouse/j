/*
 * CommentRegionTest.java
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

import org.armedbear.j.mode.html.HtmlMode;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.python.PythonMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class CommentRegionTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private String uncomment(Mode mode, String text) {
        h = EditorHarness.create(text).mode(mode);
        h.editor().selectAll();
        h.editor().uncommentRegion();
        return h.text();
    }

    @Test
    public void anIndentedCommentKeepsItsIndentation() {
        assertEquals("if (x) {\n    y();\n}\n", uncomment(JavaMode.getMode(), "if (x) {\n    // y();\n}\n"));
    }

    @Test
    public void aCommentWithoutItsSpaceIsStillAComment() {
        assertEquals("    y = 1\n", uncomment(PythonMode.getMode(), "    #y = 1\n"));
    }

    @Test
    public void aCommentEndBeforeTrailingSpaceIsFound() {
        assertEquals("<p>x</p>  \n", uncomment(HtmlMode.getMode(), "<!--<p>x</p>-->  \n"));
    }

    @Test
    public void commentReachesTheLastLine() {
        h = EditorHarness.create("a = 1\nb = 2").mode(PythonMode.getMode());
        h.editor().selectAll();
        h.editor().commentRegion();
        assertEquals("#a = 1\n#b = 2", h.value());
    }
}
