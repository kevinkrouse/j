/*
 * WindowCommands.java
 *
 * Copyright (C) 1998-2003 Peter Graves
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

import static org.armedbear.j.Constants.*;

/** Splitting, switching and sizing windows; frames, the sidebar, the toolbar, and showing tabs. */
public final class WindowCommands {
    private WindowCommands() {}

    public static void nextFrame(Editor editor) {
        int count = Editor.getEditorCount();
        if (count > 1) {
            Editor ed = null;
            for (int i = 0; i < count; i++) {
                ed = Editor.getEditor(i);
                if (ed == editor) {
                    if (++i == count)
                        i = 0;
                    ed = Editor.getEditor(i);
                    ed.getFrame().toFront();
                    ed.requestFocusLater();
                    break;
                }
            }
        }
    }

    public static void toggleSidebar(Editor editor) {
        editor.getFrame().frameToggleSidebar();
    }

    public static void sidebarListBuffers(Editor editor) {
        editor.ensureActive();

        if (editor.getFrame().getSidebar() == null)
            toggleSidebar(editor);

        if (editor.getFrame().getSidebar() != null)
            editor.getFrame().getSidebar().activateBufferList();
    }

    public static void sidebarListTags(Editor editor) {
        if (!editor.getFrame().isActive())
            return;

        if (editor.getMode().getSidebarComponent(editor) != null) {
            if (editor.getFrame().getSidebar() == null)
                toggleSidebar(editor);
            if (editor.getFrame().getSidebar() != null)
                editor.getFrame().getSidebar().activateNavigationComponent();
        }
    }

    public static void toggleToolbar(Editor editor) {
        editor.getFrame().frameToggleToolbar();
    }

    public static boolean isSibling(Editor editor, Editor other) {
        return editor.getFrame().isEditorSibling(editor, other);
    }

    public static boolean isTopLeftOf(Editor editor, Editor other) {
        return editor.getFrame().isEditorTopLeftOf(editor, other);
    }

    public static void splitWindow(Editor editor) {
        Editor.currentEditor().getFrame().splitWindow();
    }

    /**
     * {@code splitWindow vim}: the caret stays in the top window, which is
     * what vim's split looks like -- the new window above, and the caret in
     * it -- with both showing the same.
     */
    public static void splitWindow(Editor editor, String arg) {
        editor.getFrame().splitWindow(editor, false, !"vim".equals(arg));
    }

    public static void vsplitWindow(Editor editor) {
        Editor.currentEditor().getFrame().vsplitWindow();
    }

    /** {@code vsplitWindow vim}: the caret stays in the left window. */
    public static void vsplitWindow(Editor editor, String arg) {
        editor.getFrame().splitWindow(editor, true, !"vim".equals(arg));
    }

    public static void unsplitWindow(Editor editor) {
        IdleThread.killFollowContextTask();
        editor.getFrame().unsplitWindow();
    }

    /**
     * {@code killWindow vim}: the caret goes to the window that takes this
     * one's space, the next in its row or column, as in vim.
     */
    public static void killWindow(Editor editor, String arg) {
        editor.getFrame().closeEditor(Editor.currentEditor(), "vim".equals(arg));
        Sidebar sidebar = editor.getSidebar();
        if (sidebar != null) {
            sidebar.setUpdateFlag(SIDEBAR_ALL);
            sidebar.refreshSidebar();
        }
    }

    /**
     * {@code adjacentWindow h|j|k|l} -- to the window left, below, above or
     * right of this one; of several, the one level with the caret.
     */
    public static void adjacentWindow(Editor editor, String direction) {
        if (direction == null || direction.length() != 1)
            return;
        switchWindow(editor, editor.getFrame().getAdjacentEditor(editor, direction.charAt(0)));
    }

    /** {@code gotoWindow n} -- to the nth window, the top left first. */
    public static void gotoWindow(Editor editor, String n) {
        if (n == null)
            return;
        int target;
        try {
            target = Integer.parseInt(n.trim());
        }
        catch (NumberFormatException e) {
            return;
        }
        // There being no such window does nothing, as in vim.
        for (Editor ed : editor.getFrame().getEditors())
            if (--target == 0) {
                switchWindow(editor, ed);
                return;
            }
    }

    /** Every window in each row or column the same size again. */
    public static void balanceWindows(Editor editor) {
        editor.getFrame().balanceWindows();
    }

    public static void killWindow(Editor editor) {
        killWindow(editor, null);
    }

    // Close every window but the editor's own.
    // Also aliased as 'killOtherWindows' in CommandTable
    public static void unsplitAllWindows(Editor editor) {
        editor.getFrame().unsplitAll(editor);
    }

    // Switch to the Editor that is paired with the editor's buffer
    // or to its parent buffer.  If neither exist, switch
    // to the most recent Editor or the previous primary buffer.
    public static void otherWindow(Editor editor) {
        final Editor ed = editor.getOtherEditor();
        if (ed != null)
            switchWindow(editor, ed);
    }

    // Switch to most recent Editor or the previous Editor.
    public static void priorWindow(Editor editor) {
        Editor ed = editor.getFrame().getPriorEditor();
        if (ed == null || ed == Editor.currentEditor())
            ed = editor.getFrame().getNextEditor(-1);

        switchWindow(editor, ed);
    }

    public static void nextWindow(Editor editor) {
        _nextWindow(editor, 1);
    }

    public static void nextWindow(Editor editor, String arg) {
        int count = 1;
        if (arg != null)
            try {
                count = Integer.parseInt(arg);
            }
            catch (NumberFormatException e) {
                MessageDialog.showMessageDialog(
                    "Invalid number \"" + arg + '"',
                    "Error"
                );
                return;
            }

        _nextWindow(editor, count);
    }

    public static void previousWindow(Editor editor) {
        _nextWindow(editor, -1);
    }

    public static void previousWindow(Editor editor, String arg) {
        int count = 1;
        if (arg != null)
            try {
                count = Integer.parseInt(arg);
            }
            catch (NumberFormatException e) {
                MessageDialog.showMessageDialog(
                    "Invalid number \"" + arg + '"',
                    "Error"
                );
                return;
            }

        _nextWindow(editor, -1 * count);
    }

    private static void _nextWindow(Editor editor, int count) {
        final Editor ed = editor.getFrame().getNextEditor(count);
        switchWindow(editor, ed);
    }

    private static void switchWindow(Editor editor, Editor ed) {
        if (ed != null) {
            editor.saveView();
            Editor.setCurrentEditor(ed);
            ed.getBuffer().setLastActivated(System.currentTimeMillis());
            ed.setFocusToDisplay();
            if (ed.getDot() != null) {
                ed.update(ed.getDotLine());
                ed.getDisplay().repaintChangedLines();
            }
            if (editor.getDot() != null) {
                editor.updateDotLine();
                editor.getDisplay().repaintChangedLines();
            }
            editor.getFrame().setMenu();
            editor.getFrame().setToolbar();
            Sidebar sidebar = editor.getSidebar();
            if (sidebar != null) {
                sidebar.setUpdateFlag(SIDEBAR_ALL);
                sidebar.refreshSidebar();
            }
        }
    }

    public static void enlargeWindow(Editor editor) {
        editor.getFrame().enlargeWindow(editor, 1);
    }

    public static void enlargeWindow(Editor editor, int n) {
        editor.getFrame().enlargeWindow(editor, n);
    }

    public static void shrinkWindowIfLargerThanBuffer(Editor editor) {
        final Frame frame = editor.getFrame();
        int n = editor.getBuffer().getLineCount();
        if (n < editor.getWindowHeight())
            frame.setWindowHeight(editor, n);
    }

    public static void killFrame(Editor editor) {
        if (Editor.getFrameCount() == 1) {
            // Does not return if OK to exit.
            editor.maybeExit();
        } else {
            // Move frame being closed to end of list.
            if (Editor.indexOf(editor.getFrame()) != Editor.getFrameCount() - 1) {
                Editor.removeFrame(editor.getFrame());
                Editor.addFrame(editor.getFrame());
            }
            Editor.getSessionProperties().saveWindowPlacement();
            Editor.removeFrame(editor.getFrame());
            editor.getFrame().dispose();

            for (Editor ed : editor.getFrame().getEditors())
                Editor.removeEditor(ed);
            Editor.setCurrentEditor(Editor.getEditor(0));
        }
    }

    public static void visibleTabs(Editor editor) {
        Editor.setTabsAreVisible(!Editor.tabsAreVisible());
        if (Editor.tabsAreVisible())
            editor.status("Tabs are visible");
        else
            editor.status("Tabs are not visible");
        for (int i = 0; i < Editor.getEditorCount(); i++) {
            Editor ed = Editor.getEditor(i);
            ed.getDisplay().repaint();
        }
    }
}
