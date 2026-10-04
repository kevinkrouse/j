/*
 * KeyPressedHook.java
 *
 * Copyright (C) 1999-2003 Peter Graves
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

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import javax.swing.JDialog;
import org.armedbear.j.extension.Extensions;
import org.armedbear.j.util.Keys;

/**
 * Runs key-pressed-hook, when enableKeyPressedHook is set, before a key is
 * dispatched to any component but the edit window, incremental find or a
 * dialog.
 */
public final class KeyPressedHook {
    private KeyPressedHook() {}

    public static void install() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED && isComponentHookable(e.getComponent())) {
                KeyMapping km;
                int keyCode = e.getKeyCode();
                if (keyCode != 0)
                    km = new KeyMapping(keyCode, Keys.keyModifiers(e), null);
                else
                    km = new KeyMapping(e.getKeyChar(), null);
                Extensions.hooks().invoke("key-pressed-hook", km.toString());
            }
            return false;
        });
    }

    private static final boolean isComponentHookable(Component c) {
        if (c instanceof Display)
            return false;
        if (c == null)
            return false;
        if (c instanceof HistoryTextField) {
            HistoryTextField textField = (HistoryTextField) c;
            if (textField.getHandler() instanceof IncrementalFindTextFieldHandler)
                return false;
        }
        if (!Editor.preferences().getBooleanProperty(Property.ENABLE_KEY_PRESSED_HOOK))
            return false;
        while (true) {
            if (c instanceof JDialog)
                return false;
            c = c.getParent();
            if (c == null)
                return true;
        }
    }
}
