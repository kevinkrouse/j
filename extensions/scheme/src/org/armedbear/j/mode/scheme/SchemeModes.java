/*
 * SchemeModes.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.scheme;

import java.util.List;
import org.armedbear.j.extension.ModeDescriptor;
import org.armedbear.j.extension.ModeProvider;

public final class SchemeModes implements ModeProvider {
    public List<ModeDescriptor> modes() {
        return List.of(
            new ModeDescriptor(
                0,
                SchemeMode.NAME,
                SchemeMode.class,
                SchemeMode::create,
                true,
                ".+\\.sc[ehm]?|.+\\.ss",
                List.of(),
                List.of("scheme", "scm", "racket")
            )
        );
    }
}
