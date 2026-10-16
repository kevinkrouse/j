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
import org.armedbear.j.DefaultTheme;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.GutterColumn;
import org.armedbear.j.Line;
import org.armedbear.j.Property;
import org.armedbear.j.transients.FilterBar;
import org.armedbear.j.vcs.RepositoryColors;
import org.armedbear.j.vcs.VcsRepository;

/**
 * The log's graph, painted in the gutter before each row's text: a lane's
 * line straight down, a cubic curve where it moves between lanes or to or
 * from a commit, and the commit a dot, hollow for a merge and ringed for
 * HEAD. Antialiased, sized from the font, so it scales with it. Over a
 * lane, its tool tip names the branch. A log of several repositories has
 * a strip before it in the row's repository's color, and its name with
 * vcsLogRepositoryNames; a click on the strip shows that one alone.
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
        return strip() + graph();
    }

    // The graph's part.
    private int graph() {
        final int lanes = Math.min(log.graphWidth(), MAX_LANES);
        return lanes == 0 ? 0 : inset() + lanes * laneWidth() + Display.getCharWidth() / 2;
    }

    private static int bar() {
        return Math.max(3, Display.getCharWidth() / 2);
    }

    // The repository's strip, with its name when they are shown; none for one.
    private int strip() {
        final LogScope scope = log.getScope();
        if (!scope.isSeveral())
            return 0;
        if (!log.getBooleanProperty(Property.VCS_LOG_REPOSITORY_NAMES))
            return bar() + gap();
        int longest = 0;
        for (VcsRepository r : scope.repositories())
            longest = Math.max(longest, scope.name(r).length());
        return bar() + (longest + 1) * Display.getCharWidth() + gap();
    }

    // Between the strip, or its name, and the graph.
    private static int gap() {
        return Display.getCharWidth();
    }

    private Color repositoryColor(VcsRepository repository) {
        final int i = log.getScope().colorIndex(repository);
        return RepositoryColors.color(i, DefaultTheme.isDark(log.getFormatter().getBackgroundColor()));
    }

    static Color color(int index) {
        return COLORS[Math.floorMod(index, COLORS.length)];
    }

    @Override
    public void paint(Graphics2D g, Line line, int x, int y, int height) {
        if (!(line instanceof LogLine l))
            return;
        final int strip = strip();
        if (strip > 0) {
            final VcsRepository r = log.repositoryOf(l.commit());
            g.setColor(repositoryColor(r));
            g.fillRect(x, y, bar(), height);
            if (strip > bar() + gap()) {
                // Its own font: the gutter's last drawing leaves another set.
                g.setFont(Display.getPlainFont());
                g.drawString(
                    log.getScope().name(r),
                    x + bar() + Display.getCharWidth() / 2,
                    y + g.getFontMetrics().getAscent());
            }
        }
        if (l.graph() == null)
            return;
        paintGraph(g, l, x + strip, y, height);
    }

    private void paintGraph(Graphics2D g, LogLine l, int x, int y, int height) {
        final LogGraph.Row row = l.graph();
        final Graphics2D g2d = (Graphics2D) g.create();
        try {
            final float stroke = stroke(height);
            final float mid = y + height / 2f;
            final float cx = center(x, row.column());
            final Area clip = new Area(new java.awt.Rectangle(x, y, graph(), height));
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
            edges(g2d, row.top(), x, y, mid, true);
            edges(g2d, row.bottom(), x, mid, y + height, false);
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
        if (!(line instanceof LogLine l))
            return null;
        if (x < strip())
            return log.repositoryOf(l.commit()).root().toString();
        return l.graph() == null ? null : graphToolTip(l, x - strip(), y);
    }

    @Override
    public boolean isClickable(Line line, int x, int y) {
        return line instanceof LogLine l && (x < strip() || arrowAt(l, x - strip(), y) != null);
    }

    // On the strip, the repository alone, or all of them again when it is
    // so already; on an arrow, the lane's other end.
    @Override
    public boolean clicked(Editor editor, Line line, int x, int y) {
        if (!(line instanceof LogLine l))
            return false;
        if (x >= strip()) {
            final LogGraph.Edge arrow = arrowAt(l, x - strip(), y);
            if (arrow == null)
                return false;
            log.jump(editor, arrow.jump());
            return true;
        }
        final String name = log.getScope().name(log.repositoryOf(l.commit()));
        LogFilters.REPOSITORY.binding().set(editor, name.equals(log.getQuery().repository()) ? null : name);
        FilterBar.refreshAll(log);
        return true;
    }

    private String graphToolTip(LogLine l, int x, int y) {
        final LogGraph.Row row = l.graph();
        final float mid = l.getHeight() / 2f;
        if (Math.abs(x - center(0, row.column())) <= laneWidth() / 2f && Math.abs(y - mid) <= radius() * 2)
            return row.lane().name();
        final LogGraph.Edge e = edgeAt(l, x, y);
        if (e == null)
            return null;
        return switch (e.arrow()) {
            case NONE -> e.lane().name();
            case DOWN -> e.lane().name() + ": goes on down; click to go to it";
            case UP -> e.lane().name() + ": comes from up above; click to go to it";
        };
    }

    // The nearest edge in the half row at y, along its curve, or null.
    private static LogGraph.Edge edgeAt(LogLine l, int x, int y) {
        final LogGraph.Row row = l.graph();
        final float mid = l.getHeight() / 2f;
        final boolean top = y < mid;
        final float t = top ? y / mid : (y - mid) / (l.getHeight() - mid);
        final float s = t * t * (3 - 2 * t);
        LogGraph.Edge nearest = null;
        float distance = laneWidth() / 2f;
        for (LogGraph.Edge e : top ? row.top() : row.bottom()) {
            final float x0 = center(0, e.from());
            final float d = Math.abs(x - (x0 + (center(0, e.to()) - x0) * s));
            if (d <= distance) {
                distance = d;
                nearest = e;
            }
        }
        return nearest;
    }

    // The edge with an arrow at x and y, or null.
    private static LogGraph.Edge arrowAt(LogLine l, int x, int y) {
        if (l.graph() == null)
            return null;
        final LogGraph.Edge e = edgeAt(l, x, y);
        return e != null && e.arrow() != LogGraph.Arrow.NONE ? e : null;
    }

    // Each edge from its lane at y0 to its lane at y1: straight, or an S
    // curve; a stub's with its arrow, an arrow at the row's top set in, so
    // it is whole.
    private static void edges(Graphics2D g, List<LogGraph.Edge> edges, int x, float y0, float y1, boolean top) {
        final float a = laneWidth() * 0.3f;
        for (LogGraph.Edge e : edges) {
            final float x0 = center(x, e.from());
            final float x1 = center(x, e.to());
            g.setColor(color(e.color()));
            switch (e.arrow()) {
                case DOWN -> {
                    g.draw(new Line2D.Float(x1, y0, x1, y1));
                    chevron(g, x1, y1, a, 1);
                }
                case UP -> {
                    final float tip = top ? y0 + a : y0;
                    g.draw(new Line2D.Float(x0, tip, x0, y1));
                    chevron(g, x0, tip, a, -1);
                }
                case NONE -> {
                    if (e.from() == e.to())
                        g.draw(new Line2D.Float(x0, y0, x1, y1));
                    else {
                        final float ym = (y0 + y1) / 2;
                        g.draw(new CubicCurve2D.Float(x0, y0, x0, ym, x1, ym, x1, y1));
                    }
                }
            }
        }
    }

    // An arrowhead's two strokes back from its tip at x and y, pointing down
    // for 1 or up for -1.
    private static void chevron(Graphics2D g, float x, float y, float size, int down) {
        g.draw(new Line2D.Float(x - size, y - down * size, x, y));
        g.draw(new Line2D.Float(x + size, y - down * size, x, y));
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
