/*
 * OpenFileTextFieldHandler.java
 *
 * Copyright (C) 1998-2007 Peter Graves
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.armedbear.j;

import static org.armedbear.j.Constants.*;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import org.armedbear.j.extension.Extensions;
import org.armedbear.j.extension.Opener;
import org.armedbear.j.mode.web.WebBuffer;
import org.armedbear.j.mode.web.WebMode;
import org.armedbear.j.util.FuzzyMatcher;
import org.armedbear.j.util.FuzzyMatcher.Query;
import org.armedbear.j.util.FuzzyMatcher.Ranked;
import org.armedbear.j.util.Icons;
import org.armedbear.j.util.Keys;
import org.armedbear.j.util.Utilities;

public final class OpenFileTextFieldHandler extends DefaultTextFieldHandler implements MouseListener {
    private static final boolean filenamesIgnoreCase = Platform.isPlatformWindows();

    private String title = "Open File";

    // Options.
    private boolean allowRemote = true;
    private boolean fileMustExist = false;
    private boolean checkBuffers = true;
    private boolean checkSourcePath = true;

    private Object returned;
    private String encoding;

    private CompletionPopup<String> popup;
    // The completions are fuzzy matches, not names starting with what was typed.
    private boolean fuzzy;
    private final Map<String, FinderItem.Row> rows = new HashMap<>();

    private String originalText;
    private String originalPrefix;

    public OpenFileTextFieldHandler(Editor editor, HistoryTextField textField) {
        this(editor, textField, true);
    }

    // Without the mouse listener, for a handler that only lends its enter().
    OpenFileTextFieldHandler(Editor editor, HistoryTextField textField, boolean listen) {
        super(editor, textField);
        if (listen)
            textField.addMouseListener(this);
    }

    public final void setTitle(String s) {
        title = s;
    }

    public final void setAllowRemote(boolean b) {
        allowRemote = b;
    }

    public final void setFileMustExist(boolean b) {
        fileMustExist = b;
    }

    public final void setCheckBuffers(boolean b) {
        checkBuffers = b;
    }

    public final void setCheckSourcePath(boolean b) {
        checkSourcePath = b;
    }

    @Override
    public void enter() {
        final Buffer buffer = editor.getBuffer();
        String entry = textField.getText();
        if (!entry.equals(buffer.getFileNameForDisplay()))
            saveHistory();
        entry = preprocess(entry);
        if (encoding != null && !Utilities.isSupportedEncoding(encoding)) {
            StringBuilder sb = new StringBuilder("Unsupported encoding \"");
            sb.append(encoding);
            sb.append('"');
            error(sb.toString());
            return;
        }
        File currentDir = buffer.getCompletionDirectory();
        if (entry.length() == 0) {
            returned = currentDir;
            done();
            return;
        }
        // Aliases.
        String value = editor.getAlias(entry);
        if (value != null)
            entry = value;
        Opener opener = Extensions.opener(entry);
        if (opener != null) {
            opener.open(editor, entry);
            editor.ensureActive();
            editor.setFocusToDisplay();
            editor.updateLocation();
            editor.updateDisplay();
            return;
        }
        File candidate = null;
        if (Utilities.isFilenameAbsolute(entry)) {
            candidate = File.getInstance(currentDir, entry);
            if (candidate == null) {
                error("Invalid path");
                return;
            }
        } else if (entry.startsWith("./") || entry.startsWith(".\\")) {
            // Path specified is relative to current directory (even if remote).
            candidate = File.getInstance(currentDir, entry);
            if (candidate == null) {
                error("Invalid path");
                return;
            }
        }
        if (candidate != null) {
            if (candidate.isRemote()) {
                if (!allowRemote) {
                    error("File is remote");
                    return;
                }
            } else {
                // Not remote.
                if (!candidate.exists()) {
                    if (fileMustExist) {
                        error("File not found");
                        return;
                    }
                    if (!checkParentDirectory(candidate, title)) {
                        editor.setFocusToDisplay();
                        editor.updateLocation();
                        return;
                    }
                }
            }
            returned = candidate;
            done();
            return;
        }
        // Not absolute.  Look in current directory.
        candidate = File.getInstance(currentDir, entry);
        if (candidate != null && candidate.exists()) {
            returned = candidate;
            done();
            return;
        }
        // Not in current directory. Look for a match in one of the current
        // buffers.
        if (checkBuffers) {
            for (Buffer buf : Editor.getBufferList()) {
                if (buf.getFile() == null)
                    continue;
                boolean found;
                if (filenamesIgnoreCase)
                    found = buf.getFile().getName().equalsIgnoreCase(entry);
                else
                    found = buf.getFile().getName().equals(entry);
                if (found) {
                    returned = buf;
                    done();
                    return;
                }
            }
        }
        // Not currently in a buffer. Look in source and include paths as
        // appropriate.
        if (checkSourcePath) {
            candidate = Utilities.findFile(editor, entry);
            if (candidate != null) {
                returned = candidate;
                done();
                return;
            }
        }
        // Not found in source or include path.
        if (allowRemote) {
            if (entry.startsWith("www.")) {
                returned = File.getInstance("http://".concat(entry));
                done();
                return;
            }
            if (entry.startsWith("ftp.")) {
                returned = File.getInstance("ftp://".concat(entry));
                done();
                return;
            }
        }
        // We failed. Use current directory.
        if (currentDir == null || currentDir.isRemote())
            currentDir = Directories.getUserHomeDirectory();
        candidate = File.getInstance(currentDir, entry);
        if (candidate == null) {
            error("Invalid path");
            return;
        }
        if (fileMustExist && !candidate.exists()) {
            error("File not found");
            return;
        }
        if (!checkParentDirectory(candidate, title)) {
            editor.setFocusToDisplay();
            editor.updateLocation();
            return;
        }
        returned = candidate;
        done();
    }

    private String preprocess(String s) {
        encoding = null;
        s = s.trim();
        if (s.startsWith("-e ")) {
            s = s.substring(3).trim();
            int index = s.indexOf(' ');
            encoding = s.substring(0, index);
            return s.substring(index + 1).trim();
        }
        int index = s.indexOf(" -e ");
        if (index < 0)
            return s; // No encoding specified.
        encoding = s.substring(index + 4).trim();
        return s.substring(0, index).trim();
    }

    private boolean checkParentDirectory(File file, String context) {
        File parentDir = file.getParentFile();
        if (parentDir != null && parentDir.isDirectory())
            return true;
        StringBuilder sb = new StringBuilder("Invalid path \"");
        sb.append(file.canonicalPath());
        sb.append('"');
        MessageDialog.showMessageDialog(sb.toString(), context);
        return false;
    }

    private void done() {
        OpenFileDialog owner = textField.getOwner();
        if (owner != null) {
            owner.setResult(returned);
            owner.ok();
            return;
        }
        Debug.assertTrue(editor != null);
        Buffer buf = null;
        if (returned instanceof Buffer) {
            buf = (Buffer) returned;
        } else if (returned instanceof File) {
            File file = (File) returned;
            file.setEncoding(encoding);
            if (file instanceof HttpFile) {
                if (Editor.getModeList().modeAccepts(IMAGE_MODE, file.getName())) {
                    buf = Editor.getBufferList().findBuffer(file);
                    if (buf == null)
                        buf = new RemoteBuffer(file);
                } else if (Editor.preferences().getBooleanProperty(Property.ENABLE_WEB)) {
                    int modeId = Editor.getModeList().getModeIdForFileName(file.getName());
                    if (modeId < 0 || modeId == HTML_MODE) {
                        if (editor.getMode() instanceof WebMode) {
                            // Current buffer is already a web buffer.
                            buf = editor.getBuffer();
                            Debug.assertTrue(buf instanceof WebBuffer);
                            ((WebBuffer) buf).saveHistory(
                                buf.getFile(),
                                buf.getAbsoluteOffset(editor.getDot()),
                                ((WebBuffer) buf).getContentType());
                            // If we don't call setCache(null), go() will use the
                            // existing cache.
                            buf.setCache(null);
                            ((WebBuffer) buf).go(file, 0, null);
                        } else {
                            // Look for existing buffer.
                            for (Buffer b : Editor.getBufferList()) {
                                if (b instanceof WebBuffer && b.getFile().equals(file)) {
                                    buf = b;
                                    break;
                                }
                            }
                            if (buf == null) {
                                // Existing buffer not found.
                                buf = WebBuffer.createWebBuffer(file, null, null);
                            }
                        }
                    }
                }
            }
            if (buf == null)
                buf = editor.openFile(file);
        }
        Editor.setCurrentEditor(editor);
        final Editor target = buf == null ? editor : editor.show(buf);
        if (Editor.getEditorList().contains(target)) {
            target.ensureActive();
            target.setFocusToDisplay();
            target.updateLocation();
            target.updateDisplay();
        }
    }

    private void saveHistory() {
        final History history = textField.getHistory();
        if (history != null) {
            String entry = textField.getText().trim();
            if (entry.length() > 0) {
                history.append(entry);
                history.save();
            }
        }
    }

    @Override
    public void escape() {
        if (popupShowing()) {
            Debug.bug();
            popup.hide();
        }
        OpenFileDialog owner = textField.getOwner();
        if (owner != null) {
            owner.cancel();
        } else {
            // Using location bar.
            editor.setFocusToDisplay();
            editor.updateLocation();
            editor.ensureActive();
        }
    }

    @Override
    public boolean wantTab() {
        return true;
    }

    @Override
    public void tab() {
        final String entry = textField.getText();
        if (entry.startsWith("http:") || entry.startsWith("https:") || entry.startsWith("ftp:"))
            return;
        final File dir = editor.getCompletionDirectory();
        if (dir == null)
            return;
        String prefix = null;
        if (Utilities.isFilenameAbsolute(entry) || entry.startsWith("..")) {
            File file = File.getInstance(dir, entry);
            if (file != null) {
                if (file.isRemote())
                    prefix = file.netPath();
                else if (dir.isRemote())
                    prefix = file.netPath();
                else
                    prefix = file.canonicalPath();
                if (entry.endsWith(LocalFile.getSeparator()))
                    prefix = prefix.concat(LocalFile.getSeparator());
            }
        } else
            prefix = entry;
        if (prefix == null)
            return;
        editor.setWaitCursor();
        final boolean showCompletionList;
        if (textField.getOwner() != null) {
            showCompletionList = false;
        } else {
            showCompletionList = Editor.preferences().getBooleanProperty(Property.SHOW_COMPLETION_LIST);
        }
        if (showCompletionList) {
            if (!popupShowing()) {
                long start = System.currentTimeMillis();
                completions = getCompletions(prefix);
                long elapsed = System.currentTimeMillis() - start;
                Log.debug("getCompletions " + elapsed + " ms " + completions.size() + " completions");
                index = 0;
                originalText = textField.getText();
                originalPrefix = prefix;
                // A lone fuzzy match is only a guess: list it, so Escape can undo it.
                if (completions.size() == 1 && !fuzzy) {
                    String s = completions.get(0);
                    textField.setText(s);
                    Runnable r = () -> {
                        textField.setCaretPosition(textField.getText().length());
                    };
                    SwingUtilities.invokeLater(r);
                } else if (!completions.isEmpty())
                    showCompletionsPopup();
            } else
                tabPopup(+1, true);
        } else {
            // No completion list.
            while (true) {
                String s = getCompletion(prefix);
                if (s == null)
                    break;
                if (s.equals(entry)) {
                    // Only one possible completion. Accept it and continue.
                    prefix = entry;
                    reset();
                    File file = File.getInstance(dir, entry);
                    if (file != null && file.isDirectory())
                        continue;
                    else
                        break;
                }
                // More than one possible completion. Present the current one
                // and let the user decide what to do next.
                textField.setText(s);
                textField.setCaretPosition(s.length());
                break;
            }
        }
        editor.setDefaultCursor();
    }

    @Override
    public List<String> getCompletions(String prefix) {
        final File dir = editor.getCompletionDirectory();
        ArrayList<String> completions = new ArrayList<>();
        final String sourcePath = checkSourcePath ? getSourcePath() : null;
        prefix = File.normalize(prefix);
        fuzzy = false;
        final boolean ignoreCase = ignoreCase();
        String excludes = Editor.preferences().getStringProperty(Property.FILENAME_COMPLETIONS_EXCLUDE_PATTERN);
        FilenameCompletion completion = new FilenameCompletion(dir, prefix, sourcePath, excludes, ignoreCase);
        final File currentDirectory = getCurrentDirectory();
        List<File> files = completion.listFiles();
        if (files != null) {
            for (final File file : files) {
                final String name = getNameForFile(file, currentDirectory);
                if (file.isDirectory()) {
                    addCompletion(completions, name.concat(file.getSeparator()), ignoreCase);
                    continue;
                }
                addCompletion(completions, name, ignoreCase);
            }
        }
        if (checkBuffers && !Utilities.isFilenameAbsolute(prefix) && prefix.indexOf(LocalFile.getSeparatorChar()) < 0) {
            // Short name.
            addCompletionsFromBufferList(completions, prefix, currentDirectory, ignoreCase);
        }
        if (completions.isEmpty()) {
            fuzzy = true;
            return fuzzyCompletions(dir, prefix, excludes, ignoreCase);
        }
        return completions;
    }

    private static boolean ignoreCase() {
        return Platform.isFileSystemCaseInsensitive()
                || Editor.preferences().getBooleanProperty(Property.FILENAME_COMPLETIONS_IGNORE_CASE);
    }

    // A case-insensitive file system matches regardless of case.
    private static Query fuzzyQuery(String name, boolean ignoreCase) {
        return Query.parse(ignoreCase ? name.toLowerCase(Locale.ROOT) : name);
    }

    private static boolean isSeparator(char c) {
        return c == '/' || c == LocalFile.getSeparatorChar();
    }

    // Where the last component of s begins, ignoring a trailing separator.
    private static int nameStart(String s) {
        int end = s.length();
        if (end > 0 && isSeparator(s.charAt(end - 1)))
            end--;
        for (int i = end; i > 0; i--) {
            if (isSeparator(s.charAt(i - 1)))
                return i;
        }
        return 0;
    }

    private static final int MAX_FUZZY_COMPLETIONS = 50;

    // When nothing starts with prefix: the entries of the directory it names
    // that fuzzily match the rest of it, best first.
    static List<String> fuzzyCompletions(File dir, String prefix, String excludes, boolean ignoreCase) {
        final int index = prefix.lastIndexOf(LocalFile.getSeparatorChar());
        final String head = prefix.substring(0, index + 1);
        final String name = prefix.substring(index + 1);
        if (name.isBlank())
            return new ArrayList<>();
        File directory = head.isEmpty() ? dir : File.getInstance(dir, head);
        if (directory == null || directory.isRemote() || !directory.isDirectory())
            return new ArrayList<>();
        Pattern excludesRE = null;
        if (excludes != null) {
            try {
                excludesRE = Pattern.compile(excludes, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
            }
            catch (PatternSyntaxException e) {
                Log.error(e);
            }
        }
        List<File> files = new ArrayList<>();
        File[] listed = directory.listFiles();
        if (listed != null) {
            for (File file : listed) {
                if (excludesRE == null || !excludesRE.matcher(file.getName()).matches())
                    files.add(file);
            }
        }
        List<String> result = new ArrayList<>();
        // Only the best are asked whether they're directories.
        for (Ranked<File> r : FuzzyMatcher
                .rank(files, File::getName, fuzzyQuery(name, ignoreCase), MAX_FUZZY_COMPLETIONS)) {
            File file = r.item();
            result.add(head + file.getName() + (file.isDirectory() ? file.getSeparator() : ""));
        }
        return result;
    }

    private void addCompletionsFromBufferList(
            List<String> list,
            String prefix,
            File currentDirectory,
            boolean ignoreCase) {
        for (Buffer buf : Editor.getBufferList()) {
            if (buf.getType() != Buffer.TYPE_NORMAL)
                continue;
            if (buf == editor.getBuffer())
                continue;
            File file = buf.getFile();
            if (file != null) {
                boolean isMatch = false;
                if (ignoreCase)
                    isMatch = file.getName().regionMatches(true, 0, prefix, 0, prefix.length());
                else
                    isMatch = file.getName().startsWith(prefix);
                if (isMatch)
                    addCompletion(list, getNameForFile(file, currentDirectory), ignoreCase);
            }
        }
    }

    // Returns file.netPath(), file.getName(), or file.getAbsolutePath(),
    // depending on the situation.
    private String getNameForFile(File file, File currentDirectory) {
        String name;
        if (currentDirectory != null) {
            if (currentDirectory.isLocal()) {
                if (file.isRemote())
                    name = file.netPath();
                else if (currentDirectory.equals(file.getParentFile()))
                    name = file.getName();
                else
                    name = file.getAbsolutePath();
            } else {
                // Current directory is remote. There might be local as well as
                // remote completions, so we need to use the net path.
                name = file.netPath();
            }
        } else {
            if (file.isRemote())
                name = file.netPath();
            else
                name = file.canonicalPath();
        }
        return name;
    }

    // Add string to list if it's not already there.
    private void addCompletion(List<String> list, String s, boolean ignoreCase) {
        if (s != null) {
            for (int i = list.size(); i-- > 0;) {
                if (ignoreCase) {
                    if (s.equalsIgnoreCase(list.get(i)))
                        return;
                } else if (s.equals(list.get(i)))
                    return;
            }
            // Didn't find it.
            list.add(s);
        }
    }

    private boolean isExcluded(String pathname) {
        final int length = pathname.length();
        if (length > 0) {
            if (pathname.charAt(length - 1) == '~')
                return true;
        }
        String extension = Utilities.getExtension(pathname);
        if (extension == null)
            return false;
        if (Platform.isPlatformWindows())
            extension = extension.toLowerCase(Locale.ROOT);
        if (extension.equals(".class") || extension.equals(".cls") || extension.equals(".abcl")) {
            return true;
        }
        if (Platform.isPlatformWindows()) {
            if (extension.equals(".obj") || extension.equals(".exe")) {
                return true;
            }
        }
        return false;
    }

    // Returns null if there is no file associated with the current buffer.
    private File getCurrentDirectory() {
        File file = editor.getBuffer().getFile();
        if (file == null)
            return null;
        if (file.isDirectory())
            return file;
        return file.getParentFile();
    }

    private String getSourcePath() {
        ArrayList<String> dirs = new ArrayList<>();
        // We want to search the mode-specific source path first.
        String sourcePathForMode = editor.getBuffer().getStringProperty(Property.SOURCE_PATH);
        if (sourcePathForMode != null)
            dirs.addAll(Utilities.getDirectoriesInPath(sourcePathForMode));
        // Append any additional directories from the global source path.
        String globalSourcePath = Editor.preferences().getStringProperty(Property.SOURCE_PATH);
        if (globalSourcePath != null) {
            List<String> list = Utilities.getDirectoriesInPath(globalSourcePath);
            for (String s : list) {
                if (!dirs.contains(s))
                    dirs.add(s);
            }
        }
        // Reconstruct source path string.
        StringBuilder sb = new StringBuilder();
        for (String dir : dirs) {
            sb.append(dir);
            sb.append(LocalFile.getPathSeparatorChar());
        }
        // Remove extra path separator at end of string.
        if (sb.length() > 0)
            sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private final void error(String message) {
        MessageDialog.showMessageDialog(editor, message, title);
        editor.setFocusToDisplay();
        editor.updateLocation();
    }

    private boolean popupShowing() {
        return popup != null && popup.isShowing();
    }

    private void showCompletionsPopup() {
        if (popup == null) {
            popup = new CompletionPopup<>(textField, 8);
            FinderCellRenderer renderer = new FinderCellRenderer();
            popup.setCellRenderer(
                (list, value, index, selected, focus) -> renderer
                        .render(list, rows.computeIfAbsent(value, this::row), selected));
            popup.setOnClick(completion -> {
                textField.setText(completion);
                enterPopup();
            });
            popup.setOnDismiss(() -> {
                originalText = null;
                originalPrefix = null;
            });
        }
        rows.clear();
        if (!popup.show(completions, 0))
            return;
        final String completion = completions.get(0);
        Runnable r = () -> {
            updateTextField(completion);
        };
        SwingUtilities.invokeLater(r);
    }

    // A completion as a list row: its file's icon, and the characters typed marked.
    private FinderItem.Row row(String completion) {
        String typed = originalPrefix == null ? "" : originalPrefix;
        String name = typed.substring(nameStart(typed));
        final int start = nameStart(completion);
        FuzzyMatcher.Match m = FuzzyMatcher.match(completion.substring(start), fuzzyQuery(name, ignoreCase()));
        int[] positions = null;
        if (m != null) {
            positions = m.positions().clone();
            for (int i = 0; i < positions.length; i++)
                positions[i] += start;
        }
        final boolean isDirectory = !completion.isEmpty() && isSeparator(completion.charAt(completion.length() - 1));
        FinderItem item = new FinderItem() {
            @Override
            public String matchText() {
                return completion;
            }

            @Override
            public String label() {
                return completion;
            }

            @Override
            public int labelOffset() {
                return 0;
            }

            @Override
            public Icon icon() {
                return isDirectory
                        ? Icons.getIconFromFile("dir_close")
                        : FileIcons.getIcon(completion.substring(start), null);
            }

            @Override
            public void accept(Editor editor, boolean otherWindow) {}
        };
        return new FinderItem.Row(item, positions);
    }

    private void tabPopup(int n, boolean wrap) {
        if (popup.size() == 0) {
            Debug.bug();
            return;
        }
        if (popup.move(n, wrap))
            updateTextField(popup.getSelected());
    }

    private void updateTextField(String completion) {
        if (completion == null)
            return;
        textField.setText(completion);
        if (Editor.preferences().getBooleanProperty(Property.SELECT_COMPLETION)) {
            if (originalText != null && originalText.length() > 0) {
                boolean ignoreCase = Editor.preferences().getBooleanProperty(Property.FILENAME_COMPLETIONS_IGNORE_CASE);
                boolean select = completion.regionMatches(ignoreCase, 0, originalPrefix, 0, originalPrefix.length());
                if (select) {
                    textField.setCaretPosition(originalPrefix.length());
                    textField.moveCaretPosition(completion.length());
                    textField.getCaret().setVisible(false);
                } else {
                    char c = originalText.charAt(0);
                    if (c == '/'
                            || c == '\\'
                            || (Platform.isPlatformWindows()
                                    && originalText.length() >= 3
                                    && originalText.charAt(1) == ':'
                                    && originalText.charAt(2) == '\\')) {
                        final int index;
                        if (ignoreCase) {
                            index = completion.toLowerCase(Locale.ROOT)
                                    .lastIndexOf(originalText.toLowerCase(Locale.ROOT));
                        } else {
                            index = completion.lastIndexOf(originalText);
                        }
                        if (index >= 0) {
                            textField.setCaretPosition(index + originalText.length());
                            textField.moveCaretPosition(completion.length());
                            textField.getCaret().setVisible(false);
                        }
                    } else {
                        Pattern re = Pattern.compile(
                            "[\\/]".concat(Pattern.quote(originalText)),
                            ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
                        boolean found = false;
                        int index = 0;
                        Matcher matcher = re.matcher(completion);
                        while (matcher.find(index)) {
                            index = matcher.end();
                            found = true;
                        }
                        if (found) {
                            textField.setCaretPosition(index);
                            textField.moveCaretPosition(completion.length());
                            textField.getCaret().setVisible(false);
                        }
                    }
                }
            }
        }
    }

    private void enterPopup() {
        popup.hide();
        File file = File.getInstance(editor.getCompletionDirectory(), textField.getText());
        if (file == null || file.isDirectory()) {
            textField.requestFocus();
            end();
        } else {
            editor.repaintNow();
            enter();
        }
    }

    private void end() {
        Runnable r = () -> {
            textField.setCaretPosition(textField.getText().length());
            textField.getCaret().setVisible(true);
        };
        SwingUtilities.invokeLater(r);
    }

    private void left() {
        reset();
        final int pos;
        final int start = textField.getSelectionStart();
        if (start != textField.getSelectionEnd())
            pos = start;
        else
            pos = Math.max(0, textField.getCaretPosition() - 1);
        textField.requestFocus();
        Runnable r = () -> {
            textField.setCaretPosition(pos);
            textField.getCaret().setVisible(true);
        };
        SwingUtilities.invokeLater(r);
    }

    @Override
    protected void reset() {
        if (popup != null)
            popup.hide();
        super.reset();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (popupShowing()) {
            int modifiers = Keys.keyModifiers(e);
            switch (e.getKeyCode()) {
                case KeyEvent.VK_ENTER:
                    enterPopup();
                    e.consume();
                    return;
                case KeyEvent.VK_DELETE:
                case KeyEvent.VK_ESCAPE:
                    popup.hide();
                    textField.setText(originalText);
                    originalText = null;
                    originalPrefix = null;
                    end();
                    e.consume();
                    return;
                case KeyEvent.VK_TAB:
                    if (modifiers == 0)
                        tabPopup(+1, true);
                    else if (modifiers == SHIFT_MASK)
                        tabPopup(-1, true);
                    e.consume();
                    return;
                case KeyEvent.VK_UP:
                case KeyEvent.VK_KP_UP:
                    tabPopup(-1, false);
                    e.consume();
                    return;
                case KeyEvent.VK_DOWN:
                case KeyEvent.VK_KP_DOWN:
                    tabPopup(+1, false);
                    e.consume();
                    return;
                case KeyEvent.VK_P:
                case KeyEvent.VK_N:
                    if (modifiers == CTRL_MASK) {
                        tabPopup(e.getKeyCode() == KeyEvent.VK_P ? -1 : +1, false);
                        e.consume();
                        return;
                    }
                    break;
                case KeyEvent.VK_PAGE_UP:
                case KeyEvent.VK_PAGE_DOWN:
                    if (popup.page(e.getKeyCode() == KeyEvent.VK_PAGE_UP ? -1 : +1))
                        updateTextField(popup.getSelected());
                    e.consume();
                    return;
                case KeyEvent.VK_LEFT:
                case KeyEvent.VK_KP_LEFT:
                    left();
                    e.consume();
                    return;
                case KeyEvent.VK_RIGHT:
                case KeyEvent.VK_KP_RIGHT:
                case KeyEvent.VK_END:
                    reset();
                    originalText = null;
                    originalPrefix = null;
                    end();
                    e.consume();
                    return;
                default:
                    break;
            }
        } else {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_LEFT:
                case KeyEvent.VK_KP_LEFT:
                case KeyEvent.VK_RIGHT:
                case KeyEvent.VK_KP_RIGHT:
                    textField.getCaret().setVisible(true);
                    originalText = null;
                    originalPrefix = null;
                    break;
            }
        }
        super.keyPressed(e);
    }

    @Override
    public void keyTyped(KeyEvent e) {
        char c = e.getKeyChar();
        if (c == 8) {
            // backspace
            textField.getCaret().setVisible(true);
            return;
        }
        if ((Keys.keyModifiers(e) & (ALT_MASK | CTRL_MASK | META_MASK)) != 0) {
            e.consume();
            return;
        }
        if (c >= ' ' && c != 127) {
            // The key press has already hidden the list.
            final boolean refine = fuzzy && originalText != null;
            if (popup != null)
                popup.hide();
            String text = textField.getText();
            if (refine) {
                // Typing after a fuzzy Tab adds to what was typed, not to the guess.
                text = originalText;
                originalText = null;
                originalPrefix = null;
                textField.setText(text);
                textField.setCaretPosition(text.length());
            } else if (textField.getSelectionStart() != textField.getSelectionEnd()) {
                if (originalText != null) {
                    text = originalText;
                    originalText = null;
                    originalPrefix = null;
                } else {
                    StringBuilder sb = new StringBuilder(text.substring(0, textField.getSelectionStart()));
                    sb.append(text.substring(textField.getSelectionEnd()));
                    text = sb.toString();
                    textField.setCaretPosition(textField.getSelectionStart());
                }
            }
            // Insert (or append) typed char.
            final int pos = Math.min(textField.getCaretPosition(), text.length());
            StringBuilder sb = new StringBuilder(text.substring(0, pos));
            sb.append(c);
            if (pos < text.length())
                sb.append(text.substring(pos));
            textField.setText(sb.toString());
            textField.requestFocus();
            Runnable r = () -> {
                int caretPos;
                final String s = textField.getText();
                if (s != null)
                    caretPos = Math.min(pos + 1, s.length());
                else
                    caretPos = 0;
                textField.setCaretPosition(caretPos);
                textField.getCaret().setVisible(true);
            };
            SwingUtilities.invokeLater(r);
        }
        e.consume();
    }

    @Override
    public void mousePressed(MouseEvent e) {
        Editor.setCurrentEditor(editor);
        originalText = null;
        originalPrefix = null;
    }

    @Override
    public void mouseReleased(MouseEvent e) {}

    @Override
    public void mouseClicked(MouseEvent e) {}

    @Override
    public void mouseEntered(MouseEvent e) {}

    @Override
    public void mouseExited(MouseEvent e) {}
}
