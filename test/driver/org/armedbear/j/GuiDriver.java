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
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
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
 * location (the location bar) or focus (the focused component's class).
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
            default -> throw new IllegalArgumentException("can't read " + what);
        };
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
        return "buffer=" + ed.getBuffer() + " mode=" + ed.getMode().getDisplayName() + " line="
                + (ed.getDot() == null ? -1 : ed.getDotLineNumber() + 1) + " col="
                + (ed.getDot() == null ? -1 : ed.getDotCol() + 1) + " focus=" + name(focus()) + " location=["
                + read("location") + "] status=[" + read("status") + "] editors=" + Editor.getEditorList().size()
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
