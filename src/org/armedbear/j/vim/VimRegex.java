/*
 * VimRegex.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Vim's regular expressions, rewritten as Java's.
 *
 * The difference that matters is the <em>magic level</em>. Java treats
 * {@code + ? ( ) | { }} as operators; vim's default, {@code \m}, treats them as
 * plain characters and spells the operators with a backslash: {@code a\+},
 * {@code \(ab\)}, {@code a\|b}. So {@code /a+} finds "a+" in vim and one or
 * more a's in Java, and passing a vim pattern to Java unchanged gets both the
 * easy cases and the hard ones subtly wrong. {@code \v}, {@code \m},
 * {@code \M} and {@code \V} switch level anywhere in a pattern.
 *
 * <p>The rule underneath is one table. At each level some punctuation is
 * special bare and literal with a backslash; the rest is the other way round.
 * {@link #specialBare} is that table, and every dual-meaning character goes
 * through it, which is what keeps the four levels from becoming four parsers.
 *
 * <p>Vim's letter escapes are rewritten rather than passed through, because
 * several mean something else in Java: {@code \a} is the bell character there,
 * <code>&#92;u</code> starts a unicode escape and {@code \b} is a word boundary. And
 * {@code \s} is space or tab only, where Java's includes the newline.
 *
 * <p>Every expectation in the tests was taken from nvim.
 */
final class VimRegex {
    private VimRegex() {}

    /**
     * What a translation produced.
     *
     * @param java the pattern in Java's syntax
     * @param ignoreCase {@code \c} or {@code \C} in the pattern, or null for neither
     * @param hasUppercase an upper case letter outside any escape, which is
     *        what {@code 'smartcase'} looks for; the S in {@code \S} does not count
     */
    record Result(String java, Boolean ignoreCase, boolean hasUppercase) {}

    // The four levels, in order of how much is special.
    private static final int VERY_NOMAGIC = 0;
    private static final int NOMAGIC = 1;
    private static final int MAGIC = 2;
    private static final int VERY_MAGIC = 3;

    /**
     * True when a character means its operator written bare at this level,
     * and so means itself when written with a backslash.
     *
     * The table in vim's {@code :help /magic}, as code.
     */
    private static boolean specialBare(char c, int level) {
        switch (c) {
            case '^':
            case '$':
                return level >= NOMAGIC;
            case '.':
            case '*':
            case '[':
            case '~':
                return level >= MAGIC;
            case '(':
            case ')':
            case '|':
            case '+':
            case '=':
            case '?':
            case '{':
            case '@':
            case '<':
            case '>':
            case '%':
            case '&':
                return level == VERY_MAGIC;
            default:
                return false;
        }
    }

    /** Characters that have an operator meaning at some level. */
    private static boolean dualMeaning(char c) {
        return "^$.*[~()|+=?{@<>%&".indexOf(c) >= 0;
    }

    /**
     * Translates a vim pattern.
     *
     * @param lastSubstitute what {@code ~} matches: the replacement of the
     *        last {@code :s}, or null if there has been none, in which case
     *        {@code ~} matches nothing at all, as in vim
     * @throws PatternSyntaxException for a construct this cannot express,
     *         named in the description, rather than a silently wrong pattern
     */
    static Result translate(String pattern, String lastSubstitute) {
        return new VimRegex.Translator(pattern, lastSubstitute).run();
    }

    /** {@link #translate} with no previous substitute. */
    static Result translate(String pattern) {
        return translate(pattern, null);
    }

    /**
     * Compiles a vim pattern with the case rules applied: {@code \c} and
     * {@code \C} first, then {@code 'ignorecase'} narrowed by
     * {@code 'smartcase'}.
     *
     * @param forceCase a caller's own override -- {@code :s}'s i and I flags
     *        -- or null to use the pattern's and the options'
     */
    static Pattern compile(String pattern, Boolean forceCase) {
        final Result r = translate(pattern, VimExSubstitute.lastReplacement());
        final int flags = ignoreCase(r, forceCase)
            ? Pattern.CASE_INSENSITIVE
            : 0;
        return Pattern.compile(r.java, flags);
    }

    /** Whether a translated pattern should ignore case. */
    static boolean ignoreCase(Result r, Boolean forceCase) {
        return ignoreCase(r, forceCase, true);
    }

    /**
     * @param useSmartcase false for * and its kin, which vim documents as
     *        using 'ignorecase' but not 'smartcase'
     */
    static boolean ignoreCase(
        Result r,
        Boolean forceCase,
        boolean useSmartcase
    ) {
        // \c and \C in the pattern beat the flags and the options, as they
        // do in vim; a flag given to :s beats the options.
        if (r.ignoreCase != null)
            return r.ignoreCase.booleanValue();
        if (forceCase != null)
            return forceCase.booleanValue();
        final VimOptions options = VimKeyMap.getSharedOptions();
        if (!options.isOn("ignorecase"))
            return false;
        return !(useSmartcase
            && options.isOn("smartcase")
            && r.hasUppercase);
    }

    /**
     * Vim's pat_has_uppercase: an upper case letter that is not part of an
     * escape. {@code \S}, {@code \_S} and {@code \%V} do not count.
     */
    private static boolean hasUppercase(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                final char next = pattern.charAt(++i);
                if ((next == '_' || next == '%') && i + 1 < pattern.length())
                    ++i;
                continue;
            }
            if (Character.isUpperCase(c))
                return true;
        }
        return false;
    }

    // -------------------------------------------------------------- scanner

    private static final class Translator {
        private final String in;
        private final String lastSubstitute;
        private final StringBuilder out = new StringBuilder();
        private int pos;
        private int level = MAGIC;
        private Boolean ignoreCase;

        /** Where each open group's output starts, innermost last. */
        private final Deque<Integer> groups = new ArrayDeque<>();
        /** Where the last complete atom's output starts, or -1 if none. */
        private int lastAtom = -1;
        /** True at the start of a branch, where ^ anchors and * is literal. */
        private boolean branchStart = true;
        /** Output offsets of \zs and \ze, or -1. */
        private int zs = -1;
        private int ze = -1;
        private boolean topLevelAlternation;
        /**
         * Set once anything of variable width has been written: a
         * quantifier, an alternative, a backreference. \zs after one is
         * refused, because the lookbehind it becomes is satisfied at the
         * leftmost place it can be, where vim's \zs lands after the greedy
         * match -- .*\zsfoo is the last foo in vim and the first here.
         */
        private boolean variableWidth;

        Translator(String in, String lastSubstitute) {
            this.in = in;
            this.lastSubstitute = lastSubstitute;
        }

        Result run() {
            while (pos < in.length()) {
                final char c = in.charAt(pos++);
                if (c == '\\') {
                    if (pos >= in.length()) {
                        // A trailing backslash is a literal one.
                        literal('\\');
                        continue;
                    }
                    escape(in.charAt(pos++));
                } else if (dualMeaning(c) && specialBare(c, level)) {
                    operator(c);
                } else {
                    literal(c);
                }
            }
            if (!groups.isEmpty())
                throw error("E54: Unmatched \\(");
            return new Result(finish(), ignoreCase, hasUppercase(in));
        }

        /** A backslash and the character after it. */
        private void escape(char c) {
            if (dualMeaning(c)) {
                // The flip side of the table: special bare means literal
                // escaped, and the other way round.
                if (specialBare(c, level))
                    literal(c);
                else
                    operator(c);
                return;
            }
            switch (c) {
                // The level switches, which can appear anywhere.
                case 'v':
                    level = VERY_MAGIC;
                    return;
                case 'm':
                    level = MAGIC;
                    return;
                case 'M':
                    level = NOMAGIC;
                    return;
                case 'V':
                    level = VERY_NOMAGIC;
                    return;
                case 'c':
                    ignoreCase = Boolean.TRUE;
                    return;
                case 'C':
                    ignoreCase = Boolean.FALSE;
                    return;
                // Character classes, spelt out: several of these letters mean
                // something else entirely to Java.
                case 's':
                    atom("[ \\t]");
                    return;
                case 'S':
                    atom("[^ \\t]");
                    return;
                case 'd':
                    atom("[0-9]");
                    return;
                case 'D':
                    atom("[^0-9]");
                    return;
                case 'w':
                    atom("[0-9A-Za-z_]");
                    return;
                case 'W':
                    atom("[^0-9A-Za-z_]");
                    return;
                case 'a':
                    atom("[A-Za-z]");
                    return;
                case 'A':
                    atom("[^A-Za-z]");
                    return;
                case 'l':
                    atom("[a-z]");
                    return;
                case 'L':
                    atom("[^a-z]");
                    return;
                case 'u':
                    atom("[A-Z]");
                    return;
                case 'U':
                    atom("[^A-Z]");
                    return;
                case 'x':
                    atom("[0-9A-Fa-f]");
                    return;
                case 'X':
                    atom("[^0-9A-Fa-f]");
                    return;
                case 'o':
                    atom("[0-7]");
                    return;
                case 'O':
                    atom("[^0-7]");
                    return;
                case 'h':
                    atom("[A-Za-z_]");
                    return;
                case 'H':
                    atom("[^A-Za-z_]");
                    return;
                // Keyword and identifier characters depend on 'iskeyword'
                // and 'isident' in vim; the usual values are these.
                case 'k':
                case 'i':
                    atom("[0-9A-Za-z_]");
                    return;
                case 'K':
                case 'I':
                    atom("[A-Za-z_]");
                    return;
                case 'f':
                    atom("[0-9A-Za-z_./\\-+,#$%~=]");
                    return;
                case 'F':
                    atom("[A-Za-z_./\\-+,#$%~=]");
                    return;
                case 'p':
                    atom("[\\x20-\\x7e]");
                    return;
                // Capital forms of \i \k \f \p exclude digits.
                case 'P':
                    atom("[\\x20-\\x2f\\x3a-\\x7e]");
                    return;
                // Control characters. \b is backspace here, not a boundary.
                case 'e':
                    atom("\\x1b");
                    return;
                case 't':
                    atom("\\t");
                    return;
                case 'r':
                    atom("\\r");
                    return;
                case 'b':
                    atom("\\x08");
                    return;
                case 'n':
                    atom("\\n");
                    return;
                case '_':
                    underscore();
                    return;
                case 'z':
                    zed();
                    return;
                default:
                    break;
            }
            if (c >= '1' && c <= '9') {
                atom("\\" + c);
                variableWidth = true;
                return;
            }
            // Anything else escaped is itself: \\, \/, \] and the rest.
            literal(c);
        }

        /** A character with its operator meaning. */
        private void operator(char c) {
            switch (c) {
                case '^':
                    // Only an anchor at the start of a branch; anywhere else
                    // it is the character, where Java would read an anchor
                    // that can never match. branchStart is left set, so that
                    // a * straight after it is still the character.
                    if (branchStart)
                        out.append('^');
                    else
                        literal('^');
                    return;
                case '$':
                    // Likewise only at the end of a branch.
                    if (atBranchEnd())
                        out.append('$');
                    else
                        literal('$');
                    return;
                case '.':
                    atom(".");
                    return;
                case '[':
                    characterClass();
                    return;
                case '~':
                    // The last substitute string, as a single atom. With none
                    // yet it is an error, as in vim.
                    if (lastSubstitute == null)
                        throw error(
                            "E33: No previous substitute regular expression"
                        );
                    atom("(?:" + Pattern.quote(lastSubstitute) + ")");
                    return;
                case '(':
                    openGroup("(");
                    return;
                case ')':
                    closeGroup();
                    return;
                case '|':
                    if (groups.isEmpty())
                        topLevelAlternation = true;
                    out.append('|');
                    lastAtom = -1;
                    branchStart = true;
                    variableWidth = true;
                    return;
                case '*':
                    quantifier("*", c);
                    return;
                case '+':
                    quantifier("+", c);
                    return;
                case '=':
                case '?':
                    quantifier("?", c);
                    return;
                case '{':
                    braces();
                    return;
                case '@':
                    lookaround();
                    return;
                case '<':
                    // Start of word: the next character is a keyword one and
                    // the one before is not.
                    out.append("(?<![0-9A-Za-z_])(?=[0-9A-Za-z_])");
                    return;
                case '>':
                    out.append("(?<=[0-9A-Za-z_])(?![0-9A-Za-z_])");
                    return;
                case '%':
                    percent();
                    return;
                case '&':
                    throw error("\\& (concat) is not supported");
                default:
                    literal(c);
            }
        }

        // ------------------------------------------------------------ atoms

        private void literal(char c) {
            final int start = out.length();
            if ("\\.[]{}()*+-?^$|&".indexOf(c) >= 0)
                out.append('\\');
            out.append(c);
            lastAtom = start;
            branchStart = false;
        }

        private void atom(String java) {
            lastAtom = out.length();
            out.append(java);
            branchStart = false;
        }

        /** True when a $ here is at the end of its branch. */
        private boolean atBranchEnd() {
            if (pos >= in.length())
                return true;
            // The next token closes a group or starts another branch; what
            // spells those depends on the level.
            final boolean bare = level == VERY_MAGIC;
            if (bare)
                return in.charAt(pos) == '|' || in.charAt(pos) == ')';
            return in.startsWith("\\|", pos) || in.startsWith("\\)", pos);
        }

        // ------------------------------------------------------ quantifiers

        private void quantifier(String java, char c) {
            if (lastAtom < 0) {
                // * with nothing before it is the character; the others are
                // errors, as in vim.
                if (c == '*') {
                    literal('*');
                    return;
                }
                throw error("E64: " + c + " follows nothing");
            }
            out.append(java);
            branchStart = false;
            variableWidth = true;
        }

        /** {@code \{n,m}} and its non-greedy {@code \{-n,m}} form. */
        private void braces() {
            if (lastAtom < 0)
                throw error("E64: { follows nothing");
            final StringBuilder body = new StringBuilder();
            while (pos < in.length() && in.charAt(pos) != '}') {
                // \} closes as well as }.
                if (
                    in.charAt(pos) == '\\'
                        && pos + 1 < in.length()
                        && in.charAt(pos + 1) == '}'
                ) {
                    ++pos;
                    break;
                }
                body.append(in.charAt(pos++));
            }
            if (pos >= in.length())
                throw error("E554: Syntax error in \\{...}");
            ++pos;

            String s = body.toString();
            final boolean lazy = s.startsWith("-");
            if (lazy)
                s = s.substring(1);
            final String java;
            if (s.isEmpty() || s.equals(",")) {
                java = "*";
            } else if (s.indexOf(',') < 0) {
                java = "{" + number(s) + "}";
            } else {
                final int comma = s.indexOf(',');
                final String lo = s.substring(0, comma);
                final String hi = s.substring(comma + 1);
                int min = lo.isEmpty() ? 0 : number(lo);
                if (hi.isEmpty()) {
                    java = "{" + min + ",}";
                } else {
                    int max = number(hi);
                    // Vim takes {3,1} to mean {1,3}; Java refuses it.
                    if (min > max) {
                        final int t = min;
                        min = max;
                        max = t;
                    }
                    java = "{" + min + "," + max + "}";
                }
            }
            out.append(java);
            if (lazy)
                out.append('?');
            // An exact count, {n}, is still a fixed width.
            if (!java.matches("\\{\\d+\\}"))
                variableWidth = true;
        }

        private int number(String s) {
            try {
                return Integer.parseInt(s.trim());
            }
            catch (NumberFormatException e) {
                throw error("E554: Syntax error in \\{...}");
            }
        }

        /**
         * {@code \@=}, {@code \@!}, {@code \@<=}, {@code \@<!} and
         * {@code \@>}, which apply to the atom before them. Java writes
         * lookaround in front of its atom, so the atom already written is
         * wrapped. A group keeps its capture, so \1 still counts it.
         */
        private void lookaround() {
            if (lastAtom < 0)
                throw error("E64: @ follows nothing");
            // \@123<= limits how far back vim looks; Java needs no hint.
            while (pos < in.length() && Character.isDigit(in.charAt(pos)))
                ++pos;
            final String open;
            if (in.startsWith("<=", pos)) {
                open = "(?<=";
                pos += 2;
            } else if (in.startsWith("<!", pos)) {
                open = "(?<!";
                pos += 2;
            } else if (in.startsWith("=", pos)) {
                open = "(?=";
                pos += 1;
            } else if (in.startsWith("!", pos)) {
                open = "(?!";
                pos += 1;
            } else if (in.startsWith(">", pos)) {
                open = "(?>";
                pos += 1;
            } else {
                throw error("E64: invalid character after \\@");
            }
            out.insert(lastAtom, open);
            out.append(')');
        }

        // ------------------------------------------------------------ groups

        private void openGroup(String java) {
            groups.push(Integer.valueOf(out.length()));
            out.append(java);
            lastAtom = -1;
            branchStart = true;
        }

        private void closeGroup() {
            if (groups.isEmpty())
                throw error("E55: Unmatched \\)");
            out.append(')');
            lastAtom = groups.pop().intValue();
            branchStart = false;
        }

        /** The {@code \%} family. */
        private void percent() {
            if (pos >= in.length())
                throw error("E71: Invalid character after \\%");
            final char c = in.charAt(pos++);
            switch (c) {
                case '(':
                    openGroup("(?:");
                    return;
                case 'd':
                    codePoint(10, "0123456789");
                    return;
                case 'x':
                    codePoint(16, "0123456789abcdefABCDEF", 2);
                    return;
                case 'u':
                    codePoint(16, "0123456789abcdefABCDEF", 4);
                    return;
                case 'U':
                    codePoint(16, "0123456789abcdefABCDEF", 8);
                    return;
                case 'o':
                    codePoint(8, "01234567", 4);
                    return;
                default:
                    // \%V, \%#, \%23l and the like depend on the window and
                    // the cursor rather than the text.
                    throw error("\\%" + c + " is not supported");
            }
        }

        private void codePoint(int radix, String digits) {
            codePoint(radix, digits, Integer.MAX_VALUE);
        }

        private void codePoint(int radix, String digits, int max) {
            final int start = pos;
            while (
                pos < in.length()
                    && pos - start < max
                    && digits.indexOf(in.charAt(pos)) >= 0
            )
                ++pos;
            if (pos == start)
                throw error("E678: Invalid character after \\%[dxouU]");
            // Parsed as a long and range-checked: \%d99999999999 overflows an
            // int, and \%UFFFFFFFF is past Unicode. Either would otherwise
            // escape as an exception no caller expects.
            final long cp;
            try {
                cp = Long.parseLong(in.substring(start, pos), radix);
            }
            catch (NumberFormatException e) {
                throw error("E678: Invalid character after \\%[dxouU]");
            }
            if (cp > Character.MAX_CODE_POINT)
                throw error("E678: Invalid character after \\%[dxouU]");
            atom(Pattern.quote(new String(Character.toChars((int) cp))));
        }

        /** {@code \_x}: a class that also matches a newline, and {@code \_.}. */
        private void underscore() {
            if (pos >= in.length())
                throw error("E63: Invalid use of \\_");
            final char c = in.charAt(pos++);
            switch (c) {
                case '.':
                    atom("(?s:.)");
                    return;
                case '^':
                    out.append('^');
                    return;
                case '$':
                    out.append('$');
                    return;
                case 's':
                    atom("[ \\t\\n]");
                    return;
                case 'S':
                    atom("(?:[^ \\t]|\\n)");
                    return;
                case 'd':
                    atom("[0-9\\n]");
                    return;
                case 'w':
                    atom("[0-9A-Za-z_\\n]");
                    return;
                case 'a':
                    atom("[A-Za-z\\n]");
                    return;
                case 'l':
                    atom("[a-z\\n]");
                    return;
                case 'u':
                    atom("[A-Z\\n]");
                    return;
                case 'x':
                    atom("[0-9A-Fa-f\\n]");
                    return;
                case 'h':
                    atom("[A-Za-z_\\n]");
                    return;
                case '[':
                    // \_[...] is the class plus the newline.
                    final int start = out.length();
                    characterClass();
                    out.insert(start, "(?:");
                    out.append("|\\n)");
                    lastAtom = start;
                    return;
                default:
                    throw error("E63: Invalid use of \\_" + c);
            }
        }

        /** {@code \zs} and {@code \ze}: where the match starts and ends. */
        private void zed() {
            if (pos >= in.length())
                throw error("E68: Invalid character after \\z");
            final char c = in.charAt(pos++);
            if (c != 's' && c != 'e')
                throw error("\\z" + c + " is not supported");
            // They are rewritten as lookbehind and lookahead around the whole
            // pattern, which only works at the top level and without top
            // level alternatives, where "the whole pattern" is one thing.
            if (!groups.isEmpty())
                throw error("\\z" + c + " inside a group is not supported");
            if (c == 's' && variableWidth)
                throw error(
                    "\\zs after something of variable width is not "
                        + "supported"
                );
            if (c == 's')
                zs = out.length();
            else
                ze = out.length();
        }

        // ------------------------------------------------------------ classes

        /**
         * {@code [...]}, from the character after the opening bracket.
         *
         * With no closing bracket the {@code [} is just a character, as in
         * vim. Inside, Java would read a nested {@code [} as a union and
         * {@code &&} as an intersection, so both are escaped.
         */
        private void characterClass() {
            final int save = pos;
            final StringBuilder sb = new StringBuilder("[");
            if (pos < in.length() && in.charAt(pos) == '^') {
                sb.append('^');
                ++pos;
            }
            // A ] first is part of the class, not its end.
            if (pos < in.length() && in.charAt(pos) == ']') {
                sb.append("\\]");
                ++pos;
            }
            boolean closed = false;
            while (pos < in.length()) {
                final char c = in.charAt(pos++);
                if (c == ']') {
                    closed = true;
                    break;
                }
                if (c == '[' && in.startsWith(":", pos)) {
                    final int end = in.indexOf(":]", pos + 1);
                    if (end > 0) {
                        sb.append(posix(in.substring(pos + 1, end)));
                        pos = end + 2;
                        continue;
                    }
                }
                if (c == '\\' && pos < in.length()) {
                    classEscape(sb, in.charAt(pos++));
                    continue;
                }
                if (c == '[' || c == '&')
                    sb.append('\\');
                sb.append(c);
            }
            if (!closed) {
                pos = save;
                literal('[');
                return;
            }
            sb.append(']');
            atom(sb.toString());
        }

        private void classEscape(StringBuilder sb, char c) {
            switch (c) {
                case 'e':
                    sb.append("\\x1b");
                    return;
                case 't':
                    sb.append("\\t");
                    return;
                case 'r':
                    sb.append("\\r");
                    return;
                case 'b':
                    sb.append("\\x08");
                    return;
                case 'n':
                    sb.append("\\n");
                    return;
                case '\\':
                case ']':
                case '^':
                case '-':
                    sb.append('\\').append(c);
                    return;
                default:
                    // Anything else keeps its backslash, as vim does.
                    sb.append("\\\\");
                    if (c == '[' || c == '&')
                        sb.append('\\');
                    sb.append(c);
            }
        }

        private String posix(String name) {
            switch (name) {
                case "alnum":
                    return "0-9A-Za-z";
                case "alpha":
                    return "A-Za-z";
                case "blank":
                    return " \\t";
                case "cntrl":
                    return "\\x00-\\x1f\\x7f";
                case "digit":
                    return "0-9";
                case "graph":
                    return "\\x21-\\x7e";
                case "lower":
                    return "a-z";
                case "print":
                    return "\\x20-\\x7e";
                case "punct":
                    return "\\p{Punct}";
                case "space":
                    return " \\t\\n\\r\\f\\x0b";
                case "upper":
                    return "A-Z";
                case "xdigit":
                    return "0-9A-Fa-f";
                case "return":
                    return "\\r";
                case "tab":
                    return "\\t";
                case "escape":
                    return "\\x1b";
                case "backspace":
                    return "\\x08";
                default:
                    throw error("[:" + name + ":] is not supported");
            }
        }

        // ------------------------------------------------------------ result

        private String finish() {
            final String s = out.toString();
            if (zs < 0 && ze < 0)
                return s;
            if (topLevelAlternation)
                throw error("\\zs and \\ze with a top-level \\| are not supported");
            final int start = zs < 0 ? 0 : zs;
            final int end = ze < 0 ? s.length() : ze;
            if (end < start)
                throw error("\\ze before \\zs is not supported");
            final StringBuilder sb = new StringBuilder();
            if (zs >= 0)
                sb.append("(?<=").append(s, 0, start).append(')');
            sb.append(s, start, end);
            if (ze >= 0)
                sb.append("(?=").append(s, end, s.length()).append(')');
            return sb.toString();
        }

        private PatternSyntaxException error(String description) {
            return new PatternSyntaxException(description, in, pos - 1);
        }
    }
}
