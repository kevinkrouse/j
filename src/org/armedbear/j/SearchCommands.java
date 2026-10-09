/*
 * SearchCommands.java
 *
 * Copyright (C) 1998-2003 Peter Graves
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

package org.armedbear.j;

import java.util.regex.Matcher;
import org.armedbear.j.mode.list.ListOccurrencesInFilesBuffer;

/** Finding the next or previous match, incrementally, or of the word at the caret. */
public final class SearchCommands {
    private SearchCommands() {}

    public static Search getSearchAtDot(Editor editor) {
        if (editor.getDot() == null)
            return null;
        String pattern = null;
        boolean wholeWordsOnly = false;
        if (editor.getMark() != null) {
            // No action if there's a multi-line selection.
            if (editor.getMarkLine() == editor.getDotLine())
                pattern = (new Region(editor.getBuffer(), editor.getDot(), editor.getMark())).toString();
        } else {
            pattern = editor.tokenAt(editor.getDot());
            wholeWordsOnly = true;
        }
        if (pattern != null && pattern.length() != 0)
            return new Search(pattern, false, wholeWordsOnly);
        else
            return null;
    }

    // Assumes dot is on first char of found pattern.
    public static void markFoundPattern(Editor editor, Search search) {
        final Display display = editor.getDisplay();
        if (search.isRegularExpression() && search.isMultilinePattern()) {
            Matcher matcher = search.getMatch();
            if (matcher != null) {
                editor.setDot(editor.getBuffer().getPosition(matcher.start()));
                editor.setMark(editor.getBuffer().getPosition(matcher.end()));
                final Line markLine = editor.getMarkLine();
                for (Line line = editor.getDotLine(); line != null; line = line.next()) {
                    editor.update(line);
                    if (line == markLine)
                        break;
                }
                editor.moveCaretToDotCol();
            }
        } else {
            final int context = 2; // This could be a preference.
            Position saved = editor.getDot().copy();

            // Move dot to end of found pattern.
            int length;
            if (search.getMatch() != null)
                length = search.getMatch().group().length();
            else
                length = search.getPatternLength();

            // Found pattern might go beyond end of line.
            editor.getDot().setOffset(Math.min(editor.getDot().getOffset() + length, editor.getDotLine().length()));

            // Set mark at end of pattern.
            editor.moveCaretToDotCol();
            editor.setMarkAtDot();

            // Make sure end of pattern is actually visible, with additional
            // context as appropriate.
            int absCol = editor.getDotCol() + context;
            display.ensureColumnVisible(editor.getDotLine(), absCol);

            // Restore dot to original position at start of pattern.
            editor.setDot(saved);

            // Make sure start of pattern is actually visible, with additional
            // context as appropriate.
            absCol = editor.getDotCol() - context;
            if (absCol < 0)
                absCol = 0;
            display.ensureColumnVisible(editor.getDotLine(), absCol);
            editor.moveCaretToDotCol();
        }
    }

    public static void findNext(Editor editor) {
        final Search search = editor.getLastSearch();
        if (search != null) {
            // Shows the matches again after clearSearchHighlight.
            editor.setSearchHighlightHidden(false);
            Position start;
            if (editor.getMark() != null) {
                Region r = new Region(editor);
                start = new Position(r.getBegin());
            } else
                start = new Position(editor.getDot());
            if (!start.next())
                return;
            editor.setWaitCursor();
            Position pos = search.find(editor.getBuffer(), start);
            editor.setDefaultCursor();
            if (pos != null) {
                editor.recordJump();
                editor.moveDotTo(pos);
                markFoundPattern(editor, search);
                if (search instanceof FindInFiles findInFiles) {
                    if (editor.getBuffer().getFile() != null) {
                        ListOccurrencesInFilesBuffer buf = findInFiles.getOutputBuffer();
                        if (buf != null)
                            buf.follow(editor.getBuffer().getFile(), editor.getDotLine());
                    }
                }
                return;
            }
            if (search instanceof FindInFiles findInFiles) {
                Editor ed = editor.getOtherEditor();
                if (ed != null) {
                    ListOccurrencesInFilesBuffer buf = findInFiles.getOutputBuffer();
                    if (ed.getBuffer() == buf) {
                        buf.findNextOccurrence(ed);
                        return;
                    }
                }
            }
            search.notFound(editor);
        }
    }

    public static void findPrev(Editor editor) {
        final Search search = editor.getLastSearch();
        if (search != null) {
            editor.setSearchHighlightHidden(false);
            Position start;
            if (editor.getMark() != null) {
                Region r = new Region(editor);
                start = new Position(r.getBegin());
            } else
                start = new Position(editor.getDot());
            if (!start.prev())
                return;
            editor.setWaitCursor();
            Position pos = search.reverseFind(editor.getBuffer(), start);
            editor.setDefaultCursor();
            if (pos != null) {
                editor.recordJump();
                editor.moveDotTo(pos);
                markFoundPattern(editor, search);
                if (search instanceof FindInFiles findInFiles) {
                    if (editor.getBuffer().getFile() != null) {
                        ListOccurrencesInFilesBuffer buf = findInFiles.getOutputBuffer();
                        if (buf != null)
                            buf.follow(editor.getBuffer().getFile(), editor.getDotLine());
                    }
                }
                return;
            }
            if (search instanceof FindInFiles findInFiles) {
                Editor ed = editor.getOtherEditor();
                if (ed != null) {
                    ListOccurrencesInFilesBuffer buf = findInFiles.getOutputBuffer();
                    if (ed.getBuffer() == buf) {
                        buf.findPreviousOccurrence(ed);
                        return;
                    }
                }
            }
            search.notFound(editor);
        }
    }

    public static void incrementalFind(Editor editor) {
        if (editor.getDot() == null)
            return;

        // Use location bar.
        if (editor.getPromptLocationBar() != null) {
            editor.getPromptLocationBar().setLabelText(LocationBar.PROMPT_PATTERN);
            HistoryTextField textField = editor.getPromptLocationBar().getTextField();
            textField.setHandler(new IncrementalFindTextFieldHandler(editor, textField));
            textField.setHistory(new History("incrementalFind.pattern"));
            textField.setText("");
            editor.setFocusToTextField();
        }
    }

    /** findNextWord, or with "partial" g* in vim: matches inside other words too. */
    public static void findNextWord(Editor editor, String arg) {
        findWordAtDot(editor, true, "partial".equals(arg));
    }

    public static void findNextWord(Editor editor) {
        findWordAtDot(editor, true, false);
    }

    /** findPrevWord, or with "partial" g# in vim. */
    public static void findPrevWord(Editor editor, String arg) {
        findWordAtDot(editor, false, "partial".equals(arg));
    }

    public static void findPrevWord(Editor editor) {
        findWordAtDot(editor, false, false);
    }

    private static void findWordAtDot(Editor editor, boolean forward, boolean partial) {
        if (editor.getDot() == null)
            return;
        final Word word = wordAt(editor, editor.getDot());
        if (word == null)
            return;
        final Position pos = findWord(editor, editor.getDot(), forward, partial, 1, false, true);
        if (pos != null) {
            editor.recordJump();
            editor.moveDotTo(pos);
            markFoundPattern(editor, editor.getLastSearch());
        } else
            editor.getLastSearch().notFound(editor);
    }

    /**
     * The word findNextWord and vim's * look for: the keyword under the
     * caret, or the next one along its line; on a line with none, the next
     * run of non-blanks, which is not a keyword.
     */
    public record Word(String text, int offset, boolean keyword) {}

    public static Word wordAt(Editor editor, Position at) {
        final Mode mode = editor.getBuffer().getMode();
        final Position pos = new Position(at);
        final String text = pos.getLine().getText();
        if (text == null)
            return null;
        for (int offset = pos.getOffset(); offset < text.length(); offset++) {
            pos.setOffset(offset);
            final String word = mode.getIdentifier(pos);
            if (word != null && !word.isEmpty())
                // getIdentifier scans back to the start of the word, so with
                // the caret inside one the word begins before this offset.
                return new Word(word, Math.max(0, text.lastIndexOf(word, offset)), true);
        }
        for (int offset = at.getOffset(); offset < text.length(); offset++) {
            if (Character.isWhitespace(text.charAt(offset)))
                continue;
            int end = offset;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end)))
                ++end;
            return new Word(text.substring(offset, end), offset, false);
        }
        return null;
    }

    /**
     * Searches for the word at from, as vim's * and # do, and makes it the
     * last search, in the direction it went: whole words unless partial or
     * the word is no keyword, from the word's start, count matches on.
     * Returns where the count'th match is, or null.
     */
    public static Position findWord(
            Editor editor,
            Position from,
            boolean forward,
            boolean partial,
            int count,
            boolean ignoreCase,
            boolean wrap) {
        final Word word = wordAt(editor, from);
        if (word == null)
            return null;
        final Search search = new Search(word.text(), ignoreCase, word.keyword() && !partial);
        search.setForward(forward);
        editor.setLastSearch(search);
        editor.setSearchHighlightHidden(false);
        Position pos = new Position(from.getLine(), word.offset());
        for (int i = 0; i < count && pos != null; i++)
            pos = forward
                    ? nextMatch(editor.getBuffer(), search, pos, wrap)
                    : prevMatch(editor.getBuffer(), search, pos, wrap);
        return pos;
    }

    // The first match after pos; with wrap, from the top if there is none.
    private static Position nextMatch(Buffer buffer, Search search, Position pos, boolean wrap) {
        final Position start = new Position(pos);
        Position found = start.next() ? search.find(buffer.getMode(), start) : null;
        if (found == null && wrap)
            found = search.find(buffer.getMode(), new Position(buffer.getFirstLine(), 0));
        return found;
    }

    // The last match before pos; with wrap, from the bottom if there is none.
    private static Position prevMatch(Buffer buffer, Search search, Position pos, boolean wrap) {
        final Position start = new Position(pos);
        Position found = start.prev() ? search.reverseFind(buffer, start) : null;
        if (found != null && !found.isBefore(pos))
            found = null;
        if (found == null && wrap)
            found = search.reverseFind(buffer, buffer.getEnd());
        return found;
    }

    public static void findFirstOccurrence(Editor editor) {
        if (editor.getDot() == null)
            return;
        String pattern = editor.getTokenAtDot();
        if (pattern == null || pattern.length() == 0)
            return;
        final Search search = new Search(pattern, false, true);
        editor.setLastSearch(search);
        Position pos = search.find(editor.getBuffer().getMode(), new Position(editor.getBuffer().getFirstLine(), 0));
        if (pos != null) {
            editor.recordJump();
            editor.moveDotTo(pos);
            markFoundPattern(editor, search);
        } else
            search.notFound(editor);
    }
}
