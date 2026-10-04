/*
 * Sentences.java
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
 * Sentences, the way vim finds them (vim's findsent). A sentence ends at a
 * '.', '!' or '?' followed, after any closing ')', ']', '"' or '\'', by a
 * space, a tab or the end of the line; the next one starts at the first
 * non-blank after that. An empty line, and the start of a paragraph or a
 * section ({@link Paragraphs#isStart}), is a sentence boundary too.
 */
public final class Sentences {
    private static final String END = ".!?";
    private static final String CLOSING = ")]\"'";
    private static final String END_OR_CLOSING = END + CLOSING;

    private Sentences() {}

    /**
     * Where the start of the sentence {@code count} away is.
     *
     * @return null when the count runs past the edge of the buffer; one
     *         that only just reaches it stops at the edge, forward at the
     *         end of the last line
     */
    @SuppressWarnings("LabelledBreakTarget") // found: is the block a sentence is found in.
    public static Position find(Position from, boolean forward, int count) {
        final Scan pos = new Scan(from);
        boolean noSkip = false;
        while (count-- > 0) {
            final Scan prev = new Scan(pos);
            found: {
                if (pos.at() == 0) {
                    // On an empty line: to the next line of text.
                    do {
                        if (pos.step(forward) == -1)
                            break;
                    } while (pos.at() == 0);
                    if (forward)
                        break found;
                } else if (
                    forward
                        && pos.offset == 0
                        && Paragraphs.isStart(pos.line, (char) 0, false)
                ) {
                    final Line next = pos.line.nextVisible();
                    if (next == null)
                        return null;
                    pos.line = next;
                    break found;
                } else if (!forward) {
                    pos.decl();
                }

                // Back over blanks and the punctuation that ends a sentence,
                // so that a scan from its end finds that end again.
                boolean foundDot = false;
                int c;
                while (isWhite(c = pos.at()) || END_OR_CLOSING.indexOf(c) >= 0) {
                    final Scan before = new Scan(pos);
                    if (
                        before.decl() == -1
                            || before.line.length() == 0 && forward
                    )
                        break;
                    if (foundDot)
                        break;
                    if (END.indexOf(c) >= 0)
                        foundDot = true;
                    if (
                        CLOSING.indexOf(c) >= 0
                            && END_OR_CLOSING.indexOf(before.at()) < 0
                    )
                        break;
                    pos.decl();
                }

                // To the end of the sentence.
                final Line startLine = pos.line;
                while (true) {
                    c = pos.at();
                    if (
                        c == 0
                            || pos.offset == 0
                                && Paragraphs.isStart(pos.line, (char) 0, false)
                    ) {
                        if (!forward && pos.line != startLine) {
                            pos.line = pos.line.nextVisible();
                            pos.offset = 0;
                        }
                        break;
                    }
                    if (END.indexOf(c) >= 0) {
                        final Scan after = new Scan(pos);
                        int r;
                        do {
                            if ((r = after.inc()) == -1)
                                break;
                        } while (CLOSING.indexOf(r = after.at()) >= 0);
                        if (r == -1 || r == ' ' || r == '\t' || r == 0) {
                            pos.set(after);
                            if (pos.at() == 0)
                                pos.inc();
                            break;
                        }
                    }
                    if (pos.step(forward) == -1) {
                        if (count > 0)
                            return null;
                        noSkip = true;
                        break;
                    }
                }
            }
            int c;
            while (!noSkip && ((c = pos.at()) == ' ' || c == '\t'))
                if (pos.incl() == -1)
                    break;
            if (pos.equals(prev)) {
                // Did not move: once more, from one character on.
                if (pos.step(forward) == -1) {
                    if (count > 0)
                        return null;
                    break;
                }
                ++count;
            }
        }
        return new Position(pos.line, pos.offset);
    }

    private static boolean isWhite(int c) {
        return c == ' ' || c == '\t';
    }

    /**
     * A position that can stand on a line's end, as vim's does, stepping
     * as vim's inc() and dec() do: by whole characters, stopping on each
     * line end, or with incl() and decl() on the end of an empty line only.
     */
    private static final class Scan {
        Line line;
        int offset;

        Scan(Position pos) {
            line = pos.getLine();
            offset = pos.getOffset();
        }

        Scan(Scan other) {
            set(other);
        }

        void set(Scan other) {
            line = other.line;
            offset = other.offset;
        }

        /** The character here, 0 at the end of the line. */
        int at() {
            return offset < line.length() ? line.charAt(offset) : 0;
        }

        /** 0 within a line, 2 onto its end, 1 onto the next, -1 if none. */
        int inc() {
            if (offset < line.length()) {
                offset += Character.charCount(line.getText().codePointAt(offset));
                return offset < line.length() ? 0 : 2;
            }
            final Line next = line.nextVisible();
            if (next == null)
                return -1;
            line = next;
            offset = 0;
            return 1;
        }

        /** 0 within a line, 1 onto the end of the one before, -1 if none. */
        int dec() {
            if (offset > 0) {
                offset = Character.offsetByCodePoints(line.getText(), offset, -1);
                return 0;
            }
            final Line previous = line.previousVisible();
            if (previous == null)
                return -1;
            line = previous;
            offset = line.length();
            return 1;
        }

        int incl() {
            int r = inc();
            if (r >= 1 && offset != 0)
                r = inc();
            return r;
        }

        int decl() {
            int r = dec();
            if (r == 1 && offset != 0)
                r = dec();
            return r;
        }

        int step(boolean forward) {
            return forward ? incl() : decl();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Scan
                && ((Scan) o).line == line
                && ((Scan) o).offset == offset;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(line) + offset;
        }
    }

    // ------------------------------------------------------------ commands

    /** {@code forwardSentence} -- to the start of the next sentence. */
    public static void forwardSentence() {
        moveTo(true);
    }

    /** {@code backwardSentence} -- to the start of this one, or the last. */
    public static void backwardSentence() {
        moveTo(false);
    }

    private static void moveTo(boolean forward) {
        final Editor editor = Editor.currentEditor();
        final Position dot = editor.getDot();
        if (dot == null)
            return;
        final Position to = find(dot, forward, 1);
        if (to == null)
            return;
        editor.beginMotion();
        editor.setDot(to);
        editor.moveCaretToDotCol();
        editor.updateDotLine();
    }
}
