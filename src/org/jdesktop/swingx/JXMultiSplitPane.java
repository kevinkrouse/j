/*
 * $Id: JXMultiSplitPane.java 3475 2009-08-28 08:30:47Z kleopatra $
 *
 * Copyright 2004 Sun Microsystems, Inc., 4150 Network Circle,
 * Santa Clara, California 95054, U.S.A. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */

package org.jdesktop.swingx;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JPanel;
import javax.swing.event.MouseInputAdapter;
import org.jdesktop.swingx.MultiSplitLayout.Divider;
import org.jdesktop.swingx.MultiSplitLayout.Node;

/**
 *
 * <p>
 * All properties in this class are bound: when a properties value
 * is changed, all PropertyChangeListeners are fired.
 * 
 * @author Hans Muller
 * @author Luan O'Carroll
 */
public class JXMultiSplitPane extends JPanel {
    private AccessibleContext accessibleContext = null;
    private boolean continuousLayout = true;

    /**
     * Creates a MultiSplitPane with it's LayoutManager set to 
     * to an empty MultiSplitLayout.
     */
    public JXMultiSplitPane() {
        this(new MultiSplitLayout());
    }

    /**
     * Creates a MultiSplitPane.
     * @param layout the new split pane's layout
     */
    public JXMultiSplitPane(MultiSplitLayout layout) {
        super(layout);
        InputHandler inputHandler = new InputHandler();
        addMouseListener(inputHandler);
        addMouseMotionListener(inputHandler);
        addKeyListener(inputHandler);
        setFocusable(true);
    }

    /** 
     * A convenience method that returns the layout manager cast 
     * to MutliSplitLayout.
     * 
     * @return this MultiSplitPane's layout manager
     * @see java.awt.Container#getLayout
     * @see #setModel
     */
    public final MultiSplitLayout getMultiSplitLayout() {
        return (MultiSplitLayout) getLayout();
    }

    /** 
     * A convenience method that sets the MultiSplitLayout model.
     * Equivalent to <code>getMultiSplitLayout.setModel(model)</code>
     * 
     * @param model the root of the MultiSplitLayout model
     * @see #getMultiSplitLayout
     * @see MultiSplitLayout#setModel
     */
    public final void setModel(Node model) {
        getMultiSplitLayout().setModel(model);
    }

    /** 
     * A convenience method that sets the MultiSplitLayout dividerSize
     * property. Equivalent to 
     * <code>getMultiSplitLayout().setDividerSize(newDividerSize)</code>.
     * 
     * @param dividerSize the value of the dividerSize property
     * @see #getMultiSplitLayout
     * @see MultiSplitLayout#setDividerSize
     */
    public final void setDividerSize(int dividerSize) {
        getMultiSplitLayout().setDividerSize(dividerSize);
    }

    /** 
     * A convenience method that returns the MultiSplitLayout dividerSize
     * property. Equivalent to 
     * <code>getMultiSplitLayout().getDividerSize()</code>.
     * 
     * @see #getMultiSplitLayout
     * @see MultiSplitLayout#getDividerSize
     */
    public final int getDividerSize() {
        return getMultiSplitLayout().getDividerSize();
    }

    /**
     * Sets the value of the <code>continuousLayout</code> property.
     * If true, then the layout is revalidated continuously while
     * a divider is being moved.  The default value of this property
     * is true.
     *
     * @param continuousLayout value of the continuousLayout property
     * @see #isContinuousLayout
     */
    public void setContinuousLayout(boolean continuousLayout) {
        boolean oldContinuousLayout = isContinuousLayout();
        this.continuousLayout = continuousLayout;
        firePropertyChange("continuousLayout", oldContinuousLayout, isContinuousLayout());
    }

    /**
     * Returns true if dragging a divider only updates
     * the layout when the drag gesture ends (typically, when the 
     * mouse button is released).
     *
     * @return the value of the <code>continuousLayout</code> property
     * @see #setContinuousLayout
     */
    public boolean isContinuousLayout() {
        return continuousLayout;
    }

    /** 
     * Returns the Divider that's currently being moved, typically
     * because the user is dragging it, or null.
     * 
     * @return the Divider that's being moved or null.
     */
    public Divider activeDivider() {
        return dragDivider;
    }

    /**
     * Paints the active divider black over the children while it is dragged
     * and the layout is not continuous.
     */
    @Override
    protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        Divider divider = activeDivider();
        Rectangle clip = g.getClipBounds();
        if (divider != null && !isContinuousLayout() && clip != null) {
            Rectangle bounds = divider.getBounds();
            if (bounds.intersects(clip)) {
                g.setColor(Color.black);
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
        }
    }

    private boolean dragUnderway = false;
    private MultiSplitLayout.Divider dragDivider = null;
    private Rectangle initialDividerBounds = null;
    private boolean oldFloatingDividers = true;
    private int dragOffsetX = 0;
    private int dragOffsetY = 0;
    private int dragMin = -1;
    private int dragMax = -1;

    private void startDrag(int mx, int my) {
        requestFocusInWindow();
        MultiSplitLayout msl = getMultiSplitLayout();
        MultiSplitLayout.Divider divider = msl.dividerAt(mx, my);
        if (divider != null) {
            MultiSplitLayout.Node prevNode = divider.previousSibling();
            MultiSplitLayout.Node nextNode = divider.nextSibling();
            if ((prevNode == null) || (nextNode == null)) {
                dragUnderway = false;
            } else {
                initialDividerBounds = divider.getBounds();
                dragOffsetX = mx - initialDividerBounds.x;
                dragOffsetY = my - initialDividerBounds.y;
                dragDivider = divider;

                Rectangle prevNodeBounds = prevNode.getBounds();
                Rectangle nextNodeBounds = nextNode.getBounds();
                if (dragDivider.isVertical()) {
                    dragMin = prevNodeBounds.x;
                    dragMax = nextNodeBounds.x + nextNodeBounds.width;
                    dragMax -= dragDivider.getBounds().width;
                    if (msl.getLayoutMode() == MultiSplitLayout.USER_MIN_SIZE_LAYOUT)
                        dragMax -= msl.getUserMinSize();
                } else {
                    dragMin = prevNodeBounds.y;
                    dragMax = nextNodeBounds.y + nextNodeBounds.height;
                    dragMax -= dragDivider.getBounds().height;
                    if (msl.getLayoutMode() == MultiSplitLayout.USER_MIN_SIZE_LAYOUT)
                        dragMax -= msl.getUserMinSize();
                }

                if (msl.getLayoutMode() == MultiSplitLayout.USER_MIN_SIZE_LAYOUT) {
                    dragMin = dragMin + msl.getUserMinSize();
                } else {
                    if (dragDivider.isVertical()) {
                        dragMin = Math.max(dragMin, dragMin + getMinNodeSize(msl, prevNode).width);
                        dragMax = Math.min(dragMax, dragMax - getMinNodeSize(msl, nextNode).width);

                        Dimension maxDim = getMaxNodeSize(msl, prevNode);
                        if (maxDim != null)
                            dragMax = Math.min(dragMax, prevNodeBounds.x + maxDim.width);
                    } else {
                        dragMin = Math.max(dragMin, dragMin + getMinNodeSize(msl, prevNode).height);
                        dragMax = Math.min(dragMax, dragMax - getMinNodeSize(msl, nextNode).height);

                        Dimension maxDim = getMaxNodeSize(msl, prevNode);
                        if (maxDim != null)
                            dragMax = Math.min(dragMax, prevNodeBounds.y + maxDim.height);
                    }
                }

                oldFloatingDividers = getMultiSplitLayout().getFloatingDividers();
                getMultiSplitLayout().setFloatingDividers(false);
                dragUnderway = true;
            }
        } else {
            dragUnderway = false;
        }
    }

    /**
     * Set the maximum node size. This method can be overridden to limit the 
     * size of a node during a drag operation on a divider. When implementing 
     * this method in a subclass the node instance should be checked, for 
     * example:
     * <code>
     * class MyMultiSplitPane extends JXMultiSplitPane
     * {
     *   protected Dimension getMaxNodeSize( MultiSplitLayout msl, Node n )
     *   {
     *     if (( n instanceof Leaf ) && ((Leaf)n).getName().equals( "top" ))
     *       return msl.maximumNodeSize( n );
     *     return null;
     *   }
     * }
     * </code>
     * @param msl the MultiSplitLayout used by this pane
     * @param n the node being resized
     * @return the maximum size or null (by default) to ignore the maximum size.
     */
    protected Dimension getMaxNodeSize(MultiSplitLayout msl, Node n) {
        return null;
    }

    /**
     * Set the minimum node size. This method can be overridden to limit the 
     * size of a node during a drag operation on a divider. 
     * @param msl the MultiSplitLayout used by this pane
     * @param n the node being resized
     * @return the maximum size or null (by default) to ignore the maximum size.
     */
    protected Dimension getMinNodeSize(MultiSplitLayout msl, Node n) {
        return msl.minimumNodeSize(n);
    }

    private void repaintDragLimits() {
        Rectangle damageR = dragDivider.getBounds();
        if (dragDivider.isVertical()) {
            damageR.x = dragMin;
            damageR.width = dragMax - dragMin;
        } else {
            damageR.y = dragMin;
            damageR.height = dragMax - dragMin;
        }
        repaint(damageR);
    }

    private void updateDrag(int mx, int my) {
        if (!dragUnderway) {
            return;
        }
        Rectangle oldBounds = dragDivider.getBounds();
        Rectangle bounds = new Rectangle(oldBounds);
        if (dragDivider.isVertical()) {
            bounds.x = mx - dragOffsetX;
            bounds.x = Math.max(bounds.x, dragMin);
            bounds.x = Math.min(bounds.x, dragMax);
        } else {
            bounds.y = my - dragOffsetY;
            bounds.y = Math.max(bounds.y, dragMin);
            bounds.y = Math.min(bounds.y, dragMax);
        }
        dragDivider.setBounds(bounds);
        if (isContinuousLayout()) {
            revalidate();
            repaintDragLimits();
        } else {
            repaint(oldBounds.union(bounds));
        }
    }

    private void clearDragState() {
        dragDivider = null;
        initialDividerBounds = null;
        oldFloatingDividers = true;
        dragOffsetX = dragOffsetY = 0;
        dragMin = dragMax = -1;
        dragUnderway = false;
    }

    private void finishDrag(int x, int y) {
        if (dragUnderway) {
            clearDragState();
            if (!isContinuousLayout()) {
                revalidate();
                repaint();
            }
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
    }

    private void cancelDrag() {
        if (dragUnderway) {
            dragDivider.setBounds(initialDividerBounds);
            getMultiSplitLayout().setFloatingDividers(oldFloatingDividers);
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            repaint();
            revalidate();
            clearDragState();
        }
    }

    private void updateCursor(int x, int y, boolean show) {
        if (dragUnderway) {
            return;
        }
        int cursorID = Cursor.DEFAULT_CURSOR;
        if (show) {
            MultiSplitLayout.Divider divider = getMultiSplitLayout().dividerAt(x, y);
            if (divider != null) {
                cursorID = (divider.isVertical()) ? Cursor.E_RESIZE_CURSOR : Cursor.N_RESIZE_CURSOR;
            }
        }
        setCursor(Cursor.getPredefinedCursor(cursorID));
    }

    private class InputHandler extends MouseInputAdapter implements KeyListener {

        @Override
        public void mouseEntered(MouseEvent e) {
            updateCursor(e.getX(), e.getY(), true);
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            updateCursor(e.getX(), e.getY(), true);
        }

        @Override
        public void mouseExited(MouseEvent e) {
            updateCursor(e.getX(), e.getY(), false);
        }

        @Override
        public void mousePressed(MouseEvent e) {
            startDrag(e.getX(), e.getY());
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            finishDrag(e.getX(), e.getY());
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            updateDrag(e.getX(), e.getY());
        }

        @Override
        public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                cancelDrag();
            }
        }

        @Override
        public void keyReleased(KeyEvent e) {}

        @Override
        public void keyTyped(KeyEvent e) {}
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleMultiSplitPane();
        }
        return accessibleContext;
    }

    protected class AccessibleMultiSplitPane extends AccessibleJPanel {
        @Override
        public AccessibleRole getAccessibleRole() {
            return AccessibleRole.SPLIT_PANE;
        }
    }
}
