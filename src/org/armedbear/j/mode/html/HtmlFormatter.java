/*
 * HtmlFormatter.java
 *
 * Copyright (C) 1998-2002 Peter Graves
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

package org.armedbear.j.mode.html;

import static org.armedbear.j.Constants.*;

import java.util.Locale;
import org.armedbear.j.Buffer;
import org.armedbear.j.Escapes;
import org.armedbear.j.Editor;
import org.armedbear.j.FormatTable;
import org.armedbear.j.Formatter;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.Position;
import org.armedbear.j.mode.java.JavaFormatter;
import org.armedbear.j.util.Utilities;

public final class HtmlFormatter extends Formatter {
    // HTML formats must not overlap with Java formats!
    private static final int HTML_FORMAT_FIRST = JavaFormatter.JAVA_FORMAT_LAST + 1;

    private static final int HTML_FORMAT_TEXT = HTML_FORMAT_FIRST;
    private static final int HTML_FORMAT_COMMENT = HTML_FORMAT_FIRST + 1;
    private static final int HTML_FORMAT_TAG = HTML_FORMAT_FIRST + 2;
    private static final int HTML_FORMAT_TAG_IMAGE = HTML_FORMAT_FIRST + 3;
    private static final int HTML_FORMAT_TAG_ANCHOR = HTML_FORMAT_FIRST + 4;
    private static final int HTML_FORMAT_TAG_TABLE = HTML_FORMAT_FIRST + 5;
    private static final int HTML_FORMAT_TAG_TABLE_ROW = HTML_FORMAT_FIRST + 6;
    private static final int HTML_FORMAT_TAG_TABLE_DATA = HTML_FORMAT_FIRST + 7;
    private static final int HTML_FORMAT_SCRIPT = HTML_FORMAT_FIRST + 8;
    private static final int HTML_FORMAT_ESCAPE = HTML_FORMAT_FIRST + 9;
    private static final int HTML_FORMAT_ESCAPE_ERROR = HTML_FORMAT_FIRST + 10;

    // The carried markup state is the low byte. The element kind of an
    // open tag rides in the next byte so a tag that spans lines keeps its
    // color. Two high bits describe the value being built: whether it is
    // a JavaScript event handler (no references) and whether the value
    // is still pending on the next line after a trailing `=`.
    static final int HTML_STATE_MASK = 0xff;
    private static final int HTML_KIND_SHIFT = 8;
    private static final int HTML_EVENT = 0x10000;
    private static final int HTML_PENDING = 0x20000;
    private static final int HTML_CLOSING = 0x40000;

    // Element kinds carried across lines for a tag that does not close on
    // the line it opens.
    private static final int KIND_OTHER = 0;
    private static final int KIND_ANCHOR = 1;
    private static final int KIND_IMAGE = 2;
    private static final int KIND_TABLE = 3;
    private static final int KIND_TABLE_ROW = 4;
    private static final int KIND_TABLE_DATA = 5;
    private static final int KIND_SCRIPT = 6;
    private static final int KIND_STYLE = 7;

    // Within-tag states. A quoted value can span lines, so these are
    // carried; an unquoted value ends at the newline and never is.
    private static final byte STATE_ATTR_DQ = STATE_LAST + 1;
    private static final byte STATE_ATTR_SQ = STATE_LAST + 2;
    private static final byte STATE_ATTR_UNQ = STATE_LAST + 3;
    private static final byte STATE_STYLE = STATE_LAST + 4;

    // The line being formatted. Segments are emitted by offset into it.
    private String text = "";

    // The element kind of the tag currently open and the format its
    // pieces take. Set when the tag opens, or from the carried flags
    // when a tag continues from the previous line.
    private int kind = KIND_OTHER;
    private int tagFormat = HTML_FORMAT_TAG;

    // The value being built inside a tag: where it starts and whether it
    // takes references. An event-handler value does not.
    private int valueStart = 0;
    private boolean valueEligible = false;

    // Whether the tag currently open is a closing tag (`</p>`). Only an
    // opening `<script>` or `<style>` starts raw content; a closing one
    // never does, so a stray `</style>` in text stays text.
    private boolean closing = false;

    private JavaFormatter javaFormatter;

    public HtmlFormatter(Buffer buffer) {
        this.buffer = buffer;
        javaFormatter = new JavaFormatter(buffer, LANGUAGE_JAVASCRIPT);
        // HTML's keywords and colors, even in another mode's buffer, as in
        // Markdown's fenced html.
        javaFormatter.setLanguageMode(HtmlMode.getMode());
    }

    @Override
    public LineSegmentList formatLine(Line line) {
        if (line == null) {
            clearSegmentList();
            addSegment("", HTML_FORMAT_TEXT);
            return segmentList;
        }
        final int flags = line.flags();
        final int state = flags & HTML_STATE_MASK;
        if (state == STATE_SCRIPT || state == STATE_COMMENT) {
            final String trim = line.trim();
            if (trim.startsWith("<!--")) {
                clearSegmentList();
                addSegment(line.getText(), HTML_FORMAT_COMMENT);
                return segmentList;
            }
            if (!trim.regionMatches(true, 0, "</script>", 0, 9))
                return javaFormatter.formatLine(line);
        }
        parseLine(line.getText(), state,
                (flags >> HTML_KIND_SHIFT) & 0xff,
                (flags & HTML_EVENT) != 0,
                (flags & HTML_PENDING) != 0,
                (flags & HTML_CLOSING) != 0);
        return segmentList;
    }

    // Emit [begin, end) in the given format.
    private void emit(int begin, int end, int format) {
        if (end > begin)
            addSegment(text, begin, end, format);
    }

    // A run of character data: base text with references split out.
    private void emitText(int begin, int end) {
        if (end <= begin)
            return;
        if (hasAmpersand(text, begin, end))
            addEscapedSegment(text, begin, end,
                Escapes.scanReferences(text, begin, end, Escapes.Reference.HTML),
                HTML_FORMAT_TEXT, HTML_FORMAT_ESCAPE, HTML_FORMAT_ESCAPE_ERROR);
        else
            addSegment(text, begin, end, HTML_FORMAT_TEXT);
    }

    // A tag's attribute value: the element's own format, with references
    // split out when the value is eligible. Ineligible values (event
    // handlers) stay whole.
    private void emitValue(int begin, int end, boolean eligible) {
        if (end <= begin)
            return;
        if (eligible && hasAmpersand(text, begin, end))
            addEscapedSegment(text, begin, end,
                Escapes.scanReferences(text, begin, end, Escapes.Reference.HTML),
                tagFormat, HTML_FORMAT_ESCAPE, HTML_FORMAT_ESCAPE_ERROR);
        else
            addSegment(text, begin, end, tagFormat);
    }

    private static boolean hasAmpersand(String s, int from, int to) {
        for (int i = from; i < to; i++)
            if (s.charAt(i) == '&')
                return true;
        return false;
    }

    private void parseLine(String lineText, int state, int carriedKind,
                          boolean carriedEvent, boolean carriedPending,
                          boolean carriedClosing) {
        if (Editor.tabsAreVisible())
            text = Utilities.makeTabsVisible(lineText, buffer.getTabWidth());
        else
            text = Utilities.detab(lineText, buffer.getTabWidth());
        clearSegmentList();
        closing = false;
        if (isTagState(state)) {
            kind = carriedKind;
            tagFormat = kindToFormat(kind);
            closing = carriedClosing;
        }
        // runStart is the start of the current run of tag-format text
        // not yet emitted; it flushes at a value boundary or the close.
        int runStart = 0;
        if (state == STATE_ATTR_DQ || state == STATE_ATTR_SQ) {
            // The value opened on a previous line; it starts at 0 here.
            valueStart = 0;
            valueEligible = !carriedEvent;
        }
        boolean pending = carriedPending;
        int i = 0;
        final int limit = text.length();
        while (i < limit) {
            char c = text.charAt(i);
            if (state == STATE_HTML_COMMENT) {
                if (i < limit - 2 && text.substring(i, i + 3).equals("-->")) {
                    emit(runStart, i + 3, HTML_FORMAT_COMMENT);
                    runStart = i + 3;
                    state = STATE_NEUTRAL;
                    i += 3;
                } else {
                    ++i;
                }
                continue;
            }
            if (state == STATE_SCRIPT || state == STATE_STYLE) {
                // Raw content: never reference-split. Ends at the
                // matching close tag, which is emitted as a tag.
                final boolean script = state == STATE_SCRIPT;
                final String closer = script ? "</script>" : "</style>";
                if (c == '<' && text.regionMatches(true, i, closer, 0, closer.length())) {
                    emit(runStart, i, script ? HTML_FORMAT_SCRIPT : HTML_FORMAT_TEXT);
                    emit(i, i + closer.length(), HTML_FORMAT_TAG);
                    runStart = i + closer.length();
                    state = STATE_NEUTRAL;
                    i += closer.length();
                } else {
                    ++i;
                }
                continue;
            }
            if (state == STATE_ATTR_DQ) {
                if (c == '"') {
                    emitValue(valueStart, i, valueEligible);
                    emit(i, i + 1, tagFormat);
                    runStart = i + 1;
                    state = STATE_TAG;
                }
                ++i;
                continue;
            }
            if (state == STATE_ATTR_SQ) {
                if (c == '\'') {
                    emitValue(valueStart, i, valueEligible);
                    emit(i, i + 1, tagFormat);
                    runStart = i + 1;
                    state = STATE_TAG;
                }
                ++i;
                continue;
            }
            if (state == STATE_ATTR_UNQ) {
                if (c == '>' || isWhitespace(c)) {
                    emitValue(valueStart, i, valueEligible);
                    if (c == '>') {
                        emit(i, i + 1, tagFormat);
                        runStart = i + 1;
                        state = closeTag();
                        i += 1;
                    } else {
                        runStart = i;
                        state = STATE_TAG;
                        ++i;
                    }
                    continue;
                }
                ++i;
                continue;
            }
            if (state == STATE_TAG) {
                if (pending) {
                    // The value opens on this line after a trailing `=`.
                    if (isWhitespace(c)) {
                        ++i;
                        continue;
                    }
                    pending = false;
                    valueEligible = !carriedEvent;
                    if (c == '"' || c == '\'') {
                        emit(runStart, i + 1, tagFormat);
                        runStart = i + 1;
                        valueStart = i + 1;
                        state = c == '"' ? STATE_ATTR_DQ : STATE_ATTR_SQ;
                        i += 1;
                    } else {
                        emit(runStart, i, tagFormat);
                        runStart = i;
                        valueStart = i;
                        state = STATE_ATTR_UNQ;
                    }
                    continue;
                }
                if (c == '>' || (c == '/' && i + 1 < limit && text.charAt(i + 1) == '>')) {
                    int closeLen = c == '>' ? 1 : 2;
                    emit(runStart, i + closeLen, tagFormat);
                    runStart = i + closeLen;
                    state = closeTag();
                    i += closeLen;
                    continue;
                }
                if (c == '=') {
                    // The attribute name is the token just before '='.
                    int ts = i;
                    while (ts > runStart && !isWhitespace(text.charAt(ts - 1)))
                        --ts;
                    String attr = text.substring(ts, i).toLowerCase(Locale.ROOT);
                    valueEligible = !attr.startsWith("on");
                    int k = i + 1;
                    while (k < limit && isWhitespace(text.charAt(k)))
                        ++k;
                    if (k >= limit) {
                        // `=` with only whitespace to end of line: the
                        // value is pending on the next line. The event
                        // bit rides in the flags for the next line.
                        emit(runStart, limit, tagFormat);
                        runStart = limit;
                        pending = true;
                        i = limit;
                        continue;
                    }
                    char vc = text.charAt(k);
                    if (vc == '"' || vc == '\'') {
                        // Include the opening quote in the tag run.
                        emit(runStart, k + 1, tagFormat);
                        runStart = k + 1;
                        valueStart = k + 1;
                        state = vc == '"' ? STATE_ATTR_DQ : STATE_ATTR_SQ;
                        i = k + 1;
                    } else {
                        emit(runStart, k, tagFormat);
                        runStart = k;
                        valueStart = k;
                        state = STATE_ATTR_UNQ;
                        i = k;
                    }
                    continue;
                }
                ++i;
                continue;
            }
            // Neutral: character data, until markup.
            if (c == '<') {
                emitText(runStart, i);
                if (text.regionMatches(i, "<!--", 0, 4)) {
                    runStart = i;
                    state = STATE_HTML_COMMENT;
                    i += 4;
                    continue;
                }
                int j = i + 1;
                closing = j < limit && text.charAt(j) == '/';
                if (closing)
                    ++j;
                int nameStart = j;
                while (j < limit && isElementNameChar(text.charAt(j)))
                    ++j;
                kind = elementKind(text, nameStart);
                tagFormat = kindToFormat(kind);
                runStart = i;
                state = STATE_TAG;
                i = j;
                continue;
            }
            ++i;
        }
        endLine(state, runStart, limit, pending);
    }

    // Flush the run open at end of line.
    private void endLine(int state, int runStart, int limit, boolean pending) {
        if (state == STATE_HTML_COMMENT)
            emit(runStart, limit, HTML_FORMAT_COMMENT);
        else if (state == STATE_SCRIPT)
            emit(runStart, limit, HTML_FORMAT_SCRIPT);
        else if (state == STATE_STYLE)
            emit(runStart, limit, HTML_FORMAT_TEXT);
        else if (state == STATE_ATTR_DQ || state == STATE_ATTR_SQ)
            emitValue(valueStart, limit, valueEligible);
        else if (state == STATE_ATTR_UNQ)
            emitValue(valueStart, limit, valueEligible);
        else if (state == STATE_TAG)
            emit(runStart, limit, tagFormat);
        else
            emitText(runStart, limit);
    }

    // The state after a tag closes, from its element kind. Only an
    // opening tag starts raw content; a closing `</script>` does not.
    private int closeTag() {
        if (closing)
            return STATE_NEUTRAL;
        if (kind == KIND_SCRIPT)
            return STATE_SCRIPT;
        if (kind == KIND_STYLE)
            return STATE_STYLE;
        return STATE_NEUTRAL;
    }

    private static boolean isTagState(int state) {
        return state == STATE_TAG || state == STATE_ATTR_DQ || state == STATE_ATTR_SQ
                || state == STATE_ATTR_UNQ;
    }

    private static int kindToFormat(int kind) {
        switch (kind) {
            case KIND_ANCHOR: return HTML_FORMAT_TAG_ANCHOR;
            case KIND_IMAGE: return HTML_FORMAT_TAG_IMAGE;
            case KIND_TABLE: return HTML_FORMAT_TAG_TABLE;
            case KIND_TABLE_ROW: return HTML_FORMAT_TAG_TABLE_ROW;
            case KIND_TABLE_DATA: return HTML_FORMAT_TAG_TABLE_DATA;
            default: return HTML_FORMAT_TAG;
        }
    }

    // Classify the element whose name begins at `nameStart` in `s`.
    private static int elementKind(String s, int nameStart) {
        int j = nameStart;
        while (j < s.length() && isElementNameChar(s.charAt(j)))
            ++j;
        String name = s.substring(nameStart, j).toLowerCase(Locale.ROOT);
        switch (name) {
            case "a": return KIND_ANCHOR;
            case "img": return KIND_IMAGE;
            case "table": return KIND_TABLE;
            case "tr": return KIND_TABLE_ROW;
            case "td": return KIND_TABLE_DATA;
            case "script": return KIND_SCRIPT;
            case "style": return KIND_STYLE;
            default: return KIND_OTHER;
        }
    }

    private static boolean isElementNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r'
                || c == '\f' || Character.isWhitespace(c);
    }

    @Override
    public boolean parseBuffer() {
        Line line = buffer.getFirstLine();
        if (line == null)
            return false;
        Position pos = new Position(line, 0);
        boolean changed = false;
        int state = STATE_NEUTRAL;
        int kind = KIND_OTHER;
        boolean event = false;
        boolean pending = false;
        boolean closing = false;
        while (line != null) {
            // The kind, event, pending and closing bits describe an open
            // tag, so they ride only on tag states. A script, comment or
            // neutral line carries exactly its state, so the
            // `flags() == STATE_X` checks elsewhere keep working.
            int flags = state;
            if (isTagState(state)) {
                flags |= kind << HTML_KIND_SHIFT;
                if (event)
                    flags |= HTML_EVENT;
                if (pending)
                    flags |= HTML_PENDING;
                if (closing)
                    flags |= HTML_CLOSING;
            }
            if (line.flags() != flags) {
                line.setFlags(flags);
                changed = true;
            }
            final int limit = line.length();
            for (int i = 0; i < limit; i++) {
                char c = line.charAt(i);
                if (state == STATE_HTML_COMMENT) {
                    if (c == '-') {
                        pos.moveTo(line, i);
                        if (pos.lookingAt("-->")) {
                            state = STATE_NEUTRAL;
                            i += 2;
                        }
                    }
                    continue;
                }
                if (state == STATE_SCRIPT) {
                    if (c == '<') {
                        pos.moveTo(line, i);
                        if (pos.lookingAtIgnoreCase("</script>")) {
                            state = STATE_NEUTRAL;
                            i += 8;
                        }
                    } else if (c == '/' && i < limit - 1 && line.charAt(i + 1) == '*') {
                        // A JavaScript block comment inside the script.
                        // It can contain `</script>` without closing the
                        // script, so track it as its own state.
                        state = STATE_COMMENT;
                        ++i;
                    }
                    continue;
                }
                if (state == STATE_COMMENT) {
                    if (c == '*' && i < limit - 1 && line.charAt(i + 1) == '/') {
                        state = STATE_SCRIPT;
                        ++i;
                    }
                    continue;
                }
                if (state == STATE_STYLE) {
                    if (c == '<') {
                        pos.moveTo(line, i);
                        if (pos.lookingAtIgnoreCase("</style>")) {
                            state = STATE_NEUTRAL;
                            i += 7;
                        }
                    }
                    continue;
                }
                if (state == STATE_ATTR_DQ) {
                    if (c == '"')
                        state = STATE_TAG;
                    continue;
                }
                if (state == STATE_ATTR_SQ) {
                    if (c == '\'')
                        state = STATE_TAG;
                    continue;
                }
                if (state == STATE_ATTR_UNQ) {
                    if (c == '>' || isWhitespace(c)) {
                        event = false;
                        state = c == '>' ? closeTagOf(kind, closing) : STATE_TAG;
                    }
                    continue;
                }
                if (state == STATE_TAG) {
                    if (pending) {
                        if (isWhitespace(c))
                            continue;
                        pending = false;
                        if (c == '"') { state = STATE_ATTR_DQ; }
                        else if (c == '\'') { state = STATE_ATTR_SQ; }
                        else { state = STATE_ATTR_UNQ; }
                        continue;
                    }
                    if (c == '>' || (c == '/' && i + 1 < limit && line.charAt(i + 1) == '>')) {
                        state = closeTagOf(kind, closing);
                        if (c == '/')
                            i += 1;
                    } else if (c == '=') {
                        int ts = i;
                        while (ts > 0 && !isWhitespace(line.charAt(ts - 1)))
                            --ts;
                        String attr = line.substring(ts, i).toLowerCase(Locale.ROOT);
                        event = attr.startsWith("on");
                        int k = i + 1;
                        while (k < limit && isWhitespace(line.charAt(k)))
                            ++k;
                        if (k >= limit) {
                            pending = true;
                            i = limit;
                            continue;
                        }
                        char vc = line.charAt(k);
                        if (vc == '"') { state = STATE_ATTR_DQ; i = k; }
                        else if (vc == '\'') { state = STATE_ATTR_SQ; i = k; }
                        else { state = STATE_ATTR_UNQ; i = k - 1; }
                    }
                    continue;
                }
                // Neutral.
                if (c == '<') {
                    pos.moveTo(line, i);
                    if (pos.lookingAt("<!--")) {
                        state = STATE_HTML_COMMENT;
                        i += 3;
                        continue;
                    }
                    int j = i + 1;
                    closing = j < limit && line.charAt(j) == '/';
                    if (closing)
                        ++j;
                    int nameStart = j;
                    while (j < limit && isElementNameChar(line.charAt(j)))
                        ++j;
                    kind = elementKind(line.getText(), nameStart);
                    state = STATE_TAG;
                    i = j - 1;
                }
            }
            // An unquoted value ends at the newline, but the tag itself
            // stays open: the next line continues between attributes.
            if (state == STATE_ATTR_UNQ) {
                state = STATE_TAG;
                event = false;
            }
            line = line.next();
        }
        buffer.setNeedsParsing(false);
        return changed;
    }

    private static int closeTagOf(int kind, boolean closing) {
        if (closing)
            return STATE_NEUTRAL;
        if (kind == KIND_SCRIPT)
            return STATE_SCRIPT;
        if (kind == KIND_STYLE)
            return STATE_STYLE;
        return STATE_NEUTRAL;
    }

    @Override
    public FormatTable getFormatTable() {
        if (formatTable == null) {
            // Script blocks are formatted by javaFormatter, so its formats
            // share this table.
            formatTable = javaFormatter.getFormatTable();
            formatTable.setModeName("HtmlMode");
            formatTable.addEntryFromPrefs(HTML_FORMAT_TEXT, "text");
            formatTable.addEntryFromPrefs(HTML_FORMAT_COMMENT, "comment");
            formatTable.addEntryFromPrefs(HTML_FORMAT_TAG, "tag");
            formatTable.addEntryFromPrefs(HTML_FORMAT_TAG_IMAGE, "image");
            formatTable.addEntryFromPrefs(HTML_FORMAT_TAG_ANCHOR, "anchor");
            formatTable.addEntryFromPrefs(HTML_FORMAT_TAG_TABLE, "table");
            formatTable.addEntryFromPrefs(HTML_FORMAT_TAG_TABLE_ROW, "tableRow");
            formatTable.addEntryFromPrefs(HTML_FORMAT_TAG_TABLE_DATA, "tableData");
            formatTable.addEntryFromPrefs(HTML_FORMAT_SCRIPT, "script");
            formatTable.addEntryFromPrefs(HTML_FORMAT_ESCAPE, "escape");
            formatTable.addEntryFromPrefs(HTML_FORMAT_ESCAPE_ERROR, "escapeError");
        }
        return formatTable;
    }

    @Override
    public void reset() {
        javaFormatter.reset();
        super.reset();
    }
}
