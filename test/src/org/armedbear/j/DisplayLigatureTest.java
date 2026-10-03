/*
 * DisplayLigatureTest.java
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

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.util.Utilities;
import org.junit.jupiter.api.Test;

/**
 * Display shapes buffer text with Font.layoutGlyphVector when ligatures are
 * on, but goes on deriving every column position from Display.charWidth. That
 * only works because a monospaced ligature font renders a ligature as per-cell
 * glyph pieces, leaving advances untouched -- which is what
 * measuresTheSameShapedOrNot() below pins down. If a JDK or a font ever breaks
 * that, the caret drifts away from the text silently, and this is the only
 * thing that would say so.
 *
 * <p>Which of these fonts is installed varies by machine, so every test that
 * needs one runs against all of them that are present and is skipped when none
 * is. Only monospacedHasNoLigatures() is guaranteed to run everywhere.
 */
public class DisplayLigatureTest {
    /**
     * Families whose whole point is programming ligatures, under the names
     * Java reports -- which are not the names the font files advertise. A Nerd
     * Font build calls itself "CaskaydiaCove Nerd Font Mono" but reaches Java
     * as "CaskaydiaCove NFM".
     */
    private static final String[] LIGATURE_FAMILIES = {
        "CaskaydiaCove NFM",
        "Cascadia Code",
        "FiraCode Nerd Font Mono",
        "Fira Code",
        "JetBrainsMono NFM",
        "JetBrains Mono",
        "Hasklig",
        "Iosevka",
        "Monoid",
        "Victor Mono",
    };

    /**
     * Monospaced faces that deliberately ship without ligatures -- "NL" is
     * JetBrains Mono's No Ligatures build, and Cascadia Mono is Cascadia Code
     * with the feature removed. They guard the other direction: auto-detection
     * must not turn shaping on for a font that has nothing to shape.
     */
    private static final String[] PLAIN_FAMILIES = {
        "Cascadia Mono",
        "JetBrainsMonoNL NFM",
        "DejaVu Sans Mono",
        "Liberation Mono",
    };

    private static final int SIZE = 14;

    private static final String OPERATORS =
        "if (a->b != c) { d === e; } // <=> |> ...";

    /** A family resolves to itself only when it is really installed. */
    private static List<Font> installed(String[] families) {
        List<Font> fonts = new ArrayList<Font>();
        for (String family : families) {
            Font font = new Font(family, Font.PLAIN, SIZE);
            if (family.equals(font.getFamily()))
                fonts.add(font);
        }
        return fonts;
    }

    private static List<Font> ligatureFonts() {
        List<Font> fonts = installed(LIGATURE_FAMILIES);
        assumeTrue(
            !fonts.isEmpty(),
            "none of " + LIGATURE_FAMILIES.length + " ligature fonts installed"
        );
        return fonts;
    }

    private static GlyphVector shape(
        Font font,
        char[] chars,
        int start,
        int limit
    ) {
        FontRenderContext frc =
            Utilities.getFontMetrics(font).getFontRenderContext();
        return font.layoutGlyphVector(
            frc,
            chars,
            start,
            limit,
            Font.LAYOUT_LEFT_TO_RIGHT
        );
    }

    @Test
    public void monospacedHasNoLigatures() {
        // The default fontName, and the one case that has to hold on any JDK
        // with any fonts installed.
        assertFalse(
            Display.fontHasLigatures(
                new Font("Monospaced", Font.PLAIN, SIZE)
            )
        );
    }

    @Test
    public void plainMonospacedFacesAreLeftAlone() {
        for (Font font : installed(PLAIN_FAMILIES))
            assertFalse(Display.fontHasLigatures(font), font.getFamily() + " was detected as a ligature font");
    }

    @Test
    public void detectsProgrammingFonts() {
        for (Font font : ligatureFonts())
            assertTrue(Display.fontHasLigatures(font), font.getFamily() + " was not detected as a ligature font");
    }

    @Test
    public void shapesAnArrow() {
        char[] chars = "->".toCharArray();
        for (Font font : ligatureFonts()) {
            GlyphVector arrow = shape(font, chars, 0, 2);
            assertEquals(
                2,
                arrow.getNumGlyphs(),
                font.getFamily() + " changed the glyph count"
            );
            // Substitution is confined to [start, limit), so the same
            // characters shaped one at a time stay a hyphen and a greater-than
            // sign. drawText depends on this to break a ligature under the
            // caret.
            assertTrue(
                shape(font, chars, 0, 1).getGlyphCode(0) != arrow.getGlyphCode(0)
                    || shape(font, chars, 1, 2).getGlyphCode(0) != arrow.getGlyphCode(1),
                font.getFamily() + " did not ligate ->"
            );
        }
    }

    @Test
    public void measuresTheSameShapedOrNot() {
        char[] chars = OPERATORS.toCharArray();
        for (Font font : ligatureFonts()) {
            String family = font.getFamily();
            double cell = shape(font, new char[] { 'a' }, 0, 1)
                .getLogicalBounds()
                .getWidth();
            assertTrue(cell == Math.floor(cell), family + " has a fractional cell width: " + cell);
            for (int n = 1; n <= chars.length; n++) {
                double width = shape(font, chars, 0, n)
                    .getLogicalBounds()
                    .getWidth();
                assertEquals(
                    cell * n,
                    width,
                    0.0,
                    family + ": shaped width of the first " + n +
                        " columns of \"" + OPERATORS + "\""
                );
            }
        }
    }
}
