/*
 * SmtpSession.java
 *
 * Copyright (C) 2000-2003 Peter Graves
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
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.armedbear.j.Debug;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Log;
import org.armedbear.j.MessageDialog;
import org.armedbear.j.Netrc;
import org.armedbear.j.Property;
import org.armedbear.j.util.Tls;
import org.armedbear.j.util.Utilities;

public final class SmtpSession extends Writer {
    private static final int TIMEOUT = 60000; // milliseconds

    private final SmtpURL url;
    private final String user;
    private final String password;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private boolean connected;
    private String errorText;
    private String responseText;
    private final List<String> responseLines = new ArrayList<String>();
    private boolean echo;

    private SmtpSession(SmtpURL url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.echo = url.isDebug();
    }

    public final void setEcho(boolean b) {
        echo = b;
    }

    public final String getHost() {
        return url.getHost();
    }

    public final int getPort() {
        return url.getPort();
    }

    // Returns session with connection already established (or null).
    public static SmtpSession getDefaultSession() {
        return getSession(Editor.preferences().getStringProperty(Property.SMTP));
    }

    // Returns session with connection already established (or null).
    // [username[:password]@]host[:port][/user=username][/tls]
    public static SmtpSession getSession(String server) {
        if (server == null)
            return null;

        SmtpURL url;
        try {
            url = SmtpURL.parseURL(server);
        }
        catch (MalformedURLException e) {
            Log.error(e);
            StringBuilder sb = new StringBuilder();
            sb.append("Unable to parse SMTP server name \"");
            sb.append(server);
            sb.append('"');
            MessageDialog.showMessageDialog(sb.toString(), "Error");
            return null;
        }

        return getSession(url);
    }

    public static SmtpSession getSession(SmtpURL url) {
        String user = url.getUser();
        if (user == null || user.length() == 0)
            user = System.getProperty("user.name");
        return getSession(url, user);
    }

    public static SmtpSession getSession(SmtpURL url, String user) {
        String password = Netrc.getPassword(url.getHost(), user);
        if (password == null)
            return null;
        return getSession(url, user, password);
    }

    public static SmtpSession getSession(SmtpURL url, String user, String password) {
        SmtpSession session = new SmtpSession(url, user, password);
        Debug.assertTrue(session != null);
        session.setEcho(true);
        if (!session.connect())
            return null;
        session.setEcho(url.isDebug());
        return session;
    }

    public final String getErrorText() {
        return errorText;
    }

    public boolean sendMessage(SendMail sm, File messageFile) {
        List<String> addressees = sm.getAddressees();
        if (addressees == null || addressees.size() == 0)
            return false;
        if (!connect())
            return false;
        try {
            setEcho(true);
            StringBuilder sb = new StringBuilder("mail from:<");
            sb.append(sm.getFromAddress());
            sb.append('>');
            writeLine(sb.toString());
            if (getResponse() != 250)
                return false;
            for (String addressee : addressees) {
                String addr = sm.getAddress(addressee);
                if (addr == null) {
                    errorText = "Invalid addressee \"" + addressee + "\"";
                    return false;
                }
                sb.setLength(0);
                sb.append("rcpt to:<");
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
        for (String addressee : addressees) {
            MailAddress a = MailAddress.parseAddress(addressee);
            if (a != null) {
                addressBook.maybeAddMailAddress(a);
                addressBook.promote(a);
            }
        }
        AddressBook.saveGlobalAddressBook();
        return true;
    }

    public boolean connect() {
        if (connected)
            return true;
        errorText = null;
        Log.debug("connecting to port " + getPort() + " on " + getHost() + " ...");
        try {
            if (url.isSSL()) {
                socket = Tls.connect(getHost(), getPort(), url.isValidateCert());
            } else {
                socket = new Socket();
                socket.connect(new InetSocketAddress(getHost(), getPort()), TIMEOUT);
            }
            socket.setSoTimeout(TIMEOUT);
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
            setStreams();
            if (getResponse() != 220) {
                errorText = "SMTP server " + getHost() + " refused the connection: " + responseText;
                return false;
            }
            boolean secure = url.isSSL();
            if (ehlo() && !secure && (url.isTLS() || offers("STARTTLS"))) {
                writeLine("STARTTLS");
                if (getResponse() != 220)
                    errorText = "STARTTLS refused: " + responseText;
                else
                    secure = startTLS() && ehlo();
                if (!secure)
                    return false;
            }
            if (errorText == null && authenticate(secure))
                connected = true;
        }
        catch (IOException e) {
            Log.error(e);
        }
        finally {
            if (!connected)
                closeSocket();
        }
        return connected;
    }

    private void setStreams() throws IOException {
        reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
        );
        writer = new BufferedWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        );
    }

    private boolean ehlo() throws IOException {
        writeLine("EHLO " + InetAddress.getLocalHost().getHostAddress());
        if (getResponse() == 250)
            return true;
        errorText = "EHLO refused: " + responseText;
        return false;
    }

    // True if the last response lists the extension, as EHLO's does.
    private boolean offers(String extension) {
        for (String line : responseLines) {
            if (line.length() > 4 && line.substring(4).toUpperCase(Locale.ROOT).startsWith(extension))
                return true;
        }
        return false;
    }

    private boolean startTLS() {
        Log.debug("starting TLS");
        try {
            socket = Tls.wrap(socket, getHost(), getPort(), url.isValidateCert());
            setStreams();
            return true;
        }
        catch (IOException e) {
            Log.error(e);
            errorText = "TLS failed with SMTP server " + getHost() + ": " + e.getMessage();
            return false;
        }
    }

    // Use "PLAIN" authentication
    // UNDONE: support for "LOGIN", "MD5", "NTLM"
    private boolean authenticate(boolean secure) throws IOException {
        if (user == null && password == null) {
            Log.debug("no credentials, not authenticating");
            return true;
        }
        // A relay that asks for no login gets none.
        if (!offers("AUTH"))
            return true;
        if (!secure) {
            errorText = "SMTP server " + getHost() + " offers no TLS; not sending the password";
            return false;
        }

        Log.debug("authenticating");
        writeLine("AUTH PLAIN");
        if (getResponse() == 334) {
            StringBuilder sb = new StringBuilder();
            sb.append('\0');
            sb.append(user);
            sb.append('\0');
            sb.append(password);

            // RFC 4616: the SASL PLAIN message is UTF-8.
            String b64encoded = Base64.getEncoder()
                .encodeToString(
                    sb.toString().getBytes(StandardCharsets.UTF_8)
                );
            writeLine(b64encoded, "(credentials)");
            if (235 == getResponse())
                return true;

            errorText = "Authentication failed: " + responseText;
            return false;
        }

        errorText = "SMTP server doesn't support PLAIN authentication";
        return false;
    }

    public void quit() {
        setEcho(true);
        writeLine("QUIT");
        getResponse();
        setEcho(false);
        disconnect();
    }

    public synchronized void disconnect() {
        if (connected)
            closeSocket();
    }

    private synchronized void closeSocket() {
        if (socket != null) {
            try {
                socket.close();
            }
            catch (IOException e) {
                Log.error(e);
            }
        }
        socket = null;
        reader = null;
        writer = null;
        connected = false;
    }

    public int getResponse() {
        responseText = "";
        responseLines.clear();
        while (true) {
            String s = readLine();
            if (s == null)
                break;
            if (s.length() < 4)
                break;
            responseLines.add(s);
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

    private String readLine() {
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

    public void write(int c) throws IOException {
        writer.write(c);
    }

    public void write(char[] chars) throws IOException {
        writer.write(chars);
    }

    public void write(char[] chars, int offset, int length) throws IOException {
        writer.write(chars, offset, length);
    }

    public void write(String s) throws IOException {
        writer.write(s);
    }

    public void write(String s, int offset, int length) throws IOException {
        writer.write(s, offset, length);
    }

    public void flush() throws IOException {
        writer.flush();
    }

    public void close() throws IOException {
        writer.close();
    }

    public boolean writeLine(String s) {
        return writeLine(s, s);
    }

    // Logs shown in place of s.
    private boolean writeLine(String s, String shown) {
        if (echo)
            Log.debug("==> " + shown);
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
