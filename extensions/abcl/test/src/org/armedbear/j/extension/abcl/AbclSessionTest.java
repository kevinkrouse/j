/*
 * AbclSessionTest.java
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.armedbear.j.extension.abcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.armedbear.j.extension.EvalException;
import org.armedbear.j.extension.EvalRequest;
import org.armedbear.j.extension.EvalResult;
import org.armedbear.j.extension.LanguageClient;
import org.armedbear.j.extension.Session;
import org.armedbear.lisp.Packages;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The embedded ABCL, driven through the SPI.
 *
 * <p>An ABCL interpreter is a process-wide singleton and cannot be torn down
 * and restarted, so the order is fixed: the first test is about the
 * interpreter <em>not</em> being up, and every one after it boots it.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
public class AbclSessionTest {
    private static final LanguageClient client = new AbclClient();

    private static Session session() {
        return client.getDefaultSession();
    }

    @Test
    public void t1_nothingStartsUntilSomethingEvaluates() {
        assertFalse(session().isReady(), "registering an extension must not boot an interpreter");
        // Asked while building the Lisp menu, long before anything evaluates:
        // it has to answer without starting the runtime.
        assertFalse(session().hasFeature("slime"));
        assertFalse(session().isReady());
    }

    @Test
    public void t2_evaluatesAFormAndStartsTheRuntime() throws EvalException {
        EvalResult result = session().evalSync(EvalRequest.of("(+ 1 2)"));
        assertFalse(result.isError(), result.getError());
        assertEquals("3", result.getValue());
        assertTrue(session().isReady());
    }

    @Test
    public void t3_theJPackageIsInstalled() {
        // j.lisp is loaded from this extension's own jar, and defines itself in
        // terms of the primitives in LispAPI next door. If the two ever landed
        // in different loaders ABCL would swallow the failure and j.lisp would
        // break later on undefined j:: functions.
        assertNotNull(Packages.findPackage("J"), "the J package is missing");
    }

    @Test
    public void t4_capturesStandardOutput() throws EvalException {
        // What CompilationBuffer needs: the with-output-to-string wrapper it
        // used to build itself now lives behind captureOutput.
        EvalResult result = session().evalSync(
            EvalRequest.of("(princ \"hello\")").captureOutput(true)
        );
        assertFalse(result.isError(), result.getError());
        assertEquals("hello", result.getOutput());
        assertEquals("hello", result.display());
    }

    @Test
    public void t5_anErrorComesBackAsAReadableMessage() throws EvalException {
        EvalResult result = session().evalSync(EvalRequest.of("(error \"boom\")"));
        assertTrue(result.isError(), "an error form must report an error");
        assertTrue(result.getError().contains("boom"), "unreadable report: " + result.getError());
        // display() is what Editor shows; it must not be empty or a class name.
        assertEquals(result.getError(), result.display());
    }

    @Test
    public void t6_aContextChoosesThePackageTheFormIsReadIn() throws EvalException {
        // A context has to be applied before the form is *read*, not before it
        // is evaluated: which package a symbol lands in is the reader's call.
        session().evalSync(EvalRequest.of("(defpackage :j-context-test (:use :cl))"));
        EvalResult result = session().evalSync(
            EvalRequest.of("(package-name *package*)").context("j-context-test")
        );
        assertFalse(result.isError(), result.getError());
        assertTrue(result.getValue().contains("J-CONTEXT-TEST"), "wrong package: " + result.getValue());
    }

    @Test
    public void t6a_anUnknownContextFallsBackRatherThanFailing() throws EvalException {
        EvalResult result = session().evalSync(
            EvalRequest.of("(+ 1 2)").context("no-such-package")
        );
        assertFalse(result.isError(), result.getError());
        assertEquals("3", result.getValue());
    }

    @Test
    public void t7_loadsAFile() throws EvalException, IOException {
        Path file = Files.createTempFile("abcl-session-test", ".lisp");
        try {
            Files.write(
                file,
                "(defparameter cl-user::*loaded-by-j* 42)"
                    .getBytes(StandardCharsets.UTF_8)
            );
            session().loadFile(org.armedbear.j.File.getInstance(file.toString()));
            EvalResult result =
                session().evalSync(EvalRequest.of("cl-user::*loaded-by-j*"));
            assertFalse(result.isError(), result.getError());
            assertEquals("42", result.getValue());
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void t8_theClientKnowsWhereItsRuntimeIs() {
        // "M-x abcl" builds a java command line out of this; j.jar no longer
        // names abcl.jar on its manifest, so a null here breaks that command.
        String classPath = client.getRuntimeClassPath();
        assertNotNull(classPath, "the client cannot locate abcl.jar");
        assertTrue(classPath.endsWith(".jar"), classPath);
    }

    @Test
    public void t9_everySessionKeyIsTheSameSession() {
        // One JVM holds one ABCL, so a key is accepted and ignored.
        assertEquals(client.getDefaultSession(), client.getSession("somewhere"));
        assertEquals("abcl", client.getName());
        assertTrue(client.isAvailable());
    }
}
