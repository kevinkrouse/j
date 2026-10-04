/*
 * Editor.java
 *
 * Copyright (C) 1998-2007 Peter Graves <peter@armedbear.org>
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.armedbear.j;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.dnd.DropTarget;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.StringBuilder;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.FocusManager;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.extension.EvalException;
import org.armedbear.j.extension.EvalRequest;
import org.armedbear.j.extension.EvalResult;
import org.armedbear.j.extension.Extensions;
import org.armedbear.j.extension.Opener;
import org.armedbear.j.extension.ScriptFunction;
import org.armedbear.j.mode.c.CMode;
import org.armedbear.j.mode.compilation.CompilationBuffer;
import org.armedbear.j.mode.dir.DirectoryBuffer;
import org.armedbear.j.mode.dir.DirectoryTree;
import org.armedbear.j.mode.image.ImageBuffer;
import org.armedbear.j.util.Keys;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsBackends;
import org.jdesktop.swingx.MultiSplitLayout;

public final class Editor extends JPanel implements Constants,
    ComponentListener, MouseWheelListener {
    private static final long startTimeMillis = System.currentTimeMillis();

    private static boolean debug = false;
    private static boolean saveSession = true;

    static File portfile;

    private static EditorList editorList = new EditorList();

    private static PendingOperations pendingOperations = new PendingOperations();

    private static volatile Editor currentEditor;

    private static KillRing killRing = new KillRing();

    public static final KillRing getKillRing() {
        return killRing;
    }

    private static SessionProperties sessionProperties;

    public static SessionProperties getSessionProperties() {
        return sessionProperties;
    }

    private static final Preferences prefs = new Preferences();

    public static final Preferences preferences() {
        return prefs;
    }

    private static boolean isRecordingMacro;

    public static synchronized boolean isRecordingMacro() {
        return isRecordingMacro;
    }

    public static synchronized void setRecordingMacro(boolean b) {
        isRecordingMacro = b;
    }

    static String lookAndFeel;

    private Buffer buffer;

    private final Display display;
    private final Dispatcher dispatcher;
    private final Frame frame;

    /**
     * What findNext and findPrev repeat, and vim edit mode's n: one pattern
     * for every window, or with the shareSearch preference false, each
     * window's own. So is whether clearSearchHighlight has hidden its
     * matches.
     */
    private Search lastSearch;
    private static Search sharedLastSearch;
    private boolean searchHighlightHidden;
    private static boolean sharedSearchHighlightHidden;

    /** The shareSearch preference, which vim edit mode's n reads too. */
    public static boolean isSearchShared() {
        return preferences().getBooleanProperty(Property.SHARE_SEARCH);
    }

    public final Search getLastSearch() {
        return isSearchShared() ? sharedLastSearch : lastSearch;
    }

    /** A new search, whose matches show again after clearSearchHighlight. */
    public final void setLastSearch(Search search) {
        if (isSearchShared())
            sharedLastSearch = search;
        else
            lastSearch = search;
        setSearchHighlightHidden(false);
        repaintSearchWindows();
    }

    public final boolean isSearchHighlightHidden() {
        return isSearchShared()
            ? sharedSearchHighlightHidden
            : searchHighlightHidden;
    }

    /** Hides the matches highlighted until the next search, or shows them. */
    public final void setSearchHighlightHidden(boolean hidden) {
        if (hidden == isSearchHighlightHidden())
            return;
        if (isSearchShared())
            sharedSearchHighlightHidden = hidden;
        else
            searchHighlightHidden = hidden;
        repaintSearchWindows();
    }

    /**
     * {@code clearSearchHighlight} -- stops highlighting the matches of the
     * last search until the next one, as vim's {@code :nohlsearch}.
     */
    public void clearSearchHighlight() {
        setSearchHighlightHidden(true);
    }

    /**
     * The matches of the last search to highlight on a line, as offset
     * pairs, or null: with the highlightSearchMatches preference, or as an
     * input handler says, which may show a search being typed instead.
     */
    public final int[] getSearchMatches(Line line) {
        final InputHandler handler = getInputHandler();
        if (handler != null)
            return handler.getSearchMatches(this, line);
        return buffer.getBooleanProperty(Property.HIGHLIGHT_SEARCH_MATCHES)
            ? lastSearchMatches(line)
            : null;
    }

    /** The last search's matches on a line, unless they are hidden. */
    public final int[] lastSearchMatches(Line line) {
        final Search search = getLastSearch();
        if (search == null || isSearchHighlightHidden())
            return null;
        return search.matchesOnLine(buffer.getMode(), line);
    }

    /** The match a search being typed is on, as an input handler says. */
    public final int[] getCurrentSearchMatch(Line line) {
        final InputHandler handler = getInputHandler();
        return handler == null
            ? null
            : handler.getCurrentSearchMatch(this, line);
    }

    /** Every window a shared search shows in, or just this one. */
    private void repaintSearchWindows() {
        if (!isSearchShared()) {
            repaintDisplay();
            return;
        }
        for (Editor ed : Editor.getEditorList())
            ed.repaintDisplay();
    }

    // The current position in the buffer (that is, in the actual text).
    private Position dot;

    // The position of the other end of the selection, if any,
    private Position mark;

    private Selection selection;
    private boolean isColumnSelection;

    Map<SystemBuffer, View> views = new HashMap<SystemBuffer, View>();

    // BUG! This stuff should be factored somehow...
    private int currentCommand = COMMAND_NOTHING;
    private int lastCommand = COMMAND_NOTHING;

    public final int getCurrentCommand() {
        return currentCommand;
    }

    public final void setCurrentCommand(int command) {
        currentCommand = command;
    }

    public final int getLastCommand() {
        return lastCommand;
    }

    public final void setLastCommand(int command) {
        lastCommand = command;
    }

    /**
     * Bookmarks by name: 0 to 9 at 0 to 9, the temporary marker at 10, and
     * A to Z after it, which are vim's file marks.
     */
    private static Marker[] bookmarks = new Marker[11 + 26];

    private static TagFileManager tagFileManager;

    private static boolean tabsAreVisible = false;

    public static final boolean tabsAreVisible() {
        return tabsAreVisible;
    }

    static void setTabsAreVisible(boolean b) {
        tabsAreVisible = b;
    }

    static boolean isMenuSelected = false;

    // UNDONE: cache localDirectoryTree somewhere else
    public DirectoryTree localDirectoryTree;

    private static ModeList modeList;

    public static final ModeList getModeList() {
        if (modeList == null)
            modeList = ModeList.getInstance();
        return modeList;
    }

    private static final BufferList bufferList = new BufferList();

    public static final BufferList getBufferList() {
        return bufferList;
    }

    public static long getStartTimeMillis() {
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
                    Editor.debug = true;
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
                        sessionName = args[++i];
                    continue;
                }
                if (arg.equals("--force-new-instance")) {
                    forceNewInstance = true;
                    continue;
                }
                if (arg.equals("--no-session")) {
                    restoreSession = false;
                    saveSession = false;
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
        portfile = File.getInstance(Directories.getRuntimeDirectory(), "port");
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

        loadPreferences();
        Log.initialize(dumpEnv, dumpProps);
        Extensions.load();
        if (quick == 0) {
            runStartupScript();
        }
        initMacOSX();

        sessionProperties = new SessionProperties();

        if (!alreadyRunning)
            Autosave.recover();

        tagFileManager = new TagFileManager();

        final boolean restore = restoreSession;
        final String session = sessionName;
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

        //if (isLispInitialized())
        //    LispThread.remove(Thread.currentThread());

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

        setCurrentEditor(new Editor(null));

        currentEditor.getFrame().updateControls();

        // With Java 1.4, we only need to do this to support the key-pressed
        // hook.
        FocusManager.setCurrentManager(new CustomFocusManager());

        currentEditor.getFrame().placeWindow();

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
            Buffer buf = currentEditor.openFiles(list);
            if (buf != null) {
                Debug.assertTrue(bufferList.contains(buf));
                toBeActivated = buf;
            }
        }

        if (toBeActivated == null)
            toBeActivated = new DirectoryBuffer(currentDir);

        currentEditor.activate(toBeActivated);
        currentEditor.getFrame().setVisible(true);
        Sidebar sidebar = currentEditor.getSidebar();
        if (sidebar != null)
            sidebar.setUpdateFlag(SIDEBAR_ALL);
    }

    private static final void usage() {
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

    private static final void version() {
        String longVersionString = Version.getLongVersionString();
        if (longVersionString != null)
            System.out.println(longVersionString);
        String snapshotInformation = Version.getSnapshotInformation();
        if (snapshotInformation != null)
            System.out.println(snapshotInformation);
    }

    /**
     * Commands that live in an extension rather than in core.
     *
     * <p>Without this, someone who upgrades gets a bare "Unknown command" for
     * something that worked the day before, with nothing to say where it
     * went.
     */
    private static final Map<String, String> commandProviders =
        Map.of("jlisp", "abcl");

    static String unknownCommandMessage(String command) {
        String extension = command == null
            ? null
            : commandProviders.get(command.toLowerCase());
        if (extension == null)
            return "Unknown command \"".concat(String.valueOf(command)).concat("\"");
        return "\"".concat(command)
            .concat("\" is provided by the ")
            .concat(extension)
            .concat(" extension, which is not installed.");
    }

    public static final void fatal(String message) {
        System.err.println(message);
        System.exit(1);
    }

    private static final void unknown(String arg) {
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
    private static final void initDoubleBufferSize() {
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

    private static final void initMacOSX() {
        if (!Platform.isPlatformMacOSX())
            return;

        if (Desktop.isDesktopSupported()) {

            // Use menu bar
            System.setProperty("apple.laf.useScreenMenuBar", "true");

            var desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_ABOUT))
                desktop.setAboutHandler(e -> AboutDialog.about());

            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER))
                desktop.setQuitHandler((e, r) -> Editor.currentEditor().quit());
        }
    }

    public Editor(Frame f) {
        display = new Display(this);
        dispatcher = new Dispatcher(this);
        init();
        frame = f != null ? f : new Frame(this);
    }

    /**
     * Creates an editor with no frame, for use without a display.
     *
     * The buffer, the caret and every editing primitive work normally; only
     * the window does not exist. Methods that would reach through to the
     * frame (status(), getStatusBar(), setFocusToDisplay()) are null-guarded
     * and do nothing. Package private: this is for tests and for exercising
     * the editing engine headlessly, not for ordinary use.
     */
    Editor() {
        display = new Display(this);
        dispatcher = new Dispatcher(this);
        init();
        frame = null;
    }

    private void init() {
        // Add this editor to the global editor list.
        editorList.add(this);

        setLayout(new BorderLayout());
        display.setDoubleBuffered(true);
        add(display, BorderLayout.CENTER);

        if (!GraphicsEnvironment.isHeadless())
            new DropTarget(display, dispatcher);

        addLocationBar();
        addVerticalScrollBar();
        maybeAddHorizontalScrollBar();

        display.addKeyListener(dispatcher);
        display.addMouseListener(dispatcher);
        display.addMouseMotionListener(dispatcher);

        addMouseWheelListener(this);
        addComponentListener(this);
    }

    public static final boolean isDebugEnabled() {
        return debug;
    }

    private LocationBar locationBar;

    public final LocationBar getLocationBar() {
        return locationBar;
    }

    public final int getLocationBarHeight() {
        if (locationBar != null)
            return locationBar.getHeight();
        else
            return 0;
    }

    public final HistoryTextField getLocationBarTextField() {
        return locationBar == null ? null : locationBar.getTextField();
    }

    public final void repaintLocationBar() {
        if (locationBar != null)
            locationBar.repaint();
    }

    public void addLocationBar() {
        if (locationBar == null) {
            locationBar = new LocationBar(this);
            add(locationBar, BorderLayout.NORTH);
        }
    }

    public void removeLocationBar() {
        if (locationBar != null) {
            remove(locationBar);
            locationBar = null;
        }
    }

    public void updateLocation() {
        if (locationBar != null) {
            HistoryTextField textField = locationBar.getTextField();
            if (textField == null || textField != frame.getFocusedComponent())
                locationBar.update();
        }
    }

    private MultiSplitLayout.Leaf layoutLeaf;

    /** The LayoutLeaf is used by the EditorPane to locate each Editor in the MultiSplitLayout. */
    MultiSplitLayout.Leaf getLayoutLeaf() {
        return layoutLeaf;
    }

    void setLayoutLeaf(MultiSplitLayout.Leaf layoutLeaf) {
        this.layoutLeaf = layoutLeaf;
    }

    // XXX: check usages. semantics changed from 'get the editor across the split' to 'get the paired editor or parent editor or null'
    public final Editor getOtherEditor() {
        return frame.getOtherEditor(this);
    }

    public final Editor getPairedEditor() {
        return frame.getPairedEditor(this);
    }

    public static int indexOf(Editor editor) {
        for (int i = getEditorCount() - 1; i >= 0; i--) {
            if (editor == getEditor(i))
                return i;
        }

        return -1;
    }

    public static final EditorList getEditorList() {
        return editorList;
    }

    public static final int getEditorCount() {
        return editorList.size();
    }

    public static final Editor getEditor(int i) {
        return editorList.get(i);
    }

    public static final void removeEditor(Editor editor) {
        editorList.remove(editor);
    }

    public static final void removeEditors(Collection<Editor> editors) {
        editorList.removeAll(editors);
    }

    public final Frame getFrame() {
        return frame;
    }

    // Returns height in lines.
    public int getWindowHeight() {
        return getDisplay().getHeight() / Display.getCharHeight();
    }

    public void setWindowHeight(int n) {
        frame.setWindowHeight(this, n);
    }

    private static final List<Frame> frames = new ArrayList<Frame>();

    static void addFrame(Frame frame) {
        frames.add(frame);
    }

    static void removeFrame(Frame frame) {
        frames.remove(frame);
    }

    public static int indexOf(Frame frame) {
        for (int i = getFrameCount() - 1; i >= 0; i--) {
            if (frame == getFrame(i))
                return i;
        }

        return -1;
    }

    public static final int getFrameCount() {
        return frames.size();
    }

    public static final Frame getFrame(int i) {
        if (i >= 0 && i < frames.size())
            return frames.get(i);
        return null;
    }

    public final Buffer getBuffer() {
        return buffer;
    }

    // Non-null only while this editor is showing a buffer whose editMode
    // asks for one. See getInputHandler().
    private InputHandler inputHandler;
    private String inputHandlerEditMode;

    /**
     * The thing that gets first refusal on every keystroke, or null in j's
     * ordinary non-modal editing.
     *
     * Resolved from the buffer's editMode each time it is asked for, so that
     * switching buffers, or reloading preferences, takes effect immediately.
     * Only ordinary text buffers get one: directory, image and compilation
     * buffers bind bare letters as commands already, and a modal layer on top
     * of them would make them unusable.
     */
    public final InputHandler getInputHandler() {
        if (buffer == null || buffer.getType() != SystemBuffer.TYPE_NORMAL)
            return null;
        final String editMode = buffer.getStringProperty(Property.EDIT_MODE);
        if (editMode == null || editMode.equals("simple"))
            return null;
        if (!editMode.equals(inputHandlerEditMode)) {
            inputHandler = "vim".equals(editMode)
                ? new org.armedbear.j.vim.VimInputHandler()
                : null;
            inputHandlerEditMode = editMode;
            if (inputHandler == null)
                Log.error("unknown editMode \"" + editMode + "\"");
        }
        return inputHandler;
    }

    /**
     * Points this editor at a buffer without any of the activation
     * bookkeeping that activate() does -- no loading, no cursor changes, no
     * dialogs, no sidebar update. Only for an editor with no frame, where
     * there is nothing to keep in sync. Use activate() everywhere else.
     */
    void setBufferDirectly(Buffer buf) {
        Debug.assertTrue(frame == null);
        buffer = buf;
    }

    public final Mode getMode() {
        return buffer.getMode();
    }

    public final int getModeId() {
        return buffer.getModeId();
    }

    public final Formatter getFormatter() {
        return buffer.getFormatter();
    }

    public final Display getDisplay() {
        return display;
    }

    public final Dispatcher getDispatcher() {
        return dispatcher;
    }

    public static final TagFileManager getTagFileManager() {
        return tagFileManager;
    }

    public final Sidebar getSidebar() {
        return frame != null ? frame.getSidebar() : null;
    }

    public final StatusBar getStatusBar() {
        return frame != null ? frame.getStatusBar() : null;
    }

    public static final PendingOperations getPendingOperations() {
        return pendingOperations;
    }

    private VerticalScrollBar verticalScrollBar;

    private VerticalScrollBarListener verticalScrollBarListener;

    public void addVerticalScrollBar() {
        if (verticalScrollBar == null) {
            verticalScrollBar = new VerticalScrollBar(this);
            verticalScrollBar.setMinimum(0);
            add(verticalScrollBar, BorderLayout.EAST);
            verticalScrollBarListener = new VerticalScrollBarListener(this, verticalScrollBar);
            verticalScrollBar.addAdjustmentListener(verticalScrollBarListener);
        }
    }

    public void removeVerticalScrollBar() {
        if (verticalScrollBar != null) {
            if (verticalScrollBarListener == null) {
                verticalScrollBar.removeAdjustmentListener(verticalScrollBarListener);
                verticalScrollBarListener = null;
            }
            remove(verticalScrollBar);
            verticalScrollBar = null;
        }
    }

    private HorizontalScrollBar horizontalScrollBar;

    public HorizontalScrollBar getHorizontalScrollBar() {
        return horizontalScrollBar;
    }

    private HorizontalScrollBarListener horizontalScrollBarListener;

    public void maybeAddHorizontalScrollBar() {
        if (horizontalScrollBar == null) {
            if (prefs.getBooleanProperty(Property.ENABLE_HORIZONTAL_SCROLL_BAR)) {
                horizontalScrollBar = new HorizontalScrollBar(this);
                horizontalScrollBar.setMinimum(0);
                JPanel panel = new JPanel();
                panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
                panel.add(horizontalScrollBar);
                final int height = horizontalScrollBar.getPreferredSize().height;
                panel.add(Box.createRigidArea(new Dimension(height, height)));
                add(panel, BorderLayout.SOUTH);
                horizontalScrollBarListener = new HorizontalScrollBarListener(this);
                horizontalScrollBar.addAdjustmentListener(horizontalScrollBarListener);
            }
        }
    }

    public void removeHorizontalScrollBar() {
        if (horizontalScrollBar != null) {
            if (horizontalScrollBarListener == null) {
                horizontalScrollBar.removeAdjustmentListener(horizontalScrollBarListener);
                horizontalScrollBarListener = null;
            }
            // Remove JPanel containing scroll bar.
            remove(horizontalScrollBar.getParent());
            horizontalScrollBar = null;
        }
    }

    public static Editor currentEditor() {
        return currentEditor;
    }

    // Lisp calls this off the event thread; the hooks run outside the lock.
    public static void setCurrentEditor(Editor editor) {
        final Editor oldCurrentEditor;
        synchronized (Editor.class) {
            oldCurrentEditor = currentEditor;
            currentEditor = editor;
        }
        if (editor.getFrame() != null)
            editor.getFrame().setCurrentEditor(editor);
        if (editor != oldCurrentEditor) {
            editor.repaintLocationBar();
            if (oldCurrentEditor != null)
                oldCurrentEditor.repaintLocationBar();
            Extensions.hooks().bufferActivated(editor.getBuffer());
        }
    }

    public static Buffer currentBuffer() {
        return currentEditor.buffer;
    }

    public static Frame getCurrentFrame() {
        return currentEditor.getFrame();
    }

    public static Editor createNewFrame() {
        Editor ed = new Editor(null);
        ed.getFrame().updateControls();
        ed.getFrame().placeWindow();
        return ed;
    }

    public void newFrame() {
        saveView();
        Editor ed = createNewFrame();
        ed.activate(buffer);
        ed.getFrame().setVisible(true);
        setCurrentEditor(ed);
        ed.updateDisplay();
        display.repaint();
        Runnable r = () -> {
            currentEditor.setFocusToDisplay();
        };
        SwingUtilities.invokeLater(r);
    }

    public View getCurrentView() {
        return views.get(buffer);
    }

    // Might return null.
    public final View getView(SystemBuffer buf) {
        return views.get(buf);
    }

    public final void setView(SystemBuffer buf, View view) {
        views.put(buf, view);
    }

    public void removeView(SystemBuffer buf) {
        views.remove(buf);
    }

    // Find or create a view of buf.
    private View findOrCreateView(Buffer buf) {
        View view = views.get(buf);
        if (view == null) {
            view = buf.getLastView();
            if (view == null)
                view = buf.getInitialView();
            views.put(buf, view);
        }
        return view;
    }

    public final void saveView() {
        buffer.saveView(this);
    }

    private final void restoreView() {
        buffer.restoreView(this);
    }

    public final Position getDot() {
        return dot;
    }

    public final Position getDotCopy() {
        return dot != null ? new Position(dot) : null;
    }

    public final void setDot(Position pos) {
        dot = pos;
    }

    public final void setDot(Line line, int offset) {
        dot = new Position(line, offset);
    }

    public void setDot(int lineNumber, int offset) {
        Line line = buffer.getLine(lineNumber);
        if (line != null)
            setDot(line, offset);
    }

    public final Line getDotLine() {
        return dot.getLine();
    }

    public final int getDotLineNumber() {
        return dot.lineNumber();
    }

    public final int getDotOffset() {
        return dot.getOffset();
    }

    public final Selection getSelection() {
        return selection;
    }

    public final void setSelection(Selection selection) {
        this.selection = selection;
    }

    public final Position getMark() {
        return mark;
    }

    public void setMarkAtDot() {
        setMark(new Position(dot));
        selection = new Selection();
    }

    public void setMark(Position pos) {
        mark = pos;
        if (mark == null) {
            selection = null;
            setColumnSelection(false);
        }
    }

    public void setMark(int lineNumber, int offset) {
        Line line = buffer.getLine(lineNumber);
        if (line != null)
            setMark(new Position(line, offset));
    }

    public final void setColumnSelection(boolean b) {
        isColumnSelection = b;
    }

    public final boolean isColumnSelection() {
        return isColumnSelection;
    }

    public final void notSupportedForColumnSelections() {
        MessageDialog.showMessageDialog(
            this,
            "Operation not supported for column selections",
            "Error"
        );
    }

    public final Line getMarkLine() {
        return mark.getLine();
    }

    public final int getMarkLineNumber() {
        return mark.lineNumber();
    }

    public final int getMarkOffset() {
        return mark.getOffset();
    }

    public File getCurrentDirectory() {
        return buffer.getCurrentDirectory();
    }

    public File getCompletionDirectory() {
        return buffer.getCompletionDirectory();
    }

    public void adjustMarkers(Line line) {
        if (line == null)
            return;
        Position pos = null; // Where all displaced markers go.
        if (line.next() != null)
            pos = new Position(line.next(), 0);
        else if (line.previous() != null)
            pos = new Position(line.previous(), line.previous().length());
        if (pos == null)
            return;
        for (int i = 0; i < Editor.getEditorCount(); i++) {
            Editor ed = Editor.getEditor(i);
            if (ed.getBuffer() == buffer) {
                if (ed.getDotLine() == line) {
                    ed.getDot().moveTo(pos);
                    ed.moveCaretToDotCol();
                }
                if (ed.getMark() != null && ed.getMarkLine() == line)
                    ed.setMark(null); // Take no chances.
                if (ed.getTopLine() == line) {
                    ed.setTopLine(pos.getLine());
                    ed.setUpdateFlag(REPAINT);
                }
            } else {
                // Not presently displayed, but possibly in a stored view.
                View view = ed.views.get(buffer);
                if (view != null) {
                    if (view.dot != null && view.dot.getLine() == line)
                        view.dot.moveTo(pos);
                    if (view.mark != null && view.mark.getLine() == line)
                        view.mark = null;
                    if (view.topLine == line)
                        view.topLine = pos.getLine();
                }
            }
        }
        for (int i = 0; i < bookmarks.length; i++) {
            Marker m = bookmarks[i];
            if (m != null && m.getLine() == line)
                m.setPosition(pos);
        }
    }

    public static Marker[] getBookmarks() {
        return bookmarks;
    }

    /** Where a bookmark's name is kept, or -1 for a name that is not one. */
    private static int bookmarkIndex(char name) {
        if (name >= '0' && name <= '9')
            return name - '0';
        if (name >= 'A' && name <= 'Z')
            return 11 + name - 'A';
        return -1;
    }

    /** A bookmark by name, 0 to 9 or A to Z, or null. */
    public static Marker getBookmark(char name) {
        final int index = bookmarkIndex(name);
        return index < 0 ? null : bookmarks[index];
    }

    /** Sets a bookmark by name, or with null forgets it. */
    public static void setBookmark(char name, Marker marker) {
        final int index = bookmarkIndex(name);
        if (index >= 0)
            bookmarks[index] = marker;
    }

    /** The digit key that ran the command, as the default bindings name it. */
    private String bookmarkKey() {
        final AWTEvent e = dispatcher.getLastEvent();
        if (e == null || e.getID() != KeyEvent.KEY_PRESSED)
            return null;
        final int digit = ((KeyEvent) e).getKeyCode() - KeyEvent.VK_0;
        return digit >= 0 && digit <= 9 ? String.valueOf(digit) : null;
    }

    /** {@code dropBookmark} -- by the digit key it is bound to. */
    public void dropBookmark() {
        dropBookmark(bookmarkKey());
    }

    /**
     * {@code dropBookmark NAME} -- a bookmark here, named 0 to 9 or A to Z,
     * asking before one already set is replaced.
     */
    public void dropBookmark(String name) {
        if (
            name == null
                || name.trim().length() != 1
                || bookmarkIndex(name.trim().charAt(0)) < 0
        ) {
            status("A bookmark is named 0 to 9 or A to Z");
            return;
        }
        final char c = name.trim().charAt(0);
        if (
            getBookmark(c) == null
                || confirm("Drop Bookmark", "Overwrite existing bookmark?")
        ) {
            setBookmark(c, new Marker(buffer, dot));
            status("Bookmark dropped");
        }
    }

    /** {@code gotoBookmark} -- by the digit key it is bound to. */
    public void gotoBookmark() {
        gotoBookmark(bookmarkKey());
    }

    /** {@code gotoBookmark NAME} -- to a bookmark, in whatever file it is. */
    public void gotoBookmark(String name) {
        final Marker m = name == null || name.trim().length() != 1
            ? null
            : getBookmark(name.trim().charAt(0));
        if (m != null)
            m.gotoMarker(this);
    }

    // Drop a temporary bookmark, overwriting the existing temporary bookmark
    // if one exists.
    public void dropTemporaryMarker() {
        bookmarks[10] = new Marker(buffer, dot);
        status("Temporary marker dropped");
    }

    public void gotoTemporaryMarker() {
        Marker m = bookmarks[10];
        if (m != null)
            m.gotoMarker(this);
    }

    public void deleteLineSeparator() {
        final Line dotLine = getDotLine();
        final Line nextLine = dotLine.next();
        if (nextLine == null)
            return;
        buffer.withWriteLock(() -> {
            if (dotLine.length() == 0) {
                adjustMarkers(dotLine);
                // Save original text.
                StringBuilder sb = new StringBuilder();
                if (dotLine.getOriginalText() != null)
                    sb.append(dotLine.getOriginalText());
                sb.append('\n');
                if (nextLine.getOriginalText() != null)
                    sb.append(nextLine.getOriginalText());
                else
                    sb.append(nextLine.getText());
                nextLine.setOriginalText(sb.toString());
                // Unlink the current line.
                final Line prevLine = dotLine.previous();
                if (prevLine != null)
                    prevLine.setNext(nextLine);
                nextLine.setPrevious(prevLine);
                if (dotLine == buffer.getFirstLine()) {
                    Log.debug("deleteLineSeparator calling buffer.setFirstLine()");
                    buffer.setFirstLine(nextLine);
                    Log.debug("first line = |" + buffer.getFirstLine().getText() + "|");
                }
                if (dotLine == display.getTopLine())
                    display.setTopLine(nextLine);
                dot.moveTo(nextLine, 0);
            } else {
                // Append the next line's text to end of this line.
                dotLine.setText(dotLine.getText() + nextLine.getText());
                // Save original text.
                StringBuilder sb = new StringBuilder();
                if (dotLine.getOriginalText() != null)
                    sb.append(dotLine.getOriginalText());
                else
                    sb.append(dotLine.getText());
                if (!nextLine.isNew()) {
                    sb.append('\n');
                    if (nextLine.getOriginalText() != null)
                        sb.append(nextLine.getOriginalText());
                    else
                        sb.append(nextLine.getText());
                }
                dotLine.setOriginalText(sb.toString());
                // Move any markers that might be on the next line.
                adjustMarkers(nextLine);
                // Unlink the next line.
                if (nextLine.next() != null)
                    nextLine.next().setPrevious(dotLine);
                dotLine.setNext(nextLine.next());
            }
            buffer.repaint();
            setUpdateFlag(REFRAME);
            buffer.needsRenumbering = true;
            buffer.modified();
        });
    }

    void deleteNormalChar() {
        addUndo(SimpleEdit.LINE_EDIT);
        final Line dotLine = getDotLine();
        final int dotOffset = getDotOffset();
        String head = dotLine.substring(0, dotOffset);
        String tail = "";
        if (dotOffset < dotLine.length() - 1)
            tail = dotLine.substring(dotOffset + 1);
        dotLine.setText(head.concat(tail));
        buffer.modified();
        updateInAllEditors(dotLine);
    }

    // A deletion, not a kill!
    public void delete() {
        if (!checkReadOnly())
            return;
        buffer.withWriteLock(() -> {
            if (mark != null) {
                deleteRegion();
            } else {
                final Line dotLine = getDotLine();
                final int dotOffset = getDotOffset();
                final int length = dotLine.length();
                if (dotOffset < length) {
                    deleteNormalChar();
                } else if (dotOffset == length) {
                    if (dotLine.next() != null) {
                        CompoundEdit compoundEdit = beginCompoundEdit();
                        fillToCaret();
                        addUndo(SimpleEdit.DELETE_LINE_SEP);
                        deleteLineSeparator();
                        endCompoundEdit(compoundEdit);
                    } else
                        status("End of buffer");
                } else {
                    // Shouldn't happen.
                    Debug.bug();
                }
            }
        });
    }

    // A deletion, not a kill!
    public void backspace() {
        if (!checkReadOnly())
            return;
        buffer.withWriteLock(() -> {
            if (mark != null) {
                delete();
            } else if (display.getCaretCol() > buffer.getCol(getDotLine(), getDotLine().length())) {
                // The caret is beyond the end of the actual text on the current line.
                addUndo(SimpleEdit.MOVE);
                display.setCaretCol(display.getCaretCol() - 1);
                updateDotLine();
            } else if (dot.getOffset() > 0) {
                addUndo(SimpleEdit.LINE_EDIT);
                dot.moveLeft();
                deleteNormalChar();
                moveCaretToDotCol();
            } else if (getDotLine().previous() != null) {
                CompoundEdit compoundEdit = beginCompoundEdit();
                addUndo(SimpleEdit.MOVE);
                dot.moveTo(getDotLine().previous(), getDotLine().previous().length());
                addUndo(SimpleEdit.DELETE_LINE_SEP);
                deleteLineSeparator();
                endCompoundEdit(compoundEdit);
                moveCaretToDotCol();
            }
        });
    }

    public char getDotChar() {
        Debug.assertTrue(dot != null);
        return dot.getChar();
    }

    public void cppFindMatch() {
        if (getDotLine().trim().startsWith("#")) {
            Line line = CMode.findMatchPreprocessor(getDotLine());
            if (line != null)
                moveDotTo(line, 0);
            else
                status("No match");
        } else
            findMatchingChar();
    }

    // If numLines is non-zero, limit the search to that many lines either
    // forward or backward in the buffer.
    public Position findMatchInternal(Position start, int numLines) {
        return findMatchInternal(start, numLines, false);
    }

    /**
     * With vim, match as vim's % does ('cpoptions' without %): the start may
     * be in a string or escaped, and a bracket is skipped when it is inside
     * "..." counted from the start, in a 'x' literal, or escaped differently
     * from the start.
     */
    public Position findMatchInternal(Position start, int numLines, boolean vim) {
        if (start == null)
            return null;
        final String s1 = new String("{([})]");
        final char origChar = start.getChar();
        int index = s1.indexOf(origChar);
        if (index < 0)
            return null;
        final Mode mode = buffer.getMode();
        final int offset = start.getOffset();
        if (!vim) {
            if (mode.isInComment(buffer, start) || mode.isInQuote(buffer, start))
                return null;
            if (offset > 0 && start.getLine().charAt(offset - 1) == '\\') {
                // It's escaped.
                return null;
            }
        }
        final String s2 = new String("})]{([");
        return scanForMatch(
            start,
            origChar,
            s2.charAt(index),
            index > 2,
            numLines,
            vim,
            isEscaped(start.getLine(), offset)
        );
    }

    /**
     * The bracket still open at {@code start}, as vim's [( and ]) find it:
     * with '(', '[' or '{' the one before, with ')', ']' or '}' the one
     * after. The character at start does not count. With vim, brackets are
     * skipped as {@link #findMatchInternal(Position, int, boolean)} skips
     * them, but for an escaped start: vim does not look at the caret's.
     */
    public Position findUnmatched(Position start, char bracket, boolean vim) {
        final int index = "{([})]".indexOf(bracket);
        if (index < 0)
            return null;
        // Back to a '(' counts the ')'s on the way, forward the '('s.
        return scanForMatch(
            start,
            "})]{([".charAt(index),
            bracket,
            index < 3,
            0,
            vim,
            false
        );
    }

    /**
     * The other end of the string a quote at pos opens or closes, as the
     * mode's isInQuote sees strings, or null. If numLines is non-zero, looks
     * no further than that many lines away.
     */
    public Position findMatchingQuote(Position pos, int numLines) {
        final char c = pos.getChar();
        if (c != '"' && c != '\'' && c != '`')
            return null;
        final Line line = pos.getLine();
        final int offset = pos.getOffset();
        if (isEscaped(line, offset))
            return null;
        // The apostrophe of a word.
        if (
            c == '\''
                && offset > 0
                && offset + 1 < line.length()
                && Character.isLetter(line.charAt(offset - 1))
                && Character.isLetter(line.charAt(offset + 1))
        )
            return null;
        final Mode mode = buffer.getMode();
        if (mode.isInComment(buffer, pos))
            return null;
        final boolean closing = mode.isInQuote(buffer, pos);
        if (!closing && !mode.isInQuote(buffer, new Position(line, offset + 1)))
            return null;
        final Position p = new Position(pos);
        while (closing ? p.prev() : p.next()) {
            if (
                numLines != 0
                    &&
                    Math.abs(p.lineNumber() - pos.lineNumber()) > numLines
            )
                return null;
            if (
                p.getOffset() < p.getLine().length()
                    && p.getChar() == c
                    && !isEscaped(p.getLine(), p.getOffset())
            ) {
                // The first one there must be the string's other end.
                if (mode.isInQuote(buffer, p) != closing)
                    return p;
                return null;
            }
        }
        return null;
    }

    /** The first match after start not paired with an origChar on the way. */
    private Position scanForMatch(
        Position start,
        char origChar,
        char match,
        boolean searchBackwards,
        int numLines,
        boolean vim,
        boolean escaped
    ) {
        final Mode mode = buffer.getMode();
        int stopLineNumber = searchBackwards ? 0 : buffer.getLineCount();
        if (numLines != 0)
            stopLineNumber = searchBackwards ? start.lineNumber() - numLines : start.lineNumber() + numLines;
        int count = 1;
        final SyntaxIterator it = mode.getSyntaxIterator(start);
        while (true) {
            char c;
            Position pos = it.getPosition();
            if (searchBackwards) {
                if (pos.lineNumber() < stopLineNumber)
                    return null;
                else
                    c = it.prevChar();
            } else {
                if (pos.lineNumber() > stopLineNumber)
                    return null;
                else
                    c = it.nextChar();
            }
            if (c == SyntaxIterator.DONE)
                return null;
            if (
                vim
                    && (c == origChar || c == match)
                    && isSkippedByVim(start, it.getPosition(), escaped)
            )
                continue;
            if (c == origChar)
                ++count;
            else if (c == match)
                --count;
            if (count == 0) {
                // Found it!
                return it.getPosition();
            }
        }
    }

    private static boolean isSkippedByVim(
        Position start,
        Position pos,
        boolean startEscaped
    ) {
        final Line line = pos.getLine();
        final int offset = pos.getOffset();
        if (isEscaped(line, offset) != startEscaped)
            return true;
        final String text = line.getText();
        if (text == null)
            return false;
        // 'x' and '\x'.
        if (
            offset + 1 < text.length()
                && text.charAt(offset + 1) == '\''
                && (offset >= 1 && text.charAt(offset - 1) == '\''
                    || offset >= 2
                        && text.charAt(offset - 2) == '\''
                        && text.charAt(offset - 1) == '\\')
        )
            return true;
        // Quotes say nothing on a line with an odd number of them. Counted
        // from the start on its own line, from the line's start on others.
        if (!hasEvenQuotes(text))
            return false;
        int from = 0;
        int to = offset;
        if (line == start.getLine()) {
            from = Math.min(start.getOffset(), offset) + 1;
            to = Math.max(start.getOffset(), offset);
        }
        boolean inQuote = false;
        for (int i = from; i < to; i++)
            if (isQuote(text, i) && !isEscaped(line, i))
                inQuote = !inQuote;
        return inQuote;
    }

    /** After an odd number of backslashes. */
    private static boolean isEscaped(Line line, int offset) {
        int i = offset;
        while (i > 0 && line.charAt(i - 1) == '\\')
            --i;
        return ((offset - i) & 1) != 0;
    }

    /** A double quote, but not the one in the literal '"'. */
    private static boolean isQuote(String text, int i) {
        return text.charAt(i) == '"'
            && (i == 0
                || text.charAt(i - 1) != '\''
                || i + 1 == text.length()
                || text.charAt(i + 1) != '\'');
    }

    /** Vim's count, which leaves out \" and '"'. */
    private static boolean hasEvenQuotes(String text) {
        int quotes = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isQuote(text, i))
                ++quotes;
            else if (text.charAt(i) == '\\' && i + 1 < text.length())
                ++i;
        }
        return (quotes & 1) == 0;
    }

    public void findMatchingChar() {
        setWaitCursor();
        Position pos = findDelimiterNearDot();
        if (pos != null) {
            Position match = findMatchInternal(pos, 0);
            if (match != null) {
                // If the match is a right delimiter, we want to put the caret
                // beyond it.
                if ("})]".indexOf(match.getChar()) >= 0)
                    match.next();
                beginMotion();
                updateDotLine();
                dot.moveTo(match);
                updateDotLine();
                moveCaretToDotCol();
            } else
                status("No match");
        }
        setDefaultCursor();
    }

    public void selectSyntax() {
        setWaitCursor();
        Position pos = findDelimiterNearDot();
        if (pos != null) {
            Position match = findMatchInternal(pos, 0);
            if (match != null) {
                if ("})]".indexOf(pos.getChar()) >= 0)
                    pos.next();
                else if ("})]".indexOf(match.getChar()) >= 0)
                    match.next();
                if (pos.getLine() != match.getLine()) {
                    // Extend selection to full lines if possible.
                    Region r = new Region(buffer, pos, match);
                    Position begin = r.getBegin();
                    String trim =
                        begin.getLine().substring(0, begin.getOffset()).trim();
                    if (trim.length() == 0) {
                        Position end = r.getEnd();
                        trim = end.getLine().substring(end.getOffset()).trim();
                        if (trim.length() == 0) {
                            // Extend selection to complete lines.
                            begin.setOffset(0);
                            if (end.getNextLine() != null)
                                end.moveTo(end.getNextLine(), 0);
                            else
                                end.setOffset(end.getLineLength());
                            if (pos.isBefore(match)) {
                                pos = begin;
                                match = end;
                            } else {
                                match = begin;
                                pos = end;
                            }
                        }
                    }
                }
                beginMotion();
                dot.moveTo(pos);
                setMarkAtDot();
                updateDotLine();
                dot.moveTo(match);
                updateDotLine();
                moveCaretToDotCol();
                if (dot.getLine() != mark.getLine())
                    setUpdateFlag(REPAINT);
            } else
                status("No match");
        }
        setDefaultCursor();
    }

    private Position findDelimiterNearDot() {
        Position pos = dot.copy();
        if ("{([".indexOf(pos.getChar()) >= 0) {
            // The character to the right of the caret is a left delimiter.
            return pos;
        }
        Position saved = dot.copy();
        if (pos.getOffset() > 0) {
            pos.prev();
            if ("})]".indexOf(pos.getChar()) >= 0) {
                // The character to the left of the caret is a right delimiter.
                return pos;
            }
        }
        // There's no delimiter at the exact location of the caret.
        final String delimiters = "{([})]";
        pos.moveTo(saved);
        while (pos.getOffset() > 0) {
            // Look at previous char.
            pos.prev();
            char c = pos.getChar();
            if (delimiters.indexOf(c) >= 0)
                return pos;
            if (!Character.isWhitespace(c) && c != ';')
                break;
        }
        pos.moveTo(saved);
        final int limit = pos.getLineLength();
        while (pos.getOffset() < limit) {
            char c = pos.getChar();
            if (delimiters.indexOf(c) >= 0)
                return pos;
            if (!Character.isWhitespace(c))
                return null;
            // Look at next char.
            pos.next();
        }
        return null;
    }

    // closeParen's highlight of the matching paren, while it shows.
    javax.swing.Timer parenFlash;
    /*package*/ static int parenFlashMillis = 300;

    // No undo.
    public void insertLineSeparator() {
        Debug.assertTrue(mark == null);
        if (!buffer.withWriteLock(() -> {
            buffer.insertLineSeparator(dot);
        }))
            return;
        final Line dotLine = getDotLine();
        for (int i = 0; i < getEditorCount(); i++) {
            Editor ed = getEditor(i);
            if (ed.getTopLine() == dotLine)
                ed.setTopLine(dotLine.previous());
        }
    }

    public void newline() {
        if (!checkReadOnly())
            return;
        CompoundEdit compoundEdit = beginCompoundEdit();
        if (mark != null)
            deleteRegion();
        addUndo(SimpleEdit.INSERT_LINE_SEP);
        insertLineSeparator();
        moveCaretToDotCol();
        endCompoundEdit(compoundEdit);
    }

    public void newlineAndIndent() {
        if (isColumnSelection()) {
            notSupportedForColumnSelections();
            return;
        }
        if (!checkReadOnly())
            return;
        if (!buffer.withWriteLock(() -> {
            CompoundEdit compoundEdit = beginCompoundEdit();
            if (mark != null)
                deleteRegion();
            addUndo(SimpleEdit.INSERT_LINE_SEP);
            insertLineSeparator();
            final Mode mode = getMode();
            final Line dotLine = getDotLine();
            int indent;
            if (mode.canIndent()) {
                if (buffer.needsRenumbering())
                    buffer.renumber();
                getFormatter().parseBuffer();
                indent = mode.getCorrectIndentation(dotLine, buffer);
            } else {
                // Can't indent according to context. Match indentation of previous line.
                indent = buffer.getIndentation(dotLine.previous());
            }
            if (indent != buffer.getIndentation(dotLine)) {
                addUndo(SimpleEdit.LINE_EDIT);
                buffer.setIndentation(dotLine, indent);
            }
            if (dotLine.length() > 0) {
                IndentCommands.moveDotToIndentation(this);
                moveCaretToDotCol();
            } else {
                display.setCaretCol(indent - display.getShift());
                if (buffer.getBooleanProperty(Property.RESTRICT_CARET))
                    fillToCaret();
            }
            endCompoundEdit(compoundEdit);
        }))
            return;
        setUpdateFlag(REFRAME);
    }

    public void insertNormalChar(char c) {
        if (isColumnSelection()) {
            notSupportedForColumnSelections();
            return;
        }
        if (!checkReadOnly())
            return;
        try {
            buffer.lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            c = getMode().fixCase(this, c);
            if (mark != null) {
                CompoundEdit compoundEdit = beginCompoundEdit();
                deleteRegion();
                insertChar(c);
                endCompoundEdit(compoundEdit);
            } else {
                // No selection.
                if (
                    buffer.getBooleanProperty(Property.WRAP)
                        &&
                        getDotCol() >= buffer.getIntegerProperty(Property.WRAP_COL)
                ) {
                    CompoundEdit compoundEdit = beginCompoundEdit();
                    insertChar(c);
                    new WrapText(this).wrapLine();
                    endCompoundEdit(compoundEdit);
                } else
                    insertChar(c);
            }
        }
        finally {
            buffer.unlockWrite();
        }
        moveCaretToDotCol();
    }

    public final void moveDotTo(Position pos) {
        if (pos != null)
            moveDotTo(pos.getLine(), pos.getOffset());
    }

    public void moveDotTo(Line line, int offset) {
        if (dot == null)
            return;
        addUndo(SimpleEdit.MOVE);
        if (mark != null) {
            setMark(null);
            dot.moveTo(line, offset);
            setUpdateFlag(REPAINT);
        } else {
            updateDotLine();
            dot.moveTo(line, offset);
            updateDotLine();
        }
        moveCaretToDotCol();
    }

    public void save() {
        save(buffer);
    }

    public void save(Buffer toBeSaved) {
        if (toBeSaved.isLocked())
            return;
        if (toBeSaved.getType() == Buffer.TYPE_NORMAL) {
            if (toBeSaved.isModified()) {
                if (toBeSaved.isUntitled()) {
                    saveAs(toBeSaved);
                } else {
                    setWaitCursor();
                    status("Saving...");
                    if (toBeSaved.getBooleanProperty(Property.REMOVE_TRAILING_WHITESPACE))
                        toBeSaved.removeTrailingWhitespace();
                    if (toBeSaved.save())
                        status("Saving...done");
                    else
                        status("Save failed");
                    setDefaultCursor();
                }
            } else
                status("Not modified");
        }
    }

    public void saveAs() {
        saveAs(buffer);
    }

    /**
     * {@code saveAs FILE} saves to FILE and renames the buffer to it, without
     * the dialog -- which is also what vim's {@code :w FILE} does to a buffer
     * that has no name yet. A relative name is taken from the buffer's own
     * directory.
     *
     * @return true when the buffer was saved
     */
    public boolean saveAs(String path) {
        if (path == null || path.trim().isEmpty()) {
            saveAs();
            return !buffer.isModified();
        }
        final File destination = fileNamed(path.trim());
        if (destination == null)
            return false;
        return saveAsTo(buffer, destination);
    }

    /** A path typed by the user, resolved against this buffer's directory. */
    public File fileNamed(String path) {
        final File dir = buffer.getCurrentDirectory();
        return dir == null
            ? File.getInstance(path)
            : File.getInstance(dir, path);
    }

    private void saveAs(Buffer toBeSaved) {
        if (toBeSaved.isLocked())
            return;
        if (toBeSaved.getType() == Buffer.TYPE_NORMAL) {
            final String dialogTitle = "Save As";
            File destination =
                SaveFileDialog.getSaveFile(this, dialogTitle);
            if (destination == null)
                return;

            // At this point, if the target file exists, the user has said
            // it's OK to overwrite it.
            repaintNow();
            saveAsTo(toBeSaved, destination);
        }
    }

    /** The checks and the save shared by saveAs, with and without a dialog. */
    private boolean saveAsTo(Buffer toBeSaved, File destination) {
        if (
            toBeSaved.isLocked()
                || toBeSaved.getType() != Buffer.TYPE_NORMAL
        )
            return false;
        final String dialogTitle = "Save As";
        // Do we have the target file in a buffer?
        Buffer buf = bufferList.findBuffer(destination);
        if (buf != null) {
            // We do. Can we just get rid of it?
            if (!buf.isModified()) {
                buf.deleteAutosaveFile();
                bufferList.remove(buf);
            } else {
                // Buffer is modified.  Make user deal with it.
                setDefaultCursor();
                String message = "Target file is in an active buffer.  Please take care of that first.";
                MessageDialog.showMessageDialog(this, message, dialogTitle);
                return false;
            }
        }

        toBeSaved.saveAs(destination);
        return !toBeSaved.isModified();
    }

    /**
     * {@code saveCopy FILE} writes the buffer to FILE and leaves it named as
     * it was, without the dialog -- vim's {@code :w FILE} on a buffer that
     * already has a name.
     *
     * @return true when the copy was written
     */
    public boolean saveCopy(String path) {
        if (path == null || path.trim().isEmpty())
            return false;
        final File destination = fileNamed(path.trim());
        return destination != null && saveCopyTo(destination);
    }

    public void saveCopy() {
        if (buffer.isLocked())
            return;
        if (buffer.getType() == Buffer.TYPE_NORMAL) {
            final String dialogTitle = "Save Copy";
            final File destination =
                SaveFileDialog.getSaveFile(this, dialogTitle);
            if (destination == null)
                return;

            repaintNow();
            saveCopyTo(destination);
        }
    }

    /** The checks and the write shared by saveCopy, with and without a dialog. */
    private boolean saveCopyTo(File destination) {
        if (buffer.isLocked() || buffer.getType() != Buffer.TYPE_NORMAL)
            return false;
        // Do we have the target file in a buffer?
        Buffer buf = bufferList.findBuffer(destination);
        if (buf != null) {
            // We do.  Do we care?
            if (buf.isModified()) {
                // Buffer is modified.  Make user deal with it.
                setDefaultCursor();
                String message = "Target file is in an active buffer.  Please take care of that first.";
                MessageDialog.showMessageDialog(this, message, "Save Copy");
                return false;
            }
        }

        buffer.saveCopy(destination);
        if (buf != null && buf.isLoaded())
            reload(buf);
        return true;
    }

    public void saveAll() {
        setWaitCursor();
        int numModified = 0;
        int numErrors = 0;
        for (Buffer buf : Editor.getBufferList()) {
            if (buf.getModeId() == CHECKIN_MODE)
                continue;
            if (buf.isUntitled()) {
                setDefaultCursor();
                makeNext(buf);
                activate(buf);
                saveAs();
                setWaitCursor();
            } else if (buf.isModified()) {
                status("Saving modified buffers...");
                ++numModified;
                if (buffer.getFile() != null)
                    if (buffer.getBooleanProperty(Property.REMOVE_TRAILING_WHITESPACE))
                        buffer.removeTrailingWhitespace();
                if (!buf.save())
                    ++numErrors;
            }
        }
        if (numModified == 0)
            status("No modified buffers");
        else if (numErrors == 0)
            status("Saving modified buffers...done");
        else {
            // User will already have seen detailed error information from Buffer.save().
            status("Unable to save all modified buffers");
        }
        setDefaultCursor();
    }

    public boolean okToClose(Buffer buf) {
        if (buf.getType() != Buffer.TYPE_NORMAL)
            return true;
        if (buf.isUntitled() || buf.isModified()) {
            makeNext(buf);
            activate(buf);
            repaintNow();
            if (!CloseBufferConfirmationDialog.confirmClose(this, buf))
                return false;
        }
        return true;
    }

    public void closeAll() {
        repaintNow();

        for (Buffer buf : Editor.getBufferList()) {
            if (!okToClose(buf))
                return;
        }

        Marker.invalidateAllMarkers();

        Buffer toBeActivated = null;

        for (Buffer buf : Editor.getBufferList()) {
            if (buf instanceof DirectoryBuffer && buf.getFile().equals(getCurrentDirectory())) {
                toBeActivated = buf;
                break;
            }
        }

        if (toBeActivated == null)
            toBeActivated = new DirectoryBuffer(getCurrentDirectory());

        setWaitCursor();

        for (int i = 0; i < getEditorCount(); i++) {
            Editor ed = getEditor(i);
            ed.activate(toBeActivated);
        }

        for (Buffer buf : Editor.getBufferList()) {
            if (buf != toBeActivated) {
                for (Editor ed : Editor.getEditorList()) {
                    ed.views.remove(buf);
                }
                buf.deleteAutosaveFile();
                Editor.getBufferList().remove(buf);
                buf.dispose();
            }
        }

        setSessionName(null);

        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_BUFFER_LIST_ALL);
        Sidebar.refreshSidebarInAllFrames();
        setDefaultCursor();
    }

    public void closeOthers() {
        repaintNow();

        Buffer toBeActivated = buffer;

        for (Buffer buf : Editor.getBufferList()) {
            if (buf != buffer && !okToClose(buf))
                return;
        }

        List<Marker> markers = Marker.getAllMarkers();
        for (int i = 0; i < markers.size(); i++) {
            Marker m = markers.get(i);
            if (m != null && m.getBuffer() != buffer)
                m.invalidate();
        }

        setWaitCursor();

        for (Editor ed : Editor.getEditorList())
            ed.activate(toBeActivated);

        for (Buffer buf : Editor.getBufferList()) {
            if (buf != buffer) {
                for (Editor ed : Editor.getEditorList()) {
                    ed.views.remove(buf);
                }
                buf.deleteAutosaveFile();
                Editor.getBufferList().remove(buf);
                buf.dispose();
            }
        }

        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_BUFFER_LIST_ALL);
        Sidebar.refreshSidebarInAllFrames();
        setDefaultCursor();
    }

    public boolean execute(String command) throws NoSuchMethodException {
        String[] array = parseCommand(command);
        if (array == null)
            return false;
        String methodName = array[0];
        String parameters = array[1];
        return execute(methodName, parameters);
    }

    /** Runs the named command. Throws NoSuchMethodException if there is none, or it takes no such argument. */
    public boolean execute(String commandName, String parameters) throws NoSuchMethodException {
        if (commandName == null)
            return false;
        Command command = CommandTable.getCommand(commandName);
        if (command == null)
            throw new NoSuchMethodException(commandName);
        return execute(command, parameters);
    }

    /** Runs the command. A failure inside it is logged, and still counts as handled. */
    public boolean execute(Command command, String parameters) throws NoSuchMethodException {
        try {
            if (!command.run(this, parameters))
                throw new NoSuchMethodException(command.getName());
        }
        catch (VirtualMachineError e) {
            throw e;
        }
        catch (RuntimeException | Error e) {
            Log.error(e);
        }
        return true;
    }

    // FIXME Removed hard-coded Control G!
    public static boolean checkKeyboardQuit(Object object) {
        if (object instanceof JEvent) {
            JEvent e = (JEvent) object;
            if (e.getID() == JEvent.KEY_PRESSED) {
                if (e.getKeyCode() == 0x47 && e.getModifiers() == CTRL_MASK)
                    return true;
            }
            return false;
        }
        if (object instanceof KeyEvent) {
            KeyEvent e = (KeyEvent) object;
            if (e.getID() == KeyEvent.KEY_PRESSED) {
                if (e.getKeyCode() == 0x47 && Keys.keyModifiers(e) == CTRL_MASK)
                    return true;
            }
            return false;
        }
        return false;
    }

    private KeyMap requestedKeyMap;
    private EventSequence currentEventSequence;
    private boolean local;

    public boolean handleJEvent(JEvent event) {
        char keyChar = event.getKeyChar();
        int keyCode = event.getKeyCode();
        int modifiers = event.getModifiers();
        if (insertingKeyText) {
            insertKeyTextInternal(keyChar, keyCode, modifiers);
            return true;
        }
        // Modal editing gets first refusal, but stays out of a key sequence
        // that is already in progress so that Emacs-style prefix keys and
        // keyboardQuit keep working.
        if (requestedKeyMap == null) {
            final InputHandler handler = getInputHandler();
            if (handler != null) {
                switch (handler.handle(this, event)) {
                    case CONSUMED:
                        return true;
                    case DEFER:
                        // Skip the key maps and wait for the key typed event,
                        // which is the one that says which character it is.
                        return false;
                    case PASS_THROUGH:
                        break;
                }
            }
        }
        return handleKeyMapEvent(event);
    }

    /**
     * Runs whatever j's own key maps bind an event to, with no input handler
     * in front of them. For an input handler that wants j's binding for a
     * key and to do something after it, as vim edit mode does with Enter.
     *
     * @return false when nothing is bound
     */
    public boolean handleKeyMapEvent(JEvent event) {
        final char keyChar = event.getKeyChar();
        final int keyCode = event.getKeyCode();
        final int modifiers = event.getModifiers();
        KeyMapping mapping = null;
        if (requestedKeyMap != null) {
            if (checkKeyboardQuit(event)) {
                requestedKeyMap = null;
                currentEventSequence = null;
                local = false;
                status("");
                return false;
            }
            mapping = requestedKeyMap.lookup(keyChar, keyCode, modifiers);
            // "When both the global and local definitions of a key are other
            // keymaps, the next character is looked up in both keymaps, with
            // the local definition overriding the global one. The character
            // after the `C-x' is looked up in both the major mode's own keymap
            // for redefined `C-x' commands and in `ctl-x-map'. If the major
            // mode's own keymap for `C-x' commands contains `nil', the
            // definition from the global keymap for `C-x' commands is used."
            // (from the documentation for xemacs 21.4.17)
            if (mapping == null && local) {
                // Not found in local keymap.
                EventSequence copy = currentEventSequence.copy();
                copy.addEvent(event);
                mapping = KeyMap.getGlobalKeyMap().lookupEventSequence(copy);
            }
        } else {
            // Look in mode-specific key map.
            mapping =
                buffer.getMode().getKeyMap().lookup(keyChar, keyCode, modifiers);
            if (mapping != null)
                local = true;
            else
                // Look in global key map.
                mapping = KeyMap.getGlobalKeyMap()
                    .lookup(
                        keyChar,
                        keyCode,
                        modifiers
                    );
        }
        if (mapping == null) {
            if (event.getID() == JEvent.KEY_TYPED) {
                // Reset.
                requestedKeyMap = null;
                currentEventSequence = null;
                local = false;
            }
            return false;
        }
        if (mapping != null) {
            Object command = mapping.getCommand();
            if (command instanceof KeyMap) {
                // Emacs-style key sequence.
                if (currentEventSequence == null)
                    currentEventSequence = new EventSequence();
                currentEventSequence.addEvent(event);
                requestedKeyMap = (KeyMap) command;
                status(currentEventSequence.getStatusText() + "-");
                return true;
            }
            if (isRecordingMacro())
                Macro.record(this, command);
            if (command instanceof String) {
                requestedKeyMap = null;
                currentEventSequence = null;
                local = false;
                String commandString = (String) command;
                if (commandString.length() > 0 && commandString.charAt(0) == '(') {
                    // A Lisp form.
                    executeCommand(commandString);
                    return true;
                }
                String[] array = parseCommand(commandString);
                if (array != null) {
                    String methodName = array[0];
                    String parameters = array[1];
                    try {
                        return execute(methodName, parameters);
                    }
                    catch (NoSuchMethodException e) {}
                }
            } else if (command instanceof Command) {
                requestedKeyMap = null;
                currentEventSequence = null;
                local = false;
                Command c = (Command) command;
                try {
                    execute(c, null);
                }
                catch (Throwable t) {
                    Log.error(t);
                }
                return true;
            } else if (command instanceof ScriptFunction) {
                requestedKeyMap = null;
                currentEventSequence = null;
                local = false;
                try {
                    ((ScriptFunction) command).invoke();
                }
                catch (Throwable t) {
                    Log.error(t);
                }
                return true;
            }
        }
        return false;
    }

    public KeyMapping getKeyMapping(char keyChar, int keyCode, int modifiers) {
        if (requestedKeyMap != null)
            return requestedKeyMap.lookup(keyChar, keyCode, modifiers);
        // Look in mode-specific key map.
        KeyMapping mapping =
            buffer.getMode().getKeyMap().lookup(keyChar, keyCode, modifiers);
        if (mapping != null)
            return mapping;
        // Look in global key map.
        return KeyMap.getGlobalKeyMap().lookup(keyChar, keyCode, modifiers);
    }

    // Returns multiple values: mapping, mode.
    // mode == null means it's a global mapping.
    public Object[] getKeyMapping(String command) {
        Mode mode = null;
        // Look in buffer-local keymap first.
        KeyMapping mapping = buffer.getKeyMapForMode().getKeyMapping(command);
        // If not found there, try global keymap.
        if (mapping == null) {
            mapping = KeyMap.getGlobalKeyMap().getKeyMapping(command);
            // Don't let a global mapping hide a different mapping of the same
            // keystroke in the buffer-local keymap!
            if (mapping != null) {
                javax.swing.KeyStroke keyStroke =
                    javax.swing.KeyStroke.getKeyStroke(
                        mapping.getKeyCode(),
                        mapping.getModifiers()
                    );
                if (buffer.getKeyMapForMode().lookup(keyStroke) != null)
                    mapping = null;
            }
        } else
            mode = getMode();
        Object[] values = new Object[2];
        values[0] = mapping;
        values[1] = mode;
        return values;
    }

    public final int getAbsoluteCaretCol() {
        return display.getAbsoluteCaretCol();
    }

    public final void setAbsoluteCaretCol(int col) {
        display.setAbsoluteCaretCol(col);
    }

    private int goalColumn;

    public final void setGoalColumn(int col) {
        goalColumn = col;
    }

    public void moveDotToGoalCol() {
        if (buffer.getBooleanProperty(Property.RESTRICT_CARET)) {
            final int limit =
                buffer.getCol(getDotLine(), getDotLine().length());
            moveDotToCol(goalColumn > limit ? limit : goalColumn);
            moveCaretToDotCol();
        } else {
            setAbsoluteCaretCol(goalColumn);
            moveDotToCaretCol();
        }
    }

    void maybeResetGoalColumn() {
        switch (lastCommand) {
            case COMMAND_UP:
            case COMMAND_DOWN:
            case COMMAND_PAGE_UP:
            case COMMAND_PAGE_DOWN:
            case COMMAND_WINDOW_UP:
            case COMMAND_WINDOW_DOWN:
                return;
            default:
                goalColumn = getAbsoluteCaretCol();
                return;
        }
    }

    public void maybeScrollCaret() {
        if (dot == null)
            return;
        // Don't scroll the caret if a region is selected!
        if (mark != null)
            return;
        if (buffer.getBooleanProperty(Property.SCROLL_CARET)) {
            final int dotLineNumber = dot.lineNumber();
            final Line topLine = display.getTopLine();
            if (dotLineNumber < topLine.lineNumber()) {
                // Caret is above window.
                addUndo(SimpleEdit.SCROLL_CARET);
                dot.moveTo(topLine, 0);
                updateDotLine();
                moveCaretToDotCol();
                goalColumn = 0;
                return;
            }
            final Line bottomLine = display.getBottomLine();
            if (dotLineNumber > bottomLine.lineNumber()) {
                // Caret is below window.
                addUndo(SimpleEdit.SCROLL_CARET);
                updateDotLine();
                dot.moveTo(bottomLine, 0);
                updateDotLine();
                moveCaretToDotCol();
                goalColumn = 0;
            }
        }
    }

    final Position getEob() {
        return buffer.getEnd();
    }

    public void mouseMoveDotToPoint() {
        AWTEvent e = dispatcher.getLastEvent();
        if (e instanceof MouseEvent)
            mouseMoveDotToPoint((MouseEvent) e);
    }

    public void mouseMoveDotToPoint(MouseEvent e) {
        addUndo(SimpleEdit.MOVE);
        if (mark != null)
            unmark();
        display.moveCaretToPoint(e.getPoint());
        if (buffer.getBooleanProperty(Property.RESTRICT_CARET))
            moveCaretToDotCol();

        // Dec 13 2002 6:30 PM
        // Without this, focus ends up in the location bar textfield if you
        // click in the edit window after using the openFile completion list
        // to open a file. Weird.
        Editor.restoreFocus();
    }

    public void mouseSelect() {
        if (dot != null) {
            AWTEvent e = dispatcher.getLastEvent();
            if (e instanceof MouseEvent) {
                MouseEvent mouseEvent = (MouseEvent) e;
                Position pos = display.positionFromPoint(mouseEvent.getPoint());
                addUndo(SimpleEdit.MOVE);
                Position min, max;
                if (mark == null) {
                    // New selection.
                    setMarkAtDot();
                    dot = pos;
                    moveCaretToDotCol();
                    Region r = new Region(this);
                    min = r.getBegin();
                    max = r.getEnd();
                } else {
                    // Adjust existing selection.
                    Region r = new Region(this);
                    min = r.getBegin();
                    max = r.getEnd();
                    if (pos.isBefore(r.getBegin())) {
                        min = pos;
                        mark = r.getEnd();
                    } else if (pos.isAfter(r.getEnd())) {
                        max = pos;
                        mark = r.getBegin();
                    } else {
                        // Click was inside selected region.
                        if (Position.getDistance(r.getBegin(), pos) < Position.getDistance(r.getEnd(), pos)) {
                            // Click was closer to beginning of region.
                            mark = r.getEnd();
                        } else
                            mark = r.getBegin();
                    }
                    dot = pos;
                    moveCaretToDotCol();
                }
                // Minimize repaint.
                if (max.lineNumber() - min.lineNumber() < display.getRows()) {
                    Line line = min.getLine();
                    Line endLine = max.getLine();
                    while (line != null && line != endLine) {
                        update(line);
                        line = line.next();
                    }
                    update(endLine);
                } else
                    setUpdateFlag(REPAINT);
            }
        }
    }

    public void mouseSelectColumn() {
        if (dot != null) {
            AWTEvent e = dispatcher.getLastEvent();
            if (e instanceof MouseEvent) {
                MouseEvent mouseEvent = (MouseEvent) e;
                if (getMark() == null)
                    setMarkAtDot();
                display.moveCaretToPoint(mouseEvent.getPoint());
                setColumnSelection(true);
                display.setUpdateFlag(REPAINT);
            }
        }
    }

    private JPopupMenu popup;

    public void mouseShowContextMenu() {
        AWTEvent e = dispatcher.getLastEvent();
        if (e instanceof MouseEvent) {
            MouseEvent mouseEvent = (MouseEvent) e;
            int x = mouseEvent.getX();
            int y = mouseEvent.getY();
            popup = buffer.getMode().getContextMenu(this);
            if (popup != null) {
                Dimension dimPopup = popup.getPreferredSize();
                Dimension dimDisplay = display.getSize();
                int xMax = dimDisplay.width - dimPopup.width - 5;
                int yMax = dimDisplay.height - dimPopup.height - 5;
                if (x > xMax)
                    x = xMax;
                else
                    ++x;
                if (y > yMax)
                    y = yMax;
                else
                    ++y;
                popup.show(mouseEvent.getComponent(), x, y);
            }
        }
    }

    public final JPopupMenu getPopup() {
        return popup;
    }

    public final void setPopup(JPopupMenu popup) {
        this.popup = popup;
    }

    public void killPopup() {
        if (popup != null) {
            popup.setVisible(false);
            popup = null;
            restoreFocus();
        }
    }

    // Moves dot to the requested absolute column, based on the tab size of
    // the buffer. If the requested column is past the end of the line, dot is
    // moved to the end of the line.
    public void moveDotToCol(int goal) {
        if (dot == null)
            return;

        dot.moveToCol(goal, buffer.getTabWidth());
        updateDotLine();

        // Support tab chars in buffer. If we're not beyond the end of the
        // line, make sure we're on an actual character.
        if (dot.getOffset() < dot.getLineLength())
            moveCaretToDotCol();
    }

    public final void moveDotToCaretCol() {
        moveDotToCol(display.getAbsoluteCaretCol());
    }

    public final void moveCaretToDotCol() {
        display.moveCaretToDotCol();
    }

    public final void repaintDisplay() {
        display.setUpdateFlag(REPAINT);
        display.repaint();
    }

    public final void repaintNow() {
        display.repaintNow();
    }

    // Adds whitespace to fill the area between the end of the actual text on
    // a line and the location of the caret, if it's beyond the end of the
    // text. Dot is moved to the end of the appended whitespace. Does nothing
    // if caret is not past end of text.
    public void fillToCaret() {
        final int where = display.getAbsoluteCaretCol();
        final Line dotLine = getDotLine();
        String s = getFillString(dotLine, where);
        if (s != null) {
            buffer.withWriteLock(() -> {
                addUndo(SimpleEdit.LINE_EDIT);
                dotLine.setText(dotLine.getText().concat(s));
                buffer.modified();
                dot.setOffset(dotLine.length());
            });
        }
    }

    private String getFillString(Line line, int where) {
        int end = buffer.getCol(line, line.length());
        if (where <= end)
            return null;
        final int width = where - end;

        // For sanity, only use actual tab chars at beginning of line!
        if (buffer.getUseTabs() && line.length() == 0) {
            StringBuilder sb = new StringBuilder(width);
            int col = 0;
            final int tabWidth = buffer.getTabWidth();
            while (col + tabWidth <= width) {
                sb.append('\t');
                col += tabWidth;
            }
            while (col < width) {
                sb.append(' ');
                ++col;
            }
            return sb.toString();
        } else
            return Utilities.spaces(width);
    }

    public final int getDotCol() {
        return buffer.getCol(dot);
    }

    // Insert string at dot, put dot at end of inserted string.
    // No undo.
    public void insertStringInternal(String s) {
        updateInAllEditors(getDotLine());
        buffer.insertString(dot, s);
    }

    // Fills the space (if any) between dot and caret and inserts
    // the char in question.
    public void insertChar(char c) {
        final Line dotLine = getDotLine();
        if (getDotOffset() > dotLine.length()) {
            // Shouldn't happen.
            Debug.bug();
            Log.error(
                "insertChar dot offset = " + getDotOffset() +
                    " dotLine length = " + dotLine.length()
            );
            // Enforce sanity and carry on.
            dot.setOffset(dotLine.length());
        }
        if (!buffer.withWriteLock(() -> {
            addUndo(SimpleEdit.LINE_EDIT);
            fillToCaret();
            StringBuilder sb =
                new StringBuilder(dotLine.substring(0, getDotOffset()));
            sb.append(c);
            sb.append(dotLine.substring(getDotOffset()));
            dotLine.setText(sb.toString());
            dot.moveRight();
            moveCaretToDotCol();
            buffer.modified();
        }))
            return;
        updateInAllEditors(dotLine);
    }

    public void insertChar() {
        if (!checkReadOnly())
            return;
        String input = InputDialog.showInputDialog(this, "Character:", "Insert Character");
        if (input == null || input.length() == 0)
            return;
        repaintNow();
        int c = parseNumericInput(input);
        if (c >= 0 && c < 0xfffe)
            insertChar((char) c);
        else
            MessageDialog.showMessageDialog(this, "Invalid character", "Insert Character");
    }

    public void insertByte() {
        if (!checkReadOnly())
            return;
        String input = InputDialog.showInputDialog(this, "Byte:", "Insert Byte");
        if (input == null || input.length() == 0)
            return;
        repaintNow();
        int c = parseNumericInput(input);
        if (c >= 0 && c <= 255) {
            byte[] bytes = new byte[1];
            bytes[0] = (byte) c;
            String encoding = prefs.getStringProperty(Property.DEFAULT_ENCODING);
            try {
                String s = new String(bytes, encoding);
                insertChar(s.charAt(0));
            }
            catch (UnsupportedEncodingException e) {
                Log.error(e);
                MessageDialog.showMessageDialog(
                    this,
                    "Unsupported encoding \"" + encoding + "\"",
                    "Insert Byte"
                );
            }
        } else
            MessageDialog.showMessageDialog(
                this,
                "Invalid byte \"" + input + "\"",
                "Insert Byte"
            );
    }

    // Used only by insertChar and insertByte. Doesn't understand a leading
    // minus sign.
    private static int parseNumericInput(String input) {
        int n = -1;
        input = input.trim();
        try {
            if (input.startsWith("0x") || input.startsWith("0X"))
                n = Integer.parseInt(input.substring(2), 16);
            else if (input.startsWith("0"))
                n = Integer.parseInt(input, 8);
            else
                n = Integer.parseInt(input, 10);
        }
        catch (NumberFormatException e) {
            Log.error(e);
        }
        return n;
    }

    public void gotoline(int lineNumber) {
        Line line = buffer.getLine(lineNumber);
        if (line != null)
            setDot(line, 0);
    }

    public void saveState() {
        if (saveSession) {
            // Make sure information about current buffer is up-to-date.
            saveView();

            Session.saveDefaultSession();
            if (sessionName != null)
                if (prefs.getBooleanProperty(Property.AUTOSAVE_NAMED_SESSIONS))
                    Session.saveCurrentSession();
            sessionProperties.saveWindowPlacement();
            sessionProperties.save();
        }
    }

    // It might make sense to move this code into the Buffer class.
    public void reload(Buffer buf) {
        if (buf.getFile() instanceof SshFile)
            return; // Not supported.
        setWaitCursor();
        Debug.assertTrue(SwingUtilities.isEventDispatchThread());
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() == buf)
                ed.saveView();
        }

        // May be asynchronous.
        buf.reload();
        setDefaultCursor();
    }

    public void revertBuffer() {
        final File file = buffer.getFile();
        if (file instanceof SshFile)
            return; // Not supported.
        if (buffer.isModified()) {
            String prompt = "Discard changes to " + file.canonicalPath() + "?";
            if (!confirm("Revert Buffer", prompt))
                return;
            reload(buffer);
        }
    }

    // Returns true if the buffer is active and there has been some change
    // that requires us to redraw the menus, title bar or display, false
    // otherwise.
    public boolean reactivate(Buffer buf) {
        if (buf instanceof ImageBuffer)
            return ((ImageBuffer) buf).reactivate();

        if (buf.getType() != Buffer.TYPE_NORMAL)
            return false;
        if (buf.isUntitled())
            return false;

        // BUG! Why don't we reload binary mode buffers?
        if (buf.getModeId() == BINARY_MODE)
            return false;

        final File file = buf.getFile();
        if (file == null || file.isRemote() || !file.isFile())
            return false;

        boolean changed = false;

        // Check read-only status even if buffer is not loaded so buffer list
        // will be correct.
        if (buf.readOnly == file.canWrite()) {
            // Read-only status has changed.
            buf.readOnly = !buf.readOnly;
            changed = true;
            // Let the user know if the file associated with a modified buffer
            // is no longer writable.
            if (buf.readOnly && buf.isLoaded() && buf.isModified())
                MessageDialog.showMessageDialog(
                    file.canonicalPath().concat(" is no longer writable"),
                    "Warning"
                );
        }

        if (buf.isLoaded()) {
            if (file.lastModified() != buf.getLastModified()) {
                if (buf.isModified()) {
                    String prompt = file.canonicalPath() +
                        " has changed on disk. Reload and lose current changes?";
                    if (confirm("Reload File From Disk", prompt)) {
                        reload(buf);
                        changed = true;
                    } else
                        buf.setLastModified(file.lastModified());
                } else {
                    // No need for confirmation.
                    reload(buf);
                    changed = true;
                }
            }
        }

        return changed;
    }

    public void setFocus(JComponent c) {
        frame.setFocus(c);
    }

    public JComponent getFocusedComponent() {
        return frame.getFocusedComponent();
    }

    public void setFocusToDisplay() {
        if (frame != null)
            frame.setFocus(display);
    }

    public static final void restoreFocus() {
        Runnable r = () -> {
            if (currentEditor != null)
                currentEditor.setFocusToDisplay();
        };
        SwingUtilities.invokeLater(r);
    }

    public void componentHidden(ComponentEvent e) {}

    public void componentMoved(ComponentEvent e) {}

    public void componentResized(ComponentEvent e) {
        updateScrollBars();
    }

    public void componentShown(ComponentEvent e) {}

    public void mouseWheelMoved(MouseWheelEvent e) {
        // Without this, focus ends up in the location bar textfield if you use
        // the mouse wheel in the edit window after using the openFile
        // completion list to open a file.
        // See also mouseMoveDotToPoint(MouseEvent).
        setFocusToDisplay();

        int rotation = e.getWheelRotation();
        int absRotation = Math.abs(rotation);
        if (e.isShiftDown()) {
            if (rotation < 0)
                display.windowLeft(absRotation);
            else if (rotation > 0)
                display.windowRight(absRotation);
        } else {
            if (rotation < 0)
                display.windowUp(absRotation);
            else if (rotation > 0)
                display.windowDown(absRotation);
        }
    }

    public void ensureActive() {
        if (frame == null)
            return;
        if (!frame.isActive()) {
            for (int i = 0; i < getFrameCount(); i++) {
                Frame f = getFrame(i);
                if (f.isActive()) {
                    f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_DEACTIVATED));
                    break;
                }
            }
            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_ACTIVATED));
        }
    }

    public void quit() {
        maybeExit();
    }

    public void saveAllExit() {
        tagFileManager.setEnabled(false);
        saveAll();
        maybeExit(); // May never return.
        tagFileManager.setEnabled(true);
    }

    void maybeExit() {
        int numModifiedBuffers = 0;

        for (Buffer buf : Editor.getBufferList()) {
            if (buf.isModified())
                ++numModifiedBuffers;
        }

        if (numModifiedBuffers > 0) {
            StringBuilder sb = new StringBuilder("Really exit with ");
            sb.append(numModifiedBuffers);
            sb.append(" modifed buffer");
            if (numModifiedBuffers > 1)
                sb.append('s');
            sb.append('?');
            if (!confirm("Really exit?", sb.toString()))
                return;
        }

        setWaitCursor();

        saveState();
        RecentFiles.getInstance().save();

        // Delete all autosave files.
        for (Buffer buf : Editor.getBufferList())
            buf.deleteAutosaveFile();

        Autosave.deleteCatalogFile();

        // Call dispose on all buffers.
        for (Buffer buf : Editor.getBufferList())
            buf.dispose();

        // Clean up temporary directory.
        Directories.cleanTempDirectory();

        // Clean up cache  directory.
        Cache.cleanup();

        Server.stopServer();
        pendingOperations.run();
        setDefaultCursor();
        System.exit(0);
    }

    // See if we have the requested file in a buffer. If not, and if the file
    // actually exists, make a new buffer for it.
    public static Buffer getBuffer(File file) {
        if (file == null)
            return null;
        Buffer buf = bufferList.findBuffer(file);
        if (buf != null)
            return buf;
        if (file.isRemote())
            return Buffer.createBuffer(file);
        if (file.isDirectory())
            return new DirectoryBuffer(file);
        if (file.isFile()) {
            if (!file.canRead()) {
                MessageDialog.showMessageDialog(
                    "File is not readable",
                    "Error"
                );
                return null;
            }
            return Buffer.createBuffer(file);
        }
        if (file.exists()) {
            // The file exists, but it's neither a directory nor a "normal"
            // file. This can occur on Linux if an SMB mount goes south.

            // Not a very informative error message, but this is what bash says
            // in the SMB mount case.
            currentEditor.status("I/O error");
        }
        return null;
    }

    public void nextBuffer() {
        Buffer buf = bufferList.getNextPrimaryBuffer(buffer);
        if (buf == null)
            return;
        if (buf.isPaired()) {
            Buffer secondary = buf.getSecondary();
            if (secondary != null) {
                if (secondary.getLastActivated() > buf.getLastActivated())
                    buf = secondary;
            }
        }
        if (buf != buffer)
            switchToBuffer(buf);
    }

    /**
     * {@code prevBuffer alternate} goes to the buffer used most recently
     * before this one -- vim's alternate file, CTRL-^ -- rather than to the
     * one before this in the buffer list.
     */
    public void prevBuffer(String parameters) {
        if (
            parameters == null
                || !parameters.trim().equalsIgnoreCase("alternate")
        ) {
            prevBuffer();
            return;
        }
        final Buffer alternate = alternateBuffer();
        if (alternate == null) {
            status("E23: No alternate file");
            return;
        }
        switchToBuffer(alternate);
    }

    /** The buffer activated most recently, other than this one. */
    Buffer alternateBuffer() {
        Buffer best = null;
        for (Buffer b : Editor.getBufferList()) {
            if (b == buffer || !b.isPrimary())
                continue;
            if (best == null || b.getLastActivated() > best.getLastActivated())
                best = b;
        }
        return best;
    }

    public void prevBuffer() {
        Buffer buf = bufferList.getPreviousPrimaryBuffer(buffer);
        if (buf == null)
            return;
        if (buf.isPaired()) {
            Buffer secondary = buf.getSecondary();
            if (secondary != null) {
                if (secondary.getLastActivated() > buf.getLastActivated())
                    buf = secondary;
            }
        }
        if (buf != buffer)
            switchToBuffer(buf);
    }

    public void switchToBuffer(Buffer buf) {
        if (buf != null) {
            if (!buf.isPaired() && (buffer == null || !buffer.isPaired())) {
                // This is the easy case. Both the buffer we're switching in
                // and the buffer we're switching out are unpaired.
                activate(buf);
            } else {
                // We're either switching in a paired buffer or switching out
                // a paired buffer (or both). Delegate to our frame, since we
                // may end up closing this editor.
                frame.switchToBuffer(this, buf);
            }
            Sidebar sidebar = getSidebar();
            if (sidebar != null)
                sidebar.setBuffer();
        } else
            Debug.bug();
    }

    public void makeNext(final Buffer buf) {
        bufferList.makeNext(buf, buffer);
    }

    public void newBuffer() {
        Buffer buf = new Buffer(0);
        makeNext(buf);
        switchToBuffer(buf);
    }

    public final void openFile() {
        AWTEvent e = dispatcher.getLastEvent();
        if (e != null && e.getSource() instanceof MenuItem) {
            Runnable r = () -> {
                setFocusToTextField();
            };
            SwingUtilities.invokeLater(r);
        } else
            setFocusToTextField();
    }

    /**
     * {@code openFileInSplit FILE} -- splits the window and opens FILE in
     * the top one, with the caret, as vim's {@code :split FILE}.
     */
    public void openFileInSplit(String file) {
        splitAndOpen(file, false);
    }

    /** {@code openFileInVsplit FILE} -- the same, side by side, on the left. */
    public void openFileInVsplit(String file) {
        splitAndOpen(file, true);
    }

    private void splitAndOpen(String file, boolean vertical) {
        if (frame == null)
            return;
        if (vertical)
            WindowCommands.vsplitWindow(this, "vim");
        else
            WindowCommands.splitWindow(this, "vim");
        if (file == null || file.trim().isEmpty())
            return;
        // The caret is in the top or left window now.
        final Editor top = currentEditor();
        final Buffer opened = top.openFile(top.fileNamed(file.trim()));
        if (opened != null) {
            top.makeNext(opened);
            top.switchToBuffer(opened);
        }
    }

    public void openFileInOtherWindow() {
        saveView();
        boolean alreadySplit = frame.hasSplit();
        if (!alreadySplit)
            WindowCommands.splitWindow(this);
        final Editor ed = getOtherEditor();
        if (ed.getLocationBar() != null) {
            Runnable r = () -> {
                frame.setFocus(ed.getLocationBar().getTextField());
            };
            SwingUtilities.invokeLater(r);
            setCurrentEditor(ed);
            if (alreadySplit) {
                // Current editor has changed.
                repaint();
                ed.repaint();
            }
        }
    }

    public Buffer openFile(File file) {
        Buffer buf = getBuffer(file);
        if (buf != null) {
            Debug.assertTrue(bufferList.contains(buf));
            return buf;
        }
        if (file.isRemote())
            return null;
        // File is local.
        if (!file.exists()) {
            if (
                confirm(
                    "Create file?",
                    file.canonicalPath() + " does not exist. Create?"
                )
            )
                return Buffer.createBuffer(file);
        }
        return null;
    }

    public Buffer openFiles(List<String> list) {
        if (list == null)
            return null;
        final int listSize = list.size();
        if (listSize < 2)
            return null;
        Buffer toBeActivated = null;
        // First string is directory.
        String dirname = list.get(0);
        File directory = File.getInstance(dirname);
        History openFileHistory = new History("openFile.file");
        int lineNumber = -1;
        for (int i = 1; i < listSize; i++) {
            String s = list.get(i);
            if (s == null || s.length() == 0)
                continue;
            if (s.charAt(0) == '+') {
                try {
                    lineNumber = Integer.parseInt(s.substring(1)) - 1;
                }
                catch (NumberFormatException e) {}
                continue;
            }
            // Aliases.
            String value = getAlias(s);
            if (value != null)
                s = value;
            Opener opener = Extensions.opener(s);
            if (opener != null) {
                Buffer buf = opener.getBuffer(this, s);
                if (buf != null) {
                    makeNext(buf);
                    toBeActivated = buf;
                }
                continue;
            }
            File file = File.getInstance(directory, s);
            if (file == null) {
                MessageDialog.showMessageDialog(
                    this,
                    "Invalid path ".concat(s),
                    "Invalid Path"
                );
                continue;
            }
            if (Utilities.isFilenameAbsolute(s) || s.startsWith("./") || s.startsWith(".\\"))
                ; // No tricks.
            else if (!file.exists()) {
                // Look in source and include paths as appropriate.
                File f = Utilities.findFile(this, s);
                if (f != null)
                    file = f;
                else {
                    // Not found in source or include path.
                    if (s.startsWith("www."))
                        file = File.getInstance("http://".concat(s));
                    else if (s.startsWith("ftp."))
                        file = File.getInstance("ftp://".concat(s));
                }
            }
            if (file.isLocal() && !file.exists()) {
                if (!Utilities.checkParentDirectory(file, "Open File"))
                    continue;
            }
            Buffer buf = openFile(file);
            if (buf != null) {
                Debug.assertTrue(bufferList.contains(buf));
                openFileHistory.append(file.netPath());
                if (lineNumber >= 0) {
                    // Line number was specified on command line.
                    if (buf.isLoaded()) {
                        if (buf == buffer) {
                            // Current buffer.
                            Line line = buffer.getLine(lineNumber);
                            if (line == null) {
                                if (mark != null)
                                    unmark();
                                updateDotLine();
                                dot.moveTo(getEob());
                                updateDotLine();
                                moveCaretToDotCol();
                            } else {
                                addUndo(SimpleEdit.MOVE);
                                if (mark != null)
                                    unmark();
                                updateDotLine();
                                dot.moveTo(line, 0);
                                updateDotLine();
                                moveCaretToDotCol();
                            }
                        } else {
                            // Not current buffer.
                            Buffer oldBuffer = buffer;
                            if (buffer != null)
                                saveView();
                            buffer = buf;
                            findOrCreateView(buffer);
                            restoreView();
                            addUndo(SimpleEdit.MOVE);
                            if (mark != null)
                                unmark();
                            Line line = buffer.getLine(lineNumber);
                            if (line == null) {
                                line = buffer.getFirstLine();
                                while (line.next() != null)
                                    line = line.next();
                                dot.moveTo(line, line.length());
                            } else
                                dot.moveTo(line, 0);
                            saveView();
                            View view = views.get(buffer);
                            if (view != null) {
                                view.shift = 0;
                                view.caretCol = getDotCol();
                            }
                            buffer = oldBuffer;
                            if (buffer != null)
                                restoreView();
                        }
                    } else {
                        // Not yet loaded.
                        View view = findOrCreateView(buf);
                        view.lineNumber = lineNumber;
                        view.offs = 0;
                    }
                }
                Debug.assertTrue(bufferList.contains(buf));
                makeNext(buf);
                Debug.assertTrue(bufferList.contains(buf));
                toBeActivated = buf;
            }
        }
        openFileHistory.save();
        return toBeActivated;
    }

    public void unmark() {
        if (mark != null) {
            setMark(null);
            display.setUpdateFlag(REPAINT); // BUG! Not always necessary!
        }
    }

    /** Starts a caret motion: records the caret for undo and drops the selection. */
    public void beginMotion() {
        addUndo(SimpleEdit.MOVE);
        unmark();
    }

    /** Starts a selecting motion: records the caret for undo and anchors a selection there if there is none. */
    public void beginSelectMotion() {
        addUndo(SimpleEdit.MOVE);
        if (mark == null)
            setMarkAtDot();
    }

    public void cancelBackgroundProcess() {
        BackgroundProcess backgroundProcess = buffer.getBackgroundProcess();
        if (backgroundProcess != null)
            backgroundProcess.cancel();
    }

    // Calls buffer.setMark(null), then returns after doing exactly one thing.
    public void escape() {
        buffer.setMark(null); // keyboard-quit

        // Cancel background process (if any).
        BackgroundProcess backgroundProcess = buffer.getBackgroundProcess();
        if (backgroundProcess != null) {
            backgroundProcess.cancel();
            return;
        }

        if (popup != null) {
            if (popup.isVisible()) {
                killPopup();
                return;
            }
            popup = null;
            // We haven't really done anything yet. Fall through...
        }

        if (lastCommand == COMMAND_EXPAND) {
            Expansion expansion = Expansion.getLastExpansion();
            if (expansion != null) {
                expansion.undo(this);
                return;
            }
        }

        if (buffer instanceof RemoteBuffer && buffer.isEmpty()) {
            killBuffer();
            return;
        }

        if (escapeInternal())
            return;

        if (selection != null && selection.getSavedDot() != null)
            moveDotTo(selection.getSavedDot());
        else if (mark != null)
            moveDotTo(mark);
    }

    public boolean escapeInternal() {
        if (buffer instanceof CompilationBuffer || buffer.isTransient()) {
            if (buffer.unsplitOnClose()) {
                buffer.windowClosing();
                WindowCommands.otherWindow(this);
                WindowCommands.unsplitWindow(this);
            }
            maybeKillBuffer(buffer);
            restoreFocus();
            Sidebar.refreshSidebarInAllFrames();
            return true;
        }
        if (buffer.getModeId() == CHECKIN_MODE) {
            WindowCommands.otherWindow(this);
            WindowCommands.unsplitWindow(this);
            if (!buffer.isModified())
                maybeKillBuffer(buffer);
            restoreFocus();
            return true;
        }
        // Check for transient buffer in other editor in current frame.
        Editor ed = getOtherEditor();
        if (ed != null) {
            Buffer buf = ed.getBuffer();
            if (buf instanceof CompilationBuffer || buf.isTransient()) {
                if (buf.unsplitOnClose())
                    WindowCommands.unsplitWindow(this);
                maybeKillBuffer(buf);
                if (!buf.unsplitOnClose())
                    ed.updateDisplay();
                Sidebar.refreshSidebarInAllFrames();
                return true;
            }
            if (buf.getModeId() == CHECKIN_MODE) {
                WindowCommands.unsplitWindow(this);
                if (!buf.isModified())
                    maybeKillBuffer(buf);
                return true;
            }
        }
        return false;
    }

    public void tempBufferQuit() {
        if (buffer instanceof CompilationBuffer || buffer.isTransient()) {
            if (buffer.unsplitOnClose()) {
                buffer.windowClosing();
                WindowCommands.otherWindow(this);
                WindowCommands.unsplitWindow(this);
            }
            maybeKillBuffer(buffer);
            restoreFocus();
            Sidebar.refreshSidebarInAllFrames();
            return;
        }
    }

    public void stamp() {
        if (!checkReadOnly())
            return;
        Date now = new Date(System.currentTimeMillis());
        String dateString = null;
        String stampFormat = buffer.getStringProperty(Property.STAMP_FORMAT);
        if (stampFormat != null) {
            try {
                SimpleDateFormat df = new SimpleDateFormat(stampFormat);
                dateString = df.format(now);
            }
            catch (Throwable t) {
                // Fall through...
            }
        }
        if (dateString == null) {
            SimpleDateFormat df = new SimpleDateFormat("MMM d yyyy h:mm a");
            dateString = df.format(now);
        }
        try {
            buffer.lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            CompoundEdit compoundEdit = beginCompoundEdit();
            if (mark != null)
                delete();
            fillToCaret();
            addUndo(SimpleEdit.INSERT_STRING);
            insertStringInternal(dateString);
            buffer.modified();
            endCompoundEdit(compoundEdit);
            moveCaretToDotCol();
            updateInAllEditors(getDotLine());
        }
        finally {
            buffer.unlockWrite();
        }
    }

    public String getCurrentText() {
        String s = getSelectionOnCurrentLine();
        if (s == null)
            s = getTokenAtDot();
        return (s != null && s.length() > 0) ? s : null;
    }

    public String getSelectionOnCurrentLine() {
        if (dot != null && mark != null && getMarkLine() == getDotLine())
            return new Region(this).toString();
        else
            return null;
    }

    public String getFilenameAtDot() {
        if (dot == null)
            return null;
        Position pos;
        if (mark != null) {
            Region r = new Region(this);

            // Trust the user if there's a highlighted selection on a single
            // line.
            if (r.getBeginLine() == r.getEndLine())
                return r.toString();

            // Otherwise, we want the beginning of the marked region.
            pos = r.getBegin();
        } else
            pos = new Position(dot);
        final Line line = pos.getLine();
        final int limit = line.length();
        int offset = pos.getOffset();
        if (offset == limit)
            --offset;
        StringBuilder sb = new StringBuilder();
        if (offset >= 0 && offset < limit) {
            char c = line.charAt(offset);
            if (Utilities.isFilenameChar(c)) {
                while (offset > 0) {
                    c = line.charAt(--offset);
                    if (!Utilities.isFilenameChar(c)) {
                        ++offset;
                        break;
                    }
                }

                // Now we're looking at the first char of the filename.
                sb.append(line.charAt(offset));
                while (++offset < limit) {
                    c = line.charAt(offset);
                    if (Utilities.isFilenameChar(c)) {
                        sb.append(c);
                    } else if (sb.toString().startsWith("http://")) {
                        // Be more permissive since there may be an appended
                        // query.
                        if (!Character.isWhitespace(c))
                            sb.append(c);
                        else
                            break;
                    } else
                        break;
                }

                // Now we're looking at the first char past the end of the
                // filename. If the filename starts with "http://", make sure
                // it doesn't end with normal punctuation (as is often the
                // case with links embedded in text).
                int length = sb.length();
                while (length > 0) {
                    c = sb.charAt(length - 1);
                    if (".,:;)]>".indexOf(c) >= 0)
                        --length;
                    else
                        break;
                }
                sb.setLength(length);

                Pattern re = Pattern.compile(" line [0-9]+");
                Matcher matcher = re.matcher(line.getText().substring(offset));
                if (matcher.find() && matcher.start() == 0)
                    sb.append(matcher.group());
            }
        }
        return sb.toString();
    }

    String getTokenAtDot() {
        // If a selection is marked, return the token at the beginning of the
        // marked region.
        if (mark != null) {
            Region r = new Region(this);
            return tokenAt(r.getBegin());
        }
        return tokenAt(dot);
    }

    String tokenAt(Position pos) {
        return getMode().getIdentifier(pos);
    }

    /**
     * Deletes the text from start to end, leaving the caret at start --
     * which is also where undo puts it back. The display's caret moves too,
     * even when there is nothing to delete: an insert there is padded out
     * to the display's column.
     */
    public void deleteRegion(Position start, Position end) {
        setMark(end);
        setDot(start);
        moveCaretToDotCol();
        deleteRegion();
    }

    // Handles undo, updates display and marks buffer modified.
    public void deleteRegion() {
        if (mark == null)
            return;
        if (getMarkLine() != getDotLine() || getMarkOffset() != getDotOffset()) {
            if (!buffer.withWriteLock(() -> {
                Region r = new Region(this);
                if (isColumnSelection()) {
                    deleteColumn(r);
                } else {
                    // A hard update is only necessary if the region spans a
                    // line boundary.
                    boolean hard = getDotLine() != getMarkLine();

                    // Save undo information before calling r.delete() so
                    // the modified flag will be correct if we revert.
                    CompoundEdit compoundEdit = beginCompoundEdit();
                    addUndo(SimpleEdit.MOVE);
                    dot.moveTo(r.getBegin());
                    addUndoDeleteRegion(r);

                    // Sets buffer modified flag.
                    r.delete();

                    endCompoundEdit(compoundEdit);

                    if (hard)
                        buffer.repaint();
                    else
                        updateInAllEditors(getDotLine());
                }
            }))
                return;
            moveCaretToDotCol();
        }
        setMark(null);
    }

    // Leaves dot at beginning of deleted region.
    // Block splits a tab the region's edge cuts through, and leaves the
    // caret at its top left.
    void deleteColumn(Region r) {
        Debug.assertTrue(r.isColumnRegion());
        r.toBlock().delete(this);
    }

    public void insertString(String toBeInserted) {
        if (toBeInserted == null || toBeInserted.length() == 0)
            return;
        CompoundEdit compoundEdit = beginCompoundEdit();
        if (mark != null)
            delete();
        fillToCaret();
        addUndo(SimpleEdit.INSERT_STRING);
        insertStringInternal(toBeInserted);
        updateInAllEditors(dot.getLine());
        moveCaretToDotCol();
        endCompoundEdit(compoundEdit);
        if (getFormatter().parseBuffer())
            buffer.repaint();
    }

    public void centerDialog(JDialog d) {
        d.setLocationRelativeTo(frame);
    }

    public boolean confirm(String title, String text) {
        int response = ConfirmDialog.showConfirmDialog(this, text, title);
        repaintNow();
        return response == RESPONSE_YES;
    }

    public int confirmAll(String title, String text) {
        int response = ConfirmDialog.showConfirmAllDialog(this, text, title);
        repaintNow();
        return response;
    }

    public void killBuffer() {
        try {
            if (buffer.isSecondary()) {
                buffer.windowClosing();
                WindowCommands.otherWindow(this);
                WindowCommands.unsplitWindow(this);
                currentEditor.maybeKillBuffer(buffer);
                restoreFocus();
                return;
            }
            Buffer buf = buffer.getSecondary();
            if (buf != null) {
                WindowCommands.unsplitWindow(this);
                maybeKillBuffer(buf);
                return;
            }
            // Normal buffer.
            maybeKillBuffer(buffer);
            // If we're left with two editors next to each other showing exactly the same thing,
            // unsplit the window.
            Frame frame = currentEditor.getFrame();
            frame.coalesceEditors(frame.getCurrentEditor());
        }
        finally {
            Sidebar.refreshSidebarInAllFrames();
        }
    }

    public void maybeKillBuffer(Buffer toBeKilled) {
        if (!bufferList.contains(toBeKilled)) {
            Debug.bug("maybeKillBuffer buffer not in list " + toBeKilled);
            return;
        }

        // Don't kill the last buffer if it's a directory.
        if (bufferList.size() == 1 && toBeKilled instanceof DirectoryBuffer)
            return;

        // Cancel background process if any.
        BackgroundProcess backgroundProcess = toBeKilled.getBackgroundProcess();
        if (backgroundProcess != null) {
            Log.debug("maybeKillBuffer calling backgroundProcess.cancel...");
            backgroundProcess.cancel();
            // backgroundProcess.cancel() may have killed the buffer, so
            // verify that it's still in the list.
            if (!bufferList.contains(toBeKilled)) {
                Log.debug("maybeKillBuffer buffer is no longer in list");
                return;
            }
        }

        Mode mode = toBeKilled.getMode();
        if (mode == null || mode.confirmClose(this, toBeKilled))
            toBeKilled.kill();
    }

    public void clearStatusText() {
        StatusBar statusBar = getStatusBar();
        if (statusBar != null) {
            statusBar.setText(null);
            statusBar.repaint();
        }
    }

    // Save information about buffer being deactivated.
    public void deactivate() {
        Debug.bugIfNot(buffer != null && bufferList.contains(buffer));
        // Through getInputHandler(), not the raw field: the field is a cache
        // that getInputHandler() only refreshes for a TYPE_NORMAL buffer, so
        // reading it directly here would clean up a vim session against a
        // directory or image buffer that was never in vim mode.
        final InputHandler handler = getInputHandler();
        if (handler != null)
            handler.editorDeactivated(this);
        buffer.autosave();
        saveView();
        RecentFiles.getInstance().bufferDeactivated(buffer, dot);
        buffer.windowClosing();
    }

    public void activate(Buffer buf) {
        if (buf == null)
            return;
        Debug.assertTrue(bufferList.contains(buf));
        if (buf == buffer)
            return;
        if (!buf.initialized())
            buf.initialize();
        clearStatusText();
        if (buffer != null && bufferList.contains(buffer)) {
            deactivate();
        }

        // Read-only status may have changed. (We could be switching back from
        // a shell buffer.)
        reactivate(buf);

        buf.setLastActivated(System.currentTimeMillis());
        if (buf.isLoaded()) {
            buffer = buf;
            bufferActivated(false);
        } else {
            setWaitCursor();
            int result = LOAD_FAILED;
            try {
                result = buf.load();
            }
            catch (OutOfMemoryError e) {
                buf.kill();
                Sidebar.setUpdateFlagInAllFrames(SIDEBAR_ALL);
                MessageDialog.showMessageDialog(
                    this,
                    "Insufficient memory to load buffer",
                    "Error"
                );
                return;
            }
            switch (result) {
                case LOAD_COMPLETED:
                    buffer = buf;
                    bufferActivated(true);
                    break;
                case LOAD_PENDING:
                    buffer = buf;
                    buffer.setBusy(true);
                    bufferPending();
                    break;
                case LOAD_FAILED:
                    setDefaultCursor();
                    buffer = buf;
                    bufferActivated(true);
                    MessageDialog.showMessageDialog(
                        this,
                        "Unable to load buffer",
                        "Error"
                    );
                    break;
                default:
                    Debug.assertTrue(false);
            }
        }
    }

    public Editor activateInOtherWindow(Buffer buf) {
        return frame.activateInOtherWindow(this, buf);
    }

    public Editor activateInOtherWindow(Buffer buf, float split) {
        return frame.activateInOtherWindow(this, buf, split);
    }

    public Editor displayInOtherWindow(Buffer buf) {
        return frame.displayInOtherWindow(this, buf);
    }

    public void bufferActivated(boolean firstTime) {
        if (buffer.getModeId() == IMAGE_MODE) {
            setDot(null);
            setMark(null);
            display.setTopLine(null);
            display.setShift(0);
            display.setCaretCol(0);
        } else {
            findOrCreateView(buffer);
            restoreView();

            // If the buffer has already been loaded, the caret position will
            // be restored correctly.
            if (firstTime)
                moveCaretToDotCol();
        }

        if (dot != null && dot.getOffset() > dot.getLineLength()) {
            dot.setOffset(dot.getLineLength());
            moveCaretToDotCol();
        }

        frame.updateTitle();
        frame.setMenu();
        frame.setToolbar();

        if (buffer.isBusy())
            setWaitCursor();
        else
            setDefaultCursor();

        setUpdateFlag(REFRAME);
        reframe();
        setUpdateFlag(REPAINT);

        RecentFiles.getInstance().bufferActivated(buffer);

        if (buffer.isTaggable()) {
            tagFileManager.addToQueue(
                buffer.getCurrentDirectory(),
                buffer.getMode()
            );
        }

        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_ALL);

        if (firstTime)
            Extensions.hooks().openFile(buffer);
        Extensions.hooks().bufferActivated(buffer);
    }

    private void bufferPending() {
        // Find or create a view of this buffer.
        findOrCreateView(buffer);
        restoreView();

        frame.updateTitle();
        frame.setMenu();
        frame.setToolbar();

        display.repaint();

        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_ALL);
        Sidebar sidebar = getSidebar();
        if (sidebar != null)
            sidebar.setBuffer();
    }

    // Find or create another frame in which to activate the specified buffer.
    public Editor activateInOtherFrame(Buffer buf) {
        Editor ed = null;
        if (getEditorCount() == 1) {
            ed = createNewFrame();
            ed.activate(buf);
            ed.getFrame().setVisible(true);
            ed.updateDisplay();
        } else {
            for (int i = 0; i < getEditorCount(); i++) {
                ed = getEditor(i);
                if (ed != this) {
                    ed.activate(buf);
                    ed.getFrame().toFront();
                    break;
                }
            }
        }
        return ed;
    }

    void requestFocusLater() {
        Runnable r = () -> {
            Editor.this.requestFocus();
        };
        SwingUtilities.invokeLater(r);
    }

    public final boolean addUndo(int type) {
        return SimpleEdit.addUndo(this, type);
    }

    public final boolean addUndoDeleteRegion(Region r) {
        buffer.addEdit(new UndoDeleteRegion(this, r));
        return true;
    }

    public final CompoundEdit beginCompoundEdit() {
        return buffer.beginCompoundEdit();
    }

    public final void endCompoundEdit(CompoundEdit compoundEdit) {
        buffer.endCompoundEdit(compoundEdit);
    }

    public void undo() {
        try {
            buffer.lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        setWaitCursor();
        try {
            buffer.undo();
            checkDotInOtherFrames();
            setCurrentCommand(COMMAND_UNDO);
        }
        catch (Throwable t) {
            Log.error(t);
        }
        finally {
            buffer.unlockWrite();
            setDefaultCursor();
        }
    }

    public void redo() {
        try {
            buffer.lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        setWaitCursor();
        try {
            buffer.redo();
            checkDotInOtherFrames();
        }
        catch (Throwable t) {
            Log.error(t);
        }
        finally {
            buffer.unlockWrite();
            setDefaultCursor();
        }
    }

    private void checkDotInOtherFrames() {
        if (getEditorCount() > 1) {
            for (int i = 0; i < getEditorCount(); i++) {
                Editor ed = getEditor(i);
                if (ed != this && ed.getBuffer() == buffer) {
                    if (ed.getDotOffset() > ed.getDotLine().length()) {
                        ed.getDot().setOffset(ed.getDotLine().length());
                        ed.moveCaretToDotCol();
                        ed.updateDotLine();
                    }
                }
            }
        }
    }

    public final void jumpToLine(int lineNumber) {
        jumpToLine(lineNumber, 0);
    }

    public void jumpToLine(int lineNumber, int offset) {
        Line line = buffer.getLine(lineNumber);
        if (line != null) {
            recordJump();
            if (offset < 0)
                offset = 0;
            else if (offset > line.length())
                offset = line.length();
            moveDotTo(line, offset);
            setUpdateFlag(REFRAME);
        } else
            MotionCommands.eob(this);
    }

    public void offset() {
        status(String.valueOf(buffer.getAbsoluteOffset(dot)));
    }

    public void executeCommand() {
        // Use location bar.
        if (locationBar != null) {
            locationBar.setLabelText(LocationBar.PROMPT_COMMAND);
            HistoryTextField textField = locationBar.getTextField();
            textField.setHandler(new ExecuteCommandTextFieldHandler(this, textField));
            textField.setHistory(new History("executeCommand.input", 30));
            textField.recallLast();
            textField.selectAll();
            AWTEvent e = dispatcher.getLastEvent();
            if (e != null && e.getSource() instanceof MenuItem) {
                Runnable r = () -> {
                    setFocusToTextField();
                };
                SwingUtilities.invokeLater(r);
            } else
                setFocusToTextField();
        }
    }

    public void executeCommand(String input) {
        executeCommand(input, false);
    }

    private void showEvalError(String message) {
        if (message == null || message.length() == 0)
            message = "Error";
        else {
            StringBuilder sb = new StringBuilder(message);
            sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
            message = sb.toString();
        }
        MessageDialog.showMessageDialog(this, message, "Error");
    }

    public void executeCommand(String input, final boolean interactive) {
        input = Utilities.trimLeading(input);
        if (input.length() == 0)
            return;
        if (input.charAt(0) == '(') {
            // A form, for whatever language client is installed. Unwrapping the
            // runtime's own condition types is the client's job; what arrives
            // here is already a message fit to show.
            try {
                EvalResult result = Extensions.session()
                    .evalSync(
                        EvalRequest.of(input).origin("command-line")
                    );
                if (result.isError())
                    showEvalError(result.getError());
                else if (interactive)
                    status(result.display());
            }
            catch (EvalException e) {
                showEvalError(e.getMessage());
            }
            return;
        }
        int index = input.indexOf('=');
        if (index >= 0) {
            String key = input.substring(0, index).trim();
            if (key.indexOf(' ') < 0 && key.indexOf('\t') < 0) {
                String value = input.substring(index + 1).trim();
                setProperty(key, value);
                return;
            }
        }
        String[] array = parseCommand(input);
        if (array != null) {
            final String command = array[0];
            final String parameters = array[1];
            Runnable r = () -> {
                try {
                    StatusBar statusBar = getStatusBar();
                    if (statusBar != null)
                        statusBar.setText("");
                    execute(command, parameters);
                    if (interactive && parameters == null) {
                        // Suggest key binding if one is available.
                        Object[] values = getKeyMapping(command);
                        Debug.assertTrue(values != null);
                        Debug.assertTrue(values.length == 2);
                        KeyMapping mapping = (KeyMapping) values[0];
                        Mode mode = (Mode) values[1];
                        if (mapping != null && statusBar != null) {
                            String statusText = statusBar.getText();
                            boolean append =
                                statusText != null && statusText.length() > 0;
                            StringBuilder sb = new StringBuilder();
                            if (append) {
                                sb.append(statusText);
                                sb.append("          ");
                            }
                            sb.append(command);
                            sb.append(" is mapped to ");
                            sb.append(mapping.getKeyText());
                            if (mode != null) {
                                sb.append(" (");
                                sb.append(mode);
                                sb.append(" mode)");
                            } else
                                sb.append(" (global mapping)");
                            status(sb.toString());
                        }
                    }
                }
                catch (NoSuchMethodException e) {
                    MessageDialog.showMessageDialog(
                        Editor.this,
                        unknownCommandMessage(command),
                        "Error"
                    );
                }
            };
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                try {
                    SwingUtilities.invokeAndWait(r);
                }
                catch (Throwable t) {
                    Log.debug(t);
                }
            }
        }
    }

    /**
     * Parses input line into command and arguments:
     *   command
     *   command()
     *   command args
     *   command("args")
     */
    static String[] parseCommand(String command) {
        command = Utilities.trimLeading(command);
        // Command name is terminated by whitespace or '('.
        char delimiter = '\0';
        int index = -1;
        int commandLength = command.length();
        for (int i = 0; i < commandLength; i++) {
            char c = command.charAt(i);
            if (c == '(' || Character.isWhitespace(c)) {
                delimiter = c;
                index = i;
                break;
            }
        }
        String methodName, parameters;
        if (index < 0) {
            methodName = command;
            parameters = null;
        } else {
            methodName = command.substring(0, index);
            parameters = Utilities.trimLeading(command.substring(index));
            if (delimiter != '(') {
                if (parameters.startsWith("("))
                    delimiter = '(';
            }
            if (delimiter == '(') {
                // Strip parens.
                int length = parameters.length();
                if (length < 2)
                    return null; // Error.
                if (parameters.charAt(length - 1) != ')')
                    return null; // Error.
                parameters = parameters.substring(1, length - 1).trim();
                length = parameters.length();
                if (length == 0)
                    parameters = null;
                else {
                    // Strip required quotes.
                    if (length < 2)
                        return null; // Error.
                    if (parameters.charAt(0) != '"' || parameters.charAt(length - 1) != '"')
                        return null; // Error.
                    parameters = parameters.substring(1, length - 1); // Done.
                }
            }
        }
        String[] array = new String[2];
        array[0] = methodName;
        array[1] = parameters;
        return array;
    }

    // Set a buffer-specific property.
    private void setProperty(String key, String value) {
        Property property = Property.findProperty(key);
        if (property == null) {
            MessageDialog.showMessageDialog(
                "Property \"" + key + "\" not found",
                "Error"
            );
            return;
        }
        final boolean succeeded;
        if (value.length() == 0) {
            succeeded = buffer.removeProperty(property);
        } else {
            succeeded = buffer.setPropertyFromString(property, value);
            if (!succeeded)
                invalidPropertyValue(property, value);
        }
        if (succeeded)
            buffer.saveProperties();
    }

    // No error checking.
    public static void setGlobalProperty(String key, String value) {
        if (value == null || value.length() == 0)
            prefs.removeProperty(key);
        else
            prefs.setProperty(key, value);
    }

    private void invalidPropertyValue(Property property, String value) {
        if (property.isIntegerProperty())
            status("Invalid integer value \"" + value + "\"");
        else if (property.isBooleanProperty())
            status("Invalid boolean value \"" + value + "\"");
    }

    public void dirHome() {
        if (buffer instanceof DirectoryBuffer)
            ((DirectoryBuffer) buffer).home();
    }

    public void dirTagFile() {
        if (buffer instanceof DirectoryBuffer)
            ((DirectoryBuffer) buffer).tagFileAtDot();
    }

    public void dirBrowseFile() {
        if (buffer instanceof DirectoryBuffer && !buffer.getFile().isRemote()) {
            DirectoryBuffer d = (DirectoryBuffer) buffer;
            d.browseFileAtDot();
        }
    }

    public void dirDeleteFiles() {
        if (mark != null && getMarkLine() != getDotLine()) {
            MessageDialog.showMessageDialog(
                this,
                "This operation is not supported with multi-line text selections.",
                "Delete Files"
            );
            return;
        }
        if (buffer instanceof DirectoryBuffer) {
            if (buffer.getFile() instanceof SshFile) {
                MessageDialog
                    .showMessageDialog(this, "Deletions are not yet supported in ssh directory buffers.", "Error");
                return;
            }
            ((DirectoryBuffer) buffer).deleteFiles();
        }
    }

    public void dirCopyFile() {
        if (buffer instanceof DirectoryBuffer && buffer.getFile().isLocal())
            ((DirectoryBuffer) buffer).copyFileAtDot();
    }

    public void dirGetFile() {
        if (buffer instanceof DirectoryBuffer && buffer.getFile() instanceof FtpFile)
            ((DirectoryBuffer) buffer).getFileAtDot();
    }

    public void dirMoveFile() {
        if (buffer instanceof DirectoryBuffer && buffer.getFile().isLocal())
            ((DirectoryBuffer) buffer).moveFileAtDot();
    }

    public void dirRescan() {
        if (buffer instanceof DirectoryBuffer) {
            setWaitCursor();
            ((DirectoryBuffer) buffer).rescan();
            setDefaultCursor();
        }
    }

    public void dirHomeDir() {
        File homeDir = File.getInstance(Utilities.getUserHome());
        if (buffer instanceof DirectoryBuffer) {
            if (!buffer.getFile().equals(homeDir))
                ((DirectoryBuffer) buffer).changeDirectory(homeDir);
        } else {
            Buffer buf = getBuffer(homeDir);
            if (buf != null) {
                makeNext(buf);
                activate(buf);
            }
        }
    }

    public void dirUpDir() {
        if (buffer instanceof DirectoryBuffer)
            ((DirectoryBuffer) buffer).upDir();
    }

    public void setFocusToTextField() {
        if (locationBar != null)
            frame.setFocus(locationBar.getTextField());
    }

    public final void updateDotLine() {
        display.lineChanged(dot.getLine());
    }

    // Adds line to changed line list of current frame only.
    public final void update(Line line) {
        display.lineChanged(line);
    }

    /**
     * Adds line to changed line list of all editors in which the current
     * buffer is displayed.
     *
     * @param line      the line
     */
    public static void updateInAllEditors(Line line) {
        if (line != null) {
            for (Editor ed : Editor.getEditorList()) {
                if (ed.getBuffer() == currentEditor.getBuffer())
                    ed.getDisplay().lineChanged(line);
            }
        }
    }

    /**
     * Adds line to changed line list of all editors in which the specified
     * buffer is displayed.
     *
     * @param buffer    the buffer
     * @param line      the line
     */
    public static void updateInAllEditors(Buffer buffer, Line line) {
        if (line != null) {
            for (Editor ed : Editor.getEditorList()) {
                if (ed.getBuffer() == buffer)
                    ed.getDisplay().lineChanged(line);
            }
        }
    }

    public void updateScrollBars() {
        if (buffer == null)
            return; // Avoid NPE.
        if (!displayReady)
            return;
        updateVerticalScrollBar();
        updateHorizontalScrollBar();
    }

    boolean inScrollBarUpdate = false;

    public void updateVerticalScrollBar() {
        if (verticalScrollBar != null) {
            inScrollBarUpdate = true;
            int y;
            if (getTopLine() != null)
                y = buffer.getY(getTopLine()) + display.getPixelsAboveTopLine();
            else
                y = display.getPixelsAboveTopLine();
            verticalScrollBar.setValues(y, display.getHeight(), 0, buffer.getDisplayHeight());
            inScrollBarUpdate = false;
        }
    }

    public void updateHorizontalScrollBar() {
        if (horizontalScrollBar != null)
            horizontalScrollBar.setValues(
                display.getShift() * Display.getCharWidth(),
                display.getWidth(),
                0,
                buffer.getDisplayWidth()
            );
    }

    public void updateDisplay() {
        if (dot != null) {
            if (dot.isHidden()) {
                buffer.appendUndoFold(this);
                FoldCommands.show(this, getDotLine());
            }
            reframe();
        }
        display.repaintChangedLines();
        updateScrollBars();
        Sidebar sidebar = getSidebar();
        if (sidebar != null)
            sidebar.setUpdateFlag(SIDEBAR_POSITION);
        if (frame != null)
            frame.repaintStatusBar();
        if (buffer.isBusy())
            setWaitCursor();
        else
            setDefaultCursor();
    }

    public void updateDisplayLater() {
        Runnable r = () -> {
            updateDisplay();
        };
        SwingUtilities.invokeLater(r);
    }

    // Update display of buf in all windows showing it.
    public static void updateDisplayLater(final Buffer buf) {
        Runnable r = () -> {
            for (int i = 0; i < getEditorCount(); i++) {
                Editor ed = getEditor(i);
                if (ed.getBuffer() == buf)
                    ed.updateDisplay();
            }
        };
        SwingUtilities.invokeLater(r);
    }

    public final Line getTopLine() {
        return display.getTopLine();
    }

    public final void setTopLine(Line line) {
        display.setTopLine(line);
    }

    public final void setUpdateFlag(int mask) {
        display.setUpdateFlag(mask);
    }

    public final void reframe() {
        display.reframe();
    }

    public boolean checkReadOnly() {
        boolean readOnly = buffer.isReadOnly();
        if (readOnly) {
            for (VcsBackend backend : VcsBackends.all()) {
                if (backend.autoEdit(this)) {
                    readOnly = buffer.isReadOnly();
                    break;
                }
            }
        }
        if (readOnly) {
            status("Buffer is read only");
            return false;
        }
        if (buffer.isLocked())
            return false;
        if (dot == null)
            return false;
        return true;
    }

    public static boolean checkExperimental() {
        return prefs.getBooleanProperty(Property.ENABLE_EXPERIMENTAL_FEATURES);
    }

    public void status(String s) {
        lastStatus = s;
        if (frame != null)
            frame.setStatusText(s);
    }

    /**
     * The last message given to {@link #status}, frame or no frame.
     *
     * A frameless editor drops status messages, which made every fix of the
     * kind "report this error rather than do nothing" untestable: the text
     * is the same either way and the message is the whole change. Package
     * private, for the test harness; the same trade as
     * Display.isRepaintPending.
     */
    String getLastStatus() {
        return lastStatus;
    }

    private String lastStatus;

    private static boolean displayReady;

    public static final boolean displayReady() {
        return displayReady;
    }

    public static final void setDisplayReady(boolean b) {
        displayReady = b;
    }

    private static final Cursor waitCursor =
        Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR);

    public final void setWaitCursor() {
        display.setCursor(waitCursor);
    }

    public final void setDefaultCursor() {
        final Cursor cursor;
        if (displayReady && buffer != null)
            cursor = buffer.getDefaultCursor();
        else
            cursor = waitCursor;
        display.setCursor(cursor);
    }

    public final void setCursor(Cursor cursor) {
        display.setCursor(cursor);
    }

    public static void loadPreferences() {
        prefs.reload();
        debug = prefs.getBooleanProperty(Property.DEBUG, Editor.debug);
    }

    private boolean insertingKeyText = false;

    public void insertKeyText() {
        if (!checkReadOnly())
            return;
        insertingKeyText = true; // The real work is done in handleKeyEvent.
    }

    private void insertKeyTextInternal(char keyChar, int keyCode, int modifiers) {
        Log.debug("keycode = 0x" + Integer.toString(keyCode, 16));
        Log.debug("modifiers = 0x" + Integer.toString(modifiers, 16));
        Log.debug("character = " + String.valueOf(keyChar));
        Log.debug("character = 0x" + Integer.toString((int) keyChar, 16));

        insertingKeyText = false;

        buffer.withWriteLock(() -> {
            KeyMapping km;
            if (keyCode != 0)
                km = new KeyMapping(keyCode, modifiers, null);
            else
                km = new KeyMapping(keyChar, null);

            CompoundEdit compoundEdit = beginCompoundEdit();
            if (mark != null)
                delete();
            fillToCaret();
            addUndo(SimpleEdit.INSERT_STRING);
            insertStringInternal(km.toString());
            buffer.modified();
            moveCaretToDotCol();
            endCompoundEdit(compoundEdit);
        });
    }

    public void whatChar() {
        if (dot.getOffset() < dot.getLineLength()) {
            char c = getDotChar();
            StringBuilder sb = new StringBuilder(Integer.toString(c));
            sb.append("  0x");
            sb.append(Integer.toHexString(c));
            if (c >= ' ' && c < 0x7f) {
                sb.append("  '");
                if (c == '\'')
                    sb.append('\\');
                sb.append(c);
                sb.append('\'');
            }
            status(sb.toString());
        }
    }

    public void httpDeleteCookies() {
        Cookie.deleteCookies();
    }

    private static void runStartupScript() {
        File file =
            File.getInstance(Directories.getConfigDirectory(), "init.lisp");
        if (file != null && file.isFile()) {
            if (!Extensions.languageClient().isAvailable()) {
                // Starting anyway would drop every customization in the file
                // on the floor, silently, and leave the user wondering why
                // their key bindings stopped working.
                StringBuilder sb = new StringBuilder();
                sb.append(file.canonicalPath());
                sb.append(" cannot run: no language client is installed.\n");
                sb.append("Install the abcl extension, remove the file, or ");
                sb.append("start j with -q to skip it.");
                fatal(sb.toString());
            }
            try {
                long start = System.currentTimeMillis();
                Extensions.session().loadFile(file);
                long elapsed = System.currentTimeMillis() - start;
                StringBuilder sb = new StringBuilder("loaded ");
                sb.append(file.canonicalPath());
                sb.append(" (");
                sb.append(elapsed);
                sb.append(" ms)");
                Log.info(sb.toString());
            }
            catch (Throwable t) {
                Log.error(t);
                Log.error("error loading " + file.canonicalPath());
            }
        }
    }

    public void mode() {
        String modeName =
            InputDialog.showInputDialog(this, "New mode:", "Change Mode");
        if (modeName != null) {
            modeName = modeName.trim();
            if (modeName.length() > 0) {
                repaintNow();
                mode(modeName);
            }
        }
    }

    public void mode(String modeName) {
        int modeId = getModeList().getModeIdFromModeName(modeName);
        if (modeId < 0) {
            MessageDialog.showMessageDialog(
                "Unknown mode \"" + modeName + '"',
                "Error"
            );
        } else if (modeId != buffer.getMode().getId()) {
            if (buffer.isModified() && modeId == BINARY_MODE) {
                String prompt =
                    "Buffer will be reloaded in binary mode; discard changes?";
                if (!confirm("Change Mode", prompt))
                    return;
            }
            Mode mode = getModeList().getMode(modeId);
            if (mode != null) {
                setWaitCursor();
                buffer.changeMode(mode);
                buffer.saveProperties();
                setDefaultCursor();
            }
        }
    }

    public void defaultMode() {
        Mode mode = buffer.getDefaultMode();
        if (mode != null && mode != buffer.getMode()) {
            if (buffer.isModified()) {
                StringBuilder sb =
                    new StringBuilder("Buffer will be reloaded in ");
                sb.append(mode.toString());
                sb.append(" mode; discard changes?");
                if (!confirm("Change Mode", sb.toString()))
                    return;
            }
            setWaitCursor();
            buffer.changeMode(mode);
            setDefaultCursor();
        }
    }

    public void textMode() {
        if (buffer.getModeId() == BINARY_MODE) {
            if (buffer.isModified()) {
                String prompt =
                    "Buffer will be reloaded in text mode; discard changes?";
                if (!confirm("Change Mode", prompt))
                    return;
            }
            setWaitCursor();
            buffer.changeMode(modeList.getMode(PLAIN_TEXT_MODE));
            setDefaultCursor();
        }
    }

    private static Aliases aliases;

    private static final Aliases getAliases() {
        if (aliases == null)
            aliases = new Aliases();
        return aliases;
    }

    public static final File getAliasesFile() {
        return aliases != null ? aliases.getFile() : null;
    }

    public static final void reloadAliases() {
        if (aliases != null)
            aliases.reload();
    }

    public final String getAlias(String alias) {
        return getAliases().get(alias);
    }

    public final void setAlias(String alias, String value) {
        getAliases().setAlias(alias, value);
    }

    public final void setAliasForBuffer(String alias, Buffer buf) {
        getAliases().setAliasForBuffer(alias, buf);
    }

    public final void removeAlias(String alias) {
        getAliases().remove(alias);
    }

    public void setEncoding() {
        File file = buffer.getFile();
        if (file != null) {
            InputDialog d =
                new InputDialog(
                    this,
                    "Encoding:",
                    "Set Encoding",
                    buffer.getSaveEncoding()
                );
            d.setHistory(new History("setEncoding"));
            centerDialog(d);
            d.setVisible(true);
            String encoding = d.getInput();
            if (encoding != null)
                setEncoding(encoding);
        }
    }

    public void setEncoding(String encoding) {
        File file = buffer.getFile();
        if (file != null) {
            if (Utilities.isSupportedEncoding(encoding)) {
                file.setEncoding(encoding);
                buffer.saveProperties();
            } else {
                StringBuilder sb =
                    new StringBuilder("Unsupported encoding \"");
                sb.append(encoding);
                sb.append('"');
                MessageDialog.showMessageDialog(this, sb.toString(), "Error");
            }
        }
    }

    private static String sessionName;

    public static String getSessionName() {
        return sessionName;
    }

    public static void setSessionName(String name) {
        sessionName = name;
        // The session name is displayed in the frame's title bar.
        for (int i = 0; i < getFrameCount(); i++) {
            Frame frame = getFrame(i);
            if (frame != null)
                frame.titleChanged();
        }
    }

    /** A jump is leaving the caret's position: onto the {@link JumpList}. */
    public void recordJump() {
        if (dot != null)
            JumpList.record(buffer, dot);
    }

    /** {@code pushPosition} -- puts the caret's position on the jump list. */
    public void pushPosition() {
        recordJump();
        status("Position saved");
    }

    /** {@code popPosition} -- back along the jump list, as jumpBack. */
    public void popPosition() {
        JumpList.jumpBack();
    }

    public static void resetDisplay() {
        if (!displayReady())
            return;
        // Force formatters to be re-initialized.
        for (Buffer buf : Editor.getBufferList()) {
            if (buf.getFormatter() != null)
                buf.getFormatter().reset();
        }
        Display.initializeStaticValues();
        for (Editor ed : Editor.getEditorList()) {
            Display display = ed.getDisplay();
            display.initialize();
            display.repaint();
        }
        Editor.restoreFocus();
    }
}
