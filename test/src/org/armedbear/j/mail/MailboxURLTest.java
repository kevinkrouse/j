/*
 * Copyright (C) 2011 Kevin Krouse
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

package org.armedbear.j.mail;

import org.armedbear.j.File;
import org.junit.Test;
import org.junit.BeforeClass;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * User: kevink
 * Date: 5/22/11
 */
public class MailboxURLTest
{
    @BeforeClass
    public static void setup()
    {
    }

    @Test
    public void parseEmpty()
    {
        assertNull(MailboxURL.parse(null));
        assertNull(MailboxURL.parse(" "));
        assertNull(MailboxURL.parse("\"\""));
    }

    @Test
    public void local() throws Exception
    {
        MailboxURL url = MailboxURL.parse("local");
        assertTrue(url instanceof LocalMailboxURL);
        assertEquals(File.getInstance("local"), ((LocalMailboxURL)url).getFile());

        url = MailboxURL.parse("mailbox:local");
        assertTrue(url instanceof LocalMailboxURL);
        assertEquals(File.getInstance("local"), ((LocalMailboxURL) url).getFile());
    }

    @Test
    public void imap()
    {
        MailboxURL url = MailboxURL.parse("{hello@example.com}");
        assertTrue(url instanceof ImapURL);
        assertEquals("hello", url.getUser());
        assertEquals("example.com", url.getHost());

        url = MailboxURL.parse("{hello@example.com/imap}");
        assertTrue(url instanceof ImapURL);
        assertEquals("hello", url.getUser());
        assertEquals("example.com", url.getHost());
    }

    @Test
    public void pop3()
    {
        MailboxURL url = MailboxURL.parse("pop://hello@example.com");
        assertTrue(url instanceof PopURL);
        assertEquals("hello", url.getUser());
        assertEquals("example.com", url.getHost());

        url = MailboxURL.parse("{hello@example.com/pop3}");
        assertTrue(url instanceof PopURL);
        assertEquals("hello", url.getUser());
        assertEquals("example.com", url.getHost());

        url = MailboxURL.parse("{hello@example.com/service=pop3}");
        assertTrue(url instanceof PopURL);
        assertEquals("hello", url.getUser());
        assertEquals("example.com", url.getHost());
    }

    @Test
    public void userHostPort() throws Exception
    {
        MailboxURL url;

        url = MailboxURL.parse("{\"hello world\"}");
        assertEquals("hello world", url.getUser());
        assertEquals("127.0.0.1", url.getHost());

        url = MailboxURL.parse("{\"hello world\"@example.com}");
        assertEquals("hello world", url.getUser());
        assertEquals("example.com", url.getHost());

        url = MailboxURL.parse("{hello world@example.com}");
        assertEquals("hello world", url.getUser());
        assertEquals("example.com", url.getHost());

        url = MailboxURL.parse("{@example.com}");
        assertEquals("", url.getUser());
        assertEquals("example.com", url.getHost());

        url = MailboxURL.parse("{@example.com/user=hello}");
        assertEquals("hello", url.getUser());
        assertEquals("example.com", url.getHost());

        url = MailboxURL.parseRemote("{hello@example.com:123}", "imap");
        assertEquals("hello", url.getUser());
        assertEquals("example.com", url.getHost());
        assertEquals(123, url.getPort());

        url = MailboxURL.parse("pop://hello@example.com:123");
        assertEquals("hello", url.getUser());
        assertEquals("example.com", url.getHost());
        assertEquals(123, url.getPort());
    }

    @Test
    public void options()
    {
        MailboxURL url = MailboxURL.parse("{hello.world@example.com/ssl}");
        assertTrue(url.isSSL());
        assertFalse(url.isTLS());
        assertTrue(url.isDebug());
        assertTrue(url.validateCert);

        url = MailboxURL.parse("{hello.world@example.com/tls}");
        assertFalse(url.isSSL());
        assertTrue(url.isTLS());
        assertTrue(url.isDebug());
        assertTrue(url.validateCert);

        url = MailboxURL.parse("{hello.world@example.com/tls/ssl}");
        assertTrue(url.isSSL());
        assertTrue(url.isTLS());
        assertTrue(url.isDebug());
        assertTrue(url.validateCert);

        url = MailboxURL.parse("{hello.world@example.com/ssl/novalidate-cert}");
        assertTrue(url.isSSL());
        assertFalse(url.isTLS());
        assertTrue(url.isDebug());
        assertFalse(url.validateCert);
    }

    @Test
    public void limit() throws Exception
    {
        MailboxURL url = MailboxURL.parse("{hello@example.com}inbox");
        assertEquals("inbox", ((ImapURL)url).getFolderName());
        assertEquals(null, url.getLimitPattern());

        url = MailboxURL.parse("{hello@example.com}folder/path limit pattern");
        assertEquals("folder/path", ((ImapURL)url).getFolderName());
        assertEquals(Arrays.asList("folder", "path"), ((ImapURL)url).getFolderPathComponents());
        assertEquals("limit pattern", url.getLimitPattern());

        url = MailboxURL.parse("{hello@example.com} limit pattern");
        assertEquals("limit pattern", url.getLimitPattern());

        url = MailboxURL.parse("{\"hello world\"@example.com} limit pattern");
        assertEquals("limit pattern", url.getLimitPattern());

        url = MailboxURL.parse("\"{hello@example.com}\"limit pattern");
        assertEquals("limit pattern", url.getLimitPattern());

        url = MailboxURL.parse("\"{hello@example.com}folder path\" limit pattern");
        assertEquals("folder path", ((ImapURL)url).getFolderName());
        assertEquals(Arrays.asList("folder path"), ((ImapURL)url).getFolderPathComponents());
        assertEquals("limit pattern", url.getLimitPattern());

        url = MailboxURL.parse("\"pop://hello@example.com\"limit pattern");
        assertEquals("limit pattern", url.getLimitPattern());

        url = MailboxURL.parse("pop://hello@example.com limit pattern");
        assertEquals("limit pattern", url.getLimitPattern());
    }
}
