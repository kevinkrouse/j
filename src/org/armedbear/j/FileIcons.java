/*
 * FileIcons.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.swing.Icon;
import org.armedbear.j.util.Icons;

/** An icon for a kind of file, by its name. */
public final class FileIcons {
    private FileIcons() {}

    private static final Map<String, String> BY_EXTENSION = new HashMap<>();
    private static final Map<String, String> BY_NAME = new HashMap<>();

    private static void extensions(String icon, String... extensions) {
        for (String e : extensions)
            BY_EXTENSION.put(e, icon);
    }

    static {
        extensions(
            "file-code",
            "java",
            "c",
            "h",
            "cc",
            "cpp",
            "cxx",
            "hpp",
            "hh",
            "m",
            "mm",
            "cs",
            "go",
            "rs",
            "kt",
            "kts",
            "scala",
            "swift",
            "js",
            "mjs",
            "cjs",
            "ts",
            "jsx",
            "tsx",
            "py",
            "rb",
            "pl",
            "pm",
            "php",
            "lua",
            "tcl",
            "sh",
            "bash",
            "zsh",
            "fish",
            "lisp",
            "lsp",
            "cl",
            "el",
            "scm",
            "ss",
            "clj",
            "cljs",
            "cljc",
            "bb",
            "hs",
            "ml",
            "v",
            "sv",
            "vhd",
            "vhdl",
            "asm",
            "s",
            "sql",
            "awk",
            "groovy",
            "dart",
            "zig"
        );
        extensions(
            "file-markup",
            "html",
            "htm",
            "xhtml",
            "xml",
            "xsl",
            "xslt",
            "xsd",
            "dtd",
            "svg",
            "md",
            "markdown",
            "rst",
            "adoc",
            "tex",
            "css",
            "scss"
        );
        extensions(
            "file-config",
            "json",
            "yaml",
            "yml",
            "toml",
            "edn",
            "properties",
            "ini",
            "cfg",
            "conf",
            "config",
            "gradle",
            "lock",
            "nix",
            "mk",
            "cmake",
            "env",
            "editorconfig",
            "gitignore",
            "gitattributes",
            "keywords"
        );
        extensions("file-image", "png", "jpg", "jpeg", "gif", "bmp", "ico", "icns", "webp", "tif", "tiff", "xpm");
        for (String name : new String[] { "makefile", "gnumakefile", "dockerfile", "jenkinsfile", "rakefile",
            "gemfile" })
            BY_NAME.put(name, "file-config");
    }

    /** The name of the icon for a file called name; "buffer" for plain text and anything unknown. */
    static String iconName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String icon = BY_NAME.get(lower);
        if (icon != null)
            return icon;
        int dot = lower.lastIndexOf('.');
        if (dot >= 0) {
            icon = BY_EXTENSION.get(lower.substring(dot + 1));
            if (icon != null)
                return icon;
        }
        return "buffer";
    }

    /** The icon for a file called name, with badge ("modified", "locked") or none if null. */
    public static Icon getIcon(String name, String badge) {
        return Icons.getBadgedIcon(iconName(name), badge);
    }
}
