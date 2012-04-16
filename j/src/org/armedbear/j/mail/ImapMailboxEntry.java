/*
 * ImapMailboxEntry.java
 *
 * Copyright (C) 2000-2007 Peter Graves <peter@armedbear.org>
 * $Id$
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.armedbear.j.mail;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.TimeZone;
import org.armedbear.j.FastStringBuffer;
import org.armedbear.j.Headers;
import org.armedbear.j.IntStringPair;
import org.armedbear.j.Log;
import org.armedbear.j.StringPair;

/*package*/ final class ImapMailboxEntry extends MailboxEntry implements Serializable
{
  private transient ImapMailbox mailbox;

  private int uid;
  private RFC822Date arrival;

  private ImapMailboxEntry()
  {
  }

  // For testing only!
  /*package*/ ImapMailboxEntry(int uid)
  {
    this.uid = uid;
  }

  public final ImapMailbox getMailbox()
  {
    return mailbox;
  }

  public final void setMailbox(ImapMailbox mailbox)
  {
    this.mailbox = mailbox;
  }

  public final int getUid()
  {
    return uid;
  }

  public final RFC822Date getArrival()
  {
    return arrival;
  }

  private static final String UID_START = "UID ";
  private static final String INTERNALDATE_START = "INTERNALDATE ";
  private static final String RFC822_SIZE_START = "RFC822.SIZE ";
  private static final String FLAGS_START = "FLAGS ";
  private static final String ENVELOPE_START = "ENVELOPE ";
  private static final String BODY_START = "BODY[";

    public static ImapMailboxEntry parseEntry(String s)
    {
        ImapMailboxEntry entry = new ImapMailboxEntry();
        entry.messageNumber = parseMessageNumber(s);
        if (entry.messageNumber < 1) {
            Log.error("can't parse message number");
            return null;
        }

        int index = s.indexOf('(');
        s = s.substring(index+1);
        while (s != null && s.length() > 0)
        {
            s = skipWhitespace(s);
            if (test(s, ")"))
                break;

            if (test(s, UID_START)) {
                s = readUid(entry, s);
            }
            else if (test(s, RFC822_SIZE_START)) {
                s = readRFC822Size(entry, s);
            }
            else if (test(s, INTERNALDATE_START)) {
                s = readInternalDate(entry, s);
            }
            else if (test(s, FLAGS_START)) {
                s = readFlags(entry, s);
            }
            else if (test(s, ENVELOPE_START)) {
                s = readEnvelope(entry, s);
            }
            else if (test(s, BODY_START)) {
                s = readBodyHeaders(entry, s);
            }
            else {
                Log.error("Unexpected text: " + s);
                break;
            }
        }

        // The read functions return null if parsing failed. Error has already been logged.
        if (s == null)
            return null;

        if (entry.uid <= 0) {
            Log.error("didn't find UID");
            return null;
        }

        if (entry.arrival == null) {
            Log.error("didn't find INTERNALDATE");
            return null;
        }

        if (entry.size <= 0) {
            Log.error("didn't find RFC822.SIZE");
            return null;
        }

        if (entry.date == null) {
            Log.error("didn't find ENVELOPE date");
            return null;
        }

        if (entry.subject == null) {
            Log.error("didn't find ENVELOPE subject");
            return null;
        }

        return entry;
    }

    private static String skipWhitespace(String s)
    {
        int i;
        for (i = 0; i < s.length(); i++)
            if (!Character.isWhitespace(s.charAt(i)))
                break;
        return s.substring(i);
    }

    // Case-insensitive prefix match
    private static boolean test(String s, String test)
    {
        if (s.length() < test.length())
            return false;

        for (int i = 0; i < test.length(); i++) {
            if (Character.toUpperCase(s.charAt(i)) != Character.toUpperCase(test.charAt(i)))
                return false;
        }

        return true;
    }

    private static String match(String s, String test)
    {
        if (test(s, test)) {
            return s.substring(test.length());
        }

        Log.error("failed to match '" + test + "'");
        return null;
    }

    private static String readUid(ImapMailboxEntry entry, String s)
    {
        IntStringPair p = _parseUid(s);
        if (p == null || p.first < 1) {
            Log.error("can't parse UID");
            return null;
        }
        entry.uid = p.first;
        return p.second;
    }

    private static String readRFC822Size(ImapMailboxEntry entry, String s)
    {
        s = match(s, RFC822_SIZE_START);
        IntStringPair p = parseNumber(s);
        if (p == null || p.first < 1) {
            Log.error("can't parse RFC822.SIZE");
            return null;
        }
        entry.size = p.first;
        return p.second;
    }

    private static String readInternalDate(ImapMailboxEntry entry, String s)
    {
        s = match(s, INTERNALDATE_START);
        StringPair p = parseQuoted(s);
        if (p == null || p.first.length() == 0) {
            Log.error("can't parse INTERNALDATE");
            return null;
        }
        entry.arrival = parseInternalDate(p.first.trim());
        return p.second;
    }

    private static String readFlags(ImapMailboxEntry entry, String s)
    {
        s = match(s, FLAGS_START);
        IntStringPair p = _parseFlags(s);
        if (p == null) {
            Log.error("can't parse FLAGS");
            return null;
        }
        entry.flags = p.first;
        return p.second;
    }

    private static String readEnvelope(ImapMailboxEntry entry, String s)
    {
        String remaining = match(s, ENVELOPE_START);

        // Next field is date (quoted string).
        StringPair p = parseQuoted(remaining);
        if (p == null) {
            Log.error("can't parse date");
            return null;
        }
        entry.date = RFC822Date.parseDate(p.first);
        remaining = p.second;

        // Next field is subject (quoted string).
        p = parseQuoted(remaining);
        if (p == null) {
            Log.error("can't parse subject");
            return null;
        }
        entry.subject = parseSubject(p.first);
        remaining = p.second;

        // Next field is "From" (parenthesized list).
        p = parseParenthesizedList(remaining);
        if (p == null) {
            Log.error("can't parse \"From\" list");
            return null;
        }
        if (p.first != null)
            entry.from = parseAddressList(p.first);
        remaining = p.second;

        // Sender
        p = parseParenthesizedList(remaining);
        if (p == null) {
            Log.error("can't parse \"Sender\" list");
            return null;
        }
        remaining = p.second;

        // Reply-To
        p = parseParenthesizedList(remaining);
        if (p == null) {
            Log.error("can't parse \"Reply-To\" list");
            return null;
        }
        if (p.first != null)
            entry.replyTo = parseAddressList(p.first);
        remaining = p.second;

        // To
        p = parseParenthesizedList(remaining);
        if (p == null) {
            Log.error("can't parse \"To\" list");
            return null;
        }
        if (p.first != null)
            entry.to = parseAddressList(p.first);
        remaining = p.second;

        // Cc
        p = parseParenthesizedList(remaining);
        if (p == null) {
            Log.error("can't parse \"Cc\" list");
            return null;
        }
        if (p.first != null)
            entry.cc = parseAddressList(p.first);
        remaining = p.second;

        // Bcc
        p = parseParenthesizedList(remaining);
        if (p == null) {
            Log.error("can't parse \"Bcc\" list");
            return null;
        }
        remaining = p.second;

        // In-Reply-To (quoted string)
        p = parseQuoted(remaining);
        if (p == null) {
            Log.error("can't parse \"In-Reply-To\"");
            return null;
        }
        entry.inReplyTo = parseInReplyTo(p.first);
        remaining = p.second;

        // Message-ID
        p = parseQuoted(remaining);
        if (p == null) {
            Log.error("can't parse \"Message-ID\"");
            return null;
        }
        entry.messageId = p.first;
        remaining = p.second;

        // final closing ')'
        return match(remaining, ")");
    }

    private static String readBodyHeaders(ImapMailboxEntry entry, String s)
    {
        String remaining = match(s, BODY_START);
        int end = remaining.indexOf("]");
        StringPair p = parseQuoted(remaining.substring(end + "]".length()));
        if (p == null) {
            Log.error("can't parse BODY headers");
            return null;
        }

        Headers headers = Headers.parse(p.first);
        if (headers != null) {
            String refs = headers.getValue(Headers.REFERENCES);
            if (refs != null)
                entry.references = parseReferences(refs.trim());
        }

        // final closing ')'
        return match(p.second, ")");
    }

    private static IntStringPair parseNumber(String s)
    {
        final int limit = s.length();
        int i;
        for (i = 0; i < limit; i++) {
            if (!Character.isDigit(s.charAt(i)))
                break;
        }
        if (i == 0) // No digit found.
            return null;

        try
        {
            int num = Integer.parseInt(s.substring(0, i));
            return new IntStringPair(num, s.substring(i));
        }
        catch (NumberFormatException e) {
            Log.error(e);
            return null;
        }
    }

    private static String parseSubject(String subject)
    {
        subject = subject == null ? "" : RFC2047.decode(subject);
        if (subject.indexOf('\\') >= 0)
        {
            // strip out escape chars
            String temp = subject;
            final int limit = temp.length();
            FastStringBuffer sb = new FastStringBuffer();
            boolean escaped = false;
            for (int i = 0; i < limit; i++)
            {
                char c = temp.charAt(i);
                if (escaped)
                {
                    sb.append(c);
                    escaped =false;
                }
                else
                {
                    // not escaped
                    if (c == '\\')
                        escaped = true;
                    else
                        sb.append(c);
                }
            }
            subject = sb.toString();
        }
        return subject;
    }

  private static int parseMessageNumber(String s)
  {
    if (s == null)
      return 0; // Error.
    final int length = s.length();
    if (length < 2)
      return 0; // Error.
    // String must start with "* ".
    if (s.charAt(0) != '*' || s.charAt(1) != ' ')
      return 0; // Error.
    FastStringBuffer sb = new FastStringBuffer();
    for (int i = 2; i < length; i++)
      {
        char c = s.charAt(i);
        if (c >= '0' && c <= '9')
          sb.append(c);
        else
          break;
      }
    try
      {
        return Integer.parseInt(sb.toString());
      }
    catch (NumberFormatException e)
      {
        Log.error(e);
        return 0;
      }
  }

    public static int parseUid(String s)
    {
        final int length = s.length();
        if (length < 2)
            return 0; // Error.
        // String must start with "* ".
        if (s.charAt(0) != '*' || s.charAt(1) != ' ')
            return 0; // Error.
        IntStringPair p = _parseUid(s);
        if (p == null)
            return 0;
        return p.first;
    }

    private static IntStringPair _parseUid(String s)
    {
        int index = s.indexOf(UID_START);
        if (index < 0)
            return null;
        return parseNumber(s.substring(index + UID_START.length()));
    }

    public static int parseFlags(String s)
    {
        if (s == null || s.length() == 0)
            return 0;

        int flags = 0;
        final int index = s.indexOf(FLAGS_START);
        if (index >= 0) {
            IntStringPair p = _parseFlags(s.substring(index));
            if (p != null)
                return p.first;
        }
        return flags;
    }

    private static IntStringPair _parseFlags(String s)
    {
        StringPair p = parseParenthesized(s);
        if (p == null)
            return null;

        int flags = 0;
        if (p.first != null)
        {
            String flagsList = p.first.toLowerCase();
            if (flagsList.length() > 0)
            {
                if (flagsList.indexOf("seen") >= 0)
                    flags |= SEEN;
                if (flagsList.indexOf("answered") >= 0)
                    flags |= ANSWERED;
                if (flagsList.indexOf("recent") >= 0)
                    flags |= RECENT;
                if (flagsList.indexOf("deleted") >= 0)
                    flags |= DELETED;
                if (flagsList.indexOf("flagged") >= 0)
                    flags |= FLAGGED;
                if (flagsList.indexOf("draft") >= 0)
                    flags |= DRAFT;
                if (flagsList.indexOf("nonjunk") >= 0 || flagsList.indexOf("notjunk") >= 0 ||
                    flagsList.indexOf("nonspam") >= 0 || flagsList.indexOf("notspam") >= 0)
                    flags |= NON_JUNK;
                else if (flagsList.indexOf("junk") >= 0 || flagsList.indexOf("spam") >= 0)
                    flags |= JUNK;
            }
        }
        return new IntStringPair(flags, p.second);
    }


  private static StringPair parseQuoted(String s)
  {
    s = s.trim();
    final int slen = s.length();
    if (slen == 0)
      return null;
    String quoted = null;
    String remaining = null;
    if (s.charAt(0) == '{')
      {
        int end = s.indexOf('}', 1);
        if (end < 0)
          {
            Log.error("parseQuoted: bad literal");
            return null;
          }
        int length = 0;
        try
          {
            length = Integer.parseInt(s.substring(1, end));
          }
        catch (NumberFormatException e)
          {
            Log.error(e);
          }
        if (length == 0)
          {
            Log.error("parseQuoted: length of literal is zero");
            return null;
          }
        int begin = s.indexOf('\n', end + 1);
        if (begin < 0)
          {
            Log.error("parseQuoted: no LF after literal");
            return null;
          }
        ++begin; // Skip LF.
        end = begin + length;
        if (end > slen)
          {
            Log.error("parseQuoted end > slen");
            return null;
          }
        quoted = s.substring(begin, end);
        remaining = s.substring(end);
      }
    else if (s.startsWith("NIL"))
      {
        quoted = null;
        remaining = s.substring(3).trim();
      }
    else
      {
        final int begin = s.indexOf('"');
        if (begin < 0)
          return null;
        int end = begin + 1;
        while (end < slen)
          {
            char c = s.charAt(end);
            if (c == '\\')
              {
                if (end < slen - 1)
                  ++end;
                else
                  return null; // REVIEW
              }
            else if (c == '"')
              break;
            ++end;
          }
        if (end == slen)
          // reached end of string without closing quote
          return null;
        quoted = s.substring(begin + 1, end);
        remaining = s.substring(end + 1);
      }
    return new StringPair(quoted, remaining);
  }

  private static StringPair parseParenthesized(String s)
  {
    int begin = s.indexOf('(');
    if (begin < 0)
      return null;
    int end = -1;
    final int limit = s.length();
    boolean inQuote = false;
    char quoteChar = '\0';
    for (int i = begin + 1; i < limit; i++)
      {
        char c = s.charAt(i);
        if (inQuote)
          {
            if (c == quoteChar && s.charAt(i-1) != '\\')
              inQuote = false;
          }
        else
          {
            // Not in quote.
            if (c == '"' || c == '\'')
              {
                inQuote = true;
                quoteChar = c;
              }
            else if (c == ')')
              {
                end = i;
                break;
              }
          }
      }
    if (end < 0)
      return null;
    String parenthesized = s.substring(begin + 1, end);
    String remaining = s.substring(end + 1);
    return new StringPair(parenthesized, remaining);
  }

  static private StringPair parseParenthesizedList(String s)
  {
    s = s.trim();
    if (s.startsWith("NIL"))
      return new StringPair(null, s.substring(3).trim());
    final int begin = s.indexOf("((");
    if (begin < 0)
      return null;
    int end = -1;
    final int slen = s.length();
    boolean in_quote = false;
    char quote_char = '\0';
    for (int i = begin + 2; i < slen; i++)
      {
        char c = s.charAt(i);
        if (in_quote)
          {
            if (c == quote_char && s.charAt(i-1) != '\\')
                in_quote = false;
          }
        else
          {
            // not in quote
            if (c == '"' || c == '\'')
              {
                in_quote = true;
                quote_char = c;
              }
            else if (c == ')' && i < slen - 1 && s.charAt(i + 1) == ')')
              {
                end = i;
                break;
              }
          }
      }
    if (end < 0)
      return null;
    String list = s.substring(begin, end + 2);
    String remaining = s.substring(end + 2);
    return new StringPair(list, remaining);
  }

  private static MailAddress[] parseAddressList(String list)
  {
    if (list == null)
      return null;
    ArrayList addresses = new ArrayList();
    String remaining = list.substring(1, list.length() - 1);
    while (remaining.length() > 0)
      {
        StringPair p = parseParenthesized(remaining);
        if (p == null)
          {
            Log.error("parseAddressList error");
            Log.error("list = |" + list + "|");
            Log.error("remaining = |" + remaining + "|");
            return null;
          }
        String s = p.first; // The address.
        MailAddress address = parseAddress(s);
        if (address == null)
          {
            Log.error("**** parseAddress returned null");
            Log.error("s = |" + s + "|");
          }
        if (address != null)
          addresses.add(address);
        remaining = p.second;
      }
    if (addresses.size() == 0)
      return null;
    MailAddress[] array = new MailAddress[addresses.size()];
    return (MailAddress[]) addresses.toArray(array);
  }

  private static MailAddress parseAddress(String s)
  {
    StringPair p = parseQuoted(s);
    if (p == null) // Error.
      return null;
    String encodedPersonal = p.first;
    String remaining = p.second;
    p = parseQuoted(remaining);
    if (p == null) // Error.
      return null;
    String sourceRoute = p.first;
    remaining = p.second;
    p = parseQuoted(remaining);
    if (p == null) // Error.
      return null;
    String mailName = p.first;
    remaining = p.second;
    p = parseQuoted(remaining);
    if (p == null) // Error.
      return null;
    String domainName = p.first;
    remaining = p.second;
    if (remaining.length() > 0)
      Log.error("**** parseAddress: unexpected string remaining ****");
    return new MailAddress(encodedPersonal, mailName + '@' + domainName);
  }

  private static SimpleDateFormat internalDateFormat = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss");

  private static RFC822Date parseInternalDate(String internalDate)
  {
    Date date = null;
    int index = internalDate.indexOf(' ');
    if (index >= 0)
      {
        index = internalDate.indexOf(' ', index+1);
        if (index >= 0)
          {
            String dateString = internalDate.substring(0, index);
            String timeZone = internalDate.substring(index + 1);
            TimeZone tz = TimeZone.getTimeZone("GMT" + timeZone);
            if (tz != null)
              internalDateFormat.setTimeZone(tz);
            try
              {
                date = internalDateFormat.parse(dateString);
              }
            catch (Throwable t)
              {
                Log.error(t);
              }
          }
      }
    return new RFC822Date(date);
  }

  /** Exposes private methods for testing. */
  static final class TestHelper {
      static int parseMessageNumber(String s) {
          return ImapMailboxEntry.parseMessageNumber(s);
      }
  }
}
