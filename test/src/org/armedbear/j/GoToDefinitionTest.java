/*
 * GoToDefinitionTest.java
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.markdown.MarkdownMode;
import org.armedbear.j.mode.text.PlainTextMode;
import org.junit.After;
import org.junit.Test;

/**
 * Ctrl+click on an identifier, followLink: to where the tags say it is
 * defined, as IntelliJ goes to a declaration.
 */
public class GoToDefinitionTest {
    private EditorHarness h;

    @After
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private EditorHarness on(String text, Mode mode) {
        h = EditorHarness.create(text).mode(mode);
        Editor.setCurrentEditor(h.editor());
        return h;
    }

    private TextLink linkAt(int line, int offset) {
        h.cursor(line, offset);
        return h.buffer().getMode().getLinkAt(h.editor(), h.editor().getDot());
    }

    private static final String JAVA =
        "class A {\n"
            + "    void foo() {}\n"
            + "    void f(int a) {}\n"
            + "    void f(int a, int b) {}\n"
            + "    void bar() { foo(); f(1, 2); missing(); }\n"
            + "}\n";

    @Test
    public void aCallIsALinkToItsDefinition() {
        on(JAVA, JavaMode.getMode());
        final TextLink foo = linkAt(4, 19);
        assertTrue(foo.isDefinition());
        assertEquals("foo", foo.getTarget());
        assertEquals(17, foo.getBegin());
        assertEquals(20, foo.getEnd());
    }

    @Test
    public void notADeclarationKeywordOrUnknownName() {
        on(JAVA, JavaMode.getMode());
        assertNull(linkAt(1, 10)); // foo's own declaration
        assertNull(linkAt(4, 5)); // void
        assertNull(linkAt(4, 36)); // missing
        assertNull(linkAt(4, 3)); // white space
    }

    @Test
    public void followingGoesThere() {
        on(JAVA, JavaMode.getMode()).cursor(4, 18);
        FollowLink.followLink();
        assertEquals(1, h.editor().getDotLineNumber());
    }

    @Test
    public void anOverloadByItsArguments() {
        on(JAVA, JavaMode.getMode()).cursor(4, 24);
        FollowLink.followLink();
        assertEquals(3, h.editor().getDotLineNumber());
    }

    @Test
    public void jumpBackReturns() {
        on(JAVA, JavaMode.getMode()).cursor(4, 18);
        FollowLink.followLink();
        JumpList.jumpBack();
        assertEquals(4, h.editor().getDotLineNumber());
    }

    @Test
    public void wordsInProseAreNotDefinitions() {
        on("# foo\nfoo bar\n", MarkdownMode.getMode());
        h.buffer().getFormatter().parseBuffer();
        assertNull(linkAt(1, 1));
        on("foo bar\n", PlainTextMode.getMode());
        assertNull(linkAt(0, 1));
    }

    @Test
    public void aUrlStillWins() {
        on("// see https://example.com/foo\nclass foo {}\n", JavaMode.getMode());
        assertEquals("https://example.com/foo", linkAt(0, 10).getTarget());
    }
}
