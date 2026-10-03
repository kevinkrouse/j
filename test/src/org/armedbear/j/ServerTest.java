/*
 * ServerTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ServerTest {
    @TempDir
    Path dir;

    private final BlockingQueue<List<String>> received = new ArrayBlockingQueue<>(4);
    private Server server;
    private Path portFile;

    @BeforeEach
    public void setUp() throws Exception {
        final String token = Server.newToken();
        server = new Server(token, received::add);
        server.start();
        portFile = dir.resolve("port");
        Server.writePortFile(portFile, server.getPort(), token);
    }

    @AfterEach
    public void tearDown() throws Exception {
        server.close();
    }

    @Test
    public void listensOnLoopbackOnly() {
        assertTrue(server.getAddress().isLoopbackAddress());
    }

    @Test
    public void portFileIsPrivate() throws Exception {
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(portFile)));
    }

    @Test
    public void sendDeliversLines() throws Exception {
        assertTrue(Server.send(portFile, List.of("/tmp", "a.txt")));
        assertEquals(List.of("/tmp", "a.txt"), received.poll(5, TimeUnit.SECONDS));
    }

    @Test
    public void aRequestWithoutTheTokenIsIgnored() throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getPort());
            Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
            out.write("not-the-token\n/etc\npasswd\n");
        }
        assertTrue(Server.isListening(portFile));
        assertNull(received.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    public void nobodyListeningAfterClose() throws Exception {
        server.close();
        assertFalse(Server.send(portFile, List.of("/tmp")));
    }
}
