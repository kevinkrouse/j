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
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.Mode;
import org.armedbear.j.NavigationComponent;
import org.armedbear.j.Position;
import org.armedbear.j.Property;
import org.armedbear.j.SidebarTagTree;
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
