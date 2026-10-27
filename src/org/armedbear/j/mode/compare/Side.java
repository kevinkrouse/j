/*
 * Side.java
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

package org.armedbear.j.mode.compare;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.armedbear.j.EncodingDetector;
import org.armedbear.j.File;

/**
 * A side of a comparison: a text's lines as shown, and as a patch writes
 * them, in their encoding with their CRs.
 */
public record Side(List<String> lines, List<String> raw, boolean noNewline) {
    /** No text, as of a file that is not there. */
    public static final Side NONE = new Side(List.of(), List.of(), false);

    /**
     * bytes as they are, in charset: each line's raw bytes kept, as a
     * patch must have them, and its text past a byte order mark. UTF-16,
     * by its mark or charset, is split on its own newlines.
     */
    public static Side of(byte[] bytes, Charset charset) {
        final Boolean bigEndian = utf16(bytes, charset);
        final int unit = bigEndian == null ? 1 : 2;
        final List<String> raw = new ArrayList<>();
        int start = 0;
        while (start < bytes.length) {
            int end = start;
            while (end < bytes.length && !newline(bytes, end, bigEndian))
                end += unit;
            end = Math.min(end, bytes.length);
            raw.add(new String(bytes, start, end - start, StandardCharsets.ISO_8859_1));
            start = end + unit;
        }
        final boolean noNewline = bytes.length >= unit && !newline(bytes, bytes.length - unit, bigEndian);
        final Charset decoding =
                bigEndian == null ? charset : bigEndian ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE;
        final List<String> lines = new ArrayList<>();
        for (String r : raw) {
            String s = new String(r.getBytes(StandardCharsets.ISO_8859_1), decoding);
            if (lines.isEmpty() && s.startsWith("\uFEFF"))
                s = s.substring(1);
            if (s.endsWith("\r"))
                s = s.substring(0, s.length() - 1);
            lines.add(s);
        }
        return new Side(lines, raw, noNewline);
    }

    /**
     * Whether bytes are UTF-16, by their byte order mark or else charset:
     * text whose raw lines are not git's, which splits on a byte.
     */
    public static boolean isUtf16(byte[] bytes, Charset charset) {
        return utf16(bytes, charset) != null;
    }

    // UTF-8's byte order mark, as a raw line has it.
    private static final String UTF8_MARK = "\u00ef\u00bb\u00bf";

    /**
     * This side, its first raw line starting with other's UTF-8 byte order
     * mark if other's does and its own does not, as a buffer's text, read
     * past the mark, is written back to its file.
     */
    public Side withMarkOf(Side other) {
        if (raw.isEmpty()
                || other.raw.isEmpty()
                || !other.raw.get(0).startsWith(UTF8_MARK)
                || raw.get(0).startsWith(UTF8_MARK))
            return this;
        final List<String> marked = new ArrayList<>(raw);
        marked.set(0, UTF8_MARK + raw.get(0));
        return new Side(lines, marked, noNewline);
    }

    // Whether bytes are UTF-16, big-endian or not, by their byte order mark
    // or else charset; null if they are not.
    private static Boolean utf16(byte[] bytes, Charset charset) {
        if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff)
            return true;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
            return false;
        if (charset.equals(StandardCharsets.UTF_16LE))
            return false;
        if (charset.equals(StandardCharsets.UTF_16) || charset.equals(StandardCharsets.UTF_16BE))
            return true;
        return null;
    }

    // Whether a newline starts at i, a byte, or a UTF-16 unit.
    private static boolean newline(byte[] bytes, int i, Boolean bigEndian) {
        if (bigEndian == null)
            return bytes[i] == '\n';
        if (i + 1 >= bytes.length)
            return false;
        return bigEndian ? bytes[i] == 0 && bytes[i + 1] == '\n' : bytes[i] == '\n' && bytes[i + 1] == 0;
    }

    /** file's text, as ofFile has it. */
    public static Side read(File file, Charset charset) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return ofFile(in.readAllBytes(), charset);
        }
    }

    /**
     * Whether bytes are not text, as git judges: a NUL in the first 8000,
     * but for UTF-16 with a byte order mark.
     */
    public static boolean isBinary(byte[] bytes) {
        if (bytes.length >= 2
                && (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff
                        || bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe))
            return false;
        for (int i = 0; i < Math.min(bytes.length, 8000); i++)
            if (bytes[i] == 0)
                return true;
        return false;
    }

    /**
     * A file's text, as j reads it: past a byte order mark; UTF-16 if that
     * says so; else UTF-8 if its bytes are, else in charset, unless that is
     * UTF-8 and they are not.
     */
    public static Side ofFile(byte[] bytes, Charset charset) {
        final int n = bytes.length;
        if (n >= 2
                && (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff
                        || bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)) {
            final String text = new String(bytes, StandardCharsets.UTF_16);
            return Side.of(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        final boolean bom = n >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf;
        final byte[] text = bom ? Arrays.copyOfRange(bytes, 3, n) : bytes;
        final String found = EncodingDetector.classify(text, 0, text.length, false);
        if (EncodingDetector.UTF_8.equals(found) || bom)
            return Side.of(text, StandardCharsets.UTF_8);
        if (found != null && charset.equals(StandardCharsets.UTF_8))
            return Side.of(text, Charset.forName(found));
        return Side.of(text, charset);
    }

    /**
     * A buffer's lines, written as the file would be: in charset, each
     * with a CR if crlf. j keeps a file's last newline as an empty last
     * line, which git does not count; without it, the file ends with no
     * newline.
     */
    public static Side ofBuffer(List<String> bufferLines, Charset charset, boolean crlf) {
        final int n = bufferLines.size();
        final boolean noNewline = n > 0 && !bufferLines.get(n - 1).isEmpty();
        final List<String> lines = List.copyOf(noNewline || n == 0 ? bufferLines : bufferLines.subList(0, n - 1));
        final List<String> raw = new ArrayList<>();
        for (String l : lines)
            raw.add(new String((crlf ? l + "\r" : l).getBytes(charset), StandardCharsets.ISO_8859_1));
        return new Side(lines, raw, noNewline);
    }

    /**
     * Its lines as they are compared: the last one marked if it has no
     * newline, so that adding or dropping one is a change, as git has it.
     */
    public List<String> keys() {
        if (!noNewline || lines.isEmpty())
            return lines;
        final List<String> keys = new ArrayList<>(lines);
        keys.set(keys.size() - 1, keys.get(keys.size() - 1) + "\u0000no newline");
        return keys;
    }

    /** Whether its lines end in CR LF, as most of them do. */
    public boolean crlf() {
        int cr = 0;
        for (String r : raw)
            if (r.endsWith("\r"))
                cr++;
        return !raw.isEmpty() && cr * 2 > raw.size();
    }
}
