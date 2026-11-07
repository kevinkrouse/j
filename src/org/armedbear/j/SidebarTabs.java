/*
 * SidebarTabs.java
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
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;

/**
 * The titles of the sidebar's upper half's tabs in a row: the one shown on
 * the list's background, joined to the list under it, a line in the
 * selection's color along its foot; the others on the panel's, a thin line
 * under one the mouse is over. Every title in the text's color, so none
 * reads as disabled. A click on one chooses it.
 */
final class SidebarTabs extends JPanel {
    private final List<Title> titles = new ArrayList<>();
    private int shown;

    SidebarTabs(IntConsumer chosen, String... names) {
        // Laid out here, each title its full height, whatever the scale.
        super(null);
        setAlignmentX(LEFT_ALIGNMENT);
        for (int i = 0; i < names.length; i++) {
            final int tab = i;
            final Title title = new Title(names[i]);
            title.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    chosen.accept(tab);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    title.hover = true;
                    title.repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    title.hover = false;
                    title.repaint();
                }
            });
            titles.add(title);
            add(title);
        }
    }

    /** Which is shown. */
    int shown() {
        return shown;
    }

    /** Marks tab i the one shown. */
    void show(int i) {
        shown = i;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        final Insets in = getInsets();
        int w = 0;
        int h = 0;
        for (Title t : titles) {
            final Dimension d = t.getPreferredSize();
            w += d.width;
            h = Math.max(h, d.height);
        }
        return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
    }

    // Its width no limit on the sidebar's.
    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public void doLayout() {
        final Insets in = getInsets();
        final int h = getHeight() - in.top - in.bottom;
        int x = in.left;
        for (Title t : titles) {
            final int w = t.getPreferredSize().width;
            t.setBounds(x, in.top, w, h);
            x += w;
        }
    }

    private final class Title extends JLabel {
        private boolean hover;

        Title(String text) {
            super(text);
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3 + line(), 6));
        }

        // The line along a title's foot.
        private static int line() {
            return UIScale.scale(2);
        }

        @Override
        public void paintComponent(Graphics g) {
            Display.setRenderingHints(g);
            final boolean isShown = titles.indexOf(this) == shown;
            if (isShown) {
                final Color list = UIManager.getColor("Tree.background");
                g.setColor(list != null ? list : Color.WHITE);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            super.paintComponent(g);
            if (isShown || hover) {
                final Color selection = UIManager.getColor("Tree.selectionBackground");
                final Color quiet = UIManager.getColor("Separator.foreground");
                g.setColor(
                    isShown ? (selection != null ? selection : Color.BLUE) : (quiet != null ? quiet : Color.GRAY));
                final int h = isShown ? line() : Math.max(1, line() / 2);
                g.fillRect(0, getHeight() - h, getWidth(), h);
            }
        }
    }
}
