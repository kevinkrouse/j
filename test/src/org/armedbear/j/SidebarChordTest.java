/*
 * SidebarChordTest.java
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

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class SidebarChordTest {
    private EditorHarness h;

    @AfterEach
    public void tearDown() {
        if (h != null)
            h.close();
    }

    private static KeyEvent key(int code, int modifiers) {
        return new KeyEvent(new JTree(), KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED);
    }

    @Test
    public void aTreeKeepsItsOwnKeysAndPlainOnesFromTheEditor() throws Exception {
        h = EditorHarness.create();
        SwingUtilities.invokeAndWait(() -> {
            final Editor editor = h.editor();
            // Select all, copy, cut, paste, clear: the tree's own.
            for (int code : new int[] {
                KeyEvent.VK_A,
                KeyEvent.VK_C,
                KeyEvent.VK_X,
                KeyEvent.VK_V,
                KeyEvent.VK_BACK_SLASH })
                assertFalse(SidebarTree.chord(editor, key(code, InputEvent.CTRL_DOWN_MASK)), KeyEvent.getKeyText(code));
            // Moving about, and keys with no Ctrl, Alt or Meta: the tree's.
            assertFalse(SidebarTree.chord(editor, key(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK)));
            assertFalse(SidebarTree.chord(editor, key(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK)));
            assertFalse(SidebarTree.chord(editor, key(KeyEvent.VK_P, 0)));
            assertFalse(SidebarTree.chord(editor, key(KeyEvent.VK_CONTROL, InputEvent.CTRL_DOWN_MASK)));
        });
    }
}
