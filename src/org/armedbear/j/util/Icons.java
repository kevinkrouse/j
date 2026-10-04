/*
 * Icons.java
 *
 * Copyright (C) 1998-2005 Peter Graves
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import javax.swing.ImageIcon;
import org.armedbear.j.Editor;
import org.armedbear.j.Log;
import org.armedbear.j.Preferences;
import org.armedbear.j.Property;
import org.armedbear.j.UIScale;

/** j's icons, drawn from SVG at the display's scale and cached. */
public final class Icons {
    private Icons() {}

    // The size j's icons are drawn for before the display scale is applied.
    public static final int ICON_SIZE = 16;

    // Geometry, parsed once per icon and shared by every size and color.
    private static final HashMap<String, SvgIcon> svgCache =
        new HashMap<>();

    // Painted icons, keyed by name + badges and size. Renderers ask for an icon
    // on every row of every repaint, so this is the cache that matters.
    private static final HashMap<String, ImageIcon> iconCache =
        new HashMap<>();

    private static Color iconColor;

    /** An icon from j's own set, at the standard size, scaled for the display. */
    public static ImageIcon getIconFromFile(String name) {
        return getIconFromFile(name, UIScale.scale(ICON_SIZE));
    }

    /** The icon rendered at exactly {@code size} pixels. */
    public static synchronized ImageIcon getIconFromFile(String name, int size) {
        if (size <= 0)
            return null;
        final String key = name + '@' + size;
        if (iconCache.containsKey(key))
            return iconCache.get(key);

        ImageIcon icon = null;
        try {
            SvgIcon svg = getSvgIcon(name);
            if (svg != null) {
                BufferedImage image =
                    new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2d = image.createGraphics();
                try {
                    svg.paint(g2d, size);
                }
                finally {
                    g2d.dispose();
                }
                icon = new ImageIcon(image);
            }
        }
        catch (Throwable t) {
            Log.error(t);
        }
        iconCache.put(key, icon);
        return icon;
    }

    /**
     * A base icon with badges drawn over it, separated by a cleared gap.
     *
     * <p>A badge sits in a corner of the same 16 by 16 field as the icon it
     * marks, so the two can touch. Each badge first erases a fattened silhouette
     * of itself from what has been drawn so far. Because this is composited
     * into a transparent image, erasing shows the background the icon is sitting
     * on, whatever that happens to be, rather than punching a hole in the row.
     *
     * @param badges drawn in order; nulls are skipped so a caller can pass a
     *               badge it may not have without branching.
     */
    public static ImageIcon getBadgedIcon(String base, String... badges) {
        return getBadgedIcon(UIScale.scale(ICON_SIZE), base, badges);
    }

    public static synchronized ImageIcon getBadgedIcon(
        int size,
        String base,
        String... badges
    ) {
        if (size <= 0 || base == null)
            return null;
        StringBuilder sb = new StringBuilder(base);
        for (int i = 0; i < badges.length; i++) {
            if (badges[i] != null)
                sb.append('+').append(badges[i]);
        }
        sb.append('@').append(size);
        final String key = sb.toString();
        if (iconCache.containsKey(key))
            return iconCache.get(key);

        ImageIcon icon = null;
        try {
            SvgIcon baseIcon = getSvgIcon(base);
            if (baseIcon != null) {
                BufferedImage image =
                    new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2d = image.createGraphics();
                try {
                    baseIcon.paint(g2d, size);
                    for (int i = 0; i < badges.length; i++) {
                        if (badges[i] == null)
                            continue;
                        SvgIcon badge = getSvgIcon(badges[i]);
                        if (badge == null)
                            continue;
                        g2d.setComposite(AlphaComposite.Clear);
                        badge.paintOutline(g2d, size, BADGE_GAP);
                        g2d.setComposite(AlphaComposite.SrcOver);
                        badge.paint(g2d, size);
                    }
                }
                finally {
                    g2d.dispose();
                }
                icon = new ImageIcon(image);
            }
        }
        catch (Throwable t) {
            Log.error(t);
        }
        iconCache.put(key, icon);
        return icon;
    }

    // How far a badge holds the drawing underneath it at bay, in the icon's own
    // 16 unit coordinates.
    private static final float BADGE_GAP = 1.6f;

    private static SvgIcon getSvgIcon(String name) throws Exception {
        if (svgCache.containsKey(name))
            return svgCache.get(name);
        SvgIcon svg = null;
        try {
            svg = new SvgIcon(name, getIconColor());
        }
        catch (IllegalArgumentException e) {
            Log.warn("failed to get icon: " + name + " (" + e.getMessage() + ")");
        }
        svgCache.put(name, svg);
        return svg;
    }

    public static synchronized Color getIconColor() {
        if (iconColor == null) {
            Preferences preferences = Editor.preferences();
            if (preferences != null) {
                String s = preferences.getStringProperty(Property.ICON_COLOR);
                if (s != null && s.trim().length() > 0)
                    iconColor = SvgIcon.parseColor(s, null);
            }
        }
        return iconColor;
    }

    /** Called when uiScale, the theme, or any other icon input changes. */
    public static synchronized void clearIconCache() {
        iconCache.clear();
        svgCache.clear();
        iconColor = null;
    }
}
