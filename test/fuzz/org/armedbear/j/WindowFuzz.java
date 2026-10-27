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

import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
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
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import org.armedbear.j.mode.compare.CompareBuffer;
import org.armedbear.j.mode.dir.DirectoryBuffer;
import org.armedbear.j.mode.list.ListOccurrencesBuffer;
import org.armedbear.j.transients.TransientCommands;
import org.armedbear.j.transients.TransientPopup;
import org.armedbear.j.vcs.log.LogBuffer;
import org.armedbear.j.vcs.status.VcsStatusBuffer;
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
    private static int quitsChecked;

    private record Op(String name, Consumer<Editor> run) {}

    // Each line, as the runner keeps only those marked.
    private static void report(String s) {
        for (String line : s.split("\n"))
            System.out.println("FUZZ " + line);
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
        gitFixture(h.resolve("proj"));
        if (org.armedbear.j.util.ProcessRunner.exists("git"))
            multiFixture(h.resolve("multi"));
    }

    // proj as a repository: everything committed, then two files changed,
    // one of them staged, for the status buffer.
    private static void gitFixture(Path proj) throws IOException {
        if (!org.armedbear.j.util.ProcessRunner.exists("git"))
            return;
        git(proj, "init", "-q", "-b", "main");
        git(proj, "add", "-A");
        git(proj, "commit", "-q", "-m", "first");
        history(proj);
        Files.writeString(
            proj.resolve("file1.txt"),
            Files.readString(proj.resolve("file1.txt")).replace("line 1 7\n", "changed\n"));
        Files.writeString(proj.resolve("A.java"), "class A {\n  long x;\n}\n");
        git(proj, "add", "A.java");
        Files.writeString(proj.resolve("new.txt"), "new\n");
    }

    // For the log: commits on main and on a branch merged into it, a tag.
    private static void history(Path repo) throws IOException {
        git(repo, "checkout", "-q", "-b", "topic");
        for (int i = 1; i <= 3; i++) {
            Files.writeString(repo.resolve("topic.txt"), "topic " + i + "\n");
            git(repo, "add", "-A");
            git(repo, "commit", "-q", "-m", "topic " + i);
        }
        git(repo, "checkout", "-q", "main");
        for (int i = 1; i <= 3; i++) {
            Files.writeString(repo.resolve("main.txt"), "main " + i + "\n");
            git(repo, "add", "-A");
            git(repo, "commit", "-q", "-m", "main " + i);
        }
        git(repo, "tag", "v1");
        git(repo, "merge", "-q", "--no-edit", "topic");
    }

    // A project of three repositories, for a log and a status of several:
    // its own, so that none it is in counts, and two in it.
    private static void multiFixture(Path multi) throws IOException {
        Files.createDirectories(multi.resolve(".j-project"));
        Files.writeString(multi.resolve("multi.txt"), "multi\n");
        git(multi, "init", "-q", "-b", "main");
        git(multi, "add", "multi.txt");
        git(multi, "commit", "-q", "-m", "multi first");
        for (String name : new String[] { "one", "two" }) {
            final Path repo = multi.resolve(name);
            Files.createDirectories(repo);
            Files.writeString(repo.resolve(name + ".txt"), name + "\n");
            git(repo, "init", "-q", "-b", "main");
            git(repo, "add", "-A");
            git(repo, "commit", "-q", "-m", name + " first");
            history(repo);
            Files.writeString(repo.resolve(name + ".txt"), name + " changed\n");
        }
    }

    private static void git(Path dir, String... args) throws IOException {
        final List<String> argv = new ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        final ProcessBuilder pb = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment()
                .putAll(
                    Map.of(
                        "GIT_CONFIG_GLOBAL",
                        "/dev/null",
                        "GIT_CONFIG_NOSYSTEM",
                        "1",
                        "GIT_AUTHOR_NAME",
                        "Fuzz",
                        "GIT_AUTHOR_EMAIL",
                        "fuzz@example.com",
                        "GIT_COMMITTER_NAME",
                        "Fuzz",
                        "GIT_COMMITTER_EMAIL",
                        "fuzz@example.com"));
        try {
            pb.start().waitFor();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        if (b instanceof LogBuffer l)
            return (l.getFrameView() != null ? "log" : "qlog") + "(" + l.toString().replaceFirst("^git log: ", "")
                    + ")";
        if (b.getPrimary() instanceof LogBuffer)
            return b.toString();
        if (b instanceof VcsStatusBuffer)
            return "status";
        if (name.contains("Output"))
            return "out";
        return b.getFile() != null ? b.getFile().getName() : name;
    }

    // The log ed shows, its list or one of its panes; null for none.
    private static LogBuffer log(Editor ed) {
        return top(ed.getBuffer()) instanceof LogBuffer l ? l : null;
    }

    // The status b is, or is a part of, as its diff or a side of its comparison; or null.
    private static VcsStatusBuffer status(Buffer b) {
        return top(b) instanceof VcsStatusBuffer s ? s : null;
    }

    // The buffer b is a part of, through a part's part, or b.
    private static Buffer top(Buffer b) {
        while (b != null && b.getPrimary() != null && b.getPrimary() != b)
            b = b.getPrimary();
        return b;
    }

    // A file of proj or of the project of two, for the logs and statuses to start from.
    private static Editor showRepositoryFile(Editor ed) {
        final String[] files = { "proj/file1.txt", "proj/A.java", "multi/one/one.txt", "multi/two/two.txt" };
        return ed.show(Editor.getBuffer(file(files[rnd.nextInt(files.length)])));
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
        }),
        new Op("vcsStatus", ed -> {
            if (!(ed.getBuffer() instanceof VcsStatusBuffer))
                ed = showRepositoryFile(ed);
            command(ed, "vcsStatus", null);
        }),
        new Op("statusVisit", ed -> {
            if (ed.getBuffer() instanceof VcsStatusBuffer)
                command(ed, "vcsStatusVisit", null);
        }),
        new Op("changeKey", ed -> {
            // The change marks of a file with changes.
            if (ed.getBuffer().getFile() == null || ed.getBuffer() instanceof DirectoryBuffer)
                ed = ed.show(Editor.getBuffer(file(rnd.nextBoolean() ? "proj/file1.txt" : "multi/one/one.txt")));
            final String[] commands = { "nextChange", "previousChange", "previewChange", "olderChange", "newerChange" };
            command(ed, commands[rnd.nextInt(commands.length)], null);
        }),
        new Op("vcsLog", ed -> {
            if (log(ed) == null && !(ed.getBuffer() instanceof VcsStatusBuffer))
                ed = showRepositoryFile(ed);
            command(ed, rnd.nextInt(3) == 0 ? "vcsLogQuick" : "vcsLog", null);
        }),
        new Op("vcsLogFile", ed -> {
            if (ed.getBuffer().getFile() == null || ed.getBuffer() instanceof DirectoryBuffer)
                ed = showRepositoryFile(ed);
            command(ed, "vcsLogFile", null);
        }),
        new Op("logLine", ed -> {
            // The caret to a commit, as a click or j and k do.
            if (ed.getBuffer() instanceof LogBuffer l && l.getLineCount() > 0) {
                ed.setDot(l.getLine(rnd.nextInt(l.getLineCount())), 0);
                ed.moveCaretToDotCol();
                command(ed, "down", null);
            }
        }),
        new Op("logKey", ed -> {
            if (log(ed) == null)
                return;
            final String[] commands = {
                "vcsLogVisit",
                "vcsLogVisit",
                "vcsLogNext",
                "vcsLogPrevious",
                "vcsLogNextFile",
                "vcsLogPreviousFile",
                "vcsLogScrollDiff",
                "vcsLogScrollDiffBack",
                "vcsLogToggle",
                "vcsLogToggleAll",
                "vcsLogDiffView",
                "vcsLogToggleTags",
                "vcsLogCollapseRuns",
                "vcsLogCollapseRuns",
                "toggleFold",
                "foldAll",
                "unfoldAll",
                "vcsLogTimeStyle",
                "vcsLogRefsOrder",
                "vcsLogClearFilters",
                "vcsLogRefresh",
                "vcsLogPopup",
                "vcsLogOpenFile",
                "vcsLogRevision",
                "down",
                "up" };
            command(ed, commands[rnd.nextInt(commands.length)], null);
        }),
        new Op("logFilter", ed -> {
            // A filter typed in the field over its chip, or cleared.
            if (log(ed) == null)
                return;
            final String[][] filters = {
                { "branch", "main", "topic", "v1", "nothing" },
                { "author", "Fuzz", "nobody" },
                { "path", "file1.txt", "topic.txt", "one.txt", "missing" },
                { "since", "1 year ago", "tomorrow" },
                { "message", "topic", "main 2", "absent" },
                { "change", "line", "topic 2" },
                { "repository", "one", "two", "proj" } };
            final String[] f = filters[rnd.nextInt(filters.length)];
            command(ed, "vcsLogFilter", f[0]);
            final Editor list = current();
            if (list.getFilterBar() != null)
                list.getFilterBar().acceptField(rnd.nextInt(4) == 0 ? "" : f[1 + rnd.nextInt(f.length - 1)]);
        }),
        new Op("logQuit", ed -> {
            final LogBuffer l = log(ed);
            if (l != null)
                closing(l, () -> command(ed, "vcsLogQuit", null));
        }),
        new Op("statusDiff", ed -> {
            // The diff beside, opened, or closed as q in it does, back to the status.
            final VcsStatusBuffer s = status(ed.getBuffer());
            if (s == null)
                return;
            if (s.getFrameView() != null)
                closing(s, () -> command(ed, "vcsStatusDiff", null), s);
            else
                command(ed, "vcsStatusDiff", null);
        }),
        new Op("statusDiffView", ed -> {
            // =: the diff beside opened, or unified or side by side.
            if (status(ed.getBuffer()) != null)
                command(ed, "vcsStatusDiffView", null);
        }),
        new Op("compareToggle", ed -> {
            // s or w in a side: the scroll lock, or whitespace ignored, in every comparison.
            if (CompareBuffer.of(ed.getBuffer()) != null)
                command(ed, rnd.nextBoolean() ? "compareScrollLock" : "diffIgnoreWhitespace", null);
        }),
        new Op("statusDiffKey", ed -> {
            if (status(ed.getBuffer()) == null || ed.getBuffer() instanceof VcsStatusBuffer)
                return;
            final String[] commands = {
                "vcsStatusVisit",
                "vcsStatusToggle",
                "vcsStatusCycle",
                "vcsStatusNext",
                "vcsStatusPrevious",
                "vcsStatusScrollDiff",
                "vcsStatusScrollDiffBack",
                "vcsStatusRefresh",
                "toggleFold",
                "foldAll",
                "unfoldAll",
                "down",
                "up" };
            command(ed, commands[rnd.nextInt(commands.length)], null);
        }),
        new Op("statusKey", ed -> {
            if (!(ed.getBuffer() instanceof VcsStatusBuffer))
                return;
            final String[] commands = {
                "vcsStatusToggle",
                "vcsStatusCycle",
                "vcsStatusDepth",
                "vcsStatusNext",
                "vcsStatusPrevious",
                "vcsStatusNextSection",
                "vcsStatusPreviousSection",
                "vcsStatusRefresh",
                "toggleFold",
                "foldAll",
                "unfoldAll",
                "down",
                "up",
                "vcsStage",
                "vcsUnstage",
                "vcsStageAll",
                "vcsUnstageAll",
                "vcsCommitPopup",
                "vcsStatusScrollDiff",
                "vcsStatusDiff" };
            final String c = commands[rnd.nextInt(commands.length)];
            command(ed, c, c.equals("vcsStatusDepth") ? String.valueOf(1 + rnd.nextInt(4)) : null);
        }),
        new Op("statusCommitMessage", ed -> {
            // Opened and given up: it is empty, so nothing asks.
            if (!(ed.getBuffer() instanceof VcsStatusBuffer))
                return;
            command(ed, "vcsCommit", null);
            if (current().getBuffer() instanceof org.armedbear.j.vcs.status.CommitMessageBuffer)
                command(current(), "vcsCommitCancel", null);
        }),
        new Op("statusChange", ed -> {
            // A change j didn't make, as an edit elsewhere would be.
            try {
                Files.writeString(Path.of(home, "proj/file2.txt"), "fuzz " + rnd.nextInt(1000) + "\n");
            }
            catch (IOException e) {
                throw new IllegalStateException(e);
            }
            org.armedbear.j.vcs.Repositories.invalidateAll(); // As j's window coming back does.
        }),
        new Op("dirPopup", ed -> {
            if (!(ed.getBuffer() instanceof DirectoryBuffer))
                ed = ed.show(Editor.getBuffer(file("proj")));
            TransientCommands.modePopup(ed);
        }),
        new Op("popupKey", ed -> {
            // Not copy, move, delete or shell: their dialogs are modal.
            final TransientPopup popup = TransientPopup.getOpen();
            if (popup != null)
                popup.dispatchEvent(
                    new KeyEvent(popup,
                            KeyEvent.KEY_TYPED,
                            System.currentTimeMillis(),
                            0,
                            KeyEvent.VK_UNDEFINED,
                            "s.loubftr-abgx".charAt(rnd.nextInt(14))));
        }),
        new Op("popupLimit", ed -> {
            final TransientPopup popup = TransientPopup.getOpen();
            if (popup != null && popup.getTransient().item("l") != null) {
                popup.dispatchEvent(
                    new KeyEvent(popup, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'l'));
                popup.acceptField(rnd.nextBoolean() ? "*.txt" : "");
            }
        }),
        new Op("popupEscape", ed -> {
            final TransientPopup popup = TransientPopup.getOpen();
            if (popup != null)
                popup.dispatchEvent(
                    new KeyEvent(popup,
                            KeyEvent.KEY_PRESSED,
                            System.currentTimeMillis(),
                            0,
                            KeyEvent.VK_ESCAPE,
                            KeyEvent.CHAR_UNDEFINED));
        }),
        new Op("virtualText", ed -> {
            final Buffer b = ed.getBuffer();
            final List<VirtualText> list = new ArrayList<>();
            final int count = b.getLineCount();
            for (int i = 0; i < 1 + rnd.nextInt(4) && count > 0; i++) {
                final Line line = b.getLine(rnd.nextInt(count));
                if (line == null)
                    continue;
                final List<VirtualText.Run> runs = List.of(VirtualText.Run.of("virtual " + i));
                list.add(switch (rnd.nextInt(5)) {
                    case 0 -> VirtualText.eol(line, runs);
                    case 1 -> VirtualText.right(line, runs);
                    case 2 -> VirtualText.above(line, List.of(runs, runs));
                    case 3 -> VirtualText.below(line, List.of(runs));
                    default -> VirtualText.top(List.of(runs));
                });
            }
            b.getVirtualText().set("fuzz", list);
        }),
        new Op("clearVirtualText", ed -> ed.getBuffer().getVirtualText().clear("fuzz")));

    /**
     * Runs close, which closes view's frame view: laid out, it must put
     * back the windows it kept, those of them still open, or leave one.
     */
    private static void closing(Buffer view, Runnable close) {
        closing(view, close, null);
    }

    /**
     * As closing, back to back: one of the windows kept shows it, if none
     * does, as the status after its diff closes.
     */
    private static void closing(Buffer view, Runnable close, Buffer back) {
        final boolean shown = frame().shownView() == view;
        final WindowLayout.Node before = shown ? ((FrameView) view).before() : null;
        final String was = layout();
        close.run();
        if (!shown)
            return;
        quitsChecked++;
        final WindowLayout.Node kept =
                before == null ? null : WindowLayout.keeping(before, b -> Editor.getBufferList().contains(b));
        final List<String> want = new ArrayList<>();
        if (kept != null)
            for (WindowLayout.Window w : WindowLayout.windows(kept))
                want.add(kind(w.buffer()));
        final List<String> got = new ArrayList<>();
        for (int i = 0; i < Editor.getEditorCount(); i++)
            if (!frame().isPanel(Editor.getEditor(i)))
                got.add(kind(Editor.getEditor(i).getBuffer()));
        if (kept != null ? !got.equals(want) && !backIn(kept, back) : got.size() != 1)
            errors.add(
                "closing " + kind(view) + " left " + got + ", not " + (kept != null ? want : "one window")
                        + "; it kept " + before + " from " + was);
    }

    // Whether the windows are those kept but for one showing back, as none of them did.
    private static boolean backIn(WindowLayout.Node kept, Buffer back) {
        final List<Buffer> want = new ArrayList<>();
        for (WindowLayout.Window w : WindowLayout.windows(kept))
            want.add(w.buffer());
        final List<Buffer> got = new ArrayList<>();
        for (int i = 0; i < Editor.getEditorCount(); i++)
            if (!frame().isPanel(Editor.getEditor(i)))
                got.add(Editor.getEditor(i).getBuffer());
        if (back == null || want.contains(back) || got.size() != want.size())
            return false;
        for (int i = 0; i < got.size(); i++) {
            final List<Buffer> w = new ArrayList<>(want);
            w.set(i, back);
            if (got.equals(w))
                return true;
        }
        return false;
    }

    // ----------------------------------------------------------- invariants

    private static int maxWindows;
    private static int panelSteps;
    private static int boundSteps;
    private static int popupSteps;
    private static int logSteps;
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
        if (frame.shownView() != null)
            logSteps++;

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
        // A view's parts, as a log's panes and the status's diff, live and die with it.
        for (Buffer b : Editor.getBufferList()) {
            if (b instanceof ViewPane<?> p && !Editor.getBufferList().contains(p.getOwner()))
                bad.add(b + " outlives " + kind(p.getOwner()));
            for (Buffer child : b.getChildren())
                if (!Editor.getBufferList().contains(child))
                    bad.add(kind(b) + " lost its " + child);
        }
        checkPopup(frame, bad);
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

    // At most one popup, in the popup layer over a live window; none left
    // behind once closed.
    private static void checkPopup(Frame frame, List<String> bad) {
        final JLayeredPane layers = frame.getRootPane().getLayeredPane();
        int shown = 0;
        for (Component c : layers.getComponentsInLayer(JLayeredPane.POPUP_LAYER))
            if (c instanceof TransientPopup)
                shown++;
        final TransientPopup open = TransientPopup.getOpen();
        if (open != null)
            popupSteps++;
        if (open == null) {
            if (shown > 0)
                bad.add(shown + " popups left in the popup layer");
            return;
        }
        if (shown != 1)
            bad.add(shown + " popups in the popup layer, one open");
        final Editor ed = open.getEditor();
        if (!Editor.getEditorList().contains(ed))
            bad.add("popup over a closed window");
        else if (open.getParent() == layers && ed.isShowing()) {
            final Rectangle window = SwingUtilities.convertRectangle(ed.getParent(), ed.getBounds(), layers);
            // An empty one, in a window with no room for text, is in no rectangle.
            if (!open.getBounds().isEmpty() && !window.contains(open.getBounds()))
                bad.add("popup " + open.getBounds() + " outside its window " + window);
        }
    }

    // What each window shows, the panel's marked P:, the current one *.
    private static String layout() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Editor.getEditorCount(); i++) {
            final Editor e = Editor.getEditor(i);
            sb.append(frame().isPanel(e) ? "P:" : "")
                    .append(e.getBuffer() == null ? "none" : kind(e.getBuffer()))
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
            report("    " + (e.getBuffer() == null ? "no buffer" : kind(e.getBuffer())) + " " + e.getBounds());
        }
        report("  model:");
        dumpModel(pane, pane.getMultiSplitLayout().getModel(), "    ");
    }

    // The splits and their weights, each leaf's window.
    private static void dumpModel(EditorPane pane, MultiSplitLayout.Node node, String indent) {
        if (node instanceof MultiSplitLayout.Divider)
            return;
        final String weight = String.format("%.3f", node.getWeight());
        if (node instanceof MultiSplitLayout.Split split) {
            report(indent + (split.isRowLayout() ? "row " : "column ") + weight);
            for (MultiSplitLayout.Node child : split.getChildren())
                dumpModel(pane, child, indent + "  ");
        } else {
            final Component c = pane.getMultiSplitLayout().getComponentForNode(node);
            report(indent + weight + " " + (c instanceof Editor e && e.getBuffer() != null ? kind(e.getBuffer()) : c));
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
        final List<Op> logOps = OPS.stream().filter(o -> o.name().startsWith("log")).toList();
        final List<Op> diffOps = OPS.stream().filter(o -> o.name().startsWith("statusDiff")).toList();
        for (int step = 0; step < steps; step++) {
            // In a log, or a status, more often what it does.
            final List<?>[] focused = new List<?>[1];
            onEdt(() -> {
                final Buffer b = current().getBuffer();
                focused[0] = log(current()) != null ? logOps : status(b) != null ? diffOps : null;
            }, "focused");
            final Op op = focused[0] != null && rnd.nextInt(3) == 0
                    ? (Op) focused[0].get(rnd.nextInt(focused[0].size()))
                    : OPS.get(rnd.nextInt(OPS.size()));
            final int before = errors.size();
            final String[] shown = new String[1];
            onEdt(() -> shown[0] = layout(), "layout");
            final String what = "seed " + seed + " step " + step + " " + op.name() + " on [" + shown[0] + "]";
            trail.add(what);
            if (!onEdt(() -> op.run().accept(current()), what)) {
                report("FAIL " + what);
                break;
            }
            // Mail loads in the background, as do the log's pages and details.
            Thread.sleep(
                op.name().contains("Mail") || op.name().contains("Message") ? 700
                        : op.name().contains("og") || op.name().contains("Diff") ? 250
                        : 60);
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
                    + " windows, the panel on " + panelSteps + " steps, a message window on " + boundSteps
                    + ", a popup on " + popupSteps + ", a frame view on " + logSteps + ", " + quitsChecked
                    + " of its closes checked, " + layouts.size() + " layouts");
        System.exit(errors.isEmpty() ? 0 : 1);
    }
}
