/*
 * SchemeLexicalCursorTest.java
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

package org.armedbear.j.mode.scheme;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.armedbear.j.Editor;
import org.armedbear.j.EditorHarness;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Scheme lexical queries scan the line once. The guard measures the
 * formatter's accumulated scan work through the real
 * {@code mode.lexicalContexts}/{@code lexicalContext} entry points: a
 * bulk query secretly looping point queries would accumulate O(len^2)
 * and blow the linear bound. The fixture carries a block comment, a
 * string and a line comment to exercise marker and escape paths.
 */
public class SchemeLexicalCursorTest {
    private static final String TOKENS = "(define x \"a b\") #| blk |# ; tail";

    @BeforeAll
    public static void registerModes() {
        Editor.getModeList().register(new SchemeModes());
    }

    private SchemeFormatter formatter(EditorHarness h) {
        return (SchemeFormatter) h.buffer().getFormatter();
    }

    @Test
    public void bulkWalkIsLinear() {
        EditorHarness h = EditorHarness.create(TOKENS).mode(SchemeMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            int len = line.length();
            SchemeFormatter f = formatter(h);
            f.scanSteps = 0;
            h.buffer().getMode().lexicalContexts(h.buffer(), line, len);
            // One pass, at most one step per char; looping point queries
            // would sum 1+2+...+len.
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
        EditorHarness h = EditorHarness.create(TOKENS).mode(SchemeMode.getMode());
        try {
            Line line = h.buffer().getFirstLine();
            int boundary = TOKENS.indexOf("#|");
            SchemeFormatter f = formatter(h);
            f.scanSteps = 0;
            h.buffer().getMode().lexicalContext(h.buffer(), new Position(line, boundary));
            // The walk stops at the boundary; a full-line walk would exceed it.
            assertTrue(f.scanSteps <= boundary,
                    "point scanSteps " + f.scanSteps + " exceeds boundary " + boundary);
        }
        finally {
            h.close();
        }
    }
}
