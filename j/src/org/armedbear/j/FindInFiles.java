/*
 * FindInFiles.java
 *
 * Copyright (C) 1998-2005 Peter Graves
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

import org.armedbear.j.mode.list.ListOccurrencesInFilesBuffer;
import java.lang.StringBuilder;
import org.armedbear.j.vcs.p4.P4;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.regex.Matcher;
import javax.swing.SwingUtilities;
import javax.swing.undo.CompoundEdit;

public final class FindInFiles extends Replacement implements Constants,
    BackgroundProcess
{
    private static FindInFiles findInFiles;

    public static final FindInFiles getFindInFiles()
    {
        return findInFiles;
    }

    private final Frame frame;
    private String files;

    private boolean defaultExcludes = true;
    private boolean includeSubdirs;
    private boolean searchFilesInMemory = true;
    private ListOccurrencesInFilesBuffer outputBuffer;
    private boolean listEachOccurrence;

    private Mode mode;

    private Vector<File> results = new Vector<File>();

    private boolean cancelled;

    private int numFilesExamined;
    private int numFilesModified;

    private List<Filter> filters;

    private SaveException saveException;
    private ConfirmReplacementDialog confirmDialog;

    private final String encoding;

    public FindInFiles(Editor editor)
    {
        super(editor);
        this.frame = editor.getFrame();
        encoding =
            Editor.preferences().getStringProperty(Property.DEFAULT_ENCODING);
    }

    public final boolean getDefaultExcludes()
    {
        return defaultExcludes;
    }

    public final void setDefaultExcludes(boolean b)
    {
        defaultExcludes = b;
    }

    public final boolean getIncludeSubdirs()
    {
        return includeSubdirs;
    }

    public final void setIncludeSubdirs(boolean b)
    {
        includeSubdirs = b;
    }

    public final boolean getSearchFilesInMemory()
    {
        return searchFilesInMemory;
    }

    public final void setSearchFilesInMemory(boolean b)
    {
        searchFilesInMemory = b;
    }

    public final Mode getMode()
    {
        return mode;
    }

    public final void setMode(Mode mode)
    {
        this.mode = mode;
    }

    public final ListOccurrencesInFilesBuffer getOutputBuffer()
    {
        return outputBuffer;
    }

    public final void setOutputBuffer(ListOccurrencesInFilesBuffer buf)
    {
        outputBuffer = buf;
    }

    public final boolean getListEachOccurrence()
    {
        return listEachOccurrence;
    }

    public final void setListEachOccurrence(boolean b)
    {
        listEachOccurrence = b;
    }

    public void listFiles(Editor editor)
    {
        if (outputBuffer != null && editor.getBuffer() != outputBuffer) {
            Buffer buf = null;
            for (BufferIterator it = new BufferIterator(); it.hasNext();) {
                Buffer b = it.next();
                if (b == outputBuffer) {
                    buf = b;
                    break;
                }
            }
            if (buf == null) {
                // Output buffer was closed.
                outputBuffer.relink();
            }
            editor.makeNext(outputBuffer);
            Editor ed = editor.activateInOtherWindow(outputBuffer);
            if (buf == null) {
                // Need to restore dot pos.
                ed.setDot(outputBuffer.getLastDotPos());
                ed.moveCaretToDotCol();
                ed.setUpdateFlag(REFRAME);
                ed.updateDisplay();
            }
        }
    }

    public final String getFiles()
    {
        return files;
    }

    public void setFiles(String files) throws Exception
    {
        ArrayList<Filter> list = new ArrayList<Filter>();
        StringTokenizer st = new StringTokenizer(files, ";");
        // We start in the editor's current directory.
        File currentDir = getEditor().getCurrentDirectory();
        if (currentDir == null || currentDir.isRemote())
            throw new Exception("Operation not supported for remote files");
        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();
            File file = File.getInstance(currentDir, token);
            if (file == null) {
                String message = "Invalid path \"" + token + '"';
                throw new Exception(message);
            }
            File parent = file.getParentFile();
            // Verify that the parent directory actually exists.
            if (parent == null || !parent.isDirectory()) {
                String message = "Invalid path \"" + token + '"';
                throw new Exception(message);
            }
            // Parent is our new current directory.
            currentDir = parent;
            String canonicalPath = file.canonicalPath();
            // This will throw an exception if canonicalPath isn't an
            // acceptable wildcard pattern.
            try {
                list.add(new Filter(canonicalPath));
            }
            catch (Exception e) {
                String message = "Unsupported wild card pattern";
                throw new Exception(message);
            }
        }
        // Success.
        this.files = files;
        filters = list;
    }

    public final void run()
    {
        Debug.assertTrue(outputBuffer != null);
        outputBuffer.setBusy(true);
        outputBuffer.setBackgroundProcess(this);
        runInternal();
        outputBuffer.setBackgroundProcess(null);
        outputBuffer.setBusy(false);
        if (!cancelled && getReplaceWith() != null) {
            Runnable r = new Runnable() {
                public void run()
                {
                    Editor.getTagFileManager().setEnabled(false);
                    replaceInAllFiles();
                    Editor.getTagFileManager().setEnabled(true);
                }
            };
            SwingUtilities.invokeLater(r);
        }
    }

    private void runInternal()
    {
        frame.setWaitCursor();
        Pattern excludesRE = null;
        if (defaultExcludes) {
            try {
                String excludesPattern = Editor.preferences().getStringProperty(
                    Property.FILENAME_COMPLETIONS_EXCLUDE_PATTERN);
                excludesRE = Pattern.compile(excludesPattern, 0);
            } catch (PatternSyntaxException e) {
                Log.error(e);
            }
        }
        for (Filter filter : filters) {
            File dir = null;
            File spec = File.getInstance(filter.getOriginalPattern());
            if (spec != null) {
                File parent = spec.getParentFile();
                if (parent != null)
                    dir = parent;
            }
            if (dir == null)
                dir = getEditor().getCurrentDirectory();
            searchDirectory(dir, filter, excludesRE);
            // Did the user cancel?
            if (cancelled)
                break;
        }
        if (getReplaceWith() == null) {
            // Find in files, not replace in files.
            Runnable runnable = new Runnable() {
                public void run()
                {
                    frame.setDefaultCursor();
                    if (outputBuffer != null) {
                        if (cancelled)
                            getEditor().status("Search cancelled");
                        else
                            getEditor().status("Search completed");
                        StringBuilder sb =
                            new StringBuilder("Pattern found in ");
                        sb.append(results.size());
                        sb.append(" of ");
                        sb.append(numFilesExamined);
                        sb.append(" files examined");
                        if (cancelled)
                            sb.append(" (search cancelled by user)");
                        outputBuffer.appendStatusLine(sb.toString());
                        outputBuffer.invalidate();
                        outputBuffer.renumber();
                        outputBuffer.setBusy(false);
                        EditorIterator iter = new EditorIterator();
                        while (iter.hasNext()) {
                            Editor ed = iter.next();
                            if (ed.getBuffer() == outputBuffer) {
                                ed.setTopLine(outputBuffer.getFirstLine());
                                ed.setDot(outputBuffer.getInitialDotPos());
                                ed.moveCaretToDotCol();
                                ed.setUpdateFlag(REPAINT);
                                ed.updateDisplay();
                            }
                        }
                    }
                }
            };
            SwingUtilities.invokeLater(runnable);
            // Nothing more to do.
            return;
        }
        SwingUtilities.invokeLater(updateDisplayRunnable);
    }

    public final void cancel()
    {
        cancelled = true;
    }

    private void searchDirectory(File dir, Filter filter, Pattern excludesRE)
    {
        String[] files = dir.list();
        if (files == null)
            return;
        for (String f : files) {
            if (cancelled)
                return;
            File file = File.getInstance(dir, f);
            if (excludesRE != null && excludesRE.matcher(f).matches())
                continue;
            if (file.isDirectory()) {
                if (includeSubdirs)
                    searchDirectory(file, filter, excludesRE); // Recurse!
                continue;
            }
            if (!filter.accepts(f))
                continue;
            if (isBinaryFile(file))
                continue;
            if (searchFilesInMemory) {
                Buffer buf = Editor.getBufferList().findBuffer(file);
                if (buf != null && buf.isLoaded()) {
                    Position pos = findInBuffer(buf);
                    if (pos != null) {
                        results.add(file);
                        processFile(file, buf.getMode(), pos);
                    }
                    ++numFilesExamined;
                    continue;
                }
                // No buffer found, fall through...
            }
            Debug.assertTrue(outputBuffer != null);
            processFile(file);
            ++numFilesExamined;
        }
    }

    private void processFile(File file)
    {
        try {
            boolean update = false;
            BufferedReader reader =
                new BufferedReader(new InputStreamReader(file.getInputStream(),
                                                         encoding));
            int lineNumber = 0;
            int matches = 0;
            final boolean delimited = wholeWordsOnly();
            String s;
            while ((s = reader.readLine()) != null) {
                ++lineNumber;
                boolean found = delimited ? findDelimited(s, mode) : find(s);
                if (found) {
                    try {
                        outputBuffer.lockWrite();
                    }
                    catch (InterruptedException e) {
                        Log.error(e);
                        return;
                    }
                    try {
                        if (matches == 0) {
                            // First match in this file.
                            if (!listEachOccurrence && results.size() == 0)
                                outputBuffer.appendLine("Found in:");
                            outputBuffer.appendFileLine(file,
                                                        listEachOccurrence);
                            results.add(file);
                            update = true;
                        }
                        ++matches;
                        if (listEachOccurrence)
                            outputBuffer.appendOccurrenceLine(s, lineNumber);
                        else
                            break;
                    }
                    finally {
                        outputBuffer.renumber();
                        outputBuffer.unlockWrite();
                    }
                }
            }
            // Update display once per file.
            if (update)
                SwingUtilities.invokeLater(updateDisplayRunnable);
        }
        catch (IOException e) {
            Log.error(e);
        }
    }

    // BUG!! Unicode files are treated as binary.
    private static boolean isBinaryFile(File file)
    {
        try {
            InputStream in = file.getInputStream();
            byte[] bytes = new byte[4096];
            int bytesRead = in.read(bytes);
            in.close();
            for (int i = 0; i < bytesRead; i++) {
                if (bytes[i] == 0)
                    return true;
            }
            return false;
        }
        catch (IOException e) {
            Log.error(e);
            return true;
        }
    }

    private void processFile(File file, Mode mode, Position pos)
    {
        Debug.assertTrue(outputBuffer != null);
        try {
            outputBuffer.lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            processFileInternal(file, mode, pos);
            outputBuffer.renumber();
        }
        finally {
            outputBuffer.unlockWrite();
        }
        // Update display once per file.
        SwingUtilities.invokeLater(updateDisplayRunnable);
    }

    private final Runnable updateDisplayRunnable = new Runnable() {
        public void run()
        {
            Position end = null;
            for (EditorIterator iter = new EditorIterator(); iter.hasNext();) {
                Editor ed = iter.next();
                if (ed.getBuffer() == outputBuffer) {
                    if (end == null) {
                        end = outputBuffer.getEnd();
                        end.setOffset(0);
                    }
                    ed.moveDotTo(end);
                    ed.setUpdateFlag(REPAINT);
                    ed.updateDisplay();
                }
            }
        }
    };

    private void processFileInternal(File file, Mode mode, Position pos)
    {
        if (!listEachOccurrence && results.size() == 1)
            outputBuffer.appendLine("Found in:");
        outputBuffer.appendFileLine(file, listEachOccurrence);
        if (listEachOccurrence) {
            outputBuffer.appendOccurrenceLine(pos.getLine());
            while (pos.getLine().next() != null) {
                pos.moveTo(pos.getLine().next(), 0);
                if ((pos = find(mode, pos)) != null)
                    outputBuffer.appendOccurrenceLine(pos.getLine());
                else
                    break;
            }
        }
    }

    private void replaceInAllFiles()
    {
        final Editor editor = getEditor();
        final Buffer oldBuffer = editor.getBuffer();
        for (File file : results) {
            try {
                replaceInFile(file);
            }
            catch (CheckFileException e) {
                handleCheckFileException(e);
            }
            catch (SaveException e) {
                handleSaveException(e);
            }
            if (cancelled)
                break;
        }

        // Restore state and display completion message.
        Runnable runnable = new Runnable() {
            public void run()
            {
                editor.activate(oldBuffer);
                editor.setUpdateFlag(REPAINT);
                frame.setDefaultCursor();
                editor.updateDisplay();
                completed();
            }
        };
        if (SwingUtilities.isEventDispatchThread())
            runnable.run();
        else
            SwingUtilities.invokeLater(runnable);
    }

    private void handleCheckFileException(final CheckFileException e)
    {
        Runnable runnable = new Runnable() {
            public void run()
            {
                String title = "Replace In Files";
                String message = e.getMessage();
                if (message == null)
                    message = "Error.";
                message += " Continue?";
                cancelled = !getEditor().confirm(title, message);
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(runnable);
            }
            catch (Exception ex) {
                Log.error(ex);
            }
        }
    }

    private void handleSaveException(final SaveException e)
    {
        Runnable runnable = new Runnable() {
            public void run()
            {
                String title = "Replace In Files";
                String message = e.getMessage();

                // Tell user exactly what error occurred.
                if (message != null)
                    MessageDialog.showMessageDialog(message, title);

                // Display summary message.
                message = "Unable to save " + e.getFile().canonicalPath();
                MessageDialog.showMessageDialog(message, title);

                message = "Continue anyway?";
                cancelled = !getEditor().confirm(title, message);
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(runnable);
            }
            catch (Exception ex) {
                Log.error(ex);
            }
        }
    }

    private void replaceInFile(final File file) throws CheckFileException,
        SaveException
    {
        checkFile(file);

        if (confirmChanges()) {
            if (SwingUtilities.isEventDispatchThread()) {
                frame.setDefaultCursor();
                replaceInFileConfirm(file);
                frame.setWaitCursor();
            } else {
                Runnable runnable = new Runnable() {
                    public void run()
                    {
                        frame.setDefaultCursor();
                        try {
                            replaceInFileConfirm(file);
                        }
                        catch (SaveException e) {
                            FindInFiles.this.saveException = e;
                        }
                        frame.setWaitCursor();
                    }
                };
                try {
                    SwingUtilities.invokeAndWait(runnable);
                }
                catch (Exception e) {
                    Log.error(e);
                }
                if (FindInFiles.this.saveException != null)
                    throw FindInFiles.this.saveException;
            }
        } else
            replaceInFileNoConfirm(file);
    }

    private void replaceInFileNoConfirm(File file) throws SaveException
    {
        Buffer buffer = Editor.getBufferList().findBuffer(file);
        if (buffer != null) {
            // Found existing buffer. It may or may not be loaded at this
            // point.
            if (!buffer.isLoaded()) {
                if (!buffer.initialized())
                    buffer.initialize();
                buffer.load();
                if (!buffer.isLoaded())
                    return; // Error handling?
            }
            final boolean wasModified = buffer.isModified();
            int oldReplacementCount = getReplacementCount();
            try {
                buffer.lockWrite();
            }
            catch (InterruptedException e) {
                Log.error(e);
                return;
            }
            try {
                CompoundEdit compoundEdit = new CompoundEdit();
                Position pos = new Position(buffer.getFirstLine(), 0);
                while ((pos = find(mode, pos)) != null) {
                    compoundEdit.addEdit(new UndoLineEdit(buffer, pos.getLine()));
                    replaceOccurrence(pos);
                    buffer.incrementModCount();
                }
                compoundEdit.end();
                buffer.addEdit(compoundEdit);
            }
            finally {
                buffer.unlockWrite();
            }
            if (buffer.isModified() != wasModified)
                Sidebar.setUpdateFlagInAllFrames(SIDEBAR_REPAINT_BUFFER_LIST);
            if (getReplacementCount() > oldReplacementCount)
                ++numFilesModified;
            for (EditorIterator it = new EditorIterator(); it.hasNext();) {
                Editor ed = it.next();
                if (ed.getBuffer() == buffer) {
                    if (ed.getDotOffset() > ed.getDotLine().length()) {
                        ed.getDot().setOffset(ed.getDotLine().length());
                        ed.moveCaretToDotCol();
                        ed.updateDotLine();
                        ed.updateDisplay();
                    }
                }
            }
        } else {
            // There was no buffer for the file in question prior to this operation.
            SystemBuffer buf = new SystemBuffer(file);
            buf.load();
            if (!buf.isLoaded())
                return; // Error handling?
            boolean modified = false;
            Position pos = new Position(buf.getFirstLine(), 0);
            while ((pos = find((Mode)null, pos)) != null) {
                if (cancelled)
                    break;
                replaceOccurrence(pos);
                modified = true;
            }
            if (modified)
                buf.writeBuffer(); // Throws SaveException if there's an error.
            if (modified)
                ++numFilesModified;
        }
        replacedInFile(file);
    }

    private void replaceInFileConfirm(File file) throws SaveException
    {
        final Editor editor = getEditor();
        boolean close = false;
        Buffer buffer = Editor.getBufferList().findBuffer(file);
        if (buffer == null) {
            buffer = Buffer.createBuffer(file);
            if (buffer == null)
                return; // Error handling?

            // We created the buffer for this operation, so we should close it
            // when we're done.
            close = true;
        }
        editor.activate(buffer);
        int oldReplacementCount = getReplacementCount();
        Position saved = new Position(editor.getDot());
        CompoundEdit compoundEdit = buffer.beginCompoundEdit();
        editor.moveDotTo(buffer.getFirstLine(), 0);
        Position pos = find(mode, editor.getDot());
        if (pos == null) {
            // Not found.
            buffer.endCompoundEdit(compoundEdit);
            editor.undo();
            return;
        }
        final boolean wasModified = buffer.isModified();
        editor.moveDotTo(pos);
        editor.markFoundPattern(this);
        editor.updateDisplay();
        confirmDialog = new ConfirmReplacementDialog(this, true);
        confirmDialog.setTitle(file.netPath());

        // This is modal: carry out all replacements in this file.
        confirmDialog.show();

        if (confirmDialog.cancelled())
            cancelled = true;
        editor.moveDotTo(saved);
        buffer.endCompoundEdit(compoundEdit);
        if (close) {
            if (buffer.isModified()) {
                buffer.writeBuffer(); // Throws SaveException if there's an error.
                buffer.saved();
                buffer.setLastModified(buffer.getFile().lastModified());
            }
            if (!buffer.isModified())
                buffer.kill();
        } else {
            // We're keeping the buffer open. Make sure the sidebar shows the
            // modified status for the file correctly.
            if (buffer.isModified() != wasModified) {
                // The file was just modified for the fist time. The buffer
                // lists need to be updated.
                Sidebar.setUpdateFlagInAllFrames(SIDEBAR_REPAINT_BUFFER_LIST);
            }
        }

        if (getReplacementCount() > oldReplacementCount) {
            ++numFilesModified;
            replacedInFile(file);
        }
    }

    private void replacedInFile(File file)
    {
        if (outputBuffer != null) {
            try {
                outputBuffer.lockWrite();
            }
            catch (InterruptedException e) {
                Log.error(e);
                return;
            }
            try {
                if (numFilesModified == 1)
                    outputBuffer.appendLine("Replaced in:");
                outputBuffer.appendFileLine(file, false);
            }
            finally {
                outputBuffer.renumber();
                outputBuffer.unlockWrite();
            }
            // Update display once per file.
            if (SwingUtilities.isEventDispatchThread()) {
                Position end = null;
                for (EditorIterator iter = new EditorIterator(); iter.hasNext();) {
                    Editor ed = iter.next();
                    if (ed.getBuffer() == outputBuffer) {
                        if (end == null)
                            end = outputBuffer.getEnd();
                        ed.moveDotTo(end);
                        ed.setUpdateFlag(REPAINT);
                        ed.updateDisplay();
                        ed.repaintNow();
                    }
                }
            } else {
                Debug.bug();
                SwingUtilities.invokeLater(updateDisplayRunnable);
            }
        }
    }

    private void checkFile(File file) throws CheckFileException
    {
        if (file.isRemote())
            checkFileError(file, "file is not local");
        if (file.isDirectory())
            checkFileError(file, "file is a directory");
        if (!file.isFile())
            checkFileError(file, "file not found");
        if (!file.canRead())
            checkFileError(file, "file is not readable");
        boolean writable = file.canWrite();
        if (!writable) {
            if (Editor.preferences().getBooleanProperty(Property.P4_AUTO_EDIT)) {
                if (P4.autoEdit(file))
                    writable = file.canWrite();
            }
            if (!writable)
                checkFileError(file, "file is read only");
        }
    }

    private void checkFileError(File file, String reason) throws CheckFileException
    {
        StringBuilder sb = new StringBuilder("Can't process ");
        sb.append(file.netPath());
        sb.append(" (");
        sb.append(reason);
        sb.append(')');
        throw new CheckFileException(sb.toString());

    }

    // Completion message for replace in files only.
    private void completed()
    {
        StringBuilder sb = new StringBuilder();
        int replacementCount = getReplacementCount();
        if (replacementCount == 0) {
            sb.append("No occurrences replaced");
        } else {
            sb.append("Replaced ");
            sb.append(replacementCount);
            sb.append(" occurrence");
            if (replacementCount > 1)
                sb.append('s');
            sb.append(" in ");
            sb.append(numFilesModified);
            sb.append(" file");
            if (numFilesModified > 1)
                sb.append('s');
        }
        if (cancelled)
            sb.append(" (operation cancelled)");
        else
            sb.append(" (" + numFilesExamined + " files examined)");
        if (outputBuffer != null) {
            outputBuffer.appendStatusLine(sb.toString());
            outputBuffer.renumber();
            for (EditorIterator it = new EditorIterator(); it.hasNext();) {
                Editor ed = it.next();
                if (ed.getBuffer() == outputBuffer) {
                    ed.setTopLine(outputBuffer.getFirstLine());
                    ed.setDot(outputBuffer.getEnd());
                    ed.moveCaretToDotCol();
                    ed.setUpdateFlag(REPAINT);
                    ed.updateDisplay();
                }
            }
        } else
            MessageDialog.showMessageDialog(getEditor(), sb.toString(),
                "ReplaceInFiles");
    }

    public static void findInFiles()
    {
        final Editor editor = Editor.currentEditor();
        final File dir = editor.getCurrentDirectory();
        if (dir != null && !dir.isRemote())
            findOrReplaceInFiles(editor, false);
    }

    public static void replaceInFiles()
    {
        final Editor editor = Editor.currentEditor();
        final File dir = editor.getCurrentDirectory();
        if (dir != null && !dir.isRemote())
            findOrReplaceInFiles(editor, true);
    }

    private static void findOrReplaceInFiles(Editor editor, boolean replace)
    {
        FindInFilesDialog d = new FindInFilesDialog(editor, replace);
        editor.centerDialog(d);
        d.show();
        editor.repaintNow();
        if (d.getFindInFiles() == null)
            return;
        if (findInFiles != null) {
            // Kill old output buffer.
            Buffer buf = findInFiles.getOutputBuffer();
            if (Editor.getBufferList().contains(buf))
                buf.kill();
        }
        findInFiles = d.getFindInFiles();
        findInFiles.setOutputBuffer(new ListOccurrencesInFilesBuffer(findInFiles));
        new Thread(findInFiles).start();
        Buffer outputBuffer = findInFiles.getOutputBuffer();
        if (outputBuffer != null) {
            Editor otherEditor = editor.getOtherEditor();
            if (otherEditor != null) {
                outputBuffer.setUnsplitOnClose(otherEditor.getBuffer().unsplitOnClose());
                otherEditor.makeNext(outputBuffer);
            } else
                outputBuffer.setUnsplitOnClose(true);
            editor.makeNext(outputBuffer);
            editor.activateInOtherWindow(outputBuffer);
        }
        editor.status("Press Escape to cancel search");
    }

    public static void listFiles()
    {
        if (findInFiles != null)
            findInFiles.listFiles(Editor.currentEditor());
    }

    private static final class Filter
    {
        private final String originalPattern;
        private final boolean ignoreCase;
        private Pattern pattern;

        public Filter(String s) throws Exception
        {
            this.originalPattern = s;
            ignoreCase = Platform.isPlatformWindows();
            File file = File.getInstance(ignoreCase ? s.toLowerCase() : s);
            if (!processFilter(file.getName()))
                throw new Exception("process pattern failed");
        }

        public String getOriginalPattern()
        {
            return originalPattern;
        }

        private boolean processFilter(String s)
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '.':
                        sb.append("\\.");
                        break;
                    case '*':
                        sb.append(".*");
                        break;
                    case '?':
                        sb.append(".?");
                        break;
                    default:
                        sb.append(c);
                        break;
                }
            }
            try {
                pattern = Pattern.compile(sb.toString());
                return true;
            }
            catch (PatternSyntaxException e) {
                Log.error(e);
                return false;
            }
        }

        public boolean accepts(String name)
        {
            if (ignoreCase)
                name = name.toLowerCase();
            Matcher matcher = pattern.matcher(name);
            return matcher.matches();
        }
    }

    private static final class CheckFileException extends Exception
    {
        CheckFileException(String message)
        {
            super(message);
        }
    }
}
