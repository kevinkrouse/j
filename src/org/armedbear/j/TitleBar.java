/*
 * TitleBar.java
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

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import org.armedbear.j.util.Icons;

/**
 * j's own window title bar, with the menu embedded, FlatLAF-style.
 *
 * <p>It is a {@link JMenuBar} subclass set via {@code setJMenuBar}, so it lands
 * in the slot {@link javax.swing.JRootPane.RootLayout} already reserves. The
 * layout is a horizontal box:
 *
 * <pre>[pad] [icon] [pad] [menus] [glue] [min] [max] [close]</pre>
 *
 * <p>The window's title is set on the {@link Frame} for the desktop's
 * application switcher but is not painted here.
 *
 * <p>The real per-mode {@link MenuBar} is nested as the {@code menus} child and
 * swapped on mode change via {@link #setMenus}; the bar itself is created once
 * and survives every rebuild.
 *
 * <p>Dragging works because Swing hands a mouse press to the nearest ancestor
 * with a listener: the icon and glue have none, so presses on them reach this
 * bar's drag listener, while menus and buttons consume their own.
 *
 * <p>Colors come from the {@code TitlePane.*} UI defaults, derived from the
 * editor theme in {@link DefaultLookAndFeel}.
 */
public final class TitleBar extends JMenuBar {
    /** The bar's height, kept short so it reads as a slim title strip. */
    private static final int BAR_HEIGHT = 28;
    /** The app icon's edge, and the padding on each side of it. */
    private static final int ICON_SIZE = 16;
    private static final int ICON_PAD = 8;
    /** A window button's width, its circle's diameter, and its glyph's edge. */
    private static final int BUTTON_WIDTH = 28;
    private static final int CIRCLE_DIAMETER = 20;
    private static final int BUTTON_ICON = 12;
    /** The gap between the last menu and the window buttons. */
    private static final int BUTTONS_GAP = 6;

    private final JLabel iconLabel = new JLabel();
    private final TitleButton minimizeButton;
    private final TitleButton maximizeButton;
    private final TitleButton restoreButton;
    private final TitleButton closeButton;

    private JMenuBar menus;
    private Window window;
    private Point dragOffset;

    // Colors, re-read on updateUI so a theme reload repaints the bar.
    private Color background;
    private Color inactiveBackground;
    private Color foreground;
    private Color inactiveForeground;

    private final Handler handler = new Handler();

    public TitleBar() {
        setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));
        setFocusable(false);
        setOpaque(false);
        setBorder(new EmptyBorder(0, 0, 0, 0));
        setPreferredSize(new Dimension(0, UIScale.scale(BAR_HEIGHT)));

        iconLabel.setFocusable(false);
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        iconLabel.setBorder(new EmptyBorder(0, UIScale.scale(ICON_PAD), 0, UIScale.scale(ICON_PAD)));

        minimizeButton = new TitleButton("title-minimize", "Minimize", e -> minimize());
        maximizeButton = new TitleButton("title-maximize", "Maximize", e -> maximize());
        restoreButton = new TitleButton("title-restore", "Restore", e -> restore());
        closeButton = new TitleButton("title-close", "Close", e -> close());

        add(iconLabel);
        // The glue pushes the buttons to the right edge. It has no listeners, so
        // a press on the empty space bubbles to the bar's drag handler.
        add(Box.createHorizontalGlue());
        add(Box.createHorizontalStrut(UIScale.scale(BUTTONS_GAP)));
        add(minimizeButton);
        add(maximizeButton);
        add(restoreButton);
        add(closeButton);

        restoreButton.setVisible(false);

        // Drag on the bar itself. A press on a listener-less child (the icon)
        // bubbles here; menus and buttons consume their own presses first.
        DragHandler drag = new DragHandler();
        addMouseListener(drag);
        addMouseMotionListener(drag);

        readColors();
        updateActiveState(true);
    }

    /** The real per-mode menu bar currently embedded, or null. */
    public JMenuBar getMenus() {
        return menus;
    }

    /** The window title, read back from the window (it is not painted here). */
    public String getTitleText() {
        return windowTitle();
    }

    /**
     * The screen point at the center of the named window button, for tests:
     * "minimize", "maximize", "restore" or "close".
     */
    public Point buttonScreenPoint(String name) {
        JButton button = switch (name) {
            case "minimize" -> minimizeButton;
            case "maximize" -> maximizeButton;
            case "restore" -> restoreButton;
            case "close" -> closeButton;
            default -> throw new IllegalArgumentException("no such button " + name);
        };
        return button.getLocationOnScreen();
    }

    /**
     * Swaps the embedded per-mode menu bar, keeping the title bar intact. The
     * menus are made transparent so the bar's own background shows through them
     * as one continuous color, and their top-level items get a slim margin so
     * the bar stays short.
     */
    public void setMenus(JMenuBar newMenus) {
        if (menus != null)
            remove(menus);
        menus = newMenus;
        if (menus != null) {
            menus.setOpaque(false);
            menus.setBorder(new EmptyBorder(0, 0, 0, 0));
            slimTopLevelMenus(menus);
            add(menus, 1); // after the icon, before the glue
        }
        revalidate();
        repaint();
    }

    // Slim border so the menus do not force the bar tall, and the bar's own
    // foreground so a dark theme's menus are not unreadable. Only top-level
    // items: dropdowns keep the theme's normal colors.
    private void slimTopLevelMenus(JMenuBar bar) {
        final Color fg = isActive() ? foreground : inactiveForeground;
        for (int i = 0; i < bar.getComponentCount(); i++) {
            final Component c = bar.getComponent(i);
            if (c instanceof JMenu menu) {
                menu.setOpaque(false);
                menu.setForeground(fg);
                menu.setBorder(new EmptyBorder(1, 6, 1, 6));
            }
        }
    }

    @Override
    public void updateUI() {
        // Keep the JMenuBar UI so the menu selection machinery stays wired, then
        // re-apply the customizations the base install overwrites.
        super.updateUI();
        // The JMenuBar constructor calls updateUI() before our fields exist.
        if (minimizeButton == null)
            return;
        setOpaque(false);
        setBorder(new EmptyBorder(0, 0, 0, 0));
        setPreferredSize(new Dimension(0, UIScale.scale(BAR_HEIGHT)));
        readColors();
        refreshIcons();
        if (menus != null) {
            menus.setOpaque(false);
            menus.setBorder(new EmptyBorder(0, 0, 0, 0));
            slimTopLevelMenus(menus);
        }
        updateActiveState(window == null || window.isActive());
    }

    private void readColors() {
        background = color("TitlePane.background", new Color(0x3c3f41));
        inactiveBackground = color("TitlePane.inactiveBackground", new Color(0x45494c));
        foreground = color("TitlePane.foreground", Color.WHITE);
        inactiveForeground = color("TitlePane.inactiveForeground", new Color(0x9aa0a6));
    }

    private static Color color(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private void refreshIcons() {
        refreshIcons(isActive() ? foreground : inactiveForeground);
    }

    private void refreshIcons(Color tint) {
        final int size = UIScale.scale(BUTTON_ICON);
        minimizeButton.setIcon(Icons.getIconFromFile("title-minimize", size, tint));
        maximizeButton.setIcon(Icons.getIconFromFile("title-maximize", size, tint));
        restoreButton.setIcon(Icons.getIconFromFile("title-restore", size, tint));
        closeButton.setIcon(Icons.getIconFromFile("title-close", size, tint));
    }

    @Override
    public void addNotify() {
        super.addNotify();
        window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.addPropertyChangeListener(handler);
            window.addWindowListener(handler);
            window.addWindowStateListener(handler);
            updateIcon();
            updateMaximizeRestore();
        }
    }

    @Override
    public void removeNotify() {
        if (window != null) {
            window.removePropertyChangeListener(handler);
            window.removeWindowListener(handler);
            window.removeWindowStateListener(handler);
        }
        window = null;
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(isActive() ? background : inactiveBackground);
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    private boolean isActive() {
        return window == null || window.isActive();
    }

    /** The window's title, which lives on Frame and Dialog but not Window. */
    private String windowTitle() {
        if (window instanceof JFrame frame)
            return frame.getTitle();
        if (window instanceof java.awt.Dialog dialog)
            return dialog.getTitle();
        return "";
    }

    private void updateActiveState(boolean active) {
        refreshIcons(active ? foreground : inactiveForeground);
        if (menus != null)
            slimTopLevelMenus(menus);
        repaint();
    }

    private void updateIcon() {
        if (window == null || window.getIconImages().isEmpty()) {
            iconLabel.setVisible(false);
            return;
        }
        final int size = UIScale.scale(ICON_SIZE);
        iconLabel.setIcon(Icons.getIconFromFile("j-logo", size));
        iconLabel.setVisible(true);
    }

    // ---- window actions ---------------------------------------------------

    private void minimize() {
        if (window instanceof JFrame frame)
            frame.setExtendedState(frame.getExtendedState() | JFrame.ICONIFIED);
    }

    private void maximize() {
        if (!(window instanceof JFrame frame))
            return;
        updateMaximizedBounds(frame);
        frame.setExtendedState(frame.getExtendedState() | JFrame.MAXIMIZED_BOTH);
    }

    private void restore() {
        if (!(window instanceof JFrame frame))
            return;
        int state = frame.getExtendedState();
        if ((state & JFrame.ICONIFIED) != 0)
            frame.setExtendedState(state & ~JFrame.ICONIFIED);
        else
            frame.setExtendedState(state & ~JFrame.MAXIMIZED_BOTH);
    }

    private void close() {
        if (window != null)
            window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
    }

    private void toggleMaximize() {
        if (!(window instanceof JFrame frame))
            return;
        if ((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH)
            restore();
        else
            maximize();
    }

    private void updateMaximizedBounds(JFrame frame) {
        GraphicsConfiguration gc = window.getGraphicsConfiguration();
        if (gc == null) {
            gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
        }
        Rectangle screen = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        frame.setMaximizedBounds(new Rectangle(
                screen.x + insets.left,
                screen.y + insets.top,
                screen.width - insets.left - insets.right,
                screen.height - insets.top - insets.bottom));
    }

    private void updateMaximizeRestore() {
        if (!(window instanceof JFrame frame)) {
            minimizeButton.setVisible(false);
            maximizeButton.setVisible(false);
            restoreButton.setVisible(false);
            return;
        }
        boolean maximizable = frame.isResizable();
        boolean maximized = (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
        minimizeButton.setVisible(true);
        maximizeButton.setVisible(maximizable && !maximized);
        restoreButton.setVisible(maximizable && maximized);
    }

    // ---- window listeners -----------------------------------------------

    private final class Handler extends WindowAdapter implements PropertyChangeListener {
        @Override
        public void propertyChange(PropertyChangeEvent e) {
            switch (e.getPropertyName()) {
                case "iconImage":
                    updateIcon();
                    break;
                case "resizable":
                    updateMaximizeRestore();
                    break;
            }
        }

        @Override
        public void windowActivated(WindowEvent e) {
            updateActiveState(true);
        }

        @Override
        public void windowDeactivated(WindowEvent e) {
            updateActiveState(false);
        }

        @Override
        public void windowStateChanged(WindowEvent e) {
            updateMaximizeRestore();
        }
    }

    // ---- drag / double-click on the bar ---------------------------------

    private final class DragHandler extends MouseAdapter {
        // True once the WM has taken the move via _NET_WM_MOVERESIZE: the WM
        // grabs the pointer, so the client sees no further events.
        private boolean wmOwnsMove;

        @Override
        public void mousePressed(MouseEvent e) {
            if (window != null && SwingUtilities.isLeftMouseButton(e)) {
                wmOwnsMove = false;
                dragOffset = new Point(e.getXOnScreen() - window.getX(), e.getYOnScreen() - window.getY());
            }
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (window == null || dragOffset == null || !SwingUtilities.isLeftMouseButton(e))
                return;
            if (wmOwnsMove)
                return;
            // Hand the move to the window manager on the first drag event, not on
            // press: a press that never becomes a drag is a click, and a
            // double-click must still reach the maximize handler below.
            if (X11WmHandoff.available()
                    && X11WmHandoff.moveOrResize(window, e.getXOnScreen(), e.getYOnScreen(), X11WmHandoff.MOVE)) {
                wmOwnsMove = true;
                return;
            }
            // Restore first if maximized, so the window follows the mouse.
            if (window instanceof JFrame frame && (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0) {
                int maximizedWidth = window.getWidth();
                frame.setExtendedState(frame.getExtendedState() & ~JFrame.MAXIMIZED_BOTH);
                int restoredWidth = window.getWidth();
                int center = restoredWidth / 2;
                if (dragOffset.x > center) {
                    if (dragOffset.x > maximizedWidth - center)
                        dragOffset.x = restoredWidth - (maximizedWidth - dragOffset.x);
                    else
                        dragOffset.x = center;
                }
            }
            window.setLocation(e.getXOnScreen() - dragOffset.x, e.getYOnScreen() - dragOffset.y);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            wmOwnsMove = false;
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (window == null || !SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 2)
                return;
            // Double-click anywhere on the bar toggles maximize.
            toggleMaximize();
        }
    }

    // ---- title button ---------------------------------------------------

    /**
     * A window button drawn as a small glyph on a circular disc, the way
     * desktop window controls look: the disc is always faintly visible, darkens
     * on hover, and the close button's disc turns red.
     */
    private final class TitleButton extends JButton {
        private boolean hover;
        private boolean pressed;
        private final boolean isClose;
        private final String iconName;

        TitleButton(String iconName, String accessibleName, java.awt.event.ActionListener action) {
            super();
            this.iconName = iconName;
            isClose = "title-close".equals(iconName);
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setMargin(new Insets(0, 0, 0, 0));
            setMaximumSize(new Dimension(UIScale.scale(BUTTON_WIDTH), Short.MAX_VALUE));
            setPreferredSize(new Dimension(UIScale.scale(BUTTON_WIDTH), UIScale.scale(BAR_HEIGHT)));
            getAccessibleContext().setAccessibleName(accessibleName);
            addActionListener(action);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; pressed = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { pressed = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            final Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            final int d = UIScale.scale(CIRCLE_DIAMETER);
            final int x = (getWidth() - d) / 2;
            final int y = (getHeight() - d) / 2;
            if (isClose && (hover || pressed)) {
                g2.setColor(pressed ? new Color(0xa31e12) : new Color(0xc42b1c));
                g2.fillOval(x, y, d, d);
                paintGlyph(g, Color.WHITE);
            } else {
                // A faint disc always, so the control reads as a button; it
                // darkens on hover and press.
                final Color base = isActive() ? background : inactiveBackground;
                final double amount = pressed ? 0.22 : (hover ? 0.14 : 0.07);
                g2.setColor(DefaultTheme.blend(base, foreground, amount));
                g2.fillOval(x, y, d, d);
                super.paintComponent(g);
            }
            g2.dispose();
        }

        private void paintGlyph(Graphics g, Color color) {
            final int size = UIScale.scale(BUTTON_ICON);
            Icon icon = Icons.getIconFromFile(iconName, size, color);
            if (icon != null) {
                int ix = (getWidth() - icon.getIconWidth()) / 2;
                int iy = (getHeight() - icon.getIconHeight()) / 2;
                icon.paintIcon(this, g, ix, iy);
            }
        }
    }
}
