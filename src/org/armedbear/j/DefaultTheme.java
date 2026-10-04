/*
 * DefaultTheme.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DefaultTheme {
    public static final Color getColor(String thing) {
        return getColor(null, thing);
    }

    // Returns null if mode/thing not found.
    public static final Color getColor(String mode, String thing) {
        return getColor(mode, thing, false);
    }

    /**
     * Whether text on a background wants the colors made for a dark one: its
     * perceived brightness, as YIQ weighs it, is under half.
     */
    public static boolean isDark(Color background) {
        return (background.getRed() * 299 + background.getGreen() * 587 +
            background.getBlue() * 114) / 1000 < 128;
    }

    // Styles any mode can link its own to, as emacs faces inherit and nvim
    // links highlight groups: { name, light background, dark background }.
    // The colors are GitHub Primer's, each 4.5:1 or better on its background.
    private static final Object[][] SHARED_COLORS = {
        { "heading", 0x0550ae, 0x58a6ff },
        { "link", 0x0969da, 0x79c0ff },
        { "code", 0x953800, 0xffa657 },
        { "quote", 0x57606a, 0x8b949e },
        { "muted", 0x6e7781, 0x8b949e },
        { "listMarker", 0x8250df, 0xd2a8ff },
        { "todo", 0x9a6700, 0xe3b341 },
        { "inProgress", 0x8250df, 0xbc8cff },
        { "inProgressMarker", 0x0969da, 0x58a6ff },
        { "done", 0x1a7f37, 0x3fb950 },
        { "cancelled", 0x8c959f, 0x6e7681 },
    };

    private static final Object[][] SHARED_STYLES = {
        { "heading", TextStyle.BOLD | TextStyle.ITALIC },
        { "link", TextStyle.UNDERLINE },
        { "strong", TextStyle.BOLD },
        { "emphasis", TextStyle.ITALIC },
        { "quote", TextStyle.ITALIC },
        { "listMarker", TextStyle.BOLD },
        { "cancelled", TextStyle.STRIKETHROUGH },
    };

    // The shared style a thing takes what it does not say from, unless a
    // theme or prefs link it elsewhere: { mode or null for any, thing, to }.
    private static final String[][] LINKS = {
        { null, "emphasis", "text" },
        { null, "strong", "text" },
        { null, "url", "muted" },
        { "MarkdownMode", "heading1", "heading" },
        { "MarkdownMode", "heading2", "heading" },
        { "MarkdownMode", "heading3", "heading" },
        { "MarkdownMode", "heading4", "heading" },
        { "MarkdownMode", "heading5", "heading" },
        { "MarkdownMode", "heading6", "heading" },
        { "MarkdownMode", "headingMarker", "muted" },
        { "MarkdownMode", "codeBlock", "code" },
        { "MarkdownMode", "fence", "muted" },
        { "MarkdownMode", "linkText", "link" },
        { "MarkdownMode", "markup", "muted" },
        { "MarkdownMode", "codeMarker", "markup" },
        { "MarkdownMode", "strongEmphasis", "text" },
        { "MarkdownMode", "strikethrough", "text" },
        { "MarkdownMode", "quoteMarker", "link" },
        { "MarkdownMode", "rule", "muted" },
        { "MarkdownMode", "doneText", "muted" },
        { "MarkdownMode", "cancelledText", "cancelled" },
        { "MarkdownMode", "htmlTag", "muted" },
        { "MarkdownMode", "frontMatter", "comment" },
    };

    // What every mode has, before a mode or a shared style says otherwise:
    // the editor's own colors, then the syntax most modes color.
    private static final String[] BUILT_IN_NAMES = {
        "text", "background", "caret", "currentLineBackground",
        "selectionBackground", "matchingBracketBackground",
        "searchMatchBackground", "verticalRule", "lineNumber", "gutterBorder",
        "change", "savedChange",
        "comment", "keyword", "function", "string", "number", "operator",
        "brace", "preprocessor", "disabled", "matchingText", "prompt", "input",
        "status", "key", "value", "delimiter",
    };

    /** The names of the styles every mode has, for listStyles. */
    public static List<String> getBuiltInNames() {
        return Arrays.asList(BUILT_IN_NAMES);
    }

    /** The names of the shared styles, for listStyles. */
    public static List<String> getSharedStyleNames() {
        final Set<String> names = new LinkedHashSet<String>();
        for (Object[] entry : SHARED_COLORS)
            names.add((String) entry[0]);
        for (Object[] entry : SHARED_STYLES)
            names.add((String) entry[0]);
        for (String[] link : LINKS)
            if (link[0] == null)
                names.add(link[1]);
        return new ArrayList<String>(names);
    }

    /**
     * The name a thing links to by default, or null. FormatTable follows it
     * for whatever the thing's own preferences leave out.
     */
    public static String getLink(String mode, String thing) {
        String any = null;
        for (String[] link : LINKS) {
            if (!link[1].equals(thing))
                continue;
            if (link[0] == null)
                any = link[2];
            else if (link[0].equals(mode))
                return link[2];
        }
        return any;
    }

    private static Color getSharedColor(String thing, boolean dark) {
        for (Object[] entry : SHARED_COLORS)
            if (entry[0] == thing)
                return new Color((Integer) entry[dark ? 2 : 1]);
        return null;
    }

    private static int getSharedStyle(String thing) {
        for (Object[] entry : SHARED_STYLES)
            if (entry[0] == thing)
                return (Integer) entry[1];
        return -1;
    }

    /**
     * A mode's own default for a thing first, then a shared style's color
     * for the background, then the colors every mode shares. Returns null if
     * mode/thing not found.
     */
    public static final Color getColor(String mode, String thing, boolean dark) {
        if (thing == null)
            return null;
        thing = thing.intern();
        if (mode != null) {
            mode = mode.intern();
            if (mode == "CSSMode") {
                if (thing == "selector")
                    return new Color(0, 0, 0);
                if (thing == "property")
                    return new Color(0, 0, 204);
            } else if (mode == "DiffMode") {
                if (thing == "file")
                    return new Color(0, 0, 0);
                if (thing == "header")
                    return new Color(0, 102, 0);
                if (thing == "context")
                    return new Color(0, 0, 0);
                if (thing == "inserted")
                    return new Color(153, 0, 0);
                if (thing == "deleted")
                    return new Color(0, 0, 153);
            } else if (mode == "DirectoryMode") {
                if (thing == "directory")
                    return new Color(0, 0, 0);
                if (thing == "symlink")
                    return new Color(0, 0, 255);
                if (thing == "marked")
                    return new Color(153, 0, 0);
            } else if (mode == "HtmlMode") {
                if (thing == "tag")
                    return new Color(0, 0, 153);
                if (thing == "anchor")
                    return new Color(51, 153, 51);
                if (thing == "image")
                    return new Color(204, 102, 0);
                if (thing == "table")
                    return new Color(204, 0, 0);
                if (thing == "tableRow")
                    return new Color(153, 0, 0);
                if (thing == "tableData")
                    return new Color(153, 51, 0);
                if (thing == "comment")
                    return new Color(128, 128, 128);
                if (thing == "script")
                    return new Color(0, 0, 255);
            } else if (mode == "ListOccurrencesMode") {
                if (thing == "headerName")
                    return new Color(0, 0, 153);
                if (thing == "headerValue")
                    return new Color(0, 0, 255);
            } else if (mode == "MailboxMode") {
                if (thing == "to")
                    return new Color(0, 0, 0);
                if (thing == "flags")
                    return new Color(0, 0, 0);
                if (thing == "date")
                    return new Color(51, 51, 51);
                if (thing == "from")
                    return new Color(0, 0, 0);
                if (thing == "size")
                    return new Color(51, 51, 51);
                if (thing == "subject")
                    return new Color(51, 102, 102);
                if (thing == "flaggedTo")
                    return new Color(204, 51, 0);
                if (thing == "flaggedFlags")
                    return new Color(0, 0, 0);
                if (thing == "flaggedDate")
                    return new Color(0, 0, 0);
                if (thing == "flaggedFrom")
                    return new Color(204, 51, 0);
                if (thing == "flaggedSize")
                    return new Color(0, 0, 0);
                if (thing == "flaggedSubject")
                    return new Color(204, 51, 0);
                if (thing == "marked")
                    return new Color(153, 0, 0);
                if (thing == "deleted")
                    return new Color(153, 153, 153);
            } else if (mode == "MessageMode") {
                if (thing == "headerName")
                    return new Color(0, 0, 153);
                if (thing == "headerValue")
                    return new Color(51, 102, 102);
                if (thing == "signature")
                    return new Color(102, 102, 102);
                if (thing == "string")
                    return new Color(0, 102, 0);
                if (thing == "comment")
                    return new Color(102, 102, 102);
            } else if (mode == "WebMode") {
                if (thing == "headerValue")
                    return new Color(51, 102, 102);
            } else if (mode == "LispMode") {
                if (thing == "substitution")
                    return new Color(153, 0, 153);
                if (thing == "punctuation")
                    return new Color(102, 102, 102);
                if (thing == "parenthesis")
                    return new Color(102, 102, 102);
                if (thing == "secondaryKeyword")
                    return new Color(0, 102, 153);
            } else if (mode == "PerlMode") {
                if (thing == "scalar")
                    return new Color(51, 51, 0);
                if (thing == "list")
                    return new Color(0, 51, 51);
            } else if (mode == "PHPMode") {
                if (thing == "var")
                    return new Color(51, 51, 0);
                if (thing == "tag")
                    return new Color(0, 0, 0);
                if (thing == "attribute")
                    return new Color(0, 0, 128);
                if (thing == "equals")
                    return new Color(0, 153, 153);
            } else if (mode == "TclMode") {
                if (thing == "brace")
                    return new Color(153, 0, 51);
                if (thing == "bracket")
                    return new Color(204, 102, 0);
            } else if (mode == "VHDLMode") {
                if (thing == "type")
                    return new Color(0, 0, 255);
            } else if (mode == "PropertiesMode") {
                if (thing == "section")
                    return new Color(0, 0, 153);
            } else if (mode == "XmlMode") {
                if (thing == "attribute")
                    return new Color(0, 0, 128);
                if (thing == "equals")
                    return new Color(0, 153, 153);
                if (thing == "namespace")
                    return new Color(0, 0, 0);
                if (thing == "tag")
                    return new Color(0, 0, 0);
            } else if (mode == "StatusMode") {
                if (thing == "added")
                    return new Color(0, 153, 0);
                else if (thing == "deleted")
                    return new Color(176, 130, 130);
                else if (thing == "changed")
                    return new Color(0, 0, 255);
                else if (thing == "conflict")
                    return new Color(153, 0, 0);
                else if (thing == "unknown")
                    return new Color(180, 180, 180);
                else if (thing == "nochange")
                    return new Color(0, 0, 0);
                else if (thing == "ignored")
                    return new Color(190, 204, 204);
            }
        }

        final Color shared = getSharedColor(thing, dark);
        if (shared != null)
            return shared;

        if (thing == "text")
            return new Color(0, 0, 0);
        if (thing == "background")
            return new Color(255, 255, 224);
        if (thing == "caret")
            return new Color(255, 0, 0);
        if (thing == "verticalRule")
            return new Color(204, 204, 204);
        if (thing == "selectionBackground")
            return new Color(153, 204, 255);
        if (thing == "matchingBracketBackground")
            return new Color(153, 204, 255);
        if (thing == "searchMatchBackground")
            return new Color(255, 221, 102);
        if (thing == "preprocessor")
            return new Color(255, 0, 0);
        if (thing == "comment")
            return new Color(0, 102, 0);
        if (thing == "keyword")
            return new Color(0, 0, 153);
        if (thing == "brace")
            return new Color(0, 128, 128);
        if (thing == "number")
            return new Color(153, 102, 51);
        if (thing == "currentLineBackground")
            return new Color(235, 235, 204);
        if (thing == "function")
            return new Color(0, 0, 0);
        if (thing == "string")
            return new Color(153, 51, 0);
        if (thing == "operator")
            return new Color(0, 0, 255);
        if (thing == "disabled")
            return new Color(153, 153, 153);
        if (thing == "change")
            return new Color(255, 164, 0);
        if (thing == "savedChange")
            return new Color(180, 180, 180);
        if (thing == "lineNumber")
            return new Color(153, 153, 153);
        if (thing == "gutterBorder")
            return new Color(153, 153, 153);
        if (thing == "prompt")
            return new Color(0, 0, 0);
        if (thing == "input")
            return new Color(0, 0, 255);
        if (thing == "matchingText")
            return new Color(204, 102, 0);
        if (thing == "status")
            return new Color(0, 0, 153);
        if (thing == "key")
            return new Color(0, 0, 153);
        if (thing == "value")
            return new Color(128, 0, 0);
        if (thing == "delimiter")
            return new Color(0, 153, 153);

        // Makefile mode.
        if (thing == "target")
            return new Color(0, 0, 0);

        // List Registers mode.
        if (thing == "registerPrefix")
            return new Color(0, 0, 153);
        if (thing == "registerName")
            return new Color(204, 102, 0);

        // Not found.
        return null;
    }

    // A TextStyle: Font.PLAIN is 0, Font.BOLD is 1, Font.ITALIC is 2, and
    // the other bits combine with them. Returns -1 if mode/thing not found.
    public static final int getStyle(String mode, String thing) {
        if (thing == null)
            return -1;
        thing = thing.intern();
        if (mode != null) {
            mode = mode.intern();
            if (mode == "CSSMode") {
                if (thing == "selector")
                    return Font.BOLD;
                if (thing == "property")
                    return Font.PLAIN;
            } else if (mode == "DiffMode") {
                if (thing == "file")
                    return Font.BOLD;
                if (thing == "header")
                    return Font.ITALIC;
            } else if (mode == "MailboxMode") {
                if (thing == "to")
                    return Font.BOLD;
                if (thing == "date")
                    return Font.PLAIN;
                if (thing == "from")
                    return Font.BOLD;
                if (thing == "subject")
                    return Font.BOLD;
                if (thing == "flaggedTo")
                    return Font.BOLD;
                if (thing == "flaggedFrom")
                    return Font.BOLD;
                if (thing == "flaggedSubject")
                    return Font.BOLD;
                if (thing == "marked")
                    return Font.BOLD;
            } else if (mode == "MessageMode") {
                if (thing == "headerName")
                    return Font.BOLD;
                if (thing == "headerValue")
                    return Font.BOLD;
                if (thing == "comment")
                    return Font.PLAIN;
            } else if (mode == "WebMode") {
                if (thing == "headerValue")
                    return Font.BOLD;
            } else if (mode == "ListOccurrencesMode") {
                if (thing == "headerName")
                    return Font.BOLD;
            } else if (mode == "PropertiesMode") {
                if (thing == "section")
                    return Font.BOLD;
                else if (thing == "comment")
                    return Font.ITALIC;
                else
                    return Font.PLAIN;
            } else if (mode == "DirectoryMode") {
                if (thing == "directory")
                    return Font.BOLD;
                if (thing == "marked")
                    return Font.BOLD;
            } else if (mode == "TclMode") {
                if (thing == "brace")
                    return Font.BOLD;
                if (thing == "bracket")
                    return Font.BOLD;
            } else if (mode == "XmlMode" || mode == "PHPMode") {
                if (thing == "tag")
                    return Font.BOLD;
            } else if (mode == "MakefileMode") {
                if (thing == "target")
                    return Font.BOLD;
            } else if (mode == "MarkdownMode") {
                if (thing == "codeBlock")
                    return TextStyle.ITALIC;
                if (thing == "strongEmphasis")
                    return TextStyle.BOLD | TextStyle.ITALIC;
                if (thing == "strikethrough" || thing == "cancelledText")
                    return TextStyle.STRIKETHROUGH;
                // The box, unlike the item, is not struck through.
                if (thing == "cancelled")
                    return TextStyle.PLAIN;
                // The link's blue for a quote's bar, without its underline.
                if (thing == "quoteMarker")
                    return TextStyle.PLAIN;
            } else if (mode == "StatusMode") {
                if (thing == "unknown")
                    return Font.ITALIC;
                if (thing == "ignored")
                    return Font.ITALIC;
            }
        }

        final int shared = getSharedStyle(thing);
        if (shared >= 0)
            return shared;

        if (thing == "keyword")
            return Font.BOLD;
        if (thing == "function")
            return Font.BOLD;
        if (thing == "prompt")
            return Font.BOLD;
        if (thing == "comment")
            return Font.ITALIC;
        if (thing == "matchingText")
            return Font.BOLD;
        if (thing == "status")
            return Font.ITALIC;
        if (thing == "key")
            return Font.PLAIN;
        if (thing == "delimiter")
            return Font.BOLD;

        // List Registers mode.
        if (thing == "registerPrefix")
            return Font.BOLD;
        if (thing == "registerName")
            return Font.BOLD;

        // Not found.
        return -1;
    }
}
