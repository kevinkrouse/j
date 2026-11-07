/*
 * SidebarTree.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import org.armedbear.j.util.Keys;

public class SidebarTree extends JTree {
    /** A selection's background in a sidebar list without the focus. */
    public static final Color NO_FOCUS_SELECTION_BACKGROUND = new Color(208, 208, 208);

    public SidebarTree(TreeModel model) {
        super(model);
    }

    /**
     * The keys a tree of tags takes, as {@link #sidebarKeyPressed}'s: Enter
     * goes to the selected tag.
     *
     * @param updatePosition selects the tag the caret is at
     */
    protected static void tagKeyPressed(Editor editor, KeyEvent e, LocalTag selected, Runnable updatePosition) {
        sidebarKeyPressed(editor, e, selected == null ? null : () -> selected.gotoTag(editor), updatePosition);
    }

    /**
     * The keys a tree in the sidebar takes: Enter does enter, if any, and
     * goes back to the text (Alt+Enter hides the sidebar too), Tab goes to
     * the upper half's list, Escape back to the text. Any other key is the
     * tree's, not the editor's, until it is released.
     */
    protected static void sidebarKeyPressed(Editor editor, KeyEvent e, Runnable enter, Runnable updatePosition) {
        final int modifiers = Keys.keyModifiers(e);
        switch (e.getKeyCode()) {
            // Ignore modifier keystrokes.
            case KeyEvent.VK_SHIFT:
            case KeyEvent.VK_CONTROL:
            case KeyEvent.VK_ALT:
            case KeyEvent.VK_META:
                return;
            case KeyEvent.VK_ENTER:
                e.consume();
                if (enter != null)
                    enter.run();
                editor.setFocusToDisplay();
                if (modifiers == Constants.ALT_MASK)
                    WindowCommands.toggleSidebar(editor);
                return;
            case KeyEvent.VK_TAB:
                e.consume();
                if (modifiers == 0) {
                    final Sidebar sidebar = editor.getSidebar();
                    if (sidebar != null) {
                        updatePosition.run();
                        sidebar.focusTopView();
                    }
                }
                return;
            case KeyEvent.VK_ESCAPE:
                e.consume();
                if (editor.getSidebar() != null)
                    editor.getSidebar().setBuffer();
                updatePosition.run();
                editor.setFocusToDisplay();
                return;
        }
        if (chord(editor, e))
            return;
        editor.getDispatcher().setEnabled(false);
    }

    /**
     * A chord, Ctrl or Alt with a key the tree doesn't move by, run as the
     * text's keys run it, so Ctrl P, Ctrl Shift P and the like work from the
     * sidebar too: whether something took it.
     */
    public static boolean chord(Editor editor, KeyEvent e) {
        final int modifiers = Keys.keyModifiers(e);
        if ((modifiers & (Constants.CTRL_MASK | Constants.ALT_MASK | Constants.META_MASK)) == 0)
            return false;
        switch (e.getKeyCode()) {
            case KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_UP,
                    KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_KP_UP, KeyEvent.VK_KP_DOWN,
                    KeyEvent.VK_KP_LEFT, KeyEvent.VK_KP_RIGHT, KeyEvent.VK_HOME, KeyEvent.VK_END, KeyEvent.VK_PAGE_UP,
                    KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_SPACE, KeyEvent.VK_ENTER, KeyEvent.VK_TAB, KeyEvent.VK_ESCAPE:
                return false;
            default:
                break;
        }
        if (!editor.getDispatcher().dispatchKeyPressedFrom(e))
            return false;
        e.consume();
        return true;
    }

    /** The key's release: the editor's keys again. */
    protected static void tagKeyReleased(Editor editor, KeyEvent e) {
        e.consume();
        editor.getDispatcher().setEnabled(true);
    }

    /**
     * The mouse leaving: the focus back to the text, if this had it, and not
     * from whatever else had it.
     */
    protected void giveBackFocus(Editor editor) {
        final Frame frame = editor.getFrame();
        if (frame != null && frame.getFocusedComponent() == this)
            editor.setFocusToDisplay();
    }

    protected void scrollNodeToCenter(DefaultMutableTreeNode node) {
        TreePath treePath = new TreePath(node.getPath());
        TreePath parentPath = treePath.getParentPath();
        if (parentPath != null)
            expandPath(parentPath);
        int row = getRowForPath(treePath);
        scrollRowToCenter(row);
        setSelectionRow(row);
    }

    protected void scrollRowToCenter(int row) {
        Rectangle rect = getVisibleRect();
        int top = getClosestRowForLocation(rect.x, rect.y);
        int bottom = top + getVisibleRowCount() - 1;
        int margin = getVisibleRowCount() / 4;
        int first = row - margin;
        if (first < 0)
            first = 0;
        int last = row + margin;
        if (last > getRowCount() - 1)
            last = getRowCount() - 1;
        if (first < top || first > bottom) {
            scrollRowToVisible(first);
            rect = getVisibleRect();
            top = getClosestRowForLocation(rect.x, rect.y);
            bottom = top + getVisibleRowCount() - 1;
        }
        if (last < top || last > bottom)
            scrollRowToVisible(last);
    }
}
