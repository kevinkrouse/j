/*
 * Base64DecoderTest.java
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

package org.armedbear.j.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Captures the contract the mail code relies on, which is not quite
 * java.util.Base64's: a failure is a null or a false, never an exception, and
 * a body that is not base64 at all has to read as a failure rather than as an
 * empty decode.
 */
public class Base64DecoderTest {
    private static String str(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    @Test
    public void decodesTheRfc4648Vectors() {
        assertEquals("", str(Base64Decoder.decode("")));
        assertEquals("f", str(Base64Decoder.decode("Zg==")));
        assertEquals("fo", str(Base64Decoder.decode("Zm8=")));
        assertEquals("foo", str(Base64Decoder.decode("Zm9v")));
        assertEquals("foob", str(Base64Decoder.decode("Zm9vYg==")));
        assertEquals("fooba", str(Base64Decoder.decode("Zm9vYmE=")));
        assertEquals("foobar", str(Base64Decoder.decode("Zm9vYmFy")));
    }

    /** A message body arrives as wrapped lines; the breaks are not data. */
    @Test
    public void ignoresTheLineBreaksInAMessageBody() {
        String body = "VGhpcyBpcyBhIHRlc3Qgb2YgdGhlIGJhc2U2NCBkZWNvZGVyIHdo\r\n"
            + "aWNoIG5lZWRzIHRvIHNwYW4gc2V2ZXJhbCBsaW5lcy4=\r\n";
        assertEquals(
            "This is a test of the base64 decoder which needs to span several lines.",
            str(Base64Decoder.decode(body))
        );
    }

    @Test
    public void toleratesTrailingWhitespaceAndBareNewlines() {
        assertEquals("foobar", str(Base64Decoder.decode("Zm9v  \nYmFy \n")));
    }

    @Test
    public void decodesEveryByteValue() {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++)
            all[i] = (byte) i;
        String encoded = java.util.Base64.getMimeEncoder().encodeToString(all);
        assertArrayEquals(all, Base64Decoder.decode(encoded));
    }

    @Test
    public void reportsFailureRatherThanThrowing() {
        assertNull(Base64Decoder.decode(null));
        // Not base64 at all: an empty decode would be read as success.
        assertNull(Base64Decoder.decode("!!!!"));
        // A final unit with too few bits to yield even one byte.
        assertNull(Base64Decoder.decode("Zm9vY"));
    }

    /**
     * A truncated body gives up what it can. Worth pinning: a mail reader
     * showing most of a damaged attachment beats it showing none.
     */
    public void recoversWhatItCanFromATruncatedBody() {
        assertEquals("fooba", str(Base64Decoder.decode("Zm9vYmF")));
        // Stray characters in the middle are skipped, not fatal.
        assertEquals("foobar", str(Base64Decoder.decode("Zm9v!!YmFy")));
    }

    @Test
    public void blankInputIsAnEmptyDecodeNotAFailure() {
        assertEquals("", str(Base64Decoder.decode("   \r\n  ")));
    }

    @Test
    public void writesToAStreamAndReportsSuccess() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(Base64Decoder.decode("Zm9vYmFy", out));
        assertEquals("foobar", str(out.toByteArray()));
    }

    /** Nothing is written when the input cannot be decoded. */
    @Test
    public void writesNothingOnFailure() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertFalse(Base64Decoder.decode("!!!!", out));
        assertEquals(0, out.size());
    }
}
