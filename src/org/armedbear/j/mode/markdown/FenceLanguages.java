/*
 * FenceLanguages.java
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


package org.armedbear.j.mode.markdown;

import org.armedbear.j.Constants;
import org.armedbear.j.Editor;
import org.armedbear.j.Mode;
import org.armedbear.j.ModeListEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The language a fence's info string names, as the mode that colors it:
 * "```java", "``` py", "~~~ {.sh}". Each language seen gets a small number,
 * a slot, that fits in a line's flags.
 */
final class FenceLanguages implements Constants
{
    /** Slots fit in six bits of a line's flags; 0 is no language. */
    static final int MAX_SLOT = 63;

    // The names, beyond j's own names for its modes, that fences use.
    private static final Map<String, Integer> NAMES = new HashMap<String, Integer>();
    static {
        name(JAVA_MODE, "java", "jad");
        name(JAVASCRIPT_MODE, "javascript", "js", "jsx", "mjs", "cjs", "json",
             "typescript", "ts");
        name(C_MODE, "c", "h");
        name(CPP_MODE, "cpp", "c++", "cc", "cxx", "hpp", "hxx");
        name(OBJC_MODE, "objc", "objective-c", "m");
        name(PYTHON_MODE, "python", "py", "python3");
        name(PERL_MODE, "perl", "pl", "pm");
        name(PHP_MODE, "php");
        name(RUBY_MODE, "ruby", "rb");
        name(TCL_MODE, "tcl");
        name(SHELL_SCRIPT_MODE, "sh", "bash", "zsh", "ksh", "csh", "shell",
             "console", "shell-script");
        name(LISP_MODE, "lisp", "elisp", "emacs-lisp", "el", "cl",
             "common-lisp", "clojure", "clj");
        name(SCHEME_MODE, "scheme", "scm", "racket");
        name(XML_MODE, "xml", "svg", "xsd", "xsl", "plist");
        name(HTML_MODE, "html", "htm", "xhtml");
        name(CSS_MODE, "css");
        name(DIFF_MODE, "diff", "patch");
        name(MAKEFILE_MODE, "make", "makefile", "mk");
        name(PROPERTIES_MODE, "properties", "ini", "conf", "cfg", "toml");
        name(AUTOCONF_MODE, "autoconf", "m4");
        name(VERILOG_MODE, "verilog", "v");
        name(VHDL_MODE, "vhdl");
        name(ASM_MODE, "asm", "assembly", "nasm");
    }

    private static void name(int modeId, String... names)
    {
        for (String name : names)
            NAMES.put(name, modeId);
    }

    // The mode id of each slot, from slot 1.
    private static final List<Integer> slots = new ArrayList<Integer>();

    private FenceLanguages() {}

    /** The slot of the language info names, or 0 if it names none j colors. */
    static synchronized int slotFor(String info)
    {
        final int id = modeIdFor(info);
        if (id < 0)
            return 0;
        int slot = slots.indexOf(id) + 1;
        if (slot == 0 && slots.size() < MAX_SLOT) {
            slots.add(id);
            slot = slots.size();
        }
        return slot;
    }

    /** The mode for a slot, or null. */
    static synchronized Mode modeFor(int slot)
    {
        if (slot < 1 || slot > slots.size())
            return null;
        return Editor.getModeList().getMode(slots.get(slot - 1));
    }

    // The language is the info string's first word: "java title=x", or
    // pandoc's "{.java}".
    private static int modeIdFor(String info)
    {
        String word = info.trim();
        if (word.startsWith("{"))
            word = word.substring(1);
        if (word.startsWith("."))
            word = word.substring(1);
        int end = 0;
        while (end < word.length() && !Character.isWhitespace(word.charAt(end))
               && word.charAt(end) != '}' && word.charAt(end) != ',')
            ++end;
        word = word.substring(0, end).toLowerCase(Locale.ROOT);
        if (word.isEmpty())
            return -1;
        final Integer id = NAMES.get(word);
        if (id != null)
            return id;
        // j's own name for a mode it edits files in, but not one in which
        // Markdown would color Markdown.
        for (ModeListEntry entry : Editor.getModeList()) {
            if (entry.isSelectable() && entry.getId() != MARKDOWN_MODE
                && entry.getId() != PLAIN_TEXT_MODE
                && entry.getId() != BINARY_MODE
                && entry.getDisplayName().equalsIgnoreCase(word))
                return entry.getId();
        }
        return -1;
    }
}
