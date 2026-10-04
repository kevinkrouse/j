/*
 * FileCommands.java
 *
 * Copyright (C) 1998-2003 Peter Graves
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

package org.armedbear.j;

import static org.armedbear.j.Constants.*;

import java.awt.AWTEvent;
import java.util.List;
import javax.swing.SwingUtilities;
import org.armedbear.j.mode.dir.DirectoryBuffer;
import org.armedbear.j.util.Utilities;

/** Opening, saving, reloading and closing files; quitting; deleting HTTP cookies. */
public final class FileCommands {
    private FileCommands() {}

    public static void save(Editor editor) {
        save(editor, editor.getBuffer());
    }

    public static void save(Editor editor, Buffer toBeSaved) {
        if (toBeSaved.isLocked())
            return;
        if (toBeSaved.getType() == Buffer.TYPE_NORMAL) {
            if (toBeSaved.isModified()) {
                if (toBeSaved.isUntitled()) {
                    saveAs(editor, toBeSaved);
                } else {
                    editor.setWaitCursor();
                    editor.status("Saving...");
                    if (toBeSaved.getBooleanProperty(Property.REMOVE_TRAILING_WHITESPACE))
                        toBeSaved.removeTrailingWhitespace();
                    if (toBeSaved.save())
                        editor.status("Saving...done");
                    else
                        editor.status("Save failed");
                    editor.setDefaultCursor();
                }
            } else
                editor.status("Not modified");
        }
    }

    public static void saveAs(Editor editor) {
        saveAs(editor, editor.getBuffer());
    }

    /**
     * {@code saveAs FILE} saves to FILE and renames the buffer to it, without
     * the dialog -- which is also what vim's {@code :w FILE} does to a buffer
     * that has no name yet. A relative name is taken from the buffer's own
     * directory.
     *
     * @return true when the buffer was saved
     */
    public static boolean saveAs(Editor editor, String path) {
        if (path == null || path.trim().isEmpty()) {
            saveAs(editor);
            return !editor.getBuffer().isModified();
        }
        final File destination = fileNamed(editor, path.trim());
        if (destination == null)
            return false;
        return saveAsTo(editor, editor.getBuffer(), destination);
    }

    /** A path typed by the user, resolved against the editor's buffer's directory. */
    public static File fileNamed(Editor editor, String path) {
        final File dir = editor.getBuffer().getCurrentDirectory();
        return dir == null
            ? File.getInstance(path)
            : File.getInstance(dir, path);
    }

    private static void saveAs(Editor editor, Buffer toBeSaved) {
        if (toBeSaved.isLocked())
            return;
        if (toBeSaved.getType() == Buffer.TYPE_NORMAL) {
            final String dialogTitle = "Save As";
            File destination =
                SaveFileDialog.getSaveFile(editor, dialogTitle);
            if (destination == null)
                return;

            // At this point, if the target file exists, the user has said
            // it's OK to overwrite it.
            editor.repaintNow();
            saveAsTo(editor, toBeSaved, destination);
        }
    }

    /** The checks and the save shared by saveAs, with and without a dialog. */
    private static boolean saveAsTo(Editor editor, Buffer toBeSaved, File destination) {
        if (
            toBeSaved.isLocked()
                || toBeSaved.getType() != Buffer.TYPE_NORMAL
        )
            return false;
        final String dialogTitle = "Save As";
        // Do we have the target file in a buffer?
        Buffer buf = Editor.getBufferList().findBuffer(destination);
        if (buf != null) {
            // We do. Can we just get rid of it?
            if (!buf.isModified()) {
                buf.deleteAutosaveFile();
                Editor.getBufferList().remove(buf);
            } else {
                // Buffer is modified.  Make user deal with it.
                editor.setDefaultCursor();
                String message = "Target file is in an active buffer.  Please take care of that first.";
                MessageDialog.showMessageDialog(editor, message, dialogTitle);
                return false;
            }
        }

        toBeSaved.saveAs(destination);
        return !toBeSaved.isModified();
    }

    /**
     * {@code saveCopy FILE} writes the buffer to FILE and leaves it named as
     * it was, without the dialog -- vim's {@code :w FILE} on a buffer that
     * already has a name.
     *
     * @return true when the copy was written
     */
    public static boolean saveCopy(Editor editor, String path) {
        if (path == null || path.trim().isEmpty())
            return false;
        final File destination = fileNamed(editor, path.trim());
        return destination != null && saveCopyTo(editor, destination);
    }

    public static void saveCopy(Editor editor) {
        if (editor.getBuffer().isLocked())
            return;
        if (editor.getBuffer().getType() == Buffer.TYPE_NORMAL) {
            final String dialogTitle = "Save Copy";
            final File destination =
                SaveFileDialog.getSaveFile(editor, dialogTitle);
            if (destination == null)
                return;

            editor.repaintNow();
            saveCopyTo(editor, destination);
        }
    }

    /** The checks and the write shared by saveCopy, with and without a dialog. */
    private static boolean saveCopyTo(Editor editor, File destination) {
        if (editor.getBuffer().isLocked() || editor.getBuffer().getType() != Buffer.TYPE_NORMAL)
            return false;
        // Do we have the target file in a buffer?
        Buffer buf = Editor.getBufferList().findBuffer(destination);
        if (buf != null) {
            // We do.  Do we care?
            if (buf.isModified()) {
                // Buffer is modified.  Make user deal with it.
                editor.setDefaultCursor();
                String message = "Target file is in an active buffer.  Please take care of that first.";
                MessageDialog.showMessageDialog(editor, message, "Save Copy");
                return false;
            }
        }

        editor.getBuffer().saveCopy(destination);
        if (buf != null && buf.isLoaded())
            reload(editor, buf);
        return true;
    }

    public static void saveAll(Editor editor) {
        editor.setWaitCursor();
        int numModified = 0;
        int numErrors = 0;
        for (Buffer buf : Editor.getBufferList()) {
            if (buf.getModeId() == CHECKIN_MODE)
                continue;
            if (buf.isUntitled()) {
                editor.setDefaultCursor();
                editor.makeNext(buf);
                editor.activate(buf);
                saveAs(editor);
                editor.setWaitCursor();
            } else if (buf.isModified()) {
                editor.status("Saving modified buffers...");
                ++numModified;
                if (editor.getBuffer().getFile() != null)
                    if (editor.getBuffer().getBooleanProperty(Property.REMOVE_TRAILING_WHITESPACE))
                        editor.getBuffer().removeTrailingWhitespace();
                if (!buf.save())
                    ++numErrors;
            }
        }
        if (numModified == 0)
            editor.status("No modified buffers");
        else if (numErrors == 0)
            editor.status("Saving modified buffers...done");
        else {
            // User will already have seen detailed error information from Buffer.save().
            editor.status("Unable to save all modified buffers");
        }
        editor.setDefaultCursor();
    }

    public static void closeAll(Editor editor) {
        editor.repaintNow();

        for (Buffer buf : Editor.getBufferList()) {
            if (!editor.okToClose(buf))
                return;
        }

        Marker.invalidateAllMarkers();

        Buffer toBeActivated = null;

        for (Buffer buf : Editor.getBufferList()) {
            if (buf instanceof DirectoryBuffer && buf.getFile().equals(editor.getCurrentDirectory())) {
                toBeActivated = buf;
                break;
            }
        }

        if (toBeActivated == null)
            toBeActivated = new DirectoryBuffer(editor.getCurrentDirectory());

        editor.setWaitCursor();

        for (int i = 0; i < Editor.getEditorCount(); i++) {
            Editor ed = Editor.getEditor(i);
            ed.activate(toBeActivated);
        }

        for (Buffer buf : Editor.getBufferList()) {
            if (buf != toBeActivated) {
                for (Editor ed : Editor.getEditorList()) {
                    ed.views.remove(buf);
                }
                buf.deleteAutosaveFile();
                Editor.getBufferList().remove(buf);
                buf.dispose();
            }
        }

        Editor.setSessionName(null);

        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_BUFFER_LIST_ALL);
        Sidebar.refreshSidebarInAllFrames();
        editor.setDefaultCursor();
    }

    public static void closeOthers(Editor editor) {
        editor.repaintNow();

        Buffer toBeActivated = editor.getBuffer();

        for (Buffer buf : Editor.getBufferList()) {
            if (buf != editor.getBuffer() && !editor.okToClose(buf))
                return;
        }

        List<Marker> markers = Marker.getAllMarkers();
        for (int i = 0; i < markers.size(); i++) {
            Marker m = markers.get(i);
            if (m != null && m.getBuffer() != editor.getBuffer())
                m.invalidate();
        }

        editor.setWaitCursor();

        for (Editor ed : Editor.getEditorList())
            ed.activate(toBeActivated);

        for (Buffer buf : Editor.getBufferList()) {
            if (buf != editor.getBuffer()) {
                for (Editor ed : Editor.getEditorList()) {
                    ed.views.remove(buf);
                }
                buf.deleteAutosaveFile();
                Editor.getBufferList().remove(buf);
                buf.dispose();
            }
        }

        Sidebar.setUpdateFlagInAllFrames(SIDEBAR_BUFFER_LIST_ALL);
        Sidebar.refreshSidebarInAllFrames();
        editor.setDefaultCursor();
    }

    // It might make sense to move this code into the Buffer class.
    public static void reload(Editor editor, Buffer buf) {
        if (buf.getFile() instanceof SshFile)
            return; // Not supported.
        editor.setWaitCursor();
        Debug.assertTrue(SwingUtilities.isEventDispatchThread());
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() == buf)
                ed.saveView();
        }

        // May be asynchronous.
        buf.reload();
        editor.setDefaultCursor();
    }

    public static void revertBuffer(Editor editor) {
        final File file = editor.getBuffer().getFile();
        if (file instanceof SshFile)
            return; // Not supported.
        if (editor.getBuffer().isModified()) {
            String prompt = "Discard changes to " + file.canonicalPath() + "?";
            if (!editor.confirm("Revert Buffer", prompt))
                return;
            reload(editor, editor.getBuffer());
        }
    }

    public static void quit(Editor editor) {
        editor.maybeExit();
    }

    public static void saveAllExit(Editor editor) {
        Editor.getTagFileManager().setEnabled(false);
        saveAll(editor);
        editor.maybeExit(); // May never return.
        Editor.getTagFileManager().setEnabled(true);
    }

    public static void openFile(Editor editor) {
        // The location bar may still be holding another prompt's handler.
        LocationBar locationBar = editor.getLocationBar();
        if (locationBar != null && locationBar.getTextField().getHandler() instanceof FinderTextFieldHandler) {
            locationBar.update();
            // The field may have the focus already, and not gain it.
            if (locationBar.getTextField().getHandler() instanceof OpenFileFinderTextFieldHandler finder)
                SwingUtilities.invokeLater(finder::start);
        }
        AWTEvent e = editor.getDispatcher().getLastEvent();
        if (e != null && e.getSource() instanceof MenuItem) {
            Runnable r = () -> {
                editor.setFocusToTextField();
            };
            SwingUtilities.invokeLater(r);
        } else
            editor.setFocusToTextField();
    }

    /**
     * {@code openFileInSplit FILE} -- splits the window and opens FILE in
     * the top one, with the caret, as vim's {@code :split FILE}.
     */
    public static void openFileInSplit(Editor editor, String file) {
        splitAndOpen(editor, file, false);
    }

    /** {@code openFileInVsplit FILE} -- the same, side by side, on the left. */
    public static void openFileInVsplit(Editor editor, String file) {
        splitAndOpen(editor, file, true);
    }

    private static void splitAndOpen(Editor editor, String file, boolean vertical) {
        if (editor.getFrame() == null)
            return;
        if (vertical)
            WindowCommands.vsplitWindow(editor, "vim");
        else
            WindowCommands.splitWindow(editor, "vim");
        if (file == null || file.trim().isEmpty())
            return;
        // The caret is in the top or left window now.
        final Editor top = Editor.currentEditor();
        final Buffer opened = top.openFile(fileNamed(top, file.trim()));
        if (opened != null) {
            top.makeNext(opened);
            top.switchToBuffer(opened);
        }
    }

    public static void openFileInOtherWindow(Editor editor) {
        editor.saveView();
        boolean alreadySplit = editor.getFrame().hasSplit();
        if (!alreadySplit)
            WindowCommands.splitWindow(editor);
        final Editor ed = editor.getOtherEditor();
        if (ed.getLocationBar() != null) {
            Runnable r = () -> {
                editor.getFrame().setFocus(ed.getLocationBar().getTextField());
            };
            SwingUtilities.invokeLater(r);
            Editor.setCurrentEditor(ed);
            if (alreadySplit) {
                // Current editor has changed.
                editor.repaint();
                ed.repaint();
            }
        }
    }

    public static void httpDeleteCookies(Editor editor) {
        Cookie.deleteCookies();
    }

    public static void setEncoding(Editor editor) {
        File file = editor.getBuffer().getFile();
        if (file != null) {
            InputDialog d =
                new InputDialog(
                    editor,
                    "Encoding:",
                    "Set Encoding",
                    editor.getBuffer().getSaveEncoding()
                );
            d.setHistory(new History("setEncoding"));
            editor.centerDialog(d);
            d.setVisible(true);
            String encoding = d.getInput();
            if (encoding != null)
                setEncoding(editor, encoding);
        }
    }

    public static void setEncoding(Editor editor, String encoding) {
        File file = editor.getBuffer().getFile();
        if (file != null) {
            if (Utilities.isSupportedEncoding(encoding)) {
                file.setEncoding(encoding);
                editor.getBuffer().saveProperties();
            } else {
                StringBuilder sb =
                    new StringBuilder("Unsupported encoding \"");
                sb.append(encoding);
                sb.append('"');
                MessageDialog.showMessageDialog(editor, sb.toString(), "Error");
            }
        }
    }
}
