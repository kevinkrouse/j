/*
 * SchemeGoldenTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.scheme;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.armedbear.j.Editor;
import org.armedbear.j.GoldenTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

public class SchemeGoldenTest {
    @BeforeAll
    public static void registerModes() {
        Editor.getModeList().register(new SchemeModes());
    }

    @TestFactory
    public Stream<DynamicTest> samples() throws IOException {
        return GoldenTest.samples(Paths.get("extensions/scheme/test/golden"));
    }
}
