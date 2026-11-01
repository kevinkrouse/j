/*
 * MenuItem.java
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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import javax.swing.JMenuItem;
import javax.swing.UIManager;

public final class MenuItem extends JMenuItem {
    private static final Font acceleratorFont =
        UIManager.getFont("MenuItem.acceleratorFont");
    private static final Color acceleratorForeground =
        UIManager.getColor("MenuItem.acceleratorForeground");
    private static final Color acceleratorSelectionForeground =
        UIManager.getColor("MenuItem.acceleratorSelectionForeground");
    private static final Color disabledForeground =
        UIManager.getColor("MenuItem.disabledForeground");

    private final String acceleratorText;

    public MenuItem(String label, String acceleratorText) {
        super(label);
        this.acceleratorText = acceleratorText;
    }

    @Override
    public Dimension getPreferredSize() {
        return withAccelerator(this, super.getPreferredSize(), acceleratorText);
    }

    // We paint our own menu items so the accelerator text will be consistent
    // with our key map format.
    @Override
    public void paint(Graphics g) {
        Display.setRenderingHints(g);
        super.paint(g);
        paintAccelerator(this, g, acceleratorText);
    }

    /** d widened for item's accelerator text, if it has one. */
    static Dimension withAccelerator(JMenuItem item, Dimension d, String acceleratorText) {
        if (acceleratorText != null)
            d.width += item.getFontMetrics(acceleratorFont).stringWidth(acceleratorText) + 30;
        return d;
    }

    /** item's accelerator text at its right, if it has one. */
    static void paintAccelerator(JMenuItem item, Graphics g, String acceleratorText) {
        if (acceleratorText != null) {
            g.setFont(acceleratorFont);
            Color c;
            if (item.isEnabled())
                c = item.getModel().isArmed() ? acceleratorSelectionForeground : acceleratorForeground;
            else
                c = disabledForeground;
            g.setColor(c);
            FontMetrics fm = g.getFontMetrics();
            Insets insets = item.getInsets();
            g.drawString(
                acceleratorText,
                item.getWidth() - (fm.stringWidth(acceleratorText) + insets.right + insets.left),
                item.getFont().getSize() + (insets.top - 1)
            );
        }
    }
}
