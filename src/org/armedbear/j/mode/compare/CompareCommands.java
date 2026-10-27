/*
 * CompareCommands.java
 *
 * Copyright (C) 2026 Kevin Krouse
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

package org.armedbear.j.mode.compare;

import static org.armedbear.j.Constants.FILETYPE_BINARY;
import static org.armedbear.j.Constants.FILETYPE_GZIP;
import static org.armedbear.j.Constants.FILETYPE_JPEG;
import static org.armedbear.j.Constants.FILETYPE_ZIP;
import static org.armedbear.j.ToolBarIcon.*;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.FileCommands;
import org.armedbear.j.Frame;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Menu;
import org.armedbear.j.OpenFileDialog;
import org.armedbear.j.Property;
import org.armedbear.j.ToolBar;
import org.armedbear.j.util.Utilities;
import org.armedbear.j.util.Whitespace;

/** The commands of a comparison. */
public final class CompareCommands {
    private CompareCommands() {}

    /**
     * {@code diff [FILE]}: the buffer side by side with FILE, at the left,
     * following the buffer; with none, with its file's patchMode backup
     * if it has one, else with a file asked for.
     */
    public static void diff(Editor editor, String path) {
        final Buffer buffer = editor.getBuffer();
        if (buffer.getType() != Buffer.TYPE_NORMAL || buffer.getFileType() == FILETYPE_BINARY) {
            editor.status("Not a text file");
            return;
        }
        File other;
        if (path != null && !path.isBlank())
            other = FileCommands.fileNamed(editor, path.trim());
        else {
            final File file = buffer.getFile();
            other = file != null && file.isLocal() ? buffer.getPatchFile() : null;
            if (other == null || !other.isFile())
                other = OpenFileDialog.getLocalFile(editor, "Diff");
            if (other == null)
                return;
        }
        if (other == null || !other.isLocal() || !other.isFile()) {
            editor.status("No such file: " + (other != null ? other.netPath() : path.trim()));
            return;
        }
        final int type = Utilities.getFileType(other);
        if (type == FILETYPE_BINARY || type == FILETYPE_ZIP || type == FILETYPE_GZIP || type == FILETYPE_JPEG) {
            editor.status("Not a text file: " + other.canonicalPath());
            return;
        }
        final String title = buffer.getFile() != null ? buffer.getFile().canonicalPath() : buffer.getTitle();
        Following.open(
            editor,
            "diff|" + other.canonicalPath() + "|" + title,
            title + " (buffer)",
            new OtherFile(other, buffer.getFile()));
    }

    // Another file, read again once it changes on disk.
    private static final class OtherFile implements Following.Base {
        private final File file;
        // The buffer's file when compared.
        private final File bufferFile;
        // Its time and size when last read, and what was read in charset.
        private volatile long lastModified = -1;
        private volatile long length = -1;
        private Charset charset;
        private Following.Read read;

        OtherFile(File file, File bufferFile) {
            this.file = file;
            this.bufferFile = bufferFile;
        }

        @Override
        public Following.Read read(Charset charset) {
            final long lastModified = file.lastModified();
            final long length = file.length();
            if (read != null
                    && charset.equals(this.charset)
                    && lastModified == this.lastModified
                    && length == this.length)
                return read;
            this.lastModified = lastModified;
            this.length = length;
            this.charset = charset;
            if (!file.isFile())
                return read = new Following.Read(Side.NONE, file.canonicalPath() + " (not there)");
            try {
                return read = new Following.Read(Side.read(file, charset), file.canonicalPath());
            }
            catch (IOException e) {
                return read = null;
            }
        }

        @Override
        public boolean changed() {
            return file.lastModified() != lastModified || file.length() != length;
        }

        // Not once the buffer is saved as another file.
        @Override
        public boolean of(Buffer buffer) {
            return Objects.equals(buffer.getFile(), bufferFile);
        }

        @Override
        public String unreadable() {
            return "Couldn't read " + file.canonicalPath();
        }
    }

    /** s: the sides scroll together, and their carets follow each other, or not. */
    public static void compareScrollLock(Editor editor) {
        editor.status(CompareBuffer.toggleScrollLock() ? "Scroll lock on" : "Scroll lock off");
    }

    /**
     * {@code diffIgnoreWhitespace [none|trailing|amount|all]}, w: whitespace
     * ignored in diffs, or compared; with a kind, that ignored, or compared
     * if that is what is ignored.
     */
    public static void diffIgnoreWhitespace(Editor editor, String arg) {
        if (arg == null || arg.isBlank())
            DiffWhitespace.toggle();
        else {
            final Whitespace kind = Whitespace.named(arg);
            if (kind == null) {
                editor.status("Not none, trailing, amount or all: " + arg.strip());
                return;
            }
            DiffWhitespace.choose(kind);
        }
        editor.status(switch (DiffWhitespace.ignored()) {
            case NONE -> "Comparing whitespace";
            case TRAILING -> "Ignoring whitespace at line ends";
            case AMOUNT -> "Ignoring changes in the amount of whitespace";
            case ALL -> "Ignoring all whitespace";
        });
    }

    // What chooses k, from Ignore Whitespace's menus.
    private static String command(Whitespace k) {
        return "diffIgnoreWhitespace " + k.preferenceValue();
    }

    // How Ignore Whitespace's menus name k.
    private static String label(Whitespace k) {
        return switch (k) {
            case TRAILING -> "At Line Ends";
            case AMOUNT -> "Changes in Amount";
            default -> "All";
        };
    }

    /** A comparison's own toolbar: closing it, by change, the scroll lock and whitespace. */
    static ToolBar toolBar(Frame frame) {
        return frame.keep(CompareCommands.class, f -> {
            final ToolBar tb = new ToolBar(f);
            tb.addButton("New", ICON_NEW, "newBuffer");
            tb.addButton("Open", ICON_OPEN, "openFile");
            tb.addButton("Close", ICON_CLOSE, "compareQuit");
            tb.addSeparator();
            addChangeButtons(tb, () -> true);
            tb.addSeparator();
            addButtons(tb, () -> true);
            tb.addExtensionButtons();
            tb.addSeparator();
            tb.addButton("Exit", ICON_EXIT, "quit");
            return tb;
        });
    }

    /** From change to change, enabled while shown is true: a diff's buttons, unified or side by side. */
    public static void addChangeButtons(ToolBar tb, BooleanSupplier shown) {
        tb.addButton("Previous Change", ICON_CHANGE_PREVIOUS, "previousChange").enabledWhen(shown);
        tb.addButton("Next Change", ICON_CHANGE_NEXT, "nextChange").enabledWhen(shown);
    }

    /**
     * The scroll lock, enabled while shown is true; then whitespace: a
     * comparison's buttons, for a view one is part of too.
     */
    public static void addButtons(ToolBar tb, BooleanSupplier shown) {
        tb.addButton("Scroll Lock", ICON_SCROLL_LOCK, "compareScrollLock")
                .enabledWhen(shown)
                .selectedWhen(CompareBuffer::scrollLocked);
        addWhitespaceButton(tb);
    }

    /** Whitespace ignored, or not, and which, as a diff's button. */
    public static void addWhitespaceButton(ToolBar tb) {
        final List<ToolBar.Choice> choices = new ArrayList<>();
        for (Whitespace k : KINDS)
            choices.add(new ToolBar.Choice(label(k), command(k), () -> DiffWhitespace.ignored() == k));
        tb.addSplitButton(
            "Ignore Whitespace",
            ICON_WHITESPACE,
            "diffIgnoreWhitespace",
            DiffWhitespace::isIgnoring,
            choices);
    }

    private static final List<Whitespace> KINDS = List.of(Whitespace.TRAILING, Whitespace.AMOUNT, Whitespace.ALL);

    /**
     * Where a diff's command from the toolbar or Mode menu of a view with a
     * diff runs, from from: by change, in the diff, unified or a side, from
     * in it, else in comparison's new side, its old one if that alone is
     * shown, or else unified's window; the
     * scroll lock in the comparison; whitespace, every diff's, in from;
     * null for another command.
     */
    public static Editor windowFor(Editor from, String command, Buffer unified, CompareBuffer comparison) {
        final boolean inSide = CompareBuffer.of(from.getBuffer()) != null;
        if (command.equals("nextChange") || command.equals("previousChange")) {
            if (inSide || from.getBuffer() == unified)
                return from;
            // Either side shown, or else the unified diff.
            if (comparison != null && !shownIn(from, comparison))
                return windowOf(from, comparison.getOld());
            return windowOf(from, comparison != null ? comparison : unified);
        }
        if (command.equals("compareScrollLock"))
            return inSide ? from : windowOf(from, comparison);
        return command.equals("diffIgnoreWhitespace") ? from : null;
    }

    /**
     * Whether from, a side of a comparison that is part of a view, runs a
     * command of the view's itself: it has a key for it, as = and q.
     */
    public static boolean sideRuns(Buffer from, String command) {
        final KeyMap modal = from.getModalKeyMapForMode();
        return from.getKeyMapForMode().getKeyMapping(command) != null
                || modal != null && modal.getKeyMapping(command) != null;
    }

    // Whether b has a window in from's frame.
    private static boolean shownIn(Editor from, Buffer b) {
        return from.getFrame() != null && from.getFrame().findEditor(b) != null;
    }

    // b's window in from's frame, or from.
    private static Editor windowOf(Editor from, Buffer b) {
        final Editor e = b == null || from.getFrame() == null ? null : from.getFrame().findEditor(b);
        return e != null ? e : from;
    }

    /** A comparison's own Mode menu: its items, and closing it. */
    static void populateModeMenu(Editor editor, Menu menu) {
        addChangeItems(editor, menu);
        menu.addSeparator();
        addItems(editor, menu);
        menu.addSeparator();
        menu.add(editor, "Close", 'C', "compareQuit");
    }

    /** From change to change: a diff's items, unified or side by side. */
    public static void addChangeItems(Editor editor, Menu menu) {
        menu.add(editor, "Next Change", 'T', "nextChange");
        menu.add(editor, "Previous Change", 'V', "previousChange");
    }

    /**
     * The scroll lock and whitespace, checked as they are: the Mode menu's
     * items, for a view a comparison is part of too.
     */
    public static void addItems(Editor editor, Menu menu) {
        menu.addCheckBox(editor, "Scroll Lock", 'K', "compareScrollLock", CompareBuffer.scrollLocked());
        addWhitespaceItems(editor, menu);
    }

    /** What whitespace is ignored, checked if it is: a diff's items, in a menu of their own. */
    public static void addWhitespaceItems(Editor editor, Menu menu) {
        final Menu kinds = new Menu("Ignore Whitespace");
        if (Editor.preferences().getBooleanProperty(Property.USE_MENU_MNEMONICS))
            kinds.setMnemonic('I');
        for (Whitespace k : KINDS)
            kinds.addCheckBox(
                editor,
                label(k),
                k == Whitespace.TRAILING ? 'E' : k == Whitespace.AMOUNT ? 'A' : 'L',
                command(k),
                DiffWhitespace.ignored() == k);
        menu.add(kinds);
    }

    /** q: closes the comparison, its windows from before back. */
    public static void compareQuit(Editor editor) {
        final CompareBuffer b = CompareBuffer.of(editor.getBuffer());
        // A part of another view, as the status's, is closed as that view closes it.
        if (b != null && b.getOwner() != null)
            b.kill();
        else if (b != null)
            b.close(editor.getFrame());
    }
}
