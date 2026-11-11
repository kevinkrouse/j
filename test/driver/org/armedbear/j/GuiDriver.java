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
import java.awt.Dialog;
import java.awt.KeyboardFocusManager;
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
import javax.swing.SwingUtilities;

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
 * click X Y                              a real mouse click at X Y in the focused window
 * drag X1 Y1 X2 Y2                       a real mouse drag between points in the focused window
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
 * text (the buffer's), line (the caret's line, the text of it),
 * location (the location bar), focus (the focused component's class),
 * title (j's title bar text), menuopen (the length of the open menu
 * selection path), framepos (the frame's screen position, x,y),
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
            }
            Thread.sleep(100);
        }
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
            case "exec" -> onEdt(() -> Editor.currentEditor().executeCommand(rest));
            case "execlater" -> SwingUtilities.invokeLater(() -> Editor.currentEditor().executeCommand(rest));
            case "shot" -> shot(out.resolve(rest + ".png"), false);
            case "shotwin" -> shot(out.resolve(rest + ".png"), true);
            case "log" -> System.out.println("LOG " + rest + (rest.isEmpty() ? "" : ": ") + onEdt(GuiDriver::state));
            case "text" -> {
                final String text = onEdt(GuiDriver::bufferText);
                text.lines().limit(200).forEach(l -> System.out.println("TEXT " + l));
            }
            case "expect" -> expect(rest, true);
            case "expect-not" -> expect(rest, false);
            case "click" -> click(rest);
            case "drag" -> drag(rest);
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
            case "mode" -> ed.getMode().getDisplayName();
            case "text" -> bufferText();
            case "line" -> ed.getDotLine() == null ? "" : ed.getDotLine().getText();
            case "location" -> ed.getLocationBarTextField() == null ? "" : ed.getLocationBarTextField().getText();
            case "focus" -> name(focus());
            case "title" -> {
                final TitleBar tb = ed.getFrame().getTitleBar();
                yield tb == null ? "" : tb.getTitleText();
            }
            case "menuopen" -> String.valueOf(
                    javax.swing.MenuSelectionManager.defaultManager().getSelectedPath().length);
            case "framepos" -> {
                final java.awt.Point p = ed.getFrame().getLocationOnScreen();
                yield p.x + "," + p.y;
            }
            case "framesize" -> {
                final java.awt.Dimension d = ed.getFrame().getSize();
                yield d.width + "x" + d.height;
            }
            case "cursor" -> lastCursor;
            case "wm-managed" -> String.valueOf(X11WmHandoff.wmManages(ed.getFrame()));
            case "maximized" -> {
                final java.awt.Frame f = ed.getFrame();
                yield String.valueOf((f.getExtendedState() & java.awt.Frame.MAXIMIZED_BOTH) != 0);
            }
            default -> throw new IllegalArgumentException("can't read " + what);
        };
    }

    // A real mouse click at x y in the focused window, via the screen robot, so
    // the event travels the same path a user's click does.
    private static void click(String spec) throws Exception {
        final String[] xy = spec.split("\\s+");
        final int x = Integer.parseInt(xy[0]);
        final int y = Integer.parseInt(xy[1]);
        final Window w = onEdt(() -> Editor.currentEditor().getFrame());
        final java.awt.Point loc = w.getLocationOnScreen();
        final Robot robot = new Robot();
        robot.mouseMove(loc.x + x, loc.y + y);
        Thread.sleep(80);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(60);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(120);
    }

    // A real mouse drag from x1 y1 to x2 y2 in the focused window.
    private static void drag(String spec) throws Exception {
        final String[] xy = spec.split("\\s+");
        final int x1 = Integer.parseInt(xy[0]);
        final int y1 = Integer.parseInt(xy[1]);
        final int x2 = Integer.parseInt(xy[2]);
        final int y2 = Integer.parseInt(xy[3]);
        final Window w = onEdt(() -> Editor.currentEditor().getFrame());
        final java.awt.Point loc = w.getLocationOnScreen();
        final Robot robot = new Robot();
        robot.mouseMove(loc.x + x1, loc.y + y1);
        Thread.sleep(80);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        for (int i = 1; i <= 10; i++) {
            robot.mouseMove(loc.x + x1 + (x2 - x1) * i / 10, loc.y + y1 + (y2 - y1) * i / 10);
            Thread.sleep(20);
        }
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(120);
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
                System.out.println("TITLECHILD " + c.getBounds() + " "
                        + c.getClass().getSimpleName() + (c.isVisible() ? "" : " HIDDEN"));
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
            return c.getClass().getSimpleName() + ":" + (cur == null
                    ? java.awt.Cursor.getDefaultCursor().getType()
                    : cur.getType());
        });
        lastCursor = info;
        System.out.println("CURSOR " + x + " " + y + " " + info);
    }

    // Its lines, not Buffer.getText, which a buffer that redraws itself, as the status does, may leave stale.
    private static String bufferText() {
        final StringBuilder sb = new StringBuilder();
        for (Line l = Editor.currentEditor().getBuffer().getFirstLine(); l != null; l = l.next())
            sb.append(l.getText()).append('\n');
        return sb.toString();
    }

    private static String state() {
        final Editor ed = Editor.currentEditor();
        final java.awt.Point fp = ed.getFrame().getLocationOnScreen();
        return "buffer=" + ed.getBuffer() + " mode=" + ed.getMode().getDisplayName() + " line="
                + (ed.getDot() == null ? -1 : ed.getDotLineNumber() + 1) + " col="
                + (ed.getDot() == null ? -1 : ed.getDotCol() + 1) + " focus=" + name(focus()) + " location=["
                + read("location") + "] status=[" + read("status") + "] frame=" + fp.x + "," + fp.y
                + " size=" + ed.getFrame().getWidth() + "x" + ed.getFrame().getHeight()
                + " editors=" + Editor.getEditorList().size()
                + " frames=" + Editor.getFrameCount();
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
}
