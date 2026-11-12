/*
 * Mode.java
 *
 * Copyright (C) 1998-2005 Peter Graves
 * Copyright (C) 2012-2026 Kevin Krouse
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

import java.awt.Color;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Optional;
import javax.swing.JPopupMenu;
import org.armedbear.j.transients.Transient;

/**
 * Mode is an interface for defining modes.  A mode is a set of rules that
 * define how the editor behaves.  Different behaviors are desireable when
 * editing different kinds of files.  As an example, a user expects
 * different behavior/functionality when editing a C++ file than what she
 * expects when editing a Lisp file.
 * <p>
 * Users should not implement Mode directly, they should instead extend
 * {@link AbstractMode AbstractMode}.
 *
 * @see AbstractMode
 */
public interface Mode extends Folds {
    /**
     * Returns the unique identifier of this mode.  All identifiers should
     * be defined in <code>Constants</code>.
     *
     * @return  the unique identifier for this mode
     * @see     org.armedbear.j.Constants
     */
    public int getId();

    /**
     * Returns the display name of this mode. All display names should be
     * defined in <code>Constants</code>.
     *
     * @return  the display name of this mode
     * @see     org.armedbear.j.Constants
     */
    public String getDisplayName();

    public Buffer createBuffer(File file);

    /**
     * Returns a <code>Formatter</code> for the given <code>Buffer</code>.
     *
     * @param buffer    the <code>Buffer</code> that is to be formatted
     *                  according to this mode
     * @return          a <code>Formatter</code> for the given mode.
     */
    public Formatter getFormatter(Buffer buffer);

    /**
     * Returns a non-null <code>KeyMap</code> that may define custom
     * <code>KeyMapping</code>s for this Mode.  If there are no custom
     * <code>KeyMapping</code>s for this Mode, then an empty
     * <code>KeyMap</code> is returned.
     *
     * @return  a non-null KeyMap
     * @see     org.armedbear.j.KeyMapping
     */
    public KeyMap getKeyMap();

    /**
     * Runs a vim ex command (:wq, :q!) as this mode means it, as a commit
     * message's :wq commits; false to leave it to vim.
     */
    public default boolean runExCommand(Editor editor, String name, boolean bang) {
        return false;
    }

    /**
     * Keys the mode takes in vim's visual mode, to act on the selection, or
     * null for none. Vim's own keys are asked after.
     */
    public default KeyMap getVisualKeyMap() {
        return null;
    }

    /**
     * Keys that win over vim's normal mode in this mode's buffers, when no
     * command is part-typed: read-only buffers whose commands want keys vim
     * has for editing. Null for none.
     */
    public default KeyMap getModalKeyMap() {
        return null;
    }

    /** The key map of this mode in buffer: its own, unless the mode gives some buffers others, as a pane of a view. */
    public default KeyMap getKeyMap(Buffer buffer) {
        return getKeyMap();
    }

    /** The modal key map of this mode in buffer, as {@link #getKeyMap(Buffer)} is to the key map. */
    public default KeyMap getModalKeyMap(Buffer buffer) {
        return getModalKeyMap();
    }

    /** A word for what a transient buffer in this mode is, as Help or Results. */
    public default String getTransientTag() {
        return "Output";
    }

    /**
     * The lines buffer's changes start on, in order, for nextChange and
     * previousChange to go by, as a diff's hunks; null to go by the change
     * marks.
     */
    public default List<Line> getChangeStarts(Buffer buffer) {
        return null;
    }

    /**
     * Commands to list, with a word for each, in a dimmed line over the
     * text of buffer: { command, what it does }. Null for none.
     */
    public default String[][] getHints(Buffer buffer) {
        return null;
    }

    /**
     * The popup of the keys, arguments and commands of editor's buffer that
     * modePopup opens, or null for none. Its keys should be the buffer's in
     * editor's edit mode.
     */
    public default Transient getTransient(Editor editor) {
        return null;
    }

    /**
     * Returns the file that defines a custom <code>KeyMap</code> for
     * this Mode, if any.
     *
     * @return  the <code>KeyMap</code> file, or <code>null</code> if there is
     *          none.
     * @see     #getKeyMap
     * @see     KeyMap
     * @see     org.armedbear.j.KeyMapping
     */
    public File getKeyMapFile();

    public void useDefaultKeyMap();

    public void deleteKeyMap();

    /**
     * Returns an identifier for the <code>MenuBar</code> that is to
     * be used for this mode.
     * Override this method to return a unique name if you override
     * {@link #createMenuBar(org.armedbear.j.Frame) createMenuBar} to have a
     * custom menu.
     *
     * @return  an identifier for the <code>MenuBar</code> that is returned
     *          by calling <code>createMenuBar</code>.
     */
    public String getMenuName();

    /**
     * Returns the MenuBar that is to be used for this mode.
     * Override to return a custom <code>MenuBar</code> for this mode.
     * If this method is overriden, then you must also override
     * {@link #getMenuName() getMenuName} to return a unique identifier,
     * otherwise the default is fine.
     *
     * @param frame     the frame that the <code>MenuBar</code> will be
     *                  attached to
     * @return          a <code>MenuBar</code> for the specified frame
     */
    public MenuBar createMenuBar(Frame frame);

    /**
     * Populates the given <code>Menu</code> with appropriate menu items.
     * Unless there is a compelling reason not to, classes that override
     * this method should call <code>super.createMenuBar(editor, menu)</code>
     * when they are done putting in their own entries.
     *
     * @param editor    the current <code>Editor</code>.
     * @param menu      the <code>Menu</code> that is to be populated with
     *                  entries for this mode.
     */
    public void populateMenu(Editor editor, Menu menu);

    public void populateModeMenu(Editor editor, Menu menu);

    /**
     * Returns a context menu populated with entries specific to the given
     * mode and the current location in the specified <code>Editor</code>.
     *
     * @param editor    the current <code>Editor</code>
     * @return          a menu filled with context sensitive goodies.
     */
    public JPopupMenu getContextMenu(Editor editor);

    /**
     * Returns a <code>ToolBar</code> for the specified <code>Frame</code>.
     *
     * @param frame     the <code>Frame</code>
     * @return          the <code>ToolBar</code>
     */
    public ToolBar getToolBar(Frame frame);

    /**
     * Returns a navigation component for the specified <code>Editor</code>,
     * or <code>null</code> if there is no navigation component for this mode.
     * <p>
     * The navigation component appears in the lower pane of the sidebar.
     *
     * @param editor    the <code>Editor</code>
     * @return          the navigation component, or <code>null</code>
     */
    public NavigationComponent getSidebarComponent(Editor editor);

    /**
     * Most useful for programming language modes, this method returns
     * either a <code>Tagger</code> for this mode, or <code>null</code>.  If a
     * <code>Tagger</code> is returned, then
     * {@link #isTaggable() isTaggable()} must return <code>true</code>.
     * Otherwise it should return <code>false</code>.
     *
     * @param buffer    the current buffer
     * @return          a <code>Tagger</code> specific for this mode or
     *                  <code>null</code> if a <code>Tagger</code> is not
     *                  applicable.
     */
    public Tagger getTagger(SystemBuffer buffer);

    /**
     * Returns whether or not this mode has a {@link Tagger Tagger}
     * associated with it.  This returns <code>false</code> if
     * {@link #getTagger(SystemBuffer) getTagger()} returns <code>null</code>,
     * and <code>true</code> otherwise.
     *
     * @return  Whether or not this mode is taggable. Which is the same as
     *          whether or not <code>getTagger</code> returns <code>null</code>.
     */
    public boolean isTaggable();

    /**
     * Returns <code>true</code> if the mode's underlying programming language
     * supports qualified names. (For example, Java, C++ and Perl do; C does
     * not.)
     *
     * @return  <code>true</code> if the mode supports qualified names.
     * @since   0.16.1
     */
    public boolean hasQualifiedNames();

    /**
     * Returns <code>true</code> if the string in question is a qualified name
     * in the mode's underlying programming language.
     *
     * @return  <code>true</code> if the string is a qualified name.
     * @since   0.16.1
     */
    public boolean isQualifiedName(String s);

    /**
     * Returns whether or not this mode will potentially perform context
     * sensitive indentation on a given <code>Line</code> in a given
     * <code>Buffer</code>.  This returns <code>true</code> if
     * {@link #getCorrectIndentation(org.armedbear.j.Line, Buffer) getCorrectIndentation()}
     * might return something other than zero, <code>false</code> otherwise.
     *
     * @return  <code>true</code> if this mode supports context-sensitive
     *          indentation.
     *
     */
    public boolean canIndent();

    /**
     * Returns whether or not this mode will potentially perform context
     * sensitive indentation for a paste operation.  This returns
     * <code>true</code> if
     * {@link #getCorrectIndentation(org.armedbear.j.Line, Buffer) getCorrectIndentation()}
     * might return something other than zero, <code>false</code> otherwise.
     *
     * @return  <code>true<code> if this mode supports context-sensitive
     *          indentation of pasted text.
     */
    public boolean canIndentPaste();

    public boolean acceptsLinePaste(Editor editor);

    /**
     * Returns the amount of indentation (in columns) needed to indent the
     * specified <code>Line</code> in the context of the given
     * <code>Buffer</code>.
     * <p>
     * If this mode does not support automatic context-sensitive indentation,
     * that is if {@link #canIndent() canIndent()} returns <code>false</code>,
     * then this method returns zero.
     *
     * @param line      the line to be indented
     * @param buffer    the buffer which contains the line
     * @return          the amount of indentation required, in columns.
     */
    public int getCorrectIndentation(Line line, Buffer buffer);

    /**
     * Returns a <code>SyntaxIterator</code> specific for this mode that
     * is situated at the given <code>Position</code>.
     *
     * @param pos       where to situate the <code>SyntaxIterator</code>.
     * @return          a <code>SyntaxIterator</code> for this mode.
     */
    public SyntaxIterator getSyntaxIterator(Position pos);

    /**
     * The mode's matching pairs, for findMatchingPair, vim's %,
     * highlightMatchingBracket and rainbowDelimiters.
     */
    default PairMatcher getPairMatcher() {
        return BracketPairMatcher.INSTANCE;
    }

    /**
     * The mode's electric pairs: a string of two-character pairs, the
     * opener then its closer. A symmetric delimiter pairs with itself.
     * Typing an opener inserts its closer; typing a closer steps over
     * it. Modes override to drop pairs they don't want, such as the
     * apostrophe in Lisp, where it quotes.
     */
    default String getPairs() {
        return "()[]{}\"\"";
    }

    /** The mode's electric pairs as a lookup table. */
    default PairTable getPairTable() {
        return PairTable.forPairs(getPairs());
    }

    /**
     * Whether this mode formats braces electrically: typing an opening
     * brace on a blank line opens a block, and the brace line is
     * reindented after the character is inserted or typed over. The
     * Java family, Perl and Tcl turn this on; other modes get generic
     * brace pairing without the block formatting or reindentation.
     */
    default boolean electricBraces() {
        return false;
    }

    /**
     * A mode-specific electric insertion, consulted by the
     * {@code insertNormalChar} coordinator before the generic pair
     * decision. The mode performs the whole insertion itself and
     * returns {@code true} to stop the generic path, or {@code false}
     * to let the generic decision proceed. Used where the flat pair
     * table cannot express the behavior — Markdown's fence, which
     * inserts on another line, and its delimiter runs, whose role
     * depends on the surrounding text. The call is made under the
     * buffer's write lock with the caret at the insertion point; the
     * mode owns its compound edit.
     */
    default boolean electricOverride(Editor editor, char c) {
        return false;
    }

    /**
     * The character that escapes the next one in this mode, or 0 if
     * the mode has no escape character. Backslash in C-family and
     * Lisp modes.
     */
    default char getEscapeChar() {
        return '\\';
    }

    /**
     * Returns a string that signifies the start of a comment for the
     * given mode, or <code>null</code> if the concept of comments is
     * not applicable to this mode.
     *
     * @return  the token to start a comment, or <code>null<code> if not
     *          applicable.
     */
    public String getCommentStart();

    /** The comment prefix that wrapping continues a line with, given its trimmed text, or null. */
    default String getWrapCommentStart(String trimmed) {
        return getCommentStart();
    }

    /** The first of prefixes that trimmed starts with, or null; for getWrapCommentStart. */
    static String wrapPrefix(String trimmed, String... prefixes) {
        for (String prefix : prefixes) {
            if (trimmed.startsWith(prefix))
                return prefix;
        }
        return null;
    }

    /** Whether the "indent before '{'" preference applies. */
    default boolean supportsIndentBeforeBrace() {
        return false;
    }

    /**
     * Returns a string that signifies the end of a comment for the
     * given mode, or <code>null</code> if the concept of comments is
     * not applicable to this mode or the comment does not require an
     * end token.
     *
     * @return  the token to end a comment, or <code>null<code> if not
     *          applicable.
     */
    public String getCommentEnd();

    /**
     * Looks in all mode and non-mode specific properties/preferences and
     * returns the value attached to the given key, or the default value
     * given in <code>property</code> if the key is not found.  The property
     * and preferences lists are searched in an manner which gives precedence
     * in the following order: mode specific preferences, mode specific
     * properties, global preferences.
     *
     * @param property  the key to look for with the default value to return
     *                  if the key is not found.
     * @return          the value of the given property, or the default in the
     *                  given <code>Property</code> if the property's key is
     *                  not found.
     */
    public boolean getBooleanProperty(Property property);

    /**
     * Looks in all mode and non-mode specific properties/preferences and
     * returns the value attached to the given key, or the default value
     * given in <code>property</code> if the key is not found.  The property
     * and preferences lists are searched in an manner which gives precedence
     * in the following order: mode specific preferences, mode specific
     * properties, global preferences.
     *
     * @param property  the key to look for with the default value to return
     *                  if the key is not found.
     * @return          the value of the given property, or the default in the
     *                  given <code>Property</code> if the property's key is
     *                  not found.
     */
    public int getIntegerProperty(Property property);

    /**
     * Looks in all mode and non-mode specific properties/preferences and
     * returns the value attached to the given key, or the default value
     * given in <code>property</code> if the key is not found.  The property
     * and preferences lists are searched in an manner which gives precedence
     * in the following order: mode specific preferences, mode specific
     * properties, global preferences.
     *
     * @param property  the key to look for with the default value to return
     *                  if the key is not found.
     * @return          the value of the given property, or the default in the
     *                  given <code>Property</code> if the property's key is
     *                  not found.
     */
    public String getStringProperty(Property property);

    /**
     * Looks in all mode and non-mode specific properties/preferences and
     * returns the value attached to the given key, or the default value
     * given in <code>property</code> if the key is not found.  The property
     * and preferences lists are searched in an manner which gives precedence
     * in the following order: mode specific preferences, mode specific
     * properties, global preferences.
     *
     * @param property  the key to look for with the default value to return
     *                  if the key is not found.
     * @return          the value of the given property, or the default in the
     *                  given <code>Property</code> if the property's key is
     *                  not found.
     */
    public Color getColorProperty(Property property);

    public void setProperty(Property property, String value);

    public void setProperty(Property property, boolean value);

    public void setProperty(Property property, int value);

    /**
     * Returns whether or not this mode is willing to accept the given
     * file name.
     *
     * @param filename  the name of the file that is to be checked for
     *                  validity for this mode.
     * @return          <code>true</code> if the given filename can be handled
     *                  by this mode.
     */
    public boolean accepts(String filename);

    /**
     * Checks the given character against a list of characters that are
     * legal identifier starts.  Some languages have different requirements
     * for the beginning of an identifier than the rest of that identifier.
     * Such a case is Java which allows digits in an identifier name, but
     * does not allow them to be the first character.
     *
     * @param char      the character to be checked for validity.
     * @return          <code>true</code> if <code>c</code> is one of the
     *                  legal identifier start characters for this mode.
     */
    public boolean isIdentifierStart(char c);

    /**
     * Checks the given character against a list of characters that are
     * legal identifier parts (not the first character).  Some languages
     * have different requirements for the beginning of an identifier than
     * the rest of that identifier. Such a case is Java which allows digits
     * in an identifier part, but does not allow a digit to be the first
     * character.
     *
     * @param char      the character to be checked for validity.
     * @return          <code>true</code> if <code>c</code> is one of the
     *                  legal identifier parts for this mode.
     */
    public boolean isIdentifierPart(char c);

    public boolean isDelimited(Position pos, int length);

    /**
     * Returns <code>true</code> if the specified position is inside a quoted
     * string.
     *
     * @param buffer    the <code>Buffer</code> containing the specified
     *                  position
     * @param pos       the position
     * @return          <code>true</code> if <code>pos</code> is inside a
     *                  quoted string.
     */
    public boolean isInQuote(Buffer buffer, Position pos);

    /**
     * Returns <code>true</code> if the specified position is inside a comment.
     *
     * @param buffer    the <code>Buffer</code> containing the specified
     *                  position
     * @param pos       the position
     * @return          <code>true</code> if <code>pos</code> is inside a
     *                  comment.
     */
    public boolean isInComment(Buffer buffer, Position pos);

    /**
     * The lexical context at {@code pos} for pairing purposes: NEUTRAL,
     * STRING, COMMENT or OPAQUE. Consults the buffer's formatter query,
     * which consumes the line prefix up to the offset rather than
     * reading the entering flags or the segment under the caret. Falls
     * back to {@link #isInQuote} and {@link #isInComment} only when the
     * formatter cannot answer.
     *
     * <p>A line in a disabled {@code #if 0} block reads OPAQUE for
     * pairing, since pairing has no business inside inactive code. The
     * formatter's cursor still reports the raw comment and string
     * membership of such a line, so {@link #isInComment} can find a
     * comment inside disabled code for {@code %} matching; the collapse
     * to OPAQUE happens here, at the pairing boundary, not in the cursor.
     *
     * <p>Refreshes a dirty parse first, so a preceding edit cannot
     * leave stale entering context.
     */
    default LexicalContext lexicalContext(Buffer buffer, Position pos) {
        buffer.refreshParse();
        // A disabled line is disabled whole: the #if 0 block is
        // line-based, so no token on it can leave the disabled state.
        // The formatter's cursor reports the raw comment/string
        // membership of the line so isInComment still finds a comment
        // inside disabled code for % matching; for pairing the whole
        // line is OPAQUE, since pairing has no business inside #if 0.
        if (pos.getLine().flags() == Constants.STATE_DISABLED)
            return LexicalContext.OPAQUE;
        return buffer.getFormatter()
                .lexicalContextAt(pos.getLine(), pos.getOffset())
                .orElseGet(
                    () -> isInComment(buffer, pos) ? LexicalContext.COMMENT
                            : isInQuote(buffer, pos) ? LexicalContext.STRING
                            : LexicalContext.NEUTRAL);
    }

    /**
     * The lexical context at every offset from zero to {@code end}
     * inclusive on {@code line}, in one pass where the formatter can
     * manage it. Same contract as {@link #lexicalContext}, including the
     * parse refresh, but the whole line is answered at once so a caller
     * that needs many positions on it pays for one scan instead of one
     * per position.
     *
     * <p>Empty when the formatter cannot answer in bulk. The fallback
     * predicates are O(offset) each, so building the whole array with
     * them would be quadratic; a caller that needs only some offsets
     * asks for them one at a time with {@link #lexicalContext} instead.
     *
     * @return the states indexed by offset, {@code states[end]} the
     *         state at {@code end}; or empty if the formatter cannot
     *         answer the line
     */
    default Optional<LexicalContext[]> lexicalContexts(Buffer buffer, Line line, int end) {
        buffer.refreshParse();
        final int limit = Math.min(end, line.length());
        // Same disabled-line collapse as the point query: the cursor
        // reports raw membership so isInComment can find a comment in
        // disabled code, but the pairing view treats the whole line as
        // OPAQUE so a bracket inside #if 0 never counts as code.
        if (line.flags() == Constants.STATE_DISABLED) {
            final LexicalContext[] opaque = new LexicalContext[limit + 1];
            java.util.Arrays.fill(opaque, LexicalContext.OPAQUE);
            return Optional.of(opaque);
        }
        return buffer.getFormatter().lexicalContexts(line, limit);
    }

    /**
     * Returns whether or not the given <code>Line</code> is a comment.
     *
     * @param line      the <code>Line</code> to check to see if it is a
     *                  comment.
     * @return          <code>true</code> if <code>line</code> qualifies as
     *                  a comment for this mode.
     */
    public boolean isCommentLine(Line line);

    /**
     * The target of the link at <code>pos</code> as it is written: a URL, a
     * path, a path with an anchor ("notes.md#todo"), or an anchor alone
     * ("#todo"), the anchor naming one of the target's tags
     * (LocalTag.isNamedBy); or <code>null</code> if there is none. By
     * default an autolink or a bare URL, or in a taggable mode, an
     * identifier the tags say where it is defined.
     */
    public TextLink getLinkAt(Editor editor, Position pos);

    /**
     * For a mode with sections of its own, as a status buffer's: the next
     * heading after from, or before it, or from if there is none; null to
     * move as vim's ]] does.
     */
    public default Line sectionFrom(Line from, boolean forward) {
        return null;
    }

    /**
     * The filters on the view in editor, each an option or switch of the
     * buffer's popup with a binding, shown as chips in a bar over the
     * window; null for none.
     */
    public default List<? extends Transient.Item> getFilters(Editor editor) {
        return null;
    }

    /** The help F1 opens in this mode's buffers, as Help takes it, or null for the help finder. */
    public default String getHelpTopic() {
        return null;
    }

    /**
     * Examines <code>c</code> and returns the equivalent character but
     * possibly with a different case (upper or lower) as the rules of
     * this mode dictate.  This is primarily used in programming language
     * modes where the language is case-insensitive and the user wants
     * certain identifiers to have a certain casing style (all upper, all
     * lower).
     *
     * @param editor    the <code>Editor</code> for context.
     * @param c         the character in question.
     * @return          the character represented by <code>c</code> as the
     *                  proper case given the context of <code>editor</code>
     *                  and the case rules of this mode.
     */
    public char fixCase(Editor editor, char c);

    /**
     * Returns a string that describes something about the current caret
     * position that is suitable to be displayed in the <code>StatusBar</code>.
     *
     * @param editor    the <code>Editor</code> for context.
     * @param verbose   whether or not to return more information.
     * @return          information relevant to the current caret position.
     */
    public String getContextString(Editor editor, boolean verbose);

    /**
     * Returns a string that describes something about the current mouse
     * position that is suitable to be displayed in the <code>StatusBar</code>,
     * or <code>null</code> if there is none.
     *
     * @param editor    the <code>Editor</code> for context.
     * @return          information relevant to the current mouse position.
     */
    public String getMouseMovedContextString(Editor editor, Position pos);

    /**
     * Returns a string that describes something about the current mouse
     * position that is suitable to be displayed as a tool tip, or
     * <code>null</code> if there is none.
     *
     * @param editor    the <code>Editor</code> for context.
     * @return          information relevant to the current mouse position.
     */
    public String getToolTipText(Editor editor, MouseEvent e);

    /**
     * If this mode needs to do any special processing on a file in order to
     * load it into the buffer, e.g. the file is an archive, that
     * processing is to be done here.  This method is called from the
     * protected {@link Buffer#loadFile(File) loadFile} method in
     * <code>Buffer</code> when loading a file.  It is also called from
     * the {@link Buffer#reload() reload} method in <code>Buffer</code>.
     *
     * @param buffer    the <code>Buffer</code> that the file is to be loaded
     *                  into.
     * @param file      the input source.
     */
    public void loadFile(Buffer buffer, File file);

    /**
     * Allows this mode to confirm the closing of a <code>Buffer</code>, and
     * can cancel it by returning <code>false</code>.  <code>confirmClose</code>
     * allows for this mode to do things such as make sure the user wants to
     * close a <code>Buffer</code> even though it has been modified.
     *
     * @param editor    the <code>Editor</code> for context.
     * @param buffer    the <code>Buffer</code> that is to be closed.
     * @return          <code>true</code> to allow the <code>Buffer</code> to
     *                  close.
     */
    public boolean confirmClose(Editor editor, Buffer buffer);

    /**
     * Checks the given string against a list of keywords for this mode, and
     * returns <code>true</code> if the string is on that list.
     *
     * @param s         the keyword to test.
     * @return          <code>true</code> if <code>s</code> is a valid keyword
     *                  for this mode.
     */
    public boolean isKeyword(String s);

    /**
     * Returns the expression at the current location of the caret in the
     * specified editor.
     *
     * @param editor    the editor in question
     * @param exact     a hint to the implementation: if true, return the
     *                  expression at the exact location of the caret; if
     *                  false, look for a suitable expression on the same line
     * @return          the expression, or <code>null</code> if no expression
     *                  is found.
     * @since           0.16.1
     */
    public Expression getExpressionAtDot(Editor editor, boolean exact);

    /**
     * Returns the identifier at the specified position.
     *
     * @param pos       the position
     * @return          the identifier at the specified position, or
     *                  <code>null</code> if no identifier is found.
     * @since           0.16.1
     */
    public String getIdentifier(Position pos);

    /**
     * Returns the identifier at the specified line and offset.
     *
     * @param line      the line
     * @param offset    the offset
     * @return          the identifier at the specified line and offset, or
     *                  <code>null</code> if no identifier is found.
     * @since           0.16.1
     */
    public String getIdentifier(Line line, int offset);

    /**
     * Returns the position of the start of the identifier at the specified
     * line and offset.
     *
     * @param line      the line
     * @param offset    the offset
     * @return          the position of the start of the identifier at the
     *                  specified line and offset or <code>null</code> if no
     *                  identifier is found.
     * @since           0.16.1
     */
    public Position findIdentifierStart(Line line, int offset);

    /**
     * Replaces syntactic whitespace (quotes and comments) with actual space
     * characters, then returns trimmed string.
     *
     * @param s         the string to be trimmed
     * @return          the trimmed string
     */
    public String trimSyntacticWhitespace(String s);
}
