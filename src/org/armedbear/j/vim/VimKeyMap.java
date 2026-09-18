/*
 * VimKeyMap.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.armedbear.j.Directories;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Log;
import org.armedbear.j.Property;

/**
 * The bindings, one trie per mapping mode, read from a table.
 *
 * The built-in map is a resource next to this class rather than a pile of Java
 * statements, so that the whole thing is configuration: a user can read it,
 * copy it, and point the {@code vimKeyMap} preference at their own.
 *
 * <p>Table format, whitespace separated, {@code #} comments and blank lines
 * ignored:
 *
 * <pre>
 * # modes  keys          kind      command          args
 * n,v,o    w             motion    moveByWords      forward
 * n,v,o    f&lt;character&gt;  motion    moveToChar       forward,inclusive
 * n        i             action    enterInsertMode  at=here
 * n        &lt;Left&gt;        keytokey  h
 * </pre>
 *
 * Modes are the letters vim's map commands use ({@code n v o i}). Args are
 * comma separated; {@code name=value} sets a value and a bare {@code name} is
 * shorthand for {@code name=true}. A command with no args writes {@code -}.
 */
public final class VimKeyMap
{
    /** The built-in table, as a resource beside this class. */
    public static final String DEFAULT_RESOURCE = "default-keymap.conf";

    private final Map<MappingMode, KeyStrokeTrie<VimCommand>> tries =
        new EnumMap<MappingMode, KeyStrokeTrie<VimCommand>>(MappingMode.class);

    public VimKeyMap()
    {
        for (MappingMode mode : MappingMode.values())
            tries.put(mode, new KeyStrokeTrie<VimCommand>());
    }

    private static VimKeyMap shared;
    private static VimOptions sharedOptions;

    /**
     * The key map every editor uses: the built-in table, then whatever the
     * user's vimrc changes about it.
     *
     * Built once. Nothing reloads it yet, so a change to the vimrc needs a
     * restart, the way j's own key map files did before autoReloadKeyMaps.
     */
    public static synchronized VimKeyMap getShared()
    {
        if (shared == null) {
            shared = getConfigured();
            sharedOptions = new VimOptions();
            loadVimrc(shared, sharedOptions);
        }
        return shared;
    }

    /**
     * The table to start from: the user's if the vimKeyMap preference names
     * one, otherwise the built-in.
     *
     * A named file replaces the built-in table rather than adding to it, so
     * that someone who wants a different set of bindings gets exactly theirs.
     * To change a few bindings, use a vimrc.
     */
    private static VimKeyMap getConfigured()
    {
        final String filename = Editor.preferences()
            .getStringProperty(Property.VIM_KEY_MAP);
        if (filename == null)
            return getDefault();
        final File file = File.getInstance(filename);
        if (file == null || !file.isFile()) {
            Log.error("vimKeyMap: no such file: " + filename);
            return getDefault();
        }
        final VimKeyMap keyMap = new VimKeyMap();
        try (Reader reader = new InputStreamReader(file.getInputStream(),
                                                   StandardCharsets.UTF_8)) {
            keyMap.load(reader);
        }
        catch (IOException e) {
            Log.error(e);
            return getDefault();
        }
        return keyMap;
    }

    public static synchronized VimOptions getSharedOptions()
    {
        getShared();
        return sharedOptions;
    }

    /** Forgets the shared map, so the next use rebuilds it. */
    public static synchronized void reset()
    {
        shared = null;
        sharedOptions = null;
    }

    /**
     * Uses this map instead of building one.
     *
     * For tests, which must not read whatever vimrc the person running them
     * happens to have.
     */
    public static synchronized void setShared(VimKeyMap keyMap,
                                              VimOptions options)
    {
        shared = keyMap;
        sharedOptions = options;
    }

    private static void loadVimrc(VimKeyMap keyMap, VimOptions options)
    {
        final File file =
            File.getInstance(Directories.getConfigDirectory(), "vimrc");
        if (file == null || !file.isFile())
            return;
        try (Reader reader = new InputStreamReader(file.getInputStream(),
                                                   StandardCharsets.UTF_8)) {
            new VimrcParser(keyMap, options).load(reader);
        }
        catch (IOException e) {
            Log.error(e);
        }
    }

    /** The built-in map, or an empty one if the resource cannot be read. */
    public static VimKeyMap getDefault()
    {
        final VimKeyMap keyMap = new VimKeyMap();
        try (InputStream in =
                 VimKeyMap.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                Log.error("vim: missing key map resource " + DEFAULT_RESOURCE);
                return keyMap;
            }
            keyMap.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            Log.error(e);
        }
        return keyMap;
    }

    public KeyStrokeTrie<VimCommand> getTrie(MappingMode mode)
    {
        return tries.get(mode);
    }

    /**
     * Adds every binding in a table.
     *
     * A bad row is logged and skipped rather than thrown, so that one typo in
     * a user's map costs them that binding and not the editor.
     */
    public void load(Reader reader) throws IOException
    {
        final BufferedReader in = new BufferedReader(reader);
        String line;
        int lineNumber = 0;
        while ((line = in.readLine()) != null) {
            ++lineNumber;
            final String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#')
                continue;
            try {
                add(parse(trimmed));
            }
            catch (RuntimeException e) {
                Log.error("vim key map, line " + lineNumber + ": "
                          + e.getMessage() + " in: " + trimmed);
            }
        }
    }

    public void add(VimCommand command)
    {
        final List<String> keys = KeyNotation.tokenize(command.getKeys());
        for (MappingMode mode : command.getModes())
            tries.get(mode).put(keys, command);
    }

    static VimCommand parse(String row)
    {
        final String[] fields = row.split("\\s+", 5);
        if (fields.length < 3)
            throw new IllegalArgumentException(
                "expected at least modes, keys and kind");

        final Set<MappingMode> modes = parseModes(fields[0]);
        final String keys = fields[1];
        final VimCommand.Kind kind = VimCommand.Kind.parse(fields[2]);
        final String command = fields.length > 3 ? fields[3] : "";
        final Map<String, String> args =
            parseArgs(fields.length > 4 ? fields[4] : "-");

        if (kind != VimCommand.Kind.IDLE && command.isEmpty())
            throw new IllegalArgumentException("no command named");

        return new VimCommand(modes, keys, kind, command, args);
    }

    private static Set<MappingMode> parseModes(String field)
    {
        final Set<MappingMode> modes = new LinkedHashSet<MappingMode>();
        for (String letter : field.split(",")) {
            if (letter.length() != 1)
                throw new IllegalArgumentException(
                    "a mode is one letter, not \"" + letter + "\"");
            modes.add(MappingMode.forLetter(letter.charAt(0)));
        }
        return modes;
    }

    private static Map<String, String> parseArgs(String field)
    {
        final Map<String, String> args = new LinkedHashMap<String, String>();
        if (field.equals("-"))
            return args;
        for (String arg : field.split(",")) {
            final String trimmed = arg.trim();
            if (trimmed.isEmpty())
                continue;
            final int eq = trimmed.indexOf('=');
            if (eq < 0)
                args.put(trimmed, "true");
            else
                args.put(trimmed.substring(0, eq).trim(),
                         trimmed.substring(eq + 1).trim());
        }
        return args;
    }
}
