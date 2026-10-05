/*
 * Finders.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import org.armedbear.j.util.Icons;

/**
 * The location bar's finders, by the command that opens each. A finder's
 * key, pressed in another finder, switches to it with the query.
 */
public final class Finders {
    private Finders() {}

    private interface Opener {
        void open(Editor editor, String query);
    }

    private static final Map<String, Opener> FINDERS = Map.of(
        "findFileInProject",
        ProjectCommands::findFileInProject,
        "findAction",
        ProjectCommands::findAction,
        "findTag",
        Finders::findTag,
        "openFile",
        Finders::openFile,
        "recentFiles",
        Finders::recentFiles,
        "switchBuffer",
        Finders::switchBuffer,
        "help",
        Finders::help,
        "insertRegister",
        Finders::insertRegister,
        "findBookmark",
        Finders::findBookmark
    );

    /** Whether command opens a finder. */
    public static boolean isFinder(String command) {
        return FINDERS.containsKey(command);
    }

    /** Opens the finder command names, starting from query. */
    public static void open(String command, Editor editor, String query) {
        Opener opener = FINDERS.get(command);
        if (opener != null)
            opener.open(editor, query);
    }

    /** Puts handler in the location bar with prompt, history and query, and starts it. */
    static void show(
        Editor editor,
        int prompt,
        FinderTextFieldHandler handler,
        String historyName,
        String query
    ) {
        final LocationBar locationBar = editor.getLocationBar();
        if (locationBar == null)
            return;
        locationBar.setLabelText(prompt);
        HistoryTextField textField = locationBar.getTextField();
        textField.setHandler(handler);
        textField.setHistory(new History(historyName, 30));
        textField.setText(query);
        textField.setCaretPosition(query.length());
        editor.setFocusToTextField();
        // Again after a menu has closed; then the list, once the field has the focus.
        SwingUtilities.invokeLater(() -> {
            if (handler.isActive()) {
                editor.setFocusToTextField();
                handler.start();
            }
        });
    }

    static void findTag(Editor editor, String query) {
        HistoryTextField textField = editor.getLocationBarTextField();
        if (textField != null)
            show(
                editor,
                LocationBar.PROMPT_TAG,
                new FindTagFinderTextFieldHandler(editor, textField),
                "findTag.tag",
                query
            );
    }

    static void openFile(Editor editor, String query) {
        LocationBar locationBar = editor.getLocationBar();
        if (locationBar == null)
            return;
        locationBar.update();
        HistoryTextField textField = locationBar.getTextField();
        textField.setText(query);
        textField.setCaretPosition(query.length());
        editor.setFocusToTextField();
        if (textField.getHandler() instanceof FinderTextFieldHandler finder)
            SwingUtilities.invokeLater(finder::start);
    }

    // ----------------------------------------------------------- recent files

    /** Finds a recently visited file. */
    public static void recentFiles(Editor editor) {
        recentFiles(editor, "");
    }

    static void recentFiles(Editor editor, String query) {
        HistoryTextField textField = editor.getLocationBarTextField();
        if (textField == null)
            return;
        Supplier<List<FinderItem>> items = () -> {
            List<FinderItem> list = new ArrayList<>();
            File current = editor.getBuffer().getFile();
            for (RecentFilesEntry entry : RecentFiles.getInstance().getEntries()) {
                if (entry.location == null || entry.name == null || entry.name.isEmpty())
                    continue;
                File f = File.hasRemotePrefix(entry.location)
                    ? File.getInstance(entry.location + "/" + entry.name)
                    : File.getInstance(File.getInstance(entry.location), entry.name);
                if (f == null || f.equals(current))
                    continue;
                String path = f.isRemote() ? f.netPath() : f.canonicalPath();
                list.add(new RecentFileItem(f, path, entry.lineNumber, entry.offs));
            }
            return list;
        };
        show(
            editor,
            LocationBar.PROMPT_RECENT,
            new ListFinderTextFieldHandler(editor, textField, "recentFiles", items),
            "recentFiles.input",
            query
        );
    }

    /** A recent file, opened where it was left. */
    private static final class RecentFileItem implements FinderItem {
        private final File file;
        private final FinderItem shown;
        private final int lineNumber;
        private final int offset;

        RecentFileItem(File file, String path, int lineNumber, int offset) {
            this.file = file;
            shown = new FindFileTextFieldHandler.FileItem(path, FindFileTextFieldHandler.display(path, null), null, 0);
            this.lineNumber = lineNumber;
            this.offset = offset;
        }

        @Override
        public String matchText() {
            return shown.matchText();
        }

        @Override
        public String label() {
            return shown.label();
        }

        @Override
        public int labelOffset() {
            return shown.labelOffset();
        }

        @Override
        public String detail() {
            return shown.detail();
        }

        @Override
        public int detailOffset() {
            return shown.detailOffset();
        }

        @Override
        public Icon icon() {
            return shown.icon();
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {
            Buffer buf = Editor.getBuffer(file);
            if (buf == null) {
                editor.status("File not found");
                return;
            }
            Editor ed = editor;
            if (otherWindow) {
                ed = editor.activateInOtherWindow(buf);
            } else if (buf != editor.getBuffer()) {
                editor.makeNext(buf);
                editor.activate(buf);
            } else {
                return; // Already there: stay where the caret is.
            }
            // A remote buffer may still be loading: it goes there when it's done.
            if (buf instanceof RemoteBuffer remote) {
                remote.setInitialDotPos(lineNumber, offset);
                return;
            }
            Line line = buf.getLine(lineNumber);
            if (line != null)
                ed.moveDotTo(line, Math.min(offset, line.length()));
            else
                ed.moveDotTo(buf.getFirstLine(), 0);
            ed.updateDisplay();
        }
    }

    // ---------------------------------------------------------------- buffers

    /** Switches to an open buffer. */
    public static void switchBuffer(Editor editor) {
        switchBuffer(editor, "");
    }

    static void switchBuffer(Editor editor, String query) {
        HistoryTextField textField = editor.getLocationBarTextField();
        if (textField == null)
            return;
        Supplier<List<FinderItem>> items = () -> {
            List<FinderItem> list = new ArrayList<>();
            Buffer current = editor.getBuffer();
            List<Buffer> buffers = new ArrayList<>();
            for (Buffer buf : Editor.getBufferList()) {
                if (buf != current)
                    buffers.add(buf);
            }
            // Most recently used first.
            buffers.sort(Comparator.comparingLong(Buffer::getLastActivated).reversed());
            for (Buffer buf : buffers)
                list.add(new BufferItem(buf));
            // The current one last, so Enter on nothing goes to the one before.
            list.add(new BufferItem(current));
            return list;
        };
        show(
            editor,
            LocationBar.PROMPT_BUFFER,
            new ListFinderTextFieldHandler(editor, textField, "switchBuffer", items),
            "switchBuffer.input",
            query
        );
    }

    private static final class BufferItem implements FinderItem {
        private final Buffer buffer;
        private final String label;

        BufferItem(Buffer buffer) {
            this.buffer = buffer;
            label = buffer.toString();
        }

        @Override
        public String matchText() {
            return label;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public int labelOffset() {
            return 0;
        }

        @Override
        public String detail() {
            File f = buffer.getFile();
            if (f == null || f.isDirectory())
                return "";
            String path = f.isRemote() ? f.netPath() : f.canonicalPath();
            String dir = FindFileTextFieldHandler.display(path, null);
            int slash = Math.max(dir.lastIndexOf('/'), dir.lastIndexOf(LocalFile.getSeparatorChar()));
            return slash > 0 ? dir.substring(0, slash) : "";
        }

        @Override
        public Icon icon() {
            return buffer.getIcon();
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {
            if (!Editor.getBufferList().contains(buffer))
                return;
            if (otherWindow) {
                editor.activateInOtherWindow(buffer);
            } else if (buffer != editor.getBuffer()) {
                editor.makeNext(buffer);
                editor.switchToBuffer(buffer);
            }
        }
    }

    // ------------------------------------------------------------------- help

    /** Finds a help topic: a page of the manual, a command or a preference. */
    public static void help(Editor editor) {
        help(editor, "");
    }

    static void help(Editor editor, String query) {
        HistoryTextField textField = editor.getLocationBarTextField();
        if (textField == null)
            return;
        show(
            editor,
            LocationBar.PROMPT_HELP,
            new ListFinderTextFieldHandler(editor, textField, "help", Finders::helpTopics),
            "help.input",
            query
        );
    }

    private static final Pattern TITLE =
        Pattern.compile("<title>(?:J User's Guide(?: - )?)?(.*?)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANCHOR = Pattern.compile("<a name=\"([\\w.]+)\">");

    // Read once: the manual doesn't change while j runs.
    private static List<FinderItem> helpTopics;

    private static synchronized List<FinderItem> helpTopics() {
        if (helpTopics == null)
            helpTopics = List.copyOf(readHelpTopics());
        return helpTopics;
    }

    private static List<FinderItem> readHelpTopics() {
        List<FinderItem> pages = new ArrayList<>();
        List<FinderItem> entries = new ArrayList<>();
        File dir = Help.getDocumentationDirectory();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null)
            return pages;
        Arrays.sort(files, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".html"))
                continue;
            String text = read(f);
            Matcher m = TITLE.matcher(text);
            String title = m.find() && !m.group(1).isBlank() ? m.group(1).trim() : "Contents";
            HelpItem page = new HelpItem(title, name, "", "file-markup", name);
            // Contents first, so Enter on nothing opens it as help always has.
            if (name.equals("contents.html"))
                pages.add(0, page);
            else
                pages.add(page);
            if (name.equals("commands.html") || name.equals("preferences.html")) {
                boolean commands = name.equals("commands.html");
                Matcher a = ANCHOR.matcher(text);
                while (a.find()) {
                    String anchor = a.group(1);
                    String note = commands ? CommandTable.getSummary(anchor) : "preference";
                    entries.add(
                        new HelpItem(
                            anchor,
                            name,
                            note == null ? "" : note,
                            commands ? "action" : "file-config",
                            name + "#" + anchor
                        )
                    );
                }
            }
        }
        pages.addAll(entries);
        return pages;
    }

    private static String read(File f) {
        try (BufferedReader in =
            Files.newBufferedReader(java.nio.file.Path.of(f.canonicalPath()), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null)
                sb.append(line).append('\n');
            return sb.toString();
        }
        catch (IOException e) {
            return "";
        }
    }

    private static final class HelpItem implements FinderItem {
        private final String label;
        private final String detail;
        private final String note;
        private final String icon;
        private final String target;

        HelpItem(String label, String detail, String note, String icon, String target) {
            this.label = label;
            this.detail = detail;
            this.note = note;
            this.icon = icon;
            this.target = target;
        }

        @Override
        public String matchText() {
            return label;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public int labelOffset() {
            return 0;
        }

        @Override
        public String detail() {
            return detail;
        }

        @Override
        public String note() {
            return note;
        }

        @Override
        public Icon icon() {
            return Icons.getIconFromFile(icon);
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {
            Help.help(target);
        }
    }

    // -------------------------------------------------------------- registers

    /** Inserts a register, picked from a list of them and what they hold. */
    public static void insertRegister(Editor editor) {
        insertRegister(editor, "");
    }

    static void insertRegister(Editor editor, String query) {
        HistoryTextField textField = editor.getLocationBarTextField();
        if (textField == null || !editor.checkReadOnly())
            return;
        Supplier<List<FinderItem>> items = () -> {
            List<FinderItem> list = new ArrayList<>();
            File dir = Directories.getRegistersDirectory();
            File[] files = dir == null ? null : dir.listFiles();
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    if (f.isFile())
                        list.add(new RegisterItem(f.getName()));
                }
            }
            return list;
        };
        show(
            editor,
            LocationBar.PROMPT_REGISTER,
            new ListFinderTextFieldHandler(editor, textField, "insertRegister", items),
            "insertRegister.input",
            query
        );
    }

    private static final class RegisterItem implements FinderItem {
        private final String name;
        private final String text;

        RegisterItem(String name) {
            this.name = name;
            String s = Registers.getText(name, 1);
            text = s == null ? "" : s.strip();
        }

        @Override
        public String matchText() {
            return name + " " + text;
        }

        @Override
        public String label() {
            return name;
        }

        @Override
        public int labelOffset() {
            return 0;
        }

        @Override
        public String note() {
            return text;
        }

        @Override
        public Icon icon() {
            return Icons.getIconFromFile("paste");
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {
            Registers.insertRegister(name, editor);
        }
    }

    // -------------------------------------------------------------- bookmarks

    /** Goes to a bookmark, picked from a list of them and where they are. */
    public static void findBookmark(Editor editor) {
        findBookmark(editor, "");
    }

    static void findBookmark(Editor editor, String query) {
        HistoryTextField textField = editor.getLocationBarTextField();
        if (textField == null)
            return;
        Supplier<List<FinderItem>> items = () -> {
            List<FinderItem> list = new ArrayList<>();
            String names = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
            for (char c : names.toCharArray()) {
                Marker m = Editor.getBookmark(c);
                if (m != null)
                    list.add(new BookmarkItem(c, m));
            }
            return list;
        };
        show(
            editor,
            LocationBar.PROMPT_BOOKMARK,
            new ListFinderTextFieldHandler(editor, textField, "findBookmark", items),
            "findBookmark.input",
            query
        );
    }

    private static final class BookmarkItem implements FinderItem {
        private final Marker marker;
        private final String label;
        private final String detail;
        private final String note;

        BookmarkItem(char name, Marker marker) {
            this.marker = marker;
            label = String.valueOf(name);
            File f = marker.getFile();
            Buffer buf = marker.getBuffer();
            String where = f != null ? f.getName() : buf != null ? buf.toString() : "";
            detail = where + ":" + (marker.getLineNumber() + 1);
            Line line = marker.getLine();
            note = line != null && line.getText() != null ? line.getText().strip() : "";
        }

        @Override
        public String matchText() {
            return label + " " + detail + " " + note;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public int labelOffset() {
            return 0;
        }

        @Override
        public String detail() {
            return detail;
        }

        @Override
        public int detailOffset() {
            return label.length() + 1;
        }

        @Override
        public String note() {
            return note;
        }

        @Override
        public Icon icon() {
            return Icons.getIconFromFile("leaf");
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {
            editor.recordJump();
            marker.gotoMarker(editor);
        }
    }
}
