/*
 * CFamilyFormatterTest.java
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

import org.junit.jupiter.api.Test;

/** What JavaFormatter's parseBuffer leaves in each line's flags, for Java and C. */
public class CFamilyFormatterTest implements Constants {
    private static int[] flags(int modeId, String text) {
        EditorHarness h = EditorHarness.create(text).mode(Editor.getModeList().getMode(modeId));
        try {
            Buffer buffer = h.buffer();
            buffer.getFormatter().parseBuffer();
            int[] flags = new int[buffer.getLineCount()];
            int i = 0;
            for (Line line = buffer.getFirstLine(); line != null; line = line.next())
                flags[i++] = line.flags();
            return flags;
        }
        finally {
            h.close();
        }
    }

    @Test
    public void anIfZeroBlockIsDisabledUpToItsElse() {
        int[] f = flags(C_MODE, "#if 0\nnot compiled\n#else\ncompiled\n#endif\n");
        assertEquals(STATE_DISABLED, f[0]);
        assertEquals(STATE_DISABLED, f[1]);
        assertEquals(STATE_NEUTRAL, f[2]);
        assertEquals(STATE_NEUTRAL, f[3]);
    }

    @Test
    public void anIfZeroBlockIncludesItsEndif() {
        int[] f = flags(C_MODE, "#if 0\nx\n#endif\ny\n");
        assertEquals(STATE_DISABLED, f[2]);
        assertEquals(STATE_NEUTRAL, f[3]);
    }

    @Test
    public void aCStringContinuesOnlyAfterABackslash() {
        assertEquals(STATE_QUOTE, flags(C_MODE, "s = \"abc\\\ndef\";\n")[1]);
        assertEquals(STATE_NEUTRAL, flags(C_MODE, "s = \"abc\nx;\n")[1]);
    }

    @Test
    public void aJavaStringEndsWithItsLine() {
        assertEquals(STATE_NEUTRAL, flags(JAVA_MODE, "s = \"abc\\\ndef\";\n")[1]);
        assertEquals(STATE_COMMENT, flags(JAVA_MODE, "/* a\nb */\n")[1]);
    }
}
