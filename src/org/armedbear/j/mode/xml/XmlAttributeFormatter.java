/*
 * XmlAttributeFormatter.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.xml;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Lays out a start tag's attributes across lines by splitAttributes, after
 * lemminx's DOMAttributeFormatter: force-aligned puts each attribute after
 * the first on a line of its own; the preserve modes keep the line breaks
 * the tag has and add one before an attribute that would end past wrapCol.
 */
final class XmlAttributeFormatter {
    private XmlAttributeFormatter() {}

    /** Where and how to lay a tag out. */
    record Layout(SplitAttributes split,
            // The column of the tag's '<'.
            int column,
            // Where preserve puts a wrapped attribute.
            int attributeIndent,
            // 0 for no limit.
            int wrapCol,
            // The whitespace that indents to a column, as the buffer spells it.
            IntFunction<String> indentation) {}

    private record Attribute(String text, boolean breakBefore) {}

    /**
     * The start or empty-element tag laid out; null for an end tag, comment,
     * declaration, processing instruction, or a tag that does not end.
     */
    static String format(String tag, Layout layout) {
        if (tag == null || tag.length() < 2 || tag.charAt(0) != '<')
            return null;
        final char second = tag.charAt(1);
        if (second == '/' || second == '!' || second == '?')
            return null;
        final int limit = tag.length();
        int i = 1;
        while (i < limit && !isSpace(tag.charAt(i)) && tag.charAt(i) != '>' && !tag.startsWith("/>", i))
            ++i;
        final String name = tag.substring(1, i);
        if (name.isEmpty())
            return null;

        final List<Attribute> attributes = new ArrayList<>();
        String close = null;
        boolean breakBeforeClose = false;
        boolean spaceBeforeClose = false;
        while (i < limit) {
            boolean breakBefore = false;
            final int spaceStart = i;
            while (i < limit && isSpace(tag.charAt(i))) {
                if (tag.charAt(i) == '\n')
                    breakBefore = true;
                ++i;
            }
            if (i == limit)
                break;
            if (tag.charAt(i) == '>' || tag.startsWith("/>", i)) {
                close = tag.substring(i);
                breakBeforeClose = breakBefore;
                spaceBeforeClose = i > spaceStart;
                break;
            }
            final int nameStart = i;
            while (i < limit
                    && !isSpace(tag.charAt(i))
                    && tag.charAt(i) != '='
                    && tag.charAt(i) != '>'
                    && !tag.startsWith("/>", i))
                ++i;
            final StringBuilder sb = new StringBuilder(tag.substring(nameStart, i));
            int j = i;
            while (j < limit && isSpace(tag.charAt(j)))
                ++j;
            if (j < limit && tag.charAt(j) == '=') {
                ++j;
                while (j < limit && isSpace(tag.charAt(j)))
                    ++j;
                final int valueStart = j;
                if (j < limit && (tag.charAt(j) == '"' || tag.charAt(j) == '\'')) {
                    final int end = tag.indexOf(tag.charAt(j), j + 1);
                    if (end < 0)
                        return null;
                    j = end + 1;
                } else {
                    while (j < limit && !isSpace(tag.charAt(j)) && tag.charAt(j) != '>')
                        ++j;
                }
                sb.append('=').append(tag, valueStart, j);
                i = j;
            }
            attributes.add(new Attribute(sb.toString(), breakBefore));
        }
        if (close == null || !(close.equals(">") || close.equals("/>")))
            return null;

        final int alignColumn = layout.column() + 1 + name.length() + 1;
        // Aligned under the first attribute, when it is on the tag's line.
        final boolean firstOnTagLine = attributes.isEmpty() || !attributes.get(0).breakBefore();
        final int attributeIndent =
                layout.split().isAligned() && firstOnTagLine ? alignColumn : layout.attributeIndent();
        final StringBuilder out = new StringBuilder("<").append(name);
        int col = layout.column() + out.length();
        for (int k = 0; k < attributes.size(); k++) {
            final Attribute a = attributes.get(k);
            final boolean wrap;
            final int indent;
            if (layout.split() == SplitAttributes.FORCE_ALIGNED) {
                wrap = k > 0;
                indent = alignColumn;
            } else {
                final boolean overflows =
                        layout.wrapCol() > 0 && k > 0 && col + 1 + firstLineLength(a.text()) > layout.wrapCol();
                wrap = a.breakBefore() || overflows;
                indent = attributeIndent;
            }
            if (wrap) {
                out.append('\n').append(layout.indentation().apply(indent));
                col = indent;
            } else {
                out.append(' ');
                ++col;
            }
            out.append(a.text());
            final int newline = a.text().lastIndexOf('\n');
            col = newline >= 0 ? a.text().length() - newline - 1 : col + a.text().length();
        }
        final boolean keepBreak = layout.split() != SplitAttributes.FORCE_ALIGNED && breakBeforeClose;
        if (keepBreak)
            out.append('\n').append(layout.indentation().apply(attributeIndent));
        else if (close.equals("/>") && spaceBeforeClose)
            out.append(' ');
        out.append(close);
        return out.toString();
    }

    private static int firstLineLength(String s) {
        final int newline = s.indexOf('\n');
        return newline >= 0 ? newline : s.length();
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
