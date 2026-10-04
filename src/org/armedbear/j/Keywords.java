/*
 * Keywords.java
 *
 * Copyright (C) 1998-2002 Peter Graves
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A mode's keywords, from MyMode.keywords beside its class or the file the
 * MyMode.keywords preference names: one word a line. "#include
 * mode/c/CMode.keywords" reads another list, by its path under org/armedbear/j.
 */
public final class Keywords {
    private final Mode mode;
    private final boolean ignoreCase;

    private volatile Set<String> words = Set.of();

    public Keywords(Mode mode) {
        this(mode, false);
    }

    public Keywords(Mode mode, boolean ignoreCase) {
        this.mode = mode;
        this.ignoreCase = ignoreCase;
        load();
    }

    // Called by AbstractMode.reset().
    public void reload() {
        load();
    }

    private void load() {
        final String key = mode.getClass().getSimpleName() + ".keywords";
        Set<String> set = new HashSet<String>();
        InputStream in = null;
        final String fileName = Editor.preferences().getStringProperty(key);
        if (fileName != null) {
            File file = File.getInstance(fileName);
            if (file != null && file.isFile()) {
                try {
                    in = file.getInputStream();
                }
                catch (IOException e) {
                    Log.error(e);
                }
            } else
                Log.error("keywords file not found: " + fileName);
        }
        if (in == null)
            in = mode.getClass().getResourceAsStream(key);
        if (in != null)
            read(in, set);
        else
            Log.error("no resource " + key);
        words = set;
    }

    private void read(InputStream in, Set<String> set) {
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String s; (s = reader.readLine()) != null;) {
                s = s.trim();
                if (s.startsWith("#include ")) {
                    String path = "/org/armedbear/j/" + s.substring(9).trim();
                    InputStream included = mode.getClass().getResourceAsStream(path);
                    if (included != null)
                        read(included, set);
                    else
                        Log.error("no resource " + path);
                } else if (!s.isEmpty())
                    set.add(ignoreCase ? s.toLowerCase(Locale.ROOT) : s);
            }
        }
        catch (IOException e) {
            Log.error(e);
        }
    }

    public boolean isKeyword(String s) {
        return words.contains(ignoreCase ? s.toLowerCase(Locale.ROOT) : s);
    }
}
