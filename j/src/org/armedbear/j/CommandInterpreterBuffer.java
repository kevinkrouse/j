/*
 * CommmandInterpreter.java
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

import org.armedbear.j.util.ReaderThread;
import org.armedbear.j.util.Utilities;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.regex.Matcher;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import javax.swing.undo.CompoundEdit;

public class CommandInterpreterBuffer extends Buffer
{
    protected Pattern promptRE = Pattern.compile(DEFAULT_SHELL_PROMPT_PATTERN);
    protected OutputStreamWriter stdin;
    protected ReaderThread stdoutThread;
    protected ReaderThread stderrThread;
    protected History history;
    protected String input;
    protected boolean stripEcho;
    protected String shellCommand;

    private Position posEndOfOutput;

    protected CommandInterpreterBuffer()
    {
        super();
        initializeUndo();
        initializeHistory();
    }

    public final String getShellCommand()
    {
        return shellCommand;
    }

    public boolean isLisp()
    {
        return false;
    }

    public final boolean isModified()
    {
        return false;
    }

    protected void initializeHistory()
    {
        history = new History(null, 30);
    }

    public final Pattern getPromptRE()
    {
        return promptRE;
    }

    protected final void setPromptRE(String pattern)
    {
        try {
            promptRE = Pattern.compile(pattern);
        }
        catch (PatternSyntaxException e) {
            Log.error(e);
        }
    }

    protected final synchronized Position getEndOfOutput()
    {
        if (posEndOfOutput == null)
            return null;
        if (contains(posEndOfOutput.getLine())) {
            if (posEndOfOutput.getOffset() > posEndOfOutput.getLineLength())
                posEndOfOutput.setOffset(posEndOfOutput.getLineLength());
            return posEndOfOutput;
        }
        // The original end-of-output line has been removed from the buffer.
        Pattern promptRE = getPromptRE();
        if (promptRE != null) {
            Position eob = getEnd();
            if (eob == null)
                return null;
            Line line = eob.getLine();
            while (line != null) {
                int flags = line.flags();
                if (flags == 0 || flags == STATE_PROMPT || flags == STATE_INPUT) {
                    final Matcher matcher = promptRE.matcher(line.getText());
                    if (matcher.find() && matcher.start() == 0) {
                        return new Position(line, matcher.end());
                    }
                }
                line = line.previous();
            }
        }
        return null;
    }

    protected final synchronized void setEndOfOutput(Position pos)
    {
        posEndOfOutput = pos;
    }

    public Icon getIcon()
    {
        return Utilities.getIconFromFile("jpty.png");
    }

    public int load()
    {
        try {
            lockWrite();
        }
        catch (InterruptedException e) {
            Log.debug(e);
            return LOAD_FAILED; // Shouldn't happen.
        }
        try {
            appendLine("");
            setLoaded(true);
        }
        finally {
            unlockWrite();
        }
        return LOAD_COMPLETED;
    }

    // Returns true if underlying process is alive and well.
    protected boolean checkProcess()
    {
        return true;
    }

    protected void enter()
    {
        if (!checkProcess())
            return;
        final Editor editor = Editor.currentEditor();
        final Line dotLine = editor.getDotLine();
        Position endOfOutput = getEndOfOutput();
        if (endOfOutput == null) {
            // Ignore input before first prompt is displayed.
            dotLine.setText("");
            return;
        }
        if (endOfOutput.getLine() == dotLine) {
            if (endOfOutput.getOffset() < dotLine.length())
                input = dotLine.getText().substring(endOfOutput.getOffset());
            else
                input = "";
        } else {
            // We're not at the end of the buffer.
            input = stripPrompt(dotLine.getText());
        }
        if (input.length() != 0) {
            history.append(input);
            history.save();
        }
        enter(input);
    }

    protected void enter(final String s)
    {
        final Editor editor = Editor.currentEditor();
        Line dotLine = editor.getDotLine();
        if (dotLine.next() != null) {
            // Go to end of buffer (if we're not already there) to append input.
            editor.eob();
            dotLine = editor.getDotLine();
            // Keep the prompt, but throw away anything after it.
            final Matcher matcher = promptRE.matcher(dotLine.getText());
            if (matcher.find())
                dotLine.setText(dotLine.substring(0, matcher.end()));
            // Append s.
            dotLine.setText(dotLine.getText() + s);
        }
        if (dotLine.flags() == 0)
            dotLine.setFlags(STATE_INPUT);
        editor.eol();
        editor.insertLineSeparator();
        if (needsRenumbering)
            renumber();
        editor.getDotLine().setFlags(0);
        editor.moveCaretToDotCol();
        editor.getDisplay().setReframe(-2);
        resetUndo();
        stripEcho = true;
        send(s);
    }

    protected void send(final String s)
    {
        try {
            stdin.write(s);
            if (!s.endsWith("\n"))
                stdin.write("\n");
            stdin.flush();
        }
        catch (IOException e) {
            Log.error(e);
        }
    }

    protected String stripPrompt(String s)
    {
        if (promptRE != null) {
            Matcher matcher = promptRE.matcher(s);
            if (matcher.find())
                return s.substring(matcher.end());
        }
        // Look for login name or password prompt.
        Pattern re = Pattern.compile(".*: ?");
        Matcher matcher = re.matcher(s);
        if (matcher.find())
            return s.substring(matcher.end());
        return s;
    }

    protected void escape()
    {
        Editor editor = Editor.currentEditor();
        Position endOfOutput = getEndOfOutput();
        if (editor.getMark() != null || endOfOutput == null ||
            editor.getDot().isBefore(endOfOutput))
        {
            // There's a marked block, or we're not at the command line.
            editor.escape();
            return;
        }
        if (editor.escapeInternal())
            return;
        CompoundEdit compoundEdit = beginCompoundEdit();
        editor.addUndo(SimpleEdit.MOVE);
        editor.moveDotTo(endOfOutput);
        editor.moveCaretToDotCol();
        editor.setMark(getEnd());
        editor.deleteRegion();
        endCompoundEdit(compoundEdit);
        // BUG! Undo/redo delete region doesn't preserve markers correctly!
        resetUndo();
    }

    protected void home()
    {
        final Editor editor = Editor.currentEditor();
        if (editor.getDotOffset() == 0)
            return;
        editor.addUndo(SimpleEdit.MOVE);
        editor.beginningOfBlock();
        int offset = 0;
        if (promptRE != null) {
            Line dotLine = editor.getDotLine();
            if (dotLine.next() == null || dotLine.flags() == STATE_INPUT) {
                Matcher matcher = promptRE.matcher(dotLine.getText());
                if (matcher.find())
                    offset = matcher.end();
            }
        }
        // If we're already at the prompt or to the left of it, go to column 0.
        if (editor.getDotOffset() <= offset)
            offset = 0;
        editor.getDot().setOffset(offset);
        editor.getDisplay().moveCaretToDotCol();
    }

    protected void backspace()
    {
        Position endOfOutput = getEndOfOutput();
        if (endOfOutput == null)
            return;
        boolean ok = true;
        final Editor editor = Editor.currentEditor();
        if (editor.getDotLine() == endOfOutput.getLine()) {
            if (editor.getDotOffset() <= endOfOutput.getOffset())
                ok = false;
        } else{
            String text = editor.getDotLine().getText();
            if (promptRE != null) {
                Matcher matcher = promptRE.matcher(text);
                if (matcher.find()) {
                    if (editor.getDotOffset() <= matcher.end())
                        ok = false;
                }
            }
        }
        if (ok)
            editor.backspace();
    }

    private void previousInput()
    {
        getInputFromHistory(-1);
    }

    private void nextInput()
    {
        getInputFromHistory(1);
    }

    private String currentInput;

    private void getInputFromHistory(int direction)
    {
        if (getEndOfOutput() == null) {
            // No prompt yet.
            return;
        }
        final Editor editor = Editor.currentEditor();
        final Line dotLine = editor.getDotLine();
        if (dotLine.next() != null) {
            editor.status("Not at command prompt");
            return;
        }
        if (editor.getLastCommand() != COMMAND_HISTORY) {
            history.reset();
            Position begin = getEndOfOutput().copy();
            Position end = getEnd();
            Region r = new Region(editor.getBuffer(), begin, end);
            currentInput = r.toString();
        }
        String s;
        while (true) {
            s = direction < 0 ? history.getPrevious() : history.getNext();
            if (s == null)
                break;
            s = s.trim();
            if (s.equals(currentInput))
                continue;
            if (currentInput.length() == 0 || s.startsWith(currentInput))
                break;
        }
        if (s != null) {
            CompoundEdit compoundEdit = beginCompoundEdit();
            editor.addUndo(SimpleEdit.MOVE);
            editor.setDot(getEndOfOutput().copy());
            editor.setMark(getEnd());
            editor.deleteRegion();
            editor.addUndo(SimpleEdit.INSERT_STRING);
            editor.insertStringInternal(s);
            editor.moveCaretToDotCol();
            endCompoundEdit(compoundEdit);
            // BUG! Undo/redo delete region doesn't preserve markers correctly!
            resetUndo();
        }
        for (Line line = getEndOfOutput().getLine(); line != null; line = line.next())
            line.setFlags(STATE_INPUT);
        editor.setCurrentCommand(COMMAND_HISTORY);
    }

    protected void appendString(String s)
    {
        try {
            lockWrite();
        }
        catch (InterruptedException e) {
            Log.error(e);
            return;
        }
        try {
            Position pos = getEnd();
            if (pos != null) {
                insertString(pos, s);
                if (needsRenumbering())
                    renumber();
                enforceOutputLimit(Property.SHELL_OUTPUT_LIMIT);
                setEndOfOutput(pos.copy());
            } else {
                setText(s);
                setEndOfOutput(getEnd().copy());
            }
        }
        finally {
            unlockWrite();
        }
    }

    protected void updateDisplayInAllFrames()
    {
        for (EditorIterator it = new EditorIterator(); it.hasNext();) {
            Editor ed = it.next();
            if (ed.getBuffer() == this) {
                ed.eob();
                ed.getDisplay().setReframe(-2);
                ed.setUpdateFlag(REPAINT);
                ed.updateDisplay();
            } else {
                View view = ed.getView(this);
                if (view != null) {
                    Position end = getEnd();
                    view.setDot(end);
                    view.setCaretCol(getCol(end));
                    view.setMark(null);
                }
            }
        }
    }

    protected void sendChar(int c)
    {
        final Editor editor = Editor.currentEditor();
        final Line dotLine = editor.getDotLine();
        if (dotLine.next() == null)
            dotLine.setFlags(STATE_INPUT);
        try {
            stdin.write(c);
            stdin.flush();
        }
        catch (IOException e) {
            Log.error(e);
        }
    }

    protected String stdOutFilter(String s)
    {
        return s;
    }

    protected void stdOutUpdate(final String s)
    {
        Runnable r = new Runnable() {
            public void run()
            {
                appendString(s);
                updateDisplayInAllFrames();
                resetUndo();
            }
        };
        SwingUtilities.invokeLater(r);
    }

    protected String stdErrFilter(String s)
    {
        return s;
    }

    protected void stdErrUpdate(final String s)
    {
        Runnable r = new Runnable() {
            public void run()
            {
                appendString(s);
                updateDisplayInAllFrames();
                resetUndo();
            }
        };
        SwingUtilities.invokeLater(r);
    }

    private String removeEcho(String s) {
        if (stripEcho && input != null && s.startsWith(input)) {
            int begin = input.length();
            if (s.length() > begin && s.charAt(begin) == '\r')
                ++begin;
            if (s.length() > begin && s.charAt(begin) == '\n')
                ++begin;
            s = s.substring(begin);
            // Strip echo only once per command line.
            stripEcho = false;
        }
        return s;
    }

    protected class StdoutThread extends ReaderThread
    {
        public StdoutThread(InputStream stdout)
        {
            super(stdout);
        }

        public String filter(String s)
        {
            return stdOutFilter(s);
        }

        public void update(String s)
        {
            if (s != null && s.length() > 0)
                stdOutUpdate(s);
        }
    }

    protected class StderrThread extends ReaderThread
    {
        public StderrThread(InputStream stderr)
        {
            super(stderr);
        }

        public String filter(String s)
        {
            return stdErrFilter(s);
        }

        public void update(String s)
        {
            if (s != null && s.length() > 0)
                stdErrUpdate(s);
        }
    }

    // Commands.
    public static void shellEnter()
    {
        final Buffer buffer = Editor.currentEditor().getBuffer();
        if (buffer instanceof CommandInterpreterBuffer)
            ((CommandInterpreterBuffer)buffer).enter();
    }

    public static void shellEscape()
    {
        final Buffer buffer = Editor.currentEditor().getBuffer();
        if (buffer instanceof CommandInterpreterBuffer)
            ((CommandInterpreterBuffer)buffer).escape();
    }

    public static void shellHome()
    {
        final Buffer buffer = Editor.currentEditor().getBuffer();
        if (buffer instanceof CommandInterpreterBuffer)
            ((CommandInterpreterBuffer)buffer).home();
    }

    public static void shellBackspace()
    {
        final Buffer buffer = Editor.currentEditor().getBuffer();
        if (buffer instanceof CommandInterpreterBuffer)
            ((CommandInterpreterBuffer)buffer).backspace();
    }

    public static void shellPreviousInput()
    {
        final Buffer buffer = Editor.currentEditor().getBuffer();
        if (buffer instanceof CommandInterpreterBuffer)
            ((CommandInterpreterBuffer)buffer).previousInput();
    }

    public static void shellNextInput()
    {
        final Buffer buffer = Editor.currentEditor().getBuffer();
        if (buffer instanceof CommandInterpreterBuffer)
            ((CommandInterpreterBuffer)buffer).nextInput();
    }

    public static void shellPreviousPrompt()
    {
        findPrompt(-1);
    }

    public static void shellNextPrompt()
    {
        findPrompt(1);
    }

    private static final void findPrompt(int direction)
    {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer instanceof CommandInterpreterBuffer) {
            Position dot = editor.getDot();
            if (dot != null) {
                Line line =
                    direction > 0 ? dot.getLine().next() : dot.getLine().previous();
                Pattern promptRE = ((CommandInterpreterBuffer)buffer).getPromptRE();
                if (promptRE != null) {
                    while (line != null) {
                        int flags = line.flags();
                        if (flags == STATE_PROMPT || flags == STATE_INPUT) {
                            final Matcher matcher = promptRE.matcher(line.getText());
                            if (matcher.find() && matcher.start() == 0) {
                                Position pos = new Position(line, matcher.end());
                                editor.moveDotTo(pos);
                                return;
                            }
                        }
                        line = direction > 0 ? line.next() : line.previous();
                    }
                }
            }
        }
    }
}
