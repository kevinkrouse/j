/*
 * JavaElectricKeyTest.java
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
 * The electric keys against modern Java: the colon handler must indent a
 * switch label but leave a ternary at body level, and the anonymous-class
 * indent (NEW_RE) must survive generics.
 */
public class JavaElectricKeyTest {
    private EditorHarness h;

    private EditorHarness on(String text) {
        h = EditorHarness.create(text).mode(JavaMode.getMode());
        h.buffer().getFormatter().parseBuffer();
        return h;
    }

    private Line lineAt(int line0) {
        Line l = h.buffer().getFirstLine();
        for (int i = 0; i < line0; i++)
            l = l.next();
        return l;
    }

    @AfterEach
    public void close() {
        if (h != null)
            h.close();
    }

    @Test
    public void colonIndentsASwitchLabel() {
        // Typing ':' at the end of "case 1" makes it a switch label, which
        // the mode indents one step under the switch.
        on("class C {\n"
                + "    void m(int i) {\n"
                + "        switch (i) {\n"
                + "        case 1\n"
                + "            foo();\n"
                + "        }\n"
                + "    }\n"
                + "}\n")
            .cursor(3, 14);
        ElectricCommands.electricColon(h.editor());
        h.buffer().getFormatter().parseBuffer();
        assertEquals("            case 1:", lineAt(3).getText());
    }

    @Test
    public void colonLeavesATernaryAtBodyLevel() {
        // The colon handler must not mistake the ternary's ':' for a switch
        // label: the line stays at the body indent, not the label indent.
        on("class C {\n"
                + "    void m() {\n"
                + "        int x = flag ? a\n"
                + "        return x;\n"
                + "    }\n"
                + "}\n")
            .cursor(2, 24);
        ElectricCommands.electricColon(h.editor());
        h.buffer().getFormatter().parseBuffer();
        assertEquals(8, h.buffer().getIndentation(lineAt(2)));
    }

    @Test
    public void colonLeavesALabeledStatementAtBodyLevel() {
        // A label is not a switch label either.
        on("class C {\n"
                + "    void m() {\n"
                + "        loop1\n"
                + "        for (int i = 0; i < 3; i++) ;\n"
                + "    }\n"
                + "}\n")
            .cursor(2, 13);
        ElectricCommands.electricColon(h.editor());
        h.buffer().getFormatter().parseBuffer();
        assertEquals(8, h.buffer().getIndentation(lineAt(2)));
    }

    @Test
    public void anonymousClassWithGenericsIndentsBody() {
        // NEW_RE must still see the "new" through the generic type
        // arguments, so the body indents one step under the statement.
        on("class C {\n"
                + "    Runnable r = new ArrayList<String>() {\n"
                + "        public void run() {}\n"
                + "    };\n"
                + "}\n");
        assertEquals(8, h.buffer().getMode().getCorrectIndentation(lineAt(2), h.buffer()));
    }

    @Test
    public void anonymousClassWithNestedGenericsIndentsBody() {
        on("class C {\n"
                + "    Map<String, List<Integer>> m = new HashMap<String, List<Integer>>() {\n"
                + "        public int size() { return 0; }\n"
                + "    };\n"
                + "}\n");
        assertEquals(8, h.buffer().getMode().getCorrectIndentation(lineAt(2), h.buffer()));
    }

    @Test
    public void electricStarLinesUpJavadoc() {
        // Unchanged by the modern-Java work: a blank line in a Javadoc
        // block gets its '*' under the previous line's '*'.
        on("class C {\n"
                + "    /**\n"
                + "     * existing\n"
                + "\n"
                + "     */\n"
                + "    void m() {}\n"
                + "}\n")
            .cursor(3, 0);
        ElectricCommands.electricStar(h.editor());
        h.buffer().getFormatter().parseBuffer();
        assertEquals("     *", lineAt(3).getText());
    }
}
