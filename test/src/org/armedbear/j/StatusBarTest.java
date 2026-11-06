/*
 * StatusBarTest.java
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

import org.junit.jupiter.api.Test;

public class StatusBarTest {
    @Test
    public void theLinkIsTheChangelistAfterTheMiddleDotNotAModeOfItsName() {
        final String text = "Java   git  M · Java   Line 5  Col 1";
        assertEquals(text.indexOf("· Java") + 2, StatusBar.linkStart(text, "Java"));
        assertEquals(-1, StatusBar.linkStart("Java   git  M   Line 5", "Java"), "no list: no link");
    }
}
