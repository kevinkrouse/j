/*
 * ToolBarButton.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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

import java.awt.Graphics;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Utilities;

public final class ToolBarButton extends JButton implements ActionListener,
    MouseListener {
    private Frame frame;

    public ToolBarButton(
        Frame frame,
        String actionCommand,
        ActionListener listener
    ) {
        super();
        this.frame = frame;
        addMouseListener(this);
        setActionCommand(actionCommand);
        addActionListener(listener);
        // We want our listener called first, so add it last.
        addActionListener(this);
        setRequestFocusEnabled(false);
    }

    public void setIconFromFile(String filename) {
        final int size = ToolBar.iconSize();
        ImageIcon icon;
        if (Utilities.isFilenameAbsolute(filename)) {
            // An icon supplied by an extension, as a file on disk. Still a
            // bitmap, so it may need resizing.
            icon = new ImageIcon(filename);
            if (icon.getIconWidth() != size || icon.getIconHeight() != size) {
                Image image =
                    icon.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH);
                icon = new ImageIcon(image);
            }
        } else {
            // One of j's own icons: drawn at exactly the size asked for.
            icon = Icons.getIconFromFile(filename, size);
        }
        if (icon != null)
            setIcon(icon);
    }

    @Override
    protected void paintBorder(Graphics g) {
        if (!isRolloverEnabled() || model.isRollover())
            super.paintBorder(g);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        model.setPressed(false);
        model.setArmed(false);
        model.setRollover(false);
    }

    @Override
    public void mouseClicked(MouseEvent e) {}

    @Override
    public void mousePressed(MouseEvent e) {}

    @Override
    public void mouseReleased(MouseEvent e) {}

    @Override
    public void mouseEntered(MouseEvent e) {
        frame.setStatusText(this.getToolTipText());
    }

    @Override
    public void mouseExited(MouseEvent e) {
        frame.setStatusText("");
    }
}
