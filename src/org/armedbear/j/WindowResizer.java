/*
 * WindowResizer.java
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

package org.armedbear.j;

import static java.awt.Cursor.E_RESIZE_CURSOR;
import static java.awt.Cursor.N_RESIZE_CURSOR;
import static java.awt.Cursor.NE_RESIZE_CURSOR;
import static java.awt.Cursor.NW_RESIZE_CURSOR;
import static java.awt.Cursor.S_RESIZE_CURSOR;
import static java.awt.Cursor.SE_RESIZE_CURSOR;
import static java.awt.Cursor.SW_RESIZE_CURSOR;
import static java.awt.Cursor.W_RESIZE_CURSOR;
import static java.awt.Cursor.getPredefinedCursor;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowStateListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;

/**
 * Resizes j's undecorated frames by dragging invisible strips along the window
 * edges, since the operating system no longer provides a resize border.
 *
 * <p>Four transparent strips, {@value #BORDER_DRAG_THICKNESS} logical pixels
 * thick with {@value #CORNER_DRAG_WIDTH} pixel corners, sit on the root pane
 * above the glass pane. Each shows the right resize cursor and drags the window
 * bounds. A fifth full-window component keeps the cursor stable while dragging
 * inward, so it does not flicker as the mouse crosses content.
 *
 * <p>Each drag event computes the new bounds as a delta from a snapshot taken at
 * press, never from the window's current bounds: if the window manager clamps the
 * window mid-drag, a current-bounds baseline drifts and the error compounds into
 * runaway growth.
 *
 * <p>Ported from FlatLAF's {@code FlatWindowResizer.WindowResizer}, with the
 * internal-frame and native-Linux variants dropped.
 */
public final class WindowResizer implements PropertyChangeListener, WindowStateListener {
    /** How far in from an edge a drag starts, in logical pixels. */
    private static final int BORDER_DRAG_THICKNESS = 4;
    /** How wide the corner drag zones are, in logical pixels. */
    private static final int CORNER_DRAG_WIDTH = 9;
    /** Smallest a window may be dragged to, in logical pixels. */
    private static final int MIN_WIDTH = 150;
    private static final int MIN_HEIGHT = 50;

    private final JRootPane rootPane;
    private Window window;

    private final DragBorder top;
    private final DragBorder bottom;
    private final DragBorder left;
    private final DragBorder right;
    private final JComponent center;

    public WindowResizer(JRootPane rootPane) {
        this.rootPane = rootPane;

        top = new DragBorder(NW_RESIZE_CURSOR, N_RESIZE_CURSOR, NE_RESIZE_CURSOR);
        bottom = new DragBorder(SW_RESIZE_CURSOR, S_RESIZE_CURSOR, SE_RESIZE_CURSOR);
        left = new DragBorder(NW_RESIZE_CURSOR, W_RESIZE_CURSOR, SW_RESIZE_CURSOR);
        right = new DragBorder(NE_RESIZE_CURSOR, E_RESIZE_CURSOR, SE_RESIZE_CURSOR);

        // Above the glass pane so the strips get the mouse first.
        int index = 1;
        rootPane.add(top, index++);
        rootPane.add(bottom, index++);
        rootPane.add(left, index++);
        rootPane.add(right, index++);

        // Cursor stabilizer, shown only while dragging.
        center = new JPanel();
        center.setOpaque(false);
        center.setVisible(false);
        rootPane.add(center, index);

        rootPane.addPropertyChangeListener("ancestor", this);
        // RootLayout only positions the layered pane, glass pane and menu bar,
        // so the strips need their bounds set by hand on every resize.
        rootPane.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { doLayout(); }
        });
        if (rootPane.isDisplayable())
            addNotify();
    }

    public void uninstall() {
        removeNotify();
        rootPane.removePropertyChangeListener("ancestor", this);
        rootPane.remove(top);
        rootPane.remove(bottom);
        rootPane.remove(left);
        rootPane.remove(right);
        rootPane.remove(center);
    }

    private void addNotify() {
        Component parent = rootPane.getParent();
        window = parent instanceof Window w ? w : null;
        if (window instanceof Frame) {
            window.addPropertyChangeListener("resizable", this);
            window.addWindowStateListener(this);
        }
        updateVisibility();
        doLayout();
    }

    private void removeNotify() {
        if (window instanceof Frame) {
            window.removePropertyChangeListener("resizable", this);
            window.removeWindowStateListener(this);
        }
        window = null;
    }

    /** Repositions the strips to the current window size. */
    public void doLayout() {
        if (!top.isVisible())
            return;
        int width = rootPane.getWidth();
        int height = rootPane.getHeight();
        if (width <= 0 || height <= 0)
            return;

        int thickness = UIScale.scale(BORDER_DRAG_THICKNESS);
        int cornerDelta = UIScale.scale(CORNER_DRAG_WIDTH - BORDER_DRAG_THICKNESS);

        top.setBounds(0, 0, width, thickness);
        bottom.setBounds(0, height - thickness, width, thickness);
        left.setBounds(0, thickness, thickness, height - 2 * thickness);
        right.setBounds(width - thickness, thickness, thickness, height - 2 * thickness);

        top.setCornerDragWidths(thickness + cornerDelta, thickness + cornerDelta);
        bottom.setCornerDragWidths(thickness + cornerDelta, thickness + cornerDelta);
        left.setCornerDragWidths(cornerDelta, cornerDelta);
        right.setCornerDragWidths(cornerDelta, cornerDelta);

        if (center.isVisible())
            center.setBounds(0, 0, width, height);
    }

    private void updateVisibility() {
        boolean visible = isResizable();
        if (visible == top.isVisible())
            return;
        top.setVisible(visible);
        bottom.setVisible(visible);
        left.setVisible(visible);
        // The east strip stays visible but disabled when not resizable, so its
        // paint still runs and it never intercepts the mouse.
        right.setEnabled(visible);
        if (visible) {
            right.setVisible(true);
            doLayout();
        } else {
            right.setBounds(0, 0, 1, 1);
        }
    }

    private boolean isResizable() {
        if (window instanceof Frame frame)
            return frame.isResizable() && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0;
        return false;
    }

    @Override
    public void propertyChange(PropertyChangeEvent e) {
        switch (e.getPropertyName()) {
            case "ancestor":
                if (e.getNewValue() != null)
                    addNotify();
                else
                    removeNotify();
                break;
            case "resizable":
                updateVisibility();
                break;
        }
    }

    @Override
    public void windowStateChanged(WindowEvent e) {
        updateVisibility();
    }

    /** One draggable edge strip. */
    private final class DragBorder extends JComponent implements MouseListener, MouseMotionListener {
        private final int leadingDir;
        private final int centerDir;
        private final int trailingDir;
        private int resizeDir;

        private int leadingCornerWidth;
        private int trailingCornerWidth;

        // The window bounds and mouse position at press: the baseline every drag
        // event is computed against as a pure delta.
        private int pressX;
        private int pressY;
        private int pressLeft;
        private int pressTop;
        private int pressWidth;
        private int pressHeight;

        // The bounds last pushed to the window, to skip a redundant setBounds.
        private Rectangle lastApplied;

        // True once the WM has taken the drag via _NET_WM_MOVERESIZE: the WM
        // grabs the pointer, so the client sees no further events.
        private boolean wmOwnsDrag;

        DragBorder(int leadingDir, int centerDir, int trailingDir) {
            this.leadingDir = leadingDir;
            this.centerDir = centerDir;
            this.trailingDir = trailingDir;
            // Start unset so setResizeDir installs the center cursor; assigning
            // the field directly would trip its same-value guard.
            this.resizeDir = -1;
            setResizeDir(centerDir);
            setVisible(false);
            addMouseListener(this);
            addMouseMotionListener(this);
        }

        void setCornerDragWidths(int leading, int trailing) {
            leadingCornerWidth = leading;
            trailingCornerWidth = trailing;
        }

        private void setResizeDir(int dir) {
            if (resizeDir == dir)
                return;
            resizeDir = dir;
            setCursor(getPredefinedCursor(dir));
        }

        @Override
        protected void paintComponent(Graphics g) {
            // For frames that become non-resizable without firing events, and for
            // fullscreen transitions, re-check visibility on every paint.
            updateVisibility();
        }

        // The direction for a point in this strip's coordinates: corner zones
        // near the ends, the center direction between. Used at press as well as
        // on hover, so a drag that starts without a prior hover grabs the right
        // edge.
        private int directionAt(int x, int y) {
            boolean horizontal = centerDir == N_RESIZE_CURSOR || centerDir == S_RESIZE_CURSOR;
            int xy = horizontal ? x : y;
            int wh = horizontal ? getWidth() : getHeight();
            return xy <= leadingCornerWidth
                    ? leadingDir
                    : (xy >= wh - trailingCornerWidth ? trailingDir : centerDir);
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            setResizeDir(directionAt(e.getX(), e.getY()));
        }

        // The EWMH direction for the current resize direction, or -1 if the
        // AWT cursor has no WM equivalent.
        private int wmDirection() {
            switch (resizeDir) {
                case NW_RESIZE_CURSOR: return X11WmHandoff.SIZE_TOPLEFT;
                case N_RESIZE_CURSOR:  return X11WmHandoff.SIZE_TOP;
                case NE_RESIZE_CURSOR: return X11WmHandoff.SIZE_TOPRIGHT;
                case E_RESIZE_CURSOR:  return X11WmHandoff.SIZE_RIGHT;
                case SE_RESIZE_CURSOR: return X11WmHandoff.SIZE_BOTTOMRIGHT;
                case S_RESIZE_CURSOR:  return X11WmHandoff.SIZE_BOTTOM;
                case SW_RESIZE_CURSOR: return X11WmHandoff.SIZE_BOTTOMLEFT;
                case W_RESIZE_CURSOR:  return X11WmHandoff.SIZE_LEFT;
                default:               return -1;
            }
        }

        @Override
        public void mousePressed(MouseEvent e) {
            if (!SwingUtilities.isLeftMouseButton(e) || !isResizable())
                return;
            // Lock the direction at press, so a drag that begins on a corner
            // without a prior hover still resizes that corner.
            setResizeDir(directionAt(e.getX(), e.getY()));

            // Hand the resize to the window manager when it supports the EWMH
            // hint; under XWayland the client cannot reposition its own window.
            if (X11WmHandoff.available()) {
                final int dir = wmDirection();
                if (dir >= 0 && X11WmHandoff.moveOrResize(window, e.getXOnScreen(), e.getYOnScreen(), dir)) {
                    wmOwnsDrag = true;
                    return;
                }
            }
            wmOwnsDrag = false;

            int xOnScreen = e.getXOnScreen();
            int yOnScreen = e.getYOnScreen();
            Rectangle b = window.getBounds();
            pressX = xOnScreen;
            pressY = yOnScreen;
            pressLeft = b.x;
            pressTop = b.y;
            pressWidth = b.width;
            pressHeight = b.height;
            lastApplied = b;

            center.setBounds(0, 0, rootPane.getWidth(), rootPane.getHeight());
            center.setCursor(getPredefinedCursor(resizeDir));
            center.setVisible(true);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (!SwingUtilities.isLeftMouseButton(e))
                return;
            if (wmOwnsDrag) {
                wmOwnsDrag = false;
                return;
            }
            center.setVisible(false);
            center.setCursor(null);
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (!SwingUtilities.isLeftMouseButton(e) || !isResizable())
                return;
            if (wmOwnsDrag)
                return;
            int dx = e.getXOnScreen() - pressX;
            int dy = e.getYOnScreen() - pressY;

            boolean topEdge = resizeDir == N_RESIZE_CURSOR || resizeDir == NW_RESIZE_CURSOR || resizeDir == NE_RESIZE_CURSOR;
            boolean bottomEdge = resizeDir == S_RESIZE_CURSOR || resizeDir == SW_RESIZE_CURSOR || resizeDir == SE_RESIZE_CURSOR;
            boolean leftEdge = resizeDir == W_RESIZE_CURSOR || resizeDir == NW_RESIZE_CURSOR || resizeDir == SW_RESIZE_CURSOR;
            boolean rightEdge = resizeDir == E_RESIZE_CURSOR || resizeDir == NE_RESIZE_CURSOR || resizeDir == SE_RESIZE_CURSOR;

            int x = pressLeft;
            int y = pressTop;
            int width = pressWidth;
            int height = pressHeight;

            if (leftEdge) {
                x = pressLeft + dx;
                width = pressWidth - dx;
            }
            if (rightEdge)
                width = pressWidth + dx;
            if (topEdge) {
                y = pressTop + dy;
                height = pressHeight - dy;
            }
            if (bottomEdge)
                height = pressHeight + dy;

            Rectangle r = new Rectangle(x, y, width, height);
            Rectangle press = new Rectangle(pressLeft, pressTop, pressWidth, pressHeight);
            clampMinSize(press, r);
            limitToScreen(r);

            if (!r.equals(lastApplied)) {
                lastApplied = r;
                window.setBounds(r);
                doLayout();
                if (Toolkit.getDefaultToolkit().isDynamicLayoutActive()) {
                    window.validate();
                    rootPane.repaint();
                }
            }
        }

        private void clampMinSize(Rectangle old, Rectangle r) {
            int minW = UIScale.scale(MIN_WIDTH);
            int minH = UIScale.scale(MIN_HEIGHT);
            Dimension min = window.getMinimumSize();
            if (min != null && min.width > 0)
                minW = Math.max(minW, min.width);
            if (min != null && min.height > 0)
                minH = Math.max(minH, min.height);
            if (r.width < minW) {
                if (r.x != old.x)
                    r.x -= (minW - r.width);
                r.width = minW;
            }
            if (r.height < minH) {
                if (r.y != old.y)
                    r.y -= (minH - r.height);
                r.height = minH;
            }
        }

        private void limitToScreen(Rectangle r) {
            GraphicsConfiguration gc = window.getGraphicsConfiguration();
            if (gc == null)
                return;
            Rectangle screen = gc.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            int bottom = screen.y + screen.height - insets.bottom;
            int right = screen.x + screen.width - insets.right;
            if (r.y + r.height > bottom)
                r.height = bottom - r.y;
            if (r.x + r.width > right)
                r.width = right - r.x;
        }

        @Override public void mouseClicked(MouseEvent e) {}
        @Override public void mouseEntered(MouseEvent e) {}
        @Override public void mouseExited(MouseEvent e) {}
    }
}
