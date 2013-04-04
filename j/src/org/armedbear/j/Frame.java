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

import org.armedbear.j.util.FastStringBuffer;
import org.armedbear.j.util.Utilities;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Insets;
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
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public final class Frame extends JFrame implements Constants, ComponentListener,
    FocusListener, WindowListener, WindowStateListener
{
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

    public Frame(Editor editor)
    {
        Editor.frames.add(this);
        addComponentListener(this);
        addWindowListener(this);
        addFocusListener(this);
        addWindowStateListener(this);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        statusBar = new StatusBar(this);
        getContentPane().add(statusBar, "South");
        final SessionProperties sessionProperties =
            Editor.getSessionProperties();
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

    public void titleChanged()
    {
        FastStringBuffer sb =
            new FastStringBuffer(Version.getShortVersionString());
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

    private void setIconImages()
    {
        String[] imageNames = new String[] { "icons/j-512.png", "icons/j-128.png", "icons/j-32.png", "icons/j-16.png" };
        ArrayList<Image> images = new ArrayList<Image>(imageNames.length);
        for (int i = 0; i < imageNames.length; i++)
        {
            Image image = Utilities.getImageFromFile(imageNames[i]);
            if (image != null)
                images.add(image);
        }

        setIconImages(images);
    }

    public void storeExtendedState(int state)
    {
        extendedState = state;
    }

    public int retrieveExtendedState()
    {
        return extendedState;
    }

    public Rectangle getRect()
    {
        return rect;
    }

    protected void processEvent(java.awt.AWTEvent e)
    {
        if (!(e instanceof KeyEvent))
            super.processEvent(e);
    }

    public boolean hasSplit()
    {
        return editors.size() > 1;
    }

    public int getEditorCount()
    {
        return editors.size();
    }

    public final Iterable<Editor> getEditors()
    {
        return editors;
    }

    public final Editor getCurrentEditor()
    {
        return currentEditor;
    }

    // May return null.
    public final Editor getNextEditor()
    {
        return getNextEditor(currentEditor, 1);
    }

    public final Editor getNextEditor(int count)
    {
        return getNextEditor(currentEditor, count);
    }

    public final Editor getNextEditor(Editor ed, int count)
    {
        int size = editors.size();
        Debug.bugIf(size == 0, "editor list shouldn't be empty");
        if (size == 0)
            return null;

        Debug.bugIf(count == 0, "count must be >0 to get next editor, or <0 to get previous editor");
        if (count == 0)
            return currentEditor;

        if (ed == null || size == 1)
            return editors.get(0);  // return null ?

        int index = editors.indexOf(ed);
        index += count;

        if (index < 0)
            index = size + (index % size);
        else
            index = index % size;

        return editors.get(index);
    }

    public final Editor getPriorEditor()
    {
        return getPriorEditor(currentEditor);
    }

    // get most recent editor
    private final Editor getPriorEditor(Editor editor)
    {
        // If no other editor, return null
        if (editor == null || editors.size() == 1)
            return null;

        return priorEditor;
    }

    public final void setCurrentEditor(Editor editor)
    {
        Debug.assertTrue(editor != null);
        Debug.assertTrue(editors.contains(editor));
        if (currentEditor != editor)
        {
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
    public final Editor getPairedEditor(Editor editor)
    {
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
        }
        else if (buf.getParentBuffer() != null) {
            other = buf.getParentBuffer();
        }

        if (other != null)
        {
            Editor ed = findEditor(other);
            if (ed != currentEditor)
                return ed;
        }

        return null;
    }

    // If more than one Editor is open, get the paired or parent Editor
    // or the most recent Editor.  May return null.
    public final Editor getOtherEditor(Editor editor)
    {
        Editor paired = getPairedEditor(editor);
        if (paired != null)
            return paired;

        return getPriorEditor(editor);
    }

    public final List<Editor> getPrimaryEditors()
    {
        List<Editor> ret = new ArrayList<Editor>(editors.size());
        for (Editor ed : editors)
            if (ed.getBuffer().isPrimary())
                ret.add(ed);
        return ret;
    }

    public final boolean contains(Editor ed)
    {
        return ed != null && editors.contains(ed);
    }

    // get the Editor showing Buffer
    public final Editor findEditor(final Buffer buf)
    {
        for (Editor ed : editors)
            if (ed.getBuffer() == buf)
                return ed;
        return null;
    }

    // check buf is in the list of editors
    private static final boolean hasBuffer(final List<Editor> editors, final Buffer buf)
    {
        for (Editor ed : editors)
            if (ed.getBuffer() == buf)
                return true;
        return false;
    }

    public void updateTitle()
    {
        for (Editor ed : editors)
            ed.updateLocation();
    }

    private Sidebar sidebar;

    public final Sidebar getSidebar()
    {
        return sidebar;
    }

    private SplitPane sidebarSplitPane;

    public final SplitPane getSidebarSplitPane()
    {
        return sidebarSplitPane;
    }

    private SplitPane createSidebarSplitPane()
    {
        SplitPane splitPane =
            new SplitPane(SplitPane.HORIZONTAL_SPLIT,
                sidebar, getEditorPane());
        int dividerLocation =
            Editor.getSessionProperties().getSidebarWidth(this);
        splitPane.setDividerLocation(dividerLocation);
        splitPane.setBorder(null);
        return splitPane;
    }

    private void addSidebar()
    {
        if (sidebarSplitPane != null)
            getContentPane().remove(sidebarSplitPane);
        sidebar = new Sidebar(this);
        sidebarSplitPane = createSidebarSplitPane();
        getContentPane().add(sidebarSplitPane, "Center");
        validate();
        currentEditor.setFocusToDisplay();
        sidebar.setUpdateFlag(SIDEBAR_ALL);
    }

    public final EditorPane getEditorPane()
    {
        return editorPane;
    }

    public void frameToggleSidebar()
    {
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

    public final StatusBar getStatusBar()
    {
        return statusBar;
    }

    public void repaintStatusBar()
    {
        if (statusBar != null)
            statusBar.repaint();
    }

    public void setStatusText(String text)
    {
        if (statusBar != null && text != null && !text.equals(statusBar.getText())) {
            statusBar.setText(text);
            statusBar.repaintNow();
        }
    }

    public boolean getShowToolbar()
    {
        return showToolbar;
    }

    public void setToolbar()
    {
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

    public void frameToggleToolbar()
    {
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

    public ToolBar getDefaultToolBar()
    {
        if (defaultToolBar == null)
            defaultToolBar = new DefaultToolBar(this);

        return defaultToolBar;
    }

    public void addToolbar(ToolBar tb)
    {
        Debug.assertTrue(toolbar == null);
        if (tb != null) {
            toolbar = tb;
            getContentPane().add(toolbar, "North");
            toolbar.repaint();
        }
        // Make sure toolbar doesn't steal focus.
        Runnable r = new Runnable() {
            public void run()
            {
                JComponent c = getFocusedComponent();
                if (c != null)
                    c.requestFocus();
            }
        };
        SwingUtilities.invokeLater(r);
    }

    public void maybeAddToolbar()
    {
        if (toolbar != null)
            return;
        ToolBar tb = currentEditor.getMode().getToolBar(this);
        if (tb != null)
            addToolbar(tb);
    }

    public void removeToolbar()
    {
        if (toolbar != null) {
            getContentPane().remove(toolbar);
            toolbar = null;
            getContentPane().validate();
        }
    }

    public void setMenu()
    {
        final Mode mode = currentEditor.getMode();
        final MenuBar oldMenuBar = (MenuBar) getJMenuBar();
        if (oldMenuBar == null ||
            Platform.isPlatformMacOSX() ||
            oldMenuBar.getMenuName() != mode.getMenuName())
        {
            setJMenuBar(mode.createMenuBar(this));
            validate();
        }
    }

    public void placeWindow()
    {
        final SessionProperties sessionProperties =
            Editor.getSessionProperties();
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
            adjustPlacementRunnable =
                new AdjustPlacementRunnable(this, extendedState);
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

    public void splitWindow()
    {
        splitWindow(currentEditor, false, true);
    }

    public void vsplitWindow()
    {
        splitWindow(currentEditor, true, true);
    }

    private void splitWindow(Editor ed, boolean vertical, boolean focusNewEditor)
    {
        if (!contains(ed))
            return;

        splitWindow(ed, ed.getBuffer(), ed.getBuffer(), 0.5f, vertical, focusNewEditor);
    }

    // Split the Editor into two and set the buffers for the current and new Editors.
    // UNDONE: Set divider location
    private void splitWindow(Editor ed, Buffer primary, Buffer secondary, float split, boolean vertical, boolean switchWindows)
    {
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

    public final boolean isEditorSibling(Editor ed, Editor other)
    {
        if (ed == other)
            return false;

        List<Editor> siblings = editorPane.getSiblings(ed);
        return siblings.contains(other);
    }

    // returns true if 'ed' is top-left of 'other'.
    public final boolean isEditorTopLeftOf(Editor ed, Editor other)
    {
        if (ed == other)
            return false;

        // If the 'ed' editor is found before 'other' editor, 'ed' is either to the top or to the left.
        List<Editor> siblings = editorPane.getSiblings(ed);
        for (Editor sibling : siblings)
        {
            if (sibling == ed)
                return true;
            if (sibling == other)
                return false;
        }

        // we should always find ed in it's own sibling list
        Debug.bug("editor wasn't found in it's own sibling list");
        return false;
    }


    public void switchToBuffer(final Editor fromEditor, final Buffer buf)
    {
        // We're either switching in a paired buffer or switching out
        // a paired buffer (or both).
        Debug.bugIfNot(buf.isPaired() ||
            (getEditorCount() > 1 && fromEditor.getBuffer().isPaired()));
        final Buffer primary;
        final Buffer secondary;
        if (buf.isPrimary()) {
            primary = buf;
            secondary = buf.getSecondary();
        } else {
            Debug.bugIfNot(buf.isSecondary());
            primary = buf.getPrimary();
            Debug.bugIfNot(primary != null);
            secondary = buf;
        }

        if (getEditorCount() > 1) {
            // Window is already split.
            // Get other editor and determine which are primary and secondary editors.
            Editor otherEditor = fromEditor.getOtherEditor();
            if (Editor.isDebugEnabled()) {
                Debug.bugIf(otherEditor == null);
                Debug.bugIf(fromEditor == otherEditor);
                Debug.bugIfNot(isEditorSibling(fromEditor, otherEditor));
            }

            // the primary editor is ether the primary buffer's editor or the top-left most editor
            Editor primaryEditor, secondaryEditor;
            if (fromEditor.getBuffer().isPairedTo(otherEditor.getBuffer())) {
                // editor buffers are paired
                if (fromEditor.getBuffer().isPrimary()) {
                    primaryEditor = fromEditor;
                    secondaryEditor = otherEditor;
                } else {
                    primaryEditor = otherEditor;
                    secondaryEditor = fromEditor;
                }
            } else {
                // editor buffers are not paired: consider the top-left most editor the 'primary' editor
                if (isEditorTopLeftOf(fromEditor, otherEditor)) {
                    primaryEditor = fromEditor;
                    secondaryEditor = otherEditor;
                } else {
                    primaryEditor = otherEditor;
                    secondaryEditor = fromEditor;
                }
            }

            if (secondary != null) {
                // Activate primary buffer in primary editor.
                // Activate secondary buffer in secondary editor.
                if (primaryEditor.getBuffer() != primary)
                    primaryEditor.activate(primary);
                if (secondaryEditor.getBuffer() != secondary)
                    secondaryEditor.activate(secondary);
                // UNDONE: Adjust split pane divider location.
                /*
                if (editorPane instanceof SplitPane) {
                    SplitPane sp = (SplitPane) editorPane;
                    int height = sp.getHeight();
                    float split = secondary.getSplit();
                    int dividerLocation =
                        (int)(height * (1 - split) - sp.getDividerSize());
                    sp.setDividerLocation(dividerLocation);
                }
                */
                Editor.setCurrentEditor(buf == primary ? primaryEditor : secondaryEditor);
                primaryEditor.updateDisplay();
                secondaryEditor.updateDisplay();
            } else {
                // No secondary.
                Debug.bugIfNot(secondary == null);
                // We don't need a split window. Close secondary editor.
                // Save information about the buffer in the editor that we're
                // going to close.
                secondaryEditor.deactivate();
                unsplitInternal(primaryEditor, secondaryEditor);
                // Activate primary buffer in primary editor.
                primaryEditor.activate(primary);
            }
        } else {
            // Window is not split.
            Debug.bugIfNot(getEditorCount() == 1);
            if (secondary != null) {
                // Split the editor, activate primary in fromEditor and secondary in the new editor window.
                // Focus on the selected buffer.
                // CONSIDER: Add option to specify preferred split direction
                boolean switchWindows = buf == secondary;
                splitWindow(fromEditor, primary, secondary, secondary.getSplit(), false, switchWindows);
            } else {
                // Only one editor, no secondary.
                Debug.bugIfNot(editors.get(0) != fromEditor);
                Debug.bugIfNot(editors.get(0) != null);
                Debug.bugIfNot(editors.get(1) == null);
                Debug.bugIfNot(secondary == null);
                // Activate primary in editor 0.
                fromEditor.activate(primary);
            }
        }

        buf.setLastActivated(System.currentTimeMillis());
        if (Editor.isDebugEnabled()) {
            if (buf.isPrimary()) {
                Debug.bugIfNot(hasBuffer(getPrimaryEditors(), buf), "Editor not found for primary buffer");
            } else {
                Debug.bugIfNot(buf.isSecondary());
                Buffer bufPrimary = buf.getPrimary();
                Debug.bugIfNot(primary != null);
                Debug.bugIfNot(hasBuffer(getPrimaryEditors(), bufPrimary), "Editor not found for primary buffer");
            }
        }
    }

    // UNDONE: enlarge window by N lines.
    public void enlargeWindow(Editor editor, int n)
    {
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
    public void setWindowHeight(Editor editor, int n)
    {
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

    public final Editor activateInOtherWindow(Editor editor, Buffer buffer)
    {
        // Switch to other window.
        return openInOtherWindow(editor, buffer, 0.5F, true);
    }

    public final Editor activateInOtherWindow(Editor editor, Buffer buffer,
        float split)
    {
        // Switch to other window.
        return openInOtherWindow(editor, buffer, split, true);
    }

    public final Editor displayInOtherWindow(Editor editor, Buffer buffer)
    {
        // Don't switch to other window.
        return openInOtherWindow(editor, buffer, 0.5F, false);
    }

    // UNDONE: Set divider location
    private Editor openInOtherWindow(Editor editor, Buffer buffer, float split,
        boolean switchWindows)
    {
        editor.saveView();
        Editor otherEditor = getOtherEditor(editor);
        if (otherEditor == null) {
            Debug.assertTrue(!hasSplit() && editor == editors.get(0));
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

    public void closeEditor(Editor editor)
    {
        if (!hasSplit())
            return;
        if (!contains(editor))
            return;
        promoteSecondaryBuffers();
        Editor keep = getOtherEditor(editor);
        Editor kill = editor;
        unsplitInternal(keep, kill);
    }

    public void unsplitWindow()
    {
        if (!hasSplit())
            return;
        promoteSecondaryBuffers();
        Editor keep = currentEditor;
        Editor kill = getOtherEditor(currentEditor);
        unsplitInternal(keep, kill);
    }

    public void unsplitWindowKeepOther()
    {
        closeEditor(currentEditor);
    }

    public void promoteSecondaryBuffers()
    {
        for (Editor editor : editors) {
            Buffer buffer = editor.getBuffer();
            if (buffer.isSecondary())
                buffer.promote();
        }
    }

    public void unsplitAll(final Editor keep)
    {
        if (!hasSplit())
            return;
        if (!contains(keep))
            return;
        Editor.getSessionProperties().saveSidebarState(this);
        editorPane.root(keep);
        validate();
        List<Editor> kill = new ArrayList<Editor>(editors);
        kill.remove(keep);
        unsplitInternal(keep, kill);
    }

    private void unsplitInternal(final Editor keep, final Editor kill)
    {
        Editor.getSessionProperties().saveSidebarState(this);
        editorPane.unsplit(kill);
        validate();
        unsplitInternal(keep, Collections.singletonList(kill));
    }

    private void unsplitInternal(final Editor keep, final Collection<Editor> kill)
    {
        Editor.removeEditors(kill);
        editors.removeAll(kill);
        Debug.bugIfNot(editors.contains(keep));
        Buffer buffer = keep.getBuffer();
        if (buffer.isSecondary())
            buffer.promote();
        if (keep.getLocationBar() == null)
            keep.addLocationBar();
        Editor.setCurrentEditor(keep);
        keep.setUpdateFlag(REFRAME);
        keep.reframe();
        restoreFocus();
        statusBar.repaint();
        updateControls();
    }

    // Unsplit if any two Editor siblings are showing the exactly same thing.
    public void coalesceEditors(Editor editor)
    {
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

    public void updateControls()
    {
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

    public final boolean isActive()
    {
        return active;
    }

    public void reactivate()
    {
        if (currentEditor.getBuffer() == null)
            return;
        boolean changed = false;
        for (BufferIterator it = new BufferIterator(); it.hasNext();) {
            if (currentEditor.reactivate(it.nextBuffer()))
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

    public void windowActivated(WindowEvent e)
    {
        active = true;
        Editor.setCurrentEditor(currentEditor);
        setFocus(currentEditor.getDisplay());
        repaint();
        // 1.4.0-rc hangs if we call reactivate() directly here.
        Runnable r = new Runnable() {
            public void run()
            {
                reactivate();
            }
        };
        SwingUtilities.invokeLater(r);
    }

    public void windowDeactivated(WindowEvent e)
    {
        active = false;
        // Show/hide caret.
        for (Editor editor : editors)
            editor.repaint();
    }

    public void windowOpened(WindowEvent e)
    {
        if (adjustPlacementRunnable != null) {
            adjustPlacementRunnable.run();
            adjustPlacementRunnable = null;
        }
    }

    public void windowClosing(WindowEvent e)
    {
        editors.get(0).killFrame();
    }

    public void windowClosed(WindowEvent e)
    {
    }

    public void windowIconified(WindowEvent e)
    {
    }

    public void windowDeiconified(WindowEvent e)
    {
    }

    public void windowStateChanged(WindowEvent e)
    {
        int newState = e.getNewState();
        if (newState == 0) {
            // Not maximized.
            if (rect != null)
                setBounds(rect);
        }
        storeExtendedState(newState);
    }

    private JComponent focusedComponent;

    public void setFocus(JComponent c)
    {
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

    public JComponent getFocusedComponent()
    {
        return focusedComponent;
    }

    private static final Cursor waitCursor =
        Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR);

    public final void setWaitCursor()
    {
        setCursor(waitCursor);
        for (Editor ed : editors)
            ed.setWaitCursor();
    }

    public final void setDefaultCursor()
    {
        setCursor(Cursor.getDefaultCursor());
        for (Editor ed : editors)
            ed.setDefaultCursor();
    }

    public void resetDisplay()
    {
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

            if (Platform.isJava14()) {
                // With Sun Java 1.4.0 FCS, if the following 3 lines of code
                // are removed, focus is lost when this method is called from
                // Buffer.saveLocal() after the preferences file is saved.
                // When this happens, focus can be recovered by switching to a
                // different Sawfish workspace and back again, at which point
                // any keystrokes that were lost are replayed accurately into
                // the buffer.

                // Not that it makes any sense to do this... ;)
                getContentPane().add(getEditorPane(), "Center");
                currentEditor.getDisplay().requestFocus();
                getContentPane().remove(getEditorPane());
            }

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

    public static final void restoreFocus()
    {
        Editor.restoreFocus();
    }

    private Object folderTree;

    public final Object getFolderTree()
    {
        return folderTree;
    }

    public final void setFolderTree(Object obj)
    {
        folderTree = obj;
    }

    public void componentResized(ComponentEvent e)
    {
        if (extendedState != 6) {
            // Not maximized.
            rect = getBounds();
        }
    }

    public void componentMoved(ComponentEvent e)
    {
        if (extendedState != 6) {
            // Not maximized.
            rect = getBounds();
        }
    }

    public void componentShown(ComponentEvent e) {}

    public void componentHidden(ComponentEvent e) {}

    public void focusGained(FocusEvent e)
    {
        currentEditor.setFocusToDisplay();
    }

    public void focusLost(FocusEvent e) {}
}
