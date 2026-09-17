/*
 * Main.java
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

import java.lang.reflect.Method;

public final class Main
{
    public static void main(String[] args)
    {
        final String version = System.getProperty("java.version");
        final int majorVersion = Integer.parseInt(version.split("\\.")[0]);
        System.out.println("java version: " + version);
        System.out.println("java major version: " + majorVersion);
        if (majorVersion < 25) {
            System.err.println("J requires Java 25 or later.");
            System.exit(1);
        }

        // need to set the app name property before AWT is loaded
        System.setProperty("apple.awt.application.name", "J");
        System.setProperty("apple.awt.graphics.EnableQ2DX", "true");

        try {
            Class<?> c = Class.forName("org.armedbear.j.Editor");
            Method method = c.getMethod("main", String[].class);
            Object[] parameters = new Object[1];
            parameters[0] = args;
            method.invoke(null, parameters);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
}
