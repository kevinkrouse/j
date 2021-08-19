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

import java.util.GregorianCalendar;
import java.util.TimeZone;

import static org.junit.Assert.*;

/**
 * User: kevink
 * Date: 5/22/11
 */
public class ImapMailboxEntryTest
{
    @Test
    public void parseMessageNumber()
    {
        assertEquals(0, ImapMailboxEntry.TestHelper.parseMessageNumber(null));
        assertEquals(0, ImapMailboxEntry.TestHelper.parseMessageNumber(""));
        assertEquals(0, ImapMailboxEntry.TestHelper.parseMessageNumber("abc"));
        assertEquals(0, ImapMailboxEntry.TestHelper.parseMessageNumber("* -1"));

        assertEquals(123, ImapMailboxEntry.TestHelper.parseMessageNumber("* 123"));
        assertEquals(900, ImapMailboxEntry.TestHelper.parseMessageNumber("* 900"));
    }

    @Test
    public void parseFlags()
    {
        assertEquals(0, ImapMailboxEntry.parseFlags(null));
        assertEquals(0, ImapMailboxEntry.parseFlags(""));

        assertEquals(0, ImapMailboxEntry.parseFlags("FLAGS ()"));
        assertEquals(MailboxEntry.SEEN, ImapMailboxEntry.parseFlags("FLAGS (\\Seen)"));
        assertEquals(MailboxEntry.SEEN, ImapMailboxEntry.parseFlags("FLAGS (\\SEEN)"));
        assertEquals(MailboxEntry.SEEN | MailboxEntry.ANSWERED, ImapMailboxEntry.parseFlags("FLAGS (\\SEEN \\answered)"));
        assertEquals(MailboxEntry.FLAGGED | MailboxEntry.DRAFT, ImapMailboxEntry.parseFlags("FLAGS (\\Draft \\Flagged)"));
    }

    @Test
    public void parseEmptyEntry()
    {
        assertNull(ImapMailboxEntry.parseEntry(null));
        assertNull(ImapMailboxEntry.parseEntry(""));
    }

    @Test
    public void parseEntry()
    {
        String s =
            "* 1694 FETCH (" +
            "UID 6026 " +
            "RFC822.SIZE 9452 " +
            "InternalDate \"16-Apr-2010 12:46:27 +0000\" " +
            "FLAGS () " +
            "ENVELOPE (\"Fri, 16 Apr 2010 08:45:37 -0400\" " +
            "\"Re: [armedbear-devel] testing imap parsing\" " +
            "((\"Hello World\" NIL \"helloworld\" \"example.com\")) " +
            "((\"Hello World\" NIL \"helloworld\" \"example.com\")) " +
            "((\"Hello World\" NIL \"helloworld\" \"example.com\")) " +
            "((\"Bob Smith\" NIL \"bsmith\" \"example.com\")) " +
            "((NIL NIL \"armedbear-devel\" \"common-lisp.net\")) " +
            "NIL \"<4BC7FE25.6000809@panix.com>\" \"<2ip29af5e2d1004160545wcf087e8ay6ccdf94eddde96f8@mail.gmail.com>\") " +
            "BODY[HEADER.FIELDS (references)] {111}\n" +
            "References: <2ul29af5e2d1004151541rc2e7f74fpd93d6c615488b2f3@mail.gmail.com>\r\n" +
            " <4BC7FE25.6000809@panix.com>\r\n" +
            "\r\n" +
            ")";

        ImapMailboxEntry entry = ImapMailboxEntry.parseEntry(s);
        assertEquals(1694, entry.getMessageNumber());
        assertEquals(6026, entry.getUid());
        assertEquals(0, entry.getFlags());

        GregorianCalendar gcal = new GregorianCalendar(2010, 3, 16, 12, 46, 27);
        gcal.setTimeZone(TimeZone.getTimeZone("GMT+0000"));
        assertEquals(gcal.getTime(), entry.getArrival().getDate());
        assertEquals(9452, entry.getSize());

        gcal = new GregorianCalendar(2010, 3, 16, 8, 45, 37);
        gcal.setTimeZone(TimeZone.getTimeZone("GMT-0400"));
        assertEquals(gcal.getTime(), entry.getDate().getDate());

        assertEquals("Re: [armedbear-devel] testing imap parsing", entry.getSubject());

        assertEquals(1, entry.getFrom().length);
        assertEquals("Hello World", entry.getFrom()[0].getPersonal());
        assertEquals("helloworld@example.com", entry.getFrom()[0].getAddress());

        assertEquals(1, entry.getReplyTo().length);
        assertEquals("Hello World", entry.getReplyTo()[0].getPersonal());
        assertEquals("helloworld@example.com", entry.getReplyTo()[0].getAddress());

        assertEquals(1, entry.getTo().length);
        assertEquals("Bob Smith", entry.getTo()[0].getPersonal());
        assertEquals("bsmith@example.com", entry.getTo()[0].getAddress());

        assertEquals(1, entry.getCc().length);
        assertEquals(null, entry.getCc()[0].getPersonal());
        assertEquals("armedbear-devel@common-lisp.net", entry.getCc()[0].getAddress());

        assertEquals("<4BC7FE25.6000809@panix.com>", entry.getInReplyTo());
        assertEquals("<2ip29af5e2d1004160545wcf087e8ay6ccdf94eddde96f8@mail.gmail.com>", entry.getMessageId());

        assertEquals(2, entry.getReferences().length);
        assertEquals("<2ul29af5e2d1004151541rc2e7f74fpd93d6c615488b2f3@mail.gmail.com>", entry.getReferences()[0]);
        assertEquals("<4BC7FE25.6000809@panix.com>", entry.getReferences()[1]);
    }

    @Test
    public void parseQuotedAddress()
    {
        String s = "* 1951 FETCH (" +
            "UID 17117 " +
            "RFC822.SIZE 16657 " +
            "INTERNALDATE \"22-Jun-2011 17:31:09 +0000\" " +
            "FLAGS () " +
            "ENVELOPE (\"Wed, 22 Jun 2011 17:31:09 +0000\" " +
            "\"test quoting\" " +
            "((\"Hello World\" NIL \"helloworld\" \"example.com\")) " +
            "((\"Hello World\" NIL \"helloworld\" \"example.com\")) " +
            "((\"Hello World\" NIL \"helloworld\" \"example.com\")) " +
            "((\"Kevin Krouse\\\" <\" NIL \"kevin.krouse\" \"example.com\")) " +
            "NIL NIL NIL \"<20110622173106.967541.11013.5515002@sender.example.com>\") " +
            "BODY[HEADER.FIELDS (references)] {4}\n" +
            "\r\n" +
            "\r\n" +
            ")";

        ImapMailboxEntry entry = ImapMailboxEntry.parseEntry(s);
        assertEquals("Kevin Krouse\\\" <", entry.getTo()[0].getPersonal());
        assertEquals("kevin.krouse@example.com", entry.getTo()[0].getAddress());
    }

}
