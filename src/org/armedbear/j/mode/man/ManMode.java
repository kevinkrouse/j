// ManMode.java
//
// Copyright (C) 2000-2006 Peter Graves <peter@armedbear.org>
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.

package org.armedbear.j.mode.man;

import static org.armedbear.j.Constants.*;

import java.awt.AWTEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import javax.swing.JPopupMenu;
import org.armedbear.j.AbstractMode;
import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Formatter;
import org.armedbear.j.KeyMap;
import org.armedbear.j.Line;
import org.armedbear.j.LineSegment;
import org.armedbear.j.LineSegmentList;
import org.armedbear.j.Log;
import org.armedbear.j.Mode;
import org.armedbear.j.Platform;
import org.armedbear.j.Property;
import org.armedbear.j.SystemBuffer;
import org.armedbear.j.Tagger;
import org.armedbear.j.util.Utilities;

public final class ManMode extends AbstractMode implements Mode {
    private static final ManMode mode = new ManMode();

    private ManMode() {
        super(MAN_MODE, MAN_MODE_NAME);
        setProperty(Property.VERTICAL_RULE, 0);
        setProperty(Property.SHOW_LINE_NUMBERS, false);
        setProperty(Property.HIGHLIGHT_MATCHING_BRACKET, false);
        setProperty(Property.HIGHLIGHT_BRACKETS, false);
    }

    public static final ManMode getMode() {
        return mode;
    }

    @Override
    public JPopupMenu getContextMenu(Editor editor) {
        return null;
    }

    @Override
    public Formatter getFormatter(Buffer buffer) {
        if (buffer.getType() != Buffer.TYPE_MAN)
            return null;
        return new ManFormatter(buffer, ((ManBuffer) buffer).isApropos());
    }

    @Override
    public String getTransientTag() {
        return "Man";
    }

    @Override
    protected void setKeyMapDefaults(KeyMap km) {
        km.mapKey(KeyEvent.VK_ENTER, 0, "manFollowLink");
        km.mapKey(KeyEvent.VK_G, CTRL_MASK | SHIFT_MASK, "manFollowLink");
        km.mapKey(VK_DOUBLE_MOUSE_1, 0, "manFollowLink");
        km.mapKey(VK_MOUSE_2, 0, "manFollowLink");
    }

    @Override
    public Tagger getTagger(SystemBuffer buffer) {
        return new ManTagger(buffer);
    }

    @Override
    public boolean isTaggable() {
        return true;
    }

    private static void followLink(Editor editor) {
        if (editor.getBuffer().getType() == Buffer.TYPE_MAN) {
            final ManBuffer manBuffer = (ManBuffer) editor.getBuffer();
            final Line line = editor.getDotLine();
            if (manBuffer.isApropos()) {
                String topic = null;
                String section = null;
                String text = line.getText();
                int index = text.indexOf(' ');
                if (index >= 0) {
                    topic = text.substring(0, index);
                    text = text.substring(index);
                    index = text.indexOf('(');
                    if (index >= 0) {
                        int begin = index + 1;
                        int end = text.indexOf(')', begin);
                        if (end >= 0)
                            section = text.substring(begin, end);
                    }
                }
                if (topic != null) {
                    if (section != null && section.length() > 0)
                        man(editor, section + " " + topic);
                    else
                        man(editor, topic);
                }
            } else {
                LineSegmentList segmentList = manBuffer.getFormatter().formatLine(line);
                int col = editor.getDotCol();
                int startCol = 0;
                for (int i = 0; i < segmentList.size(); i++) {
                    LineSegment segment = segmentList.getSegment(i);
                    if (startCol <= col && col < startCol + segment.length()) {
                        String s;
                        if (segment.getFormat() != 0) {
                            // Segment is highlighted. Use the whole segment.
                            s = segment.getText();
                        } else {
                            // Not highlighted.
                            s = editor.getFilenameAtDot();
                        }
                        if (s != null) {
                            if (s.startsWith("/")) {
                                // Looks like a Unix filename.
                                Buffer buf = editor.openFile(File.getInstance(s));
                                if (buf != null) {
                                    editor.makeNext(buf);
                                    editor.activate(buf);
                                }
                            } else
                                man(editor, s);
                        }
                        break;
                    }
                    startCol += segment.length();
                }
            }
        }
    }

    public static String getTitle(String topic) {
        return "man " + topic;
    }

    public static void man() {
        if (!Platform.isPlatformUnix())
            return;
        final Editor editor = Editor.currentEditor();
        ManDialog d = new ManDialog(editor);
        editor.centerDialog(d);
        d.setVisible(true);
        String topic = d.getInput();
        if (topic != null && topic.length() != 0)
            man(topic);
    }

    public static void man(String topic) {
        if (!Platform.isPlatformUnix())
            return;
        man(Editor.currentEditor(), topic);
    }

    private static void man(Editor editor, String topic) {
        editor.setWaitCursor();
        try {
            final String title = ManMode.getTitle(topic);
            for (Buffer buf : Editor.getBufferList()) {
                if (buf.getModeId() == MAN_MODE && title.equals(buf.getTitle())) {
                    editor.makeNext(buf);
                    editor.switchToBuffer(buf);
                    return;
                }
            }
            File tempFile = Utilities.getTempFile();
            ArrayList<String> cmd = new ArrayList<>();
            cmd.add("man");
            for (String word : topic.trim().split("\\s+")) {
                // Topics come from page text: a section and a name, or -k
                // for apropos, never another option.
                if (word.startsWith("-") && !word.equals("-k")) {
                    editor.status("Not a topic: " + word);
                    return;
                }
                cmd.add(word);
            }
            try {
                Process process =
                        new ProcessBuilder(cmd).redirectOutput(new java.io.File(tempFile.canonicalPath())).start();
                process.waitFor();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            catch (IOException e) {
                Log.error(e);
            }
            if (tempFile.isFile() && tempFile.length() > 0) {
                ManBuffer manBuffer = new ManBuffer(topic, tempFile);
                manBuffer.load();
                tempFile.delete();
                editor.makeNext(manBuffer);
                editor.switchToBuffer(manBuffer);
            } else
                editor.status("No entry");
        }
        finally {
            editor.setDefaultCursor();
        }
    }

    public static void manFollowLink() {
        final Editor editor = Editor.currentEditor();
        final Buffer buffer = editor.getBuffer();
        if (buffer.getType() != Buffer.TYPE_MAN)
            return;
        // If this method is invoked via a mouse event mapping, move dot to
        // location of mouse click before following link.
        AWTEvent e = editor.getDispatcher().getLastEvent();
        if (e instanceof MouseEvent mouseEvent)
            editor.mouseMoveDotToPoint(mouseEvent);
        followLink(editor);
    }
}
