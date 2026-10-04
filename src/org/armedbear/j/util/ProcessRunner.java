/*
 * ProcessRunner.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.armedbear.j.Editor;
import org.armedbear.j.File;
import org.armedbear.j.Log;
import org.armedbear.j.Platform;
import org.armedbear.j.Preferences;
import org.armedbear.j.Property;

/**
 * Runs a program from an argument list, never a shell string unless asked
 * for with {@link #shell}. Standard error is merged into the output, and
 * both are read while the program runs, so neither pipe can fill and stall it.
 */
public final class ProcessRunner {
    /** What a run produced. exitValue is -1 if the program didn't start, timed out or was interrupted. */
    public record Result(int exitValue, String output) {
        public boolean succeeded() {
            return exitValue == 0;
        }
    }

    private final List<String> command;
    private File directory;
    private String input;
    private Duration timeout;
    private Charset charset;
    private boolean discardErrors;

    private ProcessRunner(List<String> command) {
        this.command = List.copyOf(command);
    }

    public static ProcessRunner of(String... command) {
        return new ProcessRunner(List.of(command));
    }

    public static ProcessRunner of(List<String> command) {
        return new ProcessRunner(command);
    }

    /** A command line for the shell: /bin/sh -c, or cmd.exe /c on Windows. */
    public static ProcessRunner shell(String commandLine) {
        if (Platform.isPlatformWindows()) {
            List<String> list = new ArrayList<>(List.of("cmd.exe", "/c"));
            list.addAll(Utilities.tokenize(commandLine));
            return new ProcessRunner(list);
        }
        return of("/bin/sh", "-c", commandLine);
    }

    public ProcessRunner directory(File directory) {
        this.directory = directory;
        return this;
    }

    /** Written to the program's standard input, which is then closed. */
    public ProcessRunner input(String input) {
        this.input = input;
        return this;
    }

    /** How output is decoded; by default, the defaultEncoding preference. */
    public ProcessRunner charset(Charset charset) {
        this.charset = charset;
        return this;
    }

    /** Drops standard error, for a probe whose output is parsed. */
    public ProcessRunner discardErrors() {
        discardErrors = true;
        return this;
    }

    /** How long to wait before killing the program and its children. */
    public ProcessRunner timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public List<String> command() {
        return command;
    }

    /** Runs the program and waits for it. */
    public Result run() {
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (discardErrors)
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            else
                pb.redirectErrorStream(true);
            if (directory != null)
                pb.directory(new java.io.File(directory.canonicalPath()));
            process = pb.start();
        }
        catch (IOException e) {
            // A missing program or working directory, reported as output.
            Log.debug(e);
            return new Result(-1, e.getMessage() + "\n");
        }
        final Charset cs = charset != null ? charset : defaultCharset();
        StringBuilder output = new StringBuilder();
        Thread reader = Background.start("process output", () -> {
            try (InputStream in = process.getInputStream()) {
                String s = new String(in.readAllBytes(), cs);
                synchronized (output) {
                    output.append(s);
                }
            }
            catch (IOException e) {
                Log.error(e);
            }
        });
        try (OutputStream out = process.getOutputStream()) {
            if (input != null)
                out.write(input.getBytes(cs));
        }
        catch (IOException e) {
            Log.error(e);
        }
        int exitValue = -1;
        try {
            if (timeout == null) {
                exitValue = process.waitFor();
            } else if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                exitValue = process.exitValue();
            } else {
                Log.error("timed out: " + command);
                destroy(process);
            }
            // A child that outlives the program can hold the pipe open.
            if (timeout != null)
                reader.join(timeout.toMillis());
            else
                reader.join();
        }
        catch (InterruptedException e) {
            destroy(process);
            Thread.currentThread().interrupt();
        }
        synchronized (output) {
            return new Result(exitValue, output.toString());
        }
    }

    /** Runs the program on a background thread and gives its result to onDone on the event thread. */
    public void runAsync(String threadName, Consumer<Result> onDone) {
        Background.start(threadName, () -> {
            Result result = run();
            SwingUtilities.invokeLater(() -> onDone.accept(result));
        });
    }

    // As ReaderThread decodes a shell's output.
    private static Charset defaultCharset() {
        Preferences preferences = Editor.preferences();
        String encoding = preferences != null ? preferences.getStringProperty(Property.DEFAULT_ENCODING) : null;
        try {
            if (encoding != null)
                return Charset.forName(encoding);
        }
        catch (IllegalArgumentException e) {
            Log.debug(e);
        }
        return Charset.defaultCharset();
    }

    /** Kills a process and everything it started. */
    public static void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** Whether program is on the PATH or in j's own bin directory. */
    public static boolean exists(String program) {
        return which(program) != null;
    }

    /** The program's path, from the PATH or j's own bin directory, or null. */
    public static String which(String program) {
        List<Path> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                dir = dir.strip();
                if (dir.length() > 1 && dir.startsWith("\"") && dir.endsWith("\""))
                    dir = dir.substring(1, dir.length() - 1);
                try {
                    if (!dir.isEmpty())
                        dirs.add(Path.of(dir));
                }
                catch (InvalidPathException e) {
                    Log.debug(e);
                }
            }
        }
        for (File dir : Utilities.binDirectories())
            dirs.add(Path.of(dir.canonicalPath()));
        // On Windows, program.exe before an extensionless shim beside it.
        List<String> names = new ArrayList<>();
        if (Platform.isPlatformWindows()) {
            String ext = System.getenv("PATHEXT");
            for (String e : (ext != null ? ext : ".EXE;.BAT;.CMD").split(";"))
                names.add(program + e.toLowerCase(Locale.ROOT));
        }
        names.add(program);
        for (Path dir : dirs) {
            for (String name : names) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate))
                    return candidate.toString();
            }
        }
        return null;
    }
}
