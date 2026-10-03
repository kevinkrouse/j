/*
 * ModeProvider.java
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

/**
 * Modes a jar provides. An extension declares one in
 * META-INF/services/org.armedbear.j.extension.ModeProvider.
 */
public interface ModeProvider {
    List<ModeDescriptor> modes();
}
