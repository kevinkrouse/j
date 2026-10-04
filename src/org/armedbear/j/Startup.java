/*
 * Startup.java
 *
 * Copyright (C) 1998-2003 Peter Graves
 * Copyright (C) 2026 Kevin Krouse
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

import static org.armedbear.j.Constants.*;

import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.FocusManager;
import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import org.armedbear.j.extension.Extensions;
import org.armedbear.j.mode.dir.DirectoryBuffer;
import org.armedbear.j.util.Utilities;

/** j's entry point: the command line, then the first frame. */
public final class Startup {
    private static final long startTimeMillis = System.currentTimeMillis();

    private Startup() {}

    static long startTimeMillis() {
        return startTimeMillis;
    }

    private static String when() {
        return String.valueOf(System.currentTimeMillis() - startTimeMillis) + " ms";
    }

    public static void main(String[] args) {
        final File currentDir = File.getInstance(System.getProperty("user.dir"));
        boolean dumpEnv = false;
        boolean dumpProps = false;
        boolean forceNewInstance = false;
        boolean restoreSession = true;
        boolean startServer = true;
        int quick = 0;
        File userHomeDir = null;
        boolean migrateToXdg = false;
        boolean printDirectories = false;
        List<String> files = null;

        // Process command line.
        for (int i = 0; i < args.length; i++) {
            final String arg = args[i];
            if (arg.startsWith("-")) {
                if (arg.equals("-h") || arg.equals("-help") || arg.equals("--help")) {
                    usage();
                    System.exit(0);
                }
                if (arg.equals("-version") || arg.equals("--version")) {
                    version();
                    System.exit(0);
                }
                if (arg.equals("-d") || arg.equals("--debug")) {
                    Editor.setDebugEnabled(true);
                    continue;
                }
                if (arg.equals("--dump-env")) {
                    dumpEnv = true;
                    continue;
                }
                if (arg.equals("--dump-props")) {
                    dumpProps = true;
                    continue;
                }
                if (arg.equals("-q")) {
                    if (quick < 1)
                        quick = 1;
                    continue;
                }
                if (arg.equals("-n") || arg.equals("--no-restore")) {
                    restoreSession = false;
                    continue;
                }
                if (arg.equals("-session")) {
                    if (i < args.length - 1)
                        Editor.setSessionName(args[++i]);
                    continue;
                }
                if (arg.equals("--force-new-instance")) {
                    forceNewInstance = true;
                    continue;
                }
                if (arg.equals("--no-session")) {
                    restoreSession = false;
                    Editor.setSaveSession(false);
                    continue;
                }
                if (arg.equals("--no-server")) {
                    startServer = false;
                    continue;
                }
                if (arg.equals("--migrate-to-xdg")) {
                    migrateToXdg = true;
                    continue;
                }
                if (arg.equals("--print-directories")) {
                    printDirectories = true;
                    continue;
                }
                if (arg.equals("--no-extensions")) {
                    Extensions.setDisabled(true);
                    continue;
                }
                if (arg.startsWith("--home")) {
                    String home = null;
                    if (arg.equals("--home")) {
                        if (i < args.length - 1)
                            home = args[++i];
                    } else if (arg.startsWith("--home="))
                        home = arg.substring(7);
                    else
                        unknown(arg);

                    if (home == null || home.length() == 0)
                        fatal("Option \"--home\" requires an argument.");

                    userHomeDir = File.getInstance(currentDir, home);

                    if (userHomeDir == null || !userHomeDir.isDirectory()) {
                        fatal(
                            "Specified home directory \"" +
                                userHomeDir.canonicalPath() +
                                "\" does not exist."
                        );
                    }

                    if (!userHomeDir.canWrite()) {
                        fatal(
                            "Specified home directory \"" +
                                userHomeDir.canonicalPath() +
                                "\" is not writable."
                        );
                    }

                    // Specified directory is OK.
                    Utilities.setUserHome(userHomeDir.canonicalPath());

                    continue;
                }
                // If we get here, it's an unknown option.
                unknown(arg);
            } else {
                // It's a file to be opened.
                if (files == null)
                    files = new ArrayList<String>();
                files.add(arg);
            }
        }

        // At this point the user has had a chance to tell us where his home
        // directory is.
        Directories.initialize(userHomeDir, migrateToXdg);
        if (printDirectories)
            Directories.printDirectories();

        boolean alreadyRunning = false;
        final File portfile = Server.portFile();
        if (portfile.exists()) {
            final java.nio.file.Path path = java.nio.file.Path.of(portfile.canonicalPath());
            try {
                if (forceNewInstance) {
                    alreadyRunning = Server.isListening(path);
                } else {
                    final List<String> lines = new ArrayList<String>();
                    lines.add(File.getInstance(System.getProperty("user.dir")).canonicalPath());
                    if (files != null)
                        lines.addAll(files);
                    if (Server.send(path, lines))
                        System.exit(0);
                }
                if (!alreadyRunning)
                    portfile.delete();
            }
            catch (IOException e) {
                Log.error(e);
            }
        }

        Editor.loadPreferences();
        Log.initialize(dumpEnv, dumpProps);
        Extensions.load();
        if (quick == 0) {
            Editor.runStartupScript();
        }
        initMacOSX();

        Editor.setSessionProperties(new SessionProperties());

        if (!alreadyRunning)
            Autosave.recover();

        Editor.setTagFileManager(new TagFileManager());

        final boolean restore = restoreSession;
        final String session = Editor.getSessionName();
        final List<String> toOpen = files;
        try {
            SwingUtilities.invokeAndWait(() -> openFirstFrame(restore, session, toOpen, currentDir));
        }
        catch (InterruptedException | InvocationTargetException e) {
            throw new IllegalStateException("startup failed", e);
        }

        // A forced second instance leaves the first one's port file alone.
        if (startServer && !alreadyRunning)
            Server.startServer();

        Log.debug("leaving main " + when());
    }

    private static void openFirstFrame(
        boolean restoreSession,
        String sessionName,
        List<String> files,
        File currentDir
    ) {
        DefaultLookAndFeel.setLookAndFeel();
        initDoubleBufferSize();

        Editor.setCurrentEditor(new Editor(null));

        Editor.currentEditor().getFrame().updateControls();

        // With Java 1.4, we only need to do this to support the key-pressed
        // hook.
        FocusManager.setCurrentManager(new CustomFocusManager());

        Editor.currentEditor().getFrame().placeWindow();

        Buffer toBeActivated = null;

        if (restoreSession) {
            Session session = null;
            if (sessionName != null)
                session = Session.getSession(sessionName);
            if (session == null)
                session = Session.getDefaultSession();
            toBeActivated = session.restore();
        }

        if (files != null) {
            ArrayList<String> list = new ArrayList<String>();
            list.add(currentDir.canonicalPath());
            list.addAll(files);
            Buffer buf = Editor.currentEditor().openFiles(list);
            if (buf != null) {
                Debug.assertTrue(Editor.getBufferList().contains(buf));
                toBeActivated = buf;
            }
        }

        if (toBeActivated == null)
            toBeActivated = new DirectoryBuffer(currentDir);

        Editor.currentEditor().activate(toBeActivated);
        Editor.currentEditor().getFrame().setVisible(true);
        Sidebar sidebar = Editor.currentEditor().getSidebar();
        if (sidebar != null)
            sidebar.setUpdateFlag(SIDEBAR_ALL);
    }

    private static void usage() {
        version();
        System.out.println("Usage: j [options] [+linenum] file");
        System.out.println("Options:");
        System.out.println("  -h, -help");
        System.out.println("  -d, --debug");
        System.out.println("  -n, --no-restore");
        System.out.println("  -version");
        System.out.println("  --force-new-instance");
        System.out.println("  --no-session");
        System.out.println("  --no-server");
        System.out.println("  --home=directory");
        System.out.println("  --migrate-to-xdg");
        System.out.println("  --print-directories");
        System.out.println("  --no-extensions");
    }

    private static void version() {
        String longVersionString = Version.getLongVersionString();
        if (longVersionString != null)
            System.out.println(longVersionString);
        String snapshotInformation = Version.getSnapshotInformation();
        if (snapshotInformation != null)
            System.out.println(snapshotInformation);
    }

    public static void fatal(String message) {
        System.err.println(message);
        System.exit(1);
    }

    private static void unknown(String arg) {
        usage();
        fatal("Unknown option \"" + arg + "\"");
    }

    // Swing works out the maximum size of its double buffers once, the first
    // time it paints, from the screen devices it can see at that moment. If it
    // happens to look while the display is being reconfigured it can see no
    // devices at all, cache a maximum of 0 by 0, and then throw out of every
    // subsequent paint:
    //
    //   IllegalArgumentException: Width (0) and height (0) cannot be <= 0
    //       at java.awt.GraphicsConfiguration.createCompatibleVolatileImage
    //       at javax.swing.RepaintManager.getVolatileOffscreenBuffer
    private static void initDoubleBufferSize() {
        try {
            if (GraphicsEnvironment.isHeadless())
                return;

            Rectangle virtualBounds = new Rectangle();
            GraphicsDevice[] devices =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            for (int i = 0; i < devices.length; i++) {
                GraphicsConfiguration gc = devices[i].getDefaultConfiguration();
                if (gc != null)
                    virtualBounds = virtualBounds.union(gc.getBounds());
            }

            if (virtualBounds.width <= 0 || virtualBounds.height <= 0) {
                // No usable device. Fall back to the screen size.
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                virtualBounds = new Rectangle(screen);
            }

            if (virtualBounds.width <= 0 || virtualBounds.height <= 0) {
                // We are in exactly the state that causes the bug, so we have
                // nothing good to pin. Leave Swing's own value alone: it will
                // recompute on the next display change.
                Log.warn("initDoubleBufferSize: no screen bounds available");
                return;
            }

            RepaintManager.currentManager((JComponent) null)
                .setDoubleBufferMaximumSize(
                    new Dimension(
                        virtualBounds.width,
                        virtualBounds.height
                    )
                );
            Log.debug(
                "initDoubleBufferSize: " + virtualBounds.width + "x" +
                    virtualBounds.height
            );
        }
        catch (Throwable t) {
            // Nothing here is worth failing to start over.
            Log.error(t);
        }
    }

    private static void initMacOSX() {
        if (!Platform.isPlatformMacOSX())
            return;

        if (Desktop.isDesktopSupported()) {

            // Use menu bar
            System.setProperty("apple.laf.useScreenMenuBar", "true");

            var desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_ABOUT))
                desktop.setAboutHandler(e -> AboutDialog.about());

            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER))
                desktop.setQuitHandler((e, r) -> FileCommands.quit(Editor.currentEditor()));
        }
    }
}
