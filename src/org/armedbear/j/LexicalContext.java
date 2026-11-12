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
 * What the text at a position is, for pairing purposes: ordinary
 * code, inside a string, or inside a comment. The three states the
 * electric-pairs decision needs; the formatters' richer parser
 * states collapse into these.
 */
public enum LexicalContext {
    NEUTRAL, STRING, COMMENT
}
