/*
 * MailModes.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.mail;

import java.util.List;
import org.armedbear.j.extension.ModeDescriptor;
import org.armedbear.j.extension.ModeProvider;

/** The mailbox, message, news and compose modes. */
public final class MailModes implements ModeProvider {
    public List<ModeDescriptor> modes() {
        return List.of(
            new ModeDescriptor(
                0,
                MailboxMode.NAME,
                MailboxMode.class,
                MailboxMode::create,
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                0,
                MessageMode.NAME,
                MessageMode.class,
                MessageMode::create,
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                0,
                NewsGroupsMode.NAME,
                NewsGroupsMode.class,
                NewsGroupsMode::create,
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                0,
                NewsGroupSummaryMode.NAME,
                NewsGroupSummaryMode.class,
                NewsGroupSummaryMode::create,
                false,
                null,
                List.of(),
                List.of()
            ),
            new ModeDescriptor(
                0,
                SendMailMode.NAME,
                SendMailMode.class,
                SendMailMode::create,
                false,
                null,
                List.of(),
                List.of()
            )
        );
    }
}
