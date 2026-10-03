/*
 * BufferListIsolation.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Takes the buffers a test leaves in j's global buffer list out of it, so they
 * cannot change where a later test's buffer commands go. It only unlists them.
 * Autodetected from META-INF/services; junit-platform.properties turns
 * autodetection on.
 */
public final class BufferListIsolation implements BeforeEachCallback, AfterEachCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(BufferListIsolation.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getStore(NAMESPACE).put("buffers", buffers());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        @SuppressWarnings("unchecked")
        final List<Buffer> before = context.getStore(NAMESPACE).get("buffers", List.class);
        if (before == null)
            return;
        for (Buffer buffer : buffers()) {
            if (!before.contains(buffer))
                Editor.getBufferList().remove(buffer);
        }
    }

    private static List<Buffer> buffers() {
        final List<Buffer> list = new ArrayList<>();
        Editor.getBufferList().forEach(list::add);
        return list;
    }
}
