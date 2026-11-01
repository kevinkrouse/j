/*
 * VcsRunner.java
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.armedbear.j.Log;
import org.armedbear.j.util.Background;
import org.armedbear.j.util.ProcessRunner;

/**
 * Runs every version control command, and records each in the
 * {@link CommandLog}. Standard output is kept as bytes, apart from standard
 * error. Writes to one directory run one at a time; reads run as they come.
 */
public final class VcsRunner {
    private static final Map<String, ReentrantLock> writeLocks = new ConcurrentHashMap<>();

    private VcsRunner() {}

    /** Runs the command on this thread and waits for it. */
    public static VcsResult run(VcsCommand command) {
        return run(command, null);
    }

    /**
     * The same, its output given to stdout as it comes, on another thread,
     * a chunk at a time, and in the result too.
     */
    public static VcsResult run(VcsCommand command, Consumer<byte[]> stdout) {
        if (!command.write())
            return exec(command, stdout);
        ReentrantLock lock = writeLocks.computeIfAbsent(command.directory().toString(), k -> new ReentrantLock());
        lock.lock();
        try {
            return exec(command, stdout);
        }
        finally {
            lock.unlock();
        }
    }

    private static VcsResult exec(VcsCommand command, Consumer<byte[]> stdout) {
        final long start = System.nanoTime();
        VcsResult result;
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command.argv());
            pb.directory(command.directory().toFile());
            pb.environment().putAll(command.environment());
            process = pb.start();
        }
        catch (IOException e) {
            result = new VcsResult(command, -1, new byte[0], e.getMessage(), millisSince(start));
            CommandLog.add(result);
            return result;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outReader = Background.start("vcs stdout", () -> copy(process.getInputStream(), out, stdout));
        Thread errReader = Background.start("vcs stderr", () -> copy(process.getErrorStream(), err, null));
        Background.start("vcs stdin", () -> {
            try (OutputStream in = process.getOutputStream()) {
                if (command.stdin() != null)
                    in.write(command.stdin());
            }
            catch (IOException e) {
                // The program exited without reading it all; its exit code says why.
                Log.debug(e);
            }
        });
        int exitCode = -1;
        String failure = null;
        try {
            if (process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue();
            } else {
                ProcessRunner.destroy(process);
                failure = "timed out after " + command.timeout().toSeconds() + "s";
            }
            outReader.join(1000);
            errReader.join(1000);
            // A stream still being read, as when a child holds the pipe:
            // what was told is not all of it.
            if (stdout != null && outReader.isAlive() && failure == null) {
                exitCode = -1;
                failure = "its output was not all read";
            }
        }
        catch (InterruptedException e) {
            ProcessRunner.destroy(process);
            failure = "interrupted";
            Thread.currentThread().interrupt();
        }
        String stderr;
        synchronized (err) {
            stderr = err.toString(StandardCharsets.UTF_8);
        }
        if (failure != null)
            stderr = stderr.isEmpty() ? failure : stderr + "\n" + failure;
        byte[] said;
        synchronized (out) {
            said = out.toByteArray();
        }
        result = new VcsResult(command, exitCode, said, stderr, millisSince(start));
        CommandLog.add(result);
        return result;
    }

    private static void copy(InputStream in, ByteArrayOutputStream to, Consumer<byte[]> chunks) {
        byte[] buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (to) {
                    to.write(buf, 0, n);
                }
                if (chunks != null)
                    chunks.accept(Arrays.copyOf(buf, n));
            }
        }
        catch (IOException e) {
            Log.debug(e);
        }
    }

    private static long millisSince(long nanos) {
        return (System.nanoTime() - nanos) / 1_000_000;
    }
}
