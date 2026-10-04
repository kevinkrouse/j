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
                        ListOccurrencesInFilesBuffer buf =
                            findInFiles.getOutputBuffer();
                        if (buf != null)
                            buf.follow(editor.getBuffer().getFile(), editor.getDotLine());
                    }
                }
                return;
            }
            if (search instanceof FindInFiles findInFiles) {
                Editor ed = editor.getOtherEditor();
                if (ed != null) {
                    ListOccurrencesInFilesBuffer buf =
                        findInFiles.getOutputBuffer();
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
                        ListOccurrencesInFilesBuffer buf =
                            findInFiles.getOutputBuffer();
                        if (buf != null)
                            buf.follow(editor.getBuffer().getFile(), editor.getDotLine());
                    }
                }
                return;
            }
            if (search instanceof FindInFiles findInFiles) {
                Editor ed = editor.getOtherEditor();
                if (ed != null) {
                    ListOccurrencesInFilesBuffer buf =
                        findInFiles.getOutputBuffer();
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
        if (editor.getLocationBar() != null) {
            editor.getLocationBar().setLabelText(LocationBar.PROMPT_PATTERN);
            HistoryTextField textField = editor.getLocationBar().getTextField();
            textField.setHandler(new IncrementalFindTextFieldHandler(editor, textField));
            textField.setHistory(new History("incrementalFind.pattern"));
            textField.setText("");
            editor.setFocusToTextField();
        }
    }

    public static void findNextWord(Editor editor) {
        if (editor.getDot() == null)
            return;
        String pattern = editor.getTokenAtDot();
        if (pattern == null || pattern.length() == 0)
            return;
        final Search search = new Search(pattern, false, true);
        editor.setLastSearch(search);
        Position start;
        if (editor.getMark() != null && editor.getDot().isBefore(editor.getMark()))
            start = new Position(editor.getMark());
        else
            start = new Position(editor.getDot());
        Position pos = search.find(editor.getBuffer().getMode(), start);
        if (pos != null && pos.equals(start)) {
            if (pos.next())
                pos = search.find(editor.getBuffer().getMode(), pos);
        }
        if (pos != null && !pos.equals(start)) {
            editor.recordJump();
            editor.moveDotTo(pos);
            markFoundPattern(editor, search);
        } else
            search.notFound(editor);
    }

    public static void findPrevWord(Editor editor) {
        if (editor.getDot() == null)
            return;
        String pattern = editor.getTokenAtDot();
        if (pattern == null || pattern.length() == 0)
            return;
        final Search search = new Search(pattern, false, true);
        editor.setLastSearch(search);
        boolean found = false;
        Position start = null;
        if (editor.getMark() != null)
            start = new Region(editor).getBegin();
        else
            start = new Position(editor.getDot());
        if (start.prev()) {
            Position pos = search.reverseFind(editor.getBuffer(), start);
            if (pos != null && pos.getLine() == start.getLine()) {
                if (pos.getOffset() + search.getPatternLength() > start.getOffset()) {
                    // We've found the instance we started with. Keep looking.
                    start = new Position(pos);
                    if (start.prev())
                        pos = search.reverseFind(editor.getBuffer(), start);
                    else
                        pos = null;
                }
            }
            if (pos != null) {
                found = true;
                editor.recordJump();
                editor.moveDotTo(pos);
                markFoundPattern(editor, search);
            }
        }
        if (!found)
            search.notFound(editor);
    }

    public static void findFirstOccurrence(Editor editor) {
        if (editor.getDot() == null)
            return;
        String pattern = editor.getTokenAtDot();
        if (pattern == null || pattern.length() == 0)
            return;
        final Search search = new Search(pattern, false, true);
        editor.setLastSearch(search);
        Position pos = search.find(
            editor.getBuffer().getMode(),
            new Position(editor.getBuffer().getFirstLine(), 0)
        );
        if (pos != null) {
            editor.recordJump();
            editor.moveDotTo(pos);
            markFoundPattern(editor, search);
        } else
            search.notFound(editor);
    }
}
