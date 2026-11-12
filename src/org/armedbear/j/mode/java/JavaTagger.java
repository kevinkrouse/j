/*
 * JavaTagger.java
 *
 * Copyright (C) 1998-2006 Peter Graves
 * Copyright (C) 2013-2026 Kevin Krouse
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

package org.armedbear.j.mode.java;

import static org.armedbear.j.Constants.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Set;
import org.armedbear.j.Editor;
import org.armedbear.j.Line;
import org.armedbear.j.LocalTag;
import org.armedbear.j.Position;
import org.armedbear.j.Property;
import org.armedbear.j.SystemBuffer;
import org.armedbear.j.Tagger;
import org.armedbear.j.mode.cpp.CppTag;

public class JavaTagger extends Tagger {
    // States.
    private static final int NEUTRAL = 0;
    private static final int INTERFACE_NAME = 1;
    private static final int CLASS_NAME = 2;
    private static final int CLASS_PROLOG = 3; // After name, before opening brace.
    private static final int EXTENDS = 4;
    private static final int IMPLEMENTS = 5;
    private static final int METHOD_NAME = 6;
    private static final int METHOD_PROLOG = 7;
    private static final int STATIC_INITIALIZER = 8;
    private static final int NEW = 9;
    private static final int FIELD_INITIALIZER = 10; // After '=' in a variable declaration.
    private static final int RECORD_NAME = 11; // After the 'record' keyword.
    private static final int RECORD_HEADER = 12; // In the record's "(...)" header.
    private static final int ENUM_NAME = 13; // After the 'enum' keyword.
    private static final int ENUM_OPEN = 14; // After the name, before the body's '{'.
    private static final int ENUM_CONSTANTS = 15; // In the enum's constant list.

    protected Position pos;
    protected String token;
    protected Position tokenStart;

    private ArrayList<LocalTag> tags;
    private JavaClass currentClass;
    private int visibility; // TAG_PUBLIC, TAG_PRIVATE, TAG_PROTECTED
    private Deque<JavaClass> stack;
    private boolean textBlocks; // Java's """ text blocks; not JavaScript's.
    private boolean enumImplements; // An enum's "implements" clause, before its constants.

    // JavaScript, as the mode that made this tagger says: a buffer tagged in
    // the background has no mode to ask.
    private final boolean forJavaScript;

    public JavaTagger(SystemBuffer buffer) {
        this(buffer, false);
    }

    public JavaTagger(SystemBuffer buffer, boolean javaScript) {
        super(buffer);
        forJavaScript = javaScript;
    }

    @Override
    public synchronized void run() {
        pos = new Position(buffer.getFirstLine(), 0);
        token = null;
        tokenStart = null;
        tags = new ArrayList<>();
        currentClass = null;
        visibility = 0;
        final boolean javaScript = forJavaScript || buffer.getModeId() == JAVASCRIPT_MODE;
        textBlocks = !javaScript;
        enumImplements = false;
        stack = new ArrayDeque<>();
        int state = NEUTRAL;
        while (!pos.atEnd()) {
            char c = pos.getChar();
            if (Character.isWhitespace(c)) {
                pos.skipWhitespace();
                continue;
            }
            if (c == '\'' || c == '"') {
                skipQuote();
                continue;
            }
            if (pos.lookingAt("/*")) {
                skipComment(pos);
                continue;
            }
            if (pos.lookingAt("//")) {
                LocalTag tag = checkForExplicitTag(pos, JAVA_MODE);
                if (tag instanceof JavaTag)
                    tags.add(tag);
                skipSingleLineComment(pos);
                continue;
            }
            if (c == '<' && skipGenericIfPresent())
                continue;
            if (state == STATIC_INITIALIZER) {
                if (c == '{') {
                    // A static initializer, not a modifier on a declaration.
                    skipBrace();
                    visibility = 0;
                    state = NEUTRAL;
                    continue;
                }
                // Anything else...
                state = NEUTRAL;
                // Fall through...
            }
            if (state == METHOD_NAME) {
                if (c == '{') {
                    addTag(TAG_METHOD);
                    visibility = 0;
                    skipBrace();
                    state = NEUTRAL;
                    continue;
                }
                if (c == ';') {
                    // Abstract or native method.
                    addTag(TAG_METHOD);
                    visibility = 0;
                    state = NEUTRAL;
                    pos.next();
                    continue;
                }
                if (pos.lookingAt("throws")) {
                    addTag(TAG_METHOD);
                    // Set token to null here so we don't try to add this method again.
                    token = null;
                    visibility = 0;
                    state = METHOD_PROLOG;
                    pos.skip(6); // Skip over "throws".
                    continue;
                }
                state = NEUTRAL;
                // Fall through...
            }
            if (state == CLASS_PROLOG) {
                if (lookingAtWord("extends")) {
                    state = EXTENDS;
                    pos.skip(7); // Skip over "extends".
                    continue;
                }
                if (lookingAtWord("implements")) {
                    state = IMPLEMENTS;
                    pos.skip(10);
                    continue;
                }
                if (c == '{')
                    state = NEUTRAL;
                pos.next();
                continue;
            }
            if (state == RECORD_NAME || state == ENUM_NAME) {
                // 'record' is a contextual keyword: still a legal identifier.
                // Only the next identifier is the type's name; anything else
                // means the word was used as a name, not the keyword.
                if (!Character.isJavaIdentifierStart(c)) {
                    state = NEUTRAL;
                    // Fall through...
                } else if (state == RECORD_NAME) {
                    gatherToken(false);
                    beginType("record ", TAG_CLASS);
                    state = RECORD_HEADER;
                    continue;
                } else {
                    gatherToken(false);
                    beginType("enum ", TAG_CLASS);
                    state = ENUM_OPEN;
                    continue;
                }
            }
            if (state == RECORD_HEADER) {
                // The record's header, "(int x, int y)", is skipped whole so
                // its components don't become fields; then the prolog, for
                // "implements", and the body.
                if (c == '(') {
                    skipParen();
                    state = CLASS_PROLOG;
                    continue;
                }
                // Not the header, as "<T>" spaced from the name: the prolog
                // reaches it and steps over it.
                state = CLASS_PROLOG;
                continue;
            }
            if (state == ENUM_OPEN) {
                // Between the name and the body: "implements Foo" and such.
                if (c == '{') {
                    state = ENUM_CONSTANTS;
                    pos.next();
                    continue;
                }
                if (lookingAtWord("implements")) {
                    // Tagged as a class's are; its '{' opens the constants.
                    state = IMPLEMENTS;
                    enumImplements = true;
                    pos.skip(10);
                    continue;
                }
                pos.next();
                continue;
            }
            if (state == ENUM_CONSTANTS) {
                if (c == '(') {
                    // A constant's arguments: RED(0xff0000).
                    skipParen();
                    continue;
                }
                if (c == '{') {
                    // A constant with a body.
                    skipBrace();
                    continue;
                }
                if (c == ';') {
                    // The constants end; the rest of the body is ordinary.
                    state = NEUTRAL;
                    pos.next();
                    continue;
                }
                if (c == '}') {
                    // The enum ends with no semicolon and no members.
                    if (!stack.isEmpty())
                        currentClass = stack.pop();
                    else
                        currentClass = null;
                    state = NEUTRAL;
                    pos.next();
                    continue;
                }
                if (c == '@') {
                    // A constant's annotation, "@Deprecated A": not a
                    // constant itself. Its arguments are skipped as a
                    // constant's are.
                    pos.next();
                    while (Character.isJavaIdentifierPart(c = pos.getChar()) || c == '.') {
                        if (!pos.next())
                            break;
                    }
                    continue;
                }
                if (Character.isJavaIdentifierStart(c)) {
                    gatherToken(false);
                    addTag(TAG_CONSTANT);
                    continue;
                }
                pos.next();
                continue;
            }
            if (state == IMPLEMENTS) {
                if (c == '{') {
                    state = enumImplements ? ENUM_CONSTANTS : NEUTRAL;
                    enumImplements = false;
                    pos.next();
                    continue;
                }
                if (lookingAtWord("permits")) {
                    // A sealed type's permitted subclasses are not
                    // interfaces it implements; the prolog steps over them.
                    state = CLASS_PROLOG;
                    pos.skip(7);
                    continue;
                }
                // else fall through...
            }
            if (state == METHOD_PROLOG) {
                // Wait for a semicolon or the opening brace of the method body.
                if (c == '{') {
                    skipBrace();
                    state = NEUTRAL;
                } else if (c == ';') {
                    // Abstract or native method. If token is null, we've
                    // added it already.
                    if (token != null) {
                        addTag(TAG_METHOD);
                        visibility = 0;
                    }
                    state = NEUTRAL;
                    pos.next();
                } else
                    pos.next();
                continue;
            }
            if (state == NEW) {
                if (c == '(') {
                    skipParen();
                    continue;
                } else if (c == '{') {
                    skipBrace();
                    continue;
                } else if (c == ';')
                    state = NEUTRAL;
                pos.next();
                continue;
            }
            if (state == FIELD_INITIALIZER) {
                if (c == '(') {
                    skipParen();
                    continue;
                }
                if (c == '{') {
                    skipBrace();
                    if (javaScript)
                        state = NEUTRAL;
                    continue;
                }
                if (c == ',')
                    state = NEUTRAL;
                else if (c == ';') {
                    state = NEUTRAL;
                    visibility = 0;
                }
                pos.next();
                continue;
            }
            if (state == IMPLEMENTS) {
                if (c == ',') {
                    pos.next();
                    continue;
                }
            }
            if (c == '}') {
                if (!stack.isEmpty())
                    currentClass = stack.pop();
                else
                    currentClass = null;
                pos.next();
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                // If we're in an "extends" or "implements" clause, the token
                // may be a canonical class name (like "java.lang.String").
                // Otherwise the token is a simple identifier.
                gatherToken(state == EXTENDS || state == IMPLEMENTS);
                if (state == INTERFACE_NAME) {
                    beginType("interface ", TAG_INTERFACE);
                    state = CLASS_PROLOG;
                } else if (state == CLASS_NAME) {
                    beginType("class ", TAG_CLASS);
                    state = CLASS_PROLOG;
                } else if (state == EXTENDS) {
                    tags.add(new JavaTag(token, tokenStart, TAG_EXTENDS, visibility, currentClass));
                    state = CLASS_PROLOG;
                } else if (state == IMPLEMENTS)
                    tags.add(new JavaTag(token, tokenStart, TAG_IMPLEMENTS, visibility, currentClass));
                else if (token.equals("package") || token.equals("import"))
                    skipSemi();
                else if (token.equals("interface"))
                    state = INTERFACE_NAME;
                else if (token.equals("class"))
                    state = CLASS_NAME;
                else if (token.equals("record") && !javaScript)
                    // JavaScript has no records: "record" is just a name.
                    state = RECORD_NAME;
                else if (token.equals("enum"))
                    state = ENUM_NAME;
                else if (token.equals("static")) {
                    visibility |= TAG_STATIC;
                    state = STATIC_INITIALIZER;
                } else if (token.equals("abstract"))
                    visibility |= TAG_ABSTRACT;
                else if (token.equals("final"))
                    visibility |= TAG_FINAL;
                else if (token.equals("new"))
                    // Don't be confused by lines like "Runnable r = new Runnable() { ... };"
                    state = NEW;
                else if (token.equals("public"))
                    visibility |= TAG_PUBLIC;
                else if (token.equals("protected"))
                    visibility |= TAG_PROTECTED;
                else if (token.equals("private"))
                    visibility |= TAG_PRIVATE;
                continue;
            }
            if (c == '(') {
                skipParen();
                state = METHOD_NAME;
                continue;
            }
            if (state == NEUTRAL && c == '{' && currentClass != null && !javaScript) {
                // A bare block in a class body: an instance initializer, or
                // a record's compact constructor (whose name is just the
                // record's, with no parens). Skip it whole, so its
                // statements don't become members and its braces don't
                // unbalance the class stack; its modifiers, as "public R
                // { }", end with it. JavaScript's blocks, as "try { }",
                // hold functions to tag and are not skipped.
                skipBrace();
                token = null;
                visibility = 0;
                continue;
            }
            if (c == '=') {
                addTag(TAG_FIELD);
                state = FIELD_INITIALIZER;
                pos.next();
                continue;
            }
            // "int x, y;"
            if (c == ';' || c == ',') {
                if (token != null)
                    addTag(TAG_FIELD);
                // Don't reset the visibility until we see the semicolon.
                if (c == ';')
                    visibility = 0;
            }
            pos.next();
        }
        buffer.setTags(tags);
    }

    // The type named by token begins: tag it, with keyword ("class ") before
    // its name, as nested in the current class, and make it current.
    private void beginType(String keyword, int type) {
        if (currentClass != null)
            stack.push(currentClass);
        final JavaClass parentClass = currentClass;
        currentClass = new JavaClass(token, type);
        tags.add(new JavaTag(keyword.concat(token), tokenStart, type, visibility, parentClass));
        visibility = 0;
    }

    // Whether pos is at word, standing alone: not part of a longer name.
    private boolean lookingAtWord(String word) {
        if (!pos.lookingAt(word))
            return false;
        final int offset = pos.getOffset();
        final Line line = pos.getLine();
        if (offset > 0 && Character.isJavaIdentifierPart(line.charAt(offset - 1)))
            return false;
        final int after = offset + word.length();
        return after >= line.length() || !Character.isJavaIdentifierPart(line.charAt(after));
    }

    // A quoted string or character, or in Java a text block, whose """
    // would otherwise read as an empty string and then an open quote.
    protected void skipQuote() {
        if (textBlocks && pos.lookingAt("\"\"\"")) {
            pos.skip(3);
            while (!pos.atEnd()) {
                if (pos.getChar() == '\\') {
                    // An escape, as \" or \\, never closes the block.
                    pos.next();
                    pos.next();
                    continue;
                }
                if (pos.lookingAt("\"\"\"")) {
                    pos.skip(3);
                    return;
                }
                if (!pos.next())
                    return;
            }
        } else
            pos.skipQuote();
    }

    private void addTag(int type) {
        if (currentClass != null) {
            StringBuilder sb = new StringBuilder(currentClass.getName());
            sb.append('.');
            sb.append(token);
            tags.add(new JavaTag(sb.toString(), tokenStart, type, visibility, currentClass));
        } else
            tags.add(new JavaTag(token, tokenStart, type, visibility));
    }

    // Modifiers a method's type parameters may follow: "static <T> T m()".
    private static final Set<String> MODIFIERS = Set.of(
        "abstract",
        "default",
        "final",
        "native",
        "private",
        "protected",
        "public",
        "static",
        "strictfp",
        "synchronized");

    // Whether the '<' at pos opens a generic type argument or parameter
    // list, which is skipped whole so its commas and identifiers never
    // reach the declaration-list or METHOD_NAME logic. A list that runs
    // into a ';', a brace, an '=', "&&" or "||" before it closes is a
    // comparison, not a generic, and is not skipped.
    private boolean skipGenericIfPresent() {
        if (pos.lookingAt("<<") || !mayOpenGeneric())
            return false;
        final Position start = pos.copy();
        int depth = 0;
        while (true) {
            if (pos.lookingAt("/*")) {
                skipComment(pos);
                continue;
            }
            if (pos.lookingAt("//")) {
                skipSingleLineComment(pos);
                continue;
            }
            char c = pos.getChar();
            if (c == '"' || c == '\'') {
                skipQuote();
                continue;
            }
            if (c == ';' || c == '{' || c == '}' || c == '=' || pos.lookingAt("&&") || pos.lookingAt("||")) {
                // A statement, block or expression boundary before the
                // list closes: a comparison.
                pos = start;
                return false;
            }
            if (c == '<')
                ++depth;
            else if (c == '>') {
                --depth;
                if (depth == 0) {
                    pos.next();
                    return true;
                }
            }
            if (!pos.next()) {
                pos = start;
                return false;
            }
        }
    }

    // Whether the '<' at pos is where a generic list can start: right after
    // a type's name, "Foo<Bar>" (never a number's digits, "1<<4" or
    // "1<b"); after a dot, "Foo.<T>m()"; or, spaced, after a modifier, an
    // annotation or the end of the previous member, as a method's type
    // parameters are: "public static <K, V> Map<K, V> m()". A spaced
    // "a < b" after a name is a comparison.
    private boolean mayOpenGeneric() {
        final Position before = pos.copy();
        if (!before.prev())
            return false;
        char c = before.getChar();
        final boolean adjacent = Character.isJavaIdentifierPart(c);
        while (Character.isWhitespace(c)) {
            if (!before.prev())
                return false;
            c = before.getChar();
        }
        if (c == '.' || c == ';' || c == '{' || c == '}')
            return true;
        if (!Character.isJavaIdentifierPart(c))
            return false;
        // The word ending at before, and the character ahead of it.
        final StringBuilder sb = new StringBuilder();
        sb.append(c);
        char ahead = '\0';
        while (before.prev()) {
            final char ch = before.getChar();
            if (!Character.isJavaIdentifierPart(ch)) {
                ahead = ch;
                break;
            }
            sb.append(ch);
        }
        final String word = sb.reverse().toString();
        if (Character.isDigit(word.charAt(0)))
            return false;
        if (adjacent)
            return true;
        return MODIFIERS.contains(word) || ahead == '@';
    }

    protected static final void skipComment(Position pos) {
        while (true) {
            if (pos.lookingAt("*/")) {
                pos.skip(2);
                return;
            }
            if (!pos.next())
                return;
        }
    }

    protected final void skipSingleLineComment(Position pos) {
        Line next = pos.getNextLine();
        if (next != null)
            pos.moveTo(next, 0);
        else
            pos.setOffset(pos.getLineLength());
    }

    protected final LocalTag checkForExplicitTag(Position pos, int modeId) {
        final String explicitTag = Editor.preferences().getStringProperty(Property.EXPLICIT_TAG);
        if (explicitTag != null && explicitTag.length() > 0) {
            pos = pos.copy();
            String s = pos.getString(); // Substring to end of line.
            int index = s.indexOf(explicitTag);
            if (index >= 0) {
                pos.skip(index + explicitTag.length());
                pos.skipWhitespace();
                // Now we're looking at the first character of the tag.
                StringBuilder sb = new StringBuilder();
                char c = pos.getChar();
                if (c == '"') {
                    while (pos.next()) {
                        c = pos.getChar();
                        if (c == '"')
                            break;
                        sb.append(c);
                    }
                } else {
                    // By default, explicit tags are whitespace-delimited, so
                    // they can contain characters that are not legal in Java
                    // identifiers ("symbol-value").
                    sb.append(c);
                    while (pos.next()) {
                        c = pos.getChar();
                        if (Character.isWhitespace(c))
                            break;
                        sb.append(c);
                    }
                }
                final String tag = sb.toString();
                // Exact location of tag is beginning of text on line
                // containing tag.
                pos.setOffset(0);
                pos.skipWhitespace();
                if (modeId == JAVA_MODE)
                    return new JavaTag(tag, pos, TAG_EXPLICIT, 0, currentClass);
                else if (modeId == CPP_MODE)
                    return new CppTag(tag, pos, TAG_EXPLICIT);
            }
        }
        return null;
    }

    // If canonical is true, strings like "java.lang.String" are considered to
    // be a single token. Used for "extends" and "implements" clauses.
    private void gatherToken(boolean canonical) {
        tokenStart = new Position(pos);
        StringBuilder sb = new StringBuilder();
        char c;
        if (canonical) {
            while (Character.isJavaIdentifierPart(c = pos.getChar()) || c == '.') {
                sb.append(c);
                if (!pos.next())
                    break;
            }
        } else {
            while (Character.isJavaIdentifierPart(c = pos.getChar())) {
                sb.append(c);
                if (!pos.next())
                    break;
            }
        }
        token = sb.toString();
    }

    protected void skipParen() {
        if (pos.next()) {
            int count = 1;
            while (true) {
                if (pos.lookingAt("/*")) {
                    skipComment(pos);
                    continue;
                }
                if (pos.lookingAt("//")) {
                    skipSingleLineComment(pos);
                    continue;
                }
                char c = pos.getChar();
                if (c == '"' || c == '\'') {
                    skipQuote();
                    continue;
                }
                if (c == '(')
                    ++count;
                else if (c == ')')
                    --count;
                if (!pos.next())
                    break;
                if (count == 0)
                    break;
            }
        }
    }

    protected void skipBrace() {
        if (pos.next()) {
            int count = 1;
            while (true) {
                if (pos.lookingAt("/*")) {
                    skipComment(pos);
                    continue;
                }
                if (pos.lookingAt("//")) {
                    skipSingleLineComment(pos);
                    continue;
                }
                char c = pos.getChar();
                if (c == '"' || c == '\'') {
                    skipQuote();
                    continue;
                }
                if (c == '\\') {
                    // Escape. Ignore this char and the next.
                    if (!pos.next())
                        break;
                    if (!pos.next())
                        break;
                    continue;
                }
                if (c == '{')
                    ++count;
                else if (c == '}')
                    --count;
                if (!pos.next())
                    break;
                if (count == 0)
                    break;
            }
        }
    }

    private void skipSemi() {
        while (!pos.atEnd()) {
            if (pos.lookingAt("/*")) {
                skipComment(pos);
                continue;
            }
            if (pos.lookingAt("//")) {
                skipSingleLineComment(pos);
                continue;
            }
            char c = pos.getChar();
            if (c == '"' || c == '\'') {
                skipQuote();
                continue;
            } else if (c == ';') {
                pos.next();
                break;
            } else
                pos.next();
        }
    }

    // Used by the C, C++ and Objective C taggers.
    protected static final void skipPreprocessor(Position pos) {
        while (true) {
            Line line = pos.getLine();
            Line nextLine = line.next();
            if (nextLine == null) {
                pos.setOffset(line.length());
                return;
            }
            pos.moveTo(nextLine, 0);
            if (line.length() == 0 || line.charAt(line.length() - 1) != '\\')
                return;
        }
    }
}
