/*
 * SidebarPanelsTest.java
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

import static org.armedbear.j.Sidebar.BUFFERS;
import static org.armedbear.j.Sidebar.CHANGES;
import static org.armedbear.j.Sidebar.NONE;
import static org.armedbear.j.Sidebar.TREE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.armedbear.j.Sidebar.Panels;
import org.junit.jupiter.api.Test;

public class SidebarPanelsTest {
    @Test
    public void buffersAndChangesShareTheUpperHalfAndTheTreeIsApart() {
        final Panels both = new Panels(BUFFERS, true);
        assertEquals(new Panels(CHANGES, true), both.toggle(CHANGES), "Changes in Buffers' place");
        assertEquals(new Panels(NONE, true), both.toggle(BUFFERS), "Buffers hidden");
        assertEquals(new Panels(BUFFERS, false), both.toggle(TREE));
        assertEquals(new Panels(BUFFERS, true), new Panels(NONE, true).toggle(BUFFERS));
    }

    @Test
    public void theLastHiddenLeavesNoneAndShowingOneBringsTheRestBack() {
        final Panels changes = new Panels(CHANGES, false);
        assertTrue(changes.toggle(CHANGES).isEmpty(), "the sidebar hides, its panels kept");
        assertFalse(changes.isEmpty());
        // Shown again for a panel: it, with the other half as it was.
        assertEquals(new Panels(BUFFERS, false), changes.with(BUFFERS));
        assertEquals(new Panels(CHANGES, true), changes.with(TREE));
    }

    @Test
    public void theUpperHalfsPanelIsKeptByName() {
        for (int top : List.of(BUFFERS, CHANGES, NONE))
            assertEquals(top, Sidebar.topOf(Sidebar.topName(top)));
        assertEquals(BUFFERS, Sidebar.topOf(null), "Buffers to begin with");
        assertEquals(BUFFERS, Sidebar.topOf("junk"));
    }

    @Test
    public void theStatusBarsButtonsAreTheSidebarThenItsPanels() {
        assertEquals(
            List.of("toggleSidebar", "sidebarToggleBuffers", "sidebarToggleChanges", "sidebarToggleTree"),
            StatusBar.BUTTONS.stream().map(StatusBar.Button::command).toList());
        for (StatusBar.Button b : StatusBar.BUTTONS)
            assertTrue(CommandTable.getCommand(b.command()) != null, b.command() + " is a command");
    }
}
