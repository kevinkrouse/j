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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.armedbear.j.mode.text.PlainTextMode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A file that is well-formed UTF-8 loads as UTF-8 unless an encoding was
 * asked for, as vim's fileencodings=utf-8,latin1 does; anything else as
 * defaultEncoding.
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
        Editor.preferences().removeProperty(Property.DETECT_UTF8.key());
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
        final Path path = dir.resolve("f" + System.nanoTime() + ".txt");
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

    private static byte[] utf8(String s)
    {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void utf8LoadsAsUtf8()
    {
        try {
            final Buffer b = load(utf8("a → b › c\n"));
            assertEquals("a → b › c", b.getFirstLine().getText());
            assertEquals("UTF-8", b.getSaveEncoding());
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void latin1LoadsAsTheDefault() throws Exception
    {
        final Buffer b = load("café\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("café", b.getFirstLine().getText());
        assertEquals("ISO-8859-1", b.getSaveEncoding());
    }

    @Test
    public void asciiIsUtf8SoWhatIsTypedInItSaves() throws Exception
    {
        assertEquals("UTF-8", load(utf8("plain\n")).getSaveEncoding());
    }

    @Test
    public void detectionCanBeTurnedOff() throws Exception
    {
        Editor.preferences().setProperty(Property.DETECT_UTF8, "false");
        final Buffer b = load(utf8("é\n"));
        assertEquals("Ã©", b.getFirstLine().getText());
        assertEquals("ISO-8859-1", b.getSaveEncoding());
    }

    @Test
    public void anEncodingAskedForWins() throws Exception
    {
        final Buffer b = load(utf8("é\n"), "ISO-8859-1");
        assertEquals("Ã©", b.getFirstLine().getText());
    }

    @Test
    public void savingWritesTheBytesItRead() throws Exception
    {
        final byte[] bytes = utf8("# Título\n- [ ] → ☑\n");
        final Buffer b = load(bytes);
        final Path out = dir.resolve("out.md");
        b.writeFile(File.getInstance(out.toString()));
        assertArrayEquals(bytes, Files.readAllBytes(out));
    }

    @Test
    public void anEmptyFileLoads() throws Exception
    {
        final Buffer b = load(new byte[0]);
        assertEquals("", b.getFirstLine().getText());
    }
}
