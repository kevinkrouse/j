/*
 * BufferListStatusTest.java
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

package org.armedbear.j.vcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class BufferListStatusTest {
    private static String text(String code) {
        final BufferListStatus.Badge b = BufferListStatus.fromCode(code);
        return b == null ? null : b.text();
    }

    @Test
    public void theWorkingTreesChangeComesFirstThenTheIndexs() {
        assertEquals("M", text(" M"));
        assertEquals("M", text("MM"));
        assertEquals("M", text("M "));
        assertEquals("A", text("A "));
        assertEquals("D", text(" D"));
        assertEquals("R", text("R "));
        assertEquals("?", text("??"));
        assertEquals("U", text("UU"));
        assertEquals("U", text("AA"));
        assertNull(text("!!"), "ignored");
        assertNull(text("  "), "unchanged");
    }

    @Test
    public void aStagedChangeIsAnotherColorThanAnUnstagedOne() {
        assertNotEquals(BufferListStatus.fromCode(" M").color(), BufferListStatus.fromCode("M ").color());
    }
}
