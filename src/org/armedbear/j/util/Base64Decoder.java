/*
 * Base64Decoder.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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

package org.armedbear.j.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Base64Decoder
{
    private Base64Decoder() { }

    /** Decodes to a stream. Returns false, having written nothing, if the
     *  input is not base64. */
    public static boolean decode(String input, OutputStream outputStream)
        throws IOException
    {
        byte[] bytes = decode(input);
        if (bytes == null)
            return false;
        outputStream.write(bytes);
        return true;
    }

    /** Decodes to an array, or null if the input is not base64. */
    public static byte[] decode(String s)
    {
        if (s == null)
            return null;
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(
                s.getBytes(StandardCharsets.ISO_8859_1));
            // The MIME decoder skips what it does not recognise, so input
            // that is entirely unrecognisable decodes to nothing at all
            // rather than failing. Callers want that reported as a failure.
            if (bytes.length == 0 && !isBlank(s))
                return null;
            return bytes;
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBlank(String s)
    {
        for (int i = 0; i < s.length(); i++)
            if (!Character.isWhitespace(s.charAt(i)))
                return false;
        return true;
    }
}
