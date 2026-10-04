/*
 * TaggerTest.java
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Taggers run as the tag file builder runs them: on a SystemBuffer, which has no mode. */
public class TaggerTest implements Constants {
    @TempDir
    Path dir;

    private List<String> tags(int modeId, String name, String text) throws Exception {
        Path p = Files.writeString(dir.resolve(name), text);
        SystemBuffer buffer = new SystemBuffer(File.getInstance(p.toString()));
        buffer.load();
        Editor.getModeList().getMode(modeId).getTagger(buffer).run();
        List<String> names = new ArrayList<String>();
        for (LocalTag tag : buffer.getTags())
            names.add(tag.getName());
        return names;
    }

    @Test
    public void python() throws Exception {
        assertEquals(List.of("class A", "f"), tags(PYTHON_MODE, "a.py", "class A:\n    def f(self):\n        pass\n"));
    }

    @Test
    public void ruby() throws Exception {
        assertEquals(List.of("class A", "f"), tags(RUBY_MODE, "a.rb", "class A\n  def f\n  end\nend\n"));
    }

    @Test
    public void perlTagsDefinitionsNotDeclarations() throws Exception {
        assertEquals(List.of("g", "P::h"), tags(PERL_MODE, "a.pl", "sub f;\nsub g {\n}\nsub P::h {\n}\n"));
    }
}
