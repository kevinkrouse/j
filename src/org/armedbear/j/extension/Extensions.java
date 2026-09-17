/*
 * Extensions.java
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.armedbear.j.extension;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.Set;

import org.armedbear.j.CommandTable;
import org.armedbear.j.Directories;
import org.armedbear.j.Editor;
import org.armedbear.j.Log;
import org.armedbear.j.Preferences;
import org.armedbear.j.Property;

/**
 * Finds extensions and holds what they register.
 *
 * <p>Extensions live in their own directory under lib/extensions (beside the
 * installed j.jar) or under the user's config directory, each with its own
 * class loader so that two extensions can depend on different versions of the
 * same library. Discovery is java.util.ServiceLoader; there is no manifest
 * format of j's own to learn.
 *
 * <p>The accessors here never return null. Core calls them unconditionally and
 * gets a do-nothing implementation when no extension has registered, which is
 * what keeps the hot paths free of "is it installed yet" branches.
 *
 * <p>Deliberately written against java.nio.file rather than
 * org.armedbear.j.File: this runs before the editor is up, and j's File knows
 * about FTP, HTTP and SSH, none of which belong in a class path.
 */
public final class Extensions
{
    /** Where to look, overriding the installed location. Set by `bb run`. */
    public static final String DIRECTORY_PROPERTY = "j.extensions.dir";

    private static volatile EditorHooks hooks = EditorHooks.NONE;
    private static volatile KeyMapProvider keyMaps = KeyMapProvider.NONE;
    private static volatile LanguageClient languageClient = LanguageClient.NONE;

    private static final List<Extension> loaded = new ArrayList<Extension>();

    private static boolean disabled;

    private Extensions()
    {
    }

    // Accessors. These are on hot paths -- plain field reads, nothing more.

    public static EditorHooks hooks()
    {
        return hooks;
    }

    public static KeyMapProvider keyMaps()
    {
        return keyMaps;
    }

    public static LanguageClient languageClient()
    {
        return languageClient;
    }

    /** The default session of the registered client; never null. */
    public static Session session()
    {
        return languageClient.getDefaultSession();
    }

    /** Names of the extensions that loaded, in load order. */
    public static synchronized List<String> loadedNames()
    {
        List<String> names = new ArrayList<String>(loaded.size());
        for (Extension extension : loaded)
            names.add(extension.getName());
        return names;
    }

    /** Turn discovery off entirely; --no-extensions. */
    public static void setDisabled(boolean b)
    {
        disabled = b;
    }

    /**
     * Find and initialize every extension. Safe to call when nothing is
     * installed, which is the normal case for a core-only build.
     */
    public static synchronized void load()
    {
        if (disabled) {
            Log.info("extensions disabled");
            return;
        }
        Set<String> skip = disabledNames();
        for (Path dir : searchPath()) {
            for (Path extensionDir : subdirectories(dir))
                loadFrom(extensionDir, skip);
        }
    }

    /**
     * Load one extension directory. Package-visible so the tests can point it
     * at a temporary directory without going through the search path.
     */
    static synchronized void loadFrom(Path directory, Set<String> skip)
    {
        URL[] urls = jarsIn(directory);
        if (urls.length == 0)
            return;
        ClassLoader loader =
            new URLClassLoader(urls, Extensions.class.getClassLoader());
        for (Extension extension : ServiceLoader.load(Extension.class, loader)) {
            String name = extension.getName();
            if (name == null) {
                Log.error("extension in " + directory + " has no name; skipped");
                continue;
            }
            if (skip.contains(name.toLowerCase(Locale.ROOT))) {
                Log.info("extension " + name + " disabled by preference");
                continue;
            }
            initialize(extension, loader);
        }
    }

    private static void initialize(Extension extension, ClassLoader loader)
    {
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        try {
            // ABCL resolves classes and resources through the context loader in
            // several places, so an extension must be initialized under its own.
            thread.setContextClassLoader(loader);
            extension.initialize(new Context(loader));
            loaded.add(extension);
            Log.info("loaded extension " + extension.getName() + " " +
                     extension.getVersion());
        }
        catch (Throwable t) {
            // One broken extension must not stop j from starting.
            Log.error("extension " + extension.getName() + " failed to initialize");
            Log.error(t);
        }
        finally {
            thread.setContextClassLoader(saved);
        }
    }

    public static synchronized void shutdown()
    {
        for (int i = loaded.size(); i-- > 0;) {
            Extension extension = loaded.get(i);
            try {
                extension.shutdown();
            }
            catch (Throwable t) {
                Log.error(t);
            }
        }
        loaded.clear();
        languageClient.shutdown();
        hooks = EditorHooks.NONE;
        keyMaps = KeyMapProvider.NONE;
        languageClient = LanguageClient.NONE;
    }

    // Discovery

    private static Set<String> disabledNames()
    {
        Set<String> names = new HashSet<String>();
        Preferences preferences = Editor.preferences();
        if (preferences == null)
            return names;
        String value = preferences.getStringProperty(Property.DISABLED_EXTENSIONS);
        if (value == null)
            return names;
        for (String name : value.split(","))
            if (name.trim().length() > 0)
                names.add(name.trim().toLowerCase(Locale.ROOT));
        return names;
    }

    /** lib/extensions beside the installed jar, then the user's config directory. */
    private static List<Path> searchPath()
    {
        List<Path> path = new ArrayList<Path>(2);
        String override = System.getProperty(DIRECTORY_PROPERTY);
        if (override != null && override.length() > 0) {
            path.add(Paths.get(override));
            return path;
        }
        Path installed = installedExtensionsDirectory();
        if (installed != null)
            path.add(installed);
        org.armedbear.j.File config = Directories.getConfigDirectory();
        if (config != null) {
            try {
                path.add(Paths.get(config.canonicalPath(), "extensions"));
            }
            catch (RuntimeException e) {
                Log.error(e);
            }
        }
        return path;
    }

    private static Path installedExtensionsDirectory()
    {
        try {
            java.security.CodeSource source =
                Editor.class.getProtectionDomain().getCodeSource();
            if (source == null)
                return null;
            Path location = Paths.get(source.getLocation().toURI());
            // .../j.jar         -> .../lib/extensions
            // .../build/classes -> .../build/lib/extensions
            Path root = location.getParent();
            return root == null ? null : root.resolve("lib").resolve("extensions");
        }
        catch (Exception e) {
            Log.debug("cannot locate the installed extensions directory: " + e);
            return null;
        }
    }

    private static List<Path> subdirectories(Path dir)
    {
        if (dir == null || !Files.isDirectory(dir))
            return Collections.emptyList();
        List<Path> dirs = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream)
                if (Files.isDirectory(child))
                    dirs.add(child);
        }
        catch (IOException e) {
            Log.error(e);
        }
        Collections.sort(dirs);
        return dirs;
    }

    private static URL[] jarsIn(Path dir)
    {
        if (dir == null || !Files.isDirectory(dir))
            return new URL[0];
        List<URL> urls = new ArrayList<URL>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            List<Path> jars = new ArrayList<Path>();
            for (Path jar : stream)
                jars.add(jar);
            Collections.sort(jars);
            for (Path jar : jars)
                urls.add(jar.toUri().toURL());
        }
        catch (IOException e) {
            Log.error(e);
        }
        return urls.toArray(new URL[urls.size()]);
    }

    // Registration

    private static final class Context implements ExtensionContext
    {
        private final ClassLoader loader;

        Context(ClassLoader loader)
        {
            this.loader = loader;
        }

        public void registerCommand(String name, Class<?> owner, String methodName)
        {
            CommandTable.registerCommand(name, owner, methodName);
        }

        public void registerCommand(String name, Class<?> owner, String methodName,
                                    String alias)
        {
            CommandTable.registerCommand(name, owner, methodName);
            CommandTable.registerCommand(alias, owner, methodName);
        }

        public void registerLanguageClient(LanguageClient client)
        {
            if (client != null)
                languageClient = client;
        }

        public void registerHooks(EditorHooks newHooks)
        {
            if (newHooks != null)
                hooks = newHooks;
        }

        public void registerKeyMapProvider(KeyMapProvider provider)
        {
            if (provider != null)
                keyMaps = provider;
        }

        public ClassLoader getClassLoader()
        {
            return loader;
        }

        public Preferences getPreferences()
        {
            return Editor.preferences();
        }
    }
}
