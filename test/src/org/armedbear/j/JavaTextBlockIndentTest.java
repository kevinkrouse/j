/*
 * JavaTextBlockIndentTest.java
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

import org.armedbear.j.mode.java.JavaMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A text block's lines are string content: a new line in one keeps the
 * block's indent, since a line at column 0 would change the string's value.
 */
public class JavaTextBlockIndentTest {
    private EditorHarness h;

    private EditorHarness on(String text) {
        h = EditorHarness.create(text).mode(JavaMode.getMode());
        h.buffer().getFormatter().parseBuffer();
        return h;
    }

    @AfterEach
    public void close() {
        if (h != null)
            h.close();
    }

    @Test
    public void enterKeepsTheBlocksIndent() {
        on(
            "class C {\n" + "    void m() {\n" + "        String s = \"\"\"\n" + "            {\n"
                    + "            \"\"\";\n" + "    }\n" + "}\n")
                .cursor(3, 13);
        EditCommands.newlineAndIndent(h.editor());
        h.assertCursorAt(4, 12);
    }

    @Test
    public void anElectricBraceOnABlankLineKeepsItsIndent() {
        on(
            "class C {\n" + "    void m() {\n" + "        String s = \"\"\"\n" + "            a\n" + "            \n"
                    + "            \"\"\";\n" + "    }\n" + "}\n")
                .cursor(4, 12);
        ElectricCommands.electricCloseBrace(h.editor());
        assertEquals("            }", h.buffer().getFirstLine().next().next().next().next().getText());
    }

    @Test
    public void aContentLineKeepsItsOwnIndent() {
        on("class C {\n" + "    String s = \"\"\"\n" + "  shallow\n" + "        deep\n" + "        \"\"\";\n" + "}\n");
        Line shallow = h.buffer().getFirstLine().next().next();
        assertEquals(2, h.buffer().getMode().getCorrectIndentation(shallow, h.buffer()));
        assertEquals(8, h.buffer().getMode().getCorrectIndentation(shallow.next(), h.buffer()));
    }
}
