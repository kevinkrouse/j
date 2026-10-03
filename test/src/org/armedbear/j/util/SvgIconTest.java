/*
 * SvgIconTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Draws every icon j ships.
 *
 * <p>SvgIcon understands a deliberately small part of SVG, so an icon that
 * strays outside it -- a gradient, a transform, a curve command -- would
 * otherwise show up as a blank space at runtime with nothing in the log. This
 * turns that into a build failure naming the file.
 *
 * <p>It also guards the parser itself. Both bugs found while it was being
 * written were invisible on any single icon: a tokeniser that read the "3.5.5"
 * in mail-reply-all as one number instead of two, and a stray transparent path
 * in stock_copy. Only drawing the whole set caught either.
 */
public class SvgIconTest {
    /** Where the icons live in the source tree, relative to the build. */
    private static final String ICON_DIR = "src/org/armedbear/j/images/svg";

    private static File iconDir() {
        File dir = new File(ICON_DIR);
        if (!dir.isDirectory())
            dir = new File("../" + ICON_DIR);
        return dir;
    }

    private static List<String> iconNames() {
        File dir = iconDir();
        assertTrue(dir.isDirectory(), "cannot find the icon directory: " + dir.getAbsolutePath());
        String[] files = dir.list();
        Arrays.sort(files);
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < files.length; i++) {
            if (files[i].endsWith(".svg"))
                names.add(files[i].substring(0, files[i].length() - 4));
        }
        assertFalse(names.isEmpty(), "no icons found in " + dir.getAbsolutePath());
        return names;
    }

    @Test
    public void everyIconParsesAndPaints() {
        List<String> failures = new ArrayList<String>();
        List<String> names = iconNames();
        // 16 is the authored size; 33 is deliberately odd and not a multiple of
        // it, so a scale that only works on round factors is caught too.
        final int[] sizes = { 16, 33, 64 };
        for (String name : names) {
            try {
                SvgIcon icon = new SvgIcon(
                    name,
                    new FileInputStream(new File(iconDir(), name + ".svg")),
                    new Color(0x4A4E57)
                );
                for (int i = 0; i < sizes.length; i++) {
                    int size = sizes[i];
                    BufferedImage image =
                        new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2d = image.createGraphics();
                    try {
                        icon.paint(g2d, size);
                    }
                    finally {
                        g2d.dispose();
                    }
                    if (!hasInk(image))
                        failures.add(name + " drew nothing at " + size + "px");
                }
            }
            catch (Throwable t) {
                failures.add(name + ": " + t);
            }
        }
        if (!failures.isEmpty())
            fail(
                failures.size() + " of " + names.size() + " icons failed:\n  "
                    + String.join("\n  ", failures)
            );
    }

    /** An icon that parses but paints nothing is as broken as one that throws. */
    private static boolean hasInk(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) != 0)
                    return true;
            }
        }
        return false;
    }

    @Test
    public void unknownIconIsReportedByName() {
        try {
            new SvgIcon("no-such-icon", Color.BLACK);
            fail("expected a missing icon to throw");
        }
        catch (Exception e) {
            assertTrue(
                e.getMessage() != null && e.getMessage().contains("no-such-icon"),
                "the error should name the icon, was: " + e.getMessage()
            );
        }
    }
}
