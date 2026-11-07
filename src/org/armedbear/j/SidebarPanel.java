/*
 * SidebarPanel.java
 *
 * Copyright (C) 2000-2003 Peter Graves
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

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

public final class SidebarPanel extends JPanel implements MouseListener {
    private final Sidebar sidebar;
    private JLabel label;
    private JScrollPane scrollPane;

    public SidebarPanel(Sidebar sidebar) {
        super();
        this.sidebar = sidebar;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    @Override
    public void removeAll() {
        label = null;
        scrollPane = null;
        super.removeAll();
    }

    public void setLabelText(String s) {
        if (s == null || s.length() == 0) {
            // Remove label.
            if (label != null) {
                remove(label);
                label = null;
            }
        } else {
            if (label == null) {
                // Add label.
                if (scrollPane != null) {
                    remove(scrollPane);
                    add(label = new Label());
                    add(scrollPane);
                    validate();
                }
            }
            if (label != null && !s.equals(label.getText()))
                label.setText(s);
        }
    }

    public void addScrollPane(JScrollPane scrollPane) {
        addScrollPane(scrollPane, null);
    }

    /** scrollPane under header, in place of the label, if not null. */
    void addScrollPane(JScrollPane scrollPane, JComponent header) {
        if (header != null)
            add(header);
        else if (label == null)
            add(label = new Label());
        if (scrollPane.getVerticalScrollBar() != null)
            scrollPane.getVerticalScrollBar().addMouseListener(this);
        this.scrollPane = scrollPane;
        add(scrollPane);
    }

    @Override
    public void mouseClicked(MouseEvent e) {}

    @Override
    public void mouseEntered(MouseEvent e) {}

    @Override
    public void mouseExited(MouseEvent e) {}

    @Override
    public void mousePressed(MouseEvent e) {
        if (scrollPane != null) {
            if (scrollPane.getViewport() != null) {
                Component c = scrollPane.getViewport().getView();
                if (c instanceof JComponent jComponent)
                    sidebar.getEditor().setFocus(jComponent);
            }
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {}

    private static class Label extends JLabel {
        private Label() {
            super();
            setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        }

        // Its full height always, so a short sidebar doesn't squash it; its
        // width no limit on the sidebar's.
        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public void paintComponent(Graphics g) {
            Display.setRenderingHints(g);
            super.paintComponent(g);
        }
    }
}
