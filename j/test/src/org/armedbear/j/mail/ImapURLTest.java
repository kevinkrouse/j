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

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * User: kevink
 * Date: 5/22/11
 */
public class ImapURLTest
{
    @Test
    public void parse() throws Exception
    {
        ImapURL url = ImapURL.parseURL("{hello.world@example.com:123/tls/ssl}one/two");
        assertEquals("hello.world", url.getUser());
        assertEquals("example.com", url.getHost());
        assertEquals(123, url.getPort());
        assertTrue(url.isSSL());
        assertTrue(url.isTLS());
        assertTrue(url.isDebug());
        assertTrue(url.validateCert);
        assertEquals("one/two", url.getFolderName());
        assertEquals(Arrays.asList("one", "two"), url.getFolderPathComponents());
    }
}
