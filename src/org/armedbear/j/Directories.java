/*
 * Directories.java
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

import java.util.ArrayList;
import java.util.List;

/**
 * Locates the directories J keeps its files in.
 *
 * <p>By default J follows the XDG Base Directory Specification, splitting what
 * used to live together in <code>~/.j</code> across four roots:
 *
 * <pre>
 *   $XDG_CONFIG_HOME/j   prefs, aliases, init.lisp, extension classes
 *   $XDG_DATA_HOME/j     mail, registers, addresses, news
 *   $XDG_STATE_HOME/j    sessions, props, recent, files.xml, autosave, log
 *   $XDG_CACHE_HOME/j    cache, tagfiles, temp
 *   $XDG_RUNTIME_DIR/j   port, swank
 * </pre>
 *
 * <p>For backwards compatibility an existing <code>~/.j</code> wins: while it
 * is there every root resolves to it and J behaves exactly as it always has.
 * Start J once with <code>--migrate-to-xdg</code> to move it into the layout
 * above.
 *
 * <p>The <code>$XDG_*</code> variables are honoured on every platform. Where
 * one is unset the fallback is native: <code>%APPDATA%</code> and
 * <code>%LOCALAPPDATA%</code> on Windows, <code>~/Library</code> on macOS, and
 * the specification's own <code>~/.config</code>, <code>~/.local/share</code>,
 * <code>~/.local/state</code> and <code>~/.cache</code> everywhere else.
 * <code>--home</code> overrides the environment entirely: the roots are then
 * always <code>.config</code>, <code>.local/share</code>,
 * <code>.local/state</code> and <code>.cache</code> below the directory given.
 */
public final class Directories {
    private static final String LEGACY_DIRECTORY_NAME = ".j";

    private static File userHomeDirectory; // ~
    private static File configDirectory; // ~/.config/j
    private static File dataDirectory; // ~/.local/share/j
    private static File stateDirectory; // ~/.local/state/j
    private static File cacheDirectory; // ~/.cache/j
    private static File runtimeDirectory; // $XDG_RUNTIME_DIR/j
    private static File tempDirectory; // ~/.cache/j/temp
    private static File mailDirectory; // ~/.local/share/j/mail
    private static File draftsFolder; // ~/.local/share/j/mail/local/drafts
    private static File registersDirectory; // ~/.local/share/j/registers

    // True when an existing ~/.j was found and every root points at it.
    private static boolean legacyLayout;

    // True when --home was given, in which case the $XDG_* variables are
    // ignored so that everything stays below the directory the user named.
    private static boolean homeSpecified;

    public static void initialize(File userHome) {
        initialize(userHome, false);
    }

    public static void initialize(File userHome, boolean migrate) {
        homeSpecified = userHome != null;
        userHomeDirectory = userHome;
        if (userHomeDirectory == null) {
            // Home directory was not specified on the command line.
            if (Platform.isPlatformWindows()) {
                // Look for existing .j directory.
                StringBuilder sb = new StringBuilder("C:\\");
                for (char c = 'C'; c <= 'Z'; c++) {
                    sb.setCharAt(0, c);
                    File dir = File.getInstance(sb.toString());
                    if (dir != null && dir.isDirectory() && dir.canWrite()) {
                        File subdir =
                            File.getInstance(dir, LEGACY_DIRECTORY_NAME);
                        if (subdir != null && subdir.isDirectory())
                            userHomeDirectory = dir;
                        break;
                    }
                }
                if (userHomeDirectory == null)
                    // No existing .j directory.
                    userHomeDirectory = File.getInstance(System.getenv("APPDATA"));
            } else {
                // Not Windows.
                userHomeDirectory =
                    File.getInstance(System.getProperty("user.home"));
            }
            if (userHomeDirectory == null)
                Startup.fatal("Use \"--home\" option to specify home directory.");
        }
        File legacy = File.getInstance(userHomeDirectory, LEGACY_DIRECTORY_NAME);
        if (migrate && legacy != null && legacy.isDirectory())
            migrateLegacyDirectory(legacy);
        if (legacy != null && legacy.isDirectory()) {
            // An existing ~/.j takes precedence over the XDG layout, so that
            // upgrading J never moves anybody's mail or preferences. Every
            // root resolves to it and the file names below are unchanged, so
            // this is the old behaviour exactly.
            legacyLayout = true;
            configDirectory = provideDirectory(legacy, true);
            dataDirectory = configDirectory;
            stateDirectory = configDirectory;
            cacheDirectory = configDirectory;
            runtimeDirectory = configDirectory;
        } else {
            legacyLayout = false;
            configDirectory = provideDirectory(appDirectory(configHome()), true);
            dataDirectory = provideDirectory(appDirectory(dataHome()));
            stateDirectory = provideDirectory(appDirectory(stateHome()));
            cacheDirectory = provideDirectory(appDirectory(cacheHome()));
            // $XDG_RUNTIME_DIR is frequently unset. The specification says to
            // fall back on a replacement with a similar lifetime, and the
            // state directory is the closest thing J has.
            File runtimeHome = fromEnvironment("XDG_RUNTIME_DIR");
            runtimeDirectory = runtimeHome != null
                ? provideDirectory(appDirectory(runtimeHome))
                : stateDirectory;
        }
        // Make sure the required subdirectories exist and are writable.
        tempDirectory =
            provideDirectory(File.getInstance(cacheDirectory, "temp"));
        mailDirectory =
            provideDirectory(File.getInstance(dataDirectory, "mail"));
        draftsFolder =
            provideDirectory(File.getInstance(mailDirectory, "local/drafts"));
        registersDirectory =
            provideDirectory(File.getInstance(dataDirectory, "registers"));
    }

    private static File appDirectory(File home) {
        return home != null ? File.getInstance(home, "j") : null;
    }

    /**
     * The value of an environment variable as a directory, or
     * <code>null</code> if it is unset, empty, or overridden by
     * <code>--home</code>.
     */
    private static File fromEnvironment(String name) {
        if (homeSpecified)
            return null;
        String value = System.getenv(name);
        if (value == null || value.length() == 0)
            return null;
        return File.getInstance(value);
    }

    private static File configHome() {
        File dir = fromEnvironment("XDG_CONFIG_HOME");
        if (dir == null && Platform.isPlatformWindows())
            dir = fromEnvironment("APPDATA");
        if (dir == null && Platform.isPlatformMacOSX())
            dir = File.getInstance(
                userHomeDirectory,
                "Library/Application Support"
            );
        if (dir == null)
            dir = File.getInstance(userHomeDirectory, ".config");
        return dir;
    }

    private static File dataHome() {
        File dir = fromEnvironment("XDG_DATA_HOME");
        if (dir == null && Platform.isPlatformWindows())
            dir = fromEnvironment("APPDATA");
        if (dir == null && Platform.isPlatformMacOSX())
            dir = File.getInstance(
                userHomeDirectory,
                "Library/Application Support"
            );
        if (dir == null)
            dir = File.getInstance(userHomeDirectory, ".local/share");
        return dir;
    }

    private static File stateHome() {
        File dir = fromEnvironment("XDG_STATE_HOME");
        if (dir == null && Platform.isPlatformWindows())
            dir = fromEnvironment("LOCALAPPDATA");
        if (dir == null && Platform.isPlatformMacOSX())
            dir = File.getInstance(
                userHomeDirectory,
                "Library/Application Support"
            );
        if (dir == null)
            dir = File.getInstance(userHomeDirectory, ".local/state");
        return dir;
    }

    private static File cacheHome() {
        File dir = fromEnvironment("XDG_CACHE_HOME");
        if (dir == null && Platform.isPlatformWindows()) {
            File local = fromEnvironment("LOCALAPPDATA");
            if (local != null)
                dir = File.getInstance(local, "Cache");
        }
        if (dir == null && Platform.isPlatformMacOSX())
            dir = File.getInstance(userHomeDirectory, "Library/Caches");
        if (dir == null)
            dir = File.getInstance(userHomeDirectory, ".cache");
        return dir;
    }

    // What --migrate-to-xdg moves, and where to.
    private static final String[] CONFIG_ENTRIES = {
        "prefs", "aliases", "init.lisp"
    };

    private static final String[] DATA_ENTRIES = {
        "mail", "registers", "addresses", "addresses~", "news"
    };

    private static final String[] STATE_ENTRIES = {
        "session.xml", "sessions", "props", "props~", "recent", "files.xml",
        "autosave", "recover", "jdb"
    };

    private static final String[] CACHE_ENTRIES = {
        "cache", "tagfiles", "temp"
    };

    /**
     * Move ~/.j into the XDG layout. Leaves nothing behind that would be
     * mistaken for a legacy directory on the next run: whatever cannot be
     * moved is renamed aside rather than deleted.
     */
    private static void migrateLegacyDirectory(File legacy) {
        System.out.println(
            "Migrating " + legacy.canonicalPath() +
                " to the XDG layout..."
        );
        int moved = 0;
        moved += move(legacy, CONFIG_ENTRIES, appDirectory(configHome()));
        moved += move(
            legacy,
            extensionEntries(legacy),
            appDirectory(configHome())
        );
        moved += move(legacy, DATA_ENTRIES, appDirectory(dataHome()));
        moved += move(legacy, STATE_ENTRIES, appDirectory(stateHome()));
        moved += move(legacy, CACHE_ENTRIES, appDirectory(cacheHome()));
        // The port, swank and log files are recreated every run; there is
        // nothing worth carrying over.
        int discarded = discardTransientEntries(legacy);
        System.out.println(
            "Moved " + moved + " entries, discarded " +
                discarded + "."
        );
        String[] remaining = legacy.list();
        if (remaining == null || remaining.length == 0) {
            legacy.delete();
            return;
        }
        // Something is left. Rename the directory so that it is not picked up
        // as a legacy layout again, and say where it went.
        File aside = File.getInstance(
            userHomeDirectory,
            LEGACY_DIRECTORY_NAME.concat(".migrated")
        );
        if (aside != null && !aside.exists() && legacy.renameTo(aside))
            System.out.println(
                remaining.length +
                    " unrecognized entries were left in " +
                    aside.canonicalPath()
            );
        else
            System.out.println(
                "Unable to move " + remaining.length +
                    " remaining entries out of " +
                    legacy.canonicalPath() +
                    "; J will keep using it."
            );
    }

    private static int move(File from, String[] names, File to) {
        int count = 0;
        for (int i = 0; i < names.length; i++) {
            File source = File.getInstance(from, names[i]);
            if (source == null || !source.exists())
                continue;
            File destination = File.getInstance(to, names[i]);
            if (destination == null)
                continue;
            if (destination.exists()) {
                System.out.println(
                    "  " + names[i] + ": " +
                        destination.canonicalPath() +
                        " already exists, not moved"
                );
                continue;
            }
            provideDirectory(to);
            if (source.renameTo(destination)) {
                System.out.println(
                    "  " + names[i] + " -> " +
                        destination.canonicalPath()
                );
                ++count;
            } else {
                // Most likely the two roots are on different filesystems.
                System.out.println(
                    "  unable to move " + source.canonicalPath() +
                        " to " + destination.canonicalPath()
                );
            }
        }
        return count;
    }

    /** Extension classes and jars, which are loaded by name from the config
     *  directory and so cannot be listed up front. */
    private static String[] extensionEntries(File dir) {
        String[] names = dir.list();
        if (names == null)
            return new String[0];
        List<String> extensions = new ArrayList<>();
        for (int i = 0; i < names.length; i++)
            if (names[i].endsWith(".class") || names[i].endsWith(".jar"))
                extensions.add(names[i]);
        return extensions.toArray(new String[extensions.size()]);
    }

    private static int discardTransientEntries(File dir) {
        String[] names = dir.list();
        if (names == null)
            return 0;
        int count = 0;
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (
                name.equals("port")
                    || name.equals("swank")
                    ||
                    name.equals("log")
                    || name.startsWith("log.")
            ) {
                if (File.getInstance(dir, name).delete())
                    ++count;
            }
        }
        return count;
    }

    private static File provideDirectory(final File dir) {
        return provideDirectory(dir, false);
    }

    private static File provideDirectory(final File dir, boolean verbose) {
        if (dir == null)
            return null;
        if (!dir.isDirectory()) {
            System.out.println("Creating directory: " + dir);
            dir.mkdirs();
            if (!dir.isDirectory())
                Startup.fatal("Unable to create directory " + dir);
        }
        if (!dir.canWrite())
            Startup.fatal("The directory " + dir + " is not writable");
        return dir;
    }

    public static final File getUserHomeDirectory() {
        return userHomeDirectory;
    }

    /** Hand-edited configuration: prefs, aliases, init.lisp, extensions. */
    public static File getConfigDirectory() {
        return configDirectory;
    }

    /** User data worth keeping: mail, registers, addresses, news. */
    public static File getDataDirectory() {
        return dataDirectory;
    }

    /** State that survives a restart but nobody would miss: sessions, history,
     *  autosaves, logs. */
    public static File getStateDirectory() {
        return stateDirectory;
    }

    /** Regenerable data: the file cache, tag files, temporaries. */
    public static File getCacheDirectory() {
        return cacheDirectory;
    }

    /** Files belonging to the running instance: the server port, swank. */
    public static File getRuntimeDirectory() {
        return runtimeDirectory;
    }

    /**
     * Report where j's files are, for <code>--print-directories</code>. Under
     * the legacy layout that is the same directory five times over, so follow
     * it with what migrating would give instead.
     */
    public static void printDirectories() {
        print(resolvedDirectories(), "");
        if (legacyLayout) {
            System.out.println();
            System.out.println(
                "j is using the legacy " +
                    LEGACY_DIRECTORY_NAME +
                    " directory. Start j with --migrate-to-xdg to move to:"
            );
            print(xdgDirectories(), "  ");
        }
    }

    private static String[][] resolvedDirectories() {
        return new String[][] {
            { "config", pathOf(configDirectory) },
            { "data", pathOf(dataDirectory) },
            { "state", pathOf(stateDirectory) },
            { "cache", pathOf(cacheDirectory) },
            { "runtime", pathOf(runtimeDirectory) }
        };
    }

    /**
     * Where the XDG layout would put things. Worked out from the environment
     * rather than read from the fields above, which under the legacy layout
     * all point at ~/.j.
     */
    private static String[][] xdgDirectories() {
        File state = appDirectory(stateHome());
        File runtimeHome = fromEnvironment("XDG_RUNTIME_DIR");
        return new String[][] {
            { "config", pathOf(appDirectory(configHome())) },
            { "data", pathOf(appDirectory(dataHome())) },
            { "state", pathOf(state) },
            { "cache", pathOf(appDirectory(cacheHome())) },
            { "runtime", pathOf(
                runtimeHome != null
                    ? appDirectory(runtimeHome)
                    : state
            ) }
        };
    }

    private static void print(String[][] entries, String indent) {
        for (int i = 0; i < entries.length; i++)
            System.out.println(
                indent + String.format(
                    "%-9s%s",
                    entries[i][0],
                    entries[i][1]
                )
            );
    }

    private static String pathOf(File dir) {
        return dir != null ? dir.canonicalPath() : "(none)";
    }

    /** True when an existing ~/.j was found and is being used for everything. */
    public static boolean isLegacyLayout() {
        return legacyLayout;
    }

    public static File getTempDirectory() {
        return tempDirectory;
    }

    public static final File getMailDirectory() {
        return mailDirectory;
    }

    public static final File getDraftsFolder() {
        return draftsFolder;
    }

    public static final File getRegistersDirectory() {
        return registersDirectory;
    }

    public static final void cleanTempDirectory() {
        if (tempDirectory != null && tempDirectory.isDirectory()) {
            String[] files = tempDirectory.list();
            for (int i = files.length; i-- > 0;)
                File.getInstance(tempDirectory, files[i]).delete();
        }
    }
}
