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
    private static final boolean isPlatformWindows =
        osName.startsWith("Windows");
    // Java 25 runs on Linux, macOS, AIX and Windows.
    private static final boolean isPlatformUnix = !isPlatformWindows;
    private static final boolean isPlatformMacOSX =
        osName.contains("OS X");

    public static final boolean isPlatformLinux() {
        return isPlatformLinux;
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
}
