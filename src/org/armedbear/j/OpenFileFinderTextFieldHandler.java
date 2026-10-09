/*
 * OpenFileFinderTextFieldHandler.java
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.swing.Icon;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Keys;
import org.armedbear.j.util.Utilities;

/**
 * The location bar's finder (Ctrl O). A name finds files under the current
 * buffer's directory, then, if none match, in its project. A path, with a
 * separator or starting with "~", lists that directory's entries. Tab puts
 * the selected entry in the field; Enter opens it, or goes into a directory.
 * What matches nothing, a URL, an alias, or Shift Enter, opens what was
 * typed, as the location bar always has: absolute paths, encodings, new files.
 */
public final class OpenFileFinderTextFieldHandler extends FindFileTextFieldHandler {
    private final File dir; // The buffer's directory; null if it has no local one.
    private final OpenFileTextFieldHandler opener;
    private final FocusAdapter focusListener = new FocusAdapter() {
        @Override
        public void focusGained(FocusEvent e) {
            start();
        }
    };

    private List<String> dirSnapshot;
    private List<FinderItem> dirItems = List.of();
    private final Map<String, List<FinderItem>> listings = new HashMap<>();
    private boolean fromFallback;

    public OpenFileFinderTextFieldHandler(Editor editor, HistoryTextField textField) {
        super(editor, textField);
        Buffer buffer = editor.getBuffer();
        File d = buffer == null ? null : buffer.getCompletionDirectory();
        dir = d != null && d.isLocal() ? d : null;
        opener = new OpenFileTextFieldHandler(editor, textField, false);
        textField.addFocusListener(focusListener);
    }

    @Override
    protected String command() {
        return "openFile";
    }

    @Override
    public void detached() {
        super.detached();
        textField.removeFocusListener(focusListener);
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
        return lastSeparator(t) >= 0 || t.startsWith("~") ? Kind.PATH : Kind.NAME;
    }

    private static int lastSeparator(String s) {
        return Math.max(s.lastIndexOf('/'), s.lastIndexOf(LocalFile.getSeparatorChar()));
    }

    // The directory part of a path, with its separator: "~" alone is home.
    private static String head(String t) {
        return t.equals("~") ? "~/" : t.substring(0, lastSeparator(t) + 1);
    }

    @Override
    protected String queryText(String text) {
        String t = super.queryText(text).strip();
        return switch (kind(t)) {
            case TYPED -> "";
            case PATH -> t.substring(lastSeparator(t) + 1).replaceFirst("^~$", "");
            case NAME -> t;
        };
    }

    @Override
    protected List<FinderItem> candidates() {
        String t = super.queryText(textField.getText()).strip();
        return switch (kind(t)) {
            case TYPED -> List.of();
            case PATH -> listing(head(t), false);
            case NAME -> dirItems();
        };
    }

    @Override
    protected List<FinderItem> emptyQueryItems() {
        String t = super.queryText(textField.getText()).strip();
        return switch (kind(t)) {
            case TYPED -> List.of();
            case PATH -> listing(head(t), true);
            // The directory first, so Enter on nothing opens it, as it always has.
            case NAME -> listing("", true);
        };
    }

    @Override
    protected List<FinderItem> fallbackCandidates() {
        return kind(textField.getText()) == Kind.NAME ? projectCandidates() : null;
    }

    // The project's list is only the fallback here: built when first needed.
    @Override
    protected void prepare() {}

    @Override
    protected void listed(boolean fallback) {
        fromFallback = fallback;
        showStatus();
    }

    @Override
    protected void showStatus() {
        if (dir == null)
            return;
        if (fromFallback && root != null)
            editor.status("Nothing under " + dir.getName() + "; matches in project " + root.getName());
        else
            editor.status(dir.canonicalPath());
    }

    // The files under dir: from the project's list when dir is in a project,
    // else just dir's own entries, as for the home directory.
    private List<FinderItem> dirItems() {
        if (dir == null)
            return List.of();
        if (root == null || projectFiles == null || !isUnder(dir.canonicalPath(), root.canonicalPath()))
            return listing("", false);
        String rel = ProjectFiles.relative(Path.of(root.canonicalPath()), Path.of(dir.canonicalPath()));
        List<FinderItem> items = items(projectFiles.files(), rel.isEmpty() ? "" : rel + "/");
        // Nothing here in the project's list, which skips what's ignored, such as
        // build output: list the directory's own entries.
        if (items.isEmpty() && !rel.isEmpty() && !projectFiles.isScanning())
            return listing("", false);
        return items;
    }

    // The files of snapshot under relPrefix, relative to dir.
    private List<FinderItem> items(List<String> snapshot, String relPrefix) {
        if (snapshot != dirSnapshot) {
            String dirPrefix = withSeparator(dir.canonicalPath());
            List<FinderItem> items = new ArrayList<>();
            for (String s : snapshot) {
                if (s.startsWith(relPrefix))
                    items.add(new FileItem(dirPrefix, s.substring(relPrefix.length())));
            }
            dirItems = Collections.unmodifiableList(items);
            dirSnapshot = snapshot;
        }
        return dirItems;
    }

    private static String withSeparator(String path) {
        return path.endsWith(LocalFile.getSeparator()) ? path : path + LocalFile.getSeparator();
    }

    // The entries of the directory head names, relative to dir; with the
    // directory itself first if self.
    private List<FinderItem> listing(String head, boolean self) {
        List<FinderItem> entries = listings.computeIfAbsent(head, this::list);
        if (!self || entries.isEmpty() && resolve(head) == null)
            return entries;
        List<FinderItem> items = new ArrayList<>(entries.size() + 1);
        items.add(new PathItem(head, "", resolve(head), true));
        items.addAll(entries);
        return items;
    }

    private File resolve(String head) {
        if (head.isEmpty())
            return dir;
        boolean own = head.startsWith("~") || Utilities.isFilenameAbsolute(head);
        if (!own && dir == null)
            return null;
        File d = own ? File.getInstance(head) : File.getInstance(dir, head);
        return d != null && d.isLocal() && d.isDirectory() ? d : null;
    }

    private List<FinderItem> list(String head) {
        File d = resolve(head);
        if (d == null)
            return List.of();
        Pattern excludes = FilenameCompletion.excludesPattern();
        File[] files = d.listFiles();
        List<FinderItem> items = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (excludes == null || !excludes.matcher(f.getName()).matches())
                    items.add(new PathItem(head, f.getName(), f, f.isDirectory()));
            }
        }
        items.sort((a, b) -> a.matchText().toLowerCase(Locale.ROOT).compareTo(b.matchText().toLowerCase(Locale.ROOT)));
        return Collections.unmodifiableList(items);
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
        super.accept(item, otherWindow);
    }

    @Override
    public void enter() {
        // A name of an existing file or directory, such as "..", opens as named,
        // unless the user has picked another row.
        final Kind kind = kind(textField.getText());
        if (kind == Kind.TYPED || kind == Kind.NAME && !selectionMoved() && exists(textField.getText())) {
            openTyped();
            return;
        }
        FinderItem.Row row = selection();
        if (row != null)
            accept(row.item(), false);
        else
            openTyped();
    }

    private boolean exists(String text) {
        return existing(text) != null;
    }

    // The local file or directory text names, without a ":N" suffix; null if none.
    private File existing(String text) {
        String t = super.queryText(text).strip();
        if (t.isEmpty())
            return null;
        boolean own = t.startsWith("~") || Utilities.isFilenameAbsolute(t);
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

    /** An entry of a listed directory, or with an empty name, the directory itself. */
    private static final class PathItem implements FinderItem {
        final String head;
        final String name;
        final File file;
        final boolean isDirectory;

        PathItem(String head, String name, File file, boolean isDirectory) {
            this.head = head;
            this.name = name;
            this.file = file;
            this.isDirectory = isDirectory;
        }

        String completion() {
            return head + name + (isDirectory ? LocalFile.getSeparator() : "");
        }

        @Override
        public String matchText() {
            return isDirectory && !name.isEmpty() ? name + "/" : name;
        }

        @Override
        public String label() {
            return name.isEmpty() ? (head.isEmpty() ? "." : head) : matchText();
        }

        @Override
        public int labelOffset() {
            return name.isEmpty() ? -1 : 0;
        }

        @Override
        public String detail() {
            return name.isEmpty() ? "" : head;
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
