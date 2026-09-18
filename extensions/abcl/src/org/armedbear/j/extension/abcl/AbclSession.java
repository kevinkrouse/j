/*
 * AbclSession.java
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

import java.util.Locale;

import org.armedbear.j.File;
import org.armedbear.j.Log;
import org.armedbear.j.extension.EvalException;
import org.armedbear.j.extension.EvalHandler;
import org.armedbear.j.extension.EvalRequest;
import org.armedbear.j.extension.EvalResult;
import org.armedbear.j.extension.LanguageClient;
import org.armedbear.j.extension.Session;
import org.armedbear.lisp.Condition;
import org.armedbear.lisp.ControlTransfer;
import org.armedbear.lisp.Interpreter;
import org.armedbear.lisp.Lisp;
import org.armedbear.lisp.LispObject;
import org.armedbear.lisp.Load;
import org.armedbear.lisp.Packages;

/**
 * The embedded ABCL, as a session.
 *
 * <p>There is only ever one: an ABCL interpreter is a process-wide singleton,
 * so the boot state lives in statics here rather than on the instance. That
 * also lets {@link org.armedbear.j.mode.lisp.JLispBuffer} -- which brings the
 * interpreter up its own way, over a socket -- say so.
 *
 * <p>The interpreter is <em>not</em> started when the extension loads. It
 * starts the first time something evaluates, which is why {@link #isReady} can
 * be false long after j is up.
 */
public final class AbclSession implements Session
{
    private static boolean initialized;

    private final String key;

    AbclSession(String key)
    {
        this.key = key != null ? key : LanguageClient.DEFAULT_SESSION;
    }

    public String getKey()
    {
        return key;
    }

    public boolean isReady()
    {
        return isInitialized();
    }

    public static synchronized boolean isInitialized()
    {
        return initialized;
    }

    /**
     * Called by JLispBuffer once its socket REPL has an interpreter up: that
     * counts as the runtime being started, and nothing should boot a second.
     */
    public static synchronized void markInitialized()
    {
        initialized = true;
    }

    /**
     * Boot the interpreter, once.
     *
     * <p>{@code Interpreter.initializeJLisp()} loads j.lisp, which needs the
     * primitives in {@link org.armedbear.j.LispAPI}. It finds them with a
     * one-argument {@code Class.forName}, so it looks in its own loader -- this
     * extension's, where LispAPI and abcl.jar sit side by side. If that ever
     * stops being true the {@code ClassNotFoundException} is swallowed
     * silently and j.lisp fails later on undefined {@code j::} functions, so
     * check for the package and repair it rather than trust the arrangement.
     */
    public static synchronized void ensureInitialized() throws EvalException
    {
        if (initialized)
            return;
        Interpreter.initializeJLisp();
        if (Packages.findPackage("J") == null) {
            Log.warn("abcl: ABCL did not find LispAPI; loading it directly");
            try {
                Class.forName("org.armedbear.j.LispAPI", true,
                              AbclSession.class.getClassLoader());
                Load.loadSystemFile("j.lisp", false);
            }
            catch (Throwable t) {
                throw new EvalException("abcl: failed to install the J package", t);
            }
        }
        if (Packages.findPackage("J") == null)
            throw new EvalException("abcl: failed to install the J package");
        initialized = true;
    }

    private static LispObject safeCaller;

    /**
     * A funcaller that traps errors instead of entering the debugger.
     *
     * <p>{@code LispThread.execute} on a function that signals drops ABCL into
     * its interactive debugger, which then blocks reading standard input --
     * inside a GUI editor that is an editor that has silently stopped
     * responding. handler-case unwinds before the debugger is reached.
     *
     * <p>Returns NIL when the function ran, or the condition itself when it
     * did not -- the condition rather than a printed report, because printing
     * one goes through print-object, which is autoloaded and can signal in
     * turn. Java formats it instead, with {@link #report}. Only the embedded
     * REPL should ever see the debugger, so this is deliberately not a global
     * *debugger-hook*.
     */
    static synchronized LispObject safeCaller()
    {
        if (safeCaller == null) {
            safeCaller = Interpreter.evaluate(
                "(lambda (f) (handler-case (progn (funcall f) nil) (error (e) e)))");
        }
        return safeCaller;
    }

    /** A condition object, as a line fit to log. Never throws. */
    static String report(LispObject condition)
    {
        if (condition instanceof Condition) {
            try {
                String message = ((Condition)condition).getConditionReport();
                if (message != null && message.length() > 0)
                    return message;
            }
            catch (Throwable ignored) {
                // At least we tried.
            }
        }
        return "error";
    }

    public EvalResult evalSync(EvalRequest request) throws EvalException
    {
        ensureInitialized();
        try {
            LispObject result = Interpreter.evaluate(form(request));
            String printed = result.printObject();
            // With captureOutput the form's value *is* the captured output.
            return request.isCaptureOutput()
                ? EvalResult.of(printed, result.getStringValue())
                : EvalResult.of(printed);
        }
        catch (Throwable t) {
            return EvalResult.error(report(t));
        }
    }

    public void eval(final EvalRequest request, final EvalHandler handler)
    {
        Runnable r = () -> {
            EvalResult result;
            try {
                result = evalSync(request);
            }
            catch (EvalException e) {
                result = EvalResult.error(e.getMessage());
            }
            if (handler != null)
                handler.onResult(result);
        };
        // Honestly async: booting the interpreter can take a second, and this
        // is called from the event dispatch thread.
        new Thread(r, "abcl eval").start();
    }

    public void loadFile(File file) throws EvalException
    {
        ensureInitialized();
        try {
            Interpreter.evaluate("(load \"".concat(file.shellEscaped()).concat("\")"));
        }
        catch (Throwable t) {
            throw new EvalException(report(t), t);
        }
    }

    public boolean hasFeature(String name)
    {
        // Deliberately does not boot the interpreter: "is slime loaded" is
        // asked while building a menu, and the answer before startup is no.
        if (!isInitialized() || name == null)
            return false;
        try {
            LispObject result = Interpreter.evaluate(
                "(ext:featurep :".concat(name.toLowerCase(Locale.ROOT)).concat(")"));
            return result != Lisp.NIL;
        }
        catch (Throwable t) {
            Log.debug(t);
            return false;
        }
    }

    public void interrupt()
    {
        if (isInitialized())
            Interpreter.getInstance().kill(0);
    }

    // The form actually handed to the reader.

    private static String form(EvalRequest request)
    {
        String code = request.getCode();
        if (request.isCaptureOutput()) {
            // What CompilationBuffer used to wrap around the form itself.
            code = "(with-output-to-string (s) (let ((*standard-output* s)) "
                   .concat(code).concat(" ))");
        }
        String context = request.getContext();
        if (context == null)
            return code;
        // *package* has to be bound before the form is read, not before it is
        // evaluated, so the form goes through read-from-string.
        return "(let ((*package* (or (find-package \"".concat(
                   context.toUpperCase(Locale.ROOT)).concat(
               "\") *package*))) (eval (read-from-string ").concat(
                   quote(code)).concat(")))");
    }

    private static String quote(String s)
    {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\')
                sb.append('\\');
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * A condition, as a line fit to show the user. This unwrapping used to sit
     * in Editor.executeCommand; it belongs here, where the types are known.
     */
    static String report(Throwable t)
    {
        String message = null;
        if (t instanceof ControlTransfer) {
            try {
                LispObject condition = ((ControlTransfer)t).getCondition();
                if (condition instanceof Condition) {
                    try {
                        message = ((Condition)condition).getConditionReport();
                    }
                    catch (Throwable ignored) {
                        // At least we tried.
                    }
                }
            }
            catch (Throwable ignored) {}
        }
        if (message == null || message.length() == 0)
            message = t.getMessage();
        if (message == null || message.length() == 0)
            message = String.valueOf(t);
        return message;
    }
}
