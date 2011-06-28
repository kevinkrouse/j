/*
 * MailboxURL.java
 *
 * Copyright (C) 2002 Peter Graves
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
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j.mail;

import java.net.MalformedURLException;

import org.armedbear.j.FastStringBuffer;
import org.armedbear.j.Log;

public abstract class MailboxURL
{
    private String baseName;
    private String limitPattern;

    protected String user;
    protected String host;
    protected int port;
    protected boolean ssl = false;
    protected boolean tls = false;
    protected boolean validateCert = true;
    protected boolean debug = true;

    protected MailboxURL()
    {
    }
    
    protected MailboxURL(String user, String host, int port, boolean ssl, boolean tls, boolean validateCert, boolean debug)
    {
        this.user = user;
        this.host = host;
        this.port = port != -1 ? port : getDefaultPort(ssl);
        this.ssl = ssl;
        this.tls = tls;
        this.validateCert = validateCert;
        this.debug = debug;
    }

    public final String getBaseName()
    {
        return baseName;
    }

    public final void setBaseName(String baseName)
    {
        this.baseName = baseName;
    }

    public final String getLimitPattern()
    {
        return limitPattern;
    }

    public final void setLimitPattern(String limitPattern)
    {
        this.limitPattern = limitPattern;
    }

    public final String getUser()
    {
        return user;
    }

    public final void setUser(String user)
    {
        this.user = user;
    }

    public final String getHost()
    {
        return host;
    }

    public final int getPort()
    {
        return port;
    }

    public final boolean isSSL()
    {
        return ssl;
    }

    public final void setSSL(boolean ssl)
    {
        this.ssl = ssl;
    }

    public final boolean isTLS()
    {
        return tls;
    }

    public final void setTLS(boolean tls)
    {
        this.tls = tls;
    }

    public final boolean isDebug()
    {
        return debug;
    }

    public final void setDebug(boolean debug)
    {
        this.debug = debug;
    }

    public static MailboxURL parse(String input)
    {
        if (input == null)
            return null;
        input = input.trim();
        if (input.length() == 0)
            return null;
        String baseName;
        String limitPattern = null;
        if (input.charAt(0) == '"') {
            int index = input.indexOf('"', 1);
            if (index < 0)
                return null;
            baseName = input.substring(1, index);
            if (index < input.length()-1)
                limitPattern = input.substring(index+1).trim();
        } else if (input.charAt(0) == '{') {
            int index = input.indexOf('}', 1);
            if (index < 0)
                return null;
            int space = input.indexOf(' ', index);
            if (space >= index) {
                baseName = input.substring(0, space);
                limitPattern = input.substring(space).trim();
            } else
                baseName = input;
        } else {
            int index = input.indexOf(' ');
            if (index >= 0) {
                baseName = input.substring(0, index);
                limitPattern = input.substring(index).trim();
            } else
                baseName = input;
        }
        if (baseName.length() == 0)
            return null;
        MailboxURL url = null;
        try {
            if (baseName.charAt(0) == '{') {
                // IMAP or POP.
                url = parseRemote(baseName, "imap");
            } else if (baseName.startsWith("pop://")) {
                // POP.
                url = PopURL.parseURL(baseName);
            } else {
                // Local.
                url = LocalMailboxURL.parseURL(baseName);
            }
        }
        catch (MalformedURLException e) {
            Log.error(e);
        }
        if (url != null) {
            url.setBaseName(baseName);
            url.setLimitPattern(limitPattern);
        }
        return url;
    }

    // Parses the <remote-specification> part of mailbox urls using UW Alpine/Pine format:
    // http://www.washington.edu/alpine/tech-notes/config-notes.html#server-name-syntax
    //
    // [{<remote-specification>}][#<namespace>]<namespace-prefix-part>
    //
    // where <remote-specification> is of the form:
    //
    // "user name"[@hostname[:port][/options]]
    // [user]@hostname[:port][/options]
    //
    // After the hostname and port, the following options can be specified:
    //   /user=username   - user name to use for authentication
    //   /tls             - require STARTTLS to encrpyt the session.  If not set, TLS will be attempted, but fallback on sending the password in plain text.
    //   /ssl             - use SSL to encrypt the session
    //   /novalidate-cert - don't validate the certificate for TLS/SSL connections (for self-signed certs)
    //   /debug           - extra logging
    //   /imap            - use imap; the default
    //   /nntp            - use nntp
    //   /pop3            - use pop3
    static MailboxURL parseRemote(String s, String defaultType) throws MalformedURLException
    {
        String type = defaultType;
        String user = null;
        String host = null;
        int port = -1;
        boolean tls = false, ssl = false, validateCert = true, debug = true;
        String remaining = null;

        if (s == null || s.length() == 0)
            throw new MalformedURLException("Null or empty URL");
        if (!s.startsWith("{"))
            throw new MalformedURLException("Expected mailbox URL to start with '{'");
        int index = s.indexOf('}');
        if (index < 0)
            throw new MalformedURLException("Expected mailbox URL to contain '}'");
        remaining = s.substring(index + 1);
        s = s.substring(1, index);

        // The user name may be enclosed in quotes.
        if (s.length() > 0 && s.charAt(0) == '"') {
            index = s.indexOf('"', 1);
            if (index >= 0) {
                user = s.substring(1, index);
                s = s.substring(index + 1);
            } else
                throw new MalformedURLException();

            // Check if no host or other options specified.
            if (s.length() == 0) {
                host = "127.0.0.1";
                return createURL(type, remaining, user, host, port, ssl, tls, validateCert, debug);
            }
        } else {
            index = s.indexOf('@');
            if (index >= 0) {
                user = s.substring(0, index);
                s = s.substring(index);
            }
        }

        if (s.charAt(0) != '@')
            throw new MalformedURLException();
        s = s.substring(1); // Skip '@'.

        if ((index = s.indexOf(':')) >= 0 || (index = s.indexOf('/')) >= 0) {
            // Get host name up to port or connection options
            host = s.substring(0, index);

            // Get port
            index = s.indexOf(':');
            if (index >= 0) {
                int digit = index+1;
                while (digit < s.length() && Character.isDigit(s.charAt(digit))) {
                    digit++;
                }
                try {
                    port = Integer.parseInt(s.substring(index + 1, digit));
                }
                catch (Exception e) {
                    throw new MalformedURLException();
                }
                s = s.substring(digit);
            }

            // Get connection options from the end
            while ((index = s.lastIndexOf("/")) >= 0) {
                if (s.startsWith("/user=", index)) {
                    if (user != null)
                        Log.info("Username specified twice in mailbox url");
                    user = s.substring(index + "/user=".length());
                }
                else if (s.startsWith("/pop3", index) || s.startsWith("/service=pop3", index))
                    type = "pop3";
                else if (s.startsWith("/nntp", index) || s.startsWith("/service=nntp", index))
                    type = "nntp";
                else if (s.startsWith("/imap", index) || s.startsWith("/service=imap", index))
                    type = "imap";
                else if (s.startsWith("/tls", index))
                    tls = true;
                else if (s.startsWith("/ssl", index))
                    ssl = true;
                else if (s.startsWith("/novalidate-cert", index))
                    validateCert = false;
                else if (s.startsWith("/debug", index))
                    debug = true;
                else
                    Log.info("skipped option: " + s.substring(index));
                s = s.substring(0, index);
            }
        } else {
            // No port or options; what's left is the hostname.
            host = s;
        }

        return createURL(type, remaining, user, host, port, ssl, tls, validateCert, debug);
    }

    private static MailboxURL createURL(String type, String remaining,
                                        String user, String host, int port,
                                        boolean ssl, boolean tls, boolean validateCert, boolean debug)
            throws MalformedURLException
    {
        if (type.equals("imap"))
            return new ImapURL(remaining, user, host, port, ssl, tls, validateCert, debug);
        else if (type.equals("pop3"))
            return new PopURL(user, host, port, ssl, tls, validateCert, debug);
        else if (type.equals("smtp"))
            return new SmtpURL(user, host, port, ssl, tls, validateCert, debug);

        throw new MalformedURLException("type not yet supported: '" + type + "'");
    }

    public abstract String getCanonicalName();

    protected FastStringBuffer baseCanonicalURL()
    {
        FastStringBuffer sb = new FastStringBuffer('{');
        if (user != null)
            sb.append(user);
        else
            sb.append(System.getProperty("user.name"));
        sb.append('@');
        sb.append(host);
        sb.append(':');
        sb.append(port);
        if (ssl)
            sb.append("/ssl");
        if (tls)
            sb.append("/tls");
        if (!validateCert)
            sb.append("/novalidate-cert");
        sb.append('}');
        return sb;
    }

    protected abstract int getDefaultPort(boolean ssl);
}
