/*
 * BackgroundTest.java
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

package org.armedbear.j.util;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class BackgroundTest {
    @Test
    public void thenRunsWithNullAfterAnErrorToo() throws Exception {
        final CountDownLatch ran = new CountDownLatch(1);
        final AtomicReference<String> got = new AtomicReference<>("unset");
        Background.<String>then("test error", () -> {
            throw new AssertionError("an error, not an exception");
        }, s -> {
            got.set(s);
            ran.countDown();
        });
        assertTrue(ran.await(5, TimeUnit.SECONDS), "then ran");
        assertNull(got.get());
    }
}
