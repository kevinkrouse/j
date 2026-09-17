/*
 * StatusBar.java
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

import org.armedbear.j.util.Utilities;
import java.lang.StringBuilder;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Toolkit;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

public final class StatusBar extends JComponent
    implements PreferencesChangeListener
{
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

    private static synchronized void initializeStaticValues()
    {
        font = new Font("SansSerif", Font.PLAIN, UIScale.scale(12));
        leftMargin = UIScale.scale(2);
        rightMargin = UIScale.scale(2);
        fm = Utilities.getFontMetrics(font);
        charAscent = fm.getAscent();
        charDescent = fm.getDescent();
    }

    public StatusBar(Frame frame)
    {
        this.frame = frame;
        Editor.preferences().addPreferencesChangeListener(this);
        preferencesChanged();
        initializeStaticValues();
        Dimension dim = frame.getSize();
        Insets insets = frame.getInsets();
        dim.width -= (insets.left + insets.right);
        border = new CompoundBorder(new MatteBorder(1, 0, 0, 0, Color.gray),
                                    new EmptyBorder(2, 0, 2, 0));
        setBorder(border);
        insets = border.getBorderInsets(this);
        dim.height = charAscent + charDescent + insets.top + insets.bottom;
        setPreferredSize(dim);
    }

    // The status bar outlives a preferences reload -- the frame doesn't
    // recreate it -- so it has to resize itself in place.
    private void updateSize()
    {
        initializeStaticValues();
        Insets insets = border.getBorderInsets(this);
        Dimension dim = getPreferredSize();
        dim.height = charAscent + charDescent + insets.top + insets.bottom;
        setPreferredSize(dim);
        revalidate();
        repaint();
    }

    public final void setText(String s)
    {
        messageText = s;
    }

    public final String getText()
    {
        return messageText;
    }

    private String getStatusText(Editor editor)
    {
        final Buffer buffer = editor.getBuffer();
        if (buffer == null)
             return "";
        StringBuilder sb = new StringBuilder();
        String emulation = buffer.getStringProperty(Property.EMULATION);
        if (emulation != null && emulation.length() > 0) {
            sb.append('[');
            sb.append(emulation);
            sb.append("]   ");
        }
        String modeName = buffer.getMode().getDisplayName();
        if (modeName != null)
            sb.append(modeName);
        String s = buffer.getStatusText(editor);
        if (s != null && s.length() > 0) {
            sb.append("   ");
            sb.append(s);
        }
        return sb.toString();
    }

    public void paint(Graphics g)
    {
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
        int x1 = insets.left + leftMargin;
        int y = insets.top + charAscent;
        if (messageText == null && displayContext > 0) {
            // We want the long context string if displayContext > 1.
            messageText =
                buffer.getMode().getContextString(editor, displayContext > 1);
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
        if (statusText != null) {
            g.setClip(x2, 0, textAreaWidth - x2, getHeight());
            g.drawString(statusText, x2, y);
        }
    }

    public void repaintNow()
    {
        paintImmediately(0, 0, getWidth(), getHeight());
    }

    public void preferencesChanged()
    {
        displayContext =
            Editor.preferences().getIntegerProperty(Property.STATUS_BAR_DISPLAY_CONTEXT);
        if (font != null)
            updateSize(); // Not during construction; the border isn't set yet.
    }
}
