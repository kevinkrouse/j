/*
 * SplitAttributes.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mode.xml;

import java.util.Locale;
import org.armedbear.j.Buffer;
import org.armedbear.j.Property;

/**
 * The splitAttributes preference: how a start tag's attributes are laid out
 * across lines, after vscode-xml's xml.format.splitAttributes.
 */
public enum SplitAttributes {
    /** Line breaks stay; a wrapped attribute is indented splitAttributesIndentSize levels. */
    PRESERVE,
    /** Line breaks stay; a wrapped attribute lines up with the first one. */
    PRESERVE_ALIGNED,
    /** One attribute per line, lined up with the first. */
    FORCE_ALIGNED;

    public boolean isAligned() {
        return this != PRESERVE;
    }

    /** The buffer's value; preserve if unset or unknown. Takes force-aligned or forceAligned. */
    public static SplitAttributes of(Buffer buffer) {
        return parse(buffer.getStringProperty(Property.SPLIT_ATTRIBUTES));
    }

    static SplitAttributes parse(String value) {
        if (value == null)
            return PRESERVE;
        switch (value.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT)) {
            case "preservealigned":
                return PRESERVE_ALIGNED;
            case "forcealigned":
                return FORCE_ALIGNED;
            default:
                return PRESERVE;
        }
    }
}
