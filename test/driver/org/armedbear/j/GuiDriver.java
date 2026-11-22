/*
 * GuiDriver.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

/**
 * Drives a running j from a script, for checking it with no one at the
 * keyboard: under Xvfb, by an agent or as a smoke test.
 *
 * <p>Run by {@code bb drive}, which gives it a display and an empty home.
 * Arguments: the script, the directory for pictures, then j's arguments.
 *
 * <p>A script has a step a line; # starts a comment:
 *
 * <pre>
 * key [ctrl] [shift] [alt] [meta] NAME   a key, NAME a KeyEvent VK_ name: ENTER, P, F1
 * type TEXT                              TEXT typed, a key a character
 * exec COMMAND [ARGS]                    a command run, as Alt X runs it
 * execlater COMMAND [ARGS]               the same, not waited for: one that opens a dialog
 * hover X Y                              the mouse moved to X Y in the window's text area
 * click [double] [right] [ctrl] [shift] TARGET      a real click; TARGET: X Y, line TEXT, text TEXT, node TEXT, button NAME
 * press TARGET / move TARGET / release   a drag in steps
 * drag TARGET -> TARGET                  press, move, release; X1 Y1 X2 Y2 also works
 * menuitem LABEL                          a real click on an open popup item
 * movewin X Y                            move the focused window to X Y, to give a corner drag room to grow
 * sizewin W H                            resize the focused window, so a test controls the geometry a drag starts from
 * hovercursor X Y                         the pointer moved to X Y; prints CURSOR X Y <component:type>
 * wmprobe                                prints whether the X11 WM handoff bridge is available and the WM manages the window
 * titlebutton NAME                        a real click on a title-bar button: minimize, maximize, restore, close
 * titlepoint                             the title bar's bounds printed: TITLEPOINT x y w h
 * titlechildren                          the title bar's children printed: TITLECHILD x y w h CLASS
 * menuclick [N]                          a real click on the Nth embedded menu, 0 is File
 * wait MS                                a pause
 * timeout MS                             how long expect waits; 5000 to begin with
 * shot NAME                              the frame pictured, NAME.png
 * shotwin NAME                           the focused window or open dialog pictured
 * log [LABEL]                            the state printed: buffer, mode, caret, focus, status
 * text                                   the buffer printed, its first 200 lines
 * expect WHAT TEXT                       waits until WHAT contains TEXT, or fails
 * expect-not WHAT TEXT                   waits until WHAT does not contain TEXT, or fails
 * </pre>
 *
 * <p>WHAT is status (the status bar's message), buffer (its name), mode,
 * input (simple or the input handler's class name),
 * text (the buffer's), text@NAME (the buffer whose name's last word is NAME,
 * shown or not), line (the caret's line, the text of it),
 * location (the location bar), focus (the focused component's class),
 * title (j's title bar text), menuopen (the length of the open menu
 * selection path), popup (an item a line, LABEL [enabled] or LABEL [disabled]
 * and why), sidebar (the panels showing: top=Buffers|Changes|none tree=BOOL),
 * selection (the focused tree rows), framepos (the frame's screen position, x,y),
 * framesize (the frame's size, WxH), cursor (the component:cursor the last
 * hovercursor found), wm-managed (whether the WM advertises
 * _NET_WM_MOVERESIZE) or maximized (whether the frame is maximized).
 *
 * <p>Output lines start STEP, LOG, TEXT, SHOT, PASS, FAIL or ERROR (an
 * error logged or thrown). The exit status is 0 with no FAIL or ERROR.
 */
public final class GuiDriver {
    private GuiDriver() {}

    private static final List<String> errors = new ArrayList<>();
    private static int failures;
    private static long timeout = 5000;

    public static void main(String[] args) throws Exception {
        final Path script = Path.of(args[0]);
        final Path out = Path.of(args[1]);
        Files.createDirectories(out);
        final String[] jargs = Arrays.copyOfRange(args, 2, args.length);
        Log.errorListener = s -> {
            // A headless editor's ensureColumnVisible complains with no graphics.
            if (!s.contains("g2d is null"))
                error("logged: " + s.split("\n")[0]);
        };
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> error("threw on " + t.getName() + ": " + e));
        new Thread(() -> {
            try {
                Class.forName("Main").getMethod("main", String[].class).invoke(null, (Object) jargs);
            }
            catch (ReflectiveOperationException e) {
                e.printStackTrace();
            }
        }).start();
        for (int i = 0; i < 120 && !started(); i++)
            Thread.sleep(250);
        if (!started()) {
            System.out.println("FAIL j did not start");
            System.exit(2);
        }
        // Startup's own reads settle.
        Thread.sleep(1500);
        for (String line : Files.readAllLines(script)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            System.out.println("STEP " + line);
            final String[] w = line.split("\\s+", 2);
            final String rest = w.length > 1 ? w[1] : "";
            try {
                step(w[0], rest, out);
            }
            catch (Exception e) {
                // After a Hung step a modal dialog still takes events: the next steps can picture or close it.
                failures++;
                System.out.println("FAIL " + line + ": " + (e instanceof Hung ? e.getMessage() : e));
                // A failed step in a drag lets the button go.
                letGo();
            }
            Thread.sleep(100);
        }
        if (pressed != null) {
            failures++;
            System.out.println("FAIL the script ended with the mouse pressed");
            letGo();
        }
        // Match normal shutdown's placement save without saving edited fixture buffers.
        onEdt(() -> {
            Editor.getSessionProperties().saveWindowPlacement();
            Editor.getSessionProperties().save();
        });
        synchronized (errors) {
            for (String e : errors)
                System.out.println("ERROR " + e);
        }
        System.out.println(
            failures + errors.size() == 0 ? "OK" : "FAILED: " + failures + " failed, " + errors.size() + " errors");
        System.exit(failures + errors.size() == 0 ? 0 : 1);
    }

    private static void error(String s) {
        synchronized (errors) {
            errors.add(s);
        }
    }

    private static boolean started() {
        final Editor ed = Editor.currentEditor();
        return ed != null && ed.getFrame() != null && ed.getFrame().isShowing();
    }

    private static void step(String verb, String rest, Path out) throws Exception {
        switch (verb) {
            case "wait" -> Thread.sleep(Long.parseLong(rest));
            case "timeout" -> timeout = Long.parseLong(rest);
            case "key" -> key(rest);
            case "hover" -> hover(rest);
            case "type" -> {
                for (char c : rest.toCharArray())
                    type(c);
            }
            case "exec" -> {
                checkCommand(rest);
                onEdt(() -> Editor.currentEditor().executeCommand(rest));
            }
            case "execlater" -> {
                checkCommand(rest);
                SwingUtilities.invokeLater(() -> Editor.currentEditor().executeCommand(rest));
            }
            case "shot" -> shot(out.resolve(rest + ".png"), false);
            case "shotwin" -> shot(out.resolve(rest + ".png"), true);
            case "shotpopup" -> shotPopup(out.resolve(rest + ".png"));
            case "popupbounds" -> System.out.println("POPUP " + onEdt(() -> {
                final StringBuilder sb = new StringBuilder();
                for (Window w : Window.getWindows())
                    if (w.isShowing() && isPopupWindow(w))
                        sb.append(w.getLocationOnScreen()).append(w.getSize()).append(" ");
                return sb.length() == 0 ? "none" : sb.toString();
            }));
            case "log" -> System.out.println("LOG " + rest + (rest.isEmpty() ? "" : ": ") + onEdt(GuiDriver::state));
            case "text" -> {
                final String text = onEdt(GuiDriver::bufferText);
                text.lines().limit(200).forEach(l -> System.out.println("TEXT " + l));
            }
            case "expect" -> expect(rest, true);
            case "expect-not" -> expect(rest, false);
            case "click" -> click(rest);
            case "drag" -> drag(rest);
            case "press" -> press(rest);
            case "move" -> move(rest);
            case "release" -> release();
            case "menuitem" -> menuItem(rest);
            case "movewin" -> moveWindow(rest);
            case "sizewin" -> sizeWindow(rest);
            case "wmprobe" -> wmProbe();
            case "titlebutton" -> titleButton(rest);
            case "titlepoint" -> titlePoint();
            case "titlechildren" -> titleChildren();
            case "menuclick" -> menuClick(rest);
            case "hovercursor" -> hoverCursor(rest);
            default -> throw new IllegalArgumentException("no such step");
        }
    }

    // A typo should fail a step instead of opening an unknown-command modal dialog.
    // The commands and properties exec may run: none that runs a shell,
    // evaluates code or writes outside the fixture. Add one here to use it.
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
        "bob",
        "diff",
        "diffGotoFile",
        "dropBookmark",
        "finish",
        "incrementalFind",
        "insertString",
        "jumpToLine",
        "openFile",
        "otherWindow",
        "save",
        "selectRight",
        "sidebarListChanges",
        "svnCommit",
        "svnDiff",
        "toggleMermaid",
        "vcsBlame",
        "vcsChangelistMove",
        "vcsChangelistNew",
        "vcsCommit",
        "vcsCommitAbort",
        "vcsCommitFiles",
        "vcsCommitList",
        "vcsCommitPicked",
        "vcsConflictAllTheirs",
        "vcsConflictResolved",
        "vcsDiscard",
        "vcsLog",
        "vcsPickHunk",
        "vcsResolveTheirs",
        "vcsStatus",
        "vcsStatusDepth",
        "vcsStatusDiff",
        "vcsStatusDiffView",
        "vcsStatusUnpickAll");
    private static final java.util.Set<String> PROPERTIES = java.util.Set.of("projectRoot");

    private static void checkCommand(String command) {
        final String name = command.split("\\s+", 2)[0];
        final int eq = name.indexOf('=');
        if (eq >= 0 ? !PROPERTIES.contains(name.substring(0, eq)) : !COMMANDS.contains(name))
            throw new IllegalArgumentException("exec doesn't run " + name + ": see GuiDriver.COMMANDS");
        if (eq < 0 && CommandTable.getCommand(name) == null)
            throw new IllegalArgumentException("unknown command: " + name);
    }

    // Polls until what contains (or not) the text, or the timeout passes.
    private static void expect(String rest, boolean contains) throws Exception {
        final String[] w = rest.split("\\s+", 2);
        final String text = w.length > 1 ? w[1] : "";
        final long end = System.currentTimeMillis() + timeout;
        String got;
        while (true) {
            got = onEdt(() -> read(w[0]));
            if (got.contains(text) == contains) {
                System.out.println("PASS " + (contains ? "" : "not ") + w[0] + " " + text);
                return;
            }
            if (System.currentTimeMillis() > end)
                break;
            Thread.sleep(100);
        }
        failures++;
        System.out.println("FAIL " + w[0] + (contains ? " lacks " : " has ") + "[" + text + "]: [" + got + "]");
    }

    private static String read(String what) {
        final Editor ed = Editor.currentEditor();
        return switch (what) {
            case "status" -> Objects.toString(ed.getFrame().getStatusBar().getText(), "");
            case "buffer" -> String.valueOf(ed.getBuffer());
            case "input" -> ed.getInputHandler() == null ? "simple" : ed.getInputHandler().getClass().getSimpleName();
            case "mode" -> ed.getMode().getDisplayName();
            case "text" -> bufferText();
            case String w when w.startsWith("text@") -> namedBufferText(w.substring(5));
            case "line" -> ed.getDotLine() == null ? "" : ed.getDotLine().getText();
            case "location" -> ed.getLocationBarTextField() == null ? "" : ed.getLocationBarTextField().getText();
            case "focus" -> name(focus());
            case "title" -> {
                final TitleBar tb = ed.getFrame().getTitleBar();
                yield tb == null ? "" : tb.getTitleText();
            }
            case "menuopen" ->
                    String.valueOf(javax.swing.MenuSelectionManager.defaultManager().getSelectedPath().length);
            case "framepos" -> {
                final java.awt.Point p = ed.getFrame().getLocationOnScreen();
                yield p.x + "," + p.y;
            }
            case "framesize" -> {
                final java.awt.Dimension d = ed.getFrame().getSize();
                yield d.width + "x" + d.height;
            }
            case "popup" -> popupText();
            case "sidebar" -> sidebarState();
            case "selection" -> treeSelection();
            case "cursor" -> lastCursor;
            case "wm-managed" -> String.valueOf(X11WmHandoff.wmManages(ed.getFrame()));
            case "maximized" -> {
                final java.awt.Frame f = ed.getFrame();
                yield String.valueOf((f.getExtendedState() & java.awt.Frame.MAXIMIZED_BOTH) != 0);
            }
            default -> throw new IllegalArgumentException("can't read " + what);
        };
    }

    // Targets are resolved on the EDT without moving the caret or making a selection.
    // Coordinates remain frame-relative for the window decoration scripts.
    private static Point target(String spec) throws Exception {
        return onEdt(() -> targetOnEdt(spec));
    }

    private static Point targetOnEdt(String spec) {
        final Editor editor = Editor.currentEditor();
        final Frame frame = editor.getFrame();
        if (spec.matches("-?\\d+\\s+-?\\d+")) {
            final String[] xy = spec.split("\\s+");
            final Point p = frame.getLocationOnScreen();
            p.translate(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
            return p;
        }
        final String[] parts = spec.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isEmpty())
            throw new IllegalArgumentException("target needs a kind and text: " + spec);
        final String text = parts[1];
        switch (parts[0]) {
            case "mark":
                // The Nth, from 1, of the current window's scroll bar marks, in buffer order, at its middle.
                final VerticalScrollBar bar = editor.getVerticalScrollBar();
                final ScrollMarks marks = ScrollMarks.of(editor, null);
                final int n = Integer.parseInt(text.trim()) - 1;
                if (bar == null || marks == null || n < 0 || n >= marks.marks.size())
                    break;
                final Rectangle r = marks.bounds(
                    marks.marks.get(n),
                    bar.trackBounds(),
                    editor.getDisplay().getHeight(),
                    !editor.isScrollBarLeft());
                if (r == null)
                    break;
                final Point mp = bar.getLocationOnScreen();
                mp.translate(r.x + r.width / 2, r.y + r.height / 2);
                return mp;
            case "button":
                return frame.getStatusBar().buttonScreenPoint(text);
            case "node":
                for (JTree tree : trees(frame))
                    for (int row = 0; row < tree.getRowCount(); row++) {
                        final TreePath path = tree.getPathForRow(row);
                        final String shown = tree.convertValueToText(
                            path.getLastPathComponent(),
                            false,
                            tree.isExpanded(row),
                            tree.getModel().isLeaf(path.getLastPathComponent()),
                            row,
                            false);
                        if (!shown.contains(text))
                            continue;
                        final Rectangle bounds = tree.getRowBounds(row);
                        if (bounds == null || !tree.getVisibleRect().contains(bounds.x, bounds.y + bounds.height / 2))
                            continue;
                        final Point p = tree.getLocationOnScreen();
                        p.translate(bounds.x + Math.min(bounds.width / 2, 35), bounds.y + bounds.height / 2);
                        return p;
                    }
                break;
            case "line", "text":
                // Prefer the current editor, then the other visible editors in its frame.
                final List<Editor> editors = new ArrayList<>();
                editors.add(editor);
                for (Editor other : frame.getEditors())
                    if (other != editor)
                        editors.add(other);
                for (Editor ed : editors) {
                    final Display display = ed.getDisplay();
                    if (!display.isShowing())
                        continue;
                    for (Line line = display.getTopLine(); line != null; line = line.nextVisible()) {
                        final int y = display.getLineTop(line);
                        if (y < 0)
                            break;
                        final int found = line.getText().indexOf(text);
                        if (found < 0)
                            continue;
                        final int offset = parts[0].equals("line") ? 0 : found;
                        // Use the display's mapping, so tabs, fonts and horizontal scroll agree with a click.
                        final int middle = y + Display.getCharHeight() / 2;
                        for (int x = 0; x < display.getWidth(); x++) {
                            if (x < Display.getGutterWidth(ed.getBuffer()))
                                continue;
                            final Position pos = display.positionFromPoint(x, middle);
                            if (pos.getLine() == line && pos.getOffset() == offset) {
                                final Point p = display.getLocationOnScreen();
                                p.translate(x + 1, middle);
                                return p;
                            }
                        }
                        throw new IllegalStateException("text is outside the display: " + spec);
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("unknown target: " + spec);
        }
        throw new IllegalStateException("no visible target: " + spec);
    }

    private static List<JTree> trees(Container parent) {
        final List<JTree> found = new ArrayList<>();
        for (Component c : parent.getComponents()) {
            if (!c.isShowing())
                continue;
            if (c instanceof JTree tree)
                found.add(tree);
            else if (c instanceof Container container)
                found.addAll(trees(container));
        }
        return found;
    }

    // What the sidebar shows: its upper view, and whether its lower tree is showing.
    private static String sidebarState() {
        final Sidebar sidebar = Editor.currentEditor().getFrame().getSidebar();
        if (sidebar == null || !sidebar.isShowing())
            return "hidden";
        final Component top = sidebar.getTopView();
        final String shown =
                top == null || !top.isShowing() ? "none" : top == sidebar.getBufferList() ? "Buffers" : "Changes";
        return "top=" + shown + " tree=" + (sidebar.getBottomComponent() instanceof Component c && c.isShowing());
    }

    private static String treeSelection() {
        final Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (!(focus instanceof JTree tree))
            return "";
        final TreePath[] paths = tree.getSelectionPaths();
        if (paths == null)
            return "";
        final List<String> rows = new ArrayList<>();
        for (TreePath path : paths) {
            final int row = tree.getRowForPath(path);
            rows.add(
                tree.convertValueToText(
                    path.getLastPathComponent(),
                    true,
                    tree.isExpanded(path),
                    tree.getModel().isLeaf(path.getLastPathComponent()),
                    row,
                    true));
        }
        return String.join("\n", rows);
    }

    private static List<JMenuItem> popupItems() {
        final List<JMenuItem> items = new ArrayList<>();
        for (MenuElement element : MenuSelectionManager.defaultManager().getSelectedPath())
            if (element instanceof JPopupMenu menu)
                for (Component c : menu.getComponents())
                    if (c instanceof JMenuItem item && item.isShowing())
                        items.add(item);
        return items;
    }

    private static String popupText() {
        final List<String> text = new ArrayList<>();
        for (JMenuItem item : popupItems())
            text.add(
                item.getText() + (item.isEnabled() ? " [enabled]" : " [disabled]")
                        + (item.getToolTipText() == null ? "" : " — " + item.getToolTipText()));
        return String.join("\n", text);
    }

    private static void menuItem(String label) throws Exception {
        final Point p = onEdt(() -> {
            for (JMenuItem item : popupItems())
                if (item.getText().equals(label)) {
                    if (!item.isEnabled())
                        throw new IllegalStateException("disabled menu item: " + label);
                    final Point point = item.getLocationOnScreen();
                    point.translate(item.getWidth() / 2, item.getHeight() / 2);
                    return point;
                }
            throw new IllegalStateException("no open menu item: " + label);
        });
        mouseClick(p, InputEvent.BUTTON1_DOWN_MASK, 0);
    }

    private static void click(String spec) throws Exception {
        int modifiers = 0;
        int clicks = 1;
        int button = InputEvent.BUTTON1_DOWN_MASK;
        while (true) {
            final String[] word = spec.split("\\s+", 2);
            if (word.length < 2)
                break;
            switch (word[0]) {
                case "double" -> clicks = 2;
                case "right" -> button = InputEvent.BUTTON3_DOWN_MASK;
                case "ctrl" -> modifiers |= InputEvent.CTRL_DOWN_MASK;
                case "shift" -> modifiers |= InputEvent.SHIFT_DOWN_MASK;
                default -> {
                    mouseClick(target(spec), button, modifiers, clicks);
                    return;
                }
            }
            spec = word[1];
        }
        mouseClick(target(spec), button, modifiers, clicks);
    }

    private static void mouseClick(Point p, int button, int modifiers) throws Exception {
        mouseClick(p, button, modifiers, 1);
    }

    private static void mouseClick(Point p, int button, int modifiers, int clicks) throws Exception {
        final Robot robot = new Robot();
        robot.mouseMove(p.x, p.y);
        Thread.sleep(80);
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0)
            robot.keyPress(KeyEvent.VK_CONTROL);
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0)
            robot.keyPress(KeyEvent.VK_SHIFT);
        try {
            for (int i = 0; i < clicks; i++) {
                robot.mousePress(button);
                Thread.sleep(60);
                robot.mouseRelease(button);
                if (i + 1 < clicks)
                    Thread.sleep(60);
            }
        }
        finally {
            if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0)
                robot.keyRelease(KeyEvent.VK_SHIFT);
            if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0)
                robot.keyRelease(KeyEvent.VK_CONTROL);
        }
        Thread.sleep(120);
    }

    private static Point pressed;

    private static void press(String spec) throws Exception {
        if (pressed != null)
            throw new IllegalStateException("mouse already pressed");
        pressed = target(spec);
        final Robot robot = new Robot();
        robot.mouseMove(pressed.x, pressed.y);
        Thread.sleep(80);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(80);
    }

    private static void move(String spec) throws Exception {
        final Point to = target(spec);
        final Point from = java.awt.MouseInfo.getPointerInfo().getLocation();
        final Robot robot = new Robot();
        for (int i = 1; i <= 12; i++) {
            robot.mouseMove(from.x + (to.x - from.x) * i / 12, from.y + (to.y - from.y) * i / 12);
            Thread.sleep(25);
        }
        Thread.sleep(100);
    }

    private static void letGo() {
        if (pressed == null)
            return;
        try {
            new Robot().mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
        catch (java.awt.AWTException e) {
            System.out.println("FAIL couldn't release the mouse: " + e);
        }
        pressed = null;
    }

    private static void release() throws Exception {
        if (pressed == null)
            throw new IllegalStateException("mouse is not pressed");
        new Robot().mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        pressed = null;
        Thread.sleep(150);
    }

    // Semantic endpoints are separated by ->; the old four-coordinate form still works.
    private static void drag(String spec) throws Exception {
        String[] endpoints = spec.split(" -> ", 2);
        if (endpoints.length == 1 && spec.matches("-?\\d+\\s+-?\\d+\\s+-?\\d+\\s+-?\\d+")) {
            final String[] xy = spec.split("\\s+");
            endpoints = new String[] { xy[0] + " " + xy[1], xy[2] + " " + xy[3] };
        }
        if (endpoints.length != 2)
            throw new IllegalArgumentException("drag takes TARGET -> TARGET, or X1 Y1 X2 Y2");
        press(endpoints[0]);
        try {
            move(endpoints[1]);
        }
        finally {
            release();
        }
    }

    // A real mouse click on a named title-bar button: minimize, maximize,
    // restore or close.
    private static void titleButton(String name) throws Exception {
        final java.awt.Point p = onEdt(() -> {
            final TitleBar tb = Editor.currentEditor().getFrame().getTitleBar();
            if (tb == null)
                throw new IllegalStateException("no title bar to click");
            return tb.buttonScreenPoint(name);
        });
        final Robot robot = new Robot();
        robot.mouseMove(p.x, p.y);
        Thread.sleep(80);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(60);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(150);
    }

    // Prints the title bar's bounds in the window, so a script can pick points
    // without hard-coding them: TITLEPOINT x y width height.
    private static void titlePoint() throws Exception {
        final java.awt.Rectangle b = onEdt(() -> {
            final TitleBar tb = Editor.currentEditor().getFrame().getTitleBar();
            if (tb == null)
                throw new IllegalStateException("no title bar");
            return tb.getBounds();
        });
        System.out.println("TITLEPOINT " + b.x + " " + b.y + " " + b.width + " " + b.height);
    }

    // The title bar's children in bar coordinates, to see where the buttons
    // actually land: TITLECHILD x y w h <class>.
    private static void titleChildren() throws Exception {
        onEdt(() -> {
            final TitleBar tb = Editor.currentEditor().getFrame().getTitleBar();
            if (tb == null)
                throw new IllegalStateException("no title bar");
            for (int i = 0; i < tb.getComponentCount(); i++) {
                final Component c = tb.getComponent(i);
                System.out.println(
                    "TITLECHILD " + c.getBounds() + " " + c.getClass().getSimpleName()
                            + (c.isVisible() ? "" : " HIDDEN"));
            }
            return null;
        });
    }

    // A real mouse click on the Nth menu of the embedded menu bar (0 is File),
    // near the menu's left edge, so the script need not know where it landed.
    private static void menuClick(String spec) throws Exception {
        final int index = spec.isEmpty() ? 0 : Integer.parseInt(spec.trim());
        final java.awt.Point p = onEdt(() -> {
            final TitleBar tb = Editor.currentEditor().getFrame().getTitleBar();
            if (tb == null)
                throw new IllegalStateException("no title bar");
            final javax.swing.JMenuBar menus = tb.getMenus();
            if (menus == null || index >= menus.getMenuCount())
                throw new IllegalStateException("no menu at " + index);
            final java.awt.Component menu = menus.getMenu(index);
            if (menu == null)
                throw new IllegalStateException("no menu at " + index);
            return menu.getLocationOnScreen();
        });
        final Robot robot = new Robot();
        robot.mouseMove(p.x + 10, p.y + 10);
        Thread.sleep(80);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(60);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(150);
    }

    // The component:cursor the last hovercursor step found, for `expect cursor`.
    private static String lastCursor = "none";

    // Reports whether the X11 WM handoff bridge resolved and the WM advertises
    // _NET_WM_MOVERESIZE: the two gates that decide whether a drag is delegated.
    private static void wmProbe() throws Exception {
        final Window w = onEdt(() -> Editor.currentEditor().getFrame());
        System.out.println("WMPROBE available=" + X11WmHandoff.available());
        System.out.println("WMPROBE toolkit=" + java.awt.Toolkit.getDefaultToolkit().getClass().getName());
        System.out.println("WMPROBE wmManages=" + X11WmHandoff.wmManages(w));
    }

    // Resizes the focused window, so a test controls the geometry a corner drag
    // starts from: j sizes a new window to the screen, which varies with the
    // display.
    private static void sizeWindow(String spec) throws Exception {
        final String[] wh = spec.split("\\s+");
        final int w = Integer.parseInt(wh[0]);
        final int h = Integer.parseInt(wh[1]);
        onEdt(() -> {
            final Window win = Editor.currentEditor().getFrame();
            win.setSize(w, h);
            return null;
        });
        Thread.sleep(150);
    }

    // Moves the focused window to x y in its own coordinates, so a test can give
    // a corner drag room to grow: a window parked at 0,0 cannot be dragged left.
    private static void moveWindow(String spec) throws Exception {
        final String[] xy = spec.split("\\s+");
        final int x = Integer.parseInt(xy[0]);
        final int y = Integer.parseInt(xy[1]);
        onEdt(() -> {
            final Window w = Editor.currentEditor().getFrame();
            w.setLocation(x, y);
            return null;
        });
        Thread.sleep(150);
    }

    // Moves the real pointer to x y in the focused window and prints the cursor
    // type the window manager shows there: the grab-ability signal for a resize
    // edge. CURSOR x y type (0 default, 6 NW, 7 NE, 4 SW, 5 SE, 10 W, 11 E,
    // 8 N, 9 S).
    private static void hoverCursor(String spec) throws Exception {
        final String[] xy = spec.split("\\s+");
        final int x = Integer.parseInt(xy[0]);
        final int y = Integer.parseInt(xy[1]);
        final Window w = onEdt(() -> Editor.currentEditor().getFrame());
        final java.awt.Point loc = w.getLocationOnScreen();
        final Robot robot = new Robot();
        robot.mouseMove(loc.x + x, loc.y + y);
        Thread.sleep(150);
        final int cx = x;
        final int cy = y;
        final String info = onEdt(() -> {
            final Component c = Editor.currentEditor().getFrame().getRootPane().findComponentAt(cx, cy);
            if (c == null)
                return "none";
            final java.awt.Cursor cur = c.getCursor();
            return c.getClass().getSimpleName() + ":"
                    + (cur == null ? java.awt.Cursor.getDefaultCursor().getType() : cur.getType());
        });
        lastCursor = info;
        System.out.println("CURSOR " + x + " " + y + " " + info);
    }

    // Its lines, not Buffer.getText, which a buffer that redraws itself, as the status does, may leave stale.
    // The text of the buffer whose name's last word is name, as "repo" in
    // "Git status: repo": what another window or a hidden buffer shows.
    private static String namedBufferText(String name) {
        for (Buffer b : Editor.getBufferList()) {
            final String title = String.valueOf(b);
            if (title.equals(name) || title.endsWith(" " + name)) {
                final StringBuilder sb = new StringBuilder();
                for (Line l = b.getFirstLine(); l != null; l = l.next())
                    sb.append(l.getText()).append('\n');
                return sb.toString();
            }
        }
        return "";
    }

    private static String bufferText() {
        final StringBuilder sb = new StringBuilder();
        for (Line l = Editor.currentEditor().getBuffer().getFirstLine(); l != null; l = l.next())
            sb.append(l.getText()).append('\n');
        return sb.toString();
    }

    private static String state() {
        final Editor ed = Editor.currentEditor();
        final java.awt.Point fp = ed.getFrame().getLocationOnScreen();
        return "buffer=" + ed.getBuffer() + " mode=" + ed.getMode().getDisplayName() + " input=" + read("input")
                + " line=" + (ed.getDot() == null ? -1 : ed.getDotLineNumber() + 1) + " col="
                + (ed.getDot() == null ? -1 : ed.getDotCol() + 1) + " focus=" + name(focus()) + " location=["
                + read("location") + "] status=[" + read("status") + "] frame=" + fp.x + "," + fp.y + " size="
                + ed.getFrame().getWidth() + "x" + ed.getFrame().getHeight() + " editors="
                + Editor.getEditorList().size() + " frames=" + Editor.getFrameCount();
    }

    private static <T> T onEdt(Supplier<T> s) throws Exception {
        final FutureTask<T> task = new FutureTask<>(s::get);
        SwingUtilities.invokeLater(task);
        try {
            return task.get(30, TimeUnit.SECONDS);
        }
        catch (TimeoutException e) {
            throw new Hung();
        }
        catch (ExecutionException e) {
            throw e.getCause() instanceof Exception x ? x : e;
        }
    }

    private static void onEdt(Runnable r) throws Exception {
        onEdt(() -> {
            r.run();
            return null;
        });
    }

    /** The event thread busy for too long: a modal dialog open, most likely. */
    private static final class Hung extends RuntimeException {
        Hung() {
            super("not done in 30s: a modal dialog? execlater opens one, shotwin pictures it");
        }
    }

    private static String name(Component c) {
        return c == null ? "null" : c.getClass().getSimpleName();
    }

    private static Component focus() {
        final Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (c != null)
            return c;
        final Editor ed = Editor.currentEditor();
        final Component f = ed.getFrame().getFocusedComponent();
        return f != null ? f : ed.getDisplay();
    }

    // The mouse moved to x y, in the current window's text area.
    private static void hover(String spec) throws Exception {
        final String[] xy = spec.split("\\s+");
        final int x = Integer.parseInt(xy[0]);
        final int y = Integer.parseInt(xy[1]);
        onEdt(() -> {
            final Component display = Editor.currentEditor().getDisplay();
            display.dispatchEvent(
                new MouseEvent(display, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, y, 0, false));
        });
    }

    private static void key(String spec) throws Exception {
        final String[] parts = spec.split("\\s+");
        int m = 0;
        for (int i = 0; i < parts.length - 1; i++)
            m |= switch (parts[i].toLowerCase()) {
                case "ctrl" -> InputEvent.CTRL_DOWN_MASK;
                case "shift" -> InputEvent.SHIFT_DOWN_MASK;
                case "alt" -> InputEvent.ALT_DOWN_MASK;
                case "meta" -> InputEvent.META_DOWN_MASK;
                default -> throw new IllegalArgumentException("no such modifier " + parts[i]);
            };
        final int code = KeyEvent.class.getField("VK_" + parts[parts.length - 1].toUpperCase()).getInt(null);
        final int mods = m;
        onEdt(() -> {
            final long t = System.currentTimeMillis();
            focus().dispatchEvent(new KeyEvent(focus(), KeyEvent.KEY_PRESSED, t, mods, code, KeyEvent.CHAR_UNDEFINED));
            final boolean shift = mods == InputEvent.SHIFT_DOWN_MASK;
            final char ch = switch (code) {
                case KeyEvent.VK_ENTER -> '\n';
                case KeyEvent.VK_TAB -> '\t';
                case KeyEvent.VK_ESCAPE -> 27;
                case KeyEvent.VK_BACK_SPACE -> 8;
                case KeyEvent.VK_SPACE -> ' ';
                default -> code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z
                        ? (char) (shift ? code : Character.toLowerCase(code))
                        : code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9 && !shift ? (char) code
                        : KeyEvent.CHAR_UNDEFINED;
            };
            // A plain key types its character too, as a keyboard's does.
            if (ch != KeyEvent.CHAR_UNDEFINED && (mods == 0 || shift))
                focus().dispatchEvent(new KeyEvent(focus(), KeyEvent.KEY_TYPED, t, mods, KeyEvent.VK_UNDEFINED, ch));
            focus().dispatchEvent(new KeyEvent(focus(), KeyEvent.KEY_RELEASED, t, mods, code, KeyEvent.CHAR_UNDEFINED));
        });
    }

    private static void type(char ch) throws Exception {
        onEdt(() -> {
            final long t = System.currentTimeMillis();
            final int code = KeyEvent.getExtendedKeyCodeForChar(ch);
            final int m = Character.isUpperCase(ch) ? InputEvent.SHIFT_DOWN_MASK : 0;
            focus().dispatchEvent(new KeyEvent(focus(), KeyEvent.KEY_PRESSED, t, m, code, ch));
            focus().dispatchEvent(new KeyEvent(focus(), KeyEvent.KEY_TYPED, t, m, KeyEvent.VK_UNDEFINED, ch));
            focus().dispatchEvent(new KeyEvent(focus(), KeyEvent.KEY_RELEASED, t, m, code, ch));
        });
        Thread.sleep(20);
    }

    // The frame, or the focused window or a dialog showing, painted to file.
    private static void shot(Path file, boolean window) throws Exception {
        final BufferedImage img = onEdt(() -> {
            Component c = Editor.currentEditor().getFrame().getRootPane();
            if (window) {
                Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
                if (w == null)
                    for (Window x : Window.getWindows())
                        if (x.isShowing() && x instanceof Dialog)
                            w = x;
                if (w == null)
                    return null;
                c = w;
            }
            final BufferedImage i = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
            final var g = i.createGraphics();
            c.paint(g);
            g.dispose();
            return i;
        });
        if (img == null)
            throw new IllegalStateException("no window to picture");
        ImageIO.write(img, "png", file.toFile());
        System.out.println("SHOT " + file);
    }

    // Picture the open popup menu. A JPopupMenu is heavyweight (its own
    // Popup$HeavyWeightWindow) when it cannot fit inside the window, and
    // lightweight (painted in the layered pane) when it can -- which one
    // depends on where it opens and how big it is, so handle both: prefer
    // the heavyweight window, else the topmost showing JPopupMenu in the
    // menu selection path.
    private static void shotPopup(Path file) throws Exception {
        final BufferedImage img = onEdt(() -> {
            Window found = null;
            for (Window w : Window.getWindows())
                if (w.isShowing() && isPopupWindow(w))
                    found = w;
            if (found != null)
                return paintWindow(found);
            // Lightweight: the last JPopupMenu in the open menu path is
            // the topmost (a submenu open over a menu gives two).
            JPopupMenu top = null;
            for (MenuElement e : MenuSelectionManager.defaultManager().getSelectedPath())
                if (e instanceof JPopupMenu p && p.isShowing())
                    top = p;
            return top == null ? null : paintWindow(top);
        });
        if (img == null)
            throw new IllegalStateException("no popup window to picture");
        ImageIO.write(img, "png", file.toFile());
        System.out.println("SHOT " + file);
    }

    // A heavyweight popup window: Swing's own Popup$HeavyWeightWindow,
    // the class a JPopupMenu gets when it cannot stay lightweight. The
    // class is package-private, so match by name. Excludes every Frame --
    // including another editor's frame, which the old "not the current
    // frame, not a Dialog" filter wrongly admitted, letting a screenshot
    // "succeed" by capturing a whole editor window with no popup open.
    private static boolean isPopupWindow(Window w) {
        return w.getClass().getName().startsWith("javax.swing.Popup");
    }

    private static BufferedImage paintWindow(Component c) {
        final BufferedImage i = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        final var g = i.createGraphics();
        c.paint(g);
        g.dispose();
        return i;
    }
}
