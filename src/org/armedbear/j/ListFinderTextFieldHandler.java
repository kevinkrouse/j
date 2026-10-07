/*
 * ListFinderTextFieldHandler.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.util.List;
import java.util.function.Supplier;

/** A finder over a list made when it starts; each item knows what to do when picked. */
public final class ListFinderTextFieldHandler extends FinderTextFieldHandler {
    private final String command;
    private final Supplier<List<FinderItem>> supplier;
    private List<FinderItem> items; // Made on start, or on the first Enter before it.

    public ListFinderTextFieldHandler(
        Editor editor,
        HistoryTextField textField,
        String command,
        Supplier<List<FinderItem>> supplier
    ) {
        super(editor, textField);
        this.command = command;
        this.supplier = supplier;
    }

    @Override
    protected String command() {
        return command;
    }

    @Override
    public void start() {
        if (!isActive())
            return;
        items = supplier.get();
        refilter();
    }

    @Override
    protected List<FinderItem> candidates() {
        if (items == null)
            items = supplier.get();
        return items;
    }
}
