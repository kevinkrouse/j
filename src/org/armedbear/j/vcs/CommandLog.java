/*
 * CommandLog.java
 *
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

package org.armedbear.j.vcs;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.armedbear.j.Editor;
import org.armedbear.j.MotionCommands;
import org.armedbear.j.OutputBuffer;

/** The last version control commands run, newest last: what `$` shows. */
public final class CommandLog {
    public static final int CAPACITY = 500;
    /** How much of a command's output an entry keeps. */
    static final int OUTPUT_LIMIT = 16 * 1024;

    /**
     * One command: when it finished, what ran where, without the options
     * its program always runs with, what it read (a patch, say; null if
     * nothing) and what it said.
     */
    public record Entry(Instant time, String directory, String program, String commandLine, String options,
            int exitCode, long millis, String input, String output, String stderr) {
        public boolean failed() {
            return exitCode != 0;
        }
    }

    private static final ArrayDeque<Entry> entries = new ArrayDeque<>();

    private CommandLog() {}

    static void add(VcsResult result) {
        byte[] in = result.command().stdin();
        Entry entry = new Entry(Instant.now(),
                result.command().directory().toString(),
                result.command().program(),
                result.command().shortCommandLine(),
                VcsCommand.line(result.command().options()),
                result.exitCode(),
                result.millis(),
                in == null ? null : text(in),
                text(result.stdout()),
                result.stderr());
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > CAPACITY)
                entries.removeFirst();
        }
    }

    private static String text(byte[] bytes) {
        String s = new String(bytes, 0, Math.min(bytes.length, OUTPUT_LIMIT), StandardCharsets.UTF_8);
        return bytes.length > OUTPUT_LIMIT ? s + "\n[" + (bytes.length - OUTPUT_LIMIT) + " more bytes]" : s;
    }

    /** The entries, oldest first. */
    public static List<Entry> entries() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    static void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * The log as text: a line per command, newest last, with the error output
     * of each that failed under it, and what it read; above the first with
     * options its program always runs with, a line saying what they are.
     */
    public static String format(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        String lastDirectory = null;
        String lastOptions = "";
        for (Entry e : list) {
            if (!e.options().isEmpty() && !e.options().equals(lastOptions)) {
                sb.append("[").append(e.program()).append(" runs with ").append(e.options()).append("]\n");
                lastOptions = e.options();
            }
            if (!e.directory().equals(lastDirectory)) {
                sb.append(e.directory()).append('\n');
                lastDirectory = e.directory();
            }
            sb.append(LocalTime.ofInstant(e.time(), ZoneId.systemDefault()).format(TIME));
            sb.append(e.failed() ? String.format(" %3d ", e.exitCode()) : "     ");
            sb.append(String.format("%5dms  ", e.millis()));
            sb.append(e.commandLine()).append('\n');
            if (e.failed()) {
                for (String line : e.stderr().strip().split("\n"))
                    sb.append("                     ").append(line).append('\n');
                if (e.input() != null) {
                    for (String line : e.input().split("\n"))
                        sb.append("                     | ").append(line).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** {@code vcsCommandLog}: shows the version control commands j has run. */
    public static void vcsCommandLog() {
        final Editor editor = Editor.currentEditor();
        List<Entry> list = entries();
        String text = list.isEmpty() ? "No version control commands have run yet.\n" : format(list);
        OutputBuffer buf = OutputBuffer.getOutputBuffer(text);
        buf.setTitle("vcsCommandLog");
        editor.makeNext(buf);
        Editor shown = editor.activateInOtherWindow(buf);
        if (shown != null)
            MotionCommands.eob(shown);
    }
}
