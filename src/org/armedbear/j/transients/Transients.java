/*
 * Transients.java
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

package org.armedbear.j.transients;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;
import org.armedbear.j.Buffer;
import org.armedbear.j.Directories;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Log;

/**
 * Where popups' values are kept: per buffer, as a log's filters are its
 * log's; a new buffer starts from the values last set this session, or
 * from the saved defaults.
 */
public final class Transients {
    private static final Map<Buffer, Map<String, TransientArgs>> byBuffer = new WeakHashMap<>();
    private static final Map<String, TransientArgs> last = new HashMap<>();
    private static Properties defaults;
    // Where defaults are saved; tests point it elsewhere.
    static Path defaultsFile;

    private Transients() {}

    /** The values of the popup id in the editor's buffer, for a command to read. */
    public static TransientArgs args(Editor editor, String id) {
        return args(editor.getBuffer(), id);
    }

    public static synchronized TransientArgs args(Buffer buffer, String id) {
        return byBuffer.computeIfAbsent(buffer, b -> new HashMap<>()).computeIfAbsent(id, Transients::seed);
    }

    private static TransientArgs seed(String id) {
        final TransientArgs args = last.get(id);
        return args != null ? args.copy() : TransientArgs.load(id, defaults());
    }

    /** Remembers args as the last values of id, for the next buffer. */
    static synchronized void changed(String id, TransientArgs args) {
        last.put(id, args.copy());
    }

    /** Saves args as the defaults of id. */
    static synchronized void saveDefaults(String id, TransientArgs args) throws IOException {
        final Properties p = defaults();
        args.store(id, p);
        final Path file = file();
        if (file == null)
            throw new IOException("no state directory");
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "j popup defaults");
        }
    }

    private static Properties defaults() {
        if (defaults == null) {
            defaults = new Properties();
            final Path file = file();
            if (file != null && Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    defaults.load(in);
                }
                catch (IOException e) {
                    Log.error(e);
                }
            }
        }
        return defaults;
    }

    private static Path file() {
        if (defaultsFile != null)
            return defaultsFile;
        final File dir = Directories.getStateDirectory();
        return dir == null ? null : Path.of(dir.canonicalPath(), "popup-defaults.properties");
    }

    // For tests: forget every value and the defaults read.
    static synchronized void reset(Path file) {
        byBuffer.clear();
        last.clear();
        defaults = null;
        defaultsFile = file;
    }
}
