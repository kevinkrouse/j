/*
 * Keys.java
 *
 * Copyright (C) 1998-2005 Peter Graves
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import static org.armedbear.j.Constants.*;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.KeyStroke;
import org.armedbear.j.Log;
import org.armedbear.j.Platform;

/** Key strokes and modifiers: parsing, naming, and j's modifier bits. */
public final class Keys {
    private Keys() {}

    /**
     * The keyboard modifiers held during an event, as j's own bits.
     *
     * <p>Read from getModifiersEx() rather than the deprecated getModifiers(),
     * which cannot tell some of these apart from mouse buttons: it reports the
     * middle button and Alt as the same bit, and the right button and Meta as
     * the same bit.
     */
    public static int keyModifiers(InputEvent e) {
        final int ex = e.getModifiersEx();
        int modifiers = 0;
        if ((ex & InputEvent.SHIFT_DOWN_MASK) != 0)
            modifiers |= SHIFT_MASK;
        if ((ex & InputEvent.CTRL_DOWN_MASK) != 0)
            modifiers |= CTRL_MASK;
        if ((ex & InputEvent.META_DOWN_MASK) != 0)
            modifiers |= META_MASK;
        if ((ex & InputEvent.ALT_DOWN_MASK) != 0)
            modifiers |= ALT_MASK;
        return modifiers;
    }

    /** True when the event carries no keyboard modifier. */
    public static boolean isUnmodified(InputEvent e) {
        return keyModifiers(e) == 0;
    }

    public static KeyStroke getKeyStroke(String keyText) {
        if (keyText == null)
            return null;
        keyText = keyText.trim();
        if (keyText.length() == 0)
            return null;
        if (keyText.startsWith("'")) {
            if (keyText.length() != 3)
                return null;
            if (keyText.charAt(2) != '\'')
                return null;
            return KeyStroke.getKeyStroke(keyText.charAt(1));
        }
        if (keyText.length() == 1)
            return KeyStroke.getKeyStroke(keyText.charAt(0));
        int modifiers = 0;
        while (true) {
            if (keyText.startsWith("Ctrl ") || keyText.startsWith("Ctrl\t")) {
                modifiers |= CTRL_MASK;
                keyText = keyText.substring(5).trim();
                continue;
            }
            if (keyText.startsWith("Shift ") || keyText.startsWith("Shift\t")) {
                modifiers |= SHIFT_MASK;
                keyText = keyText.substring(6).trim();
                continue;
            }
            if (keyText.startsWith("Alt ") || keyText.startsWith("Alt\t")) {
                modifiers |= ALT_MASK;
                keyText = keyText.substring(4).trim();
                continue;
            }
            if (keyText.startsWith("Meta ") || keyText.startsWith("Meta\t")) {
                modifiers |= META_MASK;
                keyText = keyText.substring(5).trim();
                continue;
            }
            // No more modifiers.  What's left is the key name.
            break;
        }
        if (modifiers == 0 && keyText.length() == 1) {
            char c = keyText.charAt(0);
            return KeyStroke.getKeyStroke(c);
        }
        if (modifiers == SHIFT_MASK && keyText.length() == 1) {
            char c = keyText.charAt(0);
            char lower = Character.toLowerCase(c);
            char upper = Character.toUpperCase(c);
            if (lower != upper)
                return KeyStroke.getKeyStroke(upper);
        }
        int keyCode = getKeyCode(keyText);
        if (keyCode == 0)
            return null;
        return KeyStroke.getKeyStroke(keyCode, modifiers);
    }

    public static final String getKeyText(KeyStroke keyStroke) {
        return getKeyText(keyStroke.getKeyChar(), keyStroke.getKeyCode(), keyStroke.getModifiers());
    }

    public static String getKeyText(char keyChar, int keyCode, int modifiers) {
        StringBuilder sb = new StringBuilder();
        if (keyChar >= ' ' && keyChar != 0xffff) {
            // Mapping is defined by character.
            if (keyChar >= 'A' && keyChar <= 'Z') {
                sb.append("Shift ");
                sb.append(keyChar);
            } else {
                sb.append('\'');
                sb.append(keyChar);
                sb.append('\'');
            }
        } else {
            // Mapping is defined by key code and modifiers.
            if ((modifiers & CTRL_MASK) != 0)
                sb.append("Ctrl ");
            if ((modifiers & SHIFT_MASK) != 0)
                sb.append("Shift ");
            if ((modifiers & ALT_MASK) != 0)
                sb.append("Alt ");
            if ((modifiers & META_MASK) != 0) {
                if (Platform.isPlatformMacOSX())
                    sb.append("Cmd ");
                else
                    sb.append("Meta ");
            }
            sb.append(getKeyName(keyCode));
        }
        return sb.toString();
    }

    private static String[] keyNames = {
        "Enter",
        "Backspace",
        "Tab",
        "Escape",
        "Space",
        "Page Up",
        "Page Down",
        "Home",
        "End",
        "Delete",
        "Left",
        "Right",
        "Up",
        "Down",
        "NumPad Left",
        "NumPad Right",
        "NumPad Up",
        "NumPad Down",
        "NumPad *",
        "NumPad +",
        "NumPad -",
        "NumPad Insert",
        "Mouse-1",
        "Double Mouse-1",
        "Mouse-2",
        "Double Mouse-2",
        "Mouse-3",
        "Double Mouse-3"
    };

    private static int[] keyCodes = {
        KeyEvent.VK_ENTER,
        KeyEvent.VK_BACK_SPACE,
        KeyEvent.VK_TAB,
        KeyEvent.VK_ESCAPE,
        KeyEvent.VK_SPACE,
        KeyEvent.VK_PAGE_UP,
        KeyEvent.VK_PAGE_DOWN,
        KeyEvent.VK_HOME,
        KeyEvent.VK_END,
        KeyEvent.VK_DELETE,
        KeyEvent.VK_LEFT,
        KeyEvent.VK_RIGHT,
        KeyEvent.VK_UP,
        KeyEvent.VK_DOWN,
        KeyEvent.VK_KP_LEFT,
        KeyEvent.VK_KP_RIGHT,
        KeyEvent.VK_KP_UP,
        KeyEvent.VK_KP_DOWN,
        0x6a,
        0x6b,
        0x6d,
        0x9b,
        VK_MOUSE_1,
        VK_DOUBLE_MOUSE_1,
        VK_MOUSE_2,
        VK_DOUBLE_MOUSE_2,
        VK_MOUSE_3,
        VK_DOUBLE_MOUSE_3
    };

    private static int getKeyCode(String keyName) {
        if (keyName.length() == 0)
            return 0;
        if (keyName.length() == 1)
            return (int) keyName.charAt(0);
        if (keyName.startsWith("0x")) {
            try {
                return Integer.parseInt(keyName.substring(2), 16);
            }
            catch (NumberFormatException e) {
                Log.error(e);
            }
            return 0;
        }
        for (int i = 0; i < keyNames.length; i++) {
            if (keyName.equals(keyNames[i]))
                return keyCodes[i];
        }
        if (keyName.charAt(0) == 'F') {
            try {
                int n = Integer.parseInt(keyName.substring(1));
                return KeyEvent.VK_F1 + n - 1;
            }
            catch (NumberFormatException e) {
                Log.error(e);
            }
        }
        return 0;
    }

    private static String getKeyName(int keyCode) {
        if (
            keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9 || keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z
        )
            return String.valueOf((char) keyCode);
        if (keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12)
            return "F" + Integer.toString(keyCode - KeyEvent.VK_F1 + 1);
        if (",./;=[\\]".indexOf(keyCode) >= 0)
            return String.valueOf((char) keyCode);
        for (int i = 0; i < keyCodes.length; i++) {
            if (keyCode == keyCodes[i])
                return keyNames[i];
        }
        return "0x" + Integer.toString(keyCode, 16);
    }
}
