/*
 * Platform.java
 *
 * Copyright (C) 1998-2007 Peter Graves
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.armedbear.j;

public final class Platform {
    private static final String osName = System.getProperty("os.name");
    private static final boolean isPlatformLinux = osName.startsWith("Linux");
    private static final boolean isPlatformSunOS = osName.startsWith("SunOS");
    private static final boolean isPlatformFreeBSD = osName.startsWith("FreeBSD");
    private static final boolean isPlatformUnix =
        isPlatformLinux
            || osName.contains("OS X")
            ||
            osName.startsWith("Solaris")
            || isPlatformSunOS
            || isPlatformFreeBSD
            ||
            osName.startsWith("AIX");
    private static final boolean isPlatformWindows =
        osName.startsWith("Windows");
    private static final boolean isPlatformMacOSX =
        osName.contains("OS X");

    public static final boolean isPlatformLinux() {
        return isPlatformLinux;
    }

    public static final boolean isPlatformSunOS() {
        return isPlatformSunOS;
    }

    public static final boolean isPlatformFreeBSD() {
        return isPlatformFreeBSD;
    }

    public static final boolean isPlatformUnix() {
        return isPlatformUnix;
    }

    public static final boolean isPlatformWindows() {
        return isPlatformWindows;
    }

    /** Whether file names that differ only in case name the same file, as j assumes on Windows. */
    public static boolean isFileSystemCaseInsensitive() {
        return isPlatformWindows();
    }

    public static final boolean isPlatformMacOSX() {
        return isPlatformMacOSX;
    }

    public static final boolean isPlatformWindows5() {
        if (!isPlatformWindows)
            return false;
        final String version = System.getProperty("os.version");
        int index = version.indexOf('.');
        final String s = index >= 0 ? version.substring(0, index) : version;
        try {
            final int major = Integer.parseInt(s);
            return major >= 5;
        }
        catch (NumberFormatException e) {
            Log.error(e);
            return false;
        }
    }

    public static final boolean isPlatformWindowsNT4() {
        if (!isPlatformWindows)
            return false;
        if (osName.indexOf("Windows NT") < 0)
            return false;
        final String version = System.getProperty("os.version");
        int index = version.indexOf('.');
        final String s = index >= 0 ? version.substring(0, index) : version;
        try {
            final int major = Integer.parseInt(s);
            return major >= 4;
        }
        catch (NumberFormatException e) {
            Log.error(e);
            return false;
        }
    }
}
