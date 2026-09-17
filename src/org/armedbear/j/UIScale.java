/*
 * UIScale.java
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

package org.armedbear.j;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.armedbear.j.util.Utilities;

/**
 * The factor by which j's built-in pixel and point sizes are multiplied so
 * that they stay legible on a high resolution display.
 *
 * <p>j's defaults were chosen when 96 DPI was universal. There are two ways a
 * modern display departs from that, and they need opposite treatment:
 *
 * <ul>
 * <li>The JDK is already scaling. Swing then works in logical pixels and does
 * the magnification itself, so a 12 point font really is drawn with 24 device
 * pixels on a 2x display. Scaling here as well would double-count, so the
 * factor is 1.
 *
 * <li>The JDK is not scaling, which is the common case for a 4K monitor run
 * at 100% under X11. Every logical pixel is one device pixel, so j's defaults
 * come out physically tiny and we have to do the scaling ourselves.
 * </ul>
 *
 * <p>The factor is applied only to j's own defaults. A size the user has set
 * explicitly is always honoured as written -- see
 * {@link #scaledProperty(Preferences, Property)}.
 */
public final class UIScale
{
    // Resolution j's built-in sizes were chosen for.
    private static final double BASE_DPI = 96.0;

    // Below this a scale factor isn't worth the rounding error it introduces.
    private static final double MIN_SCALE = 1.0;

    // Past this we're almost certainly misreading the display rather than
    // looking at a genuinely enormous one.
    private static final double MAX_SCALE = 3.0;

    private static double scale = 0;

    // Bumped every time the factor is invalidated, so components can tell
    // whether they were built against the current one. See refresh().
    private static int generation = 0;

    private static final String GENERATION_KEY = "j.uiScale.generation";

    private UIScale()
    {
    }

    /**
     * The scale factor for this display. Computed once on first use, since it
     * is read from every paint path.
     */
    public static synchronized double getScale()
    {
        if (scale == 0)
            scale = computeScale();
        return scale;
    }

    /**
     * Recomputes the scale factor. Called when preferences are reloaded, so
     * that a change to uiScale takes effect without a restart.
     */
    public static void reset()
    {
        synchronized (UIScale.class) {
            scale = 0;
            ++generation;
        }
        Utilities.clearIconCache();
    }

    /**
     * Brings a component built under an earlier scale up to date.
     *
     * <p>Most of j's interface is rebuilt when preferences are reloaded, but
     * the mode-specific sidebar trees are cached on the View and outlive that
     * rebuild.
     *
     * <p>The generation it was last refreshed at is kept on the component, so
     * this costs a client-property lookup on the common path where nothing has
     * changed.
     */
    public static void refresh(Component c)
    {
        if (!(c instanceof JComponent))
            return;
        final JComponent component = (JComponent) c;
        final int current;
        synchronized (UIScale.class) {
            current = generation;
        }
        Object seen = component.getClientProperty(GENERATION_KEY);
        if (seen instanceof Integer && ((Integer) seen).intValue() == current)
            return;
        component.putClientProperty(GENERATION_KEY, Integer.valueOf(current));
        SwingUtilities.updateComponentTreeUI(component);
    }

    /**
     * Scales one of j's built-in sizes, rounding to whole pixels.
     */
    public static int scale(int size)
    {
        if (size <= 0)
            return size;
        int scaled = (int) Math.round(size * getScale());
        return scaled > 0 ? scaled : 1;
    }

    /**
     * The value of an integer preference, scaled for the display only if the
     * user hasn't set it.
     */
    public static int scaledProperty(Preferences preferences, Property property)
    {
        final int value = preferences.getIntegerProperty(property);
        if (preferences.isPropertySet(property))
            return value;
        return scale(value);
    }

    private static double computeScale()
    {
        final Preferences preferences = Editor.preferences();

        // An explicit uiScale overrides the detection entirely.
        if (preferences != null) {
            final String s = preferences.getStringProperty(Property.UI_SCALE);
            if (s != null && s.trim().length() > 0) {
                try {
                    final double d = Double.parseDouble(s.trim());
                    // 0 means "detect", which is the default.
                    if (d > 0) {
                        final double clamped = clamp(d);
                        Log.info("UIScale: using uiScale=" + clamped +
                                 " from preferences");
                        return clamped;
                    }
                }
                catch (NumberFormatException e) {
                    Log.error("UIScale: ignoring unparsable uiScale \"" + s + "\"");
                }
            }
        }

        if (GraphicsEnvironment.isHeadless())
            return 1.0;

        try {
            // If the JDK reports a scaling transform it is already doing the
            // work for us and we must not do it twice.
            final double jdkScale = getJdkScale();
            if (jdkScale > 1.0) {
                Log.info("UIScale: JDK is scaling by " + jdkScale +
                         "; using uiScale=1.0");
                return 1.0;
            }

            final int dpi = Toolkit.getDefaultToolkit().getScreenResolution();
            final double detected = clamp(quantize(dpi / BASE_DPI));
            Log.info("UIScale: screen resolution " + dpi + " dpi; using uiScale=" +
                     detected);
            return detected;
        }
        catch (Throwable t) {
            // A missing or unusual display is not worth failing to start over.
            Log.error(t);
            return 1.0;
        }
    }

    private static double getJdkScale()
    {
        final GraphicsEnvironment env =
            GraphicsEnvironment.getLocalGraphicsEnvironment();
        final GraphicsDevice device = env.getDefaultScreenDevice();
        if (device == null)
            return 1.0;
        final GraphicsConfiguration gc = device.getDefaultConfiguration();
        if (gc == null)
            return 1.0;
        final AffineTransform t = gc.getDefaultTransform();
        if (t == null)
            return 1.0;
        return Math.max(t.getScaleX(), t.getScaleY());
    }

    // Round to quarter steps. Reported DPI is frequently a pixel-density
    // calculation rather than a round number, and 1.4791666 would give sizes
    // that look arbitrary.
    private static double quantize(double d)
    {
        return Math.round(d * 4.0) / 4.0;
    }

    private static double clamp(double d)
    {
        if (d < MIN_SCALE)
            return MIN_SCALE;
        if (d > MAX_SCALE)
            return MAX_SCALE;
        return d;
    }
}
