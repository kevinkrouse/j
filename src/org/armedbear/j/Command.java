/*
 * Command.java
 *
 * Copyright (C) 1998-2002 Peter Graves
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

package org.armedbear.j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** A named command: what it does with no argument, with a String one, or either. */
public final class Command {
    private final String name;
    private final Consumer<Editor> run;
    private final BiConsumer<Editor, String> runWithArgument;

    // An extension's commands only.
    private final Class<?> declaringClass;

    /** run or runWithArgument may be null, for a command that takes only the other. */
    public Command(String name, Consumer<Editor> run, BiConsumer<Editor, String> runWithArgument) {
        this.name = name;
        this.run = run;
        this.runWithArgument = runWithArgument;
        this.declaringClass = null;
    }

    public Command(String name, Consumer<Editor> run) {
        this(name, run, null);
    }

    /** An extension's command: owner's public static methodName, taking no argument, one String, or either. */
    public Command(String name, Class<?> owner, String methodName) {
        Method noArgument = method(owner, methodName);
        Method withArgument = method(owner, methodName, String.class);
        this.name = name;
        this.run = noArgument == null ? null : e -> invoke(noArgument);
        this.runWithArgument = withArgument == null ? null : (e, s) -> invoke(withArgument, s);
        this.declaringClass = owner;
    }

    public String getName() {
        return name;
    }

    /** The class an extension's command came from; null for core's. */
    public Class<?> getDeclaringClass() {
        return declaringClass;
    }

    /** Whether it can run at all, with an argument or without. */
    boolean isRunnable() {
        return run != null || runWithArgument != null;
    }

    /** Runs the command, with argument if it isn't null. False if it takes no such argument. */
    public boolean run(Editor editor, String argument) {
        if (argument == null) {
            if (run == null)
                return false;
            run.accept(editor);
        } else {
            if (runWithArgument == null)
                return false;
            runWithArgument.accept(editor, argument);
        }
        return true;
    }

    private static Method method(Class<?> owner, String methodName, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(methodName, parameterTypes);
        }
        catch (NoSuchMethodException e) {
            return null;
        }
    }

    // Reports the method's own exception, not the reflection that wrapped it.
    private static void invoke(Method method, Object... arguments) {
        try {
            method.invoke(null, arguments);
        }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r)
                throw r;
            if (cause instanceof Error err)
                throw err;
            throw new IllegalStateException(cause);
        }
        catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
