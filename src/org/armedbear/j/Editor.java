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

import static org.armedbear.j.Constants.*;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.dnd.DropTarget;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.extension.EvalException;
import org.armedbear.j.extension.EvalRequest;
import org.armedbear.j.extension.EvalResult;
import org.armedbear.j.extension.Extensions;
import org.armedbear.j.extension.Opener;
import org.armedbear.j.extension.ScriptFunction;
import org.armedbear.j.mode.dir.DirectoryBuffer;
import org.armedbear.j.mode.dir.DirectoryTree;
import org.armedbear.j.mode.image.ImageBuffer;
import org.armedbear.j.util.Keys;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.vcs.VcsBackend;
import org.armedbear.j.vcs.VcsBackends;
import org.jdesktop.swingx.MultiSplitLayout;

public final class Editor extends JPanel implements ComponentListener, MouseWheelListener {
    private static boolean debug = false;
    private static boolean saveSession = true;

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
        return isSearchShared() ? sharedSearchHighlightHidden : searchHighlightHidden;
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
        return buffer.getBooleanProperty(Property.HIGHLIGHT_SEARCH_MATCHES) ? lastSearchMatches(line) : null;
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
        return handler == null ? null : handler.getCurrentSearchMatch(this, line);
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

    Map<SystemBuffer, View> views = new HashMap<>();

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
        return Startup.startTimeMillis();
    }

    /**
     * Commands that live in an extension rather than in core.
     *
     * <p>Without this, someone who upgrades gets a bare "Unknown command" for
     * something that worked the day before, with nothing to say where it
     * went.
     */
    private static final Map<String, String> commandProviders = Map.of("jlisp", "abcl");

    static String unknownCommandMessage(String command) {
        String extension = command == null ? null : commandProviders.get(command.toLowerCase(Locale.ROOT));
        if (extension == null)
            return "Unknown command \"".concat(String.valueOf(command)).concat("\"");
        return "\"".concat(command)
                .concat("\" is provided by the ")
                .concat(extension)
                .concat(" extension, which is not installed.");
    }

    public Editor(Frame f) {
        display = new Display(this);
        dispatcher = new Dispatcher(this);
        init();
        try {
            frame = f != null ? f : new Frame(this);
        }
        catch (RuntimeException | Error e) {
            // Leave no frameless editor in the list.
            editorList.remove(this);
            throw e;
        }
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

    // Set once by Startup.main.
    static void setDebugEnabled(boolean b) {
        debug = b;
    }

    static void setSaveSession(boolean b) {
        saveSession = b;
    }

    static void setSessionProperties(SessionProperties properties) {
        sessionProperties = properties;
    }

    static void setTagFileManager(TagFileManager manager) {
        tagFileManager = manager;
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

    // Shown only for a prompt, in a panel that is to have none.
    private boolean promptOnlyLocationBar;

    /**
     * The location bar for a prompt to use. The panel, when it is to have
     * none, gets one until focus is back in the text.
     */
    public final LocationBar getPromptLocationBar() {
        if (locationBar == null && hidesLocationBar()) {
            locationBar = new LocationBar(this);
            add(locationBar, BorderLayout.NORTH);
            promptOnlyLocationBar = true;
            locationBar.update();
            // Gone when the prompt is, wherever focus goes: a file opened from
            // the panel takes it to the window behind.
            locationBar.getTextField().addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    if (!e.isTemporary())
                        SwingUtilities.invokeLater(Editor.this::dropPromptLocationBar);
                }
            });
            revalidate();
        }
        return locationBar;
    }

    private void dropPromptLocationBar() {
        if (promptOnlyLocationBar && locationBar != null && !locationBar.getTextField().isFocusOwner()) {
            removeLocationBar();
            revalidate();
            repaint();
        }
    }

    public final HistoryTextField getPromptTextField() {
        final LocationBar bar = getPromptLocationBar();
        return bar == null ? null : bar.getTextField();
    }

    // The panel, when the preference says it has no location bar.
    private boolean hidesLocationBar() {
        return frame != null
                && frame.isPanel(this)
                && !preferences().getBooleanProperty(Property.TRANSIENT_PANEL_LOCATION_BAR);
    }

    public final void repaintLocationBar() {
        if (locationBar != null)
            locationBar.repaint();
    }

    public void addLocationBar() {
        if (locationBar == null && !hidesLocationBar()) {
            locationBar = new LocationBar(this);
            add(locationBar, BorderLayout.NORTH);
        }
    }

    public void removeLocationBar() {
        if (locationBar != null) {
            remove(locationBar);
            locationBar = null;
            promptOnlyLocationBar = false;
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

    private static final List<Frame> frames = new ArrayList<>();

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
            inputHandler = "vim".equals(editMode) ? new org.armedbear.j.vim.VimInputHandler() : null;
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
        MessageDialog.showMessageDialog(this, "Operation not supported for column selections", "Error");
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
    static int bookmarkIndex(char name) {
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

    public char getDotChar() {
        Debug.assertTrue(dot != null);
        return dot.getChar();
    }

    // closeParen's highlight of the matching paren, while it shows.
    javax.swing.Timer parenFlash;
    /*package*/ static int parenFlashMillis = 300;

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
        if (object instanceof JEvent e) {
            if (e.getID() == JEvent.KEY_PRESSED) {
                if (e.getKeyCode() == 0x47 && e.getModifiers() == CTRL_MASK)
                    return true;
            }
            return false;
        }
        if (object instanceof KeyEvent e) {
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
            EditCommands.insertKeyTextInternal(this, keyChar, keyCode, modifiers);
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
            mapping = buffer.getMode().getKeyMap().lookup(keyChar, keyCode, modifiers);
            if (mapping != null)
                local = true;
            else if (event.getID() == JEvent.KEY_TYPED && keyChar == 'q' && modifiers == 0 && buffer.isTransient()) {
                // q closes help, results and output, in whatever mode, as
                // the status bar says.
                closeTransient();
                return true;
            } else
                // Look in global key map.
                mapping = KeyMap.getGlobalKeyMap().lookup(keyChar, keyCode, modifiers);
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
            if (command instanceof KeyMap keyMap) {
                // Emacs-style key sequence.
                if (currentEventSequence == null)
                    currentEventSequence = new EventSequence();
                currentEventSequence.addEvent(event);
                requestedKeyMap = keyMap;
                status(currentEventSequence.getStatusText() + "-");
                return true;
            }
            if (isRecordingMacro())
                Macro.record(this, command);
            if (command instanceof String commandString) {
                requestedKeyMap = null;
                currentEventSequence = null;
                local = false;
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
                    catch (NoSuchMethodException ignored) {}
                }
            } else if (command instanceof Command c) {
                requestedKeyMap = null;
                currentEventSequence = null;
                local = false;
                try {
                    execute(c, null);
                }
                catch (NoSuchMethodException e) {
                    Log.error(e);
                }
                return true;
            } else if (command instanceof ScriptFunction scriptFunction) {
                requestedKeyMap = null;
                currentEventSequence = null;
                local = false;
                try {
                    scriptFunction.invoke();
                }
                catch (Throwable e) {
                    Log.error(e);
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
        KeyMapping mapping = buffer.getMode().getKeyMap().lookup(keyChar, keyCode, modifiers);
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
                        javax.swing.KeyStroke.getKeyStroke(mapping.getKeyCode(), mapping.getModifiers());
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
            final int limit = buffer.getCol(getDotLine(), getDotLine().length());
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
        if (e instanceof MouseEvent mouseEvent)
            mouseMoveDotToPoint(mouseEvent);
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
            if (e instanceof MouseEvent mouseEvent) {
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
            if (e instanceof MouseEvent mouseEvent) {
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
        if (e instanceof MouseEvent mouseEvent) {
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
            Log.error("insertChar dot offset = " + getDotOffset() + " dotLine length = " + dotLine.length());
            // Enforce sanity and carry on.
            dot.setOffset(dotLine.length());
        }
        if (!buffer.withWriteLock(() -> {
            addUndo(SimpleEdit.LINE_EDIT);
            fillToCaret();
            StringBuilder sb = new StringBuilder(dotLine.substring(0, getDotOffset()));
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

    // Used only by insertChar and insertByte. Doesn't understand a leading
    // minus sign.
    static int parseNumericInput(String input) {
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

    // Returns true if the buffer is active and there has been some change
    // that requires us to redraw the menus, title bar or display, false
    // otherwise.
    public boolean reactivate(Buffer buf) {
        if (buf instanceof ImageBuffer imageBuffer)
            return imageBuffer.reactivate();

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
                MessageDialog.showMessageDialog(file.canonicalPath().concat(" is no longer writable"), "Warning");
        }

        if (buf.isLoaded()) {
            if (file.lastModified() != buf.getLastModified()) {
                if (buf.isModified()) {
                    String prompt = file.canonicalPath() + " has changed on disk. Reload and lose current changes?";
                    if (confirm("Reload File From Disk", prompt)) {
                        FileCommands.reload(this, buf);
                        changed = true;
                    } else
                        buf.setLastModified(file.lastModified());
                } else {
                    // No need for confirmation.
                    FileCommands.reload(this, buf);
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
        if (promptOnlyLocationBar) {
            removeLocationBar();
            revalidate();
        }
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

    @Override
    public void componentHidden(ComponentEvent e) {}

    @Override
    public void componentMoved(ComponentEvent e) {}

    @Override
    public void componentResized(ComponentEvent e) {
        updateScrollBars();
    }

    @Override
    public void componentShown(ComponentEvent e) {}

    @Override
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
                MessageDialog.showMessageDialog("File is not readable", "Error");
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

    /**
     * Shows a buffer picked from this window, made next in the buffer list,
     * where it belongs: a transient buffer in the panel, opened for it; from
     * the panel, any other in the window behind it; else here.
     *
     * @return the window it is in, now the current one
     */
    public Editor show(Buffer buf) {
        return show(buf, true);
    }

    /** As show(buf), made next in the buffer list only with makeNext, as cycling through it must not. */
    public Editor show(Buffer buf, boolean makeNext) {
        Editor ed = placeFor(buf);
        if (buf != null && buf != ed.getBuffer()) {
            if (makeNext)
                ed.makeNext(buf);
            ed.switchToBuffer(buf);
        }
        // Half of a pair goes in its own window, as a message under its
        // mailbox's: the window it is in is the one returned.
        if (buf != null && ed.getBuffer() != buf && frame != null) {
            if (frame.getCurrentEditor().getBuffer() == buf)
                ed = frame.getCurrentEditor();
            else if (frame.findEditor(buf) != null)
                ed = frame.findEditor(buf);
        }
        return ed;
    }

    // The window for show(), made current; the panel opened for a transient buffer.
    private Editor placeFor(Buffer buf) {
        if (frame == null || buf == null)
            return this;
        final boolean inPanel = frame.isPanel(this);
        if (!inPanel && buf.isTransient() && !buf.isPaired())
            return frame.openInPanel(this, buf, true);
        // A pair goes in the windows, never the panel.
        if (inPanel && (!buf.isTransient() || buf.isPaired())) {
            final Editor ed = frame.getWindowBehindPanel();
            if (ed != null && ed != this) {
                setCurrentEditor(ed);
                frame.setMenu();
                frame.setToolbar();
                ed.setFocusToDisplay();
                return ed;
            }
        }
        return this;
    }

    public void makeNext(final Buffer buf) {
        bufferList.makeNext(buf, buffer);
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
            if (confirm("Create file?", file.canonicalPath() + " does not exist. Create?"))
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
                catch (NumberFormatException ignored) {}
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
                MessageDialog.showMessageDialog(this, "Invalid path ".concat(s), "Invalid Path");
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
            BufferCommands.killBuffer(this);
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
        if (buffer.isTransient()) {
            closeTransient();
            return true;
        }
        if (buffer.getModeId() == CHECKIN_MODE) {
            WindowCommands.otherWindow(this);
            WindowCommands.unsplitWindow(this);
            if (!buffer.isModified())
                BufferCommands.maybeKillBuffer(this, buffer);
            restoreFocus();
            return true;
        }
        // From another window, Escape closes the panel.
        if (frame != null && frame.getPanelEditor() != null) {
            frame.closePanel(true);
            return true;
        }
        // Mail's message in the window under its mailbox's.
        final Editor bound = frame == null ? null : frame.getBoundWindow(this);
        if (bound != null && bound.getBuffer().isTransient()) {
            bound.closeTransient();
            return true;
        }
        Editor ed = getOtherEditor();
        if (ed != null) {
            Buffer buf = ed.getBuffer();
            if (buf.getModeId() == CHECKIN_MODE) {
                WindowCommands.unsplitWindow(this);
                if (!buf.isModified())
                    BufferCommands.maybeKillBuffer(this, buf);
                return true;
            }
        }
        return false;
    }

    /**
     * Kills this window's transient buffer, closing the panel when it is
     * there, with focus back where it came from.
     */
    public void closeTransient() {
        if (frame != null && frame.isPanel(this)) {
            frame.closePanel(true);
            return;
        }
        final Buffer closing = buffer;
        Editor ed = this;
        // A window bound under another, as mail's message under its mailbox,
        // goes with it.
        if (frame != null && frame.getPrimaryWindow(this) != null) {
            // Saves what it keeps of the window, as a message its split.
            closing.windowClosing();
            frame.closeEditor(this);
            ed = frame.getCurrentEditor();
        }
        BufferCommands.maybeKillBuffer(ed, closing);
        restoreFocus();
        Sidebar.refreshSidebarInAllFrames();
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
            EditCommands.delete(this);
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
        final Buffer leaving = buffer;
        final boolean releasing = frame != null && frame.isPanel(this) && (!buf.isTransient() || buf.isPaired());
        activateBuffer(buf);
        if (frame == null || buffer != buf)
            return;
        // The panel only shows transient buffers: given another, it is an
        // ordinary window now, which callers go on using, and what it showed
        // goes, as when the panel closes.
        if (releasing) {
            frame.releasePanel();
            if (leaving != null
                    && leaving != buf
                    && leaving.isTransient()
                    && bufferList.contains(leaving)
                    && !isShown(leaving))
                BufferCommands.maybeKillBuffer(this, leaving);
        }
        frame.checkBinding(this);
    }

    private static boolean isShown(Buffer buf) {
        for (Editor ed : editorList) {
            if (ed.getBuffer() == buf)
                return true;
        }
        return false;
    }

    private void activateBuffer(Buffer buf) {
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
                MessageDialog.showMessageDialog(this, "Insufficient memory to load buffer", "Error");
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
                    MessageDialog.showMessageDialog(this, "Unable to load buffer", "Error");
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

        // A frameless editor, as a test's, has none of these.
        if (frame != null) {
            frame.updateTitle();
            frame.setMenu();
            frame.setToolbar();
        }

        if (buffer.isBusy())
            setWaitCursor();
        else
            setDefaultCursor();

        setUpdateFlag(REFRAME);
        reframe();
        setUpdateFlag(REPAINT);

        RecentFiles.getInstance().bufferActivated(buffer);

        if (buffer.isTaggable()) {
            tagFileManager.addToQueue(buffer.getCurrentDirectory(), buffer.getMode());
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

    void checkDotInOtherFrames() {
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
        final LocationBar locationBar = getPromptLocationBar();
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
                EvalResult result = Extensions.session().evalSync(EvalRequest.of(input).origin("command-line"));
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
                            boolean append = statusText != null && statusText.length() > 0;
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
                    MessageDialog.showMessageDialog(Editor.this, unknownCommandMessage(command), "Error");
                }
            };
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                try {
                    SwingUtilities.invokeAndWait(r);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                catch (InvocationTargetException e) {
                    Log.debug(e);
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
            MessageDialog.showMessageDialog("Property \"" + key + "\" not found", "Error");
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
                buffer.getDisplayWidth());
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

    private static final Cursor waitCursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR);

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

    @Override
    public final void setCursor(Cursor cursor) {
        display.setCursor(cursor);
    }

    public static void loadPreferences() {
        prefs.reload();
        debug = prefs.getBooleanProperty(Property.DEBUG, Editor.debug);
    }

    private boolean insertingKeyText = false;

    void setInsertingKeyText(boolean b) {
        insertingKeyText = b;
    }

    static void runStartupScript() {
        File file = File.getInstance(Directories.getConfigDirectory(), "init.lisp");
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
                Startup.fatal(sb.toString());
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
            catch (Throwable e) {
                Log.error(e);
                Log.error("error loading " + file.canonicalPath());
            }
        }
    }

    public void mode() {
        String modeName = InputDialog.showInputDialog(this, "New mode:", "Change Mode");
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
            MessageDialog.showMessageDialog("Unknown mode \"" + modeName + '"', "Error");
        } else if (modeId != buffer.getMode().getId()) {
            if (buffer.isModified() && modeId == BINARY_MODE) {
                String prompt = "Buffer will be reloaded in binary mode; discard changes?";
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
                StringBuilder sb = new StringBuilder("Buffer will be reloaded in ");
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
                String prompt = "Buffer will be reloaded in text mode; discard changes?";
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
