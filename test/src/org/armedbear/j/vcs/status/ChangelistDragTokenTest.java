/*
 * ChangelistDragTokenTest.java
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

package org.armedbear.j.vcs.status;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import org.junit.jupiter.api.Test;

/**
 * The changelist drag carries its token in a flavor of its own, never as
 * plain text: a changelist drag dropped off-target must not be pasted into
 * whatever editable buffer is under the cursor.
 */
class ChangelistDragTokenTest {

    @Test
    void carriesOnlyTheChangelistFlavor() {
        VcsStatusBuffer.DragToken token = new VcsStatusBuffer.DragToken("abc-123");
        assertArrayEquals(new DataFlavor[] { VcsStatusBuffer.CHANGELIST_DRAG }, token.getTransferDataFlavors());
        assertTrue(token.isDataFlavorSupported(VcsStatusBuffer.CHANGELIST_DRAG));
    }

    @Test
    void isNotPlainText() {
        VcsStatusBuffer.DragToken token = new VcsStatusBuffer.DragToken("abc-123");
        // The hole this closes: a stringFlavor payload would fall through
        // Dispatcher.drop to acceptTextDrop and paste the token as text.
        assertFalse(token.isDataFlavorSupported(DataFlavor.stringFlavor));
        assertFalse(token.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
    }

    @Test
    void handsBackTheTokenForItsFlavorAlone() throws Exception {
        VcsStatusBuffer.DragToken token = new VcsStatusBuffer.DragToken("abc-123");
        assertEquals("abc-123", token.getTransferData(VcsStatusBuffer.CHANGELIST_DRAG));
        assertThrows(UnsupportedFlavorException.class, () -> token.getTransferData(DataFlavor.stringFlavor));
    }
}
