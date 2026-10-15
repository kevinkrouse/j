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
import java.awt.Image;
import java.awt.image.AbstractMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.GrayFilter;
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
    private static final HashMap<String, SvgIcon> svgCache = new HashMap<>();

    // Painted icons, keyed by name + badges and size. Renderers ask for an icon
    // on every row of every repaint, so this is the cache that matters.
    private static final HashMap<String, ImageIcon> iconCache = new HashMap<>();

    private static Color iconColor;

    /** An icon from j's own set, at the standard size, scaled for the display. */
    public static ImageIcon getIconFromFile(String name) {
        return getIconFromFile(name, UIScale.scale(ICON_SIZE));
    }

    /** The icon rendered at exactly {@code size} pixels. */
    public static ImageIcon getIconFromFile(String name, int size) {
        return getIconFromFile(name, size, null);
    }

    /** The icon at {@code size} pixels drawn in color, or in the icons' own with null. */
    public static synchronized ImageIcon getIconFromFile(String name, int size, Color color) {
        if (size <= 0)
            return null;
        final String key = colored(name, color) + '@' + size;
        if (iconCache.containsKey(key))
            return iconCache.get(key);

        ImageIcon icon = null;
        try {
            SvgIcon svg = color == null ? getSvgIcon(name) : getSvgIcon(name, color);
            if (svg != null)
                icon = new ImageIcon(new VectorImage(size, svg::paint));
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

    public static synchronized ImageIcon getBadgedIcon(int size, String base, String... badges) {
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
                final List<SvgIcon> badgeIcons = new ArrayList<>();
                for (int i = 0; i < badges.length; i++) {
                    if (badges[i] != null && getSvgIcon(badges[i]) != null)
                        badgeIcons.add(getSvgIcon(badges[i]));
                }
                icon = new ImageIcon(new VectorImage(size, (g2d, pixels) -> {
                    baseIcon.paint(g2d, pixels);
                    for (SvgIcon badge : badgeIcons) {
                        g2d.setComposite(AlphaComposite.Clear);
                        badge.paintOutline(g2d, pixels, BADGE_GAP);
                        g2d.setComposite(AlphaComposite.SrcOver);
                        badge.paint(g2d, pixels);
                    }
                }));
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
        return getSvgIcon(name, getIconColor());
    }

    private static SvgIcon getSvgIcon(String name, Color color) throws Exception {
        final String key = colored(name, color == getIconColor() ? null : color);
        if (svgCache.containsKey(key))
            return svgCache.get(key);
        SvgIcon svg = null;
        try {
            svg = new SvgIcon(name, color);
        }
        catch (IllegalArgumentException e) {
            Log.warn("failed to get icon: " + name + " (" + e.getMessage() + ")");
        }
        svgCache.put(key, svg);
        return svg;
    }

    // A cache key: the name, and the color when not the icons' own.
    private static String colored(String name, Color color) {
        return color == null ? name : name + '#' + Integer.toHexString(color.getRGB());
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

    /**
     * The grayed out icon a disabled button shows. Swing would make one from
     * the icon's 16 pixel bitmap, which blurs when the window is scaled.
     */
    public static synchronized ImageIcon getDisabledIcon(ImageIcon icon) {
        if (icon == null || !(icon.getImage() instanceof VectorImage image))
            return null;
        if (image.disabled != null)
            return image.disabled;
        final GrayFilter filter = new GrayFilter(true, 50);
        image.disabled = new ImageIcon(new VectorImage(image.size, (g2d, pixels) -> {
            final BufferedImage gray = image.variant(pixels);
            final int[] rgb = gray.getRGB(0, 0, pixels, pixels, null, 0, pixels);
            for (int i = 0; i < rgb.length; i++)
                rgb[i] = filter.filterRGB(0, 0, rgb[i]);
            final BufferedImage out = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB);
            out.setRGB(0, 0, pixels, pixels, rgb, 0, pixels);
            g2d.drawImage(out, 0, 0, null);
        }));
        return image.disabled;
    }

    /**
     * An icon drawn afresh at the size it is shown at in device pixels, so
     * that it stays sharp when the JDK scales the window, as it does at 2x or
     * under Wayland, rather than being a 16 pixel bitmap stretched to fit.
     */
    private static final class VectorImage extends AbstractMultiResolutionImage {
        private final int size;
        private final BiConsumer<Graphics2D, Integer> painter;
        // Drawn so far, by size in pixels.
        private final HashMap<Integer, BufferedImage> variants = new HashMap<>();
        // Its grayed out self, made when first asked for.
        ImageIcon disabled;

        VectorImage(int size, BiConsumer<Graphics2D, Integer> painter) {
            this.size = size;
            this.painter = painter;
        }

        @Override
        protected Image getBaseImage() {
            return variant(size);
        }

        @Override
        public Image getResolutionVariant(double width, double height) {
            return variant((int) Math.ceil(Math.max(width, height) - 0.01));
        }

        @Override
        public synchronized List<Image> getResolutionVariants() {
            variant(size);
            return new ArrayList<>(variants.values());
        }

        private synchronized BufferedImage variant(int pixels) {
            final int key = Math.max(1, pixels);
            BufferedImage image = variants.get(key);
            if (image == null) {
                image = new BufferedImage(key, key, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2d = image.createGraphics();
                try {
                    painter.accept(g2d, key);
                }
                finally {
                    g2d.dispose();
                }
                variants.put(key, image);
            }
            return image;
        }
    }

    /** Called when uiScale, the theme, or any other icon input changes. */
    public static synchronized void clearIconCache() {
        iconCache.clear();
        svgCache.clear();
        iconColor = null;
    }
}
