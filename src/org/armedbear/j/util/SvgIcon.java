/*
 * SvgIcon.java
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

package org.armedbear.j.util;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Turns one of j's SVG icons into Java2D geometry.
 *
 * <p>This is a tiny java2d svg renderer that only implements a few
 * features such as straight lines and arcs.
 *
 * <p>This is not a general SVG implementation and is not meant to become one.
 * j's icons are drawn in house to a deliberately small subset -- three
 * elements, straight lines and elliptical arcs -- so that they can be painted
 * at any size, in any colour, without a rendering library. Everything the icon
 * set uses is listed in {@code doc/icons.html}; anything else throws rather
 * than drawing something subtly wrong.
 *
 * <p>Parsing is the expensive part and the result is immutable, so a drawing is
 * parsed once per icon and shared by every size and colour it is painted at.
 * See {@link Utilities#getIconFromFile} for the cache that callers actually go
 * through.
 */
public final class SvgIcon
{
    /** One shape plus the paint settings in force where it appeared. */
    private static final class Op
    {
        Shape shape;
        Color fill;
        Color stroke;
        float width = 1f;
        int cap = BasicStroke.CAP_BUTT;
        int join = BasicStroke.JOIN_MITER;
        float[] dash;
    }

    private final String name;
    private final double viewBoxWidth;
    private final double viewBoxHeight;
    private final List<Op> ops = new ArrayList<Op>();

    /**
     * Parses an icon from the classpath resource
     * {@code org/armedbear/j/images/svg/<name>.svg}.
     *
     * @param defaultColor resolves {@code currentColor} when the file doesn't
     *                     name a colour of its own; may be null.
     */
    public SvgIcon(String name, Color defaultColor) throws Exception
    {
        this(name, open(name), defaultColor);
    }

    private static InputStream open(String name)
    {
        final String path = "images/svg/" + name + ".svg";
        InputStream in = org.armedbear.j.Editor.class.getResourceAsStream(path);
        if (in == null)
            throw new IllegalArgumentException("no such icon: " + path);
        return in;
    }

    /**
     * Parses an icon from an already opened stream, which is closed before this
     * returns.
     *
     * <p>Exists so that a test can point at the icon sources in the source tree
     * rather than whatever copy happens to be on the classpath -- those two can
     * drift, and a test that lists one while loading the other quietly stops
     * checking anything.
     */
    public SvgIcon(String name, InputStream in, Color defaultColor) throws Exception
    {
        this.name = name;
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(in);
        }
        finally {
            try { in.close(); } catch (Exception ignored) {}
        }

        Element root = doc.getDocumentElement();
        double[] box = numbers(root.getAttribute("viewBox"));
        viewBoxWidth = box.length == 4 ? box[2] : 16;
        viewBoxHeight = box.length == 4 ? box[3] : 16;

        // Presentation attributes on <svg> are inherited by everything below.
        Map<String, String> inherited = new HashMap<String, String>();
        final String[] presentation = {
            "fill", "stroke", "stroke-width", "stroke-linecap",
            "stroke-linejoin", "stroke-dasharray", "color"
        };
        for (int i = 0; i < presentation.length; i++) {
            String value = root.getAttribute(presentation[i]);
            if (value != null && value.length() > 0)
                inherited.put(presentation[i], value);
        }

        Color current = defaultColor;
        if (current == null)
            current = parseColor(inherited.get("color"), Color.BLACK);
        walk(root, inherited, current);
    }

    public double getViewBoxWidth()
    {
        return viewBoxWidth;
    }

    /** Paints the icon into a {@code size} by {@code size} box at the origin. */
    public void paint(Graphics2D g, int size)
    {
        Graphics2D g2d = (Graphics2D) g.create();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                 RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                                 RenderingHints.VALUE_STROKE_PURE);
            g2d.scale(size / viewBoxWidth, size / viewBoxHeight);
            for (int i = 0; i < ops.size(); i++) {
                Op op = ops.get(i);
                if (op.fill != null) {
                    g2d.setColor(op.fill);
                    g2d.fill(op.shape);
                }
                if (op.stroke != null) {
                    g2d.setColor(op.stroke);
                    g2d.setStroke(op.dash == null
                        ? new BasicStroke(op.width, op.cap, op.join, 10f)
                        : new BasicStroke(op.width, op.cap, op.join, 10f, op.dash, 0f));
                    g2d.draw(op.shape);
                }
            }
        }
        finally {
            g2d.dispose();
        }
    }

    /**
     * Paints a fattened silhouette of the icon.
     *
     * <p>Every shape is stroked at its own width plus {@code spread}, filled
     * shapes included, giving an outline that stands a little clear of the
     * drawing. Painted with the Graphics' current paint and composite, so the
     * caller decides what it means: {@link Utilities#getBadgedIcon} clears with it,
     * which opens a gap between a badge and whatever it sits on top of.
     */
    public void paintOutline(Graphics2D g, int size, float spread)
    {
        Graphics2D g2d = (Graphics2D) g.create();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                 RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                                 RenderingHints.VALUE_STROKE_PURE);
            g2d.scale(size / viewBoxWidth, size / viewBoxHeight);
            for (int i = 0; i < ops.size(); i++) {
                Op op = ops.get(i);
                if (op.fill == null && op.stroke == null)
                    continue;
                // A filled shape has no stroke width of its own to widen.
                final float width = (op.stroke != null ? op.width : 0f) + spread;
                g2d.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND,
                                              BasicStroke.JOIN_ROUND, 10f));
                g2d.draw(op.shape);
                if (op.fill != null)
                    g2d.fill(op.shape);
            }
        }
        finally {
            g2d.dispose();
        }
    }

    private void walk(Node parent, Map<String, String> inherited, Color current)
    {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE)
                continue;
            Element e = (Element) n;

            Map<String, String> attrs = new HashMap<String, String>(inherited);
            NamedNodeMap map = e.getAttributes();
            for (int i = 0; i < map.getLength(); i++)
                attrs.put(map.item(i).getNodeName(), map.item(i).getNodeValue());

            final String tag = e.getTagName();
            Shape shape = null;
            if (tag.equals("path")) {
                shape = parsePath(attrs.get("d"));
            } else if (tag.equals("circle")) {
                double cx = number(attrs.get("cx"));
                double cy = number(attrs.get("cy"));
                double r = number(attrs.get("r"));
                shape = new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
            } else if (tag.equals("g")) {
                walk(e, attrs, current);
                continue;
            } else {
                throw new IllegalArgumentException(
                    name + ".svg: unsupported element <" + tag + ">");
            }
            if (shape == null)
                continue;

            Op op = new Op();
            op.shape = shape;
            op.fill = parsePaint(attrs.get("fill"), current);
            op.stroke = parsePaint(attrs.get("stroke"), current);
            String width = attrs.get("stroke-width");
            if (width != null)
                op.width = (float) number(width);
            op.cap = "round".equals(attrs.get("stroke-linecap")) ? BasicStroke.CAP_ROUND
                   : "square".equals(attrs.get("stroke-linecap")) ? BasicStroke.CAP_SQUARE
                   : BasicStroke.CAP_BUTT;
            op.join = "round".equals(attrs.get("stroke-linejoin")) ? BasicStroke.JOIN_ROUND
                    : "bevel".equals(attrs.get("stroke-linejoin")) ? BasicStroke.JOIN_BEVEL
                    : BasicStroke.JOIN_MITER;
            String dash = attrs.get("stroke-dasharray");
            if (dash != null && !dash.equals("none")) {
                double[] values = numbers(dash);
                op.dash = new float[values.length];
                for (int i = 0; i < values.length; i++)
                    op.dash[i] = (float) values[i];
            }
            ops.add(op);
        }
    }

    // ---------------------------------------------------------------- paint

    private static Color parsePaint(String s, Color current)
    {
        if (s == null || s.equals("none"))
            return null;
        if (s.equals("currentColor"))
            return current;
        return parseColor(s, null);
    }

    public static Color parseColor(String s, Color defaultColor)
    {
        if (s == null)
            return defaultColor;
        s = s.trim();
        try {
            if (s.startsWith("#")) {
                if (s.length() == 4) {
                    return new Color(Integer.parseInt("" + s.charAt(1) + s.charAt(1), 16),
                                     Integer.parseInt("" + s.charAt(2) + s.charAt(2), 16),
                                     Integer.parseInt("" + s.charAt(3) + s.charAt(3), 16));
                }
                if (s.length() == 7)
                    return new Color(Integer.parseInt(s.substring(1), 16));
            }
        }
        catch (NumberFormatException ignored) {}
        return defaultColor;
    }

    // -------------------------------------------------------------- numbers

    private static double number(String s)
    {
        return (s == null || s.length() == 0) ? 0 : Double.parseDouble(s.trim());
    }

    private static double[] numbers(String s)
    {
        if (s == null || s.length() == 0)
            return new double[0];
        String[] parts = s.trim().split("[\\s,]+");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++)
            values[i] = Double.parseDouble(parts[i]);
        return values;
    }

    // ------------------------------------------------------------ path data

    private Path2D.Double parsePath(String d)
    {
        if (d == null)
            return null;
        Path2D.Double path = new Path2D.Double();
        Tokenizer t = new Tokenizer(d);
        double x = 0, y = 0, startX = 0, startY = 0;
        char command = 0;
        while (t.hasNext()) {
            if (t.atCommand())
                command = t.command();
            final boolean relative = Character.isLowerCase(command);
            switch (Character.toUpperCase(command)) {
                case 'M': {
                    double nx = t.number(), ny = t.number();
                    if (relative) { nx += x; ny += y; }
                    path.moveTo(nx, ny);
                    x = startX = nx;
                    y = startY = ny;
                    // Any further coordinate pairs after a moveto are linetos.
                    command = relative ? 'l' : 'L';
                    break;
                }
                case 'L': {
                    double nx = t.number(), ny = t.number();
                    if (relative) { nx += x; ny += y; }
                    path.lineTo(nx, ny);
                    x = nx; y = ny;
                    break;
                }
                case 'H': {
                    double nx = t.number();
                    if (relative) nx += x;
                    path.lineTo(nx, y);
                    x = nx;
                    break;
                }
                case 'V': {
                    double ny = t.number();
                    if (relative) ny += y;
                    path.lineTo(x, ny);
                    y = ny;
                    break;
                }
                case 'A': {
                    double rx = t.number(), ry = t.number(), rotation = t.number();
                    boolean large = t.number() != 0;
                    boolean sweep = t.number() != 0;
                    double nx = t.number(), ny = t.number();
                    if (relative) { nx += x; ny += y; }
                    arcTo(path, x, y, rx, ry, rotation, large, sweep, nx, ny);
                    x = nx; y = ny;
                    break;
                }
                case 'Z': {
                    path.closePath();
                    x = startX; y = startY;
                    break;
                }
                default:
                    throw new IllegalArgumentException(
                        name + ".svg: unsupported path command '" + command + "' in \"" + d + "\"");
            }
        }
        return path;
    }

    /**
     * Appends an SVG elliptical arc.
     *
     * <p>SVG states an arc by where it ends; Arc2D wants a centre, a start
     * angle and a sweep. The conversion is the one piece of real geometry in
     * this class and follows SVG 1.1 appendix F.6.5.
     */
    private static void arcTo(Path2D.Double path, double x1, double y1,
                              double rx, double ry, double rotationDegrees,
                              boolean large, boolean sweep, double x2, double y2)
    {
        if (rx == 0 || ry == 0) {
            path.lineTo(x2, y2);
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        final double phi = Math.toRadians(rotationDegrees % 360.0);
        final double cosPhi = Math.cos(phi);
        final double sinPhi = Math.sin(phi);
        final double dx = (x1 - x2) / 2.0;
        final double dy = (y1 - y2) / 2.0;
        final double x1p =  cosPhi * dx + sinPhi * dy;
        final double y1p = -sinPhi * dx + cosPhi * dy;

        // Radii too small to reach the endpoint are scaled up, per the spec.
        final double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            rx *= s;
            ry *= s;
        }

        final double sign = (large == sweep) ? -1 : 1;
        final double numerator = rx * rx * ry * ry
                               - rx * rx * y1p * y1p
                               - ry * ry * x1p * x1p;
        final double denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        final double coefficient = sign * Math.sqrt(Math.max(0, numerator / denominator));
        final double cxp =  coefficient * rx * y1p / ry;
        final double cyp = -coefficient * ry * x1p / rx;
        final double cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0;
        final double cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0;

        final double theta = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry,
                             (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && delta > 0)
            delta -= 2 * Math.PI;
        else if (sweep && delta < 0)
            delta += 2 * Math.PI;

        // Arc2D measures angles counter-clockwise; SVG's y axis points down,
        // which flips the sign of both the start angle and the sweep.
        Arc2D.Double arc = new Arc2D.Double(-rx, -ry, rx * 2, ry * 2,
                                            Math.toDegrees(-theta),
                                            Math.toDegrees(-delta),
                                            Arc2D.OPEN);
        AffineTransform tx = AffineTransform.getTranslateInstance(cx, cy);
        tx.rotate(phi);
        path.append(tx.createTransformedShape(arc), true);
    }

    private static double angle(double ux, double uy, double vx, double vy)
    {
        final double dot = ux * vx + uy * vy;
        final double length = Math.sqrt(ux * ux + uy * uy) * Math.sqrt(vx * vx + vy * vy);
        final double a = Math.acos(Math.max(-1, Math.min(1, dot / length)));
        return (ux * vy - uy * vx < 0) ? -a : a;
    }

    /** Splits path data into commands and numbers. */
    private static final class Tokenizer
    {
        private final String s;
        private int i;

        Tokenizer(String s)
        {
            this.s = s;
        }

        private void skipWhitespace()
        {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r')
                    ++i;
                else
                    break;
            }
        }

        boolean hasNext()
        {
            skipWhitespace();
            return i < s.length();
        }

        boolean atCommand()
        {
            skipWhitespace();
            return i < s.length() && Character.isLetter(s.charAt(i));
        }

        char command()
        {
            skipWhitespace();
            return s.charAt(i++);
        }

        double number()
        {
            skipWhitespace();
            final int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+'))
                ++i;
            // Only one '.' belongs to a number: in path data "3.5.5" is two
            // numbers, 3.5 and .5, and a greedy scan silently merges them.
            boolean seenDot = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) {
                    ++i;
                } else if (c == '.' && !seenDot) {
                    seenDot = true;
                    ++i;
                } else {
                    break;
                }
            }
            if (start == i)
                throw new IllegalArgumentException("expected a number at offset " + i + " of \"" + s + "\"");
            return Double.parseDouble(s.substring(start, i));
        }
    }
}
