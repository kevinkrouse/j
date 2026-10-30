/*
 * FindFileTextFieldHandler.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.armedbear.j.Constants.*;

import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Keys;
import org.armedbear.j.util.Utilities;

/**
 * The file finder (Ctrl P, Ctrl O, and the location bar's own). An empty
 * field lists the open buffers and recent files. A name finds them and the
 * project's files, those under the current buffer's directory first, and
 * the entries of the directory a relative path names. A path starting with
 * "./", "../" or "~", or an absolute one, lists that directory's entries
 * alone. Tab puts the
 * selected entry in the field; Enter opens it, or goes into a directory.
 * "name:N" goes to line N. A URL, an alias, an encoding, or Shift Enter
 * opens what was typed, as the location bar always has: new files too.
 */
public final class FindFileTextFieldHandler extends FinderTextFieldHandler {
    private static final Pattern LINE_SUFFIX = Pattern.compile("^(.*?):(\\d+)$");

    // Open buffers and recent files outrank other matches of about the same
    // quality, and those under the buffer's directory others in the project.
    private static final int BOOST_OPEN = 30;
    private static final int BOOST_RECENT = 15;
    private static final int BOOST_NEAR = 10;

    private final File dir; // The buffer's directory; null if it has no local one.
    private final OpenFileTextFieldHandler opener;
    private final FocusAdapter focusListener = new FocusAdapter() {
        @Override
        public void focusGained(FocusEvent e) {
            start();
        }
    };

    // Found on start(), not when the handler is made: the location bar makes
    // one on every buffer switch.
    private File root;
    private ProjectFiles projectFiles;
    private final Consumer<ProjectFiles> listener;
    private final Timer changed;

    private boolean started;
    private List<String> snapshot;
    private List<FinderItem> candidates = List.of();
    private List<FinderItem> emptyQueryItems = List.of();
    private Set<String> namedPaths = Set.of(); // The open buffers' and recent files' paths.
    private List<Object> namedKey; // Those buffers and recent files, in order; null until built.
    // By head, and whether those the project lists are left out; made again with the candidates.
    private final Map<String, List<FinderItem>> listings = new HashMap<>();
    private String namedHead;
    private List<FinderItem> namedItems;

    public FindFileTextFieldHandler(Editor editor, HistoryTextField textField) {
        super(editor, textField);
        Buffer buffer = editor.getBuffer();
        File d = buffer == null ? null : buffer.getCompletionDirectory();
        dir = d != null && d.isLocal() ? d : null;
        opener = new OpenFileTextFieldHandler(editor, textField, false);
        textField.addFocusListener(focusListener);
        // A first scan publishes every 200 ms: rebuild at most every 300.
        changed = new Timer(300, e -> projectFilesChanged());
        changed.setRepeats(false);
        listener = pf -> SwingUtilities.invokeLater(() -> {
            if (!changed.isRunning())
                changed.start();
        });
    }

    @Override
    protected String command() {
        return "findFileInProject";
    }

    /** Lists the candidates and starts a rescan if the project's list is stale. */
    @Override
    public void start() {
        if (!isActive())
            return;
        if (!started) {
            root = editor.getBuffer() == null ? null : ProjectRoot.find(editor.getBuffer());
            projectFiles = root == null ? null : ProjectFiles.forRoot(root);
        }
        if (projectFiles != null) {
            if (!started)
                projectFiles.addListener(listener);
            projectFiles.refreshIfStale(Editor.preferences().getIntegerProperty(Property.FINDER_RESCAN_SECONDS));
        }
        started = true;
        // Focus comes back often: the candidates are made again only when
        // what they list changed; directories are read again.
        if (!namedKey().equals(namedKey) || projectFiles != null && projectFiles.files() != snapshot)
            rebuild();
        else {
            listings.clear();
            namedItems = null;
        }
        showStatus();
        refilter();
    }

    private static List<Object> namedKey() {
        List<Object> key = new ArrayList<>();
        for (Buffer buf : Editor.getBufferList())
            key.add(buf);
        for (RecentFilesEntry entry : RecentFiles.getInstance().getEntries())
            key.add(entry.location + "\0" + entry.name);
        return key;
    }

    @Override
    public void detached() {
        super.detached();
        textField.removeFocusListener(focusListener);
        changed.stop();
        if (projectFiles != null)
            projectFiles.removeListener(listener);
    }

    // Called, at most every 300 ms, when the project's list has changed.
    private void projectFilesChanged() {
        if (!isActive())
            return;
        rebuild();
        showStatus();
        refilter();
    }

    private void showStatus() {
        if (root == null) {
            editor.status(
                dir == null
                        ? "Not in a project: open buffers and recent files"
                        : "Not in a project: open buffers, recent files and " + dir.canonicalPath());
            return;
        }
        StringBuilder sb = new StringBuilder(root.getName());
        sb.append(": ").append(String.format("%,d", projectFiles.files().size())).append(" files");
        if (projectFiles.isTruncated())
            sb.append(" (finderMaxFiles reached)");
        if (projectFiles.isScanning())
            sb.append(", scanning...");
        editor.status(sb.toString());
    }

    // What the text is: opened as typed, a directory to list, or a name to find.
    private enum Kind {
        TYPED, PATH, NAME
    }

    private Kind kind(String text) {
        String t = text.strip();
        if (t.isEmpty())
            return Kind.NAME;
        if (t.startsWith("-e ")
                || t.contains(" -e ")
                || t.startsWith("http:")
                || t.startsWith("https:")
                || t.startsWith("ftp:")
                || t.startsWith("file:")
                || t.startsWith("www.")
                || t.startsWith("ftp.")
                || File.hasRemotePrefix(t)
                || editor.getAlias(t) != null)
            return Kind.TYPED;
        return isOwnPath(t) || t.startsWith("./") || t.startsWith("../") ? Kind.PATH : Kind.NAME;
    }

    // Absolute, or from home.
    private static boolean isOwnPath(String t) {
        return t.startsWith("~") || Utilities.isFilenameAbsolute(t);
    }

    private static int lastSeparator(String s) {
        return Math.max(s.lastIndexOf('/'), s.lastIndexOf(LocalFile.getSeparatorChar()));
    }

    // The directory part of a path, with its separator: "~" alone is home.
    private static String head(String t) {
        return t.equals("~") ? "~/" : t.substring(0, lastSeparator(t) + 1);
    }

    // The text without a ":N" suffix, stripped.
    private String typed() {
        return withoutLine(textField.getText()).strip();
    }

    private static String withoutLine(String text) {
        Matcher m = LINE_SUFFIX.matcher(text.strip());
        return m.matches() ? m.group(1) : text;
    }

    @Override
    protected String queryText(String text) {
        String t = withoutLine(text).strip();
        return switch (kind(t)) {
            case TYPED -> "";
            case PATH -> t.equals("~") ? "~/" : t;
            case NAME -> t;
        };
    }

    @Override
    protected List<FinderItem> candidates() {
        String t = typed();
        return switch (kind(t)) {
            case TYPED -> List.of();
            case PATH -> listing(head(t), true, false);
            case NAME -> named(head(t));
        };
    }

    @Override
    protected List<FinderItem> emptyQueryItems() {
        return kind(typed()) == Kind.NAME ? emptyQueryItems : List.of();
    }

    // The project's candidates, then the entries of the directory head
    // names, relative to dir, that the project's list doesn't have.
    private List<FinderItem> named(String head) {
        if (projectFiles != null && projectFiles.files() != snapshot)
            rebuild();
        if (!head.equals(namedHead) || namedItems == null) {
            List<FinderItem> entries = listing(head, !head.isEmpty(), true);
            if (entries.isEmpty())
                namedItems = candidates;
            else {
                namedItems = new ArrayList<>(candidates.size() + entries.size());
                namedItems.addAll(candidates);
                namedItems.addAll(entries);
            }
            namedHead = head;
        }
        return namedItems;
    }

    // The entries of the directory head names, relative to dir; with the
    // directory itself first if self; leaving out, if unlisted, the files
    // the candidates have: those the project lists, and those named.
    private List<FinderItem> listing(String head, boolean self, boolean unlisted) {
        String key = (unlisted ? "u" : "a") + (self ? "s" : "-") + head;
        return listings.computeIfAbsent(key, k -> list(head, self, unlisted));
    }

    private List<FinderItem> list(String head, boolean self, boolean unlisted) {
        File d = resolve(head);
        if (d == null)
            return List.of();
        String listedDir = unlisted ? listedDir(d) : null;
        boolean near = dir != null && d.canonicalPath().equals(dir.canonicalPath());
        int boost = near ? BOOST_NEAR : 0;
        Pattern excludes = FilenameCompletion.excludesPattern();
        File[] files = d.listFiles();
        List<FinderItem> items = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (excludes != null && excludes.matcher(f.getName()).matches())
                    continue;
                boolean directory = f.isDirectory();
                if (unlisted && !directory && (namedPaths.contains(f.canonicalPath()) || isListed(listedDir, f)))
                    continue;
                items.add(new PathItem(head, f.getName(), f, directory, boost));
            }
        }
        items.sort((a, b) -> a.matchText().toLowerCase(Locale.ROOT).compareTo(b.matchText().toLowerCase(Locale.ROOT)));
        if (self)
            items.add(0, new PathItem(head, "", d, true, boost));
        return Collections.unmodifiableList(items);
    }

    // d relative to the root, with '/', if the project's list could have
    // its files; else null.
    private String listedDir(File d) {
        if (root == null || snapshot == null || !isUnder(d.canonicalPath(), root.canonicalPath()))
            return null;
        return ProjectFiles.relative(Path.of(root.canonicalPath()), Path.of(d.canonicalPath()));
    }

    // Whether the project's list, sorted, has f, a file of the directory listedDir.
    private boolean isListed(String listedDir, File f) {
        if (listedDir == null)
            return false;
        String rel = listedDir.isEmpty() ? f.getName() : listedDir + "/" + f.getName();
        return Collections.binarySearch(snapshot, rel) >= 0;
    }

    private File resolve(String head) {
        if (head.isEmpty())
            return dir;
        boolean own = isOwnPath(head);
        if (!own && dir == null)
            return null;
        File d = own ? File.getInstance(head) : File.getInstance(dir, head);
        return d != null && d.isLocal() && d.isDirectory() ? d : null;
    }

    @Override
    protected void accept(FinderItem item, boolean otherWindow) {
        if (item instanceof PathItem p) {
            if (p.isDirectory && !p.name.isEmpty() && !otherWindow) {
                // Into the directory, to go on finding.
                String s = p.completion();
                textField.setText(s);
                textField.setCaretPosition(s.length());
                return;
            }
            final int line = lineNumber();
            closePrompt();
            ProjectCommands.open(editor, p.file, otherWindow, line);
            return;
        }
        if (item instanceof FileItem file) {
            final int line = lineNumber();
            closePrompt();
            file.open(editor, otherWindow, line);
            return;
        }
        super.accept(item, otherWindow);
    }

    /** The N of a "name:N" in the field, or 0. */
    private int lineNumber() {
        Matcher m = LINE_SUFFIX.matcher(textField.getText().strip());
        if (m.matches()) {
            try {
                return Integer.parseInt(m.group(2));
            }
            catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    @Override
    public void enter() {
        // A name of an existing file or directory, such as "..", opens as named,
        // unless the user has picked another row.
        final Kind kind = kind(textField.getText());
        if (kind == Kind.TYPED || kind == Kind.NAME && !selectionMoved() && existing(textField.getText()) != null) {
            openTyped();
            return;
        }
        FinderItem.Row row = selection();
        if (row != null)
            accept(row.item(), false);
        else
            openTyped();
    }

    // The local file or directory text names, without a ":N" suffix; null if none.
    private File existing(String text) {
        String t = withoutLine(text).strip();
        if (t.isEmpty())
            return null;
        boolean own = isOwnPath(t);
        if (!own && dir == null)
            return null;
        File f = own ? File.getInstance(t) : File.getInstance(dir, t);
        return f != null && f.isLocal() && f.exists() ? f : null;
    }

    // As the location bar always has: paths, URLs, aliases, encodings, new files.
    private void openTyped() {
        detached();
        // "Foo.java:12" for an existing Foo.java: the old open knows no line.
        final int line = lineNumber();
        if (line > 0) {
            File f = existing(textField.getText());
            if (f != null && !f.isDirectory()) {
                closePrompt();
                ProjectCommands.open(editor, f, false, line);
                return;
            }
        }
        opener.enter();
    }

    // The text that names item, relative to dir where it can be.
    @Override
    protected String completion(FinderItem item) {
        if (item instanceof PathItem p)
            return p.name.isEmpty() ? null : p.completion();
        if (item instanceof FileItem f) {
            String path = f.file().canonicalPath();
            if (dir != null && isUnder(path, dir.canonicalPath()))
                return ProjectFiles.relative(Path.of(dir.canonicalPath()), Path.of(path));
            return path;
        }
        return null;
    }

    // A directory Tab completed lists its files, which Tab then steps through.
    @Override
    protected boolean continuesFrom(String completion) {
        return completion.endsWith("/") || completion.endsWith(LocalFile.getSeparator());
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER && Keys.keyModifiers(e) == SHIFT_MASK) {
            e.consume();
            openTyped();
            return;
        }
        super.keyPressed(e);
    }

    private void rebuild() {
        namedKey = namedKey();
        final String rootPath = root == null ? null : root.canonicalPath();
        Map<String, FileItem> items = new LinkedHashMap<>(); // By path.
        Set<String> listed = new HashSet<>(); // Their root-relative names, if under the root.
        List<FinderItem> empty = new ArrayList<>();
        final Buffer current = editor.getBuffer();
        for (Buffer buf : Editor.getBufferList()) {
            File f = buf.getFile();
            if (buf.getType() != Buffer.TYPE_NORMAL || f == null || !f.isLocal() || f.isDirectory())
                continue;
            String path = f.canonicalPath();
            FileItem item = new FileItem(path, display(path, rootPath), buf, BOOST_OPEN);
            if (items.putIfAbsent(path, item) == null) {
                listed.add(item.text);
                if (buf != current)
                    empty.add(item);
            }
        }
        for (RecentFilesEntry entry : RecentFiles.getInstance().getEntries()) {
            if (entry.location == null
                    || entry.name == null
                    || entry.name.isEmpty()
                    || File.hasRemotePrefix(entry.location))
                continue;
            File f = File.getInstance(File.getInstance(entry.location), entry.name);
            if (f == null || !f.isLocal())
                continue;
            String path = f.canonicalPath();
            if (items.containsKey(path) || (rootPath != null && !isUnder(path, rootPath)) || !f.isFile())
                continue;
            FileItem item = new FileItem(path, display(path, rootPath), null, BOOST_RECENT);
            items.put(path, item);
            listed.add(item.text);
            empty.add(item);
        }
        List<FinderItem> all = new ArrayList<>(items.values());
        if (projectFiles != null) {
            snapshot = projectFiles.files();
            final String prefix =
                    rootPath.endsWith(LocalFile.getSeparator()) ? rootPath : rootPath + LocalFile.getSeparator();
            // The buffer's directory, relative to the root, its files first among the project's.
            final String near = dir != null && isUnder(dir.canonicalPath(), rootPath)
                    ? ProjectFiles.relative(Path.of(rootPath), Path.of(dir.canonicalPath()))
                    : null;
            for (String rel : snapshot) {
                if (!listed.contains(rel)) {
                    boolean isNear = near != null && !near.isEmpty() && rel.startsWith(near + "/");
                    all.add(new FileItem(prefix, rel, isNear ? BOOST_NEAR : 0));
                }
            }
        }
        candidates = Collections.unmodifiableList(all);
        emptyQueryItems = Collections.unmodifiableList(empty);
        namedPaths = Set.copyOf(items.keySet());
        listings.clear();
        namedItems = null;
    }

    static boolean isUnder(String path, String rootPath) {
        return Path.of(path).startsWith(Path.of(rootPath));
    }

    // Root-relative with '/' under the root; otherwise the full path, home as "~".
    static String display(String path, String rootPath) {
        if (rootPath != null && isUnder(path, rootPath))
            return ProjectFiles.relative(Path.of(rootPath), Path.of(path));
        String home = Utilities.getUserHome();
        if (home != null && path.startsWith(home + LocalFile.getSeparator()))
            return "~" + path.substring(home.length());
        return path;
    }

    static final class FileItem implements FinderItem {
        // The path is rootPrefix + text for a project file, which saves making one per file up front.
        private final String rootPrefix;
        private final String path;
        final String text;
        private final int base;
        private final Buffer buffer;
        private final int boost;

        FileItem(String path, String text, Buffer buffer, int boost) {
            this(null, path, text, buffer, boost);
        }

        FileItem(String rootPrefix, String rel, int boost) {
            this(rootPrefix, null, rel, null, boost);
        }

        private FileItem(String rootPrefix, String path, String text, Buffer buffer, int boost) {
            this.rootPrefix = rootPrefix;
            this.path = path;
            this.text = text;
            this.buffer = buffer;
            this.boost = boost;
            base = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\')) + 1;
        }

        File file() {
            if (path != null)
                return File.getInstance(path);
            char sep = LocalFile.getSeparatorChar();
            return File.getInstance(rootPrefix + (sep == '/' ? text : text.replace('/', sep)));
        }

        @Override
        public String matchText() {
            return text;
        }

        @Override
        public String label() {
            return text.substring(base);
        }

        @Override
        public int labelOffset() {
            return base;
        }

        @Override
        public String detail() {
            return base > 0 ? text.substring(0, base - 1) : "";
        }

        @Override
        public int detailOffset() {
            return 0;
        }

        @Override
        public Icon icon() {
            return buffer != null ? buffer.getIcon() : FileIcons.getIcon(label(), null);
        }

        @Override
        public int boost() {
            return boost;
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {
            open(editor, otherWindow, 0);
        }

        void open(Editor editor, boolean otherWindow, int line) {
            File f = file();
            if (f != null)
                ProjectCommands.open(editor, f, otherWindow, line);
        }
    }

    /**
     * An entry of a listed directory, or with an empty name, the directory
     * itself; matched as its path, head and name, so that it ranks beside
     * the project's files.
     */
    private static final class PathItem implements FinderItem {
        final String head;
        final String name;
        final File file;
        final boolean isDirectory;
        private final int boost;

        PathItem(String head, String name, File file, boolean isDirectory, int boost) {
            this.head = head;
            this.name = name;
            this.file = file;
            this.isDirectory = isDirectory;
            this.boost = boost;
        }

        String completion() {
            return head + name + (isDirectory ? LocalFile.getSeparator() : "");
        }

        @Override
        public String matchText() {
            return head + (isDirectory && !name.isEmpty() ? name + "/" : name);
        }

        @Override
        public String label() {
            return name.isEmpty() ? (head.isEmpty() ? "." : head) : matchText().substring(head.length());
        }

        @Override
        public int labelOffset() {
            return name.isEmpty() ? -1 : head.length();
        }

        @Override
        public String detail() {
            return name.isEmpty() ? "" : head;
        }

        @Override
        public int detailOffset() {
            return name.isEmpty() || head.isEmpty() ? -1 : 0;
        }

        @Override
        public int boost() {
            return boost;
        }

        @Override
        public String note() {
            return name.isEmpty() ? "open directory" : "";
        }

        @Override
        public Icon icon() {
            return isDirectory ? Icons.getIconFromFile("dir_close") : FileIcons.getIcon(name, null);
        }

        @Override
        public void accept(Editor editor, boolean otherWindow) {
            ProjectCommands.open(editor, file, otherWindow, 0);
        }
    }
}
