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

    public static Side of(byte[] bytes, Charset charset) {
        final List<String> raw = new ArrayList<>();
        int start = 0;
        while (start < bytes.length) {
            int end = start;
            while (end < bytes.length && bytes[end] != '\n')
                end++;
            raw.add(new String(bytes, start, end - start, StandardCharsets.ISO_8859_1));
            start = end + 1;
        }
        final boolean noNewline = bytes.length > 0 && bytes[bytes.length - 1] != '\n';
        final List<String> lines = new ArrayList<>();
        for (String r : raw) {
            final byte[] b = r.getBytes(StandardCharsets.ISO_8859_1);
            String s = new String(b, charset);
            if (s.endsWith("\r"))
                s = s.substring(0, s.length() - 1);
            lines.add(s);
        }
        return new Side(lines, raw, noNewline);
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
