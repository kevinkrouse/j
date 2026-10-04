/*
 * Dispatcher.java
 *
 * Copyright (C) 1998-2005 Peter Graves
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

import java.awt.AWTEvent;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceContext;
import java.awt.dnd.DragSourceDragEvent;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DragSourceEvent;
import java.awt.dnd.DragSourceListener;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.List;
import javax.swing.ImageIcon;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.undo.CompoundEdit;
import org.armedbear.j.extension.Extensions;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Keys;

public final class Dispatcher implements Constants, KeyListener, MouseListener,
    MouseMotionListener, ActionListener, DragGestureListener, DragSourceListener,
    DropTargetListener {
    // For IdleThread.run.
    private static volatile long lastEventMillis = System.currentTimeMillis();

    // Needed by dropBookmark, gotoBookmark.
    private AWTEvent lastEvent;

    private static final boolean DEBUG_KEY_PRESSED = false;

    private final Editor editor;
    private final Display display;

    private boolean ignoreKeyTyped;

    private int lastKeyEvent;

    private char charToBeIgnored = '\0';

    private boolean enabled = true;

    // Drag/drop.
    private DragSource dragSource;
    private static DragSourceContext dragSourceContext;
    private boolean inDragText;
    private Region dragTextRegion;
    private static boolean isLineRegion;

    // Cursors for drag/drop.
    private static final int CURSOR_NO = 0;
    private static final int CURSOR_MOVE = 1;
    private static final int CURSOR_COPY = 2;
    private static final Cursor[] cursors = new Cursor[3];

    public Dispatcher(Editor editor) {
        this.editor = editor;
        display = editor.getDisplay();

        if (!GraphicsEnvironment.isHeadless()) {
            dragSource = DragSource.getDefaultDragSource();
            dragSource.createDefaultDragGestureRecognizer(
                display,
                DnDConstants.ACTION_COPY_OR_MOVE,
                this
            );
        }
    }

    /**
     * True if the display this dispatcher serves should see key events.
     *
     * An editor with no frame (headless) is always focused: there is no other
     * component that could hold the focus instead.
     */
    private boolean displayHasFocus() {
        final Frame frame = editor.getFrame();
        return frame == null || frame.getFocusedComponent() == display;
    }

    public final AWTEvent getLastEvent() {
        return lastEvent;
    }

    public static synchronized final long getLastEventMillis() {
        return lastEventMillis;
    }

    private static synchronized final void setLastEventMillis(long when) {
        lastEventMillis = when;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private void dispatch(AWTEvent e) {
        lastEvent = e;

        boolean handled = false;

        switch (e.getID()) {
            case KeyEvent.KEY_PRESSED:
                handled = dispatchKeyPressed((KeyEvent) e);
                break;
            case KeyEvent.KEY_TYPED:
                handled = dispatchKeyTyped((KeyEvent) e);
                break;
            case KeyEvent.KEY_RELEASED:
                break;
            case MouseEvent.MOUSE_PRESSED:
                handled = dispatchMousePressed((MouseEvent) e);
                break;
            case MouseEvent.MOUSE_DRAGGED:
                handled = dispatchMouseDragged((MouseEvent) e);
                break;
            case ActionEvent.ACTION_PERFORMED:
                handled = dispatchActionPerformed((ActionEvent) e);
                break;
        }

        if (handled)
            eventHandled();
        else
            setLastEventMillis(System.currentTimeMillis());
    }

    public void eventHandled() {
        final Buffer buffer = editor.getBuffer();
        if (buffer.needsRenumbering())
            buffer.renumber();

        // Update all editors displaying buffer.
        for (Editor ed : Editor.getEditorList()) {
            if (ed == editor) {
                ed.getDisplay().setCaretVisible(true);
                ed.updateDisplay();
            } else if (ed.getBuffer() == buffer) {
                if (buffer.getModeId() != IMAGE_MODE) {
                    ed.getDisplay().repaintChangedLines();
                    ed.updateScrollBars();
                }
                // A headless editor (test harnesses, e.g.) has no frame.
                final Frame edFrame = ed.getFrame();
                if (edFrame != null)
                    edFrame.repaintStatusBar();
            }
        }

        final int currentCommand = editor.getCurrentCommand();
        if (editor.getLastCommand() == COMMAND_PASTE)
            if (currentCommand != COMMAND_PASTE)
                if (currentCommand != COMMAND_UNDO)
                    Editor.promoteLastPaste();

        editor.setLastCommand(currentCommand);
        editor.setCurrentCommand(COMMAND_NOTHING);

        Extensions.hooks().eventHandled();

        SystemSelection.updateSystemSelection(editor);

        setLastEventMillis(System.currentTimeMillis());
    }

    private boolean dispatchKeyPressed(KeyEvent e) {
        if (editor.getStatusBar() != null)
            editor.getStatusBar().setText(null);

        if (Editor.isMenuSelected) {
            charToBeIgnored = e.getKeyChar();
            ignoreKeyTyped = true;
            return false;
        }

        int keycode = e.getKeyCode();

        // Ignore modifier keystrokes.
        if (
            keycode == KeyEvent.VK_SHIFT
                || keycode == KeyEvent.VK_CONTROL
                ||
                keycode == KeyEvent.VK_ALT
                || keycode == KeyEvent.VK_META
        )
            return false;

        int modifiers = Keys.keyModifiers(e);

        char c = e.getKeyChar();

        if (DEBUG_KEY_PRESSED) {
            Log.debug("keyPressed, keycode = 0x" + Integer.toString(keycode, 16));
            Log.debug("modifiers = 0x" + Integer.toString(modifiers, 16));
            Log.debug("character = " + String.valueOf(c));
            Log.debug("character = 0x" + Integer.toString((int) c, 16));
        }

        // Mask off the bits we don't care about (Java 1.4).
        modifiers &= 0x0f;

        if (DEBUG_KEY_PRESSED)
            Log.debug("modifiers = 0x" + Integer.toString(modifiers, 16));

        boolean handled = editor.handleJEvent(
            new JEvent(
                JEvent.KEY_PRESSED,
                keycode,
                c,
                modifiers
            )
        );

        if (handled) {
            ignoreKeyTyped = true;
            e.consume();
        } else {
            // If we consume indiscriminately here, Alt F no longer works to
            // drop the File menu (for example). So we only consume keystrokes
            // with no modifiers.
            if (modifiers == 0)
                e.consume();
        }

        return handled;
    }

    private boolean dispatchKeyTyped(KeyEvent e) {
        if (Editor.isMenuSelected)
            return false;

        if (ignoreKeyTyped)
            return false;

        final boolean altGraph =
            (e.getModifiersEx() & InputEvent.ALT_GRAPH_DOWN_MASK) != 0;
        int modifiers = Keys.keyModifiers(e);

        if (!altGraph && modifiers != 0 && modifiers != SHIFT_MASK)
            return false;

        char c = e.getKeyChar();

        boolean handled = editor.handleJEvent(
            new JEvent(
                JEvent.KEY_TYPED,
                0,
                c,
                0
            )
        );

        Buffer buffer = editor.getBuffer();

        if (!handled && !buffer.isBusy() && c != 0x1b && c != 0x08 && c != charToBeIgnored) {
            if (c == '\t') {
                if (Editor.isRecordingMacro())
                    Macro.record(editor, "insertTab");
                editor.insertTab();
            } else if (!Character.isISOControl(c)) {
                if (Editor.isRecordingMacro())
                    Macro.record(editor, c);
                editor.insertNormalChar(c);
            }
            handled = true;
        }

        charToBeIgnored = '\0';

        if (handled)
            e.consume();

        // Escape cancels a menu, and focus has to come back to the edit
        // window with it; without this the cursor keys go nowhere.
        if (c == 0x1b) {
            editor.setFocusToDisplay();
            enabled = true;
        }

        return handled;
    }

    public void keyPressed(KeyEvent e) {
        // Force tool tip to be hidden.
        ToolTipManager.sharedInstance().setEnabled(false);
        ToolTipManager.sharedInstance().setEnabled(true);

        if (!displayHasFocus())
            return;

        editor.ensureActive();
        Editor.setCurrentEditor(editor);

        // Ctrl held a moment over a link shows it; Ctrl as part of a chord,
        // Ctrl-O or Ctrl-S, shows nothing and asks the tags nothing.
        if (e.getKeyCode() == KeyEvent.VK_CONTROL)
            hoverTimer.restart();
        else
            hoverTimer.stop();

        if (enabled) {
            lastKeyEvent = KeyEvent.KEY_PRESSED;
            ignoreKeyTyped = false;
            dispatch(e);
        }
    }

    public void keyReleased(KeyEvent e) {
        e.consume();
        if (e.getKeyCode() == KeyEvent.VK_CONTROL) {
            hoverTimer.stop();
            forgetLink();
            hideLink();
        }
        if (!displayHasFocus())
            return;

        if (lastKeyEvent == KeyEvent.KEY_RELEASED) {
            // Work around bug in Windows VMs that causes a subsequent
            // KEY_TYPED event after the Alt key is released when the original
            // keystroke generates an ActionEvent.

            // If we get two (or more) KEY_RELEASED events in a row, wait
            // until we see KEY_PRESSED again before paying attention to
            // KEY_TYPED.
            if (e.getKeyCode() == KeyEvent.VK_ALT)
                ignoreKeyTyped = true;
        } else
            ignoreKeyTyped = false;

        lastKeyEvent = KeyEvent.KEY_RELEASED;
    }

    public void keyTyped(KeyEvent e) {
        if (!displayHasFocus())
            return;

        lastKeyEvent = KeyEvent.KEY_TYPED;
        dispatch(e);
    }

    public void mouseClicked(MouseEvent e) {
        final int modifiers = Keys.keyModifiers(e);
        final int button = e.getButton();
        if (button != MouseEvent.BUTTON1 || modifiers != 0)
            return;
        if (editor.getMark() != null && e.getClickCount() == 1) {
            final Buffer buffer = editor.getBuffer();
            if (buffer.getBooleanProperty(Property.ENABLE_DRAG_TEXT)) {
                Region r = new Region(editor);
                Position pos = display.positionFromPoint(e.getPoint());
                if (pos.isAfter(r.getBegin()) && pos.isBefore(r.getEnd())) {
                    editor.beginMotion();
                    display.moveCaretToPoint(e.getPoint());
                    if (buffer.getBooleanProperty(Property.RESTRICT_CARET))
                        editor.moveCaretToDotCol();
                    editor.updateDisplay();
                }
            }
        }
    }

    private boolean dragTextStarting;

    public void mousePressed(MouseEvent e) {
        if (editor.getFocusedComponent() == editor.getLocationBarTextField()) {
            TextFieldHandler handler = editor.getLocationBarTextField().getHandler();
            if (handler instanceof IncrementalFindTextFieldHandler) {
                handler.enter();
            } else
                handler.escape();
        } else
            LocationBar.cancelInput();
        editor.ensureActive();
        if (editor != Editor.currentEditor()) {
            Editor oldEditor = Editor.currentEditor();
            Editor.setCurrentEditor(editor);
            if (oldEditor.getDot() != null)
                oldEditor.update(oldEditor.getDotLine());
            oldEditor.updateDisplay();
            Frame frame = editor.getFrame();
            frame.setMenu();
            frame.setToolbar();
            Sidebar sidebar = frame.getSidebar();
            if (sidebar != null)
                sidebar.setUpdateFlag(SIDEBAR_SET_BUFFER);
        }
        // A plain left click, with no keyboard modifier.
        final boolean plainButton1 =
            e.getButton() == MouseEvent.BUTTON1 && Keys.isUnmodified(e);
        JPopupMenu popup = editor.getPopup();
        if (popup != null) {
            if (popup.isVisible()) {
                editor.killPopup();
                if (!plainButton1)
                    return;
            }
            editor.setPopup(null);
        }
        if (plainButton1 && editor.getMark() != null) {
            if (editor.getBuffer().getBooleanProperty(Property.ENABLE_DRAG_TEXT)) {
                Region r = new Region(editor);
                if (!r.isColumnRegion()) {
                    Position pos = display.positionFromPoint(e.getPoint());
                    if (pos.isAfter(r.getBegin()) && pos.isBefore(r.getEnd())) {
                        dragTextStarting = true;
                        return;
                    }
                }
            }
        }
        dragTextStarting = false;
        dispatch(e);
    }

    public void mouseReleased(MouseEvent e) {}

    public void mouseEntered(MouseEvent e) {}

    public void mouseExited(MouseEvent e) {
        lastMousePoint = null;
        forgetLink();
        hideLink();
    }

    // The buffer may change before Ctrl is held again.
    private void forgetLink() {
        hoverAskedLine = null;
        hoverAnswer = null;
    }

    private boolean dispatchMousePressed(MouseEvent e) {
        editor.setFocusToDisplay();

        enabled = true;

        if (editor.getStatusBar() != null)
            editor.getStatusBar().setText(null);

        if (editor.getModeId() == IMAGE_MODE)
            return false;

        final int x = e.getX();
        final int y = e.getY();

        final int modifiers = Keys.keyModifiers(e);
        final int button = e.getButton();

        final Mode mode = editor.getMode();
        final Buffer buffer = editor.getBuffer();

        // Folding.
        if (x < display.getGutterWidth(buffer)) {
            Position pos = display.positionFromPoint(x, y);
            Line next = pos.getLine().next();
            if (next != null && next.isHidden()) {
                editor.unfold(next);
                return true;
            } else if (button == MouseEvent.BUTTON2 && modifiers == 0) {
                // Middle button.
                editor.foldNearLine(pos.getLine());
                return true;
            }
            // else fall through...
        } else if (button == MouseEvent.BUTTON2 && modifiers == 0) {
            Position pos = display.positionFromPoint(x, y);
            if (pos != null && pos.getOffset() < pos.getLine().getIndentation()) {
                Line next = pos.getLine().next();
                if (next != null) {
                    if (next.isHidden()) {
                        editor.unfold(next);
                        return true;
                    } else if (next.trim().endsWith("{")) {
                        next = next.next();
                        if (next != null && next.isHidden()) {
                            editor.unfold(next);
                            return true;
                        }
                    }
                }
                // Couldn't find a fold to expand.
                editor.foldNearLine(pos.getLine());
                return true;
            }
            // else fall through...
        }

        int keycode = 0;
        final int clickCount = e.getClickCount();
        if (clickCount == 1 || clickCount == 2) {
            final boolean doubled = clickCount == 2;
            switch (button) {
                case MouseEvent.BUTTON1:
                    keycode = doubled ? VK_DOUBLE_MOUSE_1 : VK_MOUSE_1;
                    break;
                case MouseEvent.BUTTON2:
                    keycode = doubled ? VK_DOUBLE_MOUSE_2 : VK_MOUSE_2;
                    break;
                case MouseEvent.BUTTON3:
                    keycode = doubled ? VK_DOUBLE_MOUSE_3 : VK_MOUSE_3;
                    break;
            }
        }
        if (keycode == 0)
            return false;
        return editor.handleJEvent(
            new JEvent(
                JEvent.MOUSE_PRESSED,
                keycode,
                (char) 0,
                modifiers
            )
        );
    }

    public void mouseDragged(MouseEvent e) {
        dispatch(e);
    }

    // Where the mouse was last, for showing the link under it when Ctrl is
    // pressed with the mouse still.
    private Point lastMousePoint;

    // How long Ctrl is held before the link under a still mouse shows.
    private static final int HOVER_DELAY = 250;

    private final javax.swing.Timer hoverTimer =
        new javax.swing.Timer(HOVER_DELAY, e -> showLinkAt(lastMousePoint));
    {
        hoverTimer.setRepeats(false);
    }

    // The last place asked about and what was there: a tag lookup can read
    // tag files, so a mouse that moves within a character, or within the
    // link already shown, does not ask again.
    private Line hoverAskedLine;
    private int hoverAskedOffset = -1;
    private TextLink hoverAnswer;

    private TextLink linkAt(Position pos) {
        final Line line = pos.getLine();
        final int offset = pos.getOffset();
        if (
            line == hoverAskedLine
                && (offset == hoverAskedOffset
                    || hoverAnswer != null
                        && hoverAnswer.getBegin() <= offset
                        && offset < hoverAnswer.getEnd())
        )
            return hoverAnswer;
        hoverAskedLine = line;
        hoverAskedOffset = offset;
        hoverAnswer = editor.getMode().getLinkAt(editor, pos);
        return hoverAnswer;
    }

    /**
     * Shows the link under point as a click would follow it, as IntelliJ
     * does under Ctrl, with a hand for the mouse. Returns whether there was
     * one.
     */
    private boolean showLinkAt(Point point) {
        final Position pos = point != null ? display.positionFromPoint(point) : null;
        final TextLink link = pos != null ? linkAt(pos) : null;
        if (link == null || link.getTarget() == null) {
            display.setHoverLink(null, null);
            return false;
        }
        display.setHoverLink(pos.getLine(), link);
        editor.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return true;
    }

    private void hideLink() {
        if (display.getHoverLine() != null) {
            display.setHoverLink(null, null);
            editor.setDefaultCursor();
        }
    }

    public void mouseMoved(MouseEvent e) {
        lastMousePoint = e.getPoint();
        if (e.isControlDown()) {
            if (showLinkAt(lastMousePoint))
                return;
        } else {
            hideLink();
        }
        final Buffer buffer = editor.getBuffer();
        final Position pos = display.positionFromPoint(e.getPoint());
        final String contextString = buffer.getMode().getMouseMovedContextString(editor, pos);
        if (contextString != null) {
            // Context string will be "" rather than null if we should clear
            // the status text (e.g. web mode).
            editor.status(contextString);
        }
        if (buffer.isBusy())
            editor.setWaitCursor();
        else if (e.getX() < Display.getGutterWidth(buffer))
            editor.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        else
            editor.setCursor(buffer.getDefaultCursor(pos));
    }

    private boolean dispatchMouseDragged(MouseEvent e) {
        if (editor.getModeId() == IMAGE_MODE)
            return false;
        final Position dot = editor.getDot();
        if (dot == null)
            return false;

        // During a drag getModifiersEx reports which buttons are actually
        // held, so this can ask for button 1 directly.
        final int ex = e.getModifiersEx();
        if ((ex & InputEvent.BUTTON1_DOWN_MASK) == 0)
            return false;
        if (
            (ex & (InputEvent.BUTTON2_DOWN_MASK
                | InputEvent.BUTTON3_DOWN_MASK)) != 0
        )
            return false;
        if (!Keys.isUnmodified(e))
            return false;

        // No drag select with column selections.
        if (editor.isColumnSelection())
            return false;

        // Reaching here, button 1 must be down, or we wouldn't have a mouse
        // dragged event.
        if (inDragText)
            return false;

        Point point = e.getPoint();
        if (point.y < 0) {
            display.windowUp();
            point.y = 1;
        } else {
            int limit = display.getRows() * display.getCharHeight();
            if (point.y >= limit) {
                display.windowDown();
                point.y = limit - 1;
            }
        }

        Position pos = display.positionFromPoint(point);
        if (pos == null || pos.equals(dot))
            return false;

        if (editor.getMark() != null) {
            if (editor.getBuffer().getBooleanProperty(Property.ENABLE_DRAG_TEXT)) {
                if (dragTextStarting)
                    return false;
            }
        }

        if (editor.getMark() == null) {
            editor.addUndo(SimpleEdit.MOVE);
            editor.setMarkAtDot();
        }

        final Position oldDot = new Position(editor.getDot());

        final Line newLine = pos.getLine();
        if (newLine != dot.getLine()) {
            editor.updateDotLine();
            dot.setLine(newLine);
        }
        editor.moveDotToCol(display.getColumn(newLine, point.x) + display.getShift());

        // Don't let caret go beyond end of text on line.
        editor.moveCaretToDotCol();

        Region r = new Region(editor.getBuffer(), editor.getDot(), oldDot);
        for (Line line = r.getBeginLine(); line != r.getEndLine(); line = line.next())
            editor.update(line);

        return true;
    }

    private boolean dispatchActionPerformed(ActionEvent event) {
        editor.executeCommand(event.getActionCommand());
        return true;
    }

    public void actionPerformed(final ActionEvent e) {
        Runnable r = () -> {
            dispatch(e);
        };
        SwingUtilities.invokeLater(r);
    }

    public void dragEnter(DropTargetDragEvent event) {
        if (editor.getBuffer().isReadOnly()) {
            event.rejectDrag();
        } else {
            if (Platform.isPlatformUnix() && dragSourceContext != null)
                dragSourceContext.setCursor(getCursorForAction(event.getDropAction()));
            editor.getFrame().toFront();
        }
    }

    public void dragExit(DropTargetEvent e) {
        display.setDragCaretPos(null);
        if (Platform.isPlatformUnix() && dragSourceContext != null)
            dragSourceContext.setCursor(getDragCursor(CURSOR_NO));
    }

    public void dragOver(DropTargetDragEvent event) {
        if (event.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            Point pt = event.getLocation();

            Line line = display.lineFromY(pt.y);
            if (line == null)
                return;
            Position pos = new Position(line, 0);
            int absCol = 0;

            if (isLineRegion) {
                // Leave drag caret in column 0.
                ;
            } else {
                int col = Math.max(display.getColumn(line, pt.x), 0);
                absCol = col + display.getShift();
                pos.moveToCol(absCol, editor.getBuffer().getTabWidth());
            }

            boolean ok = false;
            if (dragTextRegion == null)
                ok = true;
            else if (pos.isBefore(dragTextRegion.getBegin()))
                ok = true;
            else if (pos.isAfter(dragTextRegion.getEnd()))
                ok = true;
            else if (pos.equals(dragTextRegion.getEnd())) {
                if (absCol > editor.getBuffer().getCol(dragTextRegion.getEnd()))
                    ok = true;
            }

            if (ok) {
                display.setDragCaretPos(pos);
                // The point in question might be beyond the end of the line.
                display.setDragCaretCol(absCol);
            } else
                display.setDragCaretPos(null);
            display.repaintChangedLines();
        }
    }

    public void drop(DropTargetDropEvent event) {
        Transferable t = event.getTransferable();
        if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
            acceptFileDrop(event, t);
        else if (t.isDataFlavorSupported(DataFlavor.stringFlavor))
            acceptTextDrop(event, t);
        else
            event.rejectDrop();
    }

    private void acceptFileDrop(DropTargetDropEvent event, Transferable t) {
        event.acceptDrop(DnDConstants.ACTION_LINK);
        try {
            @SuppressWarnings("unchecked")
            List<java.io.File> files =
                (List<java.io.File>) t.getTransferData(DataFlavor.javaFileListFlavor);
            for (java.io.File dropped : files) {
                String path = dropped.getPath();
                Buffer buffer = editor.openFile(File.getInstance(path));
                if (buffer != null) {
                    editor.makeNext(buffer);
                    editor.activate(buffer);
                } else
                    event.rejectDrop();
            }
            event.getDropTargetContext().dropComplete(true);
        }
        catch (Exception e) {
            Log.error(e);
            event.rejectDrop();
        }
        editor.updateDisplay();
    }

    private void acceptTextDrop(DropTargetDropEvent event, Transferable t) {
        final int dropAction = event.getDropAction(); // copy = 1, move = 2
        if (
            dropAction != DnDConstants.ACTION_COPY
                &&
                dropAction != DnDConstants.ACTION_MOVE
        ) {
            // Not copy or move.
            event.rejectDrop();
            return;
        }
        if (!editor.checkReadOnly()) {
            event.rejectDrop();
            return;
        }
        // Copy or move.
        try {
            event.acceptDrop(dropAction);
            final String s =
                (String) t.getTransferData(DataFlavor.stringFlavor);
            final Point point = event.getLocation();
            Position posDrop = display.positionFromPoint(point);
            boolean ok = false;
            if (dragTextRegion == null)
                ok = true;
            else if (posDrop.isBefore(dragTextRegion.getBegin()))
                ok = true;
            else if (posDrop.isAfter(dragTextRegion.getEnd()))
                ok = true;
            else if (posDrop.equals(dragTextRegion.getEnd())) {
                int col = Math.max(display.getColumn(posDrop.getLine(), point.x), 0);
                int absCol = col + display.getShift();
                if (absCol > editor.getBuffer().getCol(dragTextRegion.getEnd()))
                    ok = true;
            }
            if (ok) {
                if (dropAction == DnDConstants.ACTION_COPY) {
                    // Copy.
                    CompoundEdit compoundEdit = editor.beginCompoundEdit();
                    moveCaretToDropPoint(point);
                    editor.paste(s, true);
                    editor.endCompoundEdit(compoundEdit);
                } else if (dropAction == DnDConstants.ACTION_MOVE) {
                    // Move.
                    CompoundEdit compoundEdit = editor.beginCompoundEdit();
                    if (dragTextRegion != null && posDrop.isBefore(dragTextRegion.getBegin())) {
                        editor.deleteRegion();
                        dragTextRegion = null;
                    }
                    moveCaretToDropPoint(point);
                    editor.paste(s, true); // Leave paste selected.
                    Region r = new Region(editor);
                    posDrop = r.getBegin(); // Where the drop actually occurred.
                    if (dragTextRegion != null && posDrop.isAfter(dragTextRegion.getEnd())) {
                        Position savedDot = editor.getDotCopy();
                        Position savedMark = editor.getMark().copy();
                        dragTextRegion.adjustMarker(savedDot);
                        dragTextRegion.adjustMarker(savedMark);
                        editor.addUndo(SimpleEdit.MOVE);
                        editor.setMark(dragTextRegion.getBegin());
                        editor.setDot(dragTextRegion.getEnd());
                        editor.deleteRegion();
                        dragTextRegion = null;
                        editor.addUndo(SimpleEdit.MOVE);
                        editor.setMark(savedMark);
                        editor.setDot(savedDot);
                    }
                    editor.endCompoundEdit(compoundEdit);
                }
                editor.updateDisplay();
            }
            event.getDropTargetContext().dropComplete(true);
            // Make sure destination is current editor after drop.
            editor.ensureActive();
            Editor.setCurrentEditor(editor);
            editor.setFocusToDisplay();
        }
        catch (Exception e) {
            Log.error(e);
            event.rejectDrop();
        }
        inDragText = false;
        dragTextRegion = null;
        display.setDragCaretPos(null);
        editor.setDefaultCursor();
        editor.repaint();
    }

    private void moveCaretToDropPoint(Point point) {
        editor.addUndo(SimpleEdit.MOVE);
        editor.setMark(null);
        display.moveCaretToPoint(point);
        if (editor.getBuffer().getBooleanProperty(Property.RESTRICT_CARET))
            display.moveCaretToDotCol();
    }

    public void dropActionChanged(DropTargetDragEvent event) {
        if (Platform.isPlatformUnix() && dragSourceContext != null)
            dragSourceContext.setCursor(getCursorForAction(event.getDropAction()));
    }

    public void dragGestureRecognized(DragGestureEvent event) {
        if (!Editor.preferences().getBooleanProperty(Property.ENABLE_DRAG_TEXT))
            return;
        if (editor.getMark() != null) {
            Region r = new Region(editor);
            Position pos = display.positionFromPoint(event.getDragOrigin());
            if (!pos.isAfter(r.getBegin()))
                return;
            if (!pos.isBefore(r.getEnd()))
                return;
            dragTextRegion = r;
            Transferable transferable =
                new StringSelection(dragTextRegion.toString());
            isLineRegion = dragTextRegion.isLineRegion();
            inDragText = true;
            int action = event.getDragAction();
            Cursor cursor = Platform.isPlatformUnix() ? getCursorForAction(action) : null;
            dragSource.startDrag(event, cursor, transferable, this);
            dragSourceContext = null;
        }
    }

    public void dragDropEnd(DragSourceDropEvent event) {
        if (dragTextRegion != null && !editor.getBuffer().isReadOnly())
            if (event.getDropAction() == DnDConstants.ACTION_MOVE)
                editor.deleteRegion();
        inDragText = false;
        dragTextRegion = null;
        isLineRegion = false;
        display.setDragCaretPos(null);
        display.repaintChangedLines();
    }

    public void dragEnter(DragSourceDragEvent event) {
        if (Platform.isPlatformUnix()) {
            DragSourceContext dsc = event.getDragSourceContext();
            dsc.setCursor(getCursorForAction(event.getDropAction()));
        }
    }

    public void dragOver(DragSourceDragEvent event) {}

    public void dropActionChanged(DragSourceDragEvent event) {
        if (Platform.isPlatformUnix()) {
            DragSourceContext dsc = event.getDragSourceContext();
            int dropAction = event.getDropAction();
            if (dropAction == DnDConstants.ACTION_COPY)
                dsc.setCursor(getDragCursor(CURSOR_COPY));
            else if (dropAction == DnDConstants.ACTION_MOVE)
                dsc.setCursor(getDragCursor(CURSOR_MOVE));
        }
    }

    public void dragExit(DragSourceEvent event) {
        DragSourceContext dsc = event.getDragSourceContext();
        if (Platform.isPlatformUnix())
            dsc.setCursor(getDragCursor(CURSOR_NO));
        dragSourceContext = dsc;
    }

    // Only used on Unix.
    public static Cursor getCursorForAction(int action) {
        int index;
        if (action == DnDConstants.ACTION_COPY)
            index = CURSOR_COPY;
        else if (action == DnDConstants.ACTION_MOVE)
            index = CURSOR_MOVE;
        else
            index = CURSOR_NO;
        return getDragCursor(index);
    }

    // Only used on Unix.
    private static Cursor getDragCursor(int index) {
        if (cursors[index] != null)
            return cursors[index];
        // Need to create cursor.
        String name, iconName;
        switch (index) {
            case CURSOR_NO:
            default:
                name = "NoDrop";
                iconName = "nodrop";
                break;
            case CURSOR_MOVE:
                name = "MoveDrop";
                iconName = "movedrop";
                break;
            case CURSOR_COPY:
                name = "CopyDrop";
                iconName = "copydrop";
                break;
        }
        Cursor cursor = null;
        try {
            Toolkit toolkit = java.awt.Toolkit.getDefaultToolkit();
            // The size the platform wants: anything else is padded or scaled,
            // and on a high resolution display it is larger than the icons
            // the rest of the interface uses.
            Dimension size = toolkit.getBestCursorSize(32, 32);
            if (size.width > 0 && size.height > 0) {
                ImageIcon icon =
                    Icons.getIconFromFile(iconName, size.width);
                if (icon != null)
                    cursor = toolkit.createCustomCursor(
                        icon.getImage(),
                        new Point(1, 1),
                        name
                    );
            }
        }
        catch (Throwable t) {
            Log.error(t);
        }
        if (cursor == null)
            cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR);
        // Cache the result.
        cursors[index] = cursor;
        return cursor;
    }
}
