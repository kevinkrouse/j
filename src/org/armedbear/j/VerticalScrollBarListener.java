/*
 * VerticalScrollBarListener.java
 *
 * Copyright (C) 1998-2003 Peter Graves
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

import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;

public final class VerticalScrollBarListener implements AdjustmentListener {
    private final Editor editor;

    public VerticalScrollBarListener(Editor editor) {
        this.editor = editor;
    }

    @Override
    public void adjustmentValueChanged(AdjustmentEvent e) {
        if (editor.inScrollBarUpdate)
            return;
        final Display display = editor.getDisplay();
        if (display.getTopLine() == null) {
            // Image buffer.
            int value = e.getValue();
            if (value != display.getPixelsAboveTopLine()) {
                display.setPixelsAboveTopLine(value);
                display.repaint();
            }
            return;
        }
        int oldValue = editor.getBuffer().getY(display.getTopLine()) + display.getPixelsAboveTopLine();
        if (display.scrollByPixels(e.getValue() - oldValue) != 0) {
            editor.maybeScrollCaret();
            editor.updateDisplay();
        }
    }
}
