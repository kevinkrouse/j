/*
 * TagCommands.java
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

import org.armedbear.j.mode.java.JavaSource;
import org.armedbear.j.mode.java.JavaTag;
import org.armedbear.j.mode.list.ListTagsBuffer;
import org.armedbear.j.util.Utilities;

import java.awt.AWTEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.undo.CompoundEdit;

public final class TagCommands implements Constants
{
    public static void nextTag()
    {
        final Editor editor = Editor.currentEditor();
        final Position dot = editor.getDot();
        if (dot != null) {
            final List<LocalTag> tags = editor.getBuffer().getTags();
            if (tags != null) {
                // Find the next tag after dot.
                final int dotLineNumber = dot.lineNumber();
                for (LocalTag tag : tags) {
                    if (tag.lineNumber() > dotLineNumber) {
                        editor.moveDotTo(tag.getPosition());
                        return;
                    }
                }
            }
        }
    }

    public static void previousTag()
    {
        final Editor editor = Editor.currentEditor();
        final Position dot = editor.getDot();
        if (dot != null) {
            final List<LocalTag> tags = editor.getBuffer().getTags();
            if (tags != null) {
                // Find the last tag before dot.
                final int dotLineNumber = dot.lineNumber();
                for (int i = tags.size()-1; i >= 0; i--) {
                    LocalTag tag = tags.get(i);
                    if (tag.lineNumber() < dotLineNumber) {
                        editor.moveDotTo(tag.getPosition());
                        return;
                    }
                }
            }
        }
    }

    public static void findTag()
    {
        final Editor editor = Editor.currentEditor();
        final LocationBar locationBar = editor.getLocationBar();
        locationBar.setLabelText(LocationBar.PROMPT_TAG);
        HistoryTextField textField = locationBar.getTextField();
        textField.setHandler(new FindTagTextFieldHandler(editor, textField));
        textField.setHistory(new History("findTag.tag"));
        textField.setText("");
        if (editor.getDispatcher().getLastEvent().getSource() instanceof MenuItem) {
            Runnable r = new Runnable() {
                public void run()
                {
                    editor.setFocusToTextField();
                }
            };
            SwingUtilities.invokeLater(r);
        } else
            editor.setFocusToTextField();
    }

    private static boolean findTag(Editor editor, Expression expression,
        boolean useOtherWindow)
    {
        List<? extends Tag> tags = findMatchingTags(editor.getBuffer(), expression);
        if (tags == null || tags.size() == 0)
            return false;
        if (tags.size() == 1) {
            // One match.
            Tag tag = tags.get(0);
            editor.pushPosition();
            if (tag instanceof LocalTag)
                gotoLocalTag(editor, (LocalTag)tag, useOtherWindow);
            else if (tag instanceof GlobalTag)
                gotoGlobalTag(editor, (GlobalTag)tag, useOtherWindow);
            else
                Debug.bug();
        } else {
            // More than one match.
            editor.setDefaultCursor();
            ListTagsBuffer buf =
                new ListTagsBuffer(editor, "findTag", expression.getName(), tags);
            editor.makeNext(buf);
            Editor otherEditor = editor.getOtherEditor();
            boolean shrink = (otherEditor == null);
            Editor ed = editor.activateInOtherWindow(buf);
            if (shrink)
                ed.shrinkWindowIfLargerThanBuffer();
            ed.setDot(buf.getInitialDotPos());
            ed.moveCaretToDotCol();
            ed.updateDisplay();
        }
        return true;
    }

    public static List<? extends Tag> findMatchingTags(Buffer buffer, Expression expression)
    {
        final Mode mode = buffer.getMode();
        if (!mode.isTaggable())
            return null;

        // We'll start by looking in the current buffer. If we find an exact
        // match there, we're done.
        List<LocalTag> localTags = findMatchingTagsInBuffer(buffer, expression);
        if (localTags != null && !localTags.isEmpty())
            return localTags;

        // No exact match in the current buffer. Look in the current
        // directory.
        final File currentDirectory = buffer.getCurrentDirectory();
        List<GlobalTag> globalTags = findMatchingTagsInDirectory(expression, currentDirectory,
                                           mode);
        if (globalTags == null) {
            // Look at all the directories in the buffer's tag path.
            List<String> dirs = getDirectoriesInTagPath(buffer);
            if (dirs != null) {
                for (String dir : dirs) {
                    File directory =
                            File.getInstance(currentDirectory, dir);
                    if (directory == null)
                        continue;
                    if (directory.equals(currentDirectory))
                        continue;
                    List<GlobalTag> tagsInDir =
                            findMatchingTagsInDirectory(expression, directory,
                                    mode);
                    if (tagsInDir != null) {
                        if (globalTags == null)
                            globalTags = new ArrayList<GlobalTag>();
                        globalTags.addAll(tagsInDir);
                    }
                }
            }
        }

        return globalTags;
    }

    private static List<LocalTag> findMatchingTagsInBuffer(Buffer buffer,
        Expression expression)
    {
        if (buffer.getTags() == null) {
            Tagger tagger = buffer.getMode().getTagger(buffer);
            if (tagger != null)
                tagger.run();
        }

        List<LocalTag> list = null;
        final List<LocalTag> localTags = buffer.getTags();
        if (localTags != null) {
            // Look through all the local tags.
            for (LocalTag localTag : localTags) {
                if (expression.matches(localTag)) {
                    if (list == null)
                        list = new ArrayList<LocalTag>();
                    list.add(localTag);
                }
            }
        }
        return list;
    }

    public static List<GlobalTag> findMatchingTagsInDirectory(Expression expression,
        File directory, Mode mode)
    {
        if (!mode.isTaggable())
            return null;
        final String name = expression.getName();
        final int arity = expression.getArity();
        List<GlobalTag> tags = Editor.getTagFileManager().getTags(directory, mode);
        if (tags == null) {
            if (!directory.isRemote())
                Editor.getTagFileManager().addToQueue(directory, mode);
            return null;
        }

        List<GlobalTag> list = null;
        for (GlobalTag tag : tags) {
            String methodName = tag.getMethodName();
            if (methodName != null && methodName.equals(name)) {
                if (arity >= 0) {
                    int n = Expression.getArity(tag.getCanonicalSignature());
                    if (n >= 0 && n != arity)
                        continue;
                }
                if (list == null)
                    list = new ArrayList<GlobalTag>();
                list.add(tag);
            }
        }
        return list;
    }

    public static List<GlobalTag> findMatchingTagsInDirectory(String name,
        File directory, Mode mode, int arity, boolean ignoreCase)
    {
        if (!mode.isTaggable())
            return null;
        List<GlobalTag> tags = Editor.getTagFileManager().getTags(directory, mode);
        if (tags == null) {
            if (!directory.isRemote())
                Editor.getTagFileManager().addToQueue(directory, mode);
            return null;
        }

        boolean isQualified = mode.isQualifiedName(name);
        List<GlobalTag> list = new ArrayList<GlobalTag>();
        for (GlobalTag tag : tags) {
            String tagName = tag.getName();
            if ((ignoreCase && tagName.equalsIgnoreCase(name)) || tagName.equals(name)) {
                if (arity >= 0) {
                    int n = Expression.getArity(tag.getCanonicalSignature());
                    if (n == -1 || n == arity) {
                        list.add(tag);
                        continue;
                    }
                } else {
                    list.add(tag);
                    continue;
                }
            }
            if (!isQualified) {
                // The name we're looking for does not have a class prefix.
                String methodName = tag.getMethodName();
                if (methodName != null) {
                    if ((ignoreCase && methodName.equalsIgnoreCase(name)) || methodName.equals(name)) {
                        if (arity >= 0) {
                            int n = Expression.getArity(tag.getCanonicalSignature());
                            if (n < 0 || n == arity)
                                list.add(tag);
                        } else
                            list.add(tag);
                    }
                }
            }
        }
        return list.size() > 0 ? list : null;
    }

    public static boolean findClass(Editor editor, String className,
        boolean useOtherWindow)
    {
        editor.setWaitCursor();
        boolean succeeded = false;
        File file = JavaSource.findSource(editor.getBuffer(), className,
                false);
        if (file != null) {
            Buffer buf = Editor.getBuffer(file);
            if (buf != null) {
                Editor ed;
                if (useOtherWindow)
                    ed = editor.displayInOtherWindow(buf);
                else
                    ed = editor;
                ed.makeNext(buf);
                ed.activate(buf);
                ed.repaintDisplay();
                List<LocalTag> localTags = buf.getTags(true);
                if (localTags != null) {
                    Position pos = null;
                    for (LocalTag localTag : localTags) {
                        JavaTag tag = (JavaTag) localTag;
                        int type = tag.getType();
                        if (type == TAG_CLASS || type == TAG_INTERFACE) {
                            String name = tag.getMethodName();
                            if (name.startsWith("class"))
                                name = name.substring(5).trim();
                            else if (name.startsWith("interface"))
                                name = name.substring(9).trim();
                            if (name.equals(className)) {
                                pos = tag.getPosition();
                                break;
                            }
                        }
                    }
                    if (pos != null) {
                        CompoundEdit compoundEdit = editor.beginCompoundEdit();
                        ed.addUndo(SimpleEdit.FOLD);
                        ed.unfoldMethod(pos.getLine());
                        ed.moveDotTo(pos);
                        centerTag(ed);
                        ed.endCompoundEdit(compoundEdit);
                        ed.updateDisplay();
                        succeeded = true;
                    }
                }
            }
        }
        editor.setDefaultCursor();
        return succeeded;
    }

    public static void listMatchingTags()
    {
        final Editor editor = Editor.currentEditor();
        FindTagDialog findTagDialog =
            new FindTagDialog(editor, "List Matching Tags");
        editor.centerDialog(findTagDialog);
        findTagDialog.show();
        listMatchingTags(editor, findTagDialog.getInput());
    }

    public static void listMatchingTags(String name)
    {
        listMatchingTags(Editor.currentEditor(), name);
    }

    public static void listMatchingTagsAtDot()
    {
        final Editor editor = Editor.currentEditor();
        listMatchingTags(editor,
                         editor.getMode().getIdentifier(editor.getDot()));
    }

    private static void listMatchingTags(Editor editor, String name)
    {
        if (name != null && name.length() > 0) {
            editor.repaintNow();
            editor.setWaitCursor();
            final Buffer buffer = editor.getBuffer();
            List<? extends Tag> tags = findMatchingTagsInDirectory(name,
                buffer.getCurrentDirectory(), buffer.getMode(), -1,
                Utilities.isLowerCase(name));
            editor.setDefaultCursor();
            if (tags != null) {
                ListTagsBuffer buf =
                    new ListTagsBuffer(editor, "listMatchingTags", name, tags);
                editor.makeNext(buf);
                Editor otherEditor = editor.getOtherEditor();
                boolean shrink = (otherEditor == null);
                Editor ed = editor.activateInOtherWindow(buf);
                if (shrink)
                    ed.shrinkWindowIfLargerThanBuffer();
                ed.setDot(buf.getInitialDotPos());
                ed.moveCaretToDotCol();
                ed.updateDisplay();
            } else
                editor.status("Tag \"" + name + "\" not found");
        }
    }

    public static void gotoLocalTag(Editor editor, LocalTag localTag,
        boolean useOtherWindow)
    {
        Editor ed;
        if (useOtherWindow)
            ed = editor.displayInOtherWindow(editor.getBuffer());
        else
            ed = editor;
        localTag.gotoTag(ed);
    }

    public static void gotoGlobalTag(Editor editor, GlobalTag globalTag,
        boolean useOtherWindow)
    {
        Buffer buf = Editor.getBuffer(File.getInstance(globalTag.getFileName()));
        Editor ed;
        if (useOtherWindow)
            ed = editor.displayInOtherWindow(buf);
        else
            ed = editor;
        globalTag.gotoTag(ed);
    }

    public static List<String> getDirectoriesInTagPath(Buffer buffer)
    {
        String tagPath = buffer.getStringProperty(Property.TAG_PATH);
        if (tagPath == null)
            return null;
        return Utilities.getDirectoriesInPath(tagPath);
    }

    public static void centerTag()
    {
        centerTag(Editor.currentEditor());
    }

    public static void centerTag(Editor editor)
    {
        final Buffer buffer = editor.getBuffer();
        final List<LocalTag> tags = buffer.getTags();
        if (tags == null)
            return;
        int dotLineNumber = editor.getDotLineNumber();
        Line begin = null;
        Line end = null;
        for (LocalTag tag : tags) {
            if (tag.lineNumber() <= dotLineNumber) {
                begin = tag.getLine();
            } else {
                // tag.lineNumber() > dotLineNumber
                end = tag.getLine();
                break;
            }
        }
        if (begin != null)
            editor.getDisplay().centerRegion(begin, end);
    }

    public static void makeTagFile()
    {
        final Editor editor = Editor.currentEditor();
        File directory = editor.getCurrentDirectory();
        if (directory.isRemote()) {
            MessageDialog.showMessageDialog(
                "Tag files are not supported for remote directories",
                "Make Tag File");
            return;
        }
        Mode mode = editor.getMode();
        if (!mode.isTaggable()) {
            MessageDialog.showMessageDialog(
                "Tag files are not supported in " + mode + " mode",
                "Make Tag File");
            return;
        }
        editor.repaintNow();
        editor.setWaitCursor();
        Editor.getTagFileManager().makeTagFile(directory, mode);
        editor.setDefaultCursor();
        MessageDialog.showMessageDialog("Tag file is ready", "Make Tag File");
    }

    public static void findTagAtDot()
    {
        findTagAtDotInternal(Editor.currentEditor(), false, false);
    }

    public static void findTagAtDotOtherWindow()
    {
        findTagAtDotInternal(Editor.currentEditor(), false, true);
    }

    public static void mouseFindTag()
    {
        final Editor editor = Editor.currentEditor();
        AWTEvent e = editor.getDispatcher().getLastEvent();
        if (e instanceof MouseEvent) {
            editor.mouseMoveDotToPoint((MouseEvent)e);
            findTagAtDotInternal(editor, true, false);
        }
    }

    private static void findTagAtDotInternal(Editor editor, boolean exact,
                                             boolean useOtherWindow)
    {
        Expression expr = editor.getMode().getExpressionAtDot(editor, exact);
        if (expr != null) {
            editor.setWaitCursor();
            boolean succeeded = findTag(editor, expr, useOtherWindow);
            if (!succeeded && editor.getModeId() == C_MODE) {
                // Special case for Emacs source.
                // If name is "Frun_hooks", look for "run-hooks".
                String name = expr.getName();
                if (name != null && name.length() > 0 && name.charAt(0) == 'F')
                    name = name.substring(1).replace('_', '-');
                    succeeded = findTag(editor, new Expression(name),
                        useOtherWindow);
            }
            if (!succeeded)
                editor.status("Tag \"" + expr.getName() + "\" not found");
            editor.setDefaultCursor();
        } else
            findTag();
    }
}
