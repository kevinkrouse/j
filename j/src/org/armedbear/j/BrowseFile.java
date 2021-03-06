/*
 * BrowseFile.java
 *
 * Copyright (C) 1998-2002 Peter Graves
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

import org.armedbear.j.mode.web.WebBuffer;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.io.IOException;

public final class BrowseFile implements Constants
{
    public static void browseFileAtDot()
    {
        String browser =
            Editor.preferences().getStringProperty(Property.BROWSER);
        if (browser == null)
            browser = "j";
        final Editor editor = Editor.currentEditor();
        String filename = browseFileGetFilename(editor);
        if (filename == null)
            return;
        File file = null;
        if (!filename.startsWith("http://") && !filename.startsWith("https://")) {
            final Buffer buffer = editor.getBuffer();
            if (buffer.getFile() != null) {
                String prefix = buffer.getFile().netPath();
                if (prefix.startsWith("http://") || prefix.startsWith("https://"))
                    filename = File.appendNameToPath(prefix, filename, '/');
            }
            if (!filename.startsWith("http://") && !filename.startsWith("https://")) {
                file = File.getInstance(editor.getCurrentDirectory(), filename);
                if (file != null && file.isLocal() && file.isFile())
                    filename = "file://" + file.canonicalPath();
                else
                    return;
            }
        }
        if (browser.equals("j")) {
            if (file != null)
                WebBuffer.browse(editor, file, null);
            else
                WebBuffer.browse(editor, File.getInstance(filename), null);
            return;
        }
        // External browser.
        String browserOpts =
            Editor.preferences().getStringProperty(Property.BROWSER_OPTS);
        try {
            if (browserOpts != null) {
                String[] cmdarray = {browser, browserOpts, filename};
                Runtime.getRuntime().exec(cmdarray);
            } else {
                String[] cmdarray = {browser, filename};
                Runtime.getRuntime().exec(cmdarray);
            }
        }
        catch (IOException e) {
            Log.error(e);
        }
    }

    private static String browseFileGetFilename(Editor editor)
    {
        if (editor.getMark() != null && editor.getMarkLine() == editor.getDotLine()) {
            // Use selection.
            return new Region(editor).toString();
        }
        if (editor.getModeId() == HTML_MODE) {
            String href = getHref(editor.getDotLine().getText(), editor.getDotOffset());
            if (href != null)
                return href;
        }
        return editor.getFilenameAtDot();
    }

    static String getHref(String text, int dotOffset)
    {
        Pattern re = Pattern.compile("(href|src)=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher m = re.matcher(text);
        int index = 0;
        String href = null;
        while (m.find(index)) {
            href = m.group(2);
            if (m.end() > dotOffset)
                break; // All subsequent matches will be further away.
            index = m.end();
        }
        return href;
    }
}
