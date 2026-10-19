/*
 * Directory.java
 *
 * Copyright (C) 1998-2004 Peter Graves
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

package org.armedbear.j.mode.dir;

import static org.armedbear.j.Constants.*;

import java.awt.AWTEvent;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import org.armedbear.j.BrowseFile;
import org.armedbear.j.Buffer;
import org.armedbear.j.CopyFileDialog;
import org.armedbear.j.Debug;
import org.armedbear.j.DirectoryCache;
import org.armedbear.j.DirectoryFilenameFilter;
import org.armedbear.j.Display;
import org.armedbear.j.Editor;
import org.armedbear.j.ErrorRunnable;
import org.armedbear.j.File;
import org.armedbear.j.FtpFile;
import org.armedbear.j.FtpLoadProcess;
import org.armedbear.j.FtpSession;
import org.armedbear.j.History;
import org.armedbear.j.InputDialog;
import org.armedbear.j.Line;
import org.armedbear.j.LocalFile;
import org.armedbear.j.Log;
import org.armedbear.j.MessageDialog;
import org.armedbear.j.MotionCommands;
import org.armedbear.j.NavigationComponent;
import org.armedbear.j.OutputBuffer;
import org.armedbear.j.Platform;
import org.armedbear.j.Position;
import org.armedbear.j.Preferences;
import org.armedbear.j.ProjectRoot;
import org.armedbear.j.Property;
import org.armedbear.j.RemoteBuffer;
import org.armedbear.j.RemoteSession;
import org.armedbear.j.ShellCommand;
import org.armedbear.j.Sidebar;
import org.armedbear.j.SimpleEdit;
import org.armedbear.j.SshFile;
import org.armedbear.j.SshSession;
import org.armedbear.j.StatusBarProgressNotifier;
import org.armedbear.j.WindowCommands;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.ProcessRunner;
import org.armedbear.j.util.TimeStyle;
import org.armedbear.j.util.Utilities;

public final class DirectoryBuffer extends Buffer {
    private static final Preferences preferences = Editor.preferences();

    // This is the flag for this particular directory buffer.
    private boolean usingNativeFormat;

    public static final int SORT_BY_NAME = 0;
    public static final int SORT_BY_DATE = 1;
    public static final int SORT_BY_SIZE = 2;

    private int sortBy = SORT_BY_NAME;

    private String limitPattern;
    // Whether names starting with a dot are listed.
    private boolean showHidden = Editor.preferences().getBooleanProperty(Property.DIR_SHOW_HIDDEN);
    // The sort backward, but for . and .., which stay on top.
    private boolean reverse;
    // Directories over the files, or null for what dirSortDirectoriesFirst says.
    private Boolean directoriesFirst;
    private ListingStyle style =
            new ListingStyle(Editor.preferences().getBooleanProperty(Property.DIR_HUMAN_READABLE_SIZES),
                    Editor.preferences().getStringProperty(Property.DIR_TIME_STYLE));

    private ArrayList<DirectoryEntry> entries = new ArrayList<>();

    private int numMarked = 0;

    private DirectoryHistory history = new DirectoryHistory();

    private static final Pattern nativeMoveToFilenameRegExp;
    private static final Pattern internalMoveToFilenameRegExp;

    private boolean loadError;

    static {
        // Letters, in any language.
        final String letter = "\\p{L}";

        // Month is 2 or more letters, as "Oct" or "févr.", possibly padded on the right with spaces.
        final String month = letter + letter + "+[.]?";

        // A size in bytes, or as ls -h writes it: 4.0K, 12M, 123B.
        final String size = "[0-9]+([.,][0-9])?[KMGTPEB]?";

        // Year.
        final String yyyy = "[0-9][0-9][0-9][0-9]";

        // Day of month.
        final String dd = "[ 0-3][0-9][.]?";

        // Month and day (includes following space character).
        final String monthAndDay = "(" + month + " *" + dd + " " + "|" + dd + " " + month + " *" + ")";

        // Time of day.
        final String HHMM = "[ 0-2][0-9]:[0-5][0-9]";

        // Time or year.
        final String timeOrYear = "(" + HHMM + "|" + " " + yyyy + "|" + yyyy + " " + ")";

        String traditional = size + " " + monthAndDay + timeOrYear + " ";

        // --time-style=long-iso
        // -rw-r--r--    1 peter    peter         147 2002-11-13 13:10 notes
        // --time-style=iso
        // -rw-r--r--    1 peter    peter       69016 11-16 18:29 DirectoryBuffer.java
        // -rw-r--r--    1 peter    peter       13274 2001-09-08  thinbox.tar.gz
        final String isoMaybeYear = "(" + yyyy + "-)?";
        final String isoMonthAndDay = "[01][0-9]-[0-3][0-9]";
        final String isoMaybeTime = "(" + HHMM + ")?";
        String iso = size + " " + isoMaybeYear + isoMonthAndDay + " " + isoMaybeTime + " *";

        // Mac OS X (Pete Kirkham)
        // --time-style="+%e %b  %Y"
        // -rw-r--r--    1 pete  pete    6065 23 Oct  2003 Directories.java
        // --time-style="+%e %b %H:%M"
        // -rw-r--r--    1 pete  pete   75350 21 May 19:58 DirectoryBuffer.java
        String osx = size + " " + dd + " " + month + " " + timeOrYear + " ";

        nativeMoveToFilenameRegExp = Pattern.compile("(" + traditional + ")|(" + iso + ")|(" + osx + ")");

        // The internal format is written as ls writes its columns.
        internalMoveToFilenameRegExp = nativeMoveToFilenameRegExp;
    }

    public static final Pattern getNativeMoveToFilenameRegExp() {
        return nativeMoveToFilenameRegExp;
    }

    public static final Pattern getInternalMoveToFilenameRegExp() {
        return internalMoveToFilenameRegExp;
    }

    public DirectoryBuffer(File dir) {
        super();
        supportsUndo = false;
        setFile(dir);
        type = TYPE_DIRECTORY;
        readOnly = true;
        mode = DirectoryMode.getMode();
        formatter = mode.getFormatter(this);
        setInitialized(true);
    }

    public DirectoryBuffer(File dir, String listing) {
        this(dir);
        setListing(listing);
    }

    public final boolean isUsingNativeFormat() {
        return usingNativeFormat;
    }

    @Override
    public File getCurrentDirectory() {
        return getFile();
    }

    public int getSortBy() {
        return sortBy;
    }

    @Override
    public Position getInitialDotPos() {
        Line line = getFirstLine();
        Line upLine = null; // We'll put dot here if directory is empty.
        while (true) {
            if (line.next() == null)
                break;
            String name = getName(line);
            if (name != null) {
                if (name.equals(".."))
                    upLine = line;
                else if (!name.equals("."))
                    return new Position(line, getNameOffset(line));
            }
            line = line.next();
        }
        // DirectoryBuffer is empty.
        if (upLine != null)
            return new Position(upLine, getNameOffset(upLine));
        else
            return new Position(line, getNameOffset(line));
    }

    private void setLimitPattern(String s) {
        if (s == null || s.length() == 0)
            limitPattern = null;
        else
            limitPattern = s;
    }

    public final String getLimitPattern() {
        return limitPattern;
    }

    public static void dirLimit() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof DirectoryBuffer directory) {
            // Typed over the filter bar's chip, as the log's filters are.
            if (editor.getFilterBar() != null && editor.getFilterBar().choose(DirectoryMode.LIMIT))
                return;
            InputDialog dialog = new InputDialog(editor, "Pattern:", "Limit", directory.getLimitPattern());
            dialog.setHistory(new History("dirLimit"));
            editor.centerDialog(dialog);
            dialog.setVisible(true);
            String pattern = dialog.getInput();
            // A null pattern means the user cancelled the input dialog.
            if (pattern != null) {
                editor.repaintNow();
                directory.limit(pattern);
            }
        }
    }

    public static void dirLimit(String pattern) {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof DirectoryBuffer directoryBuffer)
            directoryBuffer.limit(pattern);
    }

    public static void dirUnlimit() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof DirectoryBuffer directory) {
            if (directory.getLimitPattern() != null)
                directory.limit(null);
        }
    }

    // Making files and directories.

    /**
     * a: a new file, its name asked for, opened in a buffer, as openFile
     * opens one: it is written when the buffer is saved.
     */
    public static void dirNewFile() {
        newEntry(false, null);
    }

    public static void dirNewFile(String name) {
        newEntry(false, name);
    }

    /** A: a new directory, its name asked for, the caret on it in the listing. */
    public static void dirNewDirectory() {
        newEntry(true, null);
    }

    public static void dirNewDirectory(String name) {
        newEntry(true, name);
    }

    private static void newEntry(boolean directory, String name) {
        final Editor editor = Editor.currentEditor();
        if (!(editor.getBuffer() instanceof DirectoryBuffer dir))
            return;
        if (name == null)
            name = InputDialog.showInputDialog(
                editor,
                directory ? "Directory:" : "File:",
                directory ? "New Directory" : "New File");
        if (name == null || name.isBlank())
            return;
        name = name.strip();
        final String error = directory ? dir.makeDirectory(name) : dir.prepareFile(name);
        if (error != null) {
            editor.status(error);
            return;
        }
        if (directory)
            return;
        // Its buffer if it has one, else the file's, or a new one's to save.
        final File file = dir.resolve(name);
        Buffer buf = Editor.getBufferList().findBuffer(file);
        if (buf == null)
            buf = file.exists() ? Editor.getBuffer(file) : Buffer.createBuffer(file);
        if (buf != null) {
            editor.recordJump();
            editor.makeNext(buf);
            editor.activate(buf);
        }
    }

    // name as the file it names here, as a typed file name is: ~ and quotes; null if it is none.
    private File resolve(String name) {
        final File file = File.getInstance(getFile(), name);
        return file != null && file.isLocal() ? file : null;
    }

    /**
     * Makes name, relative to this directory, a directory, with those it is
     * in; lists the directory again with the caret on it, or on the
     * directory it is in. Returns why it couldn't, or null.
     */
    public String makeDirectory(String name) {
        if (!getFile().isLocal())
            return "Can't make directories in a remote directory";
        final File dir = resolve(name);
        if (dir == null)
            return "Not a name here: " + name;
        if (dir.exists())
            return name + " exists";
        if (!dir.mkdirs())
            return "Can't make " + name;
        showMade(dir);
        return null;
    }

    /**
     * name, relative to this directory, ready to open as a file: the
     * directories it is in made if they aren't there, and listed, so its
     * buffer can be saved. Returns why it couldn't be, or null.
     */
    public String prepareFile(String name) {
        if (!getFile().isLocal())
            return "Can't make files in a remote directory";
        final File file = resolve(name);
        if (file == null)
            return "Not a name here: " + name;
        if (file.isDirectory())
            return name + " is a directory";
        final File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            if (!parent.mkdirs())
                return "Can't make " + parent.canonicalPath();
            showMade(parent);
        }
        return null;
    }

    // Lists the directory again, the caret on made's first part here: itself, or the directory it is in.
    private void showMade(File made) {
        keepingTags(this::rescan);
        final Path here = Path.of(getFile().canonicalPath());
        final Path path = Path.of(made.canonicalPath());
        final Line line = path.startsWith(here) && !path.equals(here)
                ? findName(here.relativize(path).getName(0).toString())
                : null;
        if (line != null)
            for (Editor ed : Editor.getEditorList())
                if (ed.getBuffer() == this) {
                    ed.setDot(line, getNameOffset(line));
                    ed.setMark(null);
                    ed.moveCaretToDotCol();
                    ed.setUpdateFlag(REFRAME | REPAINT);
                }
    }

    /** The sort, limit and hidden files back as a listing starts: by name, all of them. */
    public void clearFilters() {
        final boolean show = Editor.preferences().getBooleanProperty(Property.DIR_SHOW_HIDDEN);
        if (limitPattern != null || showHidden != show || sortBy != SORT_BY_NAME || reverse) {
            // One listing, which sorts it too.
            relist(() -> {
                setLimitPattern(null);
                showHidden = show;
                sortBy = SORT_BY_NAME;
                reverse = false;
            });
            updateHints();
        }
    }

    public static void dirClearFilters() {
        if (Editor.currentEditor().getBuffer() instanceof DirectoryBuffer directory)
            directory.clearFilters();
    }

    /** Whether names starting with a dot are listed. */
    public final boolean getShowHidden() {
        return showHidden;
    }

    public static void dirToggleHidden() {
        if (Editor.currentEditor().getBuffer() instanceof DirectoryBuffer directory)
            directory.setShowHidden(!directory.getShowHidden());
    }

    public final boolean getReverse() {
        return reverse;
    }

    /** The sort backward, or not. */
    public void setReverse(boolean b) {
        if (b != reverse) {
            reverse = b;
            rearrange(null);
            updateHints();
        }
    }

    /** Whether directories are listed over the files, whatever the sort. */
    public final boolean getDirectoriesFirst() {
        return directoriesFirst != null
                ? directoriesFirst
                : preferences.getBooleanProperty(Property.DIR_SORT_DIRECTORIES_FIRST, !usingNativeFormat);
    }

    public void setDirectoriesFirst(boolean b) {
        if (b != getDirectoriesFirst()) {
            directoriesFirst = b;
            rearrange(null);
            updateHints();
        }
    }

    public final boolean getHumanSizes() {
        return style.human();
    }

    /** Sizes as 4.0K and 12M, as ls -h writes them, or in bytes. */
    public void setHumanSizes(boolean b) {
        if (b != style.human()) {
            restyle(new ListingStyle(b, style.timeStyle()));
            updateHints();
        }
    }

    public final String getTimeStyle() {
        return style.timeStyle();
    }

    /** Dates as ls's --time-style writes them: locale, iso or long-iso. */
    public void setTimeStyle(String timeStyle) {
        if (!timeStyle.equals(style.timeStyle()) && ListingStyle.TIME_STYLES.contains(timeStyle)) {
            restyle(new ListingStyle(style.human(), timeStyle));
            updateHints();
        }
    }

    // j's own listing written again from what it read; ls run again for its -h and dates.
    private void restyle(ListingStyle next) {
        if (usingNativeFormat)
            relist(() -> style = next);
        else {
            style = next;
            rearrange(next);
        }
    }

    public static void dirToggleReverse() {
        if (Editor.currentEditor().getBuffer() instanceof DirectoryBuffer d)
            d.setReverse(!d.getReverse());
    }

    public static void dirToggleDirectoriesFirst() {
        if (Editor.currentEditor().getBuffer() instanceof DirectoryBuffer d)
            d.setDirectoriesFirst(!d.getDirectoriesFirst());
    }

    public static void dirToggleHumanSizes() {
        if (Editor.currentEditor().getBuffer() instanceof DirectoryBuffer d)
            d.setHumanSizes(!d.getHumanSizes());
    }

    /** The next time style, or the one named. */
    public static void dirTimeStyle() {
        if (Editor.currentEditor().getBuffer() instanceof DirectoryBuffer d) {
            final List<String> styles = ListingStyle.TIME_STYLES;
            d.setTimeStyle(styles.get((styles.indexOf(d.getTimeStyle()) + 1) % styles.size()));
        }
    }

    public static void dirTimeStyle(String timeStyle) {
        if (Editor.currentEditor().getBuffer() instanceof DirectoryBuffer d)
            d.setTimeStyle(timeStyle.strip());
    }

    /** Lists names starting with a dot, or leaves them out. */
    public void setShowHidden(boolean b) {
        if (b != showHidden) {
            relist(() -> showHidden = b);
            updateHints();
        }
    }

    public void limit(String pattern) {
        if (pattern != null) {
            pattern = pattern.trim();
            if (pattern.length() == 0)
                pattern = null;
        }
        boolean reload = false;
        if (pattern == null && limitPattern != null)
            reload = true;
        else if (pattern != null && !pattern.equals(limitPattern))
            reload = true;
        if (reload) {
            final String p = pattern;
            relist(() -> setLimitPattern(p));
            updateHints();
        }
    }

    // Lists the directory again after change, keeping the caret on its name.
    private void relist(Runnable change) {
        // The window showing the listing, as a chip clicked in one not current.
        final Editor editor = window();
        editor.setWaitCursor();
        String name = null;
        Line dotLine = editor.getBuffer() == this ? editor.getDotLine() : null;
        if (dotLine instanceof DirectoryLine)
            name = getName(dotLine);
        change.run();
        keepingTags(() -> {
            if (getListing() != null)
                reloadFromListing();
            else
                reload();
        });
        Line line = findName(name);
        Position pos;
        if (line != null)
            pos = new Position(line, getNameOffset(line));
        else
            pos = getInitialDotPos();
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() == this) {
                ed.setTopLine(getFirstLine());
                ed.setDot(pos);
                ed.setMark(null);
                ed.moveCaretToDotCol();
                ed.setUpdateFlag(REFRAME | REPAINT);
            }
        }
        editor.setDefaultCursor();
    }

    // The current window if it shows this listing, else one that does.
    private Editor window() {
        final Editor current = Editor.currentEditor();
        if (current.getBuffer() == this)
            return current;
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == this)
                return ed;
        return current;
    }

    // Runs a reload, then tags again the names tagged before it that it lists.
    private void keepingTags(Runnable reload) {
        final Set<String> tagged = new HashSet<>();
        for (Line line = getFirstLine(); line != null; line = line.next())
            if (line instanceof DirectoryLine dl && dl.getDirectoryEntry().isMarked())
                tagged.add(getName(line));
        reload.run();
        if (tagged.isEmpty())
            return;
        for (Line line = getFirstLine(); line != null; line = line.next())
            if (line instanceof DirectoryLine dl && tagged.contains(getName(line))) {
                final DirectoryEntry de = dl.getDirectoryEntry();
                if (!de.isMarked()) {
                    de.setMarked(true);
                    ++numMarked;
                    line.setText(de.toString());
                }
            }
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == this)
                ed.setUpdateFlag(REPAINT);
    }

    public void rescan() {
        final File file = getFile();
        if (file.isRemote())
            DirectoryCache.getDirectoryCache().purge(file.getHostName());
        reload();
        if (loadError)
            return;
        boolean rescanned = false;
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() == this) {
                if (ed.getSidebar() != null) {
                    NavigationComponent c = ed.getSidebar().getBottomComponent();
                    if (c instanceof DirectoryTree tree) {
                        DirectoryTreeModel treeModel = tree.getTreeModel();
                        if (treeModel != null && !rescanned) {
                            treeModel.rescan(file);
                            rescanned = true;
                        }
                        tree.refresh();
                    }
                }
            }
        }
    }

    @Override
    public synchronized void reload() {
        ArrayList<Editor> editors = new ArrayList<>();

        // Remember the name of the current file in every editor.
        ArrayList<String> names = new ArrayList<>();

        // Remember the line number in every editor.
        ArrayList<Integer> lineNumbers = new ArrayList<>();

        // Remember the top line of the display in every editor.
        ArrayList<Integer> topLineNumbers = new ArrayList<>();

        for (Editor ed : Editor.getEditorList()) {
            String name = null;
            int lineNumber = 0;
            int topLineNumber = 0;
            if (ed.getBuffer() == this) {
                name = getName(ed.getDotLine());
                lineNumber = ed.getDotLineNumber();
                topLineNumber = ed.getDisplay().getTopLineNumber();
            }
            editors.add(ed);
            names.add(name);
            lineNumbers.add(lineNumber);
            topLineNumbers.add(topLineNumber);
        }

        empty();
        entries.clear();
        numMarked = 0;
        setListing(null);

        load();

        // Restore the status quo in every window.
        for (int i = 0; i < editors.size(); i++) {
            final Editor ed = editors.get(i);
            if (ed.getBuffer() == this) {
                Line dotLine = findName(names.get(i));
                if (dotLine == null) {
                    dotLine = getLine(lineNumbers.get(i).intValue());
                    if (dotLine == null) {
                        if (getFirstLine() == null) {
                            Debug.bug();
                            appendLine("");
                        }
                        dotLine = getFirstLine();
                        while (dotLine.next() != null)
                            dotLine = dotLine.next();
                    }
                }
                ed.setDot(dotLine, getNameOffset(dotLine));
                ed.setMark(null);
                final Display display = ed.getDisplay();
                display.setShift(0);
                display.moveCaretToDotCol();

                Line line = getLine(topLineNumbers.get(i).intValue());
                if (line == null) {
                    Debug.assertTrue(getFirstLine() != null);
                    line = getFirstLine();
                    while (line.next() != null)
                        line = line.next();
                }
                display.setTopLine(line);
                display.setUpdateFlag(REPAINT);
                ed.updateLocation();
            }
        }
    }

    private synchronized void reloadFromListing() {
        Debug.assertTrue(getListing() != null);
        empty();
        entries.clear();
        numMarked = 0;
        load();
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() == this) {
                final Line line = getFirstLine();
                ed.setDot(line, getNameOffset(line));
                ed.setMark(null);
                final Display display = ed.getDisplay();
                display.setShift(0);
                display.moveCaretToDotCol();
                display.setTopLine(getFirstLine());
                display.setUpdateFlag(REPAINT);
                ed.updateLocation();
            }
        }
    }

    // Called only from synchronized methods.
    private DirectoryEntry addEntry(String name) {
        Debug.assertTrue(!usingNativeFormat);
        File f = File.getInstance(getFile(), name);
        if (f == null)
            return null;
        DirectoryEntry de;
        if (f.isDirectory())
            de = new DirectoryEntry(name, f.lastModified(), f.length(), true);
        else
            de = new DirectoryEntry(name, f.lastModified(), f.length());
        de.setMode(modeOf(f, de.isDirectory()));
        de.setStyle(style);
        if (!name.equals(".") && !name.equals("..")) {
            try {
                String cp = f.getCanonicalPath();
                String ap = f.getAbsolutePath();
                if (!cp.equals(ap)) {
                    final String canonicalPath = getFile().canonicalPath();
                    if (cp.startsWith(canonicalPath + LocalFile.getSeparator()))
                        de.setLinkedTo(cp.substring(canonicalPath.length() + 1));
                    else
                        de.setLinkedTo(cp);
                }
            }
            catch (IOException e) {
                Log.error(e);
            }
        }
        entries.add(de);
        return de;
    }

    private final void appendLine(DirectoryEntry entry) {
        appendLine(new DirectoryLine(entry));
    }

    private synchronized DirectoryEntry findEntry(String name) {
        for (DirectoryEntry entry : entries) {
            if (name.equals(entry.getName()))
                return entry;
        }
        return null;
    }

    private synchronized DirectoryEntry findNativeEntry(String string) {
        for (DirectoryEntry entry : entries) {
            if (string.equals(entry.getString()))
                return entry;
        }
        return null;
    }

    private synchronized void sort() {
        if (usingNativeFormat) {
            Debug.bug();
            return;
        }
        if (sortBy == SORT_BY_NAME)
            sortByName();
        else if (sortBy == SORT_BY_DATE)
            sortByDate();
        else if (sortBy == SORT_BY_SIZE)
            sortBySize();
    }

    // Called only from sort().
    private void sortByName() {
        Debug.assertTrue(!usingNativeFormat);
        Comparator<DirectoryEntry> comparator = (e1, e2) -> e1.getName().compareToIgnoreCase(e2.getName());
        Collections.sort(entries, comparator);
    }

    // Called only from sort().
    private void sortByDate() {
        // Most recent dates first.
        Comparator<DirectoryEntry> comparator = (e1, e2) -> Long.compare(e2.getDate(), e1.getDate());
        Collections.sort(entries, comparator);
    }

    // Called only from sort().
    private void sortBySize() {
        // Biggest files first.
        Comparator<DirectoryEntry> comparator = (e1, e2) -> Long.compare(e2.getSize(), e1.getSize());
        Collections.sort(entries, comparator);
    }

    private static Boolean gnuLs;
    // As long as a listing of a slow mount is waited for.
    private static final Duration LS_TIMEOUT = Duration.ofSeconds(30);

    // Whether the ls on the PATH is GNU's, which takes --time-style.
    private static synchronized boolean isGnuLs() {
        if (gnuLs == null) {
            final ProcessRunner.Result r = Platform.isPlatformUnix()
                    ? ProcessRunner.shell("\\ls --version").timeout(Duration.ofSeconds(5)).run()
                    : null;
            gnuLs = r != null && r.succeeded() && r.output().contains("GNU");
        }
        return gnuLs;
    }

    // ls -l's first column for f: its type, then read, write and execute
    // for its owner, group and others; just the owner's where there are no
    // POSIX permissions, as on Windows.
    private static String modeOf(File f, boolean isDirectory) {
        final Path p = Path.of(f.getAbsolutePath());
        final char type = Files.isSymbolicLink(p) ? 'l' : isDirectory ? 'd' : '-';
        try {
            return type + PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
        }
        catch (UnsupportedOperationException | IOException e) {
            return type + (f.canRead() ? "r" : "-") + (f.canWrite() ? "w" : "-") + (Files.isExecutable(p) ? "x" : "-")
                    + "------";
        }
    }

    // Called only from synchronized methods.
    private void addEntriesToBuffer() {
        // . and .. on top, then the rest in the sort's order or backward.
        final List<DirectoryEntry> dots = new ArrayList<>();
        final List<DirectoryEntry> rest = new ArrayList<>();
        for (DirectoryEntry entry : entries) {
            final String name = entry.getName() != null ? entry.getName() : entry.extractName();
            if (".".equals(name) || "..".equals(name))
                dots.add(entry);
            else
                rest.add(entry);
        }
        if (reverse)
            Collections.reverse(rest);
        final List<DirectoryEntry> ordered = new ArrayList<>(dots);
        if (getDirectoriesFirst()) {
            // Directories in a pass of their own, so they're on top.
            for (DirectoryEntry entry : rest)
                if (entry.isDirectory())
                    ordered.add(entry);
            for (DirectoryEntry entry : rest)
                if (!entry.isDirectory())
                    ordered.add(entry);
        } else
            ordered.addAll(rest);
        for (DirectoryEntry entry : ordered)
            appendLine(entry);
        if (getFirstLine() == null)
            appendLine("");
        setLoaded(true);
    }

    public static void dirCycleSortBy() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof DirectoryBuffer directoryBuffer) {
            editor.setWaitCursor();
            directoryBuffer.cycleSortBy();
            editor.setDefaultCursor();
        }
    }

    private synchronized void cycleSortBy() {
        if (sortBy == SORT_BY_NAME)
            sortBy = SORT_BY_DATE;
        else if (sortBy == SORT_BY_DATE)
            sortBy = SORT_BY_SIZE;
        else
            sortBy = SORT_BY_NAME;
        resort();
        updateHints();
    }

    public void resort(int sortBy) {
        if (this.sortBy != sortBy) {
            this.sortBy = sortBy;
            resort();
            updateHints();
        }
    }

    // The hint says what the listing is sorted by, and the filter bar its
    // sort, limit and hidden files.
    private void updateHints() {
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() == this) {
                ed.updateHint();
                ed.updateFilterBar();
            }
        }
    }

    // The files' total under the sizes' column, as the listing writes sizes.
    private void appendTotal() {
        final long totalSize = getTotalSize();
        if (totalSize <= 0)
            return;
        int end = getFileSizeEndOffset();
        if (end <= 0)
            end = usingNativeFormat ? 45 : 24;
        final String s = style.size(totalSize);
        final int begin = Math.max(0, end - s.length());
        appendLine(Utilities.spaces(begin) + "-".repeat(s.length()));
        appendLine(Utilities.spaces(begin) + s);
    }

    /**
     * The entries read laid out again, in order, as reverse and directories
     * first change only that: nothing read again. With style, j's own
     * listing's sizes and dates are written again too. The caret stays on
     * its name in each window.
     */
    private void rearrange(ListingStyle style) {
        final Map<Editor, String> names = new HashMap<>();
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == this && ed.getDotLine() != null)
                names.put(ed, getName(ed.getDotLine()));
        if (style != null)
            for (DirectoryEntry entry : entries)
                entry.setStyle(style);
        try {
            lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return; // Shouldn't happen.
        }
        try {
            empty();
            addEntriesToBuffer();
            appendTotal();
            renumber();
        }
        finally {
            unlockWrite();
        }
        for (Editor ed : Editor.getEditorList())
            if (ed.getBuffer() == this) {
                final Line line = findName(names.get(ed));
                final Line at = line != null ? line : getFirstLine();
                // The lines are new: the old top is gone.
                ed.setTopLine(getFirstLine());
                ed.setDot(at, getNameOffset(at));
                ed.setMark(null);
                ed.moveCaretToDotCol();
                ed.setUpdateFlag(REFRAME | REPAINT);
            }
    }

    private void resort() {
        if (usingNativeFormat) {
            keepingTags(this::reload);
        } else {
            sort();
            try {
                lockWrite();
            }
            catch (InterruptedException e) {
                Log.error(e);
                return; // Shouldn't happen.
            }
            try {
                empty();
                addEntriesToBuffer();
                renumber();
            }
            finally {
                unlockWrite();
            }
            for (Editor ed : Editor.getEditorList()) {
                if (ed.getBuffer() == this) {
                    ed.setDot(getFirstLine(), 0);
                    ed.setMark(null);
                    final Display display = ed.getDisplay();
                    display.setTopLine(getFirstLine());
                    display.setShift(0);
                    display.setCaretCol(0);
                    display.setUpdateFlag(REPAINT);
                    ed.updateLocation();
                }
            }
        }
    }

    private synchronized void loadInternal() {
        // Default is true for Unix, false otherwise. User can override default.
        boolean useNativeFormat =
                preferences.getBooleanProperty(Property.DIR_USE_NATIVE_FORMAT, Platform.isPlatformUnix());
        if (useNativeFormat) {
            if (!Utilities.haveLs())
                useNativeFormat = false;
        }
        loadError = false;
        try {
            final DirectoryFilenameFilter dff = limitPattern != null ? new DirectoryFilenameFilter(limitPattern) : null;
            final File file = getFile();
            final HiddenNames hidden = new HiddenNames(file);
            // Rules still being read, as svn's: listed again with them, if this is still the directory.
            hidden.whenRead(() -> {
                if (file.equals(getFile()) && Editor.getBufferList().contains(this))
                    relist(() -> {});
            });
            if (useNativeFormat || file.isRemote()) {
                usingNativeFormat = true;
                String flags = "-la"; // Default is sort by name.
                if (sortBy == SORT_BY_DATE)
                    flags = "-lat";
                else if (sortBy == SORT_BY_SIZE)
                    flags = "-laS";
                // -h is GNU's, the BSDs' and busybox's; --time-style GNU's alone.
                if (style.human())
                    flags += "h";
                // An age is written over a long-iso date, which has the year and the time.
                if (isGnuLs())
                    flags += " --time-style="
                            + (style.timeStyle().equals(TimeStyle.AGE) ? TimeStyle.LONG_ISO : style.timeStyle());
                String extraOptions = getStringProperty(Property.LS_EXTRA_OPTIONS);
                if (extraOptions != null) {
                    flags += " ";
                    flags += extraOptions;
                    Log.debug("DirectoryBuffer.loadInternal flags = " + flags);
                }
                BufferedReader reader = null;
                if (getListing() != null) {
                    reader = new BufferedReader(new StringReader(getListing()));
                } else if (file instanceof FtpFile || file instanceof SshFile) {
                    setListing(file.getDirectoryListing());
                    if (getListing() != null)
                        reader = new BufferedReader(new StringReader(getListing()));
                    else
                        loadError = true;
                } else {
                    // Local file.
                    final ProcessRunner runner;
                    if (Platform.isPlatformUnix()) {
                        // flags is the user's ls preference, so the shell reads it.
                        runner = ProcessRunner.shell("\\ls " + flags).directory(file);
                    } else {
                        // Windows.
                        String cp = file.canonicalPath();
                        // Convert "C:\" into "//c" for Cygwin ls.
                        if (cp.length() == 3 && cp.charAt(1) == ':' && cp.charAt(2) == '\\')
                            cp = "//" + Character.toLowerCase(cp.charAt(0));
                        runner = ProcessRunner.of("ls", flags, cp);
                    }
                    // What ls lists, though it may say it couldn't read some; a hung one given up.
                    final ProcessRunner.Result result =
                            runner.charset(StandardCharsets.UTF_8).discardErrors().timeout(LS_TIMEOUT).run();
                    if (result.exitValue() == -1)
                        loadError = true;
                    reader = new BufferedReader(new StringReader(result.output()));
                }
                if (reader != null) {
                    final long now = System.currentTimeMillis();
                    String s;
                    while ((s = reader.readLine()) != null) {
                        DirectoryEntry entry = DirectoryEntry.getDirectoryEntry(s, dff);
                        if (entry == null)
                            continue;
                        if (style.timeStyle().equals(TimeStyle.AGE))
                            entry.writeAge(now);
                        entry.setHidden(hidden.kind(entry.extractName(), entry.isDirectory()));
                        if (showHidden || entry.getHidden() == HiddenNames.SHOWN)
                            entries.add(entry);
                    }
                }
            } else {
                usingNativeFormat = false;
                boolean dirIsRoot = false;
                if (Platform.isPlatformWindows()) {
                    String cp = file.canonicalPath();
                    if (cp.length() == 3 && cp.endsWith(":\\")) // "C:\"
                        dirIsRoot = true;
                }
                if (!dirIsRoot) {
                    addEntry(".");
                    addEntry("..");
                }
                String[] names = file.list();
                if (names != null) {
                    for (String name : names) {
                        final DirectoryEntry de = addEntry(name);
                        if (de == null)
                            continue;
                        de.setHidden(hidden.kind(name, de.isDirectory()));
                        // A limit leaves directories in.
                        if ((!showHidden && de.getHidden() != HiddenNames.SHOWN)
                                || (dff != null && !de.isDirectory() && !dff.accepts(name)))
                            entries.remove(entries.size() - 1);
                    }
                }
                sort();
            }
            try {
                lockWrite();
            }
            catch (InterruptedException e) {
                Log.error(e);
                return; // Shouldn't happen.
            }
            try {
                addEntriesToBuffer();
                appendTotal();
                renumber();
            }
            finally {
                unlockWrite();
            }
        }
        catch (IOException | RuntimeException e) {
            Log.error(e);
        }
    }

    private long getTotalSize() {
        // The files' bytes: a directory's size is its own inode's, . and .. too.
        long totalSize = 0;
        for (DirectoryEntry entry : entries) {
            if (entry.isDirectory())
                continue;
            long size = entry.getSize();
            if (size < 0) {
                // ls's, read from its line.
                size = entry.nativeSize();
                if (size < 0)
                    return -1;
                entry.setSize(size);
            }
            totalSize += size;
        }
        return totalSize;
    }

    @Override
    public int load() {
        if (!isLoaded()) {
            final Editor editor = Editor.currentEditor();
            String reading = "Reading directory...";
            if (editor != null)
                editor.status(reading);
            loadInternal();
            if (editor != null)
                editor.status(reading + "done");
        }
        return LOAD_COMPLETED;
    }

    public void tagFileAtDot() {
        final Editor editor = Editor.currentEditor();
        Line line = editor.getDotLine();
        if (!(line instanceof DirectoryLine))
            return;
        String name = getName(line);
        if (name == null)
            return;
        if (name.equals("."))
            return;
        if (name.equals(".."))
            return;
        DirectoryEntry de = null;
        if (usingNativeFormat) {
            // Text of line has "T " or "  " prepended to DirectoryEntry string.
            de = findNativeEntry(line.substring(2));
        } else {
            de = findEntry(name);
        }
        if (de != null) {
            if (de.isMarked()) {
                de.setMarked(false);
                --numMarked;
            } else {
                de.setMarked(true);
                ++numMarked;
            }
            line.setText(de.toString());
            editor.update(line);
            MotionCommands.down(editor);
            resetUndo();
        }
    }

    public void upDir() {
        final Editor editor = Editor.currentEditor();
        editor.setWaitCursor();
        final File parent = getFile().getParentFile();
        if (parent == null)
            return;
        history.truncate();
        history.append(getFile(), getName(editor.getDotLine()), editor.getDotOffset());
        history.reset();
        String name = getFile().getName();
        setFile(parent);
        reload();
        Line line = findName(name);
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() == this) {
                if (line != null)
                    ed.setDot(line, getNameOffset(line));
                else
                    ed.setDot(getFirstLine(), getNameOffset(getFirstLine()));
                ed.moveCaretToDotCol();
            }
        }
        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_REPAINT_BUFFER_LIST);
    }

    public String getPathAtDot() {
        Editor editor = Editor.currentEditor();
        if (!(editor.getDotLine() instanceof DirectoryLine))
            return null;
        String name = getName(editor.getDotLine());
        if (name == null)
            return null;
        final File dir = getFile();
        if (name.equals("."))
            return dir.netPath();
        if (name.equals("..")) {
            File parent = dir.getParentFile();
            if (parent == null)
                return null;
            else
                return parent.netPath();
        }
        File file = File.getInstance(dir, name);
        if (file == null)
            return null;
        else
            return file.netPath();
    }

    public static void dir() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof DirectoryBuffer)
            return;
        File directory = editor.getCurrentDirectory();
        if (directory == null)
            return;
        if (directory.getProtocol() == File.PROTOCOL_HTTP)
            return;
        // FTP, SSH or local.
        Buffer buf = Editor.getBuffer(directory);
        // buf may be a RemoteBuffer and not specifically a Directory.
        if (buf != null) {
            File file = buffer.getFile();
            editor.makeNext(buf);
            editor.switchToBuffer(buf);
            if (buf instanceof DirectoryBuffer dir && file != null) {
                Line line = dir.findName(file.getName());
                if (line != null) {
                    editor.setDot(new Position(line, dir.getNameOffset(line)));
                    editor.setUpdateFlag(REFRAME);
                    editor.reframe();
                }
            }
        }
    }

    public static void dirOpenFile() {
        _dirOpenFile(false);
    }

    /** Shift Enter: the file or directory at the caret in a new window over the listing, as vim's :split. */
    public static void dirOpenFileInSplit() {
        if (Editor.currentEditor().getBuffer() instanceof DirectoryBuffer d)
            d.openAtDotInSplit(false);
    }

    /** Ctrl Enter: the same beside the listing, on the left, as vim's :vsplit. */
    public static void dirOpenFileInVsplit() {
        if (Editor.currentEditor().getBuffer() instanceof DirectoryBuffer d)
            d.openAtDotInSplit(true);
    }

    private void openAtDotInSplit(boolean vertical) {
        final Editor editor = Editor.currentEditor();
        if (editor.getFrame() == null || !(editor.getDotLine() instanceof DirectoryLine))
            return;
        final String name = getName(editor.getDotLine());
        if (name == null || name.equals("."))
            return;
        if (getFile().isRemote()) {
            editor.status("Not in a remote directory");
            return;
        }
        final File f = File.getInstance(getFile(), name);
        if (f == null || !f.exists()) {
            editor.status("File not found");
            return;
        }
        // A directory as a listing of its own, not this one changed.
        final Buffer buf = Editor.getBuffer(f);
        if (buf == null)
            return;
        editor.recordJump();
        // The caret goes to the new window, top or left; the listing stays in the other.
        if (vertical)
            WindowCommands.vsplitWindow(editor, "vim");
        else
            WindowCommands.splitWindow(editor, "vim");
        final Editor top = Editor.currentEditor();
        top.makeNext(buf);
        top.switchToBuffer(buf);
    }

    public static void dirOpenFileAndKillDirectory() {
        _dirOpenFile(true);
    }

    private static void _dirOpenFile(boolean killDirectory) {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof DirectoryBuffer directoryBuffer) {
            // If this method is invoked via a mouse event mapping, move dot to
            // location of mouse click first.
            AWTEvent e = editor.getDispatcher().getLastEvent();
            if (e instanceof MouseEvent mouseEvent)
                editor.mouseMoveDotToPoint(mouseEvent);
            directoryBuffer.openFileAtDot(killDirectory);
        }
    }

    private synchronized void openFileAtDot(boolean killDirectory) {
        final Editor editor = Editor.currentEditor();
        editor.setWaitCursor();
        if (!(editor.getDotLine() instanceof DirectoryLine))
            return;
        String name = getName(editor.getDotLine());
        if (name == null)
            return;
        if (name.equals("."))
            return;
        if (name.equals("..")) {
            upDir();
            return;
        }
        final File dir = getFile();
        if (dir.isRemote()) {
            String fullpath = dir.canonicalPath();
            if (!fullpath.endsWith("/"))
                fullpath += "/";
            fullpath += name;
            File newFile = File.getInstance(dir, fullpath);
            Buffer buf = Editor.getBufferList().findBuffer(newFile);
            if (buf != null) {
                editor.makeNext(buf);
                editor.activate(buf);
                return;
            }
            DirectoryLine line = (DirectoryLine) editor.getDotLine();
            DirectoryEntry de = line.getDirectoryEntry();
            boolean isDirectory = false;
            if (de.isDirectory())
                isDirectory = true;
            else if (de.isLink())
                isDirectory = newFile.isDirectory();
            if (isDirectory) {
                setBusy(true);
                history.truncate();
                history.append(dir, getName(editor.getDotLine()), editor.getDotOffset());
                history.reset();
                empty();
                entries.clear();
                numMarked = 0;
                setListing(null);
                for (Editor ed : Editor.getEditorList()) {
                    if (ed.getBuffer() == this) {
                        ed.setTopLine(null);
                        ed.setDot(null);
                        ed.setMark(null);
                    }
                }
                setFile(newFile);
                Runnable reloadRunnable = () -> {
                    load();
                    Runnable updateRunnable = () -> {
                        setBusy(false);
                        for (Editor ed : Editor.getEditorList()) {
                            if (ed.getBuffer() == DirectoryBuffer.this) {
                                ed.setTopLine(getFirstLine());
                                ed.setDot(getInitialDotPos());
                                ed.setMark(null);
                                ed.moveCaretToDotCol();
                                ed.setUpdateFlag(REPAINT);
                                ed.updateDisplay();
                                ed.updateLocation();
                            }
                        }
                        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_ALL);
                    };
                    SwingUtilities.invokeLater(updateRunnable);
                };
                Background.start("DirectoryBuffer reload", reloadRunnable);
            } else {
                // Not a directory.
                if (newFile instanceof FtpFile ftpFile)
                    buf = new RemoteBuffer(ftpFile, FtpSession.getSession(ftpFile));
                else if (newFile instanceof SshFile)
                    buf = new RemoteBuffer(newFile);
                else
                    Debug.assertTrue(false);
                editor.recordJump();
                editor.makeNext(buf);
                editor.activate(buf);
            }
            return;
        }

        // Local file.
        File f = File.getInstance(getFile(), name);
        if (!f.exists()) {
            editor.status("File not found");
            return;
        }
        if (f.isDirectory()) {
            changeDirectory(f);
            return;
        }
        Buffer buf = Editor.getBuffer(f);
        if (buf != null) {
            // A jump, so that CTRL-O comes back to the listing.
            editor.recordJump();
            editor.makeNext(buf);
            editor.activate(buf);
        }
        if (killDirectory)
            kill();
    }

    public synchronized void changeDirectory(File f) {
        if (f.isDirectory()) {
            final Editor editor = Editor.currentEditor();
            if (f.isLocal() && !f.canRead()) {
                showMessageDialog("Directory is not readable");
                return;
            }
            final String name;
            final int offset;
            if (editor.getDot() != null) {
                name = getName(editor.getDotLine());
                offset = editor.getDotOffset();
            } else {
                name = null;
                offset = 0;
            }
            history.truncate();
            history.append(getFile(), name, offset);
            history.reset();
            empty();
            entries.clear();
            numMarked = 0;
            setListing(null);
            setFile(f);
            load();
            for (Editor ed : Editor.getEditorList()) {
                if (ed.getBuffer() == this) {
                    ed.setTopLine(getFirstLine());
                    ed.setDot(getInitialDotPos());
                    ed.setMark(null);
                    ed.moveCaretToDotCol();
                    ed.setUpdateFlag(REPAINT);
                    ed.updateLocation();
                }
            }
            Sidebar.setUpdateFlagInAllFrames(SIDEBAR_REPAINT_BUFFER_LIST);
        }
    }

    /**
     * Lists dir in place, the back and forward history left as it is, as a
     * jump back to it does; false if it isn't a directory j can read.
     */
    public boolean goTo(File dir) {
        if (dir == null || !dir.isDirectory() || (dir.isLocal() && !dir.canRead()))
            return false;
        changeDirectory(new DirectoryHistoryEntry(dir, null, 0));
        return true;
    }

    // No history.
    private synchronized void changeDirectory(DirectoryHistoryEntry entry) {
        empty();
        entries.clear();
        numMarked = 0;
        setListing(null);
        setFile(entry.file);
        load();
        Line line = findName(entry.name);
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() == this) {
                ed.getDisplay().setTopLine(getFirstLine());
                ed.setUpdateFlag(REPAINT);
                if (line != null)
                    ed.setDot(line, entry.offset);
                else
                    ed.setDot(getFirstLine(), getNameOffset(getFirstLine()));
                ed.setMark(null);
                ed.moveCaretToDotCol();
                ed.updateLocation();
            }
        }
        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_REPAINT_BUFFER_LIST);
    }

    public static void dirBack() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof DirectoryBuffer directoryBuffer) {
            editor.setWaitCursor();
            directoryBuffer.back(editor);
            editor.setDefaultCursor();
        }
    }

    private void back(Editor editor) {
        boolean atEnd = history.atEnd();
        DirectoryHistoryEntry entry = history.getPrevious();
        if (entry != null) {
            if (atEnd) {
                String name = getName(editor.getDotLine());
                int offset = editor.getDotOffset();
                history.append(getFile(), name, offset);
            }
            changeDirectory(entry);
        } else
            editor.status("Can't go back");
    }

    public static void dirForward() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof DirectoryBuffer directoryBuffer) {
            editor.setWaitCursor();
            directoryBuffer.forward(editor);
            editor.setDefaultCursor();
        }
    }

    private void forward(Editor editor) {
        DirectoryHistoryEntry entry = history.getNext();
        if (entry != null)
            changeDirectory(entry);
        else
            editor.status("Can't go forward");
    }

    public void browseFileAtDot() {
        Editor editor = Editor.currentEditor();
        String name = getName(editor.getDotLine());
        if (name == null)
            return;
        File f = File.getInstance(getFile(), name);
        if (!f.exists()) {
            editor.status("File not found");
            return;
        }
        if (f.isFile())
            BrowseFile.openUrl("file://".concat(f.canonicalPath()));
    }

    public void deleteFiles() {
        if (numMarked > 0)
            deleteMarkedFiles();
        else
            deleteFileAtDot();
    }

    private void showMessageDialog(String message) {
        MessageDialog.showMessageDialog(message, "Error");
    }

    private synchronized void deleteMarkedFiles() {
        if (numMarked == 0)
            return;
        Debug.assertTrue(numMarked > 0);
        String message = null;
        final Editor editor = Editor.currentEditor();
        if (numMarked > 1)
            message = "Delete " + numMarked + " tagged files?";
        else
            message = "Delete 1 tagged file?";
        boolean confirmed = editor.confirm("Delete Files", message);
        if (!confirmed)
            return;
        boolean directoryWasDeleted = false;
        FtpSession session = null;
        for (DirectoryEntry entry : entries) {
            if (!entry.isMarked())
                continue;
            String name = entry.extractName();
            if (name == null) {
                // Shouldn't happen.
                Debug.assertTrue(false);
                continue;
            }
            File file = File.getInstance(getFile(), name);
            if (!file.isRemote() && !file.exists()) {
                showMessageDialog(file.canonicalPath() + " not found");
                return;
            }
            String displayName = file.canonicalPath();
            boolean isDirectory;
            if (file.isRemote()) {
                displayName += " on " + file.getHostName();
                isDirectory = entry.isDirectory();
            } else
                isDirectory = file.isDirectory();
            boolean succeeded = false;
            if (file instanceof FtpFile ftpFile) {
                if (session == null)
                    session = FtpSession.getSession(ftpFile);
                if (session != null) {
                    if (isDirectory)
                        succeeded = session.removeDirectory(file.canonicalPath());
                    else
                        succeeded = session.deleteFile(file.canonicalPath());
                }
            } else {
                // Local file.
                // Delete the file on disk.
                file.delete();

                // Did it work?
                succeeded = !file.exists();
            }
            if (succeeded) {
                if (isDirectory)
                    directoryWasDeleted = true;
            } else {
                // Deletion failed.
                if (isDirectory) {
                    confirmed = editor
                            .confirm("Delete Files", "Unable to remove directory " + displayName + ".  Continue?");
                } else {
                    confirmed = editor.confirm("Delete Files", "Unable to delete " + displayName + ".  Continue?");
                }
                if (!confirmed)
                    break;
            }
        }
        if (session != null)
            session.unlock();
        if (directoryWasDeleted) {
            rescan();
        } else {
            if (getFile().isRemote())
                DirectoryCache.getDirectoryCache().purge(getFile());
            reload();
        }
    }

    private void deleteFileAtDot() {
        final Editor editor = Editor.currentEditor();
        String name = getName(editor.getDotLine());
        if (name == null)
            return;
        if (name.equals("."))
            return;
        if (name.equals(".."))
            return;
        File file = File.getInstance(getFile(), name);
        if (!file.isRemote() && !file.exists()) {
            showMessageDialog("File not found");
            return;
        }
        String displayName = file.getName();
        final boolean isDirectory;
        if (file.isRemote()) {
            displayName += " on " + file.getHostName();
            isDirectory = editor.getDotLine().getText().trim().startsWith("d");
        } else
            isDirectory = file.isDirectory();
        final String title, prompt;
        if (isDirectory) {
            title = "Remove Directory";
            prompt = "Remove directory " + displayName + "?";
        } else {
            title = "Delete File";
            prompt = "Delete " + displayName + "?";
        }
        if (!editor.confirm(title, prompt))
            return;
        boolean succeeded = false;
        editor.setWaitCursor();
        if (file instanceof FtpFile ftpFile) {
            FtpSession session = FtpSession.getSession(ftpFile);
            if (session != null) {
                if (isDirectory)
                    succeeded = session.removeDirectory(file.canonicalPath());
                else
                    succeeded = session.deleteFile(file.canonicalPath());
                session.unlock();
            }
        } else {
            // Local file.
            file.delete();
            succeeded = !file.exists();
        }
        if (succeeded) {
            if (isDirectory) {
                rescan();
            } else {
                if (getFile().isRemote())
                    DirectoryCache.getDirectoryCache().purge(getFile());
                reload();
            }
        }
        editor.setDefaultCursor();
        if (!succeeded) {
            if (isDirectory)
                showMessageDialog("Unable to remove directory " + displayName);
            else
                showMessageDialog("Unable to delete " + displayName);
        }
    }

    public static void dirDoShellCommand() {
        if (!Editor.checkExperimental())
            return;
        if (!Platform.isPlatformUnix())
            return;
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof DirectoryBuffer directoryBuffer) {
            if (buffer.getFile().isRemote()) {
                MessageDialog.showMessageDialog(
                    editor,
                    "Shell commands are not supported in remote directory buffers.",
                    "Error");
                return;
            }
            directoryBuffer.doShellCommand(editor);
        }
    }

    private void doShellCommand(Editor editor) {
        List<String> names = getMarkedNames();
        if (names == null) {
            names = new ArrayList<>();
            names.add(getName(editor.getDotLine()));
        }
        String prompt = null;
        if (names.size() == 1)
            prompt = "Command (on " + names.get(0) + "):";
        else if (names.size() > 1)
            prompt = "Command (on " + names.size() + " tagged files):";
        else
            prompt = "Command:";
        InputDialog d = new InputDialog(editor, prompt, "Shell Command", null);
        d.setHistory(new History("dirDoShellCommand.command"));
        editor.centerDialog(d);
        d.setVisible(true);
        String command = d.getInput();
        if (command == null)
            return;
        command = command.trim();
        if (command.length() == 0)
            return;
        editor.setWaitCursor();
        editor.repaintNow();
        String output = null;
        if (command.indexOf('*') >= 0) {
            output = doCommandOnMultipleFiles(command, names);
        } else {
            StringBuilder sb = new StringBuilder();
            for (String filename : names) {
                String s = doCommandOnFile(command, filename);
                if (s != null && s.length() > 0)
                    sb.append(s);
            }
            output = sb.toString();
        }
        reload();
        if (output != null && output.length() > 0) {
            OutputBuffer buf = OutputBuffer.getOutputBuffer(output);
            if (buf != null) {
                buf.setTitle(command);
                editor.makeNext(buf);
                editor.displayInOtherWindow(buf);
            }
        }
        editor.setDefaultCursor();
    }

    private String doCommandOnFile(String command, String filename) {
        StringBuilder sb = new StringBuilder(command);
        sb.append(' ');
        sb.append(Utilities.maybeQuoteFile(filename));
        ShellCommand shellCommand = new ShellCommand(sb.toString(), getFile());
        shellCommand.run();
        return shellCommand.getOutput();
    }

    private String doCommandOnMultipleFiles(String command, List<String> files) {
        if (files.size() < 1)
            return null;
        String before, after;
        int index = command.indexOf('*');
        if (index >= 0) {
            before = command.substring(0, index).trim();
            after = command.substring(index + 1).trim();
        } else {
            before = command.trim();
            after = "";
        }
        if (before.length() == 0) {
            showMessageDialog("No command specified");
            return null;
        }
        StringBuilder sb = new StringBuilder(before);
        for (String file : files) {
            sb.append(' ');
            String filename = file;
            sb.append(Utilities.maybeQuoteFile(filename));
        }
        if (after.length() > 0) {
            sb.append(' ');
            sb.append(after);
        }
        ShellCommand shellCommand = new ShellCommand(sb.toString(), getFile());
        shellCommand.run();
        return shellCommand.getOutput();
    }

    private synchronized List<String> getMarkedNames() {
        if (numMarked > 0) {
            ArrayList<String> names = new ArrayList<>(numMarked);
            final int size = entries.size();
            for (DirectoryEntry de : entries) {
                if (de.isMarked()) {
                    String name = de.extractName();
                    if (name != null)
                        names.add(name);
                }
            }
            return names;
        }
        return null;
    }

    public void copyFileAtDot() {
        copyFiles();
    }

    public void moveFileAtDot() {
        moveFiles();
    }

    private List<File> getSourceFiles(Editor editor) {
        ArrayList<File> sources = new ArrayList<>();
        if (numMarked > 0) {
            List<String> names = getMarkedNames();
            for (String name : names) {
                if (name != null)
                    sources.add(File.getInstance(getFile(), name));
            }
        } else if (editor.getDotLine() instanceof DirectoryLine) {
            String name = getName(editor.getDotLine());
            if (name != null)
                sources.add(File.getInstance(getFile(), name));
        }
        return sources;
    }

    private File getDestinationForCopy(Editor editor, List<File> sources) {
        return getDestination(editor, sources, "Copy");
    }

    private File getDestinationForMove(Editor editor, List<File> sources) {
        return getDestination(editor, sources, "Move");
    }

    private File getDestination(Editor editor, List<File> sources, String operation) {
        String title = operation + " File";
        String prompt = operation + " ";
        String name = null;
        if (sources.size() == 1) {
            File source = sources.get(0);
            name = source.getName();
            prompt += name;
        } else
            prompt += String.valueOf(sources.size()) + " tagged files";
        prompt += " to: ";
        CopyFileDialog d = new CopyFileDialog(editor, title, prompt, name);
        editor.centerDialog(d);
        d.setVisible(true);
        editor.repaintNow();
        return d.getDestination();
    }

    private void copyFiles() {
        final Editor editor = Editor.currentEditor();
        List<File> sources = getSourceFiles(editor);
        File destination = getDestinationForCopy(editor, sources);
        if (destination == null)
            return;
        final String title = "Copy Files";
        if (destination.isLocal())
            copyLocalToLocal(sources, destination, editor, title);
        else
            MessageDialog.showMessageDialog(editor, "Destination must be local!", title);
    }

    private void copyLocalToLocal(List<File> sources, File destination, Editor editor, String title) {
        int numFilesCopied = 0;
        boolean mustConfirm = true;
        boolean cancelled = false;
        final int limit = sources.size();
        File destDir = destination.isDirectory() ? destination : destination.getParentFile();
        for (int i = 0; i < limit; i++) {
            File from = sources.get(i);
            File to;
            if (destination.isDirectory())
                to = File.getInstance(destination, from.getName());
            else
                to = destination;
            if (mustConfirm && to.isFile()) {
                String message = "Overwrite existing file " + to.canonicalPath() + "?";
                if (i < limit - 1) {
                    // More than one file is left. Provide "Yes To All" and
                    // "Cancel" buttons.
                    int response = editor.confirmAll(title, message);
                    switch (response) {
                        case RESPONSE_YES:
                            break;
                        case RESPONSE_NO:
                            continue;
                        case RESPONSE_YES_TO_ALL:
                            mustConfirm = false;
                            break;
                        case RESPONSE_CANCEL:
                            cancelled = true;
                            break;
                        default:
                            break;
                    }
                    if (cancelled)
                        break;
                } else if (!editor.confirm(title, message)) {
                    // Last file (or only file).
                    break;
                }
            }
            if (Utilities.copyFile(from, to)) {
                ++numFilesCopied;
            } else {
                String text = "Unable to copy " + from.getName() + " to " + to.canonicalPath() + ".";
                if (i < limit - 1) {
                    text += " Continue?";
                    if (!editor.confirm(title, text))
                        break;
                } else
                    MessageDialog.showMessageDialog(editor, text, title);
            }
        }
        String statusText = String.valueOf(numFilesCopied) + " file";
        if (numFilesCopied > 1)
            statusText += 's';
        statusText += " copied";
        editor.status(statusText);
        for (Editor ed : Editor.getEditorList()) {
            if (destDir.equals(ed.getBuffer().getFile())) {
                ((DirectoryBuffer) ed.getBuffer()).reload();
                if (ed != Editor.currentEditor())
                    ed.updateDisplay();
            }
        }
    }

    private void moveFiles() {
        final Editor editor = Editor.currentEditor();
        List<File> sources = getSourceFiles(editor);
        File destination = getDestinationForMove(editor, sources);
        if (destination == null)
            return;
        Log.debug("ready to move");
        if (destination.isRemote()) {
            MessageDialog.showMessageDialog(editor, "Destination must be local!", "Move Files");
            return;
        }
        final int count = sources.size();
        for (File source : sources) {
            // Move.
            boolean success = source.renameTo(destination);

            if (!success) {
                Log.warn("renameTo failed; trying copyFile...");
                success = Utilities.copyFile(source, destination);
                if (success) {
                    Log.debug("copyFile succeeded, deleting source...");
                    source.delete();
                    success = !source.exists();
                } else
                    Log.error("copyFile failed");
            } else
                Log.debug("renameTo succeeded");

            if (success) {
                // Change file information for any buffers (there should only
                // be one!) associated with moved file.
                for (Buffer buf : Editor.getBufferList()) {
                    if (source.equals(buf.getFile()))
                        buf.changeFile(destination);
                }
            } else {
                MessageDialog.showMessageDialog(editor, "Move failed", "Error");
                break;
            }
        }

        // Current directory may have changed.
        reload();
    }

    public void getFileAtDot() {
        final Editor editor = Editor.currentEditor();
        final Line dotLine = editor.getDotLine();
        final String name = getName(dotLine);
        if (name == null)
            return;

        final FtpFile sourceFile = (FtpFile) File.getInstance(getFile(), name);
        if (sourceFile == null)
            return;

        int status = -1;

        // We'll get in trouble here if the directory format changes.
        if (editor.getDotLine().length() >= 3) {
            char c = dotLine.charAt(2);
            if (c == 'd') {
                status = 2; // Directory.
            } else if (c == '-') {
                status = 1; // Normal file.
            }
        }

        if (status == -1) {
            // Symbolic link.
            MessageDialog.showMessageDialog("Unknown file type", "Get File");
            return;
        }
        if (status == 2) {
            MessageDialog.showMessageDialog(sourceFile.canonicalPath() + " is a directory", "Get File");
            return;
        }
        if (status != 1) {
            MessageDialog.showMessageDialog("File not found", "Get File");
            return;
        }

        CopyFileDialog d = new CopyFileDialog(editor, "Get File", "Destination:", name);
        d.setConfirmOverwrite(true);
        editor.centerDialog(d);
        d.setVisible(true);
        final File destination = d.getDestination();
        editor.repaintNow();
        if (destination == null)
            return;
        if (destination.isRemote()) {
            MessageDialog.showMessageDialog(editor, "Destination must be local!", title);
            return;
        }
        final File destinationFile = destination.isDirectory() ? File.getInstance(destination, name) : destination;
        final FtpSession session = FtpSession.getSession((FtpFile) getFile());
        if (session == null)
            return;
        final long fileSize = getFileSize(dotLine);
        final FtpLoadProcess loadProcess = new FtpLoadProcess(this, sourceFile, session);
        final Runnable successRunnable = () -> {
            File cache = loadProcess.getCache();
            if (cache != null)
                Utilities.deleteRename(cache, destination);
            for (Editor ed : Editor.getEditorList()) {
                if (ed.getBuffer() == DirectoryBuffer.this)
                    ed.setDefaultCursor();
            }
        };
        final ErrorRunnable errorRunnable = new ErrorRunnable("Operation failed") {
            @Override
            public void run() {
                File cache = loadProcess.getCache();
                if (cache != null && cache.isFile())
                    cache.delete();
                for (Editor ed : Editor.getEditorList()) {
                    if (ed.getBuffer() == DirectoryBuffer.this)
                        ed.setDefaultCursor();
                }
                String text = session.getErrorText();
                if (text == null || text.length() == 0)
                    text = "Unable to retrieve " + name;
                MessageDialog.showMessageDialog(text, "Error");
            }
        };
        loadProcess.setSuccessRunnable(successRunnable);
        loadProcess.setErrorRunnable(errorRunnable);
        loadProcess.setProgressNotifier(new StatusBarProgressNotifier(this));
        loadProcess.start();
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public String getTitle() {
        File dir = getFile();
        title = dir.canonicalPath();
        if (limitPattern != null) {
            title += LocalFile.getSeparator();
            title += limitPattern;
        }
        title += " (sorted by ";
        if (sortBy == SORT_BY_NAME)
            title += "name)";
        else if (sortBy == SORT_BY_DATE)
            title += "date)";
        else if (sortBy == SORT_BY_SIZE)
            title += "size)";
        if (dir.isRemote())
            title += "   " + dir.getHostName();
        return title;
    }

    // The entry's name; a line of another kind's by the pattern ls's dates match.
    private String getName(Line line) {
        if (line instanceof DirectoryLine dl) {
            final DirectoryEntry entry = dl.getDirectoryEntry();
            return entry.getName() != null ? entry.getName() : entry.extractName();
        }
        return getName(line.getText());
    }

    private String getName(String s) {
        // Strip symbolic link (if any) from end of line.
        int end = s.indexOf(" ->");

        if (end >= 0)
            s = s.substring(0, end);

        Matcher matcher;

        if (usingNativeFormat)
            matcher = nativeMoveToFilenameRegExp.matcher(s);
        else
            matcher = internalMoveToFilenameRegExp.matcher(s);

        if (matcher.find())
            return s.substring(matcher.end());

        return null;
    }

    private long getFileSize(Line line) {
        if (usingNativeFormat && line instanceof DirectoryLine dl)
            return Math.max(dl.getDirectoryEntry().nativeSize(), 0);
        return 0;
    }

    public void home() {
        final Editor editor = Editor.currentEditor();
        if (editor.getDotOffset() == 0)
            return;
        editor.addUndo(SimpleEdit.MOVE);
        MotionCommands.beginningOfBlock(editor);
        int offset = getNameOffset(editor.getDotLine());

        // If we're already at the first character of the name (or to the left
        // of it), go to column 0.
        if (editor.getDotOffset() <= offset)
            offset = 0;

        editor.getDot().setOffset(offset);
        editor.moveCaretToDotCol();
    }

    public static void chmod() {
        final Editor editor = Editor.currentEditor();
        if (!(editor.getDotLine() instanceof DirectoryLine))
            return;
        final Buffer buffer = editor.getBuffer();
        if (!(buffer instanceof DirectoryBuffer directory))
            return;
        String name = directory.getName(editor.getDotLine());
        if (name == null)
            return;
        final File file = File.getInstance(directory.getFile(), name);
        if (file == null)
            return;
        // Verify that operation is supported.
        if (file.isLocal()) {
            if (!Platform.isPlatformUnix())
                return;
        } else if (file.isRemote()) {
            int protocol = file.getProtocol();
            if (protocol != File.PROTOCOL_FTP && protocol != File.PROTOCOL_SSH)
                return;
        }
        StringBuilder sb = new StringBuilder("Change mode of ");
        sb.append(file.getName());
        sb.append(" to:");
        final String input = InputDialog.showInputDialog(editor, sb.toString(), "Change Mode");
        if (input == null || input.length() == 0)
            return;
        int n = 0;
        try {
            n = Integer.parseInt(input, 8);
        }
        catch (NumberFormatException e) {
            Log.error(e);
        }
        if (n == 0) {
            MessageDialog.showMessageDialog("Invalid mode string", "Change Mode");
            return;
        }
        final int permissions = n;
        if (file.isLocal()) {
            editor.setWaitCursor();
            file.setPermissions(permissions);
            editor.setDefaultCursor();
        } else if (file instanceof FtpFile ftpFile) {
            final FtpSession session = FtpSession.getSession(ftpFile);
            if (session != null) {
                final Runnable completionRunnable = () -> {
                    for (Editor ed : Editor.getEditorList()) {
                        if (ed.getBuffer() == directory)
                            ed.setDefaultCursor();
                    }
                };
                final Runnable chmodRunnable = () -> {
                    directory.setBusy(true);
                    for (Editor ed : Editor.getEditorList()) {
                        if (ed.getBuffer() == directory)
                            ed.setWaitCursor();
                    }
                    if (session.verifyConnected()) {
                        session.chmod(ftpFile, permissions);
                        session.unlock();
                    }
                    directory.setBusy(false);
                    SwingUtilities.invokeLater(completionRunnable);
                };
                Background.start("DirectoryBuffer chmod", chmodRunnable);
            }
        } else if (file instanceof SshFile sshFile) {
            final RemoteSession session = SshSession.getSession(sshFile);
            if (session != null) {
                final Runnable completionRunnable = () -> {
                    for (Editor ed : Editor.getEditorList()) {
                        if (ed.getBuffer() == directory)
                            ed.setDefaultCursor();
                    }
                };
                final Runnable chmodRunnable = () -> {
                    directory.setBusy(true);
                    for (Editor ed : Editor.getEditorList()) {
                        if (ed.getBuffer() == directory)
                            ed.setWaitCursor();
                    }
                    if (session.connect()) {
                        session.chmod(sshFile, permissions);
                        session.unlock();
                    }
                    directory.setBusy(false);
                    SwingUtilities.invokeLater(completionRunnable);
                };
                Background.start("DirectoryBuffer chmod", chmodRunnable);
            }
        }
    }

    private int getNameOffset(Line line) {
        if (line instanceof DirectoryLine dl && dl.getDirectoryEntry().nameColumn() >= 0)
            return dl.getDirectoryEntry().nameColumn();
        if (line != null) {
            Matcher matcher;
            if (usingNativeFormat)
                matcher = nativeMoveToFilenameRegExp.matcher(line.getText());
            else
                matcher = internalMoveToFilenameRegExp.matcher(line.getText());
            if (matcher.find())
                return matcher.end();
        }
        return 0;
    }

    private int getFileSizeEndOffset() {
        Line line = getFirstLine();
        if (line instanceof DirectoryLine dl && dl.getDirectoryEntry().sizeEndColumn() >= 0)
            return dl.getDirectoryEntry().sizeEndColumn();
        if (line != null) {
            final String text = line.getText();
            Matcher matcher;
            if (usingNativeFormat)
                matcher = nativeMoveToFilenameRegExp.matcher(text);
            else
                matcher = internalMoveToFilenameRegExp.matcher(text);
            if (matcher.find()) {
                int start = matcher.start();
                // The file size is followed by a single space.
                return text.indexOf(' ', start);
            }
        }
        return -1; // Error!
    }

    Line findName(String name) {
        if (name != null) {
            if (Platform.isPlatformWindows()) {
                // Case-insensitive filesystem.
                name = name.toLowerCase(Locale.ROOT);
                for (Line line = getFirstLine(); line != null; line = line.next()) {
                    String text = line.getText().toLowerCase(Locale.ROOT);
                    if (text.contains(name)) // Performance!
                        if (name.equalsIgnoreCase(getName(line)))
                            return line;
                }
            } else {
                for (Line line = getFirstLine(); line != null; line = line.next()) {
                    if (line.getText().contains(name)) // Performance!
                        if (name.equals(getName(line)))
                            return line;
                }
            }
        }
        return null;
    }

    @Override
    public final String toString() {
        File file = getFile();
        if (file.isRemote()) {
            StringBuilder sb = new StringBuilder(file.canonicalPath());
            sb.append(" [");
            sb.append(file.netPath());
            sb.append(']');
            return sb.toString();
        }
        if (Platform.isPlatformUnix()) {
            String userHome = Utilities.getUserHome();
            if (userHome != null && userHome.length() > 0) {
                String s = file.canonicalPath();
                if (s.equals(userHome))
                    return "~";
                if (s.startsWith(userHome.concat("/")))
                    return "~".concat(s.substring(userHome.length()));
            }
        }
        return file.canonicalPath();
    }

    // For the buffer list.
    @Override
    public final Icon getIcon() {
        // A project's root has the project's icon, as on the toolbar.
        return Icons.getIconFromFile(ProjectRoot.isRoot(this, getFile()) ? "project" : "directory");
    }
}

class DirectoryHistory {
    private List<DirectoryHistoryEntry> v = new ArrayList<>();
    private int index = -1;

    DirectoryHistory() {}

    boolean atEnd() {
        return index == -1;
    }

    void truncate() {
        if (index != -1)
            v.subList(index, v.size()).clear();
    }

    void append(File file, String name, int offset) {
        v.add(new DirectoryHistoryEntry(file, name, offset));
    }

    DirectoryHistoryEntry getPrevious() {
        if (v.size() == 0)
            return null;
        if (index == -1)
            index = v.size();
        if (index > 0)
            return v.get(--index);
        return null;
    }

    DirectoryHistoryEntry getNext() {
        if (v.size() == 0)
            return null;
        if (index == -1)
            return null;
        if (index < v.size() - 1)
            return v.get(++index);
        return null;
    }

    public void reset() {
        index = -1;
    }
}

class DirectoryHistoryEntry {
    File file;
    String name;
    int offset;

    DirectoryHistoryEntry(File file, String name, int offset) {
        this.file = file;
        this.name = name;
        this.offset = offset;
    }
}
