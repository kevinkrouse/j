/*
 * DefaultLookAndFeel.java
 *
 * Copyright (C) 2000-2003 Peter Graves
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
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import org.armedbear.j.util.Icons;

public final class DefaultLookAndFeel extends DefaultMetalTheme {
    private static final Preferences preferences = Editor.preferences();

    private final ColorUIResource primary1 = new ColorUIResource(0, 0, 0); // Black.

    private FontUIResource plainFont;

    public static void setLookAndFeel() {
        // This is the default.
        String lookAndFeelClassName =
            "javax.swing.plaf.metal.MetalLookAndFeel";

        Editor.lookAndFeel =
            preferences.getStringProperty(Property.LOOK_AND_FEEL);

        if (Editor.lookAndFeel == null) {
            if (Platform.isPlatformMacOSX())
                Editor.lookAndFeel = "Aqua";
        }

        if (Editor.lookAndFeel != null) {
            // User has indicated a preference.
            if (Editor.lookAndFeel.equals("System")) {
                lookAndFeelClassName = UIManager.getSystemLookAndFeelClassName();
            } else if (Editor.lookAndFeel.equals("Metal")) {
                ; // Default look and feel, but don't do customizations.
            } else {
                final String className = installedLookAndFeel(Editor.lookAndFeel);
                if (className != null) {
                    lookAndFeelClassName = className;
                } else {
                    Log.warn("lookAndFeel " + Editor.lookAndFeel + " is not installed");
                    Editor.lookAndFeel = null;
                }
            }
        }
        if (Editor.lookAndFeel == null) {
            // Default customizations.
            MetalLookAndFeel.setCurrentTheme(new DefaultLookAndFeel());
            UIManager.put(
                "Tree.collapsedIcon",
                Icons.getIconFromFile("collapsed")
            );
            UIManager.put(
                "Tree.expandedIcon",
                Icons.getIconFromFile("expanded")
            );
        } else {
            MetalLookAndFeel.setCurrentTheme(new DefaultMetalTheme());
        }
        try {
            UIManager.setLookAndFeel(lookAndFeelClassName);
        }
        catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
            Log.error(e);
        }
        // We want to do this in any case.
        UIManager.put("ToolBarUI", "org.armedbear.j.ToolBarUI");
        // Using the Metal button on OSX is ugly and shows an red/orange background when pressed that I'm not sure how to get rid of
        if (!Platform.isPlatformMacOSX())
            UIManager.put("ButtonUI", "org.armedbear.j.ButtonUI");
        UIManager.put("LabelUI", "org.armedbear.j.LabelUI");
        UIManager.put("SplitPane.dividerSize", 1);
    }

    // The class of the installed look and feel with this name, or null.
    // "Aqua" and "Motif" are the names this preference used to take.
    private static String installedLookAndFeel(String name) {
        if (name.equals("Aqua"))
            name = "Mac OS X";
        else if (name.equals("Motif"))
            name = "CDE/Motif";
        for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
            if (info.getName().equalsIgnoreCase(name))
                return info.getClassName();
        }
        return null;
    }

    private DefaultLookAndFeel() {
        String name = preferences.getStringProperty(Property.DIALOG_FONT_NAME);
        int size = UIScale.scaledProperty(preferences, Property.DIALOG_FONT_SIZE);
        Font font = new Font(name, Font.PLAIN, size);
        plainFont = new FontUIResource(font);
    }

    @Override
    public void addCustomEntriesToTable(UIDefaults table) {
        table.put("Button.border", BorderFactory.createRaisedBevelBorder());
        table.put("TextField.border", BorderFactory.createLoweredBevelBorder());
        table.put("SplitPaneUI", "javax.swing.plaf.basic.BasicSplitPaneUI");
        table.put("ScrollBarUI", "org.armedbear.j.ScrollBarUI");
        table.put("TreeUI", "javax.swing.plaf.basic.BasicTreeUI");
        table.put("SplitPane.dividerSize", 1);
        table.put("ScrollBar.background", new Color(0xe0e0e0));
        table.put("ScrollBar.foreground", new Color(0xc0c0c0));
        table.put("ScrollBar.track", new Color(0xe0e0e0));
        table.put("ScrollBar.trackHighlight", Color.black);
        table.put("ScrollBar.thumb", new Color(0xc0c0c0));
        table.put("ScrollBar.thumbHighlight", Color.white);
        table.put("ScrollBar.thumbDarkShadow", Color.black);
        table.put("ScrollBar.thumbShadow", new Color(0x808080));
        table.put("ScrollBar.width", UIScale.scale(16));
        table.put("Button.textIconGap", UIScale.scale(1));
        // Metal sizes the tree indents in whole pixels, so they stay narrow
        // while the tree font grows.
        table.put("Tree.leftChildIndent", UIScale.scale(7));
        table.put("Tree.rightChildIndent", UIScale.scale(13));
        // Tree.expandedIcon and Tree.collapsedIcon need no entry here: they
        // are replaced below with j's own images, which the icon loader
        // already scales, and a UIManager.put outranks this table anyway.
        table.put("ToolTipUI", "org.armedbear.j.ToolTipUI");
    }

    @Override
    protected ColorUIResource getPrimary1() {
        return primary1;
    }

    @Override
    public FontUIResource getControlTextFont() {
        return plainFont;
    }

    @Override
    public FontUIResource getSystemTextFont() {
        return plainFont;
    }

    @Override
    public FontUIResource getUserTextFont() {
        return plainFont;
    }

    @Override
    public FontUIResource getMenuTextFont() {
        return plainFont;
    }

    @Override
    public FontUIResource getWindowTitleFont() {
        return plainFont;
    }

    @Override
    public FontUIResource getSubTextFont() {
        return plainFont;
    }
}
