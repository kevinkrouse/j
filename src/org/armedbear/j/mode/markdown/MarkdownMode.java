/*
 * MarkdownMode.java
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

package org.armedbear.j.mode.markdown;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.LexicalState;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.NavigationComponent;
import org.armedbear.j.Position;
import org.armedbear.j.Property;
import org.armedbear.j.SidebarTagTree;
import org.armedbear.j.SimpleEdit;
import org.armedbear.j.SystemBuffer;
import org.armedbear.j.Tagger;
import org.armedbear.j.TextLink;
import org.armedbear.j.View;

public final class MarkdownMode extends AbstractMode implements Mode {
    private static final MarkdownMode mode = new MarkdownMode();

    private MarkdownMode() {
        super(MARKDOWN_MODE, MARKDOWN_MODE_NAME);
        // Brackets in prose are links and task boxes, colored as such.
        setProperty(Property.RAINBOW_DELIMITERS, false);
    }

    public static final MarkdownMode getMode() {
        return mode;
    }

    @Override
    public final Formatter getFormatter(Buffer buffer) {
        return new MarkdownFormatter(buffer);
    }

    /**
     * The single backtick pairs inline (`` `code` ``); the emphasis runs
     * and the fence are not in this table — they need the surrounding
     * text to classify, so they go through {@link #electricOverride}.
     */
    @Override
    public String getPairs() {
        return "()[]{}\"\"``";
    }

    /**
     * The Markdown electric rules the flat pair table cannot express:
     * the triple-backtick fence, which closes on the line below, and
     * the emphasis delimiter runs, whose role — opener, closer, or
     * literal — depends on the text around them.
     *
     * <p>Each rule reads the buffer at the moment of the keystroke, so
     * the live keystroke and the vim {@code .} replay of the same key
     * see the same state and reach the same result. The mode owns its
     * compound edit; the coordinator has already excluded a selection
     * and a suppressed (literal) insertion.
     */
    @Override
    public boolean electricOverride(Editor editor, char c) {
        final Buffer buffer = editor.getBuffer();
        final Line line = editor.getDotLine();
        final int offset = editor.getDotOffset();
        // Inside a fence, an HTML comment or a code span, nothing pairs.
        if (lexicalState(buffer, editor.getDot()) != LexicalState.NEUTRAL)
            return false;
        final String text = line.getText();
        final int end = text.length();
        if (c == '`')
            return fenceOverride(editor, line, text, offset, end);
        if (MarkdownDelimiters.isMarker(c))
            return markerOverride(editor, text, offset, end, c);
        return contentOverride(editor, text, offset, end, c);
    }

    /**
     * The third backtick of a fence at line start: insert it, then a
     * matching closing fence on the line below, and leave the caret
     * after the opening fence for the language name. The first two
     * backticks arrived through the generic pair and type-over, so the
     * caret sits at the end of a line whose content is exactly two
     * backticks, optionally after up to three leading spaces.
     */
    private boolean fenceOverride(Editor editor, Line line, String text, int offset, int end) {
        if (offset != end || offset < 2 || text.charAt(offset - 1) != '`' || text.charAt(offset - 2) != '`')
            return false;
        // What precedes the two backticks must be at most three spaces,
        // so the fence is at the line start, not mid-sentence.
        for (int i = 0; i < offset - 2; i++)
            if (text.charAt(i) != ' ' || i >= 3)
                return false;
        final CompoundEdit compoundEdit = editor.beginCompoundEdit();
        try {
            editor.insertChar('`');
            // The closing fence goes on the line below; the caret stays
            // after the opening fence for the language name.
            editor.getBuffer().insertString(new Position(editor.getDot()), "\n```");
            editor.getDot().moveTo(line, offset + 1);
        }
        finally {
            editor.endCompoundEdit(compoundEdit);
        }
        return true;
    }

    /**
     * A marker typed with the caret immediately before a run of the
     * same marker steps over one delimiter, not the whole run:
     * {@code **bold|**} + {@code *} → {@code **bold*|*}. Growing the
     * opener run (caret after the run, nothing before) needs no rule
     * here — the marker is not in the pair table, so the generic path
     * inserts it plainly.
     */
    private boolean markerOverride(Editor editor, String text, int offset, int end, char c) {
        if (!editor.getBuffer().getBooleanProperty(Property.ELECTRIC_PAIRS_TYPE_OVER))
            return false;
        if (offset < end && text.charAt(offset) == c) {
            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().skip(1);
            return true;
        }
        return false;
    }

    /**
     * The first content character after an unmatched opener run
     * generates the closer: {@code **|} + {@code b} → {@code **b|**}.
     * The opener run is the run of the marker just before the caret;
     * its role is decided by the shared delimiter analysis with the
     * prospective content in place. A space is not content, so
     * {@code ** } generates nothing.
     */
    private boolean contentOverride(Editor editor, String text, int offset, int end, char c) {
        if (offset == 0 || Character.isWhitespace(c))
            return false;
        final char marker = text.charAt(offset - 1);
        final int run = MarkdownDelimiters.unmatchedOpenerRun(text, offset, marker, end, c);
        if (run == 0)
            return false;
        final CompoundEdit compoundEdit = editor.beginCompoundEdit();
        try {
            editor.insertChar(c);
            // The caret is just after the content char. The closer goes
            // there too; insertChars moves its own position copy, not the
            // editor's dot, so the caret stays between the content and
            // its closer.
            editor.getBuffer().insertChars(new Position(editor.getDot()), repeat(marker, run));
        }
        finally {
            editor.endCompoundEdit(compoundEdit);
        }
        return true;
    }

    private static String repeat(char c, int n) {
        final StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++)
            sb.append(c);
        return sb.toString();
    }

    private static void buffer_insertString(Editor editor, Position pos, String s) {
        editor.getBuffer().insertString(pos, s);
    }

    private static void buffer_insertChars(Editor editor, Position pos, String s) {
        editor.getBuffer().insertChars(pos, s);
    }

    @Override
    public boolean isTaggable() {
        return true;
    }

    @Override
    public Tagger getTagger(SystemBuffer buffer) {
        return new MarkdownTagger(buffer);
    }

    /** The headings, as an outline. */
    @Override
    public NavigationComponent getSidebarComponent(Editor editor) {
        final View view = editor.getCurrentView();
        if (view == null)
            return null; // Shouldn't happen.
        if (!(view.getSidebarComponent() instanceof SidebarTagTree))
            view.setSidebarComponent(
                new SidebarTagTree(editor, tag -> tag instanceof MarkdownTag markdownTag ? markdownTag.getLevel() : 1));
        return view.getSidebarComponent();
    }

    /** The headings down to the caret's: "Syntaxes › Markdown › Tasks". */
    @Override
    public String getContextString(Editor editor, boolean verbose) {
        return super.getContextString(editor, true);
    }

    /** The link at pos: inline, reference, autolink or bare URL. */
    @Override
    public TextLink getLinkAt(Editor editor, Position pos) {
        return MarkdownLinks.find(editor.getBuffer(), pos.getLine(), pos.getOffset());
    }

    /** A fence's code, a list item's children, or a heading's section. */
    @Override
    public Line[] getFoldRange(Editor editor, Line line) {
        return MarkdownFolding.getFoldRange(editor.getBuffer(), line);
    }

    /** A fence's first line, a list item with children, or a heading over a section. */
    @Override
    public boolean isFoldStart(Buffer buffer, Line line) {
        return MarkdownFolding.headedBy(buffer, line) != null;
    }

    @Override
    public Line[] getFoldBelow(Buffer buffer, Line line) {
        return MarkdownFolding.headedBy(buffer, line);
    }

    /** All but the headings. */
    @Override
    public void foldAll(Editor editor) {
        MarkdownFolding.foldHeadings(editor, 6);
    }

    @Override
    public String getCommentStart() {
        return "<!-- ";
    }

    @Override
    public String getCommentEnd() {
        return " -->";
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        km.mapKey(KeyEvent.VK_F12, CTRL_MASK | SHIFT_MASK, "wrapParagraphsInRegion");
        // Lists: see MarkdownLists.
        km.mapKey(KeyEvent.VK_TAB, 0, "markdownTab");
        km.mapKey(KeyEvent.VK_TAB, SHIFT_MASK, "markdownShiftTab");
        km.mapKey(KeyEvent.VK_TAB, CTRL_MASK, "insertTab");
        km.mapKey(KeyEvent.VK_ENTER, 0, "markdownNewline");
        km.mapKey(KeyEvent.VK_BACK_SPACE, 0, "markdownBackspace");
        km.mapKey(KeyEvent.VK_ENTER, CTRL_MASK, "followLinkOrTask");
        // Tasks alone, VS Code's Markdown All in One's key and one beside
        // Ctrl+Enter's.
        km.mapKey(KeyEvent.VK_ENTER, ALT_MASK, "task");
        km.mapKey(KeyEvent.VK_C, ALT_MASK, "task");
        km.mapKey(KeyEvent.VK_ENTER, CTRL_MASK | SHIFT_MASK, "task cancel");
    }
}
