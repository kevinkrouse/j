/*
 * SearchMatcher.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.armedbear.j;

import java.util.function.Function;

/**
 * A window's search matches for every line of its buffer, as
 * Editor.getSearchMatches gives them a line at a time, but with what they
 * depend on read once: for the scroll bar's marks of all of them.
 *
 * @param key what the matches depend on besides the line: equal keys,
 *     equal matches
 * @param matches a line's matches as offset pairs, or null for none
 */
public record SearchMatcher(Object key, Function<Line, int[]> matches) {}
