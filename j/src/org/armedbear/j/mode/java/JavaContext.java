/*
 * JavaContext.java
 *
 * Copyright (C) 2002 Peter Graves
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

import org.armedbear.j.Constants;
import org.armedbear.j.Editor;
import java.lang.StringBuilder;
import org.armedbear.j.Line;
import org.armedbear.j.LocalTag;
import org.armedbear.j.Log;
import org.armedbear.j.Position;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public final class JavaContext implements Constants
{
    private static final boolean DEBUG = false;

    private static final Pattern parameterRE =
        Pattern.compile("^(\\w+\\s+\\w+\\s*,?)");
    private static final Pattern declarationRE =
        Pattern.compile("^(\\w+\\s+\\w+\\s*;|\\w+\\s+\\w+\\s*=[^=])");

    // "return foo;"
    private static final Pattern returnRE = Pattern.compile("^return[ \t]");

    private final Editor editor;
    private final Stack<Scope> stack = new Stack<Scope>();

    public JavaContext(Editor editor)
    {
        this.editor = editor;
    }

    public void parseContext(Position dot)
    {
        final List<LocalTag> tags = editor.getBuffer().getTags();
        if (tags != null) {
            Scope scope = new Scope(new Position(editor.getBuffer().getFirstLine(), 0));
            stack.push(scope);
            // BUG! We should only consider the current top-level class (and
            // its inner classes if any), not all the tags in the file.
            final int size = tags.size();
            for (LocalTag tag1 : tags) {
                JavaTag tag = (JavaTag) tag1;
                if (tag.getType() == TAG_FIELD)
                    scope.addField(tag.getSignature());
            }
        }
        Position pos = findStartOfMethod(dot);
        if (pos != null) {
            while (pos.next() && pos.isBefore(dot)) {
                char c = pos.getChar();
                if (c == '(') {
                    Scope scope = new Scope(pos);
                    stack.push(scope);
                    scope.parseParameters();
                    continue;
                }
                if (c == '{') {
                    Scope scope = new Scope(pos);
                    stack.push(scope);
                    scope.parse(dot);
                    break;
                }
            }
        }
    }

    public JavaVariable findDeclaration(String name)
    {
        if (DEBUG)
            Log.debug("findDeclaration name = |" + name + "|");
        if (name == null)
            return null;
        int index = name.indexOf('.');
        if (index >= 0) {
            // It's a qualified name.
            String prefix = name.substring(0, index);
            // We only handle things like "this.foo".
            if (!prefix.equals("this"))
                return null;
            // It's a member of the current class.
            name = name.substring(index+1);
            if (stack.size() > 0) {
                Scope scope = stack.get(0);
                for (int j = 0; j < scope.list.size(); j++) {
                    JavaVariable var = scope.getVariable(j);
                    if (name.equals(var.getName()))
                        return var;
                }
            }
            return null;
        }
        // It's a simple name. A local variable hides a class member with the
        // same name.
        for (int i = stack.size()-1; i >= 0; i--) {
            Scope scope = stack.get(i);
            for (int j = 0; j < scope.list.size(); j++) {
                JavaVariable var = scope.getVariable(j);
                if (name.equals(var.getName()))
                    return var;
            }
        }
        return null;
    }

    private Position findStartOfMethod(Position dot)
    {
        if (dot != null) {
            final List<LocalTag> tags = editor.getBuffer().getTags();
            if (tags != null) {
                JavaTag tag = null;
                // Find the last tag before dot.
                final int target = dot.lineNumber();
                final int limit = tags.size();
                for (int i = 0; i < limit; i++) {
                    JavaTag nextTag = (JavaTag) tags.get(i);
                    if (nextTag.lineNumber() > target)
                        break;
                    else
                        tag = nextTag;
                }
                if (tag != null && tag.getType() == TAG_METHOD)
                    return tag.getPosition().copy();
            }
        }
        return null;
    }

    private final class Scope
    {
        final ArrayList<JavaVariable> list = new ArrayList<JavaVariable>();

        final Position start;
        final Position pos;

        Scope(Position pos)
        {
            this.pos = pos;
            start = pos.copy();
        }

        void parse(Position dot)
        {
            // Skip initial '{'.
            if (pos.getChar() == '{') {
                if (!pos.next())
                    return;
            }
            while (pos.isBefore(dot)) {
                char c = pos.getChar();
                if (c == '\'' || c == '"') {
                    pos.skipQuote();
                    continue;
                }
                if (c == '/' && pos.lookingAt("//")) {
                    Line next = pos.getNextLine();
                    if (next != null) {
                        pos.moveTo(next, 0);
                        continue;
                    } else
                        break;
                }
                if (c == '{') {
                    Scope scope = new Scope(pos);
                    stack.push(scope);
                    scope.parse(dot);
                } else if (c == '}') {
                    stack.pop();
                    return;
                } else {
                    final String text = pos.getLine().substring(pos.getOffset());
                    final Matcher matcher = declarationRE.matcher(text);
                    if (matcher.find()) {
                        String s = matcher.group();
                        if (returnRE.matcher(s).find()) {
                            if (DEBUG)
                                Log.debug("skipping |" + s + "|");
                        } else
                            addLocalVariable(s);
                        pos.skip(s.length());
                        continue;
                    }
                }
                if (!pos.next())
                    return;
            }
        }

        void parseParameters()
        {
            if (pos.getChar() == '(') {
                if (!pos.next())
                    return;
            }
            StringBuilder sb = new StringBuilder();
            while (!pos.atEnd()) {
                char c = pos.getChar();
                if (c == '\'' || c == '"') {
                    pos.skipQuote();
                    continue;
                }
                if (c == '/') {
                    if (pos.lookingAt("//")) {
                        Line next = pos.getNextLine();
                        if (next != null) {
                            pos.moveTo(next, 0);
                            continue;
                        } else
                            break;
                    }
                    if (pos.lookingAt("/*")) {
                        pos.skip(2);
                        while (!pos.lookingAt("*/")) {
                            if (!pos.next())
                                return;
                        }
                        pos.skip(2);
                        continue;
                    }
                }
                if (c == ')')
                    break;
                // Default.
                sb.append(c);
                if (!pos.next())
                    break;
            }
            String parameters = sb.toString();
            while (true) {
                if (DEBUG)
                    Log.debug("parameters = |" + parameters + "|");
                Matcher matcher = parameterRE.matcher(parameters);
                if (matcher.find()) {
                    String s = matcher.group();
                    addParameter(s);
                    parameters = parameters.substring(s.length()).trim();
                } else
                    break;
            }
        }

        void addField(String signature)
        {
            int index = signature.indexOf('=');
            if (index >= 0)
                signature = signature.substring(0, index);
            list.add(new JavaVariable(signature, JavaVariable.FIELD));
        }

        void addParameter(String s)
        {
            list.add(new JavaVariable(s, JavaVariable.PARAMETER));
        }

        void addLocalVariable(String s)
        {
            list.add(new JavaVariable(s, JavaVariable.LOCAL));
        }

        JavaVariable getVariable(int index)
        {
            return list.get(index);
        }

        int getVariableCount()
        {
            return list.size();
        }

        // For debugging.
        void dump()
        {
            Log.debug("scope at " + start);
            for (JavaVariable jv : list)
                Log.debug(jv.getName());
        }
    }

    // For debugging.
    public static void context()
    {
        final Editor editor = Editor.currentEditor();
        JavaContext context = new JavaContext(editor);
        context.parseContext(editor.getDot());
        Log.debug("--- context at " + editor.getDot() + " ---");
        context.dump();
    }

    // For debugging.
    private void dump()
    {
        for (Scope s : stack)
            s.dump();
    }
}
