/*
 * TlsTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tls refuses a certificate that does not name the host, even a trusted one. */
public class TlsTest {
    private static final char[] PASSWORD = "changeit".toCharArray();

    @TempDir
    Path dir;

    @Test
    public void aCertificateForTheHostIsAccepted() throws Exception {
        assertEquals(42, roundTrip("localhost"));
    }

    @Test
    public void aCertificateForAnotherHostIsRefused() throws Exception {
        assertThrows(SSLHandshakeException.class, () -> roundTrip("wrong.example"));
    }

    // Serves one byte over TLS with a self-signed certificate for certHost,
    // which the client trusts, and reads it through Tls.connect("localhost").
    private int roundTrip(String certHost) throws Exception {
        final KeyStore keys = keyStore(certHost);
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, PASSWORD);
        final SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);

        final KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("server", keys.getCertificate("server"));
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trusted);
        final SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, tmf.getTrustManagers(), null);

        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final Thread serve = new Thread(() -> {
                try (SSLSocket s = (SSLSocket) server.accept(); OutputStream out = s.getOutputStream()) {
                    out.write(42);
                }
                catch (Exception e) {
                    // The client gave up on the handshake.
                }
            });
            serve.setDaemon(true);
            serve.start();
            final SSLSocketFactory factory = clientContext.getSocketFactory();
            try (SSLSocket socket = Tls.connect(factory, "localhost", server.getLocalPort(), true);
                InputStream in = socket.getInputStream()) {
                return in.read();
            }
        }
    }

    private KeyStore keyStore(String host) throws Exception {
        final Path file = dir.resolve(host + ".p12");
        final Process p = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair",
            "-alias",
            "server",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-dname",
            "CN=" + host,
            "-ext",
            "SAN=dns:" + host,
            "-validity",
            "1",
            "-keystore",
            file.toString(),
            "-storetype",
            "PKCS12",
            "-storepass",
            "changeit",
            "-keypass",
            "changeit"
        )
            .redirectErrorStream(true)
            .start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "keytool");
        final KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            ks.load(in, PASSWORD);
        }
        return ks;
    }
}
