/*
 * HttpLoadProcess.java
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

package org.armedbear.j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.SwingUtilities;
import org.armedbear.j.util.Utilities;

/** Loads an http or https URL into a cache file, following redirects. */
public final class HttpLoadProcess extends LoadProcess implements BackgroundProcess,
    Runnable, Cancellable {
    private String request;
    private String responseHeaders;
    private volatile String contentType;
    private volatile InputStream body;

    private final StringBuilder sbHeaders = new StringBuilder();

    public HttpLoadProcess(Buffer buffer, HttpFile file) {
        super(buffer, file);
    }

    public final String getRequest() {
        return request;
    }

    public final String getResponseHeaders() {
        return responseHeaders;
    }

    public final String getContentType() {
        return contentType;
    }

    public void run() {
        if (buffer != null) {
            buffer.setBusy(true);
            buffer.setBackgroundProcess(this);
        }
        load();
        if (buffer != null && buffer.getBackgroundProcess() == this) {
            buffer.setBackgroundProcess(null);
            buffer.setBusy(false);
        }
    }

    // A blocked read only ends when its stream closes.
    public void cancel() {
        super.cancel();
        InputStream in = body;
        if (in != null) {
            try {
                in.close();
            }
            catch (IOException e) {
                Log.debug(e);
            }
        }
    }

    private static final int MAX_REDIRECTS = 5;

    private void load() {
        cache = Utilities.getTempFile();
        if (cache == null) {
            error("Can't create a cache file");
            return;
        }
        URI uri = uri(file.netPath());
        if (uri == null) {
            error("Invalid URL " + file.netPath());
            return;
        }
        String encoding = null;
        try {
            HttpResponse<InputStream> response = null;
            // Redirects are followed here, so each hop's cookies are kept.
            for (int hops = 0;; hops++) {
                response = send(uri);
                int status = response.statusCode();
                String location = response.headers().firstValue("Location").orElse(null);
                if (!isRedirect(status) || location == null || hops == MAX_REDIRECTS)
                    break;
                URI next = uri(location);
                if (next == null)
                    break;
                response.body().close();
                uri = uri.resolve(next);
            }
            body = response.body();
            HttpHeaders headers = response.headers();
            responseHeaders = headerText(headers);
            sbHeaders.append("HTTP ")
                .append(response.statusCode())
                .append("\r\n")
                .append(responseHeaders)
                .append("\r\n");
            contentType = headers.firstValue("Content-Type").orElse(null);
            String charset = Utilities.getCharsetFromContentType(contentType);
            if (charset != null)
                encoding = Utilities.getEncodingFromCharset(charset);
            if (!uri.toString().equals(file.netPath())) {
                // Kept as it was if HttpFile can't name the target.
                HttpFile target = HttpFile.getHttpFile(uri.toString());
                if (target != null)
                    file = target;
            }
            long length = headers.firstValueAsLong("Content-Length").orElse(0);
            if (progressNotifier != null)
                progressNotifier.progressStart();
            try (InputStream in = body; OutputStream out = cache.getOutputStream()) {
                byte[] buf = new byte[16384];
                long total = 0;
                for (int n; !cancelled && (n = in.read(buf)) > 0;) {
                    out.write(buf, 0, n);
                    total += n;
                    if (progressNotifier != null)
                        progressNotifier.progress("Received ", total, length);
                }
            }
        }
        catch (IOException | IllegalArgumentException e) {
            if (!cancelled) {
                Log.error(e);
                setErrorText(e.getMessage() != null ? e.getMessage() : e.toString());
                cache.delete();
            }
        }
        catch (InterruptedException e) {
            cancelled = true;
        }
        finally {
            body = null;
            if (progressNotifier != null)
                progressNotifier.progressStop();
        }
        if (cancelled) {
            cache.delete();
            if (cancelRunnable != null)
                SwingUtilities.invokeLater(cancelRunnable);
        } else if (cache.isFile()) {
            final HttpFile httpFile = (HttpFile) file;
            httpFile.setCache(cache);
            httpFile.setHeaders(sbHeaders.toString());
            httpFile.setContentType(contentType);
            httpFile.setEncoding(encoding);
            cache.setEncoding(encoding);
            if (successRunnable != null)
                SwingUtilities.invokeLater(successRunnable);
        } else {
            cache = null;
            if (errorRunnable != null) {
                errorRunnable.setMessage(getErrorText());
                SwingUtilities.invokeLater(errorRunnable);
            }
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    // One GET, sending and storing this hop's cookies.
    private HttpResponse<InputStream> send(URI uri) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET();
        String userAgent = Editor.preferences().getStringProperty(Property.HTTP_USER_AGENT);
        if (userAgent != null && userAgent.length() > 0)
            builder.header("User-Agent", userAgent);
        final boolean cookies = Editor.preferences().getBooleanProperty(Property.HTTP_ENABLE_COOKIES);
        if (cookies) {
            String cookie = Cookie.getCookie(url(uri));
            if (cookie != null)
                builder.header("Cookie", cookie);
        }
        HttpRequest httpRequest = builder.build();
        request = "GET " + uri + "\r\n" + headerText(httpRequest.headers()) + "\r\n";
        sbHeaders.append(request);
        if (progressNotifier != null)
            progressNotifier.setText("Connecting to " + uri.getHost() + "...");
        HttpResponse<InputStream> response = client().send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (cookies) {
            for (String cookie : response.headers().allValues("Set-Cookie"))
                Cookie.setCookie(url(uri), cookie);
        }
        return response;
    }

    private static HttpClient client;
    private static InetSocketAddress clientProxy;

    // Shared, and rebuilt only when the httpProxy preference changes.
    private static synchronized HttpClient client() {
        InetSocketAddress proxy = proxy();
        if (client == null || !Objects.equals(proxy, clientProxy)) {
            HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1);
            if (proxy != null)
                builder.proxy(ProxySelector.of(proxy));
            client = builder.build();
            clientProxy = proxy;
        }
        return client;
    }

    /**
     * s as a URI, or null. Spaces, quotes, angle brackets, braces, | \ ^ `,
     * and after the host [ ] and a % not starting an escape, are
     * percent-encoded first, as browsers send them.
     */
    static URI uri(String s) {
        int scheme = s.indexOf("://");
        int pathStart = scheme < 0 ? 0 : s.length();
        if (scheme >= 0) {
            for (int i = scheme + 3; i < s.length(); i++) {
                if ("/?#".indexOf(s.charAt(i)) >= 0) {
                    pathStart = i;
                    break;
                }
            }
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean encode = c == ' ' || "\"<>\\^`{|}".indexOf(c) >= 0;
            if (i >= pathStart)
                encode |= c == '[' || c == ']' || c == '%' && !isEscape(s, i);
            if (encode)
                sb.append('%').append(String.format("%02X", (int) c));
            else
                sb.append(c);
        }
        try {
            return new URI(sb.toString());
        }
        catch (URISyntaxException e) {
            Log.debug(e);
            return null;
        }
    }

    private static boolean isEscape(String s, int i) {
        return i + 2 < s.length()
            && Character.digit(s.charAt(i + 1), 16) >= 0
            && Character.digit(s.charAt(i + 2), 16) >= 0;
    }

    // The httpProxy preference: "host:port", optionally after "http://".
    private static InetSocketAddress proxy() {
        String httpProxy = Editor.preferences().getStringProperty("httpProxy");
        if (httpProxy == null)
            return null;
        if (httpProxy.startsWith("http://"))
            httpProxy = httpProxy.substring(7);
        int index = httpProxy.indexOf(':');
        if (index < 0)
            return null;
        try {
            return InetSocketAddress.createUnresolved(
                httpProxy.substring(0, index),
                Integer.parseInt(httpProxy.substring(index + 1).trim())
            );
        }
        catch (IllegalArgumentException e) {
            Log.error(e);
            return null;
        }
    }

    private static URL url(URI uri) {
        try {
            return uri.toURL();
        }
        catch (MalformedURLException e) {
            return null;
        }
    }

    private static String headerText(HttpHeaders headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : headers.map().entrySet()) {
            for (String value : entry.getValue())
                sb.append(entry.getKey()).append(": ").append(value).append("\r\n");
        }
        return sb.toString();
    }

    private void error(String errorText) {
        Log.error(errorText);
        setErrorText(errorText);
        if (errorRunnable != null) {
            errorRunnable.setMessage(errorText);
            SwingUtilities.invokeLater(errorRunnable);
        }
    }

    public static void httpShowHeaders() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        final File file = buffer.getFile();
        if (file instanceof HttpFile) {
            editor.setWaitCursor();
            final String title = "httpShowHeaders ".concat(file.netPath());
            Buffer buf = null;
            for (Buffer b : Editor.getBufferList()) {
                if (b instanceof OutputBuffer && b.getParentBuffer() == buffer) {
                    if (title.equals(b.getTitle())) {
                        buf = b;
                        break;
                    }
                }
            }
            if (buf == null) {
                buf = OutputBuffer.getOutputBuffer(((HttpFile) file).getHeaders());
                buf.setParentBuffer(buffer);
                buf.setTitle(title);
            }
            editor.makeNext(buf);
            editor.activateInOtherWindow(buf);
            editor.setDefaultCursor();
        }
    }
}
