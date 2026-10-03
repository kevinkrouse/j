/*
 * Opener.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.extension;

import org.armedbear.j.Buffer;
import org.armedbear.j.Editor;
import org.armedbear.j.File;

/** Opens names that aren't files, such as mailbox URLs, and files an extension manages. */
public interface Opener {
    /** Whether name is one of this opener's. */
    boolean handles(String name);

    /** The buffer for a name this opener handles, or null; for opening several at once. */
    Buffer getBuffer(Editor editor, String name);

    /** Opens a name this opener handles, as the open-file prompt does. */
    void open(Editor editor, String name);

    /** A buffer for a file this opener manages, or null to open it as usual. */
    default Buffer createBuffer(File file) {
        return null;
    }
}
