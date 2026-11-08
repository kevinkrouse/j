/*
 * VcsMenu.java
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

import java.awt.event.ActionListener;
import javax.swing.JPopupMenu;
import org.armedbear.j.Editor;
import org.armedbear.j.KeyMapping;
import org.armedbear.j.Menu;
import org.armedbear.j.MenuItem;

/**
 * A context menu of the version control things to do with what is under
 * the mouse: every item a j command, its key shown beside it, dimmed
 * with why where it can't apply. The status buffer's runs its items
 * through the editor's dispatcher, by name; the Changes tab's through
 * itself, acting on its own nodes.
 */
final class VcsMenu {
    private final Editor editor;
    private final ActionListener run;
    private final JPopupMenu popup = new JPopupMenu();

    VcsMenu(Editor editor, ActionListener run) {
        this.editor = editor;
        this.run = run;
    }

    /** An item that runs command, its key shown; dimmed with why where it can't. */
    MenuItem add(String label, String command, String key, boolean enabled, String why) {
        return add(label, command, key, enabled, why, run);
    }

    /**
     * An item that runs action, labelled with command and its key, so it
     * reads as the command it is though it acts in place, as the Changes
     * tab's items do on their nodes.
     */
    MenuItem add(String label, String command, String key, boolean enabled, String why, ActionListener action) {
        final MenuItem item = new MenuItem(label, key);
        item.setActionCommand(command);
        item.addActionListener(action);
        if (!enabled) {
            item.setEnabled(false);
            if (why != null)
                item.setToolTipText(why);
        }
        popup.add(item);
        return item;
    }

    /** As add, the key the editor maps command to. */
    MenuItem add(String label, String command, boolean enabled, String why) {
        return add(label, command, key(command), enabled, why);
    }

    /** As add, the key the editor maps command to, running action. */
    MenuItem add(String label, String command, boolean enabled, String why, ActionListener action) {
        return add(label, command, key(command), enabled, why, action);
    }

    MenuItem add(String label, String command) {
        return add(label, command, true, null);
    }

    void separator() {
        popup.addSeparator();
    }

    /** Sized and ready to show. */
    JPopupMenu build() {
        popup.pack();
        return popup;
    }

    // The key editor maps command to, as the menu shows it. Where the
    // editor's own mode doesn't map it — the Changes tab's editor is a
    // file buffer — the status mode's key is shown, the command's home.
    private String key(String command) {
        final String at = Menu.keyText(editor, command);
        if (!at.isEmpty())
            return at;
        final KeyMapping mapping = VcsStatusMode.getMode().getKeyMap().getKeyMapping(command);
        if (mapping == null)
            return "";
        // As Menu.keyText: 'a' shown as A.
        String text = mapping.getKeyText();
        if (text.length() == 3 && text.charAt(0) == '\'' && text.charAt(2) == '\'')
            text = text.substring(1, 2).toUpperCase(java.util.Locale.ROOT);
        return text;
    }
}
