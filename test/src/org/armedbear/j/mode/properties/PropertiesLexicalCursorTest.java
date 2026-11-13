/*
 * PropertiesLexicalCursorTest.java
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

package org.armedbear.j.mode.properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.junit.jupiter.api.Test;

/**
 * The Properties lexical queries scan the line once. The guard measures
 * the formatter's accumulated scan work through the real
 * {@code mode.lexicalContexts} and {@code mode.lexicalContext} entry
 * points, not a hand-built cursor: a bulk query that secretly looped
 * point queries would accumulate O(len^2) steps and blow the linear
 * bound, which a single cursor run cannot show.
 */
public class PropertiesLexicalCursorTest {
    private static final String TOKENS = "key = value with a long tail of text here";

    private PropertiesFormatter formatter(EditorHarness h) {
        return (PropertiesFormatter) h.buffer().getFormatter();
    }

    @Test
    public void bulkWalkIsLinear() {
        EditorHarness h = EditorHarness.create(TOKENS).mode(PropertiesMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            int len = line.length();
            PropertiesFormatter f = formatter(h);
            f.scanSteps = 0;
            h.buffer().getMode().lexicalContexts(h.buffer(), line, len);
            assertTrue(f.scanSteps <= len,
                    "bulk scanSteps " + f.scanSteps + " exceeds len " + len);
            assertTrue(f.scanSteps > 0, "bulk made no progress");
        }
        finally {
            h.close();
        }
    }

    @Test
    public void pointQueryStopsAtBoundary() {
        EditorHarness h = EditorHarness.create(TOKENS).mode(PropertiesMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            int boundary = TOKENS.indexOf("value");
            PropertiesFormatter f = formatter(h);
            f.scanSteps = 0;
            h.buffer().getMode().lexicalContext(h.buffer(), new Position(line, boundary));
            assertTrue(f.scanSteps <= boundary,
                    "point scanSteps " + f.scanSteps + " exceeds boundary " + boundary);
        }
        finally {
            h.close();
        }
    }
}
