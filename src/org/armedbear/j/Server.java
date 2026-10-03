/*
 * Server.java
 *
 * Copyright (C) 1998-2004 Peter Graves
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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Lets a second j hand its files to the one already running. The port file
 * holds the port and a random token; the server listens on loopback only and
 * ignores a request that does not start with the token.
 */
public class Server implements Runnable {
    private static Server server;

    private final ServerSocket socket;
    private final String token;
    private final Consumer<List<String>> handler;

    /*package*/ Server(String token, Consumer<List<String>> handler) throws IOException {
        this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.token = token;
        this.handler = handler;
    }

    /*package*/ int getPort() {
        return socket.getLocalPort();
    }

    /*package*/ InetAddress getAddress() {
        return socket.getInetAddress();
    }

    /*package*/ void start() {
        final Thread thread = new Thread(this, "server");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.setDaemon(true);
        thread.start();
    }

    /*package*/ void close() throws IOException {
        socket.close();
    }

    /*package*/ static String newToken() {
        final byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static void startServer() {
        Server s = null;
        try {
            s = new Server(newToken(), lines -> SwingUtilities.invokeLater(() -> openFiles(lines)));
            writePortFile(Path.of(Editor.portfile.canonicalPath()), s.getPort(), s.token);
            // Only now is the port file ours for stopServer to delete.
            server = s;
            server.start();
        }
        catch (IOException e) {
            Log.error(e);
            if (s != null) {
                try {
                    s.close();
                }
                catch (IOException ignored) {}
            }
        }
    }

    public static void stopServer() {
        if (server != null)
            Editor.portfile.delete();
    }

    // Written private, then moved into place, so no one else sees the token.
    /*package*/ static void writePortFile(Path path, int port, String token) throws IOException {
        Path temp;
        try {
            temp = Files.createTempFile(
                path.getParent(),
                "port",
                null,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            );
        }
        catch (UnsupportedOperationException e) {
            temp = Files.createTempFile(path.getParent(), "port", null);
        }
        try {
            Files.writeString(temp, port + "\n" + token + "\n", StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        finally {
            Files.deleteIfExists(temp);
        }
    }

    /** True if a j is listening on the port this port file names. */
    /*package*/ static boolean isListening(Path portFile) throws IOException {
        return send(portFile, null);
    }

    /**
     * Sends lines to the j whose port file this is; null sends nothing.
     *
     * @return false if no j is listening there
     */
    /*package*/ static boolean send(Path portFile, List<String> lines) throws IOException {
        final List<String> port = Files.readAllLines(portFile, StandardCharsets.UTF_8);
        if (port.size() < 2)
            return false;
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(port.get(0).trim()));
            BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            )) {
            if (lines == null)
                return true;
            out.write(port.get(1).trim());
            out.newLine();
            for (String line : lines) {
                out.write(line);
                out.newLine();
            }
        }
        catch (ConnectException | NumberFormatException e) {
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        while (true) {
            final List<String> lines = new ArrayList<>();
            try (Socket sock = socket.accept();
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8)
                )) {
                // A client that never finishes must not hold up the next.
                sock.setSoTimeout(5000);
                if (!token.equals(in.readLine()))
                    continue;
                for (String s; (s = in.readLine()) != null;)
                    lines.add(s);
            }
            catch (SocketTimeoutException e) {
                continue;
            }
            catch (SocketException e) {
                return;
            }
            catch (IOException e) {
                Log.error(e);
                continue;
            }
            handler.accept(lines);
        }
    }

    private static void openFiles(List<String> v) {
        Editor editor = Editor.currentEditor();
        if (!v.isEmpty()) {
            Editor other = editor.getOtherEditor();
            if (other != null && editor.getBuffer().isSecondary())
                editor = other;
            if (!editor.getBuffer().isPrimary())
                Debug.bug();
            Buffer toBeActivated = editor.openFiles(v);
            if (toBeActivated != null) {
                editor.makeNext(toBeActivated);
                editor.switchToBuffer(toBeActivated);
                if (!Editor.getEditorList().contains(editor))
                    Debug.bug();
                editor.updateDisplay();
            }
        }
        editor.getFrame().toFront();
        editor.requestFocus();
        Editor.restoreFocus();
    }
}
