/*
 * ToolBarButton.java
 *
 * Copyright (C) 2000-2002 Peter Graves
 * Copyright (C) 2009-2026 Kevin Krouse
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
import java.awt.Graphics;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.function.BooleanSupplier;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.UIManager;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Utilities;

public final class ToolBarButton extends JButton implements ActionListener, MouseListener {
    private Frame frame;
    private BooleanSupplier visibleWhen;
    private BooleanSupplier enabledWhen;
    private BooleanSupplier selectedWhen;

    public ToolBarButton(Frame frame, String actionCommand, ActionListener listener) {
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
                Image image = icon.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH);
                icon = new ImageIcon(image);
            }
        } else {
            // One of j's own icons: drawn at exactly the size asked for.
            icon = Icons.getIconFromFile(filename, size);
            setDisabledIcon(Icons.getDisabledIcon(icon));
        }
        if (icon != null)
            setIcon(icon);
    }

    /** It is shown while shown is true, from now on. */
    public ToolBarButton visibleWhen(BooleanSupplier shown) {
        visibleWhen = shown;
        update();
        return this;
    }

    /** It is enabled while enabled is true, from now on. */
    public ToolBarButton enabledWhen(BooleanSupplier enabled) {
        enabledWhen = enabled;
        update();
        return this;
    }

    /** It is pressed while selected is true, as a toggle is on. */
    public ToolBarButton selectedWhen(BooleanSupplier selected) {
        selectedWhen = selected;
        update();
        return this;
    }

    /** Shown, enabled and pressed as it says. */
    void update() {
        if (visibleWhen != null)
            setVisible(visibleWhen.getAsBoolean());
        if (enabledWhen != null)
            setEnabled(enabledWhen.getAsBoolean());
        if (selectedWhen != null) {
            // Not pressed in while it can't be pressed.
            final boolean selected = isEnabled() && selectedWhen.getAsBoolean();
            setSelected(selected);
            // Its look's fill would cover paintComponent's.
            setContentAreaFilled(!selected);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        // Pressed in while selected: Metal's buttons don't show it.
        if (isSelected()) {
            final Color c = UIManager.getColor("ToggleButton.select");
            g.setColor(c != null ? c : getBackground().darker());
            g.fillRect(0, 0, getWidth(), getHeight());
        }
        super.paintComponent(g);
    }

    @Override
    protected void paintBorder(Graphics g) {
        if (!isRolloverEnabled() || model.isRollover() || isSelected())
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
