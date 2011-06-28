/*
 * SmtpSession.java
 *
 * Copyright (C) 2000-2003 Peter Graves
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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import org.armedbear.j.Debug;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.FastStringBuffer;
import org.armedbear.j.Log;
import org.armedbear.j.MessageDialog;
import org.armedbear.j.Netrc;
import org.armedbear.j.Property;
import org.armedbear.j.Utilities;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class SmtpSession extends Writer
{
    private static final int DEFAULT_PORT = 25;
    //private static final int LEGACY_TLS_PORT = 465;
    private static final int DEFAULT_TLS_PORT = 587;

    private final SmtpURL url;
    private final String user;
    private final String password;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private boolean connected;
    private String errorText;
    private String responseText;
    private boolean echo;

    private SmtpSession(SmtpURL url, String user, String password)
    {
        this.url = url;
        this.user = user;
        this.password = password;
        this.echo = url.isDebug();
    }

    public final void setEcho(boolean b)
    {
        echo = b;
    }

    public final String getHost()
    {
        return url.getHost();
    }

    public final int getPort()
    {
        return url.getPort();
    }

    // Returns session with connection already established (or null).
    public static SmtpSession getDefaultSession()
    {
        return getSession(Editor.preferences().getStringProperty(Property.SMTP));
    }

    // Returns session with connection already established (or null).
    // [username[:password]@]host[:port][/user=username][/tls]
    public static SmtpSession getSession(String server)
    {
        if (server == null)
            return null;

        SmtpURL url;
        try {
            url = SmtpURL.parseURL(server);
        }
        catch (MalformedURLException e) {
            Log.error(e);
            FastStringBuffer sb = new FastStringBuffer();
            sb.append("Unable to parse SMTP server name \"");
            sb.append(server);
            sb.append('"');
            MessageDialog.showMessageDialog(sb.toString(), "Error");
            return null;
        }

        return getSession(url);
    }

    public static SmtpSession getSession(SmtpURL url)
    {
        String user = url.getUser();
        if (user == null || user.length() == 0)
            user = System.getProperty("user.name");
        return getSession(url, user);
    }

    public static SmtpSession getSession(SmtpURL url, String user)
    {
        String password = Netrc.getPassword(url.getHost(), user);
        if (password == null)
            return null;
        return getSession(url, user, password);
    }

    public static SmtpSession getSession(SmtpURL url, String user, String password)
    {
        SmtpSession session = new SmtpSession(url, user, password);
        Debug.assertTrue(session != null);
        session.setEcho(true);
        if (!session.connect())
            return null;
        session.setEcho(url.isDebug());
        return session;
    }

    public final String getErrorText()
    {
        return errorText;
    }

    public boolean sendMessage(SendMail sm, File messageFile)
    {
        List addressees = sm.getAddressees();
        if (addressees == null || addressees.size() == 0)
            return false;
        if (!connect())
            return false;
        try {
            setEcho(true);
            FastStringBuffer sb = new FastStringBuffer("mail from:<");
            sb.append(sm.getFromAddress());
            sb.append('>');
            writeLine(sb.toString());
            if (getResponse() != 250)
                return false;
            for (int i = 0; i < addressees.size(); i++) {
                String addressee = (String) addressees.get(i);
                String addr = sm.getAddress(addressee);
                if (addr == null) {
                    errorText = "Invalid addressee \"" + addressee + "\"";
                    return false;
                }
                sb.setText("rcpt to:<");
                sb.append(addr);
                sb.append('>');
                writeLine(sb.toString());
                if (getResponse() != 250) {
                    errorText = "Address not accepted \"" + addr + "\"";
                    return false;
                }
            }
            writeLine("data");
            if (getResponse() != 354)
                return false;
            setEcho(false);
            BufferedReader messageFileReader = new BufferedReader(new InputStreamReader(messageFile.getInputStream()));
            String s;
            while ((s = messageFileReader.readLine()) != null)
                writeLine(s);
            setEcho(true);
            writeLine(".");
            if (getResponse() != 250)
                return false;
            quit();
        }
        catch (Throwable t) {
            Log.error(t);
        }
        finally {
            setEcho(false);
            disconnect();
        }
        // Add addressees to address book.
        AddressBook addressBook = AddressBook.getGlobalAddressBook();
        for (int i = 0; i < addressees.size(); i++) {
            String addressee = (String) addressees.get(i);
            MailAddress a = MailAddress.parseAddress(addressee);
            if (a != null) {
                addressBook.maybeAddMailAddress(a);
                addressBook.promote(a);
            }
        }
        AddressBook.saveGlobalAddressBook();
        return true;
    }

    public boolean connect()
    {
        if (connected)
            return true;
        Log.debug("connecting to port " + getPort() + " on " + getHost() + " ...");
        try {
            socket = new Socket(getHost(), getPort());
        }
        catch (UnknownHostException e) {
            errorText = "Unknown SMTP server " + getHost();
            return false;
        }
        catch (NoRouteToHostException e) {
            errorText = "No route to SMTP server " + getHost();
            return false;
        }
        catch (ConnectException e) {
            errorText = "Connection refused by SMTP server " + getHost();
            return false;
        }
        catch (IOException e) {
            Log.error(e);
            errorText = e.toString();
            return false;
        }
        try {
            reader =
                new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer =
                new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            getResponse();
            writeLine("EHLO " + InetAddress.getLocalHost().getHostAddress());
            if (getResponse() == 250) {
                if (url.isTLS()) {
                    writeLine("STARTTLS");
                    if (getResponse() == 220) {
                        startTLS();
                        writeLine("EHLO " + InetAddress.getLocalHost().getHostAddress());
                        if (getResponse() == 250) {
                            if (authenticate())
                                connected = true;
                        }
                    }
                }
                else {
                    if (authenticate())
                        connected = true;
                }
            }
        }
        catch (IOException e) {
            Log.error(e);
        }
        return connected;
    }

    private boolean startTLS()
    {
        Log.debug("staring TLS");
        SSLSocketFactory sf = (SSLSocketFactory)SSLSocketFactory.getDefault();
        try {
            SSLSocket sslsocket = (SSLSocket)sf.createSocket(this.socket, getHost(), getPort(), true);
            // XXX: check certificates
            // XXX: set protocols and cyphers
            sslsocket.startHandshake();
            Log.debug("TLS handshake successful");
            socket = sslsocket;
            reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer =
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            return true;
        } catch (IOException e) {
            Log.error(e);
            errorText = e.toString();
            return false;
        }
    }

    // Use "PLAIN" authentication
    // UNDONE: support for "LOGIN", "MD5", "NTLM"
    private boolean authenticate() throws IOException
    {
        if (user == null && password == null) {
            Log.debug("no credentials, not authenticating");
            return true;
        }

        Log.debug("authenticating");
        writeLine("AUTH PLAIN");

        // A response of 530 indicates the server wants us to use TLS
        int response = getResponse();
        if (response == 530) {
            startTLS();
            writeLine("AUTH PLAIN");
            response = getResponse();
        }

        // Send the credentials
        if (response == 334) {
            FastStringBuffer sb = new FastStringBuffer();
            sb.append('\0');
            sb.append(user);
            sb.append('\0');
            sb.append(password);

            String b64encoded = Base64Encoder.encode(sb.toString());
            writeLine(b64encoded);
            if (235 == getResponse())
                return true;

            errorText = "Authentication failed: " + responseText;
            return false;
        }

        errorText = "SMTP server doesn't support PLAIN authentication";
        return false;
    }

    public void quit()
    {
        setEcho(true);
        writeLine("QUIT");
        getResponse();
        setEcho(false);
        disconnect();
    }

    public synchronized void disconnect()
    {
        if (connected) {
            try {
                socket.close();
            }
            catch (IOException e) {
                Log.error(e);
            }
            socket = null;
            reader = null;
            writer = null;
            connected = false;
        }
    }

    public int getResponse()
    {
        responseText = "";
        while (true) {
            String s = readLine();
            if (s == null)
                break;
            if (s.length() < 4)
                break;
            if (s.charAt(3) == ' ') {
                responseText = s;
                try {
                    return Utilities.parseInt(s);
                }
                catch (NumberFormatException e) {
                    Log.error(e);
                }
                break;
            }
        }
        return 0;
    }

    private String readLine()
    {
        try {
            String s = reader.readLine();
            if (echo && s != null)
                Log.debug("<== " + s);
            return s;
        }
        catch (IOException e) {
            Log.error(e);
            return null;
        }
    }

    public void write(int c) throws IOException
    {
        writer.write(c);
    }

    public void write(char[] chars) throws IOException
    {
        writer.write(chars);
    }

    public void write(char[] chars, int offset, int length) throws IOException
    {
        writer.write(chars, offset, length);
    }

    public void write(String s) throws IOException
    {
        writer.write(s);
    }

    public void write(String s, int offset, int length) throws IOException
    {
        writer.write(s, offset, length);
    }

    public void flush() throws IOException
    {
        writer.flush();
    }

    public void close() throws IOException
    {
        writer.close();
    }

    public boolean writeLine(String s)
    {
        if (echo)
            Log.debug("==> " + s);
        try {
            writer.write(s);
            writer.write("\r\n");
            writer.flush();
            return true;
        }
        catch (IOException e) {
            Log.error(e);
            return false;
        }
    }
}
