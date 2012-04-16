/*
 * BufferStream.java
 *
 * Copyright (C) 2004-2005 Peter Graves
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

import org.armedbear.lisp.ControlTransfer;
import org.armedbear.lisp.LispObject;
import org.armedbear.lisp.Stream;
import org.armedbear.lisp.Symbol;
import static org.armedbear.lisp.Lisp.T;

public final class BufferStream extends Stream
{
    private final Buffer buffer;

    public BufferStream(Buffer buf)
    {
        super(LispAPI.BUFFER_STREAM);
        buffer = buf;
        elementType = Symbol.CHARACTER;
        isCharacterStream = true;
        isOutputStream = true;
    }

    public Buffer getBuffer()
    {
        return buffer;
    }

    public LispObject typeOf()
    {
        return LispAPI.BUFFER_STREAM;
    }

//     // FIXME
//     public LispClass classOf()
//     {
//         return BuiltInClass.STREAM;
//     }

    // FIXME
    public LispObject typep(LispObject typeSpecifier)
    {
        if (typeSpecifier == LispAPI.BUFFER_STREAM)
            return T;
        return super.typep(typeSpecifier);
    }

    public void _writeChar(char c)
    {
        try {
            buffer.lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            switch (c) {
                case '\r':
                    break;
                case '\n': {
                    buffer.appendLine("");
                    buffer.modified();
                    buffer.needsRenumbering(true);
                    break;
                }
                default: {
                    Line line = buffer.getLastLine();
                    int offset = line.length();
                    FastStringBuffer sb =
                        new FastStringBuffer(line.getText());
                    sb.append(c);
                    line.setText(sb.toString());
                    buffer.modified();
                }
            }
        }
        finally {
            buffer.unlockWrite();
        }
    }

    public void _writeChars(char[] chars, int start, int end)
    {
        _writeString(new String(chars, start, end - start));
    }

    public void _writeString(String s)
    {
        try {
            buffer.lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            buffer.insertString(buffer.getEnd(), s);
            buffer.modified();
            if (s.indexOf('\n') >= 0)
                buffer.needsRenumbering(true);
        }
        finally {
            buffer.unlockWrite();
        }
    }

    public void _writeLine(String s)
    {
        try {
            buffer.lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            buffer.append(s);
            buffer.appendLine("");
            buffer.modified();
            buffer.needsRenumbering(true);
        }
        finally {
            buffer.unlockWrite();
        }
    }

    public void _finishOutput()
    {
        if (buffer.needsRenumbering())
            buffer.renumber();
        buffer.repaint();
    }

    public void _close()
    {
        _finishOutput();
        setOpen(false);
    }

    public String toString()
    {
        return unreadableString("BUFFER-STREAM");
    }
}
