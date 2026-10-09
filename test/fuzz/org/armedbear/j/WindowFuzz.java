/*
 * WindowFuzz.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.armedbear.j.mode.dir.DirectoryBuffer;
import org.armedbear.j.mode.list.ListOccurrencesBuffer;
import org.armedbear.j.vim.VimInputHandler;
import org.jdesktop.swingx.MultiSplitLayout;

/**
 * Random window, buffer and split operations in a running j, checking after
 * each that the windows still make sense: the layout and the window list
 * agree, every window shows a live buffer, its caret on one of its lines,
 * and has room, none overlap, the
 * panel holds one transient buffer along the bottom, mail's message windows
 * are bound to their mailboxes', nothing threw, logged an error or hung.
 *
 * <p>Run by {@code bb fuzz-windows}, which gives it a display (Xvfb) and an
 * empty home directory, where it writes its own files first.
 *
 * <p>Arguments: seed, steps. A failure prints the steps that led to it;
 * the same seed repeats them.
 */
public final class WindowFuzz {
    private static final List<String> errors = new ArrayList<>();
    private static final List<String> trail = new ArrayList<>();
    private static String home;
    private static Random rnd;

    private record Op(String name, Consumer<Editor> run) {}

    private static void report(String s) {
        System.out.println("FUZZ " + s);
    }

    // ------------------------------------------------------------ fixtures

    // Preferences, files, a directory and a mailbox for the operations to use.
    private static void writeFixtures() throws IOException {
        final Path h = Path.of(home);
        Files.createDirectories(h.resolve(".config/j"));
        Files.writeString(
            h.resolve(".config/j/prefs"),
            "editMode=vim\nenableExperimentalFeatures=true\nenableMail=true\n");
        Files.createDirectories(h.resolve("proj/sub"));
        for (int i = 1; i <= 4; i++) {
            final StringBuilder sb = new StringBuilder();
            for (int line = 1; line <= 200; line++)
                sb.append("line ").append(i).append(' ').append(line).append('\n');
            Files.writeString(h.resolve("proj/file" + i + ".txt"), sb);
        }
        Files.writeString(h.resolve("proj/A.java"), "class A {\n  int x;\n}\n");
        Files.writeString(h.resolve("proj/sub/inner.txt"), "sub\n");
        final StringBuilder mbox = new StringBuilder();
        final String[] from = { "Alice", "Bob", "Carol", "Dave", "Erin" };
        for (int i = 0; i < from.length; i++) {
            final String address = from[i].toLowerCase() + "@example.com";
            mbox.append("From ").append(address).append(" Mon Oct 0").append(i + 1).append(" 17:12:00 2026\n");
            mbox.append("From: ").append(from[i]).append(" Example <").append(address).append(">\n");
            mbox.append("To: Someone <someone@example.com>\n");
            mbox.append("Subject: Message ").append(i + 1).append('\n');
            mbox.append("Date: Mon, 0").append(i + 1).append(" Oct 2026 17:12:00 +0000\n");
            mbox.append("Message-ID: <fuzz").append(i).append("@example.com>\n\n");
            mbox.append("Dummy message ").append(i + 1).append(".\n\n");
        }
        Files.writeString(h.resolve("test.mbox"), mbox, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- helpers

    private static Editor current() {
        return Editor.currentEditor();
    }

    private static Frame frame() {
        return Editor.getCurrentFrame();
    }

    private static File file(String relative) {
        return File.getInstance(home + "/" + relative);
    }

    private static Editor randomEditor() {
        return Editor.getEditor(rnd.nextInt(Editor.getEditorCount()));
    }

    // A short name for what a window shows, for the trail.
    private static String kind(Buffer b) {
        final String name = b.getClass().getSimpleName();
        if (name.contains("Mailbox"))
            return "list";
        if (name.contains("Message"))
            return "msg";
        if (name.contains("Web"))
            return "help";
        if (name.contains("Directory"))
            return "dir";
        if (name.contains("ListOccurrences"))
            return "occ";
        if (name.contains("Output"))
            return "out";
        return b.getFile() != null ? b.getFile().getName() : name;
    }

    private static void ex(Editor ed, String line) {
        if (ed.getInputHandler() instanceof VimInputHandler handler)
            handler.exEntered(ed, line);
    }

    private static void command(Editor ed, String name, String argument) {
        try {
            if (argument == null)
                ed.execute(name);
            else
                ed.execute(name, argument);
        }
        catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void collectDividers(MultiSplitLayout.Node node, List<MultiSplitLayout.Divider> out) {
        if (node instanceof MultiSplitLayout.Divider divider)
            out.add(divider);
        if (node instanceof MultiSplitLayout.Split split) {
            for (MultiSplitLayout.Node child : split.getChildren())
                collectDividers(child, out);
        }
    }

    // A real drag, as the mouse does it: press on a divider, move, let go.
    private static void dragDivider() {
        final EditorPane pane = frame().getEditorPane();
        final List<MultiSplitLayout.Divider> dividers = new ArrayList<>();
        collectDividers(pane.getMultiSplitLayout().getModel(), dividers);
        if (dividers.isEmpty())
            return;
        final MultiSplitLayout.Divider divider = dividers.get(rnd.nextInt(dividers.size()));
        final Rectangle r = divider.getBounds();
        final int x = r.x + r.width / 2;
        final int y = r.y + r.height / 2;
        final int dx = divider.isVertical() ? rnd.nextInt(301) - 150 : 0;
        final int dy = divider.isVertical() ? 0 : rnd.nextInt(301) - 150;
        final long t = System.currentTimeMillis();
        pane.dispatchEvent(
            new MouseEvent(pane,
                    MouseEvent.MOUSE_PRESSED,
                    t,
                    InputEvent.BUTTON1_DOWN_MASK,
                    x,
                    y,
                    1,
                    false,
                    MouseEvent.BUTTON1));
        pane.dispatchEvent(
            new MouseEvent(pane,
                    MouseEvent.MOUSE_DRAGGED,
                    t + 10,
                    InputEvent.BUTTON1_DOWN_MASK,
                    x + dx,
                    y + dy,
                    0,
                    false,
                    MouseEvent.NOBUTTON));
        pane.dispatchEvent(
            new MouseEvent(pane, MouseEvent.MOUSE_RELEASED, t + 20, 0, x + dx, y + dy, 1, false, MouseEvent.BUTTON1));
        pane.validate();
    }

    // ----------------------------------------------------------- operations

    private static final List<Op> OPS = List.of(
        new Op("split", ed -> frame().splitWindow()),
        new Op("vsplit", ed -> frame().vsplitWindow()),
        new Op("closeWindow", ed -> frame().closeEditor(ed)),
        new Op("closeRandomWindow", ed -> frame().closeEditor(randomEditor())),
        new Op(":only", ed -> ex(ed, "only")),
        new Op(":close", ed -> {
            if (frame().getEditorCount() > 1)
                ex(ed, "close");
        }),
        new Op(":sp", ed -> ex(ed, "sp")),
        new Op(":vs", ed -> ex(ed, "vs")),
        new Op("unsplitWindow", ed -> frame().unsplitWindow()),
        new Op("focusRandom", ed -> {
            final Editor e = randomEditor();
            Editor.setCurrentEditor(e);
            e.setFocusToDisplay();
        }),
        new Op("nextWindow", WindowCommands::nextWindow),
        new Op("otherWindow", WindowCommands::otherWindow),
        new Op("balance", ed -> frame().balanceWindows()),
        new Op("dragDivider", ed -> dragDivider()),
        new Op("resizeFrame", ed -> {
            frame().setSize(400 + rnd.nextInt(800), 300 + rnd.nextInt(600));
            frame().validate();
        }),
        new Op("openFile", ed -> ed.show(Editor.getBuffer(file("proj/file" + (1 + rnd.nextInt(4)) + ".txt")))),
        new Op("openJava", ed -> ed.show(Editor.getBuffer(file("proj/A.java")))),
        new Op("openDir", ed -> ed.show(Editor.getBuffer(file(rnd.nextBoolean() ? "proj" : "proj/sub")))),
        new Op("openFileOtherWindow",
                ed -> ed.activateInOtherWindow(Editor.getBuffer(file("proj/file" + (1 + rnd.nextInt(4)) + ".txt")))),
        new Op("openFileInOtherWindow", ed -> {
            FileCommands.openFileInOtherWindow(ed);
            final HistoryTextField field = current().getLocationBarTextField();
            if (field != null && field.getHandler() != null) {
                field.setText(home + "/proj/file2.txt");
                field.getHandler().enter();
            }
        }),
        new Op("help", ed -> Help.help(rnd.nextBoolean() ? "commands.html" : "splitWindow")),
        new Op("describeBindings", ed -> Help.describeBindings()),
        new Op("output", ed -> ed.activateInOtherWindow(OutputBuffer.getOutputBuffer("some output\nmore\n"))),
        new Op("listOccurrences", ed -> {
            if (ed.getBuffer().isTransient() || ed.getBuffer() instanceof DirectoryBuffer)
                return;
            ed.setLastSearch(new Search("line", false, false));
            ListOccurrencesBuffer.listOccurrences(ed);
        }),
        new Op("occurrenceEnter", ed -> {
            if (ed.getBuffer() instanceof ListOccurrencesBuffer list) {
                final Line line = list.getFirstLine();
                if (line != null && line.next() != null)
                    ed.setDot(line.next(), 0);
                list.findOccurrenceAtDot(ed, rnd.nextBoolean());
            }
        }),
        new Op("escape", Editor::escape),
        new Op("closePanel", BufferCommands::closePanel),
        new Op("closePanelTransient", ed -> BufferCommands.closePanel(ed, "transient")),
        new Op("killBuffer", ed -> {
            if (Editor.getBufferList().size() > 2 && !ed.getBuffer().isModified())
                BufferCommands.killBuffer(ed);
        }),
        new Op("nextBuffer", BufferCommands::nextBuffer),
        new Op("prevBuffer", BufferCommands::prevBuffer),
        new Op("alternate", ed -> BufferCommands.prevBuffer(ed, "alternate")),
        new Op("openMailbox", ed -> command(ed, "openMailbox", home + "/test.mbox")),
        new Op("readMessage", ed -> {
            if (kind(ed.getBuffer()).equals("list")) {
                Line line = ed.getBuffer().getFirstLine();
                for (int i = rnd.nextInt(4); i > 0 && line.next() != null; i--)
                    line = line.next();
                ed.setDot(line, 0);
                command(ed, "mailboxReadMessageOtherWindow", null);
            }
        }),
        new Op("messageIndex", ed -> {
            if (kind(ed.getBuffer()).equals("msg"))
                command(ed, "messageIndex", null);
        }),
        new Op("switchToRandomBuffer", ed -> {
            final List<Buffer> buffers = new ArrayList<>();
            for (Buffer b : Editor.getBufferList())
                buffers.add(b);
            ed.show(buffers.get(rnd.nextInt(buffers.size())));
        }),
        new Op("recordJump", Editor::recordJump),
        new Op("jumpBack", ed -> JumpList.jumpBack()),
        new Op("jumpForward", ed -> JumpList.jumpForward()),
        new Op("closeTransient", ed -> {
            if (ed.getBuffer().isTransient())
                ed.closeTransient();
        }));

    // ----------------------------------------------------------- invariants

    private static int maxWindows;
    private static int panelSteps;
    private static int boundSteps;
    private static final Set<String> layouts = new TreeSet<>();

    private static void check(String after) {
        final Frame frame = frame();
        final EditorPane pane = frame.getEditorPane();
        maxWindows = Math.max(maxWindows, Editor.getEditorCount());
        if (frame.getPanelEditor() != null)
            panelSteps++;
        for (int i = 0; i < Editor.getEditorCount(); i++) {
            if (frame.getBoundWindow(Editor.getEditor(i)) != null) {
                boundSteps++;
                break;
            }
        }
        layouts.add(layout().replace("*", ""));

        final List<String> bad = new ArrayList<>();
        if (!pane.checkEditorLeafCount())
            bad.add("window count != layout leaf count");
        if (Editor.getEditorCount() != frame.getEditorCount())
            bad.add("global windows " + Editor.getEditorCount() + " != frame's " + frame.getEditorCount());
        if (!frame.contains(current()))
            bad.add("current window not in the frame");
        int panels = 0;
        final List<Rectangle> seen = new ArrayList<>();
        for (int i = 0; i < Editor.getEditorCount(); i++) {
            final Editor e = Editor.getEditor(i);
            if (!Editor.getBufferList().contains(e.getBuffer()))
                bad.add("window shows a dead buffer: " + kind(e.getBuffer()));
            if (e.getDot() != null && !e.getBuffer().contains(e.getDotLine()))
                bad.add("caret of " + kind(e.getBuffer()) + " on a line of another buffer");
            if (e.getParent() != pane)
                bad.add("window not in the pane");
            if (frame.isPanel(e)) {
                panels++;
                if (!e.getBuffer().isTransient())
                    bad.add("panel shows non-transient " + kind(e.getBuffer()));
                if (e.getBuffer().isPaired())
                    bad.add("panel shows paired " + kind(e.getBuffer()));
            }
            final Editor bound = frame.getBoundWindow(e);
            if (bound != null && frame.getPrimaryWindow(bound) != e)
                bad.add("bound window mapping inconsistent");
            final Rectangle r = e.getBounds();
            if (pane.getWidth() > 50 && pane.getHeight() > 50 && (r.width <= 0 || r.height <= 0))
                bad.add("window " + kind(e.getBuffer()) + " has size " + r.width + "x" + r.height);
            for (Rectangle o : seen) {
                if (r.intersects(o))
                    bad.add("windows overlap: " + r + " " + o);
            }
            seen.add(r);
        }
        if (panels > 1)
            bad.add("more than one panel");
        final Editor panel = frame.getPanelEditor();
        if (panel != null) {
            for (int i = 0; i < Editor.getEditorCount(); i++) {
                final Editor e = Editor.getEditor(i);
                if (e != panel && e.getY() > panel.getY())
                    bad.add("a window below the panel");
            }
        }
        for (String b : bad)
            errors.add(after + ": " + b);
    }

    // What each window shows, the panel's marked P:, the current one *.
    private static String layout() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Editor.getEditorCount(); i++) {
            final Editor e = Editor.getEditor(i);
            sb.append(frame().isPanel(e) ? "P:" : "")
                    .append(kind(e.getBuffer()))
                    .append(e == current() ? "*" : "")
                    .append(' ');
        }
        return sb.toString().trim();
    }

    private static void dumpLayout() {
        final EditorPane pane = frame().getEditorPane();
        report(
            "  pane " + pane.getWidth() + "x" + pane.getHeight() + ", dividers floating: "
                    + pane.getMultiSplitLayout().getFloatingDividers());
        for (int i = 0; i < Editor.getEditorCount(); i++) {
            final Editor e = Editor.getEditor(i);
            report("    " + kind(e.getBuffer()) + " " + e.getBounds());
        }
    }

    // ------------------------------------------------------------- running

    // Runs r on the event thread; false if it did not finish, which is a hang.
    private static boolean onEdt(Runnable r, String what) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final Throwable[] thrown = new Throwable[1];
        SwingUtilities.invokeLater(() -> {
            try {
                r.run();
            }
            catch (Throwable t) {
                thrown[0] = t;
            }
            finally {
                done.countDown();
            }
        });
        if (!done.await(8, TimeUnit.SECONDS)) {
            errors.add(what + ": the event thread hung");
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                if (!e.getKey().getName().startsWith("AWT-EventQueue"))
                    continue;
                for (StackTraceElement element : e.getValue()) {
                    if (element.getClassName().startsWith("org.armedbear") || element.getClassName().contains("Dialog"))
                        errors.add("    at " + element);
                }
            }
            return false;
        }
        if (thrown[0] != null) {
            final StringWriter sw = new StringWriter();
            thrown[0].printStackTrace(new PrintWriter(sw));
            final String[] lines = sw.toString().split("\n");
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, lines.length); i++)
                sb.append("\n      ").append(lines[i].trim());
            errors.add(what + ": threw" + sb);
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        home = System.getProperty("user.home");
        final long seed = Long.parseLong(args[0]);
        final int steps = Integer.parseInt(args[1]);
        rnd = new Random(seed);
        writeFixtures();
        // A headless editor's ensureColumnVisible complains with no graphics; not a failure here.
        Log.errorListener = s -> {
            if (!s.contains("g2d is null"))
                errors.add("logged: " + s.split("\n")[0]);
        };
        new Thread(() -> {
            try {
                Class.forName("Main")
                        .getMethod("main", String[].class)
                        .invoke(
                            null,
                            (Object) new String[] {
                                "--force-new-instance",
                                "--no-server",
                                "--no-session",
                                "--no-restore",
                                home + "/proj/file1.txt" });
            }
            catch (ReflectiveOperationException e) {
                e.printStackTrace();
            }
        }).start();
        for (int i = 0; i < 60 && (current() == null || frame() == null || !frame().isShowing()); i++)
            Thread.sleep(500);
        Thread.sleep(1500);
        if (current() == null) {
            report("seed " + seed + ": j did not start");
            System.exit(2);
        }
        for (int step = 0; step < steps; step++) {
            final Op op = OPS.get(rnd.nextInt(OPS.size()));
            final int before = errors.size();
            final String[] shown = new String[1];
            onEdt(() -> shown[0] = layout(), "layout");
            final String what = "seed " + seed + " step " + step + " " + op.name() + " on [" + shown[0] + "]";
            trail.add(what);
            if (!onEdt(() -> op.run().accept(current()), what)) {
                report("FAIL " + what);
                break;
            }
            // Mail loads in the background.
            Thread.sleep(op.name().contains("Mail") || op.name().contains("Message") ? 700 : 60);
            onEdt(() -> check(what), "check");
            if (errors.size() > before) {
                report("FAIL " + what);
                for (int i = before; i < errors.size(); i++)
                    report("  " + errors.get(i));
                onEdt(() -> report("  layout after: " + layout()), "layout");
                onEdt(WindowFuzz::dumpLayout, "dump");
                report("  last steps:");
                for (int i = Math.max(0, trail.size() - 8); i < trail.size(); i++)
                    report("    " + trail.get(i));
                break;
            }
        }
        report(
            "seed " + seed + " " + (errors.isEmpty() ? "OK" : "FAILED") + "; " + steps + " steps, up to " + maxWindows
                    + " windows, the panel on " + panelSteps + " steps, a message window on " + boundSteps + ", "
                    + layouts.size() + " layouts");
        System.exit(errors.isEmpty() ? 0 : 1);
    }
}
