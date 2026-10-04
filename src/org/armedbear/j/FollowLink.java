/*
 * FollowLink.java
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

package org.armedbear.j;

import java.awt.AWTEvent;
import java.awt.event.MouseEvent;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.armedbear.j.mode.web.WebBuffer;
import org.armedbear.j.util.Utilities;

/**
 * followLink: goes where the link at the caret points. The mode says what
 * the link is (Mode.getLinkAt): by default a URL, in Markdown its links too.
 *
 * <ul>
 * <li>"#anchor": in this buffer, the tag the anchor names
 *     (LocalTag.isNamedBy): a Markdown heading, a Java member;
 * <li>a path, relative to the buffer's directory or absolute, with an
 *     anchor or not: opened, and at the anchor;
 * <li>"file#L42": opened at line 42;
 * <li>http:, https:, mailto: and the like: in the browser preference's
 *     browser, the desktop's if it is unset (BrowseFile.openUrl).
 * </ul>
 *
 * A jump within j is recorded first, so jumpBack, vim's Ctrl-O, returns.
 */
public final class FollowLink {
    /**
     * An autolink, CommonMark's: a URL, group 1, or an email address,
     * group 2, between angle brackets. What is highlighted as one and what
     * is followed as one are the same.
     */
    public static final Pattern AUTOLINK = Pattern.compile(
        "<(?:([a-zA-Z][a-zA-Z0-9+.-]{1,31}:[^\\s<>]*)|([^\\s<>@]+@[^\\s<>]+))>"
    );

    /** A URL in text, without punctuation that ends a sentence after it. */
    public static final Pattern BARE_URL = Pattern.compile(
        "(?:https?://|ftp://|file:/|mailto:)[^\\s<>()\\[\\]]*[^\\s<>()\\[\\].,;:!?'\"]"
    );
    // A scheme, but not a Windows drive letter.
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]+:");
    private static final Pattern LINE_ANCHOR = Pattern.compile("L(\\d+)(?:-L?\\d+)?");

    /** Opens a URL outside j. Tests replace it, to open nothing. */
    static Consumer<String> browser = BrowseFile::openUrl;

    /**
     * Shows another buffer in the editor, loading it. Tests, which have no
     * frame to activate a buffer in, replace it.
     */
    static BiConsumer<Editor, Buffer> switcher = (editor, buffer) -> {
        editor.makeNext(buffer);
        editor.activate(buffer);
    };

    private FollowLink() {}

    /**
     * The URL at offset in text, an autolink's without its brackets, or a
     * bare one; or null.
     */
    public static TextLink urlAt(String text, int offset) {
        Matcher m = AUTOLINK.matcher(text);
        while (m.find())
            if (m.start() <= offset && offset < m.end())
                return new TextLink(
                    m.group(1) != null
                        ? m.group(1)
                        : "mailto:" + m.group(2),
                    m.start(),
                    m.end()
                );
        m = BARE_URL.matcher(text);
        while (m.find())
            if (startsWord(text, m.start()) && m.start() <= offset && offset < m.end())
                return new TextLink(m.group(), m.start(), m.end());
        return null;
    }

    /** Whether a bare URL may start at i: not inside a word. */
    public static boolean startsWord(String text, int i) {
        return i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
    }

    /**
     * A target for followLink that is a file, at an anchor or not: its path
     * with the '%' and '#' a path may have escaped, as follow reads them.
     */
    public static String fileTarget(File file, String anchor) {
        final String path = file.canonicalPath().replace("%", "%25").replace("#", "%23");
        return anchor != null ? path + "#" + anchor : path;
    }

    /**
     * The identifier at pos as a link to its definition, if the buffer's
     * mode is taggable and a tag for it is somewhere other than here: in
     * the buffer, or the tag files of its directory and tag path. Not the
     * declaration itself, which would only go to where it is.
     */
    public static TextLink definitionAt(Editor editor, Position pos) {
        final Buffer buffer = editor.getBuffer();
        final Mode mode = buffer.getMode();
        if (mode == null || !buffer.isTaggable())
            return null;
        final Line line = pos.getLine();
        final int offset = pos.getOffset();
        if (offset >= line.length() || !mode.isIdentifierPart(line.charAt(offset)))
            return null;
        final String name = mode.getIdentifier(line, offset);
        final Position start = mode.findIdentifierStart(line, offset);
        if (name == null || start == null)
            return null;
        final java.util.List<? extends Tag> tags =
            TagCommands.findMatchingTags(buffer, new Expression(name));
        if (tags == null)
            return null;
        for (Tag tag : tags) {
            if (!(tag instanceof LocalTag) || ((LocalTag) tag).getLine() != line)
                return TextLink.definition(
                    name,
                    start.getOffset(),
                    start.getOffset() + name.length()
                );
        }
        return null;
    }

    /** Whether there is a link at the caret. */
    public static boolean hasLinkAt(Editor editor) {
        return editor.getDot() != null
            && editor.getMode().getLinkAt(editor, editor.getDot()) != null;
    }

    public static void followLink() {
        final Editor editor = Editor.currentEditor();
        // j's own web browser has links of its own.
        if (editor.getBuffer() instanceof WebBuffer) {
            WebBuffer.followLink();
            return;
        }
        // Clicked rather than typed: where the click was.
        final AWTEvent event = editor.getDispatcher().getLastEvent();
        if (event instanceof MouseEvent)
            editor.mouseMoveDotToPoint((MouseEvent) event);
        if (editor.getDot() == null)
            return;
        final TextLink link = editor.getMode().getLinkAt(editor, editor.getDot());
        if (link == null)
            editor.status("No link here");
        else if (link.getTarget() == null)
            editor.status(link.getProblem());
        else if (link.isDefinition())
            TagCommands.findDefinitionAtDot(editor);
        else
            follow(editor, link.getTarget());
    }

    /** Goes where target points, from editor's buffer. */
    public static void follow(Editor editor, String target) {
        target = target.trim();
        if (SCHEME.matcher(target).find() && !target.startsWith("file:")) {
            browser.accept(target);
            editor.status("Opening " + target);
            return;
        }
        if (target.startsWith("file:"))
            target = target.replaceFirst("^file:(//)?", "");
        final int hash = target.indexOf('#');
        final String path = decode(hash < 0 ? target : target.substring(0, hash));
        final String anchor = hash < 0 ? null : decode(target.substring(hash + 1));

        Buffer buffer = editor.getBuffer();
        if (!path.isEmpty()) {
            final File file = resolve(editor, path);
            if (file == null || !file.exists()) {
                editor.status("No such file: " + path);
                return;
            }
            buffer = Editor.getBuffer(file);
            if (buffer == null) {
                editor.status("Can't open " + path);
                return;
            }
        }
        editor.recordJump();
        if (buffer != editor.getBuffer()) {
            // Activating it loads it, and only then does it have a mode to
            // find an anchor with.
            switcher.accept(editor, buffer);
        }
        if (anchor != null && !anchor.isEmpty()) {
            final Line line = findLine(buffer, anchor);
            if (line == null) {
                editor.status("No #" + anchor + " in " + buffer.getFileNameForDisplay());
                return;
            }
            if (line.isHidden())
                editor.unfold(line);
            editor.moveDotTo(new Position(line, 0));
            TagCommands.centerTag(editor);
        }
        editor.updateDisplay();
    }

    // The line an anchor names: "L42", or the first of the buffer's tags it
    // names.
    private static Line findLine(Buffer buffer, String anchor) {
        if (buffer.needsParsing())
            buffer.getFormatter().parseBuffer();
        final Matcher m = LINE_ANCHOR.matcher(anchor);
        if (m.matches()) {
            final Line line = buffer.getLine(Integer.parseInt(m.group(1)) - 1);
            if (line != null)
                return line;
        }
        final java.util.List<LocalTag> tags = buffer.getTags(true);
        if (tags != null)
            for (LocalTag tag : tags)
                if (tag.isNamedBy(anchor))
                    return tag.getLine();
        return null;
    }

    private static File resolve(Editor editor, String path) {
        if (Utilities.isFilenameAbsolute(path) || path.startsWith("~"))
            return File.getInstance(path);
        final File file = editor.getBuffer().getFile();
        final File dir = file != null
            ? file.getParentFile()
            : editor.getCurrentDirectory();
        return dir != null ? File.getInstance(dir, path) : null;
    }

    // "my%20notes.md" as it is named; a '+' stays a '+'.
    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException e) {
            return s;
        }
    }
}
