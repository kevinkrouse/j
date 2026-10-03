/*
 * SmtpSessionTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** When SmtpSession upgrades to TLS, and when it will send a password. */
public class SmtpSessionTest {
    private final List<String> received = new CopyOnWriteArrayList<>();
    private ServerSocket server;

    @AfterEach
    public void tearDown() throws Exception {
        if (server != null)
            server.close();
    }

    @Test
    public void noPasswordWithoutTls() throws Exception {
        serve("250-localhost\r\n250 AUTH PLAIN\r\n", Map.of());
        assertNull(SmtpSession.getSession(url(), "user", "secret"));
        assertFalse(sent("AUTH"));
    }

    @Test
    public void aRelayThatAsksForNoLoginGetsNone() throws Exception {
        serve("250 localhost\r\n", Map.of("QUIT", "221 bye"));
        final SmtpSession session = SmtpSession.getSession(url(), "user", "secret");
        assertNotNull(session);
        session.quit();
        assertFalse(sent("AUTH"));
    }

    @Test
    public void aRefusedStartTlsFailsClosed() throws Exception {
        serve("250-localhost\r\n250-STARTTLS\r\n250 AUTH PLAIN\r\n", Map.of("STARTTLS", "454 not now"));
        assertNull(SmtpSession.getSession(url(), "user", "secret"));
        assertTrue(sent("STARTTLS"));
        assertFalse(sent("AUTH"));
    }

    private boolean sent(String command) {
        return received.stream().anyMatch(s -> s.startsWith(command));
    }

    private SmtpURL url() {
        return new SmtpURL(null, "127.0.0.1", server.getLocalPort(), false, false, true, true);
    }

    // Answers EHLO with ehlo and other commands from replies, else 502.
    private void serve(String ehlo, Map<String, String> replies) throws Exception {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final Thread thread = new Thread(() -> {
            try (Socket s = server.accept();
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)
                );
                OutputStream out = s.getOutputStream()) {
                out.write("220 localhost ESMTP\r\n".getBytes(StandardCharsets.UTF_8));
                for (String line; (line = in.readLine()) != null;) {
                    received.add(line);
                    final String command = line.split(" ")[0];
                    final String reply = command.equals("EHLO")
                        ? ehlo
                        : replies.getOrDefault(command, "502 no") + "\r\n";
                    out.write(reply.getBytes(StandardCharsets.UTF_8));
                }
            }
            catch (Exception e) {
                // The client hung up.
            }
        });
        thread.setDaemon(true);
        thread.start();
    }
}
