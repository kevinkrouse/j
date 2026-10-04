/*
 * ThemeColorsTest.java
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import org.armedbear.j.mode.java.JavaMode;
import org.armedbear.j.mode.properties.PropertiesMode;
import org.armedbear.j.util.Colors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Colors as preferences write them, the swatches a properties file shows in
 * the gutter beside them, and listStyles.
 */
public class ThemeColorsTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        Editor.preferences().removeProperty("color.heading");
        if (h != null)
            h.close();
    }

    @Test
    public void parsesHex() {
        assertEquals(new Color(0xff, 0x88, 0x00), Colors.parseColor("#f80"));
        assertEquals(new Color(0x05, 0x50, 0xae), Colors.parseColor("#0550AE"));
        assertEquals(new Color(0x05, 0x50, 0xae), Colors.parseColor(" #0550ae "));
        assertNull(Colors.parseColor("#0550a"));
        assertNull(Colors.parseColor("#05g0ae"));
        assertNull(Colors.parseColor("#"));
    }

    @Test
    public void parsesNumbersAndNames() {
        assertEquals(new Color(1, 2, 3), Colors.parseColor("1 2 3"));
        assertEquals(Color.red, Colors.parseColor("red"));
        assertNull(Colors.parseColor("1 2"));
        assertNull(Colors.parseColor("1 2 300"));
        assertNull(Colors.parseColor("one two three"));
        assertNull(Colors.parseColor(null));
    }

    @Test
    public void aHexPreferenceIsAColor() {
        Editor.preferences().setProperty("color.heading", "#123");
        assertEquals(
            new Color(0x11, 0x22, 0x33),
            Editor.preferences().getColorProperty("color.heading")
        );
    }

    private Line line(int lineNumber) {
        Line line = h.buffer().getFirstLine();
        for (int i = 0; i < lineNumber; i++)
            line = line.next();
        return line;
    }

    private Color gutter(int lineNumber) {
        return h.buffer().getFormatter().getGutterColor(line(lineNumber));
    }

    @Test
    public void aPropertiesLineThatSetsAColorShowsIt() {
        h = EditorHarness.create(
            "# color.text = 1 2 3\n" +
                "color.text = 0 255 0\n" +
                "JavaMode.color.comment = #808080\n" +
                "XmlMode.color.tag: #f00\n" +
                "color.text = not a color\n" +
                "fontSize = 12\n" +
                "style.keyword = 1\n"
        ).mode(PropertiesMode.getMode());
        assertNull(gutter(0));
        assertEquals(new Color(0, 255, 0), gutter(1));
        assertEquals(new Color(0x80, 0x80, 0x80), gutter(2));
        assertEquals(new Color(0xff, 0, 0), gutter(3));
        assertNull(gutter(4));
        assertNull(gutter(5));
        assertNull(gutter(6));
    }

    @Test
    public void otherModesShowNoSwatches() {
        h = EditorHarness.create("Color c = 0 255 0;\n").mode(JavaMode.getMode());
        assertNull(gutter(0));
    }

    @Test
    public void entriesSayWhereTheyCameFrom() {
        Editor.preferences().setProperty("color.heading", "#010203");
        FormatTable table = new FormatTable("ThemeColorsTestMode");
        table.addEntryFromPrefs(0, "heading");
        FormatTableEntry e = table.lookup(0);
        assertEquals("color.heading", e.getColorSource());
        assertEquals("default heading", e.getStyleSource());
        assertEquals("heading", e.getNames().get(0));
    }

    @Test
    public void listStylesDrawsEachInItself() {
        Editor.preferences().setProperty("color.heading", "#010203");
        h = EditorHarness.create("class A {}\n").mode(JavaMode.getMode());
        Buffer buf = ListStyles.makeBuffer(h.buffer(), false);
        String text = buf.getText();
        assertTrue(text.contains("Shared styles"), text);
        assertTrue(text.contains("JavaMode"), text);
        Line heading = null;
        for (Line l = buf.getFirstLine(); l != null; l = l.next())
            if (l.getText().startsWith("  heading "))
                heading = l;
        // name, color, style, links to (none), color from, style from.
        assertTrue(
            heading.getText()
                .matches(
                    "  heading +#010203  bold italic +color\\.heading  default"
                ),
            text
        );
        assertTrue(text.contains("Shared styles (light background)"), text);
        Line emphasis = null;
        for (Line l = buf.getFirstLine(); l != null; l = l.next())
            if (l.getText().startsWith("  emphasis "))
                emphasis = l;
        assertTrue(
            emphasis.getText()
                .matches(
                    "  emphasis +#[0-9a-f]{6}  italic +\u2192 text +.*default"
                ),
            text
        );
        Formatter f = buf.getFormatter();
        assertEquals(new Color(1, 2, 3), f.getGutterColor(heading));
        LineSegmentList segments = f.formatLine(heading);
        assertEquals("heading", segments.getSegment(1).getText());
        int nameFormat = segments.getSegment(1).getFormat();
        assertEquals(new Color(1, 2, 3), f.getColor(nameFormat));
        assertEquals(TextStyle.BOLD | TextStyle.ITALIC, f.getStyle(nameFormat));
    }

    private static Mode modeOf(java.nio.file.Path path) {
        return new Buffer(File.getInstance(path.toString())).getDefaultMode();
    }

    @Test
    public void aThemeWithoutAModeLineIsAPropertiesFile() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("j-themes");
        java.nio.file.Path themes = java.nio.file.Files.createDirectory(
            tmp.resolve("themes")
        );
        java.nio.file.Path other = java.nio.file.Files.createDirectory(
            tmp.resolve("other")
        );
        try {
            java.nio.file.Path theme = themes.resolve("Bright");
            java.nio.file.Files.write(theme, "# Bright\ncolor.text = 0 0 0\n".getBytes());
            assertEquals(PropertiesMode.getMode(), modeOf(theme));

            java.nio.file.Path notes = themes.resolve("notes.txt");
            java.nio.file.Files.write(notes, "color.text = 0 0 0\n".getBytes());
            assertTrue(modeOf(notes) != PropertiesMode.getMode());

            java.nio.file.Path elsewhere = other.resolve("Bright");
            java.nio.file.Files.write(elsewhere, "color.text = 0 0 0\n".getBytes());
            assertTrue(modeOf(elsewhere) != PropertiesMode.getMode());

            // A directory on themePath is as good as one named themes.
            Editor.preferences().setProperty(Property.THEME_PATH, other.toString());
            assertEquals(PropertiesMode.getMode(), modeOf(elsewhere));
        }
        finally {
            Editor.preferences().removeProperty(Property.THEME_PATH.key());
            try (java.util.stream.Stream<java.nio.file.Path> walk =
                java.nio.file.Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    public void listStylesHasTheBuiltInsFirst() {
        Editor.preferences().setProperty("color.heading", "#010203");
        h = EditorHarness.create("class A {}\n").mode(JavaMode.getMode());
        Buffer buf = ListStyles.makeBuffer(h.buffer(), false);
        String text = buf.getText();
        int builtIn = text.indexOf("Built-in styles");
        int shared = text.indexOf("Shared styles");
        assertTrue(builtIn >= 0 && builtIn < shared, text);
        Line background = null;
        for (Line l = buf.getFirstLine(); l != null; l = l.next())
            if (l.getText().startsWith("  background "))
                background = l;
        assertTrue(background != null, text);
        Formatter f = buf.getFormatter();
        // Its swatch shows the color; its name is in the text's.
        assertEquals(
            Colors.parseColor(
                background.getText().trim().split(" +")[1]
            ),
            f.getGutterColor(background)
        );
        assertEquals(
            f.getColor(0),
            f.getColor(f.formatLine(background).getSegment(1).getFormat())
        );
    }
}
