/*
 * ImapURL.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.FastStringBuffer;

public final class ImapURL extends MailboxURL
{
    static final int DEFAULT_PORT = 143;
    static final int DEFAULT_SSL_PORT = 993;

    private String folderName;

    public ImapURL(String folderName, String user, String host, int port,
                   boolean ssl, boolean tls, boolean validateCert, boolean debug)
    {
        super(user, host, port, ssl, tls, validateCert, debug);
        this.folderName = folderName;

        if ("inbox".equalsIgnoreCase(this.folderName))
            this.folderName = "inbox";
    }

    public static ImapURL parseURL(String s) throws MalformedURLException
    {
        return (ImapURL)MailboxURL.parseRemote(s, "imap");
    }

    public final String getFolderName()
    {
        return folderName;
    }

    public final List<String> getFolderPathComponents()
    {
        ArrayList<String> list = new ArrayList<String>();
        int begin = 0;
        while (true) {
            int index = folderName.indexOf('/', begin);
            if (index < 0) {
                list.add(folderName.substring(begin));
                break;
            } else {
                list.add(folderName.substring(begin, index));
                begin = index + 1;
                if (begin >= folderName.length())
                    break;
            }
        }
        return list;
    }

    public boolean equals(Object object)
    {
        if (!(object instanceof ImapURL))
            return false;
        ImapURL url = (ImapURL) object;
        if (host != url.host) {
            if (host == null)
                return false;
            if (!host.equals(url.host))
                return false;
        }
        if (folderName != url.folderName) {
            if (folderName == null)
                return false;
            if (!folderName.equals(url.folderName))
                return false;
        }
        if (user != url.user) {
            if (user == null)
                return false;
            if (!user.equals(url.user))
                return false;
        }
        if (port != url.port)
            return false;
        if (ssl != url.ssl)
            return false;
        if (tls != url.tls)
            return false;
        if (validateCert != url.validateCert)
            return false;
        return true;
    }

    public String toString()
    {
        FastStringBuffer sb = new FastStringBuffer('{');
        if (user != null) {
            sb.append(user);
            sb.append('@');
        }
        sb.append(host);
        if (port != DEFAULT_PORT) {
            sb.append(':');
            sb.append(port);
        }
        sb.append('}');
        sb.append(folderName);
        return sb.toString();
    }

    public String getCanonicalName()
    {
        FastStringBuffer sb = baseCanonicalURL();
        sb.append(folderName);
        return sb.toString();
    }

    @Override
    protected int getDefaultPort(boolean ssl)
    {
        return ssl ? DEFAULT_SSL_PORT : DEFAULT_PORT;
    }
}
