/*
 * VimExSubstitute.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.undo.CompoundEdit;

import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.Position;

/**
 * {@code :s} -- find and replace over a range of lines.
 *
 * The separator is whatever character follows the name, so {@code :s#a#b#}
 * works as well as {@code :s/a/b/}. An empty pattern reuses the last search,
 * which is what makes {@code :g/one/s//two/} read the way it does.
 *
 * <p>Replacements are rewritten from vim's spelling to Java's: vim numbers
 * groups with {@code \1} and spells "the whole match" {@code &amp;}, where
 * {@link Matcher#appendReplacement} wants {@code $1} and treats {@code $} and
 * {@code \} as its own.
 */
final class VimExSubstitute
{
    private VimExSubstitute()
    {
    }

    /** The pattern and replacement of the last {@code :s}, for a bare {@code :s}. */
    private static String lastPattern;
    private static String lastReplacement;

    /**
     * The replacement of the last {@code :s}, which {@code ~} in a pattern
     * matches, or null if there has been none.
     */
    static String lastReplacement()
    {
        return lastReplacement;
    }

    static void run(Editor editor, VimState state, VimEx.Command command)
        throws VimEx.BadCommand
    {
        final String args = command.args;
        if (args.isEmpty()) {
            // A bare :s repeats the last one over the current line.
            if (lastPattern == null)
                throw new VimEx.BadCommand("E33: No previous substitute");
            // The pattern and replacement come back, the flags do not: after
            // :s/a/b/g a bare :s changes one match on the line, not all.
            substitute(editor, state, command.range, lastPattern,
                       lastReplacement, "");
            return;
        }
        final char separator = args.charAt(0);
        if (Character.isLetterOrDigit(separator) || separator == '\\'
            || separator == '"' || separator == '|')
            throw new VimEx.BadCommand("E146: Invalid separator");

        final String[] fields = split(args, separator);
        String pattern = fields[0];
        final String replacement = fields[1];
        final String flags = fields[2];

        if (pattern.isEmpty()) {
            // An empty pattern means the last search, which is how
            // :g/one/s//two/ names its own match.
            final VimSearch.Query last = state.getLastSearch();
            if (last != null)
                pattern = last.pattern;
            else if (lastPattern != null)
                pattern = lastPattern;
            else
                throw new VimEx.BadCommand("E35: No previous regular expression");
        }
        lastPattern = pattern;
        // :s sets the search pattern too, so a following n finds it.
        state.setLastSearch(new VimSearch.Query(pattern, true, false));
        substitute(editor, state, command.range, pattern, replacement, flags);
        // Only now: a ~ in this command's own pattern means the *previous*
        // replacement, and the pattern is compiled inside substitute().
        lastReplacement = replacement;
    }

    /**
     * Splits {@code /pat/rep/flags} on its separator.
     *
     * A separator escaped with a backslash belongs to the field, and the
     * trailing ones may simply be missing: {@code :s/a/b} and {@code :s/a}
     * are both legal.
     */
    private static String[] split(String args, char separator)
    {
        final String[] fields = {"", "", ""};
        int field = 0;
        final StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length(); i++) {
            final char c = args.charAt(i);
            if (c == '\\' && i + 1 < args.length()) {
                final char escaped = args.charAt(i + 1);
                // An escaped separator loses its backslash and stays in the
                // field; every other escape is passed through for the regex.
                if (escaped == separator)
                    sb.append(escaped);
                else
                    sb.append(c).append(escaped);
                ++i;
                continue;
            }
            if (c == separator && field < 2) {
                fields[field++] = sb.toString();
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        fields[field] = sb.toString();
        return fields;
    }

    private static void substitute(Editor editor, VimState state,
                                   VimEx.Range range, String pattern,
                                   String replacement, String flags)
        throws VimEx.BadCommand
    {
        checkFlags(flags);
        final boolean all = flags.indexOf('g') >= 0;
        final Pattern regex = compile(pattern, flags);
        final String rewritten = toJavaReplacement(replacement);

        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            VimOperators.recordCaret(editor);
            int changed = 0;
            Line last = null;
            // Walk by number rather than by Line.next(): a replacement
            // containing a newline splits the line it was on, and the lines
            // it makes are not part of the range.
            for (int n = range.first; n <= range.last; n++) {
                final Line line = VimEx.lineAt(editor, n + changed);
                if (line == null)
                    break;
                final String was = line.getText() == null ? "" : line.getText();
                final Matcher matcher = regex.matcher(was);
                final String now = all ? replaceAll(matcher, rewritten)
                                       : replaceFirst(matcher, rewritten);
                if (now == null || now.equals(was))
                    continue;
                replaceLine(editor, line, now);
                // Each newline the replacement introduced pushes the rest of
                // the range down by one.
                changed += count(now, '\n');
                last = editor.getDot() == null ? line : editor.getDot().getLine();
            }
            if (last != null) {
                // Vim leaves the caret on the first non-blank of the last
                // line it changed.
                editor.setDot(last, VimMotions.firstNonBlank(last));
                editor.moveCaretToDotCol();
            }
        }
        finally {
            editor.getBuffer().endCompoundEdit(edit);
        }
        state.clampCaret(editor);
    }

    /**
     * Refuses a flag this does not implement.
     *
     * Vim reports trailing characters rather than ignoring them, and for
     * {@code c} -- confirm -- saying nothing would be worse than an error:
     * the user asked to be asked about each match and would instead get the
     * lot replaced silently.
     */
    private static void checkFlags(String flags) throws VimEx.BadCommand
    {
        for (int i = 0; i < flags.length(); i++) {
            final char c = flags.charAt(i);
            if (c != 'g' && c != 'i' && c != 'I' && c != ' ')
                throw new VimEx.BadCommand(
                    "E488: Trailing characters: " + flags.substring(i));
        }
    }

    private static Pattern compile(String pattern, String flags)
        throws VimEx.BadCommand
    {
        // The i and I flags beat the options; \c and \C in the pattern beat
        // both, which VimRegex sees to.
        final Boolean force = flags.indexOf('i') >= 0 ? Boolean.TRUE
            : flags.indexOf('I') >= 0 ? Boolean.FALSE : null;
        try {
            return VimRegex.compile(pattern, force);
        }
        catch (PatternSyntaxException e) {
            throw new VimEx.BadCommand(badPattern(pattern, e));
        }
    }

    /**
     * What to say about a pattern that will not compile.
     *
     * The translator's own description when it refused something by name.
     * Otherwise Java refused what the translator produced -- a \zs after
     * something of unbounded length becomes a lookbehind Java will not take
     * -- and saying "not found" about a search that never ran would be
     * false, so Java's reason goes along with vim's E383.
     */
    static String badPattern(String pattern, PatternSyntaxException e)
    {
        final String d = e.getDescription();
        if (d != null && (d.startsWith("E") || d.contains("not supported")))
            return d;
        return "E383: Invalid search string: " + pattern
               + (d == null ? "" : " (" + d + ")");
    }

    private static String replaceFirst(Matcher matcher, String replacement)
    {
        if (!matcher.find())
            return null;
        final StringBuffer sb = new StringBuffer();
        matcher.appendReplacement(sb, replacement);
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String replaceAll(Matcher matcher, String replacement)
    {
        final StringBuffer sb = new StringBuffer();
        boolean any = false;
        while (matcher.find()) {
            // Vim takes no empty match at the end of the line: s/x*/-/g on
            // "ab" gives "-a-b", not "-a-b-". A non-empty match there is
            // fine, so the test is on the match and not on the position.
            if (matcher.start() == matcher.end()
                && matcher.start() == matcher.regionEnd())
                break;
            matcher.appendReplacement(sb, replacement);
            any = true;
        }
        if (!any)
            return null;
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Rewrites a vim replacement as a Java one.
     *
     * Vim: {@code &} is the whole match, {@code \1} a group, {@code \&} and
     * {@code \\} the literals. Java: {@code $0} and {@code $1}, with {@code $}
     * and {@code \} needing escapes of their own.
     */
    static String toJavaReplacement(String replacement)
    {
        final StringBuilder sb = new StringBuilder(replacement.length());
        for (int i = 0; i < replacement.length(); i++) {
            final char c = replacement.charAt(i);
            if (c == '\\' && i + 1 == replacement.length()) {
                // A backslash with nothing after it is a literal one. Left to
                // fall through it produced a Java replacement ending in a
                // lone backslash, which appendReplacement rejects outright.
                sb.append("\\\\");
                continue;
            }
            if (c == '\\' && i + 1 < replacement.length()) {
                final char next = replacement.charAt(++i);
                if (next >= '0' && next <= '9')
                    sb.append('$').append(next);
                else if (next == 'n')
                    sb.append('\n');
                else if (next == 't')
                    sb.append('\t');
                else if (next == '\\')
                    sb.append("\\\\");
                else if (next == '&')
                    sb.append("&");
                else
                    sb.append(Matcher.quoteReplacement(String.valueOf(next)));
                continue;
            }
            if (c == '&') {
                sb.append("$0");
                continue;
            }
            if (c == '$')
                sb.append("\\$");
            else
                sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Puts new text on a line, splitting it if the text carries newlines.
     *
     * Goes through the editor's own region delete and insert so that the undo
     * record, the modified flag and every marker into the line are handled the
     * way j expects.
     */
    private static void replaceLine(Editor editor, Line line, String text)
    {
        editor.setMark(new Position(line, line.length()));
        editor.setDot(line, 0);
        // setDot moves the caret in the model; the display keeps its own
        // column, and j pads an insert out to it. Without this, writing into
        // an empty line indents it to wherever the last edit left the caret.
        editor.moveCaretToDotCol();
        editor.deleteRegion();
        editor.setMark(null);
        editor.insertString(text);
    }

    private static int count(String s, char c)
    {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == c)
                ++n;
        return n;
    }
}
