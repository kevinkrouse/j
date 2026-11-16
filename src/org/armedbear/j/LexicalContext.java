/*
 * LexicalContext.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

/**
 * What the text at a position is, for pairing purposes: ordinary code,
 * a string, a comment, or a region that is neither prose nor comment
 * but still not a place to pair (a Markdown code span or fence, front
 * matter, inactive C code). Pairing requires NEUTRAL, so STRING,
 * COMMENT and OPAQUE all inhibit.
 */
public enum LexicalContext {
    NEUTRAL, STRING, COMMENT, OPAQUE
}
