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

import java.awt.Component;
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
    // The buffer the lower half's tree is of, which titles it if the tree doesn't.
    private Buffer bottomBuffer;
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
        divider = Editor.getSessionProperties().getSidebarDividerLocation(frame);
        splitPane.setDividerLocation(divider);
        add(splitPane);
        setPanels(frame.sidebarTop, frame.sidebarTree);
    }

    /** Hidden or replaced: the Changes tab lets go of its status; its frame keeps the panels. */
    public void dispose() {
        if (changesShown)
            changesTree.shown(false);
        changesShown = false;
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
        setPanels(tab, frame.sidebarTree);
        frame.setFocus(getTopView());
    }

    /** Its panels: the upper half's, BUFFERS or CHANGES, NONE for none, and the lower half's tree. */
    public static final int NONE = -1;

    public static final int BUFFERS = 0;

    public static final int CHANGES = 1;

    public static final int TREE = 2;

    /**
     * The panels a sidebar shows: its upper half's, BUFFERS, CHANGES or
     * NONE, and whether its lower half's tree shows.
     */
    record Panels(int top, boolean tree) {
        /** Whether it shows none. */
        boolean isEmpty() {
            return top == NONE && !tree;
        }

        /** With panel, BUFFERS, CHANGES or TREE, shown, or hidden if it shows; Buffers and Changes in turn. */
        Panels toggle(int panel) {
            if (panel == TREE)
                return new Panels(top, !tree);
            return new Panels(top == panel ? NONE : panel, tree);
        }

        /** With panel shown, as the sidebar shows again for it. */
        Panels with(int panel) {
            return panel == TREE ? new Panels(top, true) : new Panels(panel, tree);
        }
    }

    /** A panel of the upper half as the session's properties keep it. */
    static String topName(int top) {
        return switch (top) {
            case CHANGES -> "changes";
            case NONE -> "none";
            default -> "buffers";
        };
    }

    /** The panel of the upper half name keeps; Buffers for none or another. */
    static int topOf(String name) {
        return "changes".equals(name) ? CHANGES : "none".equals(name) ? NONE : BUFFERS;
    }

    // Where the halves divide, as last seen with both shown.
    private int divider;
    // Whether the Changes tab's tree was last told it shows.
    private boolean changesShown;

    /**
     * Shows the upper half's panel top, BUFFERS or CHANGES, or hides the
     * half for NONE, and the lower half's tree if tree; its frame keeps
     * them. With one half hidden the other fills the sidebar.
     */
    public void setPanels(int top, boolean tree) {
        if (topPanel.isVisible() && bottomPanel.isVisible())
            divider = splitPane.getDividerLocation();
        frame.sidebarTop = top;
        frame.sidebarTree = tree;
        if (top != NONE)
            showTab(top);
        // Told only as it shows or hides: showing again selects the buffer's file.
        if ((top == CHANGES) != changesShown) {
            changesShown = top == CHANGES;
            changesTree.shown(changesShown);
        }
        // A half hidden with the focus in it: the focus back to the text.
        final Component focused = frame.getFocusedComponent();
        if (focused != null
                && (top == NONE && SwingUtilities.isDescendingFrom(focused, topPanel)
                        || !tree && SwingUtilities.isDescendingFrom(focused, bottomPanel)))
            frame.getCurrentEditor().setFocusToDisplay();
        topPanel.setVisible(top != NONE);
        bottomPanel.setVisible(tree);
        if (top != NONE && tree)
            splitPane.setDividerLocation(divider);
        splitPane.revalidate();
        splitPane.repaint();
        frame.repaintStatusBar();
    }

    // The upper half's view the tab's: the buffer list or the Changes tab's tree.
    private void showTab(int tab) {
        if (tab == CHANGES && changesTree == null)
            changesTree = new ChangesTree(this, frame.changesKept);
        if (tab == tabs.shown() && topScrollPane.getViewport().getView() == getView(tab))
            return;
        tabs.show(tab);
        topScrollPane.setViewportView(getView(tab));
        topPanel.validate();
        topPanel.repaint();
    }

    private JComponent getView(int tab) {
        return tab == CHANGES ? changesTree : bufferTree;
    }

    /** The Changes tab's tree, or null before it is first shown. */
    public ChangesTree getChangesTree() {
        return changesTree;
    }

    // A click on a tab's title: shown; the Changes tab's shown already, read again.
    private void chooseTab(int tab) {
        if (tab == CHANGES && tabs.shown() == CHANGES && changesTree != null)
            changesTree.again();
        setPanels(tab, frame.sidebarTree);
    }

    /** What the upper half shows, the buffer list or the Changes tab's tree; null with it hidden. */
    public JComponent getTopView() {
        return topPanel.isVisible() ? getView(tabs.shown()) : null;
    }

    /** The upper half's view focused, if it shows, as Tab from the lower half goes. */
    public void focusTopView() {
        final JComponent view = getTopView();
        if (view != null)
            frame.setFocus(view);
    }

    /** The lower half's tree focused, if it shows, as Tab from the upper half goes. */
    public void focusBottomComponent() {
        if (bottomPanel.isVisible() && bottomComponent != null)
            frame.setFocus((JComponent) bottomComponent);
    }

    public void activateNavigationComponent() {
        if (!frame.sidebarTree)
            setPanels(frame.sidebarTop, true);
        if (bottomComponent != null)
            frame.setFocus((JComponent) bottomComponent);
    }

    /** Where the halves divide: with one hidden, where they did with both shown. */
    public int getDividerLocation() {
        return topPanel.isVisible() && bottomPanel.isVisible() ? splitPane.getDividerLocation() : divider;
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
        bottomBuffer = editor.getBuffer();
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
        if (changesTree != null && frame.sidebarTop == CHANGES)
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
                    // A tree of a buffer with no file, as a comparison's side,
                    // titled by the buffer: the label row stays.
                    final String label = bottomComponent.getLabelText();
                    bottomPanel.setLabelText(label != null && !label.isEmpty() ? label : bottomBuffer.getTitle());
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
