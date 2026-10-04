/*
 * HttpLoadProcessTest.java
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
public class HttpLoadProcessTest {
    private HttpServer server;
    private String base;
    private volatile String cookieSeen;

    @BeforeEach
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/page", exchange -> {
            byte[] body = "<p>café</p>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/page");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=1; path=/");
            exchange.getResponseHeaders().add("Location", "/a b");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/a b", exchange -> {
            cookieSeen = exchange.getRequestHeaders().getFirst("Cookie");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    public void stop() {
        server.stop(0);
    }

    private HttpLoadProcess load(String path) {
        HttpLoadProcess p = new HttpLoadProcess(null, HttpFile.getHttpFile(base + path));
        p.run();
        return p;
    }

    @Test
    public void loadsAPageWithItsEncoding() throws Exception {
        HttpLoadProcess p = load("/page");
        assertNotNull(p.getCache());
        assertEquals("<p>café</p>", Files.readString(Path.of(p.getCache().canonicalPath()), StandardCharsets.UTF_8));
        assertEquals("text/html; charset=utf-8", p.getContentType());
        assertEquals("utf-8", ((HttpFile) p.getFile()).getEncoding());
        assertTrue(((HttpFile) p.getFile()).getHeaders().startsWith("GET " + base + "/page"));
    }

    @Test
    public void followsARedirect() {
        HttpLoadProcess p = load("/moved");
        assertNotNull(p.getCache());
        assertEquals(base + "/page", p.getFile().netPath());
    }

    // An error page is still a page.
    @Test
    public void keepsAnErrorResponse() {
        assertNotNull(load("/missing").getCache());
    }

    @Test
    public void aRefusedConnectionIsAnError() {
        server.stop(0);
        HttpLoadProcess p = load("/page");
        assertNull(p.getCache());
        assertNotNull(p.getErrorText());
    }

    @Test
    public void aRedirectsCookieGoesWithTheNextRequest() {
        Editor.preferences().setProperty(Property.HTTP_ENABLE_COOKIES, "true");
        try {
            HttpLoadProcess p = load("/login");
            assertNotNull(p.getCache(), p.getErrorText());
            assertEquals("session=1", cookieSeen);
            assertEquals(base + "/a%20b", p.getFile().netPath());
        }
        finally {
            Editor.preferences().removeProperty(Property.HTTP_ENABLE_COOKIES.key());
        }
    }

    // Not stuck busy: an error the error runnable can report.
    @Test
    public void aHostHttpClientRejectsIsAnError() {
        HttpLoadProcess p = new HttpLoadProcess(null, HttpFile.getHttpFile("http://my_host/"));
        p.run();
        assertNull(p.getCache());
        assertNotNull(p.getErrorText());
    }

    @Test
    public void urisBrowsersSendAsTheyAre() {
        assertEquals("http://h/a%20b?x=%7By%7D", HttpLoadProcess.uri("http://h/a b?x={y}").toString());
        assertEquals("http://h/p.php?ids%5B%5D=3", HttpLoadProcess.uri("http://h/p.php?ids[]=3").toString());
        assertEquals("http://h/100%25", HttpLoadProcess.uri("http://h/100%").toString());
        assertEquals("http://h/a%20b", HttpLoadProcess.uri("http://h/a%20b").toString());
        assertEquals("http://[::1]:8080/", HttpLoadProcess.uri("http://[::1]:8080/").toString());
    }
}
