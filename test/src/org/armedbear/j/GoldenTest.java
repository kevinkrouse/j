/*
 * GoldenTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * What each mode makes of a sample file: highlighting, tags, indentation and
 * comment/uncomment, checked against test/golden/NAME.golden.
 *
 * Comment and uncomment act on a select-all.
 * Set GOLDEN_UPDATE=1 to rewrite the .golden files instead of checking them.
 */
public class GoldenTest {
    private static final Path DIR = Paths.get("test/golden");

    @TestFactory
    public Stream<DynamicTest> samples() throws IOException {
        return samples(DIR);
    }

    /** One test per sample in dir; an extension's tests pass their own. */
    public static Stream<DynamicTest> samples(Path dir) throws IOException {
        return Files.list(dir)
            .filter(Files::isRegularFile)
            .filter(p -> !p.getFileName().toString().startsWith("."))
            .filter(p -> !p.toString().endsWith(".golden"))
            .sorted()
            .map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> check(p)));
    }

    private static void check(Path sample) throws IOException {
        final String name = sample.getFileName().toString();
        final Mode mode = Editor.getModeList().getModeForFileName(name);
        assertNotNull(mode, "no mode for " + name);
        final String text = Files.readString(sample, StandardCharsets.UTF_8);
        final String actual = describe(mode, text);
        final Path golden = sample.resolveSibling(name + ".golden");
        if ("1".equals(System.getenv("GOLDEN_UPDATE")))
            Files.writeString(golden, actual, StandardCharsets.UTF_8);
        else if (!Files.exists(golden))
            fail(golden + " is missing; run with GOLDEN_UPDATE=1");
        else
            assertEquals(Files.readString(golden, StandardCharsets.UTF_8), actual, name);
    }

    private static String describe(Mode mode, String text) {
        final StringBuilder sb = new StringBuilder();
        sb.append("mode ").append(mode.getDisplayName()).append('\n');
        final EditorHarness h = EditorHarness.create(text).mode(mode);
        try {
            final Buffer buffer = h.buffer();
            final Formatter formatter = buffer.getFormatter();
            formatter.parseBuffer();
            sb.append("\n## format\n");
            int n = 1;
            for (Line line = buffer.getFirstLine(); line != null; line = line.next(), n++)
                sb.append(n).append(':').append(segments(formatter, line)).append('\n');

            sb.append("\n## indent\n");
            n = 1;
            for (Line line = buffer.getFirstLine(); line != null; line = line.next(), n++)
                sb.append(n).append(": ").append(indent(mode, line, buffer)).append('\n');

            sb.append("\n## tags\n");
            final Tagger tagger = mode.getTagger(buffer);
            if (tagger != null) {
                tagger.run();
                final List<LocalTag> tags = buffer.getTags();
                if (tags != null) {
                    for (LocalTag tag : tags)
                        sb.append(tag.lineNumber() + 1)
                            .append(": ")
                            .append(tag.getName())
                            .append(" (")
                            .append(tag.getType())
                            .append(")\n");
                }
            }

            sb.append("\n## comment\n");
            MotionCommands.selectAll(h.editor());
            IndentCommands.commentRegion(h.editor());
            final String commented = h.text();
            sb.append(commented.equals(text) ? "(unchanged)\n" : commented);
            MotionCommands.selectAll(h.editor());
            IndentCommands.uncommentRegion(h.editor());
            final String uncommented = h.text();
            sb.append("\n## uncomment\n").append(uncommented.equals(text) ? "(restored)\n" : uncommented);
        }
        finally {
            h.close();
        }
        return sb.toString();
    }

    // Each segment as name"text".
    private static String segments(Formatter formatter, Line line) {
        final StringBuilder sb = new StringBuilder();
        final LineSegmentList list = formatter.formatLine(line);
        final FormatTable table = formatter.getFormatTable();
        for (int i = 0; i < list.size(); i++) {
            final LineSegment segment = list.getSegment(i);
            final int format = segment.getFormat();
            // High bits carry another formatter's format, as Markdown's fenced code does.
            final FormatTableEntry entry = (format & ~0xfff) == 0 ? table.lookup(format) : null;
            sb.append(' ')
                .append(entry != null ? entry.getName() : "#" + Integer.toHexString(format))
                .append('"')
                .append(segment.getText())
                .append('"');
        }
        return sb.toString();
    }

    private static String indent(Mode mode, Line line, Buffer buffer) {
        try {
            return String.valueOf(mode.getCorrectIndentation(line, buffer));
        }
        catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }
}
