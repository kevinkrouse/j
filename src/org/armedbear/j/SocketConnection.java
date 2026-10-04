/*
 * SocketConnection.java
 *
 * Copyright (C) 2002 Peter Graves
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

package org.armedbear.j;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.UnknownHostException;
import javax.net.SocketFactory;
import javax.net.ssl.SSLException;
import org.armedbear.j.util.Tls;

public final class SocketConnection {
    private final String hostName;
    private final int port;
    private final int timeout; // milliseconds
    private final int checkInterval; // milliseconds
    private final Cancellable client;
    private final boolean ssl;
    private final boolean verify;

    private Socket socket;
    private String errorText;

    public SocketConnection(
        String hostName,
        int port,
        boolean ssl,
        int timeout,
        int checkInterval,
        Cancellable client
    ) {
        this(hostName, port, ssl, true, timeout, checkInterval, client);
    }

    // verify false checks no TLS certificate.
    public SocketConnection(
        String hostName,
        int port,
        boolean ssl,
        boolean verify,
        int timeout,
        int checkInterval,
        Cancellable client
    ) {
        this.hostName = hostName;
        this.port = port;
        this.ssl = ssl;
        this.verify = verify;
        this.timeout = timeout;
        this.checkInterval = checkInterval;
        this.client = client;
    }

    public final String getErrorText() {
        return errorText;
    }

    private final void setErrorText(String s) {
        errorText = s;
    }

    public Socket connect() {
        socket = null;
        long start = System.currentTimeMillis();
        connectThread.start();
        while (System.currentTimeMillis() - start < timeout) {
            try {
                connectThread.join(checkInterval);
            }
            catch (InterruptedException e) {
                Log.error(e);
                setErrorText(e.toString());
                return null;
            }
            if (client != null && client.cancelled()) {
                Log.debug("cancelled!");
                return null;
            }
            if (!connectThread.isAlive())
                break;
        }
        if (socket == null && connectThread.isAlive())
            setErrorText("Timed out");
        return socket;
    }

    private final Thread connectThread = new Thread("connect") {
        public void run() {
            try {
                socket = ssl
                    ? Tls.connect(hostName, port, verify)
                    : SocketFactory.getDefault().createSocket(hostName, port);
            }
            catch (NoRouteToHostException e) {
                setErrorText("No route to host " + hostName);
            }
            catch (UnknownHostException e) {
                setErrorText("Unknown host " + hostName);
            }
            catch (ConnectException e) {
                setErrorText("Connection refused");
            }
            catch (SSLException e) {
                setErrorText("TLS failed for " + hostName + ": " + e.getMessage());
            }
            catch (IOException | RuntimeException e) {
                Log.error(e);
                setErrorText("Unable to connect to " + hostName);
            }
        }
    };
}
