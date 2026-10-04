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

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.armedbear.j.mode.web.WebBuffer;

public final class BrowseFile implements Constants {
    public static void browseFileAtDot() {
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
        openUrl(filename);
    }

    /**
     * Opens a URL in the browser the browser preference names: unset, the
     * desktop's; "j", j's own; else that command, given browserOpts, split
     * at white space, before the URL.
     */
    public static void openUrl(String url) {
        final Preferences prefs = Editor.preferences();
        final String browser = prefs.getStringProperty(Property.BROWSER);
        try {
            if ("j".equals(browser)) {
                final String path = url.startsWith("file://") ? url.substring(7) : url;
                WebBuffer.browse(Editor.currentEditor(), File.getInstance(path), null);
                return;
            }
            if (browser != null) {
                final List<String> command = new ArrayList<String>();
                command.add(browser);
                final String opts = prefs.getStringProperty(Property.BROWSER_OPTS);
                if (opts != null && !opts.trim().isEmpty())
                    command.addAll(Arrays.asList(opts.trim().split("\\s+")));
                command.add(url);
                Runtime.getRuntime().exec(command.toArray(new String[command.size()]));
                return;
            }
            if (Desktop.isDesktopSupported()) {
                final Desktop desktop = Desktop.getDesktop();
                final Desktop.Action action = url.startsWith("mailto:")
                    ? Desktop.Action.MAIL
                    : Desktop.Action.BROWSE;
                if (desktop.isSupported(action)) {
                    // It can take a while to start a browser.
                    final Thread thread = new Thread(() -> {
                        try {
                            if (action == Desktop.Action.MAIL)
                                desktop.mail(new URI(url));
                            else
                                desktop.browse(new URI(url));
                        }
                        catch (Exception e) {
                            Log.error(e);
                        }
                    }, "openUrl " + url);
                    thread.setDaemon(true);
                    thread.start();
                    return;
                }
            }
            Runtime.getRuntime()
                .exec(
                    new String[] {
                        Platform.isPlatformMacOSX() ? "open" : "xdg-open", url }
                );
        }
        catch (IOException e) {
            Log.error(e);
        }
    }

    private static String browseFileGetFilename(Editor editor) {
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

    static String getHref(String text, int dotOffset) {
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
