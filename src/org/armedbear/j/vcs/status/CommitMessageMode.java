/*
 * CommitMessageMode.java
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

package org.armedbear.j.vcs.status;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.CommentRing;
import org.armedbear.j.Editor;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.Mode;
import org.armedbear.j.Property;

/**
 * A commit message's mode: a summary line past 50 characters, and any line
 * past 72, in the warning color beyond that; # lines dimmed.
 */
public final class CommitMessageMode extends AbstractMode implements Mode {
    static final int SUMMARY = 50;
    static final int BODY = 72;

    private static final CommitMessageMode mode = new CommitMessageMode();

    private CommitMessageMode() {
        super(VCS_COMMIT_MESSAGE_MODE, VCS_COMMIT_MESSAGE_MODE_NAME);
        setProperty(Property.SHOW_LINE_NUMBERS, false);
        setProperty(Property.SHOW_CHANGE_MARKS, false);
        setProperty(Property.HIGHLIGHT_MATCHING_BRACKET, false);
        setProperty(Property.HIGHLIGHT_BRACKETS, false);
    }

    public static CommitMessageMode getMode() {
        return mode;
    }

    @Override
    public String getHelpTopic() {
        return "git.html#committing";
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        km.mapKey(KeyEvent.VK_ENTER, CTRL_MASK, "vcsCommitFinish");
        km.mapKey(KeyEvent.VK_ESCAPE, 0, "vcsCommitCancel");
        km.mapKey(KeyEvent.VK_P, CTRL_MASK, "vcsCommitPreviousMessage");
        km.mapKey(KeyEvent.VK_N, CTRL_MASK, "vcsCommitNextMessage");
    }

    private static KeyMap modalKeyMap;

    // In vim's normal mode, where Escape has nothing left to leave.
    @Override
    public synchronized KeyMap getModalKeyMap() {
        if (modalKeyMap == null) {
            final KeyMap km = new KeyMap();
            km.mapKey(KeyEvent.VK_ENTER, CTRL_MASK, "vcsCommitFinish");
            km.mapKey(KeyEvent.VK_ESCAPE, 0, "vcsCommitCancel");
            modalKeyMap = km;
        }
        return modalKeyMap;
    }

    private static final String[][] HINTS = { { "vcsCommitFinish", "commit" }, { "vcsCommitCancel", "cancel" } };

    @Override
    public String[][] getHints(Buffer buffer) {
        return buffer.getBooleanProperty(Property.VCS_HINTS) ? HINTS : null;
    }

    /** :wq and :x commit, :q gives the message up asking first, :q! without asking. */
    @Override
    public boolean runExCommand(Editor editor, String name, boolean bang) {
        switch (name) {
            case "wq", "x", "xit", "exi", "exit" -> CommitCommands.vcsCommitFinish(editor);
            case "q", "quit" -> {
                if (bang)
                    CommitCommands.vcsCommitAbort(editor);
                else
                    CommitCommands.vcsCommitCancel(editor);
            }
            case "w", "write" -> editor.status("A message isn't saved: :wq or ZZ commits it, :q! or ZQ gives it up");
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Giving up a message that has one asks first, and keeps it in the comment ring. */
    @Override
    public boolean confirmClose(Editor editor, Buffer buffer) {
        if (!(buffer instanceof CommitMessageBuffer b) || b.isClosing() || b.getMessage().isEmpty())
            return true;
        if (b.isCommitting()) {
            editor.status("Committing: it closes when the commit is made");
            return false;
        }
        if (!StatusActions.confirm.test(editor, "Give up this commit message? Ctrl P brings it back next time."))
            return false;
        CommentRing.getInstance().appendNew(b.getMessage());
        b.setClosing();
        return true;
    }

    @Override
    public Formatter getFormatter(Buffer buffer) {
        return new MessageFormatter(buffer);
    }

    private static final class MessageFormatter extends Formatter {
        private static final int TEXT = 0;
        private static final int COMMENT = 1;
        private static final int OVER = 2;
        private static final int ADDED = 3;
        private static final int REMOVED = 4;
        private static final int HUNK = 5;
        private static final int FILE = 6;

        // The scissors line's number, until the text changes.
        private int scissorsModCount = -1;
        private int scissorsLine = -1;

        MessageFormatter(Buffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public LineSegmentList formatLine(Line line) {
            clearSegmentList();
            final String text = getDetabbedText(line);
            final int scissors = scissorsLine();
            if (scissors >= 0 && line.lineNumber() > scissors && !text.startsWith("#")) {
                // The verbose message's diff.
                addSegment(
                    text,
                    text.startsWith("+") && !text.startsWith("+++ ") ? ADDED
                            : text.startsWith("-") && !text.startsWith("--- ") ? REMOVED
                            : text.startsWith("@@") ? HUNK
                            : text.startsWith("diff ") || text.startsWith("+++ ") || text.startsWith("--- ") ? FILE
                            : TEXT);
                return segmentList;
            }
            if (text.startsWith("#")) {
                addSegment(text, COMMENT);
                return segmentList;
            }
            final int limit = isSummary(line) ? SUMMARY : BODY;
            if (text.length() <= limit)
                addSegment(text, TEXT);
            else {
                addSegment(text, 0, limit, TEXT);
                addSegment(text, limit, OVER);
            }
            return segmentList;
        }

        private int scissorsLine() {
            final int modCount = buffer.getModCount();
            if (modCount != scissorsModCount && buffer instanceof CommitMessageBuffer b) {
                final Line s = b.scissors();
                scissorsLine = s == null ? -1 : s.lineNumber();
                scissorsModCount = modCount;
            }
            return scissorsLine;
        }

        @Override
        public java.awt.Color getColor(int format) {
            final boolean dark = org.armedbear.j.DefaultTheme.isDark(getBackgroundColor());
            if (format == ADDED)
                return new java.awt.Color(dark ? 0x7ccf86 : 0x1a7f37);
            if (format == REMOVED)
                return new java.awt.Color(dark ? 0xf08080 : 0xc0262d);
            return super.getColor(format);
        }

        // The first line that isn't a comment.
        private static boolean isSummary(Line line) {
            for (Line l = line.previous(); l != null; l = l.previous())
                if (!l.getText().startsWith("#"))
                    return false;
            return true;
        }

        @Override
        public FormatTable getFormatTable() {
            if (formatTable == null) {
                formatTable = new FormatTable("CommitMessageMode");
                formatTable.addEntryFromPrefs(TEXT, "text");
                formatTable.addEntryFromPrefs(COMMENT, "comment");
                formatTable.addEntryFromPrefs(OVER, "overLong", "keyword");
                formatTable.addEntryFromPrefs(ADDED, "text");
                formatTable.addEntryFromPrefs(REMOVED, "text");
                formatTable.addEntryFromPrefs(HUNK, "hunkHeader", "comment");
                formatTable.addEntryFromPrefs(FILE, "file", "keyword");
            }
            return formatTable;
        }
    }
}
