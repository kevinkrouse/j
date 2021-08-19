/*
 * Copyright (C) 2010 Kevin Krouse
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

// SMTP URL isn't really a mailbox URL, but they share most properties
public class SmtpURL extends MailboxURL
{
    private static final int DEFAULT_PORT = 25;
    private static final int DEFAULT_SSL_PORT = 465;

    public SmtpURL(String user, String host, int port,
                   boolean ssl, boolean tls, boolean validateCert, boolean debug)
    {
        super(user, host, port, ssl, tls, validateCert, debug);
    }

    public static SmtpURL parseURL(String s) throws MalformedURLException
    {
        return (SmtpURL)MailboxURL.parseRemote(s, "smtp");
    }
    
    @Override
    public String getCanonicalName()
    {
        return baseCanonicalURL().toString();
    }

    @Override
    protected int getDefaultPort(boolean ssl)
    {
        return ssl ? DEFAULT_SSL_PORT : DEFAULT_PORT;
    }
}
