/*
 * StatusBar.java
 *
 * Copyright (C) 1998-2005 Peter Graves
 * Copyright (C) 2013-2026 Kevin Krouse
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
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Utilities;

public final class StatusBar extends JComponent implements PreferencesChangeListener {
    // Sized for the display rather than fixed, and recomputed whenever
    // preferences are reloaded, since uiScale may have changed.
    private static Font font;
    private static int leftMargin;
    private static int rightMargin;
    private static FontMetrics fm;
    private static int charAscent;
    private static int charDescent;

    private static int displayContext = 1;

    private final Frame frame;
    private final Border border;
    private String messageText;

    private static synchronized void initializeStaticValues() {
        font = new Font("SansSerif", Font.PLAIN, UIScale.scale(12));
        leftMargin = UIScale.scale(2);
        rightMargin = UIScale.scale(2);
        fm = Utilities.getFontMetrics(font);
        charAscent = fm.getAscent();
        charDescent = fm.getDescent();
    }

    public StatusBar(Frame frame) {
        this.frame = frame;
        Editor.preferences().addPreferencesChangeListener(this);
        preferencesChanged();
        initializeStaticValues();
        Dimension dim = frame.getSize();
        Insets insets = frame.getInsets();
        dim.width -= (insets.left + insets.right);
        border = new CompoundBorder(new MatteBorder(1, 0, 0, 0, Color.gray), new EmptyBorder(2, 0, 2, 0));
        setBorder(border);
        insets = border.getBorderInsets(this);
        dim.height = charAscent + charDescent + insets.top + insets.bottom;
        setPreferredSize(dim);
        // Its buttons for the sidebar run their commands; the buffer's link in
        // the status text, as the change's changelist, opens what it does.
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                final Editor editor = frame.getCurrentEditor();
                final int b = buttonAt(e.getX(), e.getY());
                if (b >= 0 && e.getButton() == MouseEvent.BUTTON1) {
                    editor.executeCommand(BUTTONS.get(b).command());
                    repaint();
                } else if (link != null && link.contains(e.getPoint()) && editor.getBuffer() != null)
                    editor.getBuffer().statusLinkClicked(editor, StatusBar.this, link.x, 0);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover(-1);
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hover(buttonAt(e.getX(), e.getY()));
                setCursor(
                    link != null && link.contains(e.getPoint())
                            ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            : Cursor.getDefaultCursor());
            }
        });
        // Each button's, as getToolTipText says.
        setToolTipText("");
    }

    /** A button at the status bar's left that shows or hides the sidebar, or a panel of it. */
    record Button(String command, String icon, String name, int panel) {}

    // The sidebar's button's panel.
    private static final int SIDEBAR = -2;

    /** Its buttons, left to right: the sidebar, then past a divider its panels. */
    static final List<Button> BUTTONS = List.of(
        new Button("toggleSidebar", "sidebar", "Sidebar", SIDEBAR),
        new Button("sidebarToggleBuffers", "buffers", "Buffers", Sidebar.BUFFERS),
        new Button("sidebarToggleChanges", "changelist", "Changes", Sidebar.CHANGES),
        new Button("sidebarToggleTree", "tree", "Tree", Sidebar.TREE));

    // Where each button was last painted, and the one the mouse is over, or -1.
    private final Rectangle[] buttons = new Rectangle[BUTTONS.size()];
    private int hover = -1;

    private int buttonAt(int x, int y) {
        for (int i = 0; i < buttons.length; i++)
            if (buttons[i] != null && buttons[i].contains(x, y))
                return i;
        return -1;
    }

    private void hover(int b) {
        if (b == hover)
            return;
        hover = b;
        repaint();
    }

    /** Whether what b shows or hides shows in frame. */
    static boolean isOn(Frame frame, Button b) {
        final Sidebar sidebar = frame.getSidebar();
        if (b.panel() == SIDEBAR || sidebar == null)
            return sidebar != null;
        return b.panel() == Sidebar.TREE ? frame.sidebarTree : frame.sidebarTop == b.panel();
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        final int b = buttonAt(e.getX(), e.getY());
        if (b < 0)
            return null;
        final Button button = BUTTONS.get(b);
        final Editor editor = frame.getCurrentEditor();
        final Object[] mapping =
                editor == null || editor.getBuffer() == null ? null : editor.getKeyMapping(button.command());
        final String key = mapping != null && mapping[0] instanceof KeyMapping m ? m.getKeyText() : "";
        return (isOn(frame, button) ? "Hide " : "Show ") + button.name() + (key.isEmpty() ? "" : "  " + key);
    }

    // The buttons painted from x, in a row height tall at y: the right of the last.
    private int paintButtons(Graphics g, int x, int y, int height) {
        final int width = height + UIScale.scale(4);
        final int iconSize = Math.max(8, height - UIScale.scale(4));
        final int arc = UIScale.scale(6);
        final Color text = UIManager.getColor("controlText");
        final Color dim = UIManager.getColor("Label.disabledForeground");
        final Color selection = UIManager.getColor("Tree.selectionBackground");
        final Color on = selection != null ? selection : new Color(0xd9ddf7);
        final Color control = UIManager.getColor("control");
        final Color hovered = control != null ? control.darker() : Color.lightGray;
        for (int i = 0; i < BUTTONS.size(); i++) {
            final Button b = BUTTONS.get(i);
            if (i == 1) {
                // The sidebar's apart from its panels.
                x += UIScale.scale(4);
                g.setColor(dim != null ? dim : Color.gray);
                g.fillRect(x, y + UIScale.scale(3), 1, height - 2 * UIScale.scale(3));
                x += 1 + UIScale.scale(4);
            }
            final boolean shown = isOn(frame, b);
            if (shown) {
                g.setColor(on);
                g.fillRoundRect(x, y, width - 1, height - 1, arc, arc);
                g.setColor(on.darker());
                g.drawRoundRect(x, y, width - 1, height - 1, arc, arc);
            } else if (i == hover) {
                g.setColor(hovered);
                g.fillRoundRect(x, y, width - 1, height - 1, arc, arc);
            }
            // The tree dimmed where the buffer's mode has none.
            final boolean none = b.panel() == Sidebar.TREE
                    && frame.getSidebar() != null
                    && frame.getSidebar().getBottomComponent() == null;
            final Color color = shown ? linkColor().darker() : none && dim != null ? dim : text;
            final javax.swing.Icon icon = Icons.getIconFromFile(b.icon(), iconSize, color);
            if (icon != null)
                icon.paintIcon(this, g, x + (width - iconSize) / 2, y + (height - iconSize) / 2);
            buttons[i] = new Rectangle(x, y, width, height);
            x += width + UIScale.scale(1);
        }
        return x;
    }

    // Where the buffer's link in the status text was last painted, or null.
    private Rectangle link;

    // The status bar outlives a preferences reload -- the frame doesn't
    // recreate it -- so it has to resize itself in place.
    private void updateSize() {
        initializeStaticValues();
        Insets insets = border.getBorderInsets(this);
        Dimension dim = getPreferredSize();
        dim.height = charAscent + charDescent + insets.top + insets.bottom;
        setPreferredSize(dim);
        revalidate();
        repaint();
    }

    public final void setText(String s) {
        messageText = s;
    }

    public final String getText() {
        return messageText;
    }

    private String getStatusText(Editor editor) {
        final Buffer buffer = editor.getBuffer();
        if (buffer == null)
            return "";
        StringBuilder sb = new StringBuilder();
        final InputHandler handler = editor.getInputHandler();
        if (handler != null) {
            final String indicator = handler.getModeIndicator();
            if (indicator != null) {
                sb.append("-- ");
                sb.append(indicator);
                sb.append(" --   ");
            }
            final String pending = handler.getPendingCommand();
            if (pending != null) {
                sb.append(pending);
                sb.append("   ");
            }
        }
        String emulation = buffer.getStringProperty(Property.EMULATION);
        if (emulation != null && emulation.length() > 0) {
            sb.append('[');
            sb.append(emulation);
            sb.append("]   ");
        }
        String modeName = buffer.getMode().getDisplayName();
        if (modeName != null)
            sb.append(modeName);
        if (buffer.isTransient())
            sb.append("   Esc or q to close");
        String s = buffer.getStatusText(editor);
        if (s != null && s.length() > 0) {
            sb.append("   ");
            sb.append(s);
        }
        return sb.toString();
    }

    @Override
    public void paint(Graphics g) {
        Editor editor = frame.getCurrentEditor();
        Buffer buffer = editor.getBuffer();
        border.paintBorder(this, g, 0, 0, getWidth(), getHeight());
        Insets insets = border.getBorderInsets(this);
        int textAreaWidth = getWidth() - insets.left - insets.right;
        int textAreaHeight = getHeight() - insets.top - insets.bottom;
        g.setColor(UIManager.getColor("control"));
        g.fillRect(insets.left, insets.top, textAreaWidth, textAreaHeight);
        g.setColor(UIManager.getColor("controlText"));
        g.setFont(font);
        Display.setRenderingHints(g);
        int x1 = paintButtons(g, insets.left + leftMargin, insets.top, textAreaHeight) + UIScale.scale(8);
        g.setColor(UIManager.getColor("controlText"));
        int y = insets.top + charAscent;
        if (messageText == null && displayContext > 0) {
            // We want the long context string if displayContext > 1.
            messageText = buffer.getMode().getContextString(editor, displayContext > 1);
        }
        int x2 = textAreaWidth - rightMargin;
        if (Platform.isPlatformMacOSX())
            x2 -= 15; // Leave room for the Aqua window handle graphic.
        String statusText = getStatusText(editor);
        if (statusText != null)
            x2 -= fm.stringWidth(statusText);
        if (messageText != null) {
            g.setClip(x1, 0, x2 - x1 - 20, getHeight());
            g.drawString(messageText, x1, y);
        }
        link = null;
        if (statusText != null) {
            g.setClip(x2, 0, textAreaWidth - x2, getHeight());
            final String linked = buffer.getStatusLink(editor);
            final int start = linked == null ? -1 : linkStart(statusText, linked);
            final int at = start < 0 ? -1 : start - 1;
            if (at < 0)
                g.drawString(statusText, x2, y);
            else {
                // The link in the link color, underlined.
                final String before = statusText.substring(0, at + 1);
                final int lx = x2 + fm.stringWidth(before);
                final int lw = fm.stringWidth(linked);
                g.drawString(before, x2, y);
                g.setColor(linkColor());
                g.drawString(linked, lx, y);
                g.drawLine(lx, y + 1, lx + lw, y + 1);
                g.setColor(UIManager.getColor("controlText"));
                g.drawString(statusText.substring(at + 1 + linked.length()), lx + lw, y);
                link = new Rectangle(lx, 0, lw, getHeight());
            }
        }
    }

    /**
     * Where link starts in text, as a buffer's status text puts it, after
     * a middle dot, the last such; -1 if it isn't there.
     */
    static int linkStart(String text, String link) {
        final int dot = text.lastIndexOf("· " + link + "   ");
        return dot < 0 ? -1 : dot + 2;
    }

    private static Color linkColor() {
        final Color c = UIManager.getColor("Component.linkColor");
        return c != null ? c : new Color(0x3574F0);
    }

    public void repaintNow() {
        paintImmediately(0, 0, getWidth(), getHeight());
    }

    @Override
    public void preferencesChanged() {
        displayContext = Editor.preferences().getIntegerProperty(Property.STATUS_BAR_DISPLAY_CONTEXT);
        // Not during construction, when the border isn't set yet. The font is
        // static, so it's already set when a second frame is made.
        if (border != null)
            updateSize();
    }
}
