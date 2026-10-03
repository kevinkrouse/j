/*
 * SidebarTree.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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

import org.armedbear.j.util.Utilities;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

public class SidebarTree extends JTree
{
    /** A selection's background in a sidebar list without the focus. */
    public static final Color NO_FOCUS_SELECTION_BACKGROUND = new Color(208, 208, 208);

    public SidebarTree(TreeModel model)
    {
        super(model);
    }

    /**
     * The keys a tree of tags takes: Enter goes to the selected tag
     * (Alt+Enter hides the sidebar too), Tab to the buffer list, Escape back
     * to the text. Any other key is the tree's, not the editor's, until it
     * is released.
     *
     * @param updatePosition selects the tag the caret is at
     */
    protected static void tagKeyPressed(Editor editor, KeyEvent e, LocalTag selected,
                                        Runnable updatePosition)
    {
        final int modifiers = Utilities.keyModifiers(e);
        switch (e.getKeyCode()) {
            // Ignore modifier keystrokes.
            case KeyEvent.VK_SHIFT:
            case KeyEvent.VK_CONTROL:
            case KeyEvent.VK_ALT:
            case KeyEvent.VK_META:
                return;
            case KeyEvent.VK_ENTER:
                e.consume();
                if (selected != null)
                    selected.gotoTag(editor);
                editor.setFocusToDisplay();
                if (modifiers == Constants.ALT_MASK)
                    editor.toggleSidebar();
                return;
            case KeyEvent.VK_TAB:
                e.consume();
                if (modifiers == 0) {
                    final Sidebar sidebar = editor.getSidebar();
                    if (sidebar.getBufferList() != null) {
                        updatePosition.run();
                        editor.setFocus(sidebar.getBufferList());
                    }
                }
                return;
            case KeyEvent.VK_ESCAPE:
                e.consume();
                editor.getSidebar().setBuffer();
                updatePosition.run();
                editor.setFocusToDisplay();
                return;
        }
        editor.getDispatcher().setEnabled(false);
    }

    /** The key's release: the editor's keys again. */
    protected static void tagKeyReleased(Editor editor, KeyEvent e)
    {
        e.consume();
        editor.getDispatcher().setEnabled(true);
    }

    /**
     * The mouse leaving: the focus back to the text, if this had it, and not
     * from whatever else had it.
     */
    protected void giveBackFocus(Editor editor)
    {
        final Frame frame = editor.getFrame();
        if (frame != null && frame.getFocusedComponent() == this)
            editor.setFocusToDisplay();
    }

    protected void scrollNodeToCenter(DefaultMutableTreeNode node)
    {
        TreePath treePath = new TreePath(node.getPath());
        TreePath parentPath = treePath.getParentPath();
        if (parentPath != null)
            expandPath(parentPath);
        int row = getRowForPath(treePath);
        scrollRowToCenter(row);
        setSelectionRow(row);
    }

    protected void scrollRowToCenter(int row)
    {
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
