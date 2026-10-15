/*
 * IconsTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import org.junit.jupiter.api.Test;

/** Icons stay sharp in a window the JDK scales. */
public class IconsTest {
    // The icon painted into a window scaled by scale.
    private static int[] painted(ImageIcon icon, int scale) {
        final int pixels = icon.getIconWidth() * scale;
        final BufferedImage image = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g2d = image.createGraphics();
        try {
            g2d.scale(scale, scale);
            icon.paintIcon(null, g2d, 0, 0);
        }
        finally {
            g2d.dispose();
        }
        return image.getRGB(0, 0, pixels, pixels, null, 0, pixels);
    }

    @Test
    public void atTwiceTheSizeItIsDrawnAtTwiceTheSize() {
        final ImageIcon icon = Icons.getIconFromFile("attach", 16);
        assertEquals(16, icon.getIconWidth());
        assertArrayEquals(
            painted(Icons.getIconFromFile("attach", 32), 1),
            painted(icon, 2),
            "not a 16 pixel bitmap stretched");
    }

    @Test
    public void soIsTheGrayedOutIcon() {
        final ImageIcon disabled = Icons.getDisabledIcon(Icons.getIconFromFile("attach", 16));
        assertNotNull(disabled);
        assertSame(disabled, Icons.getDisabledIcon(Icons.getIconFromFile("attach", 16)), "made once");
        assertEquals(16, disabled.getIconWidth());
        assertArrayEquals(painted(Icons.getDisabledIcon(Icons.getIconFromFile("attach", 32)), 1), painted(disabled, 2));
    }

    @Test
    public void anIconInAColorIsCachedByItsColor() {
        final ImageIcon red = Icons.getIconFromFile("up", 16, Color.RED);
        assertSame(red, Icons.getIconFromFile("up", 16, new Color(255, 0, 0)), "made once");
        final int[] pixels = painted(red, 1);
        boolean drawn = false;
        for (int p : pixels)
            if ((p >>> 24) != 0)
                drawn |= (p & 0xffffff) == 0xff0000;
        assertTrue(drawn, "drawn in red, not the icons' own color");
        assertNotSame(red, Icons.getIconFromFile("up", 16, Color.BLUE));
        assertNotSame(red, Icons.getIconFromFile("up", 16));
    }
}
