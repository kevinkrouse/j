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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * The embedded ABCL, driven through the SPI.
 *
 * <p>An ABCL interpreter is a process-wide singleton and cannot be torn down
 * and restarted, so the order is fixed: the first test is about the
 * interpreter <em>not</em> being up, and every one after it boots it.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AbclSessionTest
{
    private static final LanguageClient client = new AbclClient();

    private static Session session()
    {
        return client.getDefaultSession();
    }

    @Test
    public void t1_nothingStartsUntilSomethingEvaluates()
    {
        assertFalse("registering an extension must not boot an interpreter",
                    session().isReady());
        // Asked while building the Lisp menu, long before anything evaluates:
        // it has to answer without starting the runtime.
        assertFalse(session().hasFeature("slime"));
        assertFalse(session().isReady());
    }

    @Test
    public void t2_evaluatesAFormAndStartsTheRuntime() throws EvalException
    {
        EvalResult result = session().evalSync(EvalRequest.of("(+ 1 2)"));
        assertFalse(result.getError(), result.isError());
        assertEquals("3", result.getValue());
        assertTrue(session().isReady());
    }

    @Test
    public void t3_theJPackageIsInstalled()
    {
        // j.lisp is loaded from this extension's own jar, and defines itself in
        // terms of the primitives in LispAPI next door. If the two ever landed
        // in different loaders ABCL would swallow the failure and j.lisp would
        // break later on undefined j:: functions.
        assertNotNull("the J package is missing", Packages.findPackage("J"));
    }

    @Test
    public void t4_capturesStandardOutput() throws EvalException
    {
        // What CompilationBuffer needs: the with-output-to-string wrapper it
        // used to build itself now lives behind captureOutput.
        EvalResult result = session().evalSync(
            EvalRequest.of("(princ \"hello\")").captureOutput(true));
        assertFalse(result.getError(), result.isError());
        assertEquals("hello", result.getOutput());
        assertEquals("hello", result.display());
    }

    @Test
    public void t5_anErrorComesBackAsAReadableMessage() throws EvalException
    {
        EvalResult result = session().evalSync(EvalRequest.of("(error \"boom\")"));
        assertTrue("an error form must report an error", result.isError());
        assertTrue("unreadable report: " + result.getError(),
                   result.getError().contains("boom"));
        // display() is what Editor shows; it must not be empty or a class name.
        assertEquals(result.getError(), result.display());
    }

    @Test
    public void t6_aContextChoosesThePackageTheFormIsReadIn() throws EvalException
    {
        // A context has to be applied before the form is *read*, not before it
        // is evaluated: which package a symbol lands in is the reader's call.
        session().evalSync(EvalRequest.of("(defpackage :j-context-test (:use :cl))"));
        EvalResult result = session().evalSync(
            EvalRequest.of("(package-name *package*)").context("j-context-test"));
        assertFalse(result.getError(), result.isError());
        assertTrue("wrong package: " + result.getValue(),
                   result.getValue().contains("J-CONTEXT-TEST"));
    }

    @Test
    public void t6a_anUnknownContextFallsBackRatherThanFailing() throws EvalException
    {
        EvalResult result = session().evalSync(
            EvalRequest.of("(+ 1 2)").context("no-such-package"));
        assertFalse(result.getError(), result.isError());
        assertEquals("3", result.getValue());
    }

    @Test
    public void t7_loadsAFile() throws EvalException, IOException
    {
        Path file = Files.createTempFile("abcl-session-test", ".lisp");
        try {
            Files.write(file, "(defparameter cl-user::*loaded-by-j* 42)"
                              .getBytes(StandardCharsets.UTF_8));
            session().loadFile(org.armedbear.j.File.getInstance(file.toString()));
            EvalResult result =
                session().evalSync(EvalRequest.of("cl-user::*loaded-by-j*"));
            assertFalse(result.getError(), result.isError());
            assertEquals("42", result.getValue());
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void t8_theClientKnowsWhereItsRuntimeIs()
    {
        // "M-x abcl" builds a java command line out of this; j.jar no longer
        // names abcl.jar on its manifest, so a null here breaks that command.
        String classPath = client.getRuntimeClassPath();
        assertNotNull("the client cannot locate abcl.jar", classPath);
        assertTrue(classPath, classPath.endsWith(".jar"));
    }

    @Test
    public void t9_everySessionKeyIsTheSameSession()
    {
        // One JVM holds one ABCL, so a key is accepted and ignored.
        assertEquals(client.getDefaultSession(), client.getSession("somewhere"));
        assertEquals("abcl", client.getName());
        assertTrue(client.isAvailable());
    }
}
