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
final class VimExSubstitute {
    private VimExSubstitute() {}

    /** The pattern and replacement of the last {@code :s}, for a bare {@code :s}. */
    private static String lastPattern;
    private static String lastReplacement;

    /**
     * The replacement of the last {@code :s}, which {@code ~} in a pattern
     * matches, or null if there has been none.
     */
    static String lastReplacement() {
        return lastReplacement;
    }

    /** Clears what the last :s left, for a test that needs there to be none. */
    static void forgetForTest() {
        lastPattern = null;
        lastReplacement = null;
    }

    static void run(Editor editor, VimState state, VimEx.Command command)
        throws VimEx.BadCommand {
        final String args = command.args;
        if (args.isEmpty()) {
            // A bare :s repeats the last one over the current line.
            if (lastPattern == null)
                throw new VimEx.BadCommand("E33: No previous substitute");
            // The pattern and replacement come back, the flags do not: after
            // :s/a/b/g a bare :s changes one match on the line, not all.
            substitute(
                editor,
                state,
                command.range,
                lastPattern,
                lastReplacement,
                ""
            );
            return;
        }
        final char separator = args.charAt(0);
        if (
            Character.isLetterOrDigit(separator)
                || separator == '\\'
                || separator == '"'
                || separator == '|'
        )
            throw new VimEx.BadCommand("E146: Invalid separator");

        final String[] fields = split(args, separator);
        String pattern = fields[0];
        final String replacement = fields[1];
        final String flags = fields[2];

        if (pattern.isEmpty()) {
            // An empty pattern means the last search, which is how
            // :g/one/s//two/ names its own match.
            final VimSearch.Query last = state.getLastSearch(editor);
            if (last != null)
                pattern = last.pattern;
            else if (lastPattern != null)
                pattern = lastPattern;
            else
                throw new VimEx.BadCommand("E35: No previous regular expression");
        }
        lastPattern = pattern;
        // :s sets the search pattern too, so a following n finds it.
        state.setLastSearch(editor, new VimSearch.Query(pattern, true, false));
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
    private static String[] split(String args, char separator) {
        final String[] fields = { "", "", "" };
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

    private static void substitute(
        Editor editor,
        VimState state,
        VimEx.Range given,
        String pattern,
        String replacement,
        String flags
    )
        throws VimEx.BadCommand {
        checkFlags(flags);
        // Line 0 is the first line, as for :d; past the end is an invalid
        // range, not a pattern that was not found.
        final int lines = editor.getBuffer().getLineCount();
        if (
            given.first < 0
                || given.last < 0
                || given.first > lines
                || given.last > lines
        )
            throw new VimEx.BadCommand("E16: Invalid range");
        final VimEx.Range range = new VimEx.Range(
            Math.max(1, given.first),
            Math.max(1, given.last),
            given.given
        );
        final boolean all = flags.indexOf('g') >= 0;
        // e: no error when nothing matches.
        final boolean quiet = flags.indexOf('e') >= 0;
        final Pattern regex = compile(pattern, flags);
        final String rewritten =
            toJavaReplacement(replacement, regex.matcher("").groupCount());

        // :s is a jump; by number, as the lines it changes are replaced.
        editor.getBuffer().renumber();
        final int fromLine = editor.getDotLine().lineNumber() + 1;
        final int fromOffset = editor.getDotOffset();
        final CompoundEdit edit = editor.getBuffer().beginCompoundEdit();
        try {
            VimOperators.recordCaret(editor);
            int changed = 0;
            int lastRow = 0;
            int firstRow = 0;
            boolean matched = false;
            // Walked forward rather than looked up by number each time, which
            // was a walk from the first line per line of the range. A
            // replacement containing a line break splits the line it was on,
            // and the lines it makes are not part of the range, so the next
            // line to look at is past them.
            Line line = VimEx.lineAt(editor, range.first);
            for (int n = range.first;
                n <= range.last && line != null;
                n++) {
                final Line here = line;
                line = here.next();
                final String was = here.getText() == null ? "" : here.getText();
                final Matcher matcher = regex.matcher(was);
                final String now = all
                    ? replaceAll(matcher, was, rewritten)
                    : replaceFirst(matcher, rewritten);
                if (now == null)
                    continue;
                // A match that changes nothing still counts as a match: vim
                // does not report s/b/b/ as not found.
                matched = true;
                if (now.equals(was))
                    continue;
                replaceLine(editor, here, now);
                if (firstRow == 0)
                    firstRow = n + changed;
                // Each line break the replacement introduced pushes the rest
                // of the range down by one; step past the lines it made.
                final int breaks = count(now, '\n');
                changed += breaks;
                line = here;
                for (int k = 0; k <= breaks && line != null; k++)
                    line = line.next();
                // Vim leaves the caret on the last line the last substitution
                // produced -- the tail of a split, not the line it started on.
                // Tracked by number: the dot after a split is no guide.
                lastRow = n + changed;
            }
            if (!matched && !quiet)
                throw new VimEx.BadCommand(
                    "E486: Pattern not found: "
                        + pattern
                );
            // j numbers lines lazily, and the lines a split made have none
            // yet: anything reading lineNumber() after this -- a following
            // :.d, for one -- would get -1. Trap 15, which is how the caret
            // came to be on "line -1" rather than on a wrong line.
            if (changed > 0)
                editor.getBuffer().renumber();
            final Line last = lastRow > 0
                ? VimEx.lineAt(editor, lastRow)
                : null;
            if (last != null) {
                // '[ and '] span the range; '. is the first line changed.
                final Line top = VimEx.lineAt(editor, range.first);
                final Line bottom = VimEx.lineAt(editor, range.last + changed);
                state.getMarks()
                    .noteLines(
                        editor.getBuffer(),
                        top != null ? top : last,
                        bottom != null ? bottom : last,
                        VimEx.lineAt(editor, firstRow)
                    );
                final Line back = VimEx.lineAt(editor, fromLine);
                if (back != null)
                    state.jumped(
                        editor,
                        new Position(
                            back,
                            Math.min(fromOffset, back.length())
                        )
                    );
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
    private static void checkFlags(String flags) throws VimEx.BadCommand {
        for (int i = 0; i < flags.length(); i++) {
            final char c = flags.charAt(i);
            if (c != 'g' && c != 'i' && c != 'I' && c != 'e' && c != ' ')
                throw new VimEx.BadCommand(
                    "E488: Trailing characters: " + flags.substring(i)
                );
        }
    }

    private static Pattern compile(String pattern, String flags)
        throws VimEx.BadCommand {
        // The i and I flags beat the options; \c and \C in the pattern beat
        // both, which VimRegex sees to.
        // The last of i and I wins, as in vim: s/b/B/iI is case sensitive.
        final int i = flags.lastIndexOf('i');
        final int upper = flags.lastIndexOf('I');
        final Boolean force = i < 0 && upper < 0
            ? null
            : Boolean.valueOf(i > upper);
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
    static String badPattern(String pattern, PatternSyntaxException e) {
        final String d = e.getDescription();
        if (d != null && (d.startsWith("E") || d.contains("not supported")))
            return d;
        return "E383: Invalid search string: " + pattern
            + (d == null ? "" : " (" + d + ")");
    }

    private static String replaceFirst(Matcher matcher, String replacement) {
        if (!matcher.find())
            return null;
        final StringBuilder sb = new StringBuilder();
        matcher.appendReplacement(sb, replacement);
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String replaceAll(
        Matcher matcher,
        String text,
        String replacement
    ) {
        final StringBuilder sb = new StringBuilder();
        boolean any = false;
        while (matcher.find()) {
            matcher.appendReplacement(sb, replacement);
            any = true;
            // Vim stops once the next search would start at the end of the
            // line, and after an empty match it starts a character on. So
            // s/x*/-/g on "ab" gives "-a-b", but s/a\|$/-/g gives "-b-".
            int next = matcher.end();
            if (next == matcher.start() && next < text.length())
                next += Character.charCount(text.codePointAt(next));
            if (next >= text.length())
                break;
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
     * and {@code \} needing escapes of their own. A group the pattern does
     * not have is empty, as in vim, where Java would throw.
     */
    static String toJavaReplacement(String replacement, int groups) {
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
                if (next >= '0' && next <= '9') {
                    if (next - '0' <= groups)
                        sb.append('$').append(next);
                }
                // In a replacement \r is the line break and \n inserts a NUL
                // -- the other way round from a pattern, and checked with
                // nvim, which puts ^@ where \n was.
                else if (next == 'r')
                    sb.append('\n');
                else if (next == 'n')
                    sb.append('\u0000');
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
    private static void replaceLine(Editor editor, Line line, String text) {
        editor.deleteRegion(
            new Position(line, 0),
            new Position(line, line.length())
        );
        editor.insertString(text);
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == c)
                ++n;
        return n;
    }
}
