/*
 * Sidebar.java
 *
 * Copyright (C) 2000-2003 Peter Graves
 * Copyright (C) 2012-2026 Kevin Krouse
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

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.armedbear.j.vcs.status.ChangesTree;

public final class Sidebar extends JComponent {
    private final Frame frame;
    private final SplitPane splitPane;
    private final SidebarPanel topPanel;
    private final SidebarPanel bottomPanel;
    private final SidebarBufferTree bufferTree;
    // The upper half's tabs, Buffers and Changes, and what it scrolls.
    private final SidebarTabs tabs;
    private final JScrollPane topScrollPane;
    // The Changes tab's tree, made as it is first shown.
    private ChangesTree changesTree;

    private NavigationComponent bottomComponent;
    private int updateFlag;

    public Sidebar(Frame frame) {
        this.frame = frame;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        topPanel = new SidebarPanel(this);
        bufferTree = new SidebarBufferTree(this);
        topScrollPane = new JScrollPane(bufferTree);
        topScrollPane.setAlignmentX(LEFT_ALIGNMENT);
        topScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        topScrollPane.setBorder(BorderFactory.createEmptyBorder());
        tabs = new SidebarTabs(this::chooseTab, "Buffers", "Changes");
        topPanel.addScrollPane(topScrollPane, tabs);
        bottomPanel = new SidebarPanel(this);
        splitPane = new SplitPane(SplitPane.VERTICAL_SPLIT, topPanel, bottomPanel);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setDividerLocation(Editor.getSessionProperties().getSidebarDividerLocation(frame));
        add(splitPane);
        showTab(frame.sidebarTab);
    }

    /** Hidden or replaced: its tab kept for the next, the Changes tab letting go of its status. */
    public void dispose() {
        frame.sidebarTab = tabs.shown();
        if (changesTree != null)
            changesTree.shown(false);
    }

    public final Frame getFrame() {
        return frame;
    }

    public final Editor getEditor() {
        return frame.getCurrentEditor();
    }

    public final SidebarBufferTree getBufferList() {
        return bufferTree;
    }

    public final SidebarBufferTree getBufferTree() {
        return bufferTree;
    }

    public NavigationComponent getBottomComponent() {
        return bottomComponent;
    }

    public void activateBufferList() {
        activate(BUFFERS);
    }

    /** The upper half's tab, BUFFERS or CHANGES, shown and focused. */
    public void activate(int tab) {
        showTab(tab);
        frame.setFocus(getTopView());
    }

    /** The upper half's tabs. */
    public static final int BUFFERS = 0;

    public static final int CHANGES = 1;

    /** The upper half shows tab, BUFFERS or CHANGES. */
    public void showTab(int tab) {
        if (tab == tabs.shown())
            return;
        tabs.show(tab);
        if (tab == CHANGES) {
            if (changesTree == null)
                changesTree = new ChangesTree(this, frame.changesOpen);
            topScrollPane.setViewportView(changesTree);
            changesTree.shown(true);
        } else {
            if (changesTree != null)
                changesTree.shown(false);
            topScrollPane.setViewportView(bufferTree);
        }
        topPanel.validate();
        topPanel.repaint();
    }

    // A click on a tab's title: shown; the Changes tab's shown already, read again.
    private void chooseTab(int tab) {
        if (tab == CHANGES && tabs.shown() == CHANGES)
            changesTree.again();
        showTab(tab);
    }

    /** What the upper half shows: the buffer list, or the Changes tab's tree. */
    public JComponent getTopView() {
        return tabs.shown() == CHANGES ? changesTree : bufferTree;
    }

    public void activateNavigationComponent() {
        if (bottomComponent != null)
            frame.setFocus((JComponent) bottomComponent);
    }

    public int getDividerLocation() {
        if (splitPane == null)
            return -1;

        return splitPane.getDividerLocation();
    }

    /** The lower half's label, as when its component's own text changes. */
    public final void setBottomLabelText(String s) {
        bottomPanel.setLabelText(s);
    }

    public static void repaintBufferListInAllFrames() {
        for (int i = 0; i < Editor.getFrameCount(); i++) {
            Frame frame = Editor.getFrame(i);
            if (frame != null) {
                Sidebar sidebar = frame.getSidebar();
                if (sidebar != null)
                    sidebar.getBufferList().repaint();
            }
        }
    }

    public void setBuffer() {
        if (bufferTree != null) {
            Buffer buffer = frame.getCurrentEditor().getBuffer();
            if (buffer != bufferTree.getSelectedBuffer())
                bufferTree.setSelectedBuffer(buffer);
        }
    }

    public void updatePosition() {
        if (bottomComponent != null)
            bottomComponent.updatePosition();
    }

    public static final boolean isTaggable(Buffer buffer) {
        Mode mode = buffer.getMode();
        return mode != null && mode.isTaggable();
    }

    public synchronized final void setUpdateFlag(int mask) {
        updateFlag |= mask;
        if (bufferTree != null)
            bufferTree.setUpdateFlag(updateFlag & SIDEBAR_BUFFER_LIST_ALL);
    }

    public static void setUpdateFlagInAllFrames(int mask) {
        for (int i = 0; i < Editor.getFrameCount(); i++) {
            Frame frame = Editor.getFrame(i);
            Sidebar sidebar = frame.getSidebar();
            if (sidebar != null)
                sidebar.setUpdateFlag(mask);
        }
    }

    private void setBottomComponent() {
        if (!SwingUtilities.isEventDispatchThread())
            Debug.bug("Sidebar.setBottomComponent() called from background thread!");
        if (bottomComponent != null) {
            Editor editor = frame.getCurrentEditor();
            Buffer buffer = editor.getBuffer();
            if (frame.getEditorCount() > 1 && buffer.isTransient()) {
                editor = editor.getOtherEditor();
                buffer = editor.getBuffer();
            }
            if (isTaggable(buffer) && bottomComponent instanceof SidebarTagList) {
                SidebarTagList list = (SidebarTagList) bottomComponent;
                list.setEditor(editor);
                if (list.getBuffer() == buffer)
                    return;
            } else {
                View view = editor.getCurrentView();
                if (view != null && bottomComponent == view.getSidebarComponent())
                    return;
            }
            // Reaching here, we need to remove the old bottom component.
            bottomComponent = null;
            bottomPanel.removeAll();
        }
        Debug.assertTrue(bottomComponent == null);
        final Editor editor = getEditor();
        bottomComponent = editor.getMode().getSidebarComponent(editor);
        if (bottomComponent != null) {
            // The mode caches this component on the View, so it can predate a
            // uiScale change and still be carrying the old font and row
            // heights in its renderer.
            UIScale.refresh((JComponent) bottomComponent);
            JScrollPane scrollPane = new JScrollPane((JComponent) bottomComponent);
            if (bottomComponent instanceof SidebarList)
                scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scrollPane.setAlignmentX(LEFT_ALIGNMENT);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            bottomPanel.addScrollPane(scrollPane);
            bottomPanel.validate();
        } else
            bottomPanel.repaint();
    }

    public synchronized void refreshSidebar() {
        // The current buffer's repositories, if the Changes tab shows them.
        if (changesTree != null && tabs.shown() == CHANGES)
            changesTree.follow();
        if (updateFlag != 0) {
            if ((updateFlag & SIDEBAR_BUFFER_LIST_ALL) != 0) {
                final SidebarBufferTree bufferTree = getBufferTree();
                if (bufferTree != null && bufferTree != frame.getFocusedComponent())
                    bufferTree.updateBufferList();
                Buffer.checkVCSForAllBuffers(() -> {
                    SidebarBufferTree tree = getBufferTree();
                    if (tree != null)
                        tree.repaint();
                });
            }
            if (bottomComponent == null || bottomComponent != frame.getFocusedComponent()) {
                setBottomComponent();
                if (bottomComponent != null) {
                    bottomPanel.setLabelText(bottomComponent.getLabelText());
                    // This might start a thread.
                    bottomComponent.refresh();
                    if ((updateFlag & SIDEBAR_POSITION) != 0)
                        bottomComponent.updatePosition();
                }
            }
            updateFlag = 0;
        }
    }

    public static void refreshSidebarInAllFrames() {
        if (!SwingUtilities.isEventDispatchThread())
            Debug.bug("refreshSidebarInAllFrames() called from background thread!");
        for (int i = 0; i < Editor.getFrameCount(); i++) {
            Frame frame = Editor.getFrame(i);
            Sidebar sidebar = frame.getSidebar();
            if (sidebar != null)
                sidebar.refreshSidebar();
        }
    }
}
