/*
 * LocalTag.java
 *
 * Copyright (C) 1998-2002 Peter Graves
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

import org.armedbear.j.util.Utilities;

import javax.swing.Icon;
import javax.swing.undo.CompoundEdit;

public class LocalTag extends Tag implements Constants
{
    private final Position pos;
    private final int type;
    private int flags;

    public LocalTag(String name, Line line)
    {
        super(name, line.getText());
        pos = new Position(line, 0);
        type = TAG_METHOD;
    }

    public LocalTag(String name, Position pos)
    {
        super(name, pos.getLine().getText());
        this.pos = new Position(pos);
        type = TAG_METHOD;
    }

    protected LocalTag(String name, Position pos, int type, int flags)
    {
        super(name, pos.getLine().getText());
        this.pos = new Position(pos);
        this.type = type;
        this.flags = flags;
    }

    protected LocalTag(String name, Position pos, int type)
    {
        super(name, pos.getLine().getText());
        this.pos = new Position(pos);
        this.type = type;
    }

    public String getMethodName()
    {
        return name;
    }

    public String getLongName()
    {
        return name;
    }

    public String getClassName()
    {
        return null;
    }

    public final Position getPosition()
    {
        return pos;
    }

    public final Line getLine()
    {
        return pos.getLine();
    }

    public final int lineNumber()
    {
        return pos.lineNumber();
    }

    public final int getType()
    {
        return type;
    }

    public final boolean isPublic()
    {
        return (flags & TAG_VISIBILITY_MASK) == TAG_PUBLIC;
    }

    public final boolean isProtected()
    {
        return (flags & TAG_VISIBILITY_MASK) == TAG_PROTECTED;
    }

    public final boolean isPrivate()
    {
        return (flags & TAG_VISIBILITY_MASK) == TAG_PRIVATE;
    }

    public final boolean isStatic()
    {
        return (flags & TAG_STATIC) != 0;
    }

    public final boolean isAbstract()
    {
        return (flags & TAG_ABSTRACT) != 0;
    }

    public final boolean isFinal()
    {
        return (flags & TAG_FINAL) != 0;
    }

    /**
     * The modifier badge, or null.
     *
     * <p>At most one is shown so the most telling modifier wins:
     * abstract says the most about a declaration, final the least.
     */
    private static String modifierBadge(LocalTag tag)
    {
        if (tag.isAbstract())
            return "abstract";
        if (tag.isStatic())
            return "static";
        if (tag.isFinal())
            return "final";
        return null;
    }

    public Icon getIcon()
    {
        String base;
        String visibility = null;
        switch (type) {
            case TAG_INTERFACE:
            case TAG_IMPLEMENTS:
            case TAG_TYPE:      // Lisp
                base = "interface";
                break;
            case TAG_CLASS:
            case TAG_EXTENDS:
            case TAG_CONDITION: // Lisp
            case TAG_STRUCT:    // Lisp
                base = "class";
                break;
            case TAG_METHOD:
            case TAG_MACRO:     // Lisp
            case TAG_DEFUN:     // Lisp
            default:
                base = "method";
                break;
            case TAG_FIELD:
            case TAG_CONSTANT:  // Lisp
            case TAG_PARAMETER: // Lisp
            case TAG_VAR:       // Lisp
                base = "field";
                break;
        }
        if (isPublic())
            visibility = "public";
        else if (isProtected())
            visibility = "protected";
        else if (isPrivate())
            visibility = "private";
        return Utilities.getBadgedIcon(base, visibility, modifierBadge(this));
    }

    public String toString()
    {
        return getMethodName();
    }

    public String getSidebarText()
    {
        return getMethodName();
    }

    public String getToolTipText()
    {
        return getLongName();
    }

    public void gotoTag(Editor editor)
    {
        if (editor.getBuffer().contains(pos.getLine())) {
            CompoundEdit compoundEdit = editor.beginCompoundEdit();
            editor.addUndo(SimpleEdit.FOLD);
            editor.unfoldMethod(pos.getLine());
            editor.moveDotTo(pos);
            TagCommands.centerTag(editor);
            editor.endCompoundEdit(compoundEdit);
            editor.getBuffer().repaint();
            editor.updateDisplay();
        }
    }

}
