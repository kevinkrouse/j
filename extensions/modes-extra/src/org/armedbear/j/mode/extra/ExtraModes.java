/*
 * ExtraModes.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.extra;

import java.util.List;
import org.armedbear.j.extension.ModeDescriptor;
import org.armedbear.j.extension.ModeProvider;
import org.armedbear.j.mode.objc.ObjCMode;
import org.armedbear.j.mode.tcl.TclMode;

/** Objective-C and Tcl. */
public final class ExtraModes implements ModeProvider {
    public List<ModeDescriptor> modes() {
        return List.of(
            new ModeDescriptor(
                0,
                ObjCMode.NAME,
                ObjCMode.class,
                ObjCMode::create,
                true,
                ".+\\.m",
                List.of("objc"),
                List.of("objc", "objective-c", "m")
            ),
            new ModeDescriptor(
                0,
                TclMode.NAME,
                TclMode.class,
                TclMode::create,
                true,
                ".+\\.tcl",
                List.of(),
                List.of("tcl")
            )
        );
    }
}
