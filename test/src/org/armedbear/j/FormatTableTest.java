/*
 * FormatTableTest.java
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

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * The colors and styles a FormatTable finds for a thing: its own
 * preferences, the names it links to, its fallbacks, and DefaultTheme's
 * shared styles for a light background or a dark.
 */
public class FormatTableTest
{
    private static final String MODE = "FormatTableTestMode";

    private final List<String> keys = new ArrayList<String>();

    private void set(String key, String value)
    {
        keys.add(key);
        Editor.preferences().setProperty(key, value);
    }

    @After
    public void tearDown()
    {
        for (String key : keys)
            Editor.preferences().removeProperty(key);
    }

    private FormatTableEntry entry(String thing, String... fallbacks)
    {
        FormatTable table = new FormatTable(MODE);
        table.addEntryFromPrefs(0, thing, fallbacks);
        return table.lookup(0);
    }

    private static Color rgb(int rgb)
    {
        return new Color(rgb);
    }

    @Test
    public void parsesNumbersAndWords()
    {
        assertEquals(TextStyle.BOLD, TextStyle.parse("1"));
        assertEquals(TextStyle.BOLD | TextStyle.ITALIC, TextStyle.parse("3"));
        assertEquals(TextStyle.BOLD | TextStyle.ITALIC,
                     TextStyle.parse("bold italic"));
        assertEquals(TextStyle.UNDERLINE | TextStyle.STRIKETHROUGH,
                     TextStyle.parse(" underline, strikethrough "));
        assertEquals(TextStyle.PLAIN, TextStyle.parse("plain"));
        assertEquals(-1, TextStyle.parse("loud"));
        assertEquals(-1, TextStyle.parse("16"));
        assertEquals(-1, TextStyle.parse(null));
    }

    @Test
    public void sharedStyleSuitsALightBackground()
    {
        set("color.background", "255 255 255");
        FormatTableEntry e = entry("heading");
        assertEquals(rgb(0x0550ae), e.getColor());
        assertEquals(TextStyle.BOLD | TextStyle.ITALIC, e.getStyle());
    }

    @Test
    public void sharedStyleSuitsADarkBackground()
    {
        set("color.background", "0 0 0");
        assertEquals(rgb(0x58a6ff), entry("heading").getColor());
    }

    @Test
    public void theModesOwnBackgroundDecides()
    {
        set("color.background", "255 255 255");
        set(MODE + ".color.background", "30 30 30");
        assertEquals(rgb(0x58a6ff), entry("heading").getColor());
    }

    @Test
    public void aLinkedThingTakesWhatTheThemeGaveItsLink()
    {
        set("link.heading1", "heading");
        set("color.heading", "1 2 3");
        FormatTableEntry e = entry("heading1");
        assertEquals(new Color(1, 2, 3), e.getColor());
        // The theme said nothing of its style, so DefaultTheme's for heading.
        assertEquals(TextStyle.BOLD | TextStyle.ITALIC, e.getStyle());
    }

    @Test
    public void aThingsOwnPreferencesBeatItsLink()
    {
        set("link.heading1", "heading");
        set("color.heading", "1 2 3");
        set(MODE + ".color.heading1", "4 5 6");
        set(MODE + ".style.heading1", "underline");
        FormatTableEntry e = entry("heading1");
        assertEquals(new Color(4, 5, 6), e.getColor());
        assertEquals(TextStyle.UNDERLINE, e.getStyle());
    }

    @Test
    public void aModesLinkBeatsTheGlobalOne()
    {
        set("link.thing", "heading");
        set(MODE + ".link.thing", "code");
        set("color.background", "255 255 255");
        assertEquals(rgb(0x953800), entry("thing").getColor());
    }

    @Test
    public void defaultLinksFollowTheThemesText()
    {
        set("color.text", "7 8 9");
        FormatTableEntry e = entry("emphasis");
        assertEquals(new Color(7, 8, 9), e.getColor());
        assertEquals(TextStyle.ITALIC, e.getStyle());
    }

    @Test
    public void linksChain()
    {
        set("color.background", "255 255 255");
        // url links to muted by default.
        set("link.footnote", "url");
        assertEquals(rgb(0x6e7781), entry("footnote").getColor());
    }

    @Test
    public void aCycleOfLinksEnds()
    {
        set("link.a", "b");
        set("link.b", "a");
        set("color.text", "7 8 9");
        FormatTableEntry e = entry("a");
        assertEquals(new Color(7, 8, 9), e.getColor());
        assertEquals(TextStyle.PLAIN, e.getStyle());
    }

    @Test
    public void aFallbacksPreferencesBeatDefaultTheme()
    {
        // As before links: "todo" has a DefaultTheme color, but the
        // fallback's preference is asked first.
        set("color.operator", "1 1 1");
        assertEquals(new Color(1, 1, 1), entry("todo", "operator").getColor());
    }

    @Test
    public void wordsAndBitsInTheme()
    {
        set("style.cancelled", "strikethrough italic");
        assertEquals(TextStyle.STRIKETHROUGH | TextStyle.ITALIC,
                     entry("cancelled").getStyle());
        set("style.keyword", "3");
        assertEquals(TextStyle.BOLD | TextStyle.ITALIC,
                     entry("keyword").getStyle());
    }

    @Test
    public void killThemeForgetsLinks()
    {
        set("link.heading1", "heading");
        set(MODE + ".link.heading1", "heading");
        Editor.preferences().killTheme();
        assertNull(Editor.preferences().getStringProperty("link.heading1"));
        assertNull(Editor.preferences()
                   .getStringProperty(MODE + ".link.heading1"));
    }
}
