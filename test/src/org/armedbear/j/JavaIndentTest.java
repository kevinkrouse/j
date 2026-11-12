/*
 * JavaIndentTest.java
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
 * Indentation for the modern-Java shapes: a switch expression's cases, a
 * wrapped fluent chain, and the statement switch that must not regress.
 */
public class JavaIndentTest {
    private EditorHarness h;

    private EditorHarness on(String text) {
        h = EditorHarness.create(text).mode(JavaMode.getMode());
        h.buffer().getFormatter().parseBuffer();
        return h;
    }

    private int want(int lineNo) {
        Line l = h.buffer().getFirstLine();
        for (int i = 1; i < lineNo; i++)
            l = l.next();
        return h.buffer().getMode().getCorrectIndentation(l, h.buffer());
    }

    @AfterEach
    public void close() {
        if (h != null)
            h.close();
    }

    @Test
    public void switchExpressionCasesIndentOneStep() {
        // No preceding statement switch: findSwitch must recognize the
        // mid-line "switch" of the expression, not bail to 0.
        on("class C {\n"
                + "    int m(int i) {\n"
                + "        int x = switch (i) {\n"
                + "            case 1 -> 10;\n"
                + "            default -> 0;\n"
                + "        };\n"
                + "        return x;\n"
                + "    }\n"
                + "}\n");
        assertEquals(12, want(4));
        assertEquals(12, want(5));
    }

    @Test
    public void switchExpressionBlockCaseIndents() {
        on("class C {\n"
                + "    int m(int i) {\n"
                + "        int x = switch (i) {\n"
                + "            case 2 -> {\n"
                + "                yield 20;\n"
                + "            }\n"
                + "            default -> 0;\n"
                + "        };\n"
                + "        return x;\n"
                + "    }\n"
                + "}\n");
        assertEquals(12, want(4));
        assertEquals(16, want(5));
        assertEquals(12, want(6));
        assertEquals(12, want(7));
    }

    @Test
    public void statementSwitchStillIndents() {
        // Regression: the arrow-less statement form is unchanged.
        on("class C {\n"
                + "    void m(int i) {\n"
                + "        switch (i) {\n"
                + "        case 1:\n"
                + "            foo();\n"
                + "            break;\n"
                + "        default:\n"
                + "            bar();\n"
                + "        }\n"
                + "    }\n"
                + "}\n");
        assertEquals(12, want(4));
        assertEquals(12, want(7));
    }

    @Test
    public void dotChainOffByDefault() {
        on("class C {\n"
                + "    void m() {\n"
                + "        List<Integer> r = list.stream()\n"
                + "                .filter(x -> x > 0)\n"
                + "                .toList();\n"
                + "    }\n"
                + "}\n");
        assertEquals(8, want(4));
        assertEquals(8, want(5));
    }

    @Test
    public void dotChainOnIndentsOneStep() {
        on("class C {\n"
                + "    void m() {\n"
                + "        List<Integer> r = list.stream()\n"
                + "                .filter(x -> x > 0)\n"
                + "                .toList();\n"
                + "    }\n"
                + "}\n");
        h.buffer().setProperty(Property.INDENT_DOT_CHAIN, true);
        assertEquals(12, want(4));
        assertEquals(12, want(5));
    }

    @Test
    public void leadingDotNumberIsNotAChain() {
        // ".5" is a double literal, not a method call: with the chain
        // property on it must keep the ordinary continuation indent, not
        // the chain step.
        on("class C {\n"
                + "    void m() {\n"
                + "        f(a,\n"
                + "          .5,\n"
                + "          b);\n"
                + "    }\n"
                + "}\n");
        h.buffer().setProperty(Property.INDENT_DOT_CHAIN, true);
        assertEquals(10, want(4));
    }
}
