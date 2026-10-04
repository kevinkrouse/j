/*
 * StatusBarProgressNotifier.java
 *
 * Copyright (C) 2000-2002 Peter Graves
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

import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Shows a background task's progress in the status bar every half second. */
public class StatusBarProgressNotifier implements Cancellable, ProgressNotifier {
    private final Buffer buffer;
    private Timer timer;
    private long startMillis;
    private volatile long totalBytes;
    private volatile long fileSize;
    private volatile String prefix;
    private volatile boolean cancelled;
    private volatile String progressText;

    public StatusBarProgressNotifier(Buffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public boolean cancelled() {
        return cancelled;
    }

    @Override
    public synchronized void progressStart() {
        if (timer == null) {
            startMillis = System.currentTimeMillis();
            timer = new Timer(500, e -> tick());
            timer.start();
        }
    }

    @Override
    public synchronized void progressStop() {
        if (timer != null)
            timer.stop();
    }

    @Override
    public void progress(String prefix, long totalBytes, long fileSize) {
        this.prefix = prefix;
        this.totalBytes = totalBytes;
        this.fileSize = fileSize;
    }

    @Override
    public void progress(String progressText) {
        this.progressText = progressText;
    }

    @Override
    public void setText(final String s) {
        progressText = s;
        if (s != null)
            update();
    }

    private void update() {
        if (SwingUtilities.isEventDispatchThread())
            showStatus();
        else
            SwingUtilities.invokeLater(this::showStatus);
    }

    private void showStatus() {
        for (Editor ed : Editor.getEditorList()) {
            if (ed.getBuffer() == buffer)
                ed.status(progressText);
        }
    }

    private void tick() {
        // Read once: the loading thread may change them meanwhile.
        String prefix = this.prefix;
        long totalBytes = this.totalBytes;
        long fileSize = this.fileSize;
        if (prefix != null && totalBytes != 0) {
            long elapsed = System.currentTimeMillis() - startMillis;
            setText(getProgressText(prefix, totalBytes, fileSize, elapsed));
        } else
            update();
    }

    private static String getProgressText(String prefix, long totalBytes, long fileSize, long elapsed) {
        if (elapsed == 0)
            return null;
        StringBuilder sb = new StringBuilder(prefix);
        if (fileSize > 0) {
            final long percent = (totalBytes * 100) / fileSize;
            if (percent >= 100)
                return null;
            final long rate = (totalBytes * 1000) / elapsed; // bytes per second
            final long projected = (fileSize * elapsed) / totalBytes; // milliseconds
            long seconds = (projected - elapsed) / 1000;
            long minutes = seconds / 60;
            if (minutes != 0)
                seconds = seconds % 60;
            final long hours = minutes / 60;
            if (hours != 0)
                minutes = minutes % 60;
            long K = fileSize / 1000;
            if (K == 0)
                K = 1;
            sb.append(percent);
            sb.append("% of ");
            sb.append(K);
            sb.append("K (at ");
            sb.append(rate);
            sb.append(" bytes/sec, ");
            if (hours != 0) {
                sb.append(hours);
                sb.append(':');
                if (minutes < 10)
                    sb.append('0');
            }
            sb.append(minutes);
            sb.append(':');
            if (seconds < 10)
                sb.append('0');
            sb.append(seconds);
            sb.append(" remaining)");
        } else {
            // We don't know the file size.
            final long rate = (totalBytes * 1000) / elapsed; // bytes per second
            sb.append(totalBytes);
            sb.append(" bytes (at ");
            sb.append(rate);
            sb.append(" bytes/sec)");
        }
        return sb.toString();
    }
}
