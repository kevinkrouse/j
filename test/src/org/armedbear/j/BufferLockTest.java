/*
 * BufferLockTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
public class BufferLockTest {
    private EditorHarness h;
    private Buffer buffer;

    @BeforeEach
    public void setUp() {
        h = EditorHarness.create("text\n");
        buffer = h.buffer();
    }

    @AfterEach
    public void tearDown() {
        h.close();
    }

    @Test
    public void readInsideWrite() {
        boolean[] read = new boolean[1];
        assertTrue(buffer.withWriteLock(() -> buffer.withReadLock(() -> read[0] = true)));
        assertTrue(read[0]);
    }

    @Test
    public void writeLockedIsPerThread() throws Exception {
        buffer.withWriteLock(() -> {
            assertTrue(buffer.isWriteLocked());
            assertFalse(CompletableFuture.supplyAsync(buffer::isWriteLocked).join());
        });
        assertFalse(buffer.isWriteLocked());
    }

    @Test
    public void pendingInterruptStillLocksAnUnheldLock() {
        Thread.currentThread().interrupt();
        try {
            boolean[] ran = new boolean[1];
            assertTrue(buffer.withWriteLock(() -> ran[0] = true));
            assertTrue(ran[0]);
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    public void anotherThreadMayUnlock() throws Exception {
        assertTrue(buffer.lock());
        assertFalse(buffer.lock());
        CompletableFuture.runAsync(buffer::unlock).get(5, TimeUnit.SECONDS);
        assertFalse(buffer.isLocked());
        assertTrue(buffer.lock());
        buffer.unlock();
    }
}
