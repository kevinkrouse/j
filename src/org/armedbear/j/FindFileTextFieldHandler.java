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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.armedbear.j.util.Utilities;

/**
 * Finds a file in the current buffer's project, its open buffers, and its
 * recent files. "name:N" goes to line N.
 */
public class FindFileTextFieldHandler extends FinderTextFieldHandler {
    private static final Pattern LINE_SUFFIX = Pattern.compile("^(.*?):(\\d+)$");

    // Open buffers and recent files outrank other matches of about the same quality.
    private static final int BOOST_OPEN = 30;
    private static final int BOOST_RECENT = 15;

    // Found on start(), not when the handler is made: the location bar makes
    // one on every buffer switch.
    protected File root;
    protected ProjectFiles projectFiles;
    protected final Consumer<ProjectFiles> listener;
    private final Timer changed;

    private boolean started;
    private List<String> snapshot;
    private List<FinderItem> candidates = List.of();
    private List<FinderItem> emptyQueryItems = List.of();

    public FindFileTextFieldHandler(Editor editor, HistoryTextField textField) {
        super(editor, textField);
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
        prepare();
        showStatus();
        refilter();
    }

    /** Builds the candidates the first list needs, on start. */
    protected void prepare() {
        rebuild();
    }

    @Override
    public void detached() {
        super.detached();
        changed.stop();
        if (projectFiles != null)
            projectFiles.removeListener(listener);
    }

    /** Called, at most every 300 ms, when a project list it uses has changed. */
    protected final void projectFilesChanged() {
        if (!isActive())
            return;
        prepare();
        showStatus();
        refilter();
    }

    protected void showStatus() {
        if (root == null) {
            editor.status("Not in a project: open buffers and recent files");
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

    @Override
    protected List<FinderItem> candidates() {
        return projectCandidates();
    }

    @Override
    protected List<FinderItem> emptyQueryItems() {
        return emptyQueryItems;
    }

    /** Open buffers, recent files under the root, then the project's files. */
    protected final List<FinderItem> projectCandidates() {
        if (projectFiles != null && projectFiles.files() != snapshot)
            rebuild();
        return candidates;
    }

    @Override
    protected String queryText(String text) {
        Matcher m = LINE_SUFFIX.matcher(text.strip());
        return m.matches() ? m.group(1) : text;
    }

    @Override
    protected void accept(FinderItem item, boolean otherWindow) {
        if (!(item instanceof FileItem file)) {
            super.accept(item, otherWindow);
            return;
        }
        final int line = lineNumber();
        closePrompt();
        file.open(editor, otherWindow, line);
    }

    /** The N of a "name:N" in the field, or 0. */
    protected final int lineNumber() {
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

    private void rebuild() {
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
            for (String rel : snapshot) {
                if (!listed.contains(rel))
                    all.add(new FileItem(prefix, rel));
            }
        }
        candidates = Collections.unmodifiableList(all);
        emptyQueryItems = Collections.unmodifiableList(empty);
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

        FileItem(String rootPrefix, String rel) {
            this(rootPrefix, null, rel, null, 0);
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
}
