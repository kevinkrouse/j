/*
 * Frame.java
 *
 * Copyright (C) 1998-2005 Peter Graves
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

import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.event.WindowStateListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.LayoutFocusTraversalPolicy;
import javax.swing.SwingUtilities;
import org.armedbear.j.util.Icons;

public final class Frame extends JFrame
        implements ComponentListener, FocusListener, WindowListener, WindowStateListener {
    private EditorPane editorPane;
    private EditorList editors = new EditorList();
    private Editor currentEditor;
    private Editor priorEditor;
    private ToolBar toolbar;
    private boolean showToolbar;
    private AdjustPlacementRunnable adjustPlacementRunnable;
    private Rectangle rect;
    private int extendedState;
    private final StatusBar statusBar;

    public Frame(Editor editor) {
        Editor.addFrame(this);
        addComponentListener(this);
        addWindowListener(this);
        addFocusListener(this);
        addWindowStateListener(this);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setFocusTraversalPolicy(new WindowFocusTraversalPolicy());
        statusBar = new StatusBar(this);
        getContentPane().add(statusBar, "South");
        final SessionProperties sessionProperties = Editor.getSessionProperties();
        showToolbar = sessionProperties.getShowToolbar(this);
        editorPane = new EditorPane(editor);
        editors.add(editor);
        currentEditor = editor;
        priorEditor = null;
        if (sessionProperties.getShowSidebar(this)) {
            sidebar = new Sidebar(this);
            sidebarSplitPane = createSidebarSplitPane();
            getContentPane().add(sidebarSplitPane, "Center");
        } else
            getContentPane().add(editorPane, "Center");
        titleChanged();
        setIconImages();
    }

    public void titleChanged() {
        StringBuilder sb = new StringBuilder(Version.getShortVersionString());
        String sessionName = Editor.getSessionName();
        if (sessionName != null) {
            sb.append(" [");
            sb.append(sessionName);
            sb.append(']');
        }
        if (Editor.isDebugEnabled()) {
            sb.append("     Java ");
            sb.append(System.getProperty("java.version"));
            sb.append(' ');
            sb.append(System.getProperty("java.vendor"));
        }
        setTitle(sb.toString());
    }

    private void setIconImages() {
        // Drawn from the vector logo at each size the window manager might ask
        // for, rather than scaled from one bitmap.
        final int[] sizes = new int[] { 16, 32, 64, 128, 256 };
        ArrayList<Image> images = new ArrayList<>(sizes.length);
        for (int i = 0; i < sizes.length; i++) {
            ImageIcon icon = Icons.getIconFromFile("j-logo", sizes[i]);
            if (icon != null)
                images.add(icon.getImage());
        }

        setIconImages(images);
    }

    public void storeExtendedState(int state) {
        extendedState = state;
    }

    public int retrieveExtendedState() {
        return extendedState;
    }

    public Rectangle getRect() {
        return rect;
    }

    @Override
    protected void processEvent(java.awt.AWTEvent e) {
        if (!(e instanceof KeyEvent))
            super.processEvent(e);
    }

    public boolean hasSplit() {
        return editors.size() > 1;
    }

    public int getEditorCount() {
        return editors.size();
    }

    public final Iterable<Editor> getEditors() {
        return editors;
    }

    public final Editor getCurrentEditor() {
        return currentEditor;
    }

    // May return null.
    public final Editor getNextEditor() {
        return getNextEditor(currentEditor, 1);
    }

    public final Editor getNextEditor(int count) {
        return getNextEditor(currentEditor, count);
    }

    public final Editor getNextEditor(Editor ed, int count) {
        int size = editors.size();
        Debug.bugIf(size == 0, "editor list shouldn't be empty");
        if (size == 0)
            return null;

        Debug.bugIf(count == 0, "count must be >0 to get next editor, or <0 to get previous editor");
        if (count == 0)
            return currentEditor;

        if (ed == null || size == 1)
            return editors.get(0); // return null ?

        int index = editors.indexOf(ed);
        index += count;

        if (index < 0)
            index = size + (index % size);
        else
            index = index % size;

        return editors.get(index);
    }

    public final Editor getPriorEditor() {
        return getPriorEditor(currentEditor);
    }

    /**
     * The window used most recently other than editor: the one before the
     * current one, or the current one when editor is another. Null when
     * there is no other.
     */
    private final Editor getPriorEditor(Editor editor) {
        if (editor == null || editors.size() == 1)
            return null;
        if (priorEditor != null && priorEditor != editor && editors.contains(priorEditor))
            return priorEditor;
        if (currentEditor != null && currentEditor != editor && editors.contains(currentEditor))
            return currentEditor;
        for (Editor ed : editors) {
            if (ed != editor)
                return ed;
        }
        return null;
    }

    public final void setCurrentEditor(Editor editor) {
        Debug.assertTrue(editor != null);
        Debug.assertTrue(editors.contains(editor));
        if (currentEditor != editor) {
            // currentEditor may be closing and no longer in the editors list
            if (editors.contains(currentEditor))
                priorEditor = currentEditor;
            else
                priorEditor = getNextEditor(editor, -1);
            Debug.assertTrue(priorEditor == null || editors.contains(priorEditor));

            currentEditor = editor;
        }
    }

    // Get the paired or parent editor for the given editor
    public final Editor getPairedEditor(Editor editor) {
        if (editors.size() < 2)
            return null;

        // shortcut
        if (editors.size() == 2) {
            if (editors.get(0) == editor)
                return editors.get(1);
            else
                return editors.get(0);
        }

        // find paired or parent editor
        Buffer buf = editor.getBuffer();
        Buffer other = null;
        if (buf.isPaired()) {
            if (buf.isPrimary())
                other = buf.getSecondary();
            else
                other = buf.getPrimary();
        } else if (buf.getParentBuffer() != null) {
            other = buf.getParentBuffer();
        }

        if (other != null) {
            Editor ed = findEditor(other);
            if (ed != currentEditor)
                return ed;
        }

        return null;
    }

    // If more than one Editor is open, get the paired or parent Editor
    // or the most recent Editor.  May return null.
    public final Editor getOtherEditor(Editor editor) {
        Editor paired = getPairedEditor(editor);
        if (paired != null)
            return paired;

        return getPriorEditor(editor);
    }

    /** The window along the bottom that transient buffers are shown in, or null. */
    public final Editor getPanelEditor() {
        for (Editor ed : editors) {
            if (editorPane.isPanel(ed))
                return ed;
        }
        return null;
    }

    public final boolean isPanel(Editor editor) {
        return editorPane.isPanel(editor);
    }

    /**
     * The panel as an ordinary window from now on, given a buffer that
     * belongs in one by a command that switches the window it runs in.
     */
    final void releasePanel() {
        final Editor panel = getPanelEditor();
        if (panel == null)
            return;
        editorPane.releasePanel();
        panel.addLocationBar();
        validate();
    }

    /**
     * The window other than this one to show an ordinary buffer in: never
     * the panel. Null when there is only this one, or this one and the
     * panel.
     */
    public final Editor getOtherWindow(Editor editor) {
        final Editor other = getOtherEditor(editor);
        if (other != null && !isPanel(other))
            return other;
        for (Editor ed : editors) {
            if (ed != editor && !isPanel(ed))
                return ed;
        }
        return null;
    }

    /** The window most recently used other than the panel. */
    public final Editor getWindowBehindPanel() {
        final Editor panel = getPanelEditor();
        if (panel == null)
            return currentEditor;
        if (currentEditor != panel)
            return currentEditor;
        if (priorEditor != null && priorEditor != panel && editors.contains(priorEditor))
            return priorEditor;
        return getOtherWindow(panel);
    }

    public final List<Editor> getPrimaryEditors() {
        List<Editor> ret = new ArrayList<>(editors.size());
        for (Editor ed : editors)
            if (ed.getBuffer().isPrimary())
                ret.add(ed);
        return ret;
    }

    public final boolean contains(Editor ed) {
        return ed != null && editors.contains(ed);
    }

    // get the Editor showing Buffer
    public final Editor findEditor(final Buffer buf) {
        for (Editor ed : editors)
            if (ed.getBuffer() == buf)
                return ed;
        return null;
    }

    public void updateTitle() {
        for (Editor ed : editors)
            ed.updateLocation();
    }

    private Sidebar sidebar;

    public final Sidebar getSidebar() {
        return sidebar;
    }

    private SplitPane sidebarSplitPane;

    public final SplitPane getSidebarSplitPane() {
        return sidebarSplitPane;
    }

    private SplitPane createSidebarSplitPane() {
        SplitPane splitPane = new SplitPane(SplitPane.HORIZONTAL_SPLIT, sidebar, getEditorPane());
        int dividerLocation = Editor.getSessionProperties().getSidebarWidth(this);
        splitPane.setDividerLocation(dividerLocation);
        splitPane.setBorder(null);
        // This layout is in the scale that is in force now.
        Editor.getSessionProperties().recordSidebarScale(this);
        return splitPane;
    }

    private void addSidebar() {
        if (sidebarSplitPane != null)
            getContentPane().remove(sidebarSplitPane);
        sidebar = new Sidebar(this);
        sidebarSplitPane = createSidebarSplitPane();
        getContentPane().add(sidebarSplitPane, "Center");
        validate();
        currentEditor.setFocusToDisplay();
        sidebar.setUpdateFlag(SIDEBAR_ALL);
    }

    public final EditorPane getEditorPane() {
        return editorPane;
    }

    public void frameToggleSidebar() {
        if (sidebar == null) {
            // Add sidebar.
            getContentPane().remove(getEditorPane());
            addSidebar();
        } else {
            // Save state before removing sidebar.
            Editor.getSessionProperties().saveSidebarState(this);
            // Remove sidebar.
            getContentPane().remove(sidebarSplitPane);
            sidebarSplitPane = null;
            sidebar = null;
            getContentPane().add(getEditorPane(), "Center");
        }
        validate();
        for (Editor ed : editors)
            ed.updateScrollBars();
        currentEditor.setFocusToDisplay();
    }

    public final StatusBar getStatusBar() {
        return statusBar;
    }

    public void repaintStatusBar() {
        if (statusBar != null)
            statusBar.repaint();
    }

    public void setStatusText(String text) {
        if (statusBar != null && text != null && !text.equals(statusBar.getText())) {
            statusBar.setText(text);
            statusBar.repaintNow();
        }
    }

    public boolean getShowToolbar() {
        return showToolbar;
    }

    public void setToolbar() {
        if (showToolbar && ToolBar.isToolBarEnabled()) {
            // We want a toolbar.
            ToolBar tb = currentEditor.getMode().getToolBar(this);
            if (tb != toolbar) {
                if (toolbar != null) {
                    getContentPane().remove(toolbar);
                    toolbar = null;
                }
                if (tb != null) {
                    getContentPane().add(toolbar = tb, "North");
                    toolbar.repaint();
                }
                getContentPane().validate();
            }
        } else {
            // We don't want a toolbar.
            if (toolbar != null) {
                getContentPane().remove(toolbar);
                getContentPane().validate();
            }
        }
    }

    public void frameToggleToolbar() {
        showToolbar = !showToolbar;
        if (toolbar != null) {
            if (!showToolbar) {
                getContentPane().remove(toolbar);
                toolbar = null;
                getContentPane().validate();
            }
        } else {
            if (showToolbar && ToolBar.isToolBarEnabled()) {
                ToolBar tb = currentEditor.getMode().getToolBar(this);
                if (tb != null) {
                    getContentPane().add(toolbar = tb, "North");
                    toolbar.repaint();
                    getContentPane().validate();
                }
            }
        }
        // Save new state.
        Editor.getSessionProperties().setShowToolbar(this, showToolbar);
    }

    private ToolBar defaultToolBar;

    public ToolBar getDefaultToolBar() {
        if (defaultToolBar == null)
            defaultToolBar = new DefaultToolBar(this);

        return defaultToolBar;
    }

    public void addToolbar(ToolBar tb) {
        Debug.assertTrue(toolbar == null);
        if (tb != null) {
            toolbar = tb;
            getContentPane().add(toolbar, "North");
            toolbar.repaint();
        }
        // Make sure toolbar doesn't steal focus.
        Runnable r = () -> {
            JComponent c = getFocusedComponent();
            if (c != null)
                c.requestFocus();
        };
        SwingUtilities.invokeLater(r);
    }

    public void maybeAddToolbar() {
        if (toolbar != null)
            return;
        ToolBar tb = currentEditor.getMode().getToolBar(this);
        if (tb != null)
            addToolbar(tb);
    }

    public void removeToolbar() {
        if (toolbar != null) {
            getContentPane().remove(toolbar);
            toolbar = null;
            getContentPane().validate();
        }
    }

    public void setMenu() {
        final Mode mode = currentEditor.getMode();
        final MenuBar oldMenuBar = (MenuBar) getJMenuBar();
        if (oldMenuBar == null || Platform.isPlatformMacOSX() || oldMenuBar.getMenuName() != mode.getMenuName()) {
            setJMenuBar(mode.createMenuBar(this));
            validate();
        }
    }

    public void placeWindow() {
        final SessionProperties sessionProperties = Editor.getSessionProperties();
        if (editors.get(0) == Editor.getEditor(0)) {
            // Initial window placement.
            Rectangle desired = sessionProperties.getWindowPlacement(0);
            if (desired.width == 0 || desired.height == 0) {
                // Use reasonable defaults.
                Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
                desired.width = dim.width - 100;
                if (desired.width > 800)
                    desired.width = 800;
                desired.height = dim.height - 100;
                desired.x = (dim.width - desired.width) / 2;
                desired.y = (dim.height - desired.height) / 2;
            }
            int extendedState = sessionProperties.getExtendedState(0);
            adjustPlacementRunnable = new AdjustPlacementRunnable(this, extendedState);
            setBounds(desired);
        } else {
            // BUG! Should not be hardcoded to 1!
            Rectangle desired = sessionProperties.getWindowPlacement(1);
            if (desired.width == 0 || desired.height == 0) {
                // Default positioning is cascaded.
                desired = Editor.getCurrentFrame().getBounds();
                Insets insets = Editor.getCurrentFrame().getInsets();
                desired.x += insets.left;
                desired.width -= insets.left;
                desired.y += insets.top;
                desired.height -= insets.top;
            }
            setBounds(desired);
            int extendedState = sessionProperties.getExtendedState(1);
            if (extendedState != 0)
                setExtendedState(extendedState);
        }
    }

    public void splitWindow() {
        splitWindow(currentEditor, false, true);
    }

    public void vsplitWindow() {
        splitWindow(currentEditor, true, true);
    }

    void splitWindow(Editor ed, boolean vertical, boolean focusNewEditor) {
        if (!contains(ed))
            return;
        // The panel stays one window; the split is of the one behind it.
        if (isPanel(ed))
            ed = getWindowBehindPanel();

        splitWindow(ed, ed.getBuffer(), ed.getBuffer(), 0.5f, vertical, focusNewEditor);
    }

    // Split the Editor into two and set the buffers for the current and new Editors.
    // UNDONE: Set divider location
    private void splitWindow(
            Editor ed,
            Buffer primary,
            Buffer secondary,
            float split,
            boolean vertical,
            boolean switchWindows) {
        Editor.getSessionProperties().saveSidebarState(this);
        //        final int height = ed.getHeight();
        ed.saveView();
        ed.activate(primary);
        Editor newEditor = new Editor(this);
        editors.addAfter(newEditor, ed);
        newEditor.activate(secondary);
        newEditor.updateLocation();

        editorPane.split(ed, newEditor, vertical);

        //            int dividerLocation =
        //                (int)(height * (1 - split) - sp.getDividerSize());
        //            sp.setDividerLocation(dividerLocation);

        validate();
        Editor.setCurrentEditor(switchWindows ? newEditor : ed);
        ed.setUpdateFlag(REFRAME | REPAINT);
        newEditor.updateDisplay();
        restoreFocus();
        updateControls();
    }

    /** The windows the same size again, row by row and column by column. */
    public void balanceWindows() {
        editorPane.balance();
    }

    /**
     * The window next to this one in a direction -- h, j, k or l, as vim's
     * CTRL-W takes them -- or null. Of several, the one level with the
     * caret.
     */
    public Editor getAdjacentEditor(Editor ed, char direction) {
        final List<Rectangle> others = new ArrayList<>();
        final List<Editor> candidates = new ArrayList<>();
        for (Editor other : editors) {
            if (other != ed) {
                candidates.add(other);
                others.add(other.getBounds());
            }
        }
        final Point caret = SwingUtilities.convertPoint(ed.getDisplay(), ed.getDisplay().getCaretPoint(), editorPane);
        final int index = adjacent(ed.getBounds(), caret, others, direction);
        return index < 0 ? null : candidates.get(index);
    }

    /**
     * Which of the other windows is next to this one in a direction: of
     * those beyond that side and alongside it, the nearest, and of those
     * the one the caret's row or column runs into, or the closest to it.
     *
     * @return an index into {@code others}, or -1
     */
    static int adjacent(Rectangle r, Point caret, List<Rectangle> others, char direction) {
        final boolean across = direction == 'h' || direction == 'l';
        int best = -1;
        int bestGap = 0;
        int bestMiss = 0;
        for (int i = 0; i < others.size(); i++) {
            final Rectangle o = others.get(i);
            final int gap;
            switch (direction) {
                case 'h':
                    gap = r.x - (o.x + o.width);
                    break;
                case 'l':
                    gap = o.x - (r.x + r.width);
                    break;
                case 'k':
                    gap = r.y - (o.y + o.height);
                    break;
                case 'j':
                    gap = o.y - (r.y + r.height);
                    break;
                default:
                    return -1;
            }
            final boolean alongside =
                    across ? o.y < r.y + r.height && r.y < o.y + o.height : o.x < r.x + r.width && r.x < o.x + o.width;
            if (gap < 0 || !alongside)
                continue;
            final int miss = across ? miss(caret.y, o.y, o.height) : miss(caret.x, o.x, o.width);
            if (best < 0 || gap < bestGap || (gap == bestGap && miss < bestMiss)) {
                best = i;
                bestGap = gap;
                bestMiss = miss;
            }
        }
        return best;
    }

    /** How far v is outside the span from start, of length. */
    private static int miss(int v, int start, int length) {
        if (v < start)
            return start - v;
        return v >= start + length ? v - (start + length) + 1 : 0;
    }

    public final boolean isEditorSibling(Editor ed, Editor other) {
        if (ed == other)
            return false;

        List<Editor> siblings = editorPane.getSiblings(ed);
        return siblings.contains(other);
    }

    // returns true if 'ed' is top-left of 'other'.
    public final boolean isEditorTopLeftOf(Editor ed, Editor other) {
        if (ed == other)
            return false;

        // If the 'ed' editor is found before 'other' editor, 'ed' is either to the top or to the left.
        List<Editor> siblings = editorPane.getSiblings(ed);
        for (Editor sibling : siblings) {
            if (sibling == ed)
                return true;
            if (sibling == other)
                return false;
        }

        // we should always find ed in it's own sibling list
        Debug.bug("editor wasn't found in it's own sibling list");
        return false;
    }

    // A pair's second half has a window of its own, split from under the
    // window showing its first, as mail's message under its mailbox: Gnus
    // and mu4e lay them out so. Its first half's window, to it.
    private final Map<Editor, Editor> boundWindows = new HashMap<>();

    /** The window bound under editor for its pair's second half, or null. */
    public final Editor getBoundWindow(Editor editor) {
        final Editor bound = boundWindows.get(editor);
        return bound != null && editors.contains(bound) ? bound : null;
    }

    /**
     * Forgets a binding that editor no longer shows a pair in: a bound window
     * showing anything but its window's message, or a window above one
     * showing anything but that message's mailbox, is a window like any other.
     */
    final void checkBinding(Editor editor) {
        final Editor above = getPrimaryWindow(editor);
        if (above != null && !shows(above, editor))
            boundWindows.remove(above);
        final Editor bound = getBoundWindow(editor);
        if (bound != null && !shows(editor, bound))
            boundWindows.remove(editor);
    }

    // Whether above shows the first half of a pair whose second half bound shows.
    private static boolean shows(Editor above, Editor bound) {
        final Buffer secondary = bound.getBuffer();
        return secondary.isSecondary() && secondary.getPrimary() == above.getBuffer();
    }

    /** The window a bound window is under, or null. */
    public final Editor getPrimaryWindow(Editor bound) {
        for (Map.Entry<Editor, Editor> e : boundWindows.entrySet()) {
            if (e.getValue() == bound && editors.contains(e.getKey()))
                return e.getKey();
        }
        return null;
    }

    /**
     * Shows a pair's second half in the window bound under primaryEditor,
     * split off from it if there is none yet; other windows are left alone.
     *
     * @return the bound window
     */
    public final Editor showSecondary(Editor primaryEditor, Buffer secondary, boolean focus) {
        Editor bound = getBoundWindow(primaryEditor);
        if (bound == null) {
            Editor.getSessionProperties().saveSidebarState(this);
            primaryEditor.saveView();
            bound = new Editor(this);
            editors.addAfter(bound, primaryEditor);
            bound.activate(secondary);
            bound.updateLocation();
            editorPane.split(primaryEditor, bound, false);
            validate();
            boundWindows.put(primaryEditor, bound);
        } else if (bound.getBuffer() != secondary) {
            bound.activate(secondary);
            bound.updateLocation();
        }
        Editor.setCurrentEditor(focus ? bound : primaryEditor);
        setMenu();
        setToolbar();
        primaryEditor.setUpdateFlag(REFRAME | REPAINT);
        primaryEditor.updateDisplay();
        bound.setUpdateFlag(REFRAME | REPAINT);
        bound.updateDisplay();
        restoreFocus();
        updateControls();
        return bound;
    }

    /**
     * Switches a window to a buffer that is half of a pair, or away from one:
     * the first half here with the second in the window bound under it, and
     * a window leaving the first half takes the second's window with it.
     */
    public void switchToBuffer(Editor fromEditor, final Buffer buf) {
        // The window whose bound window fromEditor is, if it is one.
        final Editor above = getPrimaryWindow(fromEditor);
        if (buf.isSecondary()) {
            final Buffer primary = buf.getPrimary();
            Editor ed = above != null && above.getBuffer() == primary ? above : null;
            if (ed == null && fromEditor.getBuffer() == primary)
                ed = fromEditor;
            if (ed == null) {
                ed = above != null ? above : fromEditor;
                ed.activate(primary);
            }
            showSecondary(ed, buf, true);
        } else if (buf.getSecondary() != null) {
            final Editor ed = above != null && above.getBuffer() == buf ? above : fromEditor;
            if (ed.getBuffer() != buf)
                ed.activate(buf);
            showSecondary(ed, buf.getSecondary(), false);
        } else {
            // Away from a pair: a bound window is a window like any other
            // now; one bound under this goes.
            if (above != null)
                boundWindows.remove(above);
            final Editor bound = getBoundWindow(fromEditor);
            fromEditor.activate(buf);
            if (bound != null)
                closeEditor(bound);
            Editor.setCurrentEditor(fromEditor);
        }
        buf.setLastActivated(System.currentTimeMillis());
    }

    // UNDONE: enlarge window by N lines.
    public void enlargeWindow(Editor editor, int n) {
        /*
        if (editorPane instanceof SplitPane) {
            final SplitPane sp = (SplitPane) editorPane;
            final int charHeight = Display.getCharHeight();
            int dividerLocation = sp.getDividerLocation();
            if (editor == editors.get(0))
                dividerLocation += charHeight;
            else
                dividerLocation -= charHeight;
            sp.setDividerLocation(dividerLocation);
        }
        */
    }

    // UNDONE: Set window height to N lines.
    public void setWindowHeight(Editor editor, int n) {
        /*
        if (editorPane instanceof SplitPane)
        {
          SplitPane sp = (SplitPane) editorPane;
          Editor otherEditor = (editor == editors.get(0)) ? editors.get(1) : editors.get(0);
          int charHeight = Display.getCharHeight();
          HorizontalScrollBar scrollBar = editor.getHorizontalScrollBar();
          int scrollBarHeight = (scrollBar != null) ? scrollBar.getHeight() : 0;
          int minHeightForOtherWindow =
            otherEditor.getLocationBarHeight() + charHeight * 4 + scrollBarHeight;
          int availableHeight =
            sp.getHeight() - minHeightForOtherWindow - sp.getDividerSize();
          int requestedHeight =
            editor.getLocationBarHeight() + charHeight * n + scrollBarHeight;
          int height = Math.min(requestedHeight, availableHeight);
          if (editor == editors.get(0))
            sp.setDividerLocation(height);
          else if (editor == editors.get(1))
            sp.setDividerLocation(sp.getHeight() - sp.getDividerSize() - height);
        }
        */
    }

    public final Editor activateInOtherWindow(Editor editor, Buffer buffer) {
        // Switch to other window.
        return openInOtherWindow(editor, buffer, 0.5F, true);
    }

    public final Editor activateInOtherWindow(Editor editor, Buffer buffer, float split) {
        // Switch to other window.
        return openInOtherWindow(editor, buffer, split, true);
    }

    public final Editor displayInOtherWindow(Editor editor, Buffer buffer) {
        // Don't switch to other window.
        return openInOtherWindow(editor, buffer, 0.5F, false);
    }

    /**
     * Shows a transient buffer in the panel along the bottom, opening the
     * panel if it is not open. The other windows are left as they are.
     */
    public final Editor openInPanel(Editor editor, Buffer buffer, boolean switchWindows) {
        editor.saveView();
        Editor panel = getPanelEditor();
        if (panel == null) {
            panel = new Editor(this);
            editors.add(panel);
            panel.activate(buffer);
            if (!Editor.preferences().getBooleanProperty(Property.TRANSIENT_PANEL_LOCATION_BAR))
                panel.removeLocationBar();
            panel.updateLocation();
            editorPane.openPanel(panel);
            validate();
        } else {
            if (panel.getBuffer() != buffer)
                panel.activate(buffer);
            panel.updateLocation();
        }
        if (switchWindows) {
            Editor.setCurrentEditor(panel);
            setMenu();
            setToolbar();
        }
        editor.setUpdateFlag(REFRAME | REPAINT);
        editor.updateDisplay();
        panel.setUpdateFlag(REFRAME | REPAINT);
        panel.updateDisplay();
        currentEditor.setFocusToDisplay();
        restoreFocus();
        updateControls();
        return panel;
    }

    /**
     * Closes the panel, with focus back in the window it came from, and
     * with {@code kill} the buffer it showed.
     */
    public final void closePanel(boolean kill) {
        final Editor panel = getPanelEditor();
        if (panel == null)
            return;
        final Buffer buffer = panel.getBuffer();
        final Editor keep = getWindowBehindPanel();
        panel.deactivate();
        unsplitInternal(keep, panel);
        if (kill && Editor.getBufferList().contains(buffer))
            BufferCommands.maybeKillBuffer(keep, buffer);
        Sidebar.refreshSidebarInAllFrames();
    }

    /**
     * Closes a list that was jumped from, as Ctrl Enter in one does: the
     * panel or other window showing it, and the list.
     */
    public final void closeList(Buffer list) {
        final Editor panel = getPanelEditor();
        if (panel != null && panel.getBuffer() == list) {
            closePanel(true);
            return;
        }
        for (Editor ed : new ArrayList<>(editors)) {
            if (ed.getBuffer() == list && editors.size() > 1)
                closeEditor(ed);
        }
        if (Editor.getBufferList().contains(list))
            list.kill();
    }

    // UNDONE: Set divider location
    private Editor openInOtherWindow(Editor editor, Buffer buffer, float split, boolean switchWindows) {
        // Half of a pair, as mail's message, has a window of its own.
        if (buffer.isTransient() && !buffer.isPaired())
            return openInPanel(editor, buffer, switchWindows);
        editor.saveView();
        // From the panel, the other window is the one behind it.
        Editor otherEditor = isPanel(editor) ? getWindowBehindPanel() : getOtherWindow(editor);
        if (otherEditor == null) {
            otherEditor = new Editor(this);
            editors.addAfter(otherEditor, editor);
            otherEditor.activate(buffer);
            otherEditor.updateLocation();

            editorPane.splitHoriz(editor, otherEditor);

            //            int dividerLocation =
            //                (int)(editor.getHeight() * (1 - split) - sp.getDividerSize());
            //            sp.setDividerLocation(dividerLocation);
            validate();
        } else {
            // Second window is already open.
            otherEditor.activate(buffer);
            otherEditor.updateLocation();
        }
        if (switchWindows) {
            Editor.setCurrentEditor(otherEditor);
            setMenu();
            setToolbar();
        }
        editor.setUpdateFlag(REFRAME | REPAINT);
        editor.updateDisplay();
        otherEditor.setUpdateFlag(REFRAME | REPAINT);
        otherEditor.updateDisplay();
        currentEditor.setFocusToDisplay();
        restoreFocus();
        updateControls();
        return otherEditor;
    }

    public void closeEditor(Editor editor) {
        closeEditor(editor, false);
    }

    /**
     * Closes a window. The caret goes to the one used before it, or with
     * {@code toSuccessor} to the one that takes its space, as in vim.
     */
    public void closeEditor(Editor editor, boolean toSuccessor) {
        if (!hasSplit())
            return;
        if (!contains(editor))
            return;
        // Closing the panel is done with what it shows.
        if (isPanel(editor)) {
            closePanel(true);
            return;
        }
        // A bound window gives its place, and the caret, back to the one above.
        final Editor above = getPrimaryWindow(editor);
        Editor keep = above != null ? above : toSuccessor ? editorPane.successor(editor) : getOtherEditor(editor);
        Editor kill = editor;
        unsplitInternal(keep, kill);
    }

    public void unsplitWindow() {
        if (!hasSplit())
            return;
        Editor keep = currentEditor;
        Editor kill = getOtherEditor(currentEditor);
        // The panel closes as it does for Escape, with what it shows.
        if (isPanel(kill)) {
            closePanel(true);
            return;
        }
        unsplitInternal(keep, kill);
    }

    public void unsplitWindowKeepOther() {
        closeEditor(currentEditor);
    }

    public void unsplitAll(final Editor keep) {
        if (!hasSplit())
            return;
        if (!contains(keep))
            return;
        // The panel closes as it does for Escape, with what it shows; kept,
        // it is the one window left.
        if (getPanelEditor() != null && !isPanel(keep)) {
            closePanel(true);
            if (!hasSplit())
                return;
        }
        Editor.getSessionProperties().saveSidebarState(this);
        // Current first, so focus leaving the others goes to keep.
        Editor.setCurrentEditor(keep);
        editorPane.root(keep);
        validate();
        List<Editor> kill = new ArrayList<>(editors);
        kill.remove(keep);
        unsplitInternal(keep, kill);
    }

    /**
     * Where focus goes when the window holding it is closed. Swing passes it
     * on from the window to the next component in the frame, which may be
     * another window's location bar, where it would select the text and open
     * the file list for an instant. After a window, or its display, comes the
     * current window's display instead, which the window kept is made before
     * the other goes. Tab is a key in a display, so nothing else traverses
     * from one.
     */
    private final class WindowFocusTraversalPolicy extends LayoutFocusTraversalPolicy {
        @Override
        public Component getComponentAfter(Container root, Component c) {
            if ((c instanceof Display || c instanceof Editor) && currentEditor != null) {
                final Display display = currentEditor.getDisplay();
                if (display != c && display.isShowing())
                    return display;
            }
            return super.getComponentAfter(root, c);
        }
    }

    private void unsplitInternal(final Editor keep, final Editor kill) {
        Editor.getSessionProperties().saveSidebarState(this);
        // Current first, so focus leaving kill goes to keep.
        Editor.setCurrentEditor(keep);
        editorPane.unsplit(kill);
        validate();
        unsplitInternal(keep, Collections.singletonList(kill));
    }

    private void unsplitInternal(final Editor keep, final Collection<Editor> kill) {
        Editor.removeEditors(kill);
        editors.removeAll(kill);
        Debug.bugIfNot(editors.contains(keep));
        // A bound window whose window above went shows a buffer of its own
        // now, as a message no longer under its mailbox.
        for (Map.Entry<Editor, Editor> e : new ArrayList<>(boundWindows.entrySet())) {
            if (kill.contains(e.getValue())) {
                boundWindows.remove(e.getKey());
            } else if (kill.contains(e.getKey())) {
                boundWindows.remove(e.getKey());
                if (e.getValue().getBuffer().isSecondary())
                    e.getValue().getBuffer().promote();
            }
        }
        if (keep.getLocationBar() == null)
            keep.addLocationBar();
        Editor.setCurrentEditor(keep);
        // The menu and toolbar are the mode's, as a help panel's are Web's.
        setMenu();
        setToolbar();
        keep.setUpdateFlag(REFRAME);
        keep.reframe();
        restoreFocus();
        statusBar.repaint();
        updateControls();
    }

    // Unsplit if any two Editor siblings are showing the exactly same thing.
    public void coalesceEditors(Editor editor) {
        if (editors.size() < 2)
            return;

        Position p = editor.getDot();
        Position m = editor.getMark();

        List<Editor> siblings = getEditorPane().getSiblings(editor);
        for (Editor sibling : siblings) {
            if (sibling == editor)
                continue;
            if (p != null && p.equals(sibling.getDot())) {
                if (m == null && sibling.getMark() == null)
                    unsplitInternal(editor, sibling);
                else if (m != null && m.equals(sibling.getMark()))
                    unsplitInternal(editor, sibling);
            }
        }

    }

    public void updateControls() {
        boolean enable = editors.size() > 1;
        for (Editor ed : editors) {
            LocationBar locationBar = ed.getLocationBar();
            if (locationBar != null) {
                JButton closeButton = locationBar.getCloseButton();
                if (closeButton != null)
                    closeButton.setEnabled(enable);
            }
        }
    }

    private boolean active;

    @Override
    public final boolean isActive() {
        return active;
    }

    public void reactivate() {
        if (currentEditor.getBuffer() == null)
            return;
        boolean changed = false;
        for (Buffer buf : Editor.getBufferList()) {
            if (currentEditor.reactivate(buf))
                changed = true;
        }
        if (changed) {
            for (int i = 0; i < Editor.getFrameCount(); i++) {
                Frame frame = Editor.getFrame(i);
                frame.setMenu();
            }
            Sidebar.repaintBufferListInAllFrames();
        }
    }

    @Override
    public void windowActivated(WindowEvent e) {
        active = true;
        Editor.setCurrentEditor(currentEditor);
        setFocus(currentEditor.getDisplay());
        repaint();
        // 1.4.0-rc hangs if we call reactivate() directly here.
        Runnable r = () -> {
            reactivate();
        };
        SwingUtilities.invokeLater(r);
    }

    @Override
    public void windowDeactivated(WindowEvent e) {
        active = false;
        // Show/hide caret.
        for (Editor editor : editors)
            editor.repaint();
    }

    @Override
    public void windowOpened(WindowEvent e) {
        if (adjustPlacementRunnable != null) {
            adjustPlacementRunnable.run();
            adjustPlacementRunnable = null;
        }
    }

    @Override
    public void windowClosing(WindowEvent e) {
        WindowCommands.killFrame(editors.get(0));
    }

    @Override
    public void windowClosed(WindowEvent e) {}

    @Override
    public void windowIconified(WindowEvent e) {}

    @Override
    public void windowDeiconified(WindowEvent e) {}

    @Override
    public void windowStateChanged(WindowEvent e) {
        int newState = e.getNewState();
        if (newState == 0) {
            // Not maximized.
            if (rect != null)
                setBounds(rect);
        }
        storeExtendedState(newState);
    }

    private JComponent focusedComponent;

    public void setFocus(JComponent c) {
        boolean change = focusedComponent != c;
        if (c != null)
            c.requestFocus();
        if (change) {
            JComponent lastFocusedComponent = focusedComponent;
            focusedComponent = c;
            // Update display of current line (show/hide caret) in all
            // windows, as required.
            for (Editor editor : editors) {
                if (editor != null && editor.getDot() != null) {
                    Display display = editor.getDisplay();
                    if (display == focusedComponent || display == lastFocusedComponent) {
                        editor.updateDotLine();
                        display.repaintChangedLines();
                    }
                }
            }
        }
    }

    public JComponent getFocusedComponent() {
        return focusedComponent;
    }

    private static final Cursor waitCursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR);

    public final void setWaitCursor() {
        setCursor(waitCursor);
        for (Editor ed : editors)
            ed.setWaitCursor();
    }

    public final void setDefaultCursor() {
        setCursor(Cursor.getDefaultCursor());
        for (Editor ed : editors)
            ed.setDefaultCursor();
    }

    public void resetDisplay() {
        if (toolbar != null) {
            getContentPane().remove(toolbar);
            toolbar = null;
        }
        defaultToolBar = null;
        for (Editor editor : editors) {
            if (editor != null) {
                editor.removeLocationBar();
                editor.removeVerticalScrollBar();
                editor.removeHorizontalScrollBar();
            }
        }
        DefaultLookAndFeel.setLookAndFeel();
        final Mode mode = currentEditor.getMode();
        setJMenuBar(mode.createMenuBar(this));
        final SessionProperties sessionProperties = Editor.getSessionProperties();
        if (sessionProperties.getShowToolbar(this) && ToolBar.isToolBarEnabled()) {
            ToolBar tb = mode.getToolBar(this);
            if (tb != null)
                addToolbar(tb);
        }
        if (sidebarSplitPane != null) {
            // Save state before removing sidebar.
            sessionProperties.saveSidebarState(this);
            // Remove sidebar.
            getContentPane().remove(sidebarSplitPane);
            sidebarSplitPane = null;
            sidebar = null;

            sidebar = new Sidebar(this);
            sidebarSplitPane = createSidebarSplitPane();
            getContentPane().add(sidebarSplitPane, "Center");
            sidebar.setUpdateFlag(SIDEBAR_ALL);
        }
        for (Editor editor : editors) {
            if (editor != null) {
                editor.addLocationBar();
                editor.updateLocation();
                editor.addVerticalScrollBar();
                editor.maybeAddHorizontalScrollBar();
                editor.getDisplay().initialize();
            }
        }
        updateControls();
        validate();
    }

    public static final void restoreFocus() {
        Editor.restoreFocus();
    }

    @Override
    public void componentResized(ComponentEvent e) {
        if (extendedState != 6) {
            // Not maximized.
            rect = getBounds();
        }
    }

    @Override
    public void componentMoved(ComponentEvent e) {
        if (extendedState != 6) {
            // Not maximized.
            rect = getBounds();
        }
    }

    @Override
    public void componentShown(ComponentEvent e) {}

    @Override
    public void componentHidden(ComponentEvent e) {}

    @Override
    public void focusGained(FocusEvent e) {
        currentEditor.setFocusToDisplay();
    }

    @Override
    public void focusLost(FocusEvent e) {}
}
