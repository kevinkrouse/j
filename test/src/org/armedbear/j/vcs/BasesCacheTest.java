/*
 * BasesCacheTest.java
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
package org.armedbear.j.vcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class BasesCacheTest {
    @Test
    public void twoAskingAtOnceReadOnce() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Bases bases = new Bases(new byte[0], new byte[0]);
        final VcsBackend backend = new VcsBackend() {
            @Override
            public int id() {
                return -1;
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public Bases readBases(VcsRepository repository, String path) {
                reads.incrementAndGet();
                reading.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return bases;
            }
        };
        final BasesCache cache = new BasesCache(new VcsRepository(Path.of("/nowhere"), backend), "a.txt", null);
        final CompletableFuture<Bases> first = CompletableFuture.supplyAsync(cache::get);
        reading.await(5, TimeUnit.SECONDS);
        final CompletableFuture<Bases> second = CompletableFuture.supplyAsync(cache::get);
        // The second waiting on the first's read.
        Thread.sleep(100);
        release.countDown();
        assertSame(bases, first.get(5, TimeUnit.SECONDS));
        assertSame(bases, second.get(5, TimeUnit.SECONDS));
        assertEquals(1, reads.get());
    }
}
