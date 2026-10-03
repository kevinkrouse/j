/*
 * Tls.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS connections that check the server's certificate names the host. A bare
 * SSLSocket checks only that the certificate chains to a trusted CA. verify
 * false, a mail URL's /novalidate-cert, checks nothing.
 */
public final class Tls {
    private static final int TIMEOUT = 30000; // milliseconds

    private Tls() {}

    /** Opens a TLS connection to host and completes the handshake. */
    public static SSLSocket connect(String host, int port) throws IOException {
        return connect(host, port, true);
    }

    public static SSLSocket connect(String host, int port, boolean verify) throws IOException {
        return connect(factory(verify), host, port, verify);
    }

    /** Upgrades a connected socket to TLS, as STARTTLS does. */
    public static SSLSocket wrap(Socket socket, String host, int port, boolean verify) throws IOException {
        return handshake((SSLSocket) factory(verify).createSocket(socket, host, port, true), verify);
    }

    /*package*/ static SSLSocket connect(SSLSocketFactory factory, String host, int port, boolean verify)
        throws IOException {
        final SSLSocket socket = (SSLSocket) factory.createSocket();
        try {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT);
        }
        catch (IOException e) {
            socket.close();
            throw e;
        }
        return handshake(socket, verify);
    }

    private static SSLSocket handshake(SSLSocket socket, boolean verify) throws IOException {
        if (verify) {
            final SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
        }
        // The caller's own timeout, if any, applies after the handshake.
        final int timeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(TIMEOUT);
            socket.startHandshake();
            socket.setSoTimeout(timeout);
        }
        catch (IOException e) {
            socket.close();
            throw e;
        }
        return socket;
    }

    private static SSLSocketFactory factory(boolean verify) throws IOException {
        if (verify)
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        try {
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] { TRUST_ALL }, null);
            return context.getSocketFactory();
        }
        catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
