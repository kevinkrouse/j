/*
 * XmlMode.java
 *
 * Copyright (C) 1998-2004 Peter Graves
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

package org.armedbear.j.mode.xml;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Debug;
import org.armedbear.j.EditCommands;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Formatter;
import org.armedbear.j.History;
import org.armedbear.j.IndentCommands;
import org.armedbear.j.InputDialog;
import org.armedbear.j.InsertTagDialog;
import org.armedbear.j.KeyMap;
import org.armedbear.j.KillRing;
import org.armedbear.j.Line;
import org.armedbear.j.Log;
import org.armedbear.j.Menu;
import org.armedbear.j.MessageDialog;
import org.armedbear.j.Mode;
import org.armedbear.j.NavigationComponent;
import org.armedbear.j.PairMatcher;
import org.armedbear.j.Position;
import org.armedbear.j.Property;
import org.armedbear.j.Sidebar;
import org.armedbear.j.SimpleEdit;
import org.armedbear.j.View;
import org.armedbear.j.XmlParserImpl;
import org.armedbear.j.util.Utilities;
import org.xml.sax.SAXParseException;

public final class XmlMode extends AbstractMode implements Mode {
    private static final String COMMENT_START = XmlPairMatcher.COMMENT_START;
    private static final String COMMENT_END = XmlPairMatcher.COMMENT_END;
    private static final String CDATA_START = XmlPairMatcher.CDATA_START;
    private static final String CDATA_END = XmlPairMatcher.CDATA_END;

    private static final XmlMode mode = new XmlMode();

    private static XmlErrorBuffer errorBuffer;

    private static Pattern tagNameRE;
    private static Pattern attributeNameRE;
    private static Pattern quotedValueRE;
    private static Pattern unquotedValueRE;

    private XmlMode() {
        super(XML_MODE, XML_MODE_NAME);
        setProperty(Property.INDENT_SIZE, 2);
    }

    public static final XmlMode getMode() {
        return mode;
    }

    public static final XmlErrorBuffer getErrorBuffer() {
        return errorBuffer;
    }

    @Override
    public NavigationComponent getSidebarComponent(Editor editor) {
        Debug.assertTrue(editor.getBuffer().getMode() == getMode());
        if (!editor.getBuffer().getBooleanProperty(Property.ENABLE_TREE))
            return null;
        View view = editor.getCurrentView();
        if (view == null)
            return null; // Shouldn't happen.
        if (view.getSidebarComponent() == null)
            view.setSidebarComponent(new XmlTree(editor, null));
        return view.getSidebarComponent();
    }

    @Override
    public String getCommentStart() {
        return COMMENT_START;
    }

    @Override
    public String getCommentEnd() {
        return COMMENT_END;
    }

    @Override
    public Formatter getFormatter(Buffer buffer) {
        return new XmlFormatter(buffer);
    }

    @Override
    public PairMatcher getPairMatcher() {
        return XmlPairMatcher.XML;
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        km.mapKey(KeyEvent.VK_TAB, 0, "tab");
        km.mapKey(KeyEvent.VK_TAB, CTRL_MASK, "insertTab");
        km.mapKey(KeyEvent.VK_ENTER, 0, "newlineAndIndent");
        km.mapKey(KeyEvent.VK_ENTER, CTRL_MASK, "newline");
        km.mapKey('=', "xmlElectricEquals");
        km.mapKey('>', "electricCloseAngleBracket");
        km.mapKey(KeyEvent.VK_E, CTRL_MASK, "xmlInsertMatchingEndTag");
        km.mapKey('/', "xmlElectricSlash");
        km.mapKey(KeyEvent.VK_I, ALT_MASK, "cycleIndentSize");
        km.mapKey(KeyEvent.VK_COMMA, CTRL_MASK | SHIFT_MASK, "xmlInsertTag");
        km.mapKey(KeyEvent.VK_PERIOD, CTRL_MASK | SHIFT_MASK, "xmlInsertEmptyElementTag");
        km.mapKey(KeyEvent.VK_EQUALS, CTRL_MASK, "xmlFindCurrentNode");
        km.mapKey(KeyEvent.VK_OPEN_BRACKET, CTRL_MASK, "fold");
        km.mapKey(KeyEvent.VK_CLOSE_BRACKET, CTRL_MASK, "unfold");

        // build.xml
        km.mapKey(KeyEvent.VK_F9, 0, "compile");
        km.mapKey(KeyEvent.VK_F9, CTRL_MASK, "recompile");
    }

    @Override
    public void populateModeMenu(Editor editor, Menu menu) {
        menu.add(editor, "Insert Element", 'I', "xmlInsertTag");
        menu.add(editor, "End Current Element", 'E', "xmlInsertMatchingEndTag");
        menu.addSeparator();
        menu.add(editor, "Parse Buffer", 'P', "xmlParseBuffer");
        menu.add(editor, "Validate Buffer", 'V', "xmlValidateBuffer");
        boolean enabled = errorBuffer != null;
        menu.addSeparator();
        menu.add(editor, "Next Error", 'N', "nextError", enabled);
        menu.add(editor, "Previous Error", 'R', "previousError", enabled);
        menu.add(editor, "Show Error Message", 'M', "showMessage", enabled);
    }

    @Override
    public void loadFile(Buffer buffer, File file) {
        String encoding = null;
        try {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
            String s = reader.readLine();
            reader.close();
            if (s != null && s.toLowerCase(Locale.ROOT).startsWith("<?xml")) {
                int end = s.indexOf("?>");
                if (end >= 0) {
                    s = s.substring(5, end);
                    Pattern re = Pattern.compile("encoding[ \t]*=[ \t]*");
                    Matcher match = re.matcher(s);
                    if (match.find()) {
                        // First char after match will be single or double
                        // quote.
                        s = s.substring(match.end());
                        if (s.length() > 0) {
                            char quoteChar = s.charAt(0);
                            // Find matching quote char.
                            end = s.indexOf(quoteChar, 1);
                            if (end >= 0) {
                                encoding = s.substring(1, end);
                                if (Utilities.isSupportedEncoding(encoding))
                                    file.setEncoding(encoding);
                                else
                                    Log.error("unsupported encoding \"" + encoding + '"');
                            }
                        }
                    }
                }
            }
        }
        catch (IOException e) {
            Log.error(e);
        }
        if (encoding == null)
            encoding = "UTF8"; // Default for XML.
        try {
            buffer.load(file.getInputStream(), file.getEncoding());
        }
        catch (IOException e) {
            Log.error(e);
        }
    }

    @Override
    public boolean canIndent() {
        return true;
    }

    @Override
    public int getCorrectIndentation(Line line, Buffer buffer) {
        if (line.flags() == STATE_TAG || line.flags() == XmlFormatter.STATE_ATTRIBUTE)
            return getAttributeIndentation(line, buffer);
        final Line model = getModel(line);
        if (model == null)
            return 0;
        int indent = buffer.getIndentation(model);
        if (line.flags() == STATE_QUOTE && model.flags() != STATE_QUOTE)
            return indent + buffer.getIndentSize();
        final String text = line.trim();
        if (text.startsWith("</")) {
            Position pos = findMatchingStartTag(line);
            if (pos != null)
                return buffer.getIndentation(pos.getLine());
            indent -= buffer.getIndentSize();
            return indent < 0 ? 0 : indent;
        }
        if (isInTagState(model.flags()))
            return getIndentationAfterTag(model, buffer, indent);
        final String modelText = model.trim();
        if (modelText.startsWith("<") && !modelText.startsWith("</") && !modelText.startsWith("<!")) {
            String tag = getTag(modelText);
            if (isEmptyElementTag(tag))
                return indent;
            if (isProcessingInstruction(tag))
                return indent;
            // Model starts with start tag.
            String tagName = Utilities.getTagName(modelText);
            String startTag = "<" + tagName;
            String endTag = "</" + tagName;
            int count = 1;
            final int limit = modelText.length();
            for (int i = startTag.length(); i < limit; i++) {
                if (modelText.charAt(i) == '<') {
                    if (lookingAt(modelText, i, endTag)) {
                        int end = i + endTag.length();
                        if (end < limit) {
                            char c = modelText.charAt(end);
                            if (c == ' ' || c == '\t' || c == '>')
                                --count;
                        }
                    } else if (lookingAt(modelText, i, startTag)) {
                        int end = i + startTag.length();
                        if (end < limit) {
                            char c = modelText.charAt(end);
                            if (c == ' ' || c == '\t' || c == '>')
                                ++count;
                        }
                    }
                }
            }
            if (count > 0)
                indent += buffer.getIndentSize();
        } else if (modelText.endsWith("/>")) {
            Position pos = new Position(model, model.length());
            while (pos.prev()) {
                if (pos.getChar() == '<')
                    break;
            }
            indent = buffer.getIndentation(pos.getLine());
        }
        return indent;
    }

    /**
     * A line inside a start tag, indented by splitAttributes as an attribute
     * is: Enter before the {@code >} of {@code <a x="1">} makes room for
     * another one.
     */
    private static int getAttributeIndentation(Line line, Buffer buffer) {
        final Position start = findTagStart(line);
        if (start == null)
            return 0;
        final Line tagLine = start.getLine();
        final int tagIndent = buffer.getIndentation(tagLine);
        if (SplitAttributes.of(buffer).isAligned()) {
            final int offset = firstAttributeOffset(tagLine, start.getOffset());
            if (offset >= 0)
                return buffer.getCol(tagLine, offset);
        }
        return tagIndent + buffer.getIntegerProperty(Property.SPLIT_ATTRIBUTES_INDENT_SIZE) * buffer.getIndentSize();
    }

    /**
     * The indentation after model, the last line of a start tag that began
     * on an earlier line: the tag's own, one level in if it opened an
     * element that is still open.
     */
    private static int getIndentationAfterTag(Line model, Buffer buffer, int indent) {
        final Position start = findTagStart(model);
        if (start == null)
            return indent;
        final int tagIndent = buffer.getIndentation(start.getLine());
        final Position end = start.copy();
        final String tag = getTag(end);
        if (tag.startsWith("</")
                || tag.startsWith("<!")
                || !tag.endsWith(">")
                || isEmptyElementTag(tag)
                || isProcessingInstruction(tag))
            return tagIndent;
        // Closed again on the same line?
        if (end.getLine() == model && model.substring(end.getOffset()).contains("</" + Utilities.getTagName(tag)))
            return tagIndent;
        return tagIndent + buffer.getIndentSize();
    }

    // Inside a tag, a quoted value included.
    private static boolean isInTagState(int flags) {
        return flags == STATE_TAG
                || flags == XmlFormatter.STATE_ATTRIBUTE
                || flags == STATE_QUOTE
                || flags == STATE_SINGLEQUOTE;
    }

    // The '<' of the tag the line starts inside. No attribute value holds a '<'.
    private static Position findTagStart(Line line) {
        final Position pos = new Position(line, 0);
        while (pos.prev()) {
            if (pos.getChar() == '<')
                return pos;
        }
        return null;
    }

    // Where the first attribute of the tag at lt starts on its line, or -1.
    private static int firstAttributeOffset(Line line, int lt) {
        final int limit = line.length();
        int i = lt + 1;
        while (i < limit && line.charAt(i) > ' ' && line.charAt(i) != '>' && line.charAt(i) != '/')
            ++i;
        while (i < limit && line.charAt(i) <= ' ')
            ++i;
        if (i == limit || line.charAt(i) == '>' || line.charAt(i) == '/')
            return -1;
        return i;
    }

    // Line must start with an end tag.
    private static Position findMatchingStartTag(Line line) {
        final String s = line.trim();
        if (!s.startsWith("</"))
            return null;
        final StringBuilder sb = new StringBuilder();
        for (int i = 2; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c <= ' ' || c == '>')
                break;
            sb.append(c);
        }
        return XmlPairMatcher.XML.findStartTag(sb.toString(), new Position(line, line.getText().indexOf("</")));
    }

    private static String getUnmatchedStartTag(Position start) {
        Position pos = start.copy();
        if (isInComment(pos))
            return null;
        if (isInTag(pos))
            return null;
        if (isInCDataSection(pos))
            return null;
        int count = 1;
        if (!pos.lookingAt("<") || pos.prev()) {
            do {
                if (pos.lookingAt(COMMENT_END)) {
                    do {
                        pos.prev();
                    } while (!pos.atStart() && !pos.lookingAt(COMMENT_START));
                } else if (pos.lookingAt(CDATA_END)) {
                    do {
                        pos.prev();
                    } while (!pos.atStart() && !pos.lookingAt(CDATA_START));
                } else if (pos.getChar() == '<') {
                    if (pos.lookingAt("</"))
                        ++count;
                    else if (pos.lookingAt("<?"))
                        ;
                    else {
                        // getTag() skips past the tag, so use pos.copy() here
                        // since we're moving backwards not forwards.
                        String tag = getTag(pos.copy());
                        if (!tag.endsWith("/>")) {
                            --count;
                            if (count == 0)
                                return tag;
                        }
                    }
                }
            } while (pos.prev());
        }
        // Not found.
        return null;
    }

    private static boolean isInTag(Position position) {
        Position pos = position.copy();
        while (pos.prev()) {
            if (pos.lookingAt(COMMENT_END)) {
                do {
                    pos.prev();
                } while (!pos.atStart() && !pos.lookingAt(COMMENT_START));
                continue;
            }
            char c = pos.getChar();
            if (c == '<')
                return true;
            else if (c == '>')
                return false;
        }
        return false;
    }

    private static boolean isInComment(Position position) {
        return XmlPairMatcher.XML.isInComment(position);
    }

    private static boolean isInCDataSection(Position position) {
        return XmlPairMatcher.XML.isInCDataSection(position);
    }

    private static String getTag(String s) {
        if (s == null || s.length() == 0 || s.charAt(0) != '<')
            return null;
        StringBuilder sb = new StringBuilder();
        final int limit = s.length();
        char quoteChar = 0;
        for (int i = 0; i < limit; i++) {
            char c = s.charAt(i);
            sb.append(c);
            if (quoteChar != 0) {
                // We're in a quoted section.
                if (c == quoteChar)
                    quoteChar = 0;
            } else {
                // We're not in a quoted section.
                if (c == '\'' || c == '"')
                    quoteChar = c;
                else if (c == '>')
                    break;
            }
        }
        return sb.toString();
    }

    // Advances position to first char past end of tag.
    private static String getTag(Position pos) {
        return XmlPairMatcher.getTag(pos);
    }

    private static boolean isProcessingInstruction(String tag) {
        if (tag.startsWith("<?") && tag.endsWith("?>"))
            return true;
        return false;
    }

    private static boolean isEmptyElementTag(String tag) {
        if (tag == null)
            return false;
        return tag.endsWith("/>");
    }

    private static final boolean lookingAt(String s, int i, String pattern) {
        return s.regionMatches(i, pattern, 0, pattern.length());
    }

    private static Line getModel(Line line) {
        Line model = line;
        while ((model = model.previous()) != null) {
            int flags = model.flags();
            if (flags == STATE_COMMENT || flags == STATE_QUOTE)
                continue;
            else if (model.trim().startsWith(COMMENT_START))
                continue;
            else if (model.isBlank())
                continue;
            else
                break;
        }
        return model;
    }

    @Override
    public char fixCase(Editor editor, char c) {
        if (!Character.isUpperCase(c) && !Character.isLowerCase(c))
            return c;
        final Buffer buffer = editor.getBuffer();
        if (!buffer.getBooleanProperty(Property.FIX_CASE))
            return c;
        if (!initRegExps())
            return c;
        Position pos = findStartOfTag(editor.getDot());
        if (pos != null) {
            int index = pos.getOffset();
            String text = pos.getLine().getText();
            Matcher matcher = tagNameRE.matcher(text);
            if (!matcher.find(index))
                return c;
            if (matcher.end() >= editor.getDotOffset()) {
                // Tag name.
                if (buffer.getBooleanProperty(Property.UPPER_CASE_TAG_NAMES))
                    return Character.toUpperCase(c);
                else
                    return Character.toLowerCase(c);
            }
            while (true) {
                index = matcher.end();
                matcher = attributeNameRE.matcher(text);
                if (!matcher.find(index))
                    return c;
                if (matcher.end() >= editor.getDotOffset()) {
                    // Attribute name.
                    if (buffer.getBooleanProperty(Property.UPPER_CASE_ATTRIBUTE_NAMES))
                        return Character.toUpperCase(c);
                    else
                        return Character.toLowerCase(c);
                }
                index = matcher.end();
                matcher = quotedValueRE.matcher(text);
                if (!matcher.find()) {
                    matcher = unquotedValueRE.matcher(text);
                    if (!matcher.find(index))
                        return c;
                }
                if (matcher.end() >= editor.getDotOffset()) {
                    // Attribute value.
                    return c;
                }
            }
        }
        return c;
    }

    private static boolean checkElectricEquals(Editor editor) {
        Position pos = findStartOfTag(editor.getDot());
        if (pos == null)
            return false;
        char c = editor.getDotChar();
        if (c == '>' || c == '/' || Character.isWhitespace(c))
            return true;
        return false;
    }

    // Scans backward on same line for '<'.
    private static Position findStartOfTag(Position pos) {
        final String text = pos.getLine().getText();
        int offset = pos.getOffset();
        if (offset >= pos.getLine().length())
            offset = pos.getLine().length() - 1;
        else if (text.charAt(offset) == '>')
            --offset;
        while (offset >= 0) {
            char c = text.charAt(offset);
            if (c == '<')
                return new Position(pos.getLine(), offset);
            if (c == '>')
                return null;
            --offset;
        }
        return null;
    }

    private static boolean initRegExps() {
        if (tagNameRE == null) {
            try {
                tagNameRE = Pattern.compile("</?[A-Za-z0-9]*");
                attributeNameRE = Pattern.compile("\\s+[A-Za-z0-9]*");
                quotedValueRE = Pattern.compile("\\s*=\\s*\"[^\"]*");
                unquotedValueRE = Pattern.compile("\\s*=\\s*\\S*");
            }
            catch (PatternSyntaxException e) {
                tagNameRE = null;
                return false;
            }
        }
        return true;
    }

    public static void xmlFindCurrentNode() {
        final Editor editor = Editor.currentEditor();
        if (editor.getModeId() == XML_MODE) {
            final Sidebar sidebar = editor.getSidebar();
            if (sidebar != null) {
                XmlTree tree = (XmlTree) sidebar.getBottomComponent();
                if (tree != null)
                    ensureCurrentNodeIsVisible(editor, tree);
            }
        }
    }

    public static void xmlParseBuffer() {
        final Editor editor = Editor.currentEditor();
        if (editor.getModeId() == XML_MODE) {
            XmlTree tree = null;
            final Sidebar sidebar = editor.getSidebar();
            if (sidebar != null) {
                tree = (XmlTree) sidebar.getBottomComponent();

                // If there's no tree...
                if (tree == null) {
                    sidebar.setUpdateFlag(SIDEBAR_NAVIGATION_COMPONENT_ALL);
                    sidebar.refreshSidebar();
                    tree = (XmlTree) sidebar.getBottomComponent();
                    if (tree != null)
                        ensureCurrentNodeIsVisible(editor, tree);
                    return;
                }
            }
            final Buffer buffer = editor.getBuffer();
            XmlParserImpl parser = new XmlParserImpl(buffer);
            if (parser.initialize()) {
                editor.setWaitCursor();
                try {
                    parser.setReader(new StringReader(buffer.getText()));
                    parser.run();
                }
                catch (OutOfMemoryError e) {
                    outOfMemory();
                    return;
                }
                finally {
                    editor.setDefaultCursor();
                }
                String output = parser.getOutput();
                // Note that with the current implementation, there will always
                // be output...
                if (output != null && output.length() > 0) {
                    if (errorBuffer == null) {
                        errorBuffer = new XmlErrorBuffer(buffer.getFile(), output);
                    } else
                        errorBuffer.recycle(buffer.getFile(), output);
                    editor.displayInOtherWindow(errorBuffer);
                }
                if (parser.getException() == null) {
                    if (tree != null) {
                        TreeModel treeModel = parser.getTreeModel();
                        if (treeModel != null) {
                            tree.setParserClassName(parser.getParserClassName());
                            tree.setModel(treeModel);
                            Debug.assertTrue(tree.getModel() != null);
                            Debug.assertTrue(tree.getModel().getRoot() != null);
                            ensureCurrentNodeIsVisible(editor, tree);
                        }
                    }
                    editor.status("No errors");
                }
            }
        }
    }

    public static void xmlValidateBuffer() {
        final Editor editor = Editor.currentEditor();
        if (editor.getModeId() == XML_MODE) {
            final Buffer buffer = editor.getBuffer();
            XmlParserImpl parser = new XmlParserImpl(buffer);
            if (parser.initialize()) {
                try {
                    editor.setWaitCursor();
                    parser.enableValidation(true);
                    parser.setReader(new StringReader(buffer.getText()));
                    parser.run();
                }
                catch (OutOfMemoryError e) {
                    outOfMemory();
                    return;
                }
                finally {
                    editor.setDefaultCursor();
                }
                String output = parser.getOutput();
                if (output != null && output.length() > 0) {
                    if (errorBuffer == null) {
                        errorBuffer = new XmlErrorBuffer(buffer.getFile(), output);
                    } else
                        errorBuffer.recycle(buffer.getFile(), output);
                    editor.displayInOtherWindow(errorBuffer);
                } else
                    editor.status("No errors");
            }
        }
    }

    private static void outOfMemory() {
        MessageDialog.showMessageDialog("Not enough memory to run parser", "XML Mode");
    }

    public static void xmlFindError(Editor editor, SAXParseException e) {
        Line line = editor.getBuffer().getLine(e.getLineNumber() - 1);
        if (line != null) {
            int offset = e.getColumnNumber() - 1;
            if (offset < 0)
                offset = 0;
            if (offset > line.length())
                offset = line.length();
            if (line != editor.getDotLine() || offset != editor.getDotOffset()) {
                editor.addUndo(SimpleEdit.MOVE);
                editor.updateDotLine();
                editor.setDot(line, offset);
                editor.updateDotLine();
                editor.moveCaretToDotCol();
                editor.updateDisplay();
            }
        }
    }

    public static void ensureCurrentNodeIsVisible(Editor editor, XmlTree tree) {
        if (tree == null)
            return;
        DefaultMutableTreeNode currentNode = tree.getNodeAtPos(editor.getDot());
        if (currentNode != null) {
            tree.scrollPathToVisible(new TreePath(currentNode.getPath()));
            tree.updatePosition();
        }
    }

    public static void copyXPath() {
        final Editor editor = Editor.currentEditor();
        if (editor.getModeId() == XML_MODE) {
            final Sidebar sidebar = editor.getSidebar();
            if (sidebar != null) {
                XmlTree tree = (XmlTree) sidebar.getBottomComponent();
                if (tree != null) {
                    DefaultMutableTreeNode currentNode = tree.getNodeAtPos(editor.getDot());
                    if (currentNode != null) {
                        TreeNode[] array = currentNode.getPath();
                        if (array != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < array.length; i++) {
                                DefaultMutableTreeNode node = (DefaultMutableTreeNode) array[i];
                                XmlTreeElement element = (XmlTreeElement) node.getUserObject();
                                sb.append('/');
                                sb.append(element.getName());
                            }
                            if (sb.length() > 0) {
                                KillRing killRing = Editor.getKillRing();
                                killRing.appendNew(sb.toString());
                                killRing.copyLastKillToSystemClipboard();
                                editor.status("XPath copied to clipboard");
                            }
                        }
                    }
                }
            }
        }
    }

    public static void xmlElectricEquals() {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        boolean ok = false;
        if (editor.getModeId() == XML_MODE) {
            // Attributes always require quotes in XML mode.
            if (checkElectricEquals(editor))
                ok = true;
        }
        if (ok) {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.fillToCaret();
            editor.addUndo(SimpleEdit.INSERT_STRING);
            editor.insertStringInternal("=\"\"");
            editor.addUndo(SimpleEdit.MOVE);
            editor.getDot().moveLeft();
            editor.moveCaretToDotCol();
            editor.endCompoundEdit(compoundEdit);
        } else
            EditCommands.insertNormalChar(editor, '=');
    }

    public static void xmlInsertTag() {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        InsertTagDialog d = new InsertTagDialog(editor);
        editor.centerDialog(d);
        d.setVisible(true);
        _xmlInsertTag(editor, d.getInput());
    }

    public static void xmlInsertTag(String input) {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        _xmlInsertTag(editor, input);
    }

    private static void _xmlInsertTag(Editor editor, String input) {
        if (input != null) {
            final String tagName, extra;
            int index = input.indexOf(' ');
            if (index >= 0) {
                tagName = input.substring(0, index);
                extra = input.substring(index);
            } else {
                tagName = input;
                extra = "";
            }
            // We always want the end tag in XML mode.
            InsertTagDialog.insertTag(editor, tagName, extra, true);
        }
    }

    public static void xmlInsertEmptyElementTag() {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        InputDialog d = new InputDialog(editor, "Tag:", "Insert Empty Element Tag", null);
        d.setHistory(new History("xmlInsertEmptyElementTag"));
        editor.centerDialog(d);
        d.setVisible(true);
        String input = d.getInput();
        if (input == null)
            return;
        String tagName;
        String extra;
        int index = input.indexOf(' ');
        if (index >= 0) {
            tagName = input.substring(0, index);
            extra = input.substring(index);
        } else {
            tagName = input;
            extra = "";
        }
        final Buffer buffer = editor.getBuffer();
        if (buffer.getBooleanProperty(Property.FIX_CASE)) {
            if (buffer.getBooleanProperty(Property.UPPER_CASE_TAG_NAMES))
                tagName = tagName.toUpperCase(Locale.ROOT);
            else
                tagName = tagName.toLowerCase(Locale.ROOT);
        }
        CompoundEdit compoundEdit = editor.beginCompoundEdit();
        editor.fillToCaret();
        final int offset = editor.getDotOffset();
        editor.addUndo(SimpleEdit.INSERT_STRING);
        StringBuilder sb = new StringBuilder();
        sb.append('<');
        sb.append(tagName);
        sb.append(extra);
        sb.append("/>");
        editor.insertStringInternal(sb.toString());
        Editor.updateInAllEditors(editor.getDotLine());
        editor.addUndo(SimpleEdit.MOVE);
        editor.getDot().setOffset(offset + 1 + input.length());
        editor.moveCaretToDotCol();
        editor.endCompoundEdit(compoundEdit);
    }

    /**
     * Lays out the attributes of the start tag at the caret, or of every
     * start tag that begins in the selected lines, by splitAttributes and
     * wrapCol.
     */
    public static void xmlFormatAttributes() {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        final Buffer buffer = editor.getBuffer();
        if (buffer.needsParsing())
            buffer.getFormatter().parseBuffer();
        final CompoundEdit compoundEdit = editor.beginCompoundEdit();
        Position first = null;
        if (editor.getMark() != null) {
            final org.armedbear.j.Region r = new org.armedbear.j.Region(editor);
            final java.util.List<Line> lines = new java.util.ArrayList<>();
            for (Line line = r.getBeginLine(); line != null; line = line.next()) {
                lines.add(line);
                if (line == r.getEndLine())
                    break;
            }
            // Lines last first, so the lines above stay where they were; on a
            // line, tags first first, each laid out where the one before left it.
            for (int k = lines.size() - 1; k >= 0; k--) {
                final Line stop = lines.get(k).next();
                Position from = new Position(lines.get(k), 0);
                Position lineFirst = null;
                Position lt;
                while ((lt = nextTagStart(buffer, from, stop)) != null) {
                    if (lineFirst == null)
                        lineFirst = lt.copy();
                    from = formatAttributes(editor, lt);
                    // A tag that never closes runs on past the line.
                    if (stop != null && !from.isBefore(new Position(stop, 0)))
                        break;
                }
                if (lineFirst != null)
                    first = lineFirst;
            }
        } else {
            first = findTagAt(editor.getDot());
            if (first != null)
                formatAttributes(editor, first.copy());
        }
        editor.setMark(null);
        if (first != null)
            editor.moveDotTo(first);
        editor.endCompoundEdit(compoundEdit);
        if (first == null)
            editor.status("No tag here");
        buffer.repaint();
    }

    // The next '<' from pos, outside comments and CDATA, on a line before stop.
    private static Position nextTagStart(Buffer buffer, Position pos, Line stop) {
        if (buffer.needsParsing())
            buffer.getFormatter().parseBuffer();
        int from = pos.getOffset();
        for (Line line = pos.getLine(); line != null && line != stop; line = line.next(), from = 0) {
            for (int i = line.getText().indexOf('<', from); i >= 0; i = line.getText().indexOf('<', i + 1)) {
                final Position lt = new Position(line, i);
                if (!isInComment(lt) && !isInCDataSection(lt))
                    return lt;
            }
        }
        return null;
    }

    // Lays out the tag at start; returns where it now ends.
    private static Position formatAttributes(Editor editor, Position start) {
        final Buffer buffer = editor.getBuffer();
        final Position end = start.copy();
        final String tag = getTag(end);
        final int tagIndent = buffer.getIndentation(start.getLine());
        final XmlAttributeFormatter.Layout layout = new XmlAttributeFormatter.Layout(SplitAttributes.of(buffer),
                buffer.getCol(start),
                tagIndent + buffer.getIntegerProperty(Property.SPLIT_ATTRIBUTES_INDENT_SIZE) * buffer.getIndentSize(),
                buffer.getIntegerProperty(Property.WRAP_COL),
                col -> buffer.getCorrectIndentationString(col).toString());
        final String formatted = XmlAttributeFormatter.format(tag, layout);
        if (formatted == null || formatted.equals(tag))
            return end;
        editor.deleteRegion(start, end);
        editor.addUndo(SimpleEdit.INSERT_STRING);
        editor.insertStringInternal(formatted);
        return editor.getDot().copy();
    }

    // The tag the caret is in, or else the first one after it on its line.
    private static Position findTagAt(Position dot) {
        if (isInComment(dot) || isInCDataSection(dot))
            return null;
        final Line line = dot.getLine();
        final String before = line.substring(0, dot.getOffset());
        final int lt = before.lastIndexOf('<');
        final int gt = before.lastIndexOf('>');
        if (lt > gt)
            return new Position(line, lt);
        if (gt < 0 && isInTagState(line.flags()))
            return findTagStart(line);
        final int next = line.getText().indexOf('<', dot.getOffset());
        return next >= 0 ? new Position(line, next) : null;
    }

    public static void xmlInsertMatchingEndTag() {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        final Buffer buffer = editor.getBuffer();
        if (buffer.needsRenumbering())
            buffer.renumber();
        if (buffer.needsParsing())
            buffer.getFormatter().parseBuffer();
        final Position dot = editor.getDot();
        String tag = getUnmatchedStartTag(dot);
        if (tag != null) {
            final String name = Utilities.getTagName(tag);
            final String endTag = "</" + name + ">";
            buffer.withWriteLock(() -> {
                CompoundEdit compoundEdit = editor.beginCompoundEdit();
                editor.fillToCaret();
                editor.addUndo(SimpleEdit.INSERT_STRING);
                editor.insertStringInternal(endTag);
                buffer.modified();
                editor.addUndo(SimpleEdit.MOVE);
                editor.moveCaretToDotCol();
                editor.endCompoundEdit(compoundEdit);
            });
        }
    }

    public static void xmlElectricSlash() {
        final Editor editor = Editor.currentEditor();
        if (!editor.checkReadOnly())
            return;
        final Buffer buffer = editor.getBuffer();
        if (buffer.needsRenumbering())
            buffer.renumber();
        final Position dot = editor.getDotCopy();
        final int offset = dot.getOffset();
        if (offset > 0 && dot.getLine().charAt(offset - 1) == '<') {
            dot.setOffset(offset - 1);
            String tag = getUnmatchedStartTag(dot);
            if (tag != null) {
                final String name = Utilities.getTagName(tag);
                final String endTag = "/" + name + ">";
                buffer.withWriteLock(() -> {
                    // We don't need a compound edit here since all the
                    // changes are line edits.
                    editor.fillToCaret();
                    editor.addUndo(SimpleEdit.LINE_EDIT);
                    editor.insertStringInternal(endTag);
                    buffer.modified();
                    editor.moveCaretToDotCol();
                    if (buffer.getBooleanProperty(Property.AUTO_INDENT))
                        IndentCommands.indentLine(editor);
                });
                return;
            }

        }
        // Not electric.
        EditCommands.insertNormalChar(editor, '/');
    }

    // Scan backward for "<!--".

    @Override
    public boolean foldsAtTags() {
        return true;
    }

}
