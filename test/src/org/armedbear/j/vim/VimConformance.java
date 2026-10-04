/*
 * VimConformance.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.armedbear.j.EditorHarness;

/**
 * The conformance corpus: cases, their file format, and how to run one.
 *
 * A case is a script -- set the document, put the caret somewhere, press some
 * keys, assert, press more keys, assert again -- because that is the shape the
 * upstream tests have. Running one is a matter of replaying it through
 * {@link EditorHarness}.
 *
 * Not named *Test so that the build's test-class glob skips it;
 * {@link VimConformanceTest} is the entry point.
 */
public final class VimConformance {
    /** Where the generated corpus lives, relative to the project root. */
    public static final String CORPUS_DIR = "test/conformance/vim";

    private VimConformance() {}

    /** One step of a case. */
    public static final class Step {
        public final String directive;
        public final String text; // for value/keys/expect-value
        public final int a; // for cursor/expect-cursor/expect-*
        public final int b;

        Step(String directive, String text, int a, int b) {
            this.directive = directive;
            this.text = text;
            this.a = a;
            this.b = b;
        }
    }

    public static final class Case {
        public final String name;
        public final List<Step> steps;
        /** Upstream assertions the generator could not express. */
        public final List<String> notes;

        Case(String name, List<Step> steps, List<String> notes) {
            this.name = name;
            this.steps = steps;
            this.notes = notes;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Raised when a case does not hold, naming the step that failed. */
    public static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------ parsing

    public static List<Case> load(Path file) throws IOException {
        final List<Case> cases = new ArrayList<>();
        String name = null;
        List<Step> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        final List<String> lines =
            Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String raw : lines) {
            final String line = raw.trim();
            if (line.isEmpty())
                continue;
            if (line.startsWith("#")) {
                if (name != null)
                    notes.add(line.substring(1).trim());
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                if (name != null)
                    cases.add(new Case(name, steps, notes));
                name = line.substring(1, line.length() - 1);
                steps = new ArrayList<>();
                notes = new ArrayList<>();
                continue;
            }
            if (name == null)
                throw new IOException("directive outside a case: " + line);
            steps.add(parseStep(line));
        }
        if (name != null)
            cases.add(new Case(name, steps, notes));
        return cases;
    }

    private static Step parseStep(String line) throws IOException {
        final int space = line.indexOf(' ');
        final String directive = space < 0 ? line : line.substring(0, space);
        final String rest = space < 0 ? "" : line.substring(space + 1).trim();

        if (
            directive.equals("value")
                || directive.equals("keys")
                || directive.equals("ex")
                || directive.equals("expect-value")
        )
            return new Step(directive, unquote(rest), 0, 0);

        if (directive.equals("cursor") || directive.equals("expect-cursor")) {
            final String[] parts = rest.split("\\s+");
            if (parts.length != 2)
                throw new IOException("expected two numbers: " + line);
            return new Step(
                directive,
                null,
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1])
            );
        }
        if (directive.equals("expect-line") || directive.equals("expect-offset"))
            return new Step(directive, null, Integer.parseInt(rest), 0);

        throw new IOException("unknown directive: " + line);
    }

    /** Decodes a quoted, JSON-style-escaped string. */
    static String unquote(String s) throws IOException {
        if (s.length() < 2 || s.charAt(0) != '"' || s.charAt(s.length() - 1) != '"')
            throw new IOException("expected a quoted string: " + s);
        final String body = s.substring(1, s.length() - 1);
        final StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            final char c = body.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            final char d = body.charAt(++i);
            switch (d) {
                case 'n':
                    sb.append('\n');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case '0':
                    sb.append('\0');
                    break;
                case 'u':
                    sb.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                    i += 4;
                    break;
                default:
                    sb.append(d);
                    break;
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ running

    /**
     * Replays one case.
     *
     * @throws Failure if an assertion does not hold
     */
    public static void run(Case c) {
        // In modal editing, obviously: the corpus is vim's expectations.
        final EditorHarness h = EditorHarness.create().vim();
        try {
            int stepNumber = 0;
            for (Step step : c.steps) {
                ++stepNumber;
                if (step.directive.equals("value")) {
                    h.value(step.text);
                } else if (step.directive.equals("cursor")) {
                    // CodeMirror's setCursor clamps to the document.
                    final String[] lines = h.value().split("\n", -1);
                    final int line = Math.min(step.a, lines.length - 1);
                    h.cursor(line, Math.min(step.b, lines[line].length()));
                } else if (step.directive.equals("ex")) {
                    // Upstream's doEx() hands the line to the ex handler
                    // rather than typing it, and so does this: an ex line
                    // can contain < and would not survive key tokenizing.
                    h.exCommand(step.text);
                } else if (step.directive.equals("keys")) {
                    h.keys(step.text);
                } else if (step.directive.equals("expect-value")) {
                    check(c, stepNumber, "document", quote(step.text), quote(h.value()));
                } else if (step.directive.equals("expect-cursor")) {
                    check(
                        c,
                        stepNumber,
                        "cursor",
                        step.a + "," + step.b,
                        h.lineNumber() + "," + h.offset()
                    );
                } else if (step.directive.equals("expect-line")) {
                    check(
                        c,
                        stepNumber,
                        "line",
                        String.valueOf(step.a),
                        String.valueOf(h.lineNumber())
                    );
                } else if (step.directive.equals("expect-offset")) {
                    check(
                        c,
                        stepNumber,
                        "offset",
                        String.valueOf(step.a),
                        String.valueOf(h.offset())
                    );
                }
            }
        }
        finally {
            h.close();
        }
    }

    private static void check(
        Case c,
        int step,
        String what,
        String expected,
        String actual
    ) {
        if (!expected.equals(actual))
            throw new Failure(
                c.name + " step " + step + ": " + what
                    + " expected " + expected + " but was " + actual
            );
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\"", "\\\"") + '"';
    }

    // ------------------------------------------------------------ ratchet

    /**
     * The case names expected to pass, one per line, '#' comments allowed.
     *
     * This is a ratchet, not a target: a name is added once a milestone makes
     * that case pass, and a name that stops passing fails the build. It starts
     * empty because the modal engine does not exist yet.
     */
    public static Set<String> loadExpectedPassing(Path file) throws IOException {
        final Set<String> names = new LinkedHashSet<>();
        if (!Files.exists(file))
            return names;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            final String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#"))
                names.add(line);
        }
        return names;
    }

    /** Locates the corpus whether tests run from the project root or not. */
    public static Path corpusDir() {
        Path dir = Paths.get(CORPUS_DIR);
        if (Files.isDirectory(dir))
            return dir;
        Path up = Paths.get("..").resolve(CORPUS_DIR).normalize();
        if (Files.isDirectory(up))
            return up;
        return dir;
    }
}
