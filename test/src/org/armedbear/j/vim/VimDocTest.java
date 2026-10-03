/*
 * VimDocTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.armedbear.j.EditorHarness;
import org.junit.Test;

/**
 * The "What is there" table in doc/editmodes.html against the key map.
 *
 * The docs claimed {@code R} for several milestones with nothing bound to it.
 * Every key the table names must resolve in the built-in map, in the modes
 * its row is about, and every ex command it names must be one j runs.
 */
public class VimDocTest
{
    private static final String DOC = "doc/editmodes.html";

    private static final Set<MappingMode> N = EnumSet.of(MappingMode.NORMAL);
    private static final Set<MappingMode> V = EnumSet.of(MappingMode.VISUAL);
    private static final Set<MappingMode> NV =
        EnumSet.of(MappingMode.NORMAL, MappingMode.VISUAL);
    private static final Set<MappingMode> OV =
        EnumSet.of(MappingMode.OP_PENDING, MappingMode.VISUAL);
    private static final Set<MappingMode> NVO =
        EnumSet.of(MappingMode.NORMAL, MappingMode.VISUAL,
                   MappingMode.OP_PENDING);
    private static final Set<MappingMode> I = EnumSet.of(MappingMode.INSERT);
    private static final Set<MappingMode> C =
        EnumSet.of(MappingMode.COMMAND_LINE);

    /** The modes each row's keys must resolve in. */
    private static final Map<String, Set<MappingMode>> ROWS =
        new LinkedHashMap<String, Set<MappingMode>>();
    static {
        ROWS.put("motions", NVO);
        ROWS.put("operators", NVO);
        ROWS.put("changes", N);
        ROWS.put("insert", N);
        ROWS.put("replace", N);
        ROWS.put("text objects", OV);
        ROWS.put("search", NVO);
        ROWS.put("scroll", NV);
        ROWS.put("folds", NV);
        ROWS.put("links", N);
        ROWS.put("buffers", N);
        ROWS.put("windows", NV);
        ROWS.put("ex", NV);
        ROWS.put("visual", V);
        ROWS.put("registers", NV);
        ROWS.put("marks", N);
        ROWS.put("other", N);
    }

    /** Keys a row names that belong to another mode than the row's. */
    private static final Map<String, Set<MappingMode>> KEY_MODES =
        new HashMap<String, Set<MappingMode>>();
    static {
        KEY_MODES.put("insert <C-t>", I);
        KEY_MODES.put("insert <C-d>", I);
        KEY_MODES.put("insert <C-w>", I);
        KEY_MODES.put("insert <C-u>", I);
        KEY_MODES.put("insert <C-r>", I);
        KEY_MODES.put("insert <C-o>", I);
        KEY_MODES.put("changes <C-a>", NV);
        KEY_MODES.put("changes <C-x>", NV);
        KEY_MODES.put("search <C-g>", C);
        KEY_MODES.put("search <C-t>", C);
    }

    /** What a row names in code that is not a key in the map, and why. */
    private static final Set<String> NOT_KEYS = new HashSet<String>(Arrays.asList(
        // The dispatcher's own: leaving insert and replace, and replace's
        // walk backwards.
        "insert <Esc>", "replace <Esc>", "replace <BS>",
        // Vim's option, named to say what o O cc S copy.
        "insert autoindent",
        // The number formats CTRL-A reads.
        "changes 0x", "changes 0b", "changes nrformats", "changes 007",
        "changes 0x0f",
        // The prefix the windows row's keys come after.
        "windows <C-w>",
        // Vim's option, named to say what a split and a close do.
        "windows equalalways",
        // Vim's options, named to say what the search row shows, and what
        // :set is given to turn hlsearch off.
        "search hlsearch", "search incsearch", "search nohls",
        // The mode shown during CTRL-O.
        "insert --", "insert (insert)",
        // j's command names, beside the keys bound to them.
        "scroll toTop", "scroll toBottom", "scroll pageUp", "scroll vim",
        // A link's target, beside the key that follows it.
        "links #heading",
        // Ex ranges, read by the command line rather than the key map.
        "ex %", "ex 1,5", "ex .", "ex $", "ex 'a", "ex /pat/", "ex '<,'>"));

    /** Ex commands the check does not run, and why. */
    private static final Set<String> NOT_RUN = new HashSet<String>(Arrays.asList(
        // A frameless buffer has no file: :w would open the Save As dialog.
        ":w", ":wq",
        // A frameless editor has no windows to close the others of.
        ":on"));

    @Test
    public void everyDocumentedKeyIsBound() throws IOException
    {
        final VimKeyMap map = VimKeyMap.getDefault();
        final List<String> failures = new ArrayList<String>();
        final Map<String, List<String>> table = table();
        assertFalse("no rows found in " + DOC, table.isEmpty());
        for (Map.Entry<String, List<String>> row : table.entrySet()) {
            final String label = row.getKey();
            final Set<MappingMode> rowModes = ROWS.get(label);
            if (rowModes == null) {
                failures.add("row \"" + label + "\": add its modes to ROWS");
                continue;
            }
            for (String key : row.getValue()) {
                final String where = label + " " + key;
                if (NOT_KEYS.contains(where) || isExCommand(label, key))
                    continue;
                final Set<MappingMode> modes = KEY_MODES.containsKey(where)
                    ? KEY_MODES.get(where) : rowModes;
                // The windows row names what comes after CTRL-W.
                final String keys =
                    label.equals("windows") ? "<C-w>" + key : key;
                for (MappingMode mode : modes)
                    if (!resolves(map, mode, KeyNotation.tokenize(keys)))
                        failures.add(where + " is not bound in mode "
                                     + mode.getLetter());
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void everyDocumentedExCommandRuns() throws IOException
    {
        final List<String> failures = new ArrayList<String>();
        int ran = 0;
        for (Map.Entry<String, List<String>> row : table().entrySet())
        for (String key : row.getValue()) {
            if (!isExCommand(row.getKey(), key) || NOT_RUN.contains(key))
                continue;
            final EditorHarness h = EditorHarness.create("one\ntwo\n").vim();
            try {
                h.exCommand(key.substring(1));
                if (h.status().startsWith("E492"))
                    failures.add(key + ": " + h.status());
            }
            finally {
                h.close();
            }
            ++ran;
        }
        assertTrue("no ex commands found in " + DOC, ran > 0);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    private static boolean isExCommand(String label, String key)
    {
        return (label.equals("ex") || label.equals("windows")
                || label.equals("search"))
            && key.length() > 1 && key.startsWith(":");
    }

    /**
     * Whether keys typed in a mode reach a binding.
     *
     * A prefix waiting on its character ({@code m}, {@code r}) counts, and so
     * does an operator followed by what it takes in operator-pending mode,
     * which is how {@code dd} and {@code cc} are bound.
     */
    private static boolean resolves(VimKeyMap map, MappingMode mode,
                                    List<String> keys)
    {
        final KeyStrokeTrie<VimCommand> trie = map.getTrie(mode);
        final KeyStrokeTrie.Match<VimCommand> m = trie.match(keys);
        if (m.status == KeyStrokeTrie.Status.FULL || m.fallback != null)
            return true;
        if (m.status == KeyStrokeTrie.Status.PARTIAL) {
            final List<String> withCharacter = new ArrayList<String>(keys);
            withCharacter.add("x");
            if (trie.match(withCharacter).status == KeyStrokeTrie.Status.FULL)
                return true;
        }
        for (int k = 1; k < keys.size(); ++k) {
            final KeyStrokeTrie.Match<VimCommand> op =
                trie.match(keys.subList(0, k));
            if (op.status == KeyStrokeTrie.Status.FULL
                && op.value.getKind() == VimCommand.Kind.OPERATOR
                && resolves(map, MappingMode.OP_PENDING,
                            keys.subList(k, keys.size())))
                return true;
        }
        return false;
    }

    // ------------------------------------------------------------ the doc

    private static final Pattern ROW =
        Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern LABEL = Pattern.compile("<b>(.*?)</b>");
    private static final Pattern CODE =
        Pattern.compile("<code>(.*?)</code>", Pattern.DOTALL);

    /** Each row's label and the keys its code elements name, in vim notation. */
    private static Map<String, List<String>> table() throws IOException
    {
        final String html = new String(Files.readAllBytes(doc()),
                                       StandardCharsets.UTF_8);
        final int start = html.indexOf("<h2>What is there</h2>");
        final int end = html.indexOf("</table>", start);
        final Map<String, List<String>> rows =
            new LinkedHashMap<String, List<String>>();
        final Matcher row = ROW.matcher(html.substring(start, end));
        while (row.find()) {
            final Matcher label = LABEL.matcher(row.group(1));
            if (!label.find())
                continue;
            final List<String> keys = new ArrayList<String>();
            final Matcher code = CODE.matcher(row.group(1));
            while (code.find())
                keys.addAll(keys(code.group(1)));
            rows.put(label.group(1), keys);
        }
        return rows;
    }

    /** The keys in one code element: {@code Ctrl T} is one key, {@code <C-t>}. */
    private static List<String> keys(String code)
    {
        final String[] words = code.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&").trim().split("\\s+");
        final List<String> keys = new ArrayList<String>();
        for (int i = 0; i < words.length; ++i) {
            if (words[i].equals("Ctrl") && i + 1 < words.length)
                keys.add("<C-" + words[++i].toLowerCase() + ">");
            // g Ctrl A is one key sequence, g<C-a>.
            else if (words[i].equals("g") && i + 2 < words.length
                     && words[i + 1].equals("Ctrl"))
                keys.add("g<C-" + words[i += 2].toLowerCase() + ">");
            else if (words[i].equals("Escape"))
                keys.add("<Esc>");
            else if (words[i].equals("Backspace"))
                keys.add("<BS>");
            else if (words[i].equals("Tab"))
                keys.add("<Tab>");
            else if (!words[i].isEmpty())
                keys.add(words[i]);
        }
        return keys;
    }

    private static Path doc()
    {
        final Path here = Paths.get(DOC);
        return Files.exists(here) ? here : Paths.get("..").resolve(DOC);
    }
}
