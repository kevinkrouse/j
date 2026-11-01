/*
 * BraceFolds.java
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

package org.armedbear.j.mode.java;

import static org.armedbear.j.Constants.STATE_COMMENT;

import org.armedbear.j.Buffer;
import org.armedbear.j.FoldCommands;
import org.armedbear.j.Folds;
import org.armedbear.j.Line;
import org.armedbear.j.Position;
import org.armedbear.j.SyntaxIterator;

/**
 * The folds of a language of braces, by its structure rather than its
 * indentation: a block, headed by the line its statement starts on, so a
 * wrapped signature or condition folds from its first line; a comment of
 * several lines, or a run of line comments at the top; a run of imports.
 * Braces in strings and comments don't count.
 */
final class BraceFolds {
    // How far down a statement's first line looks for the brace that ends it.
    private static final int STATEMENT_REACH = 50;

    private BraceFolds() {}

    static boolean isFoldStart(Buffer buffer, Line line) {
        if (line.isBlank())
            return false;
        if (commentEnd(line) != null || lineComments(line) != null || imports(line) != null)
            return true;
        return opener(buffer, line) != null;
    }

    static Line[] getFoldBelow(Buffer buffer, Line line) {
        if (line.isBlank())
            return null;
        final Line comment = commentEnd(line);
        if (comment != null)
            return new Line[] { line.next(), comment };
        final Line[] run = lineComments(line);
        if (run != null)
            return run;
        final Line[] imports = imports(line);
        if (imports != null)
            return imports;
        final Line opener = opener(buffer, line);
        if (opener == null)
            return null;
        final Line close = matchingClose(buffer, opener);
        if (close == null || close == opener)
            return null;
        // The closing brace stays in sight when it starts its line, as with "} else {".
        final Line last = code(buffer, close).startsWith("}") ? close.previous() : close;
        return last == line ? null : new Line[] { line.next(), last };
    }

    // The line whose brace opens the block line heads: line itself, or
    // the line its statement ends on; null if line heads none, or
    // continues a statement begun above.
    private static Line opener(Buffer buffer, Line line) {
        if (!ends(buffer, line.previous()))
            return null;
        Line l = line;
        for (int i = 0; l != null && i < STATEMENT_REACH; i++, l = l.next()) {
            final String code = code(buffer, l);
            if (code.endsWith("{"))
                return l;
            if (ends(code))
                return null;
        }
        return null;
    }

    // Whether a statement ends with line, so the next starts one: none
    // above, a blank line or a comment, or code ending as a statement, a
    // block or a label does, or an annotation.
    private static boolean ends(Buffer buffer, Line line) {
        return line == null || ends(code(buffer, line));
    }

    private static boolean ends(String code) {
        if (code.isEmpty() || code.startsWith("@") && !code.endsWith(","))
            return true;
        final char c = code.charAt(code.length() - 1);
        return c == ';' || c == '{' || c == '}' || c == ':';
    }

    // line's code, its comments and strings blanked, trimmed.
    private static String code(Buffer buffer, Line line) {
        return new String(buffer.getMode().getSyntaxIterator(null).hideSyntacticWhitespace(line)).trim();
    }

    // The line of the brace closing the one that ends opener; null if unmatched.
    private static Line matchingClose(Buffer buffer, Line opener) {
        final SyntaxIterator it = buffer.getMode().getSyntaxIterator(new Position(opener, opener.length()));
        int depth = 1;
        for (char c = it.nextChar(); c != SyntaxIterator.DONE; c = it.nextChar()) {
            if (c == '{')
                depth++;
            else if (c == '}' && --depth == 0)
                return it.getLine();
        }
        return null;
    }

    // The last line of the comment line begins, if it begins one that
    // ends on a later line; else null.
    private static Line commentEnd(Line line) {
        if (line.flags() == STATE_COMMENT)
            return null;
        final String text = line.trim();
        if (!text.startsWith("/*") || text.indexOf("*/", 2) >= 0)
            return null;
        for (Line l = line.next(); l != null; l = l.next())
            if (l.getText().contains("*/"))
                return l;
        return null;
    }

    private static boolean isImport(Line line) {
        final String text = line.trim();
        return text.startsWith("import ") || text.startsWith("#include");
    }

    private static Line[] imports(Line line) {
        return FoldCommands.runBelow(line, BraceFolds::isImport);
    }

    // The run of line comments line starts at the top of its buffer, as a
    // license's; null if it isn't there or is one line.
    private static Line[] lineComments(Line line) {
        if (!line.trim().startsWith("//") || !FoldCommands.isFirst(line))
            return null;
        return FoldCommands.runBelow(line, l -> l.trim().startsWith("//"));
    }

    static Line initialFold(Buffer buffer, Folds.Initial kind) {
        Line first = buffer.getFirstLine();
        while (first != null && first.isBlank())
            first = first.next();
        if (first == null)
            return null;
        final Line header = header(first);
        // What follows the header, up to the first block: the imports and
        // the class's documentation.
        final Line[] headed = header == null ? null : getFoldBelow(buffer, header);
        final Line from = headed == null ? first : headed[1].next();
        return switch (kind) {
            case HEADER -> header;
            case IMPORTS -> before(buffer, from, l -> imports(l) != null);
            case CLASS_DOC -> before(buffer, from, l -> l.trim().startsWith("/**") && commentEnd(l) != null);
        };
    }

    // first, if it starts the comment at the top that heads the file, as a
    // license: line comments, a block comment, or a documentation comment
    // a package or the imports follow, which is no class's; else null.
    private static Line header(Line first) {
        if (lineComments(first) != null)
            return first;
        final Line end = commentEnd(first);
        if (end == null)
            return null;
        if (!first.trim().startsWith("/**"))
            return first;
        Line next = end.next();
        while (next != null && next.isBlank())
            next = next.next();
        return next != null && (next.trim().startsWith("package ") || isImport(next)) ? first : null;
    }

    // The first line from on that wanted takes, before the first block opens.
    private static Line before(Buffer buffer, Line from, java.util.function.Predicate<Line> wanted) {
        for (Line l = from; l != null; l = l.next()) {
            if (wanted.test(l))
                return l;
            if (code(buffer, l).endsWith("{"))
                return null;
        }
        return null;
    }
}
