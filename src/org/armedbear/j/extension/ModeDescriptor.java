/*
 * ModeDescriptor.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.extension;

import java.util.List;
import java.util.function.IntFunction;
import org.armedbear.j.Mode;

/**
 * One mode for the mode list.
 *
 * @param id a Constants mode id, or 0 for one assigned at registration
 * @param name the name users see and type
 * @param modeClass the mode's class, whose simple name keys its preferences: "JavaMode.files"
 * @param factory makes the mode on first use, given its id
 * @param selectable whether users can choose it for a file
 * @param files the file names it edits by default (a regex), or null
 * @param aliases other names users may type for it
 * @param fenceNames Markdown fence languages it colors
 */
public record ModeDescriptor(
    int id,
    String name,
    Class<? extends Mode> modeClass,
    IntFunction<Mode> factory,
    boolean selectable,
    String files,
    List<String> aliases,
    List<String> fenceNames
) {
    public ModeDescriptor {
        aliases = List.copyOf(aliases);
        fenceNames = List.copyOf(fenceNames);
    }
}
