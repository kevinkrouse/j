/*
 * VimrcParser.java
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
import java.io.Reader;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.armedbear.j.Log;

/**
 * The part of a vimrc j understands: mappings and options.
 *
 * Users arrive with a vimrc already written, so the bindings they want are
 * spelled {@code nnoremap &lt;leader&gt;w :w&lt;CR&gt;} and not as rows of j's
 * key map table. Only that subset is read -- there is no vimscript here, and a
 * line that is not recognised is reported and skipped rather than guessed at.
 *
 * <pre>
 * " ~/.config/j/vimrc
 * let mapleader = ","
 * set shiftwidth=4
 * nnoremap &lt;leader&gt;w :save&lt;CR&gt;
 * nnoremap Y y$
 * vnoremap &lt; &lt;gv
 * </pre>
 *
 * A right-hand side that begins with {@code :} and ends with {@code &lt;CR&gt;}
 * runs one of j's own commands, which is how a key gets bound to something the
 * modal layer has no idea about.
 */
public final class VimrcParser
{
    private final VimKeyMap keyMap;
    private final VimOptions options;
    private String leader = "\\";

    public VimrcParser(VimKeyMap keyMap, VimOptions options)
    {
        this.keyMap = keyMap;
        this.options = options;
    }

    /** Reads a vimrc, reporting bad lines rather than failing on them. */
    public void load(Reader reader) throws IOException
    {
        final BufferedReader in = new BufferedReader(reader);
        String line;
        int lineNumber = 0;
        while ((line = in.readLine()) != null) {
            ++lineNumber;
            final String trimmed = line.trim();
            // " starts a comment in a vimrc, as does the rarely used ".
            if (trimmed.isEmpty() || trimmed.charAt(0) == '"')
                continue;
            try {
                if (!command(trimmed))
                    Log.error("vimrc, line " + lineNumber
                              + ": not understood: " + trimmed);
            }
            catch (RuntimeException e) {
                Log.error("vimrc, line " + lineNumber + ": " + e.getMessage()
                          + " in: " + trimmed);
            }
        }
    }

    private boolean command(String line)
    {
        final int space = line.indexOf(' ');
        final String name = space < 0 ? line : line.substring(0, space);
        final String rest = space < 0 ? "" : line.substring(space + 1).trim();

        if (name.equals("let"))
            return let(rest);
        if (name.equals("set") || name.equals("se"))
            return set(rest);
        if (name.endsWith("unmap"))
            return unmap(prefixOf(name, "unmap"), rest);
        if (name.endsWith("map"))
            return map(prefixOf(name, "map"), rest,
                       !name.contains("nore"));
        return false;
    }

    /** {@code let mapleader = ","} -- the only let there is. */
    private boolean let(String rest)
    {
        final int eq = rest.indexOf('=');
        if (eq < 0)
            return false;
        final String name = rest.substring(0, eq).trim();
        if (!name.equals("mapleader") && !name.equals("g:mapleader"))
            return false;
        leader = unquote(rest.substring(eq + 1).trim());
        return true;
    }

    private boolean set(String rest)
    {
        set(options, rest);
        return true;
    }

    /**
     * {@code set shiftwidth=4}, {@code set ignorecase}, {@code set noeol},
     * from a vimrc or the {@code :} line.
     */
    static void set(VimOptions options, String rest)
    {
        for (String option : rest.split("\\s+")) {
            if (option.isEmpty())
                continue;
            final int eq = option.indexOf('=');
            if (eq >= 0)
                options.set(option.substring(0, eq), option.substring(eq + 1));
            else if (option.startsWith("no") && option.length() > 2)
                options.set(option.substring(2), "false");
            else if (option.endsWith("!"))
                options.toggle(option.substring(0, option.length() - 1));
            else
                options.set(option, "true");
        }
    }

    /** The mode letters in front of a command name, with any "nore". */
    private static String prefixOf(String name, String suffix)
    {
        final String prefix = name.substring(0, name.length() - suffix.length());
        return prefix.endsWith("nore")
            ? prefix.substring(0, prefix.length() - 4) : prefix;
    }

    private boolean map(String prefix, String rest, boolean remap)
    {
        final Set<MappingMode> modes = modesForPrefix(prefix);
        if (modes == null)
            return false;
        final int space = rest.indexOf(' ');
        if (space < 0)
            return false;
        final String keys = expandLeader(rest.substring(0, space).trim());
        final String to = rest.substring(space + 1).trim();
        if (keys.isEmpty() || to.isEmpty())
            return false;

        keyMap.keepBuiltIn();
        keyMap.add(mapping(modes, keys, to, remap));
        return true;
    }

    private boolean unmap(String prefix, String rest)
    {
        final Set<MappingMode> modes = modesForPrefix(prefix);
        if (modes == null || rest.isEmpty())
            return false;
        final List<String> keys = KeyNotation.tokenize(expandLeader(rest));
        keyMap.keepBuiltIn();
        for (MappingMode mode : modes)
            keyMap.getTrie(mode).remove(keys);
        return true;
    }

    /**
     * Turns a right-hand side into a binding.
     *
     * {@code :cmd&lt;CR&gt;} runs one of j's commands, or else the ex command;
     * anything else is keys to press. Those keys mean what they do built in,
     * unless {@code remap} -- {@code map} rather than {@code noremap} --
     * lets them be mappings.
     */
    private static VimCommand mapping(Set<MappingMode> modes, String keys,
                                      String to, boolean remap)
    {
        final Map<String, String> noArgs = new LinkedHashMap<String, String>();
        if (remap)
            noArgs.put("remap", "true");
        if (to.startsWith(":")) {
            final String body = to.endsWith("<CR>")
                ? to.substring(1, to.length() - 4)
                : to.substring(1);
            // j's own command of that name if there is one; otherwise the
            // line goes to the ex layer, which is where :w and :bn live.
            final Map<String, String> args =
                new LinkedHashMap<String, String>(noArgs);
            args.put("ex", body.trim());
            return new VimCommand(modes, keys, VimCommand.Kind.EDITOR_COMMAND,
                                  body.trim(), args);
        }
        return new VimCommand(modes, keys, VimCommand.Kind.KEY_TO_KEY, to,
                              noArgs);
    }

    /**
     * Which maps a prefix of mode letters writes to.
     *
     * No prefix is normal, visual and operator-pending, as plain {@code map}
     * is in vim. See {@link #mapping} for what {@code nore} changes.
     */
    private static Set<MappingMode> modesForPrefix(String prefix)
    {
        switch (prefix) {
            case "":
                return EnumSet.of(MappingMode.NORMAL, MappingMode.VISUAL,
                                  MappingMode.OP_PENDING);
            case "n":
                return EnumSet.of(MappingMode.NORMAL);
            case "v":
            case "x":
                return EnumSet.of(MappingMode.VISUAL);
            case "o":
                return EnumSet.of(MappingMode.OP_PENDING);
            case "i":
                return EnumSet.of(MappingMode.INSERT);
            case "c":
                return EnumSet.of(MappingMode.COMMAND_LINE);
            default:
                return null;
        }
    }

    private String expandLeader(String keys)
    {
        return keys.replace("<leader>", leader).replace("<Leader>", leader);
    }

    private static String unquote(String s)
    {
        if (s.length() >= 2
            && (s.charAt(0) == '"' || s.charAt(0) == '\'')
            && s.charAt(s.length() - 1) == s.charAt(0))
            return s.substring(1, s.length() - 1);
        return s;
    }

    String getLeader()
    {
        return leader;
    }
}
