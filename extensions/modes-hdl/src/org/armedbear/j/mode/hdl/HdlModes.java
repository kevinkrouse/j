/*
 * HdlModes.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.hdl;

import java.util.List;
import org.armedbear.j.extension.ModeDescriptor;
import org.armedbear.j.extension.ModeProvider;
import org.armedbear.j.mode.asm.AsmMode;
import org.armedbear.j.mode.autoconf.AutoconfMode;
import org.armedbear.j.mode.verilog.VerilogMode;
import org.armedbear.j.mode.vhdl.VHDLMode;

/** Hardware description languages, assembly and autoconf. */
public final class HdlModes implements ModeProvider {
    @Override
    public List<ModeDescriptor> modes() {
        return List.of(
            new ModeDescriptor(
                0,
                AsmMode.NAME,
                AsmMode.class,
                AsmMode::create,
                true,
                ".+\\.asm|.+\\.inc",
                List.of("asm"),
                List.of("asm", "assembly", "nasm")
            ),
            new ModeDescriptor(
                0,
                AutoconfMode.NAME,
                AutoconfMode.class,
                AutoconfMode::create,
                true,
                "configure.ac|configure.in|aclocal.m4",
                List.of(),
                List.of("autoconf", "m4")
            ),
            new ModeDescriptor(
                0,
                VerilogMode.NAME,
                VerilogMode.class,
                VerilogMode::create,
                true,
                ".+\\.v",
                List.of(),
                List.of("verilog", "v")
            ),
            new ModeDescriptor(
                0,
                VHDLMode.NAME,
                VHDLMode.class,
                VHDLMode::create,
                true,
                ".+\\.vhdl?",
                List.of(),
                List.of("vhdl")
            )
        );
    }
}
