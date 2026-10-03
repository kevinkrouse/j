/*
 * MailCacheFilterTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import org.armedbear.j.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A mailbox cache file reads back j's own classes and nothing else. */
public class MailCacheFilterTest {
    @TempDir
    Path dir;

    @Test
    public void aSummaryReadsBack() throws Exception {
        final Path mbox = Files.writeString(dir.resolve("mbox"), "From a\n");
        final LocalMailboxEntry entry = new LocalMailboxEntry(
            1,
            0,
            "From: A <a@example.com>\nTo: b@example.com\nDate: Mon, 1 Jan 2024 00:00:00 +0000\nSubject: hi\n"
        );
        final MboxSummary summary = new MboxSummary(File.getInstance(mbox.toString()), List.of(entry));
        final MboxSummary read = (MboxSummary) read(write(summary));
        assertEquals("hi", read.getEntries().get(0).getSubject());
        assertNotNull(read.getEntries().get(0).getDate());
    }

    @Test
    public void anythingElseIsRefused() throws Exception {
        final byte[] bytes = write(new HashMap<String, String>());
        assertThrows(InvalidClassException.class, () -> read(bytes));
    }

    @Test
    public void aRefusedCacheIsIgnored() throws Exception {
        final Path file = dir.resolve("summary");
        Files.write(file, write(new HashMap<String, String>()));
        assertNull(MboxSummary.read(File.getInstance(file.toString())));
    }

    private static byte[] write(Object o) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(o);
        }
        return bytes.toByteArray();
    }

    private static Object read(byte[] bytes) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(MailUtilities.CACHE_FILTER);
            return in.readObject();
        }
    }
}
