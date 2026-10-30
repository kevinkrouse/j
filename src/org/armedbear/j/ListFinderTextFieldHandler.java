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
import java.util.function.Function;
import java.util.function.Supplier;

/** A finder over a list made when it starts; each item knows what to do when picked. */
public final class ListFinderTextFieldHandler extends FinderTextFieldHandler {
    private final String command;
    private final Supplier<List<FinderItem>> supplier;
    private final Function<String, FinderItem> typed;
    private List<FinderItem> items; // Made on start, or on the first Enter before it.

    public ListFinderTextFieldHandler(
            Editor editor,
            HistoryTextField textField,
            String command,
            Supplier<List<FinderItem>> supplier) {
        this(editor, textField, command, supplier, null);
    }

    /** With typed, the item for text matching nothing listed; null for none. */
    public ListFinderTextFieldHandler(
            Editor editor,
            HistoryTextField textField,
            String command,
            Supplier<List<FinderItem>> supplier,
            Function<String, FinderItem> typed) {
        super(editor, textField);
        this.command = command;
        this.supplier = supplier;
        this.typed = typed;
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
    protected List<FinderItem> fallbackCandidates() {
        final String text = textField.getText().strip();
        return typed == null || text.isEmpty() ? null : List.of(typed.apply(text));
    }

    @Override
    protected List<FinderItem> candidates() {
        if (items == null)
            items = supplier.get();
        return items;
    }
}
