/*
 * Paragraphs.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

/**
 * Paragraphs and sections, the way vim finds them: one scan for both (vim's
 * findpar). A paragraph starts at an empty line, a section at a '{' (or '}')
 * in the first column, and both at a form feed or an nroff macro such as
 * {@code .SH}, from vim's default 'sections' and 'paragraphs'.
 */
public final class Paragraphs
{
    /** Vim's 'sections' and 'paragraphs': nroff macros, two letters each. */
    private static final String SECTIONS = "SHNHH HUnhsh";
    private static final String PARAGRAPHS = "IPLPPPQPP TPHPLIPpLpItpplpipbp";

    private Paragraphs()
    {
    }

    /**
     * Where {@code count} paragraphs or sections away is: the start of the
     * line that begins one, past any run of them the scan starts in.
     * Running out of buffer stops at its edge -- going forward, the end of
     * the last line, except for '}' -- unless the count was not used up.
     *
     * @param what  0 for a paragraph, '{' or '}' for a section
     * @param both  a '}' ends a '{' section too, and is passed over, as for
     *              d]]
     * @return null when the count runs past the edge of the buffer
     */
    public static Position find(Position from, boolean forward, int count,
                                char what, boolean both)
    {
        Line line = from.getLine();
        while (count-- > 0) {
            // Only once a line of text has gone by, so that a run of
            // boundaries is passed over.
            boolean skipped = false;
            for (boolean first = true;; first = false) {
                if (line.length() != 0)
                    skipped = true;
                if (!first && skipped && isStart(line, what, both))
                    break;
                final Line next = forward ? line.nextVisible()
                                          : line.previousVisible();
                if (next == null) {
                    if (count > 0)
                        return null;
                    break;
                }
                line = next;
            }
        }
        if (both && startsWith(line, '}')) {
            final Line next = line.nextVisible();
            // A '}' on the last line: its start, or where the scan began if
            // that is on the line too, so that d]] takes nothing.
            if (next == null)
                return line == from.getLine() ? new Position(from)
                                               : new Position(line, 0);
            line = next;
        }
        if (forward && what != '}' && line.nextVisible() == null)
            return new Position(line, line.length());
        return new Position(line, 0);
    }

    /** Vim's startPS: does this line start a paragraph or a section? */
    public static boolean isStart(Line line, char what, boolean both)
    {
        final String text = line.getText();
        if (text == null || text.isEmpty())
            return what == 0;
        final char c = text.charAt(0);
        if (c == what || c == '\f' || both && c == '}')
            return true;
        return c == '.' && (isMacro(SECTIONS, text)
                            || what == 0 && isMacro(PARAGRAPHS, text));
    }

    private static boolean startsWith(Line line, char c)
    {
        return line.length() > 0 && line.charAt(0) == c;
    }

    /**
     * Vim's inmacro: the two letters after the line's dot are one of the
     * option's pairs. A space in the option stands for a space or for the
     * end of the line.
     */
    private static boolean isMacro(String macros, String text)
    {
        final char s0 = charAt(text, 1);
        final char s1 = charAt(text, 2);
        for (int i = 0; i < macros.length(); i += 2) {
            final char m0 = macros.charAt(i);
            final char m1 = charAt(macros, i + 1);
            if ((m0 == s0 || m0 == ' ' && (s0 == 0 || s0 == ' '))
                && (m1 == s1 || (m1 == 0 || m1 == ' ')
                                && (s0 == 0 || s1 == 0 || s1 == ' ')))
                return true;
        }
        return false;
    }

    private static char charAt(String s, int i)
    {
        return i < s.length() ? s.charAt(i) : 0;
    }

    // ------------------------------------------------------------ commands

    /** {@code forwardParagraph} -- to the empty line after this paragraph. */
    public static void forwardParagraph()
    {
        moveTo(true, (char) 0);
    }

    /** {@code backwardParagraph} -- to the empty line before it. */
    public static void backwardParagraph()
    {
        moveTo(false, (char) 0);
    }

    /**
     * {@code forwardSection} -- to the next '{' in the first column, or with
     * {@code forwardSection &#125;} the next '}'.
     */
    public static void forwardSection(String parameters)
    {
        moveTo(true, section(parameters));
    }

    public static void forwardSection()
    {
        moveTo(true, '{');
    }

    /** {@code backwardSection} -- the same, backward. */
    public static void backwardSection(String parameters)
    {
        moveTo(false, section(parameters));
    }

    public static void backwardSection()
    {
        moveTo(false, '{');
    }

    private static char section(String parameters)
    {
        return parameters != null && parameters.trim().equals("}")
            ? '}' : '{';
    }

    private static void moveTo(boolean forward, char what)
    {
        final Editor editor = Editor.currentEditor();
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        Position to = find(dot, forward, 1, what, false);
        if (to == null)
            return;
        // A section lands on the first non-blank, as vim's does.
        if (what != 0)
            to = new Position(to.getLine(),
                              CaretCommands.firstNonBlank(to.getLine()));
        editor.addUndo(SimpleEdit.MOVE);
        editor.unmark();
        editor.setDot(to);
        editor.moveCaretToDotCol();
        editor.updateDotLine();
    }
}
