/*
 * LogGraphColumn.java
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

package org.armedbear.j.vcs.log;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.List;
import org.armedbear.j.Display;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.Line;

/**
 * The log's graph, painted in the gutter before each row's text: a lane's
 * line straight down, a cubic curve where it moves between lanes or to or
 * from a commit, and the commit a dot, hollow for a merge and ringed for
 * HEAD. Antialiased, sized from the font, so it scales with it. Over a
 * lane, its tool tip names the branch.
 */
final class LogGraphColumn implements GutterColumn {
    // Lanes past this many are left out of sight at the right.
    static final int MAX_LANES = 16;

    // Tableau's ten, less the yellow and gray, which are faint on light themes.
    private static final Color[] COLORS = {
        new Color(0x4E79A7),
        new Color(0xF28E2B),
        new Color(0x59A14F),
        new Color(0xE15759),
        new Color(0xB07AA1),
        new Color(0x76B7B2),
        new Color(0xD37295),
        new Color(0x9C755F), };

    private final LogBuffer log;

    LogGraphColumn(LogBuffer log) {
        this.log = log;
    }

    static int laneWidth() {
        return Math.max(8, Display.getCharHeight() * 3 / 4);
    }

    private static float radius() {
        return laneWidth() * 0.28f;
    }

    private static float stroke(int height) {
        return Math.max(1.5f, height / 9f);
    }

    // Room at the left for the first lane's ring, which is wider than a lane.
    private static int inset() {
        final float ring = radius() + 2 * stroke(Display.getCharHeight());
        return Math.max(0, (int) Math.ceil(ring - laneWidth() / 2f)) + 1;
    }

    @Override
    public int width() {
        final int lanes = Math.min(log.graphWidth(), MAX_LANES);
        return lanes == 0 ? 0 : inset() + lanes * laneWidth() + Display.getCharWidth() / 2;
    }

    static Color color(int index) {
        return COLORS[Math.floorMod(index, COLORS.length)];
    }

    @Override
    public void paint(Graphics2D g, Line line, int x, int y, int height) {
        if (!(line instanceof LogLine l) || l.graph() == null)
            return;
        final LogGraph.Row row = l.graph();
        final Graphics2D g2d = (Graphics2D) g.create();
        try {
            final float stroke = stroke(height);
            final float mid = y + height / 2f;
            final float cx = center(x, row.column());
            final Area clip = new Area(new java.awt.Rectangle(x, y, width(), height));
            // A merge's dot hollow, whatever the row's background: the
            // lines stop at its ring.
            if (row.merge()) {
                final float r = radius() - stroke / 2;
                clip.subtract(new Area(new Ellipse2D.Float(cx - r, mid - r, 2 * r, 2 * r)));
            }
            g2d.clip(clip);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2d.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            edges(g2d, row.top(), x, y, mid);
            edges(g2d, row.bottom(), x, mid, y + height);
            node(g2d, row, l.commit(), cx, mid, stroke);
        }
        finally {
            g2d.dispose();
        }
    }

    private static float center(int x, int lane) {
        return x + inset() + lane * laneWidth() + laneWidth() / 2f;
    }

    @Override
    public String getToolTipText(Line line, int x, int y) {
        if (!(line instanceof LogLine l) || l.graph() == null)
            return null;
        final LogGraph.Row row = l.graph();
        final float mid = line.getHeight() / 2f;
        if (Math.abs(x - center(0, row.column())) <= laneWidth() / 2f && Math.abs(y - mid) <= radius() * 2)
            return row.lane().name();
        // The nearest edge in this half row, along its curve.
        final boolean top = y < mid;
        final float t = top ? y / mid : (y - mid) / (line.getHeight() - mid);
        final float s = t * t * (3 - 2 * t);
        LogGraph.Lane nearest = null;
        float distance = laneWidth() / 2f;
        for (LogGraph.Edge e : top ? row.top() : row.bottom()) {
            final float x0 = center(0, e.from());
            final float d = Math.abs(x - (x0 + (center(0, e.to()) - x0) * s));
            if (d <= distance) {
                distance = d;
                nearest = e.lane();
            }
        }
        return nearest == null ? null : nearest.name();
    }

    // Each edge from its lane at y0 to its lane at y1: straight, or an S curve.
    private static void edges(Graphics2D g, List<LogGraph.Edge> edges, int x, float y0, float y1) {
        for (LogGraph.Edge e : edges) {
            final float x0 = center(x, e.from());
            final float x1 = center(x, e.to());
            g.setColor(color(e.color()));
            if (e.from() == e.to())
                g.draw(new Line2D.Float(x0, y0, x1, y1));
            else {
                final float ym = (y0 + y1) / 2;
                g.draw(new CubicCurve2D.Float(x0, y0, x0, ym, x1, ym, x1, y1));
            }
        }
    }

    private static void node(Graphics2D g, LogGraph.Row row, LogCommit commit, float cx, float cy, float stroke) {
        final float r = radius();
        final Color color = color(row.color());
        final Ellipse2D dot = new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r);
        g.setColor(color);
        if (row.merge())
            g.draw(dot);
        else
            g.fill(dot);
        if (isHead(commit)) {
            final float ring = r + stroke * 1.5f;
            g.setColor(color);
            g.draw(new Ellipse2D.Float(cx - ring, cy - ring, 2 * ring, 2 * ring));
        }
    }

    private static boolean isHead(LogCommit commit) {
        for (LogRef ref : commit.refs())
            if (ref.head())
                return true;
        return false;
    }
}
