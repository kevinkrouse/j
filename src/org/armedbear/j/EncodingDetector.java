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

    // On the first two lines: Emacs's "-*- coding: latin-1 -*-", Python's
    // "# coding: latin-1" (PEP 263), vim's "vim: set fileencoding=latin1:".
    // "coding" a word of its own, so "# Encoding notes" declares nothing.
    private static final Pattern EMACS_CODING =
        Pattern.compile("-\\*-.*?\\bcoding:\\s*([-\\w.:]+).*?-\\*-");
    private static final Pattern PYTHON_CODING =
        Pattern.compile("^[ \\t\\f]*#.*?\\bcoding[:=][ \\t]*([-\\w.]+)");
    private static final Pattern VIM_CODING =
        Pattern.compile("\\bvim?:.*?\\b(?:fileencoding|fenc)=([-\\w.]+)");
    // At the very start of the file.
    private static final Pattern XML_DECLARATION =
        Pattern.compile("^<\\?xml[^>]*\\sencoding\\s*=\\s*[\"']([-\\w.:]+)[\"']");
    // In an HTML file only.
    private static final Pattern META_CHARSET =
        Pattern.compile("<meta[^>]*charset\\s*=\\s*[\"']?([-\\w.:]+)",
                        Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_FILE =
        Pattern.compile(".*\\.(?:html?|xhtml|shtml)", Pattern.CASE_INSENSITIVE);

    private EncodingDetector() {}

    /**
     * The encoding of a file named fileName whose first bytes are
     * bytes[0, length). Bytes that are well-formed UTF-8, and not all ASCII,
     * are UTF-8 whatever the file declares: a declaration is more often
     * wrong, or text about one, than Latin-1 is well-formed UTF-8.
     */
    public static Result detect(byte[] bytes, int length, String fileName)
    {
        length = Math.max(0, Math.min(length, SNIFF_LENGTH));
        if (length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb
            && bytes[2] == (byte) 0xbf)
            return new Result(UTF_8, 3);
        final String classified = classify(bytes, 0, length, true);
        if (UTF_8.equals(classified))
            return new Result(UTF_8, 0);
        final String declared = declared(bytes, length, fileName);
        return new Result(declared != null ? declared : classified, 0);
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
    private static String declared(byte[] bytes, int length, String fileName)
    {
        final String text = new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
        final String[] lines = text.split("\r?\n|\r", 3);
        for (int i = 0; i < Math.min(2, lines.length); i++) {
            for (Pattern pattern : new Pattern[] { EMACS_CODING, PYTHON_CODING,
                                                   VIM_CODING }) {
                final Matcher m = pattern.matcher(lines[i]);
                if (m.find() && charset(m.group(1)) != null)
                    return charset(m.group(1));
            }
        }
        Matcher m = XML_DECLARATION.matcher(text);
        if (m.find() && charset(m.group(1)) != null)
            return charset(m.group(1));
        if (fileName != null && HTML_FILE.matcher(fileName).matches()) {
            m = META_CHARSET.matcher(text);
            if (m.find() && charset(m.group(1)) != null)
                return charset(m.group(1));
        }
        return null;
    }

    // Java's name for a declared encoding: as it is, or as Python and Emacs
    // spell it, "latin-1", "utf-8-unix"; null if Java has none.
    private static String charset(String name)
    {
        final String withoutEol = name.replaceFirst("-(?:unix|dos|mac)$", "");
        for (String candidate : new String[] { name, withoutEol,
                                               withoutEol.replace("-", "") }) {
            try {
                if (Charset.isSupported(candidate))
                    return Charset.forName(candidate).name();
            }
            catch (IllegalArgumentException e) {
                // Not a name Java takes; try the next.
            }
        }
        return null;
    }
}
