/*
 * EncodingDetector.java
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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a file's encoding is, from its first bytes: a UTF-8 byte order mark;
 * an encoding the file declares; UTF-8 if its bytes are; else Windows-1252
 * or ISO-8859-1. Bytes that are all ASCII say nothing, and the first line
 * that is not decides instead (classify), so a file is never read ahead.
 * UTF-16 with a byte order mark SystemBuffer reads for itself.
 */
public final class EncodingDetector
{
    /** How much of a file to look at. */
    public static final int SNIFF_LENGTH = 1024;

    public static final String UTF_8 = "UTF-8";
    public static final String WINDOWS_1252 = "windows-1252";
    public static final String ISO_8859_1 = "ISO-8859-1";

    /** What detect found. */
    public static final class Result
    {
        /** The encoding, or null if the bytes were ASCII and said nothing. */
        public final String encoding;
        /** Bytes at the start that are not text: a byte order mark. */
        public final int skip;

        Result(String encoding, int skip)
        {
            this.encoding = encoding;
            this.skip = skip;
        }
    }

    // Emacs's and Python's "-*- coding: latin-1 -*-", vim's
    // "fileencoding=utf-8", on the first two lines.
    private static final Pattern CODING =
        Pattern.compile("(?:coding[:=]|fileencoding=|fenc=)\\s*([-\\w.:]+)");
    private static final Pattern XML_DECLARATION =
        Pattern.compile("^<\\?xml[^>]*\\sencoding\\s*=\\s*[\"']([-\\w.:]+)[\"']");
    private static final Pattern META_CHARSET =
        Pattern.compile("<meta[^>]*charset\\s*=\\s*[\"']?([-\\w.:]+)",
                        Pattern.CASE_INSENSITIVE);

    private EncodingDetector() {}

    /** The encoding of a file whose first bytes are bytes[0, length). */
    public static Result detect(byte[] bytes, int length)
    {
        length = Math.max(0, Math.min(length, SNIFF_LENGTH));
        if (length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb
            && bytes[2] == (byte) 0xbf)
            return new Result(UTF_8, 3);
        final String declared = declared(bytes, length);
        if (declared != null)
            return new Result(declared, 0);
        return new Result(classify(bytes, 0, length, true), 0);
    }

    /**
     * The encoding of bytes[begin, end): null if they are all ASCII, UTF-8
     * if they are well-formed UTF-8, else Windows-1252 if any is 0x80-0x9f,
     * which ISO-8859-1 leaves to control characters and Windows-1252 makes
     * quotes and dashes, else ISO-8859-1.
     *
     * @param truncated whether the bytes may stop inside a character
     */
    public static String classify(byte[] bytes, int begin, int end, boolean truncated)
    {
        boolean ascii = true;
        boolean c1 = false;
        for (int i = begin; i < end; i++) {
            final int b = bytes[i] & 0xff;
            if (b >= 0x80) {
                ascii = false;
                if (b <= 0x9f)
                    c1 = true;
            }
        }
        if (ascii)
            return null;
        if (isUtf8(bytes, begin, end, truncated))
            return UTF_8;
        return c1 ? WINDOWS_1252 : ISO_8859_1;
    }

    private static boolean isUtf8(byte[] bytes, int begin, int end, boolean truncated)
    {
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        final ByteBuffer in = ByteBuffer.wrap(bytes, begin, end - begin);
        final CharBuffer out = CharBuffer.allocate(end - begin);
        // Not the end of the input if it may stop inside a character: what
        // is left over is then not an error.
        final CoderResult result = decoder.decode(in, out, !truncated);
        if (result.isError())
            return false;
        return truncated ? in.remaining() < 4 : !in.hasRemaining();
    }

    // An encoding the text declares, that Java has.
    private static String declared(byte[] bytes, int length)
    {
        final String text = new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
        int firstLines = text.indexOf('\n');
        if (firstLines >= 0)
            firstLines = text.indexOf('\n', firstLines + 1);
        if (firstLines < 0)
            firstLines = text.length();
        Matcher m = CODING.matcher(text).region(0, firstLines);
        if (m.find() && isSupported(m.group(1)))
            return m.group(1);
        m = XML_DECLARATION.matcher(text);
        if (m.find() && isSupported(m.group(1)))
            return m.group(1);
        m = META_CHARSET.matcher(text);
        if (m.find() && isSupported(m.group(1)))
            return m.group(1);
        return null;
    }

    private static boolean isSupported(String name)
    {
        try {
            return Charset.isSupported(name);
        }
        catch (IllegalArgumentException e) {
            return false;
        }
    }
}
