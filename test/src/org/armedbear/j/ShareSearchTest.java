/*
 * ShareSearchTest.java
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
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The shareSearch preference, for j's own findNext and findPrev. */
public class ShareSearchTest {
    private EditorHarness a;
    private EditorHarness b;

    @AfterEach
    public void tearDown() {
        Editor.preferences().removeProperty(Property.SHARE_SEARCH.key());
        if (a != null)
            a.close();
        if (b != null)
            b.close();
    }

    @Test
    public void everyWindowFindsTheLastPatternByDefault() {
        a = EditorHarness.create("one two\n");
        b = EditorHarness.create("two one\n");
        final Search search = new Search("one", false, true);
        a.editor().setLastSearch(search);
        assertSame(search, b.editor().getLastSearch());
        SearchCommands.findNext(b.editor());
        assertEquals(4, b.editor().getDotOffset());
    }

    @Test
    public void eachWindowKeepsItsOwnWhenNotShared() {
        Editor.preferences().setProperty(Property.SHARE_SEARCH, "false");
        a = EditorHarness.create("one two\n");
        b = EditorHarness.create("two one\n");
        a.editor().setLastSearch(new Search("one", false, true));
        assertNull(b.editor().getLastSearch());
        SearchCommands.findNext(b.editor());
        assertEquals(0, b.editor().getDotOffset());
    }
}
