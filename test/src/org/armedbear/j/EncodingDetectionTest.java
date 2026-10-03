/*
 * EncodingDetectionTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.armedbear.j.mode.text.PlainTextMode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * What a file loads and saves as, unless an encoding was asked for: what its
 * first bytes say, or the first line that is not ASCII, else defaultEncoding,
 * UTF-8.
 */
public class EncodingDetectionTest
{
    private Path dir;

    @Before
    public void setUp() throws Exception
    {
        dir = Files.createTempDirectory("j-encoding");
    }

    @After
    public void tearDown() throws Exception
    {
        Editor.preferences().removeProperty(Property.DETECT_ENCODING.key());
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
        }
    }

    private Buffer load(byte[] bytes) throws Exception
    {
        return load(bytes, null);
    }

    private Buffer load(byte[] bytes, String encoding) throws Exception
    {
        return load(bytes, encoding, "f" + System.nanoTime() + ".txt");
    }

    private Buffer load(byte[] bytes, String encoding, String name) throws Exception
    {
        final Path path = dir.resolve(name);
        Files.write(path, bytes);
        final File file = File.getInstance(path.toString());
        if (encoding != null)
            file.setEncoding(encoding);
        final Buffer buffer = new Buffer(file);
        buffer.setMode(PlainTextMode.getMode());
        try (java.io.InputStream in = file.getInputStream()) {
            buffer.load(in, file.getEncoding());
        }
        return buffer;
    }

    private static byte[] bytes(String s, Charset charset)
    {
        return s.getBytes(charset);
    }

    private static byte[] utf8(String s)
    {
        return bytes(s, StandardCharsets.UTF_8);
    }

    private static Line line(Buffer b, int n)
    {
        Line line = b.getFirstLine();
        for (int i = 0; i < n; i++)
            line = line.next();
        return line;
    }

    // Saving gives back exactly the bytes loaded.
    private void assertRoundTrip(Buffer b, byte[] bytes) throws Exception
    {
        final Path out = dir.resolve("out" + System.nanoTime());
        b.writeFile(File.getInstance(out.toString()));
        assertArrayEquals(bytes, Files.readAllBytes(out));
    }

    @Test
    public void utf8LoadsAsUtf8() throws Exception
    {
        final byte[] bytes = utf8("a → b › c\n");
        final Buffer b = load(bytes);
        assertEquals("a → b › c", b.getFirstLine().getText());
        assertEquals("UTF-8", b.getSaveEncoding());
        assertRoundTrip(b, bytes);
    }

    @Test
    public void asciiIsTheDefaultUtf8() throws Exception
    {
        assertEquals("UTF-8", load(utf8("plain\n")).getSaveEncoding());
    }

    @Test
    public void latin1() throws Exception
    {
        final byte[] bytes = bytes("café\n", StandardCharsets.ISO_8859_1);
        final Buffer b = load(bytes);
        assertEquals("café", b.getFirstLine().getText());
        assertEquals("ISO-8859-1", b.getSaveEncoding());
        assertRoundTrip(b, bytes);
    }

    @Test
    public void windows1252ByItsQuotes() throws Exception
    {
        final byte[] bytes = bytes("“quoted” €5\n",
                                   Charset.forName("windows-1252"));
        final Buffer b = load(bytes);
        assertEquals("“quoted” €5", b.getFirstLine().getText());
        assertEquals("windows-1252", b.getSaveEncoding());
        assertRoundTrip(b, bytes);
    }

    // Past the first 1K, the first line that is not ASCII decides, and
    // the file is not read ahead to find it.
    private static String asciiLines()
    {
        final StringBuilder sb = new StringBuilder();
        while (sb.length() < 2 * EncodingDetector.SNIFF_LENGTH)
            sb.append("just ascii text on a line\n");
        return sb.toString();
    }

    @Test
    public void aLateLatin1LineDecides() throws Exception
    {
        final String text = asciiLines() + "café\n";
        final byte[] bytes = bytes(text, StandardCharsets.ISO_8859_1);
        final Buffer b = load(bytes);
        assertEquals("ISO-8859-1", b.getSaveEncoding());
        assertEquals("café", b.getLastLine().previous().getText());
        assertRoundTrip(b, bytes);
    }

    @Test
    public void aLateUtf8LineDecides() throws Exception
    {
        final byte[] bytes = utf8(asciiLines() + "a → b\n");
        final Buffer b = load(bytes);
        assertEquals("UTF-8", b.getSaveEncoding());
        assertEquals("a → b", b.getLastLine().previous().getText());
    }

    @Test
    public void aByteOrderMarkIsKept() throws Exception
    {
        final byte[] text = utf8("# Título\n");
        final byte[] bytes = new byte[text.length + 3];
        bytes[0] = (byte) 0xef;
        bytes[1] = (byte) 0xbb;
        bytes[2] = (byte) 0xbf;
        System.arraycopy(text, 0, bytes, 3, text.length);
        final Buffer b = load(bytes);
        assertEquals("# Título", b.getFirstLine().getText());
        assertRoundTrip(b, bytes);
    }

    @Test
    public void aDeclaredEncoding() throws Exception
    {
        final Charset koi8 = Charset.forName("KOI8-R");
        final Buffer b = load(bytes("# -*- coding: koi8-r -*-\nпривет\n", koi8));
        assertEquals("привет", line(b, 1).getText());
        final Buffer xml = load(bytes("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<a>é</a>\n",
                                      StandardCharsets.ISO_8859_1));
        assertEquals("<a>é</a>", line(xml, 1).getText());
    }

    @Test
    public void detectionCanBeTurnedOff() throws Exception
    {
        Editor.preferences().setProperty(Property.DETECT_ENCODING, "false");
        final Buffer b = load(bytes("café\n", StandardCharsets.ISO_8859_1));
        assertEquals("UTF-8", b.getSaveEncoding());
        assertEquals("caf�", b.getFirstLine().getText());
    }

    @Test
    public void anEncodingAskedForWins() throws Exception
    {
        final Buffer b = load(utf8("é\n"), "ISO-8859-1");
        assertEquals("Ã©", b.getFirstLine().getText());
    }

    @Test
    public void anEmptyFileLoads() throws Exception
    {
        final Buffer b = load(new byte[0]);
        assertEquals("", b.getFirstLine().getText());
    }

    @Test
    public void classify()
    {
        final byte[] utf8 = utf8("→");
        assertEquals(null, EncodingDetector.classify(utf8("abc"), 0, 3, false));
        assertEquals("UTF-8", EncodingDetector.classify(utf8, 0, utf8.length, false));
        // Cut inside the character: well-formed so far.
        assertEquals("UTF-8", EncodingDetector.classify(utf8, 0, 2, true));
        // Not at the end of the input, it is not UTF-8; 0x86 is a dagger in
        // Windows-1252 and a control character in ISO-8859-1.
        assertEquals("windows-1252", EncodingDetector.classify(utf8, 0, 2, false));
        assertEquals("ISO-8859-1", EncodingDetector.classify(
            "caf\u00e9".getBytes(StandardCharsets.ISO_8859_1), 0, 4, false));
    }

    @Test
    public void aWordWithCodingInItDeclaresNothing() throws Exception
    {
        final Buffer b = load(utf8("# Encoding: windows-1252 notes\ncaf\u00e9\n"));
        assertEquals("UTF-8", b.getSaveEncoding());
        assertEquals("caf\u00e9", line(b, 1).getText());
    }

    @Test
    public void wellFormedUtf8BeatsADeclaration() throws Exception
    {
        final Buffer b = load(utf8("# -*- coding: latin-1 -*-\ncaf\u00e9\n"));
        assertEquals("UTF-8", b.getSaveEncoding());
        assertEquals("caf\u00e9", line(b, 1).getText());
    }

    @Test
    public void aPythonCookieDeclares() throws Exception
    {
        final Buffer b = load(utf8("#!/usr/bin/env python\n# coding: latin-1\nx = 1\n"));
        assertEquals("ISO-8859-1", b.getSaveEncoding());
    }

    @Test
    public void metaCharsetOnlyInHtml() throws Exception
    {
        final byte[] text = utf8("<meta charset=\"iso-8859-1\">\nplain\n");
        assertEquals("UTF-8", load(text, null, "README" + System.nanoTime() + ".md")
                     .getSaveEncoding());
        assertEquals("ISO-8859-1", load(text, null, "page" + System.nanoTime() + ".html")
                     .getSaveEncoding());
    }

    @Test
    public void aLineThatIsNotUtf8LosesNothing() throws Exception
    {
        // UTF-8 decided late, then a Latin-1 byte: the whole file is read
        // as Latin-1, so saving writes every byte back.
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(utf8(asciiLines() + "a \u2192 b\n" + asciiLines()));
        out.write("caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));
        final byte[] bytes = out.toByteArray();
        final Buffer b = load(bytes);
        assertEquals("ISO-8859-1", b.getSaveEncoding());
        assertEquals("caf\u00e9", b.getLastLine().previous().getText());
        assertRoundTrip(b, bytes);
    }

    @Test
    public void aReloadForgetsAByteOrderMark() throws Exception
    {
        final byte[] text = utf8("x\n");
        final byte[] bytes = new byte[text.length + 3];
        bytes[0] = (byte) 0xef;
        bytes[1] = (byte) 0xbb;
        bytes[2] = (byte) 0xbf;
        System.arraycopy(text, 0, bytes, 3, text.length);
        final Buffer b = load(bytes);
        // Again, asking for UTF-8: the mark is text now, kept once.
        b.empty();
        try (java.io.InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            b.load(in, "UTF-8");
        }
        assertRoundTrip(b, bytes);
    }
}
