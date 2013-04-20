/*
 * StackPanel.java
 *
 * Copyright (C) 2002-2003 Peter Graves
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

package org.armedbear.j.jdb;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.EditorIterator;
import org.armedbear.j.File;
import org.armedbear.j.util.FastStringBuffer;
import org.armedbear.j.mode.java.JavaSource;
import org.armedbear.j.Log;

public final class StackPanel implements ContextListener, MouseListener
{
    private final Jdb jdb;
    private final JdbControlDialog dialog;
    private final JList<String> list;
    private final JScrollPane scrollPane;

    private List<StackFrame> frames;

    public StackPanel(Jdb jdb, JdbControlDialog dialog)
    {
        this.jdb = jdb;
        this.dialog = dialog;
        Vector<String> v = new Vector<String>();
        list = new JList<String>(v);
        scrollPane = new JScrollPane(list);
        jdb.addContextListener(this);
        list.addMouseListener(this);
    }

    public Component getComponent()
    {
        return scrollPane;
    }

    public void contextChanged()
    {
        ThreadReference threadRef = jdb.getCurrentThread();
        if (threadRef != null) {
            try {
                frames = threadRef.frames();
                final Vector<String> v = new Vector<String>();
                int selectedIndex = -1;
                if (frames.size() > 0) {
                    StackFrame currentStackFrame = jdb.getCurrentStackFrame();
                    if (currentStackFrame == null) {
                        currentStackFrame = frames.get(0);
                        jdb.setCurrentStackFrame(currentStackFrame);
                    }
                    int index = 1;
                    for (StackFrame frame : frames) {
                        if (frame != null && frame.equals(currentStackFrame))
                            selectedIndex = index - 1;
                        Location location = frame.location();
                        Method method = location.method();
                        FastStringBuffer sb = new FastStringBuffer();
                        if (index < 10)
                            sb.append(' ');
                        sb.append(index++);
                        sb.append(' ');
                        sb.append(getSimpleName(method.declaringType()));
                        sb.append('.');
                        sb.append(method.name());
                        if (method.isNative()) {
                            sb.append(" (native method)");
                        } else {
                            String sourceName = null;
                            try {
                                sourceName = location.sourceName();
                            }
                            catch (AbsentInformationException ignored) { }
                            int lineNumber = location.lineNumber();
                            if (sourceName != null && sourceName.length() > 0) {
                                sb.append(" (");
                                sb.append(sourceName);
                                if (lineNumber > 0) {
                                    sb.append(':');
                                    sb.append(lineNumber);
                                }
                                sb.append(')');
                            }
                        }
                        v.add(sb.toString());
                    }
                }
                final int finalSelectedIndex = selectedIndex;
                // Update UI in event dispatch thread.
                Runnable r = new Runnable() {
                    public void run()
                    {
                        list.setListData((Vector)v);
                        list.setSelectedIndex(finalSelectedIndex);
                    }
                };
                SwingUtilities.invokeLater(r);
            }
            catch (Exception e) {
                Log.error(e);
            }
        }
    }

    private static String getSimpleName(ReferenceType refType)
    {
        String name = refType.name();
        int index = name.lastIndexOf('.');
        if (index >= 0)
            return name.substring(index+1);
        else
            return name;
    }

    public void mousePressed(MouseEvent e)
    {
        if (!jdb.isSuspended())
            return;

        // Mask off the bits we don't care about (Java 1.4).
        int modifiers = e.getModifiers() & 0x1f;

        if (modifiers == InputEvent.BUTTON1_MASK ||
            modifiers == InputEvent.BUTTON2_MASK) {
            if (modifiers == InputEvent.BUTTON2_MASK)
                list.setSelectedIndex(list.locationToIndex(e.getPoint()));
            list.paintImmediately(0, 0, list.getWidth(), list.getHeight());
            int index = list.getSelectedIndex();
            if (frames != null && index >= 0 && index < frames.size()) {
                StackFrame stackFrame = frames.get(index);
                jdb.setCurrentStackFrame(stackFrame);
                Location location = stackFrame.location();
                Method method = location.method();
                if (method != null && !method.isNative()) {
                    String className = location.declaringType().name();
                    int i = className.indexOf('$');
                    if (i > 0)
                        className = className.substring(0, i);
                    File file =
                        JavaSource.findSource(className, jdb.getSourcePath());
                    if (file != null) {
                        Buffer buffer = Editor.getBuffer(file);
                        if (buffer != null) {
                            Editor editor = null;
                            for (EditorIterator it = new EditorIterator(); it.hasNext();) {
                                Editor ed = it.next();
                                if (ed.getBuffer() instanceof Jdb) {
                                    editor = ed;
                                    break;
                                }
                            }
                            if (editor != null) {
                                editor.makeNext(buffer);
                                editor = editor.activateInOtherWindow(buffer);
                            } else {
                                editor = Editor.currentEditor();
                                editor.makeNext(buffer);
                                editor.activate(buffer);
                            }
                            int lineNumber = location.lineNumber();
                            if (lineNumber > 0) {
                                editor.jumpToLine(lineNumber - 1);
                                editor.updateDisplay();
                            }
                        }
                    }
                }
            }
        }
        dialog.requestDefaultFocus();
    }

    public void mouseReleased(MouseEvent e) {}

    public void mouseClicked(MouseEvent e) {}

    public void mouseEntered(MouseEvent e) {}

    public void mouseExited(MouseEvent e) {}
}
