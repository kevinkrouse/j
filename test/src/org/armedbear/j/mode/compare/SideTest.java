/*
 * SideTest.java
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
package org.armedbear.j.mode.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

public class SideTest {
    @Test
    public void aUtf8ByteOrderMarkIsLeftOutOfTheTextAndKeptInTheBytes() {
        final byte[] b = { (byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'o', 'n', 'e', '\n', 't', 'w', 'o', '\n' };
        final Side s = Side.of(b, StandardCharsets.UTF_8);
        assertEquals(List.of("one", "two"), s.lines());
        assertEquals(6, s.raw().get(0).length(), "the mark and one, as a patch has them");
        assertFalse(s.noNewline());
    }

    @Test
    public void utf16IsSplitOnItsOwnNewlines() {
        for (boolean big : new boolean[] { true, false }) {
            final byte[] text = "aĊ\r\nb".getBytes(big ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE);
            final byte[] b = new byte[text.length + 2];
            b[0] = (byte) (big ? 0xfe : 0xff);
            b[1] = (byte) (big ? 0xff : 0xfe);
            System.arraycopy(text, 0, b, 2, text.length);
            final Side s = Side.of(b, StandardCharsets.UTF_8);
            assertEquals(List.of("aĊ", "b"), s.lines(), big ? "big-endian" : "little-endian");
            assertTrue(s.noNewline());
        }
    }
}
