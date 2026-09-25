/*
 * VimExSort.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Sort;

/**
 * {@code :sort} -- the ex line, handed to j's own {@link Sort}.
 *
 * Nothing here sorts anything. The flag letters are vim's and
 * {@code Sort.Options} speaks them, so this only works out which lines the
 * range covers and lets the editor's own command do the work. {@code
 * sortLines} in a key map and {@code :sort} at the prompt are then the same
 * code rather than two implementations that drift.
 */
final class VimExSort
{
    private VimExSort()
    {
    }

    static void run(Editor editor, VimState state, VimEx.Command command)
        throws VimEx.BadCommand
    {
        // :sort with no range takes the whole buffer, where :d and :y take
        // the current line.
        final VimEx.Range range = command.range.given
            ? command.range
            : new VimEx.Range(1, Math.max(1, editor.getBuffer().getLineCount()),
                              true);
        final Line first = VimEx.lineAt(editor, range.first);
        final Line last = VimEx.lineAt(editor, range.last);
        if (first == null || last == null)
            throw new VimEx.BadCommand("E16: Invalid range");

        final Sort.Options options;
        try {
            options = Sort.Options.parse(command.args);
        }
        catch (IllegalArgumentException e) {
            throw new VimEx.BadCommand(e.getMessage());
        }
        options.reverse = command.bang;
        // Sort.Options compiles a pattern as a plain Java one, which is what
        // j's own commands use. A pattern typed at the vim prompt goes
        // through the same shim as / and :s instead, so that \< and \> mean
        // here what they mean everywhere else in this mode.
        final String source = patternOf(command.args);
        if (source != null && !source.isEmpty())
            options.pattern = compile(source);

        Sort.sortLines(editor, first, last, options);
        final Line landed = VimEx.lineAt(editor, range.first);
        if (landed != null) {
            editor.setDot(landed, VimMotions.firstNonBlank(landed));
            editor.moveCaretToDotCol();
        }
        state.clampCaret(editor);
    }

    /** The {@code /pattern/} in a sort argument, or null when there is none. */
    private static String patternOf(String args)
    {
        final int open = args.indexOf('/');
        if (open < 0)
            return null;
        final int close = args.indexOf('/', open + 1);
        return close < 0 ? args.substring(open + 1)
                         : args.substring(open + 1, close);
    }

    private static Pattern compile(String source) throws VimEx.BadCommand
    {
        try {
            return VimRegex.compile(source, null);
        }
        catch (PatternSyntaxException e) {
            throw new VimEx.BadCommand(VimExSubstitute.badPattern(source, e));
        }
    }
}
