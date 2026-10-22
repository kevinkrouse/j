/*
 * CompareTrack.java
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

package org.armedbear.j.mode.compare;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.armedbear.j.Editor;
import org.armedbear.j.UIScale;
import org.armedbear.j.mode.compare.TrackGeometry.Band;

/**
 * The track between a comparison's panes, at the left of a window showing
 * its new side: each change in view a band from its lines in the old side's
 * window to its lines in this one, in the change's color.
 */
final class CompareTrack extends JComponent {
    // Its width, before UIScale.
    private static final int WIDTH = 28;

    private final CompareBuffer owner;
    private final Editor right;

    CompareTrack(CompareBuffer owner, Editor right) {
        this.owner = owner;
        this.right = right;
        setOpaque(true);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(UIScale.scale(WIDTH), 0);
    }

    /** The bands in a track height high, none without the old side's window beside it. */
    List<Band> bands(int height) {
        final Editor left = CompareBuffer.windowIn(right.getFrame(), owner.getOld());
        // Not while its window shows something else, as a buffer still loading.
        if (left == null || right.getBuffer() != owner)
            return List.of();
        if (left.getDisplay().getTopLine() == null || right.getDisplay().getTopLine() == null)
            return List.of();
        return TrackGeometry.bands(
            owner.getAlignment().changes(),
            CompareBuffer.geometryOf(left),
            scroll(left),
            CompareBuffer.geometryOf(right),
            scroll(right),
            height);
    }

    // How far ed's window is scrolled down its side, less where its display
    // is down the track.
    private int scroll(Editor ed) {
        final int dy = isShowing() && ed.getDisplay().isShowing()
                ? SwingUtilities.convertPoint(ed.getDisplay(), 0, 0, this).y
                : 0;
        return CompareBuffer.y(ed) - dy;
    }

    @Override
    protected void paintComponent(Graphics g) {
        final Graphics2D g2 = (Graphics2D) g.create();
        try {
            final SideFormatter f = owner.getFormatter() instanceof SideFormatter s ? s : null;
            g2.setColor(f != null ? f.getBackgroundColor() : getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (f == null)
                return;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            for (Band b : bands(getHeight())) {
                final Path2D shape = b.shape(getWidth());
                g2.setColor(f.shade(b.kind()));
                g2.fill(shape);
                g2.setColor(f.edge(b.kind()));
                g2.draw(shape);
            }
        }
        finally {
            g2.dispose();
        }
    }
}
