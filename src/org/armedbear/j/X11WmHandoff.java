/*
 * X11WmHandoff.java
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

package org.armedbear.j;

import java.awt.Component;
import java.awt.Window;
import java.lang.reflect.Method;

/**
 * Hands a window move or resize to the window manager with the EWMH
 * {@code _NET_WM_MOVERESIZE} client message.
 *
 * <p>Under XWayland the compositor owns window placement: a client
 * {@code setBounds} that changes an already-mapped window's <em>position</em> is
 * dropped, so a client-drawn resize border grows the wrong edge and a title-bar
 * drag does nothing. Delegating the operation to the window manager is correct on
 * X11 and XWayland alike.
 *
 * <p>The JDK's X11 classes are not exported, so every call is reflective and needs
 * {@code --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED}. Without it, off X11,
 * or when the WM lacks the hint, {@link #moveOrResize} returns {@code false} and
 * the caller moves the window itself.
 *
 * <p>Ported from FlatLAF's {@code FlatNativeLinuxLibrary}.
 */
final class X11WmHandoff {
    /** {@code _NET_WM_MOVERESIZE} direction values, per EWMH 1.5. */
    static final int SIZE_TOPLEFT = 0;
    static final int SIZE_TOP = 1;
    static final int SIZE_TOPRIGHT = 2;
    static final int SIZE_RIGHT = 3;
    static final int SIZE_BOTTOMRIGHT = 4;
    static final int SIZE_BOTTOM = 5;
    static final int SIZE_BOTTOMLEFT = 6;
    static final int SIZE_LEFT = 7;
    static final int MOVE = 8;

    // X11 constants. ClientMessage is 33; the two masks are bits 18 and 20.
    private static final int CLIENT_MESSAGE = 33;
    private static final long SUBSTRUCTURE_NOTIFY = 1L << 18;
    private static final long SUBSTRUCTURE_REDIRECT = 1L << 20;
    private static final long CURRENT_TIME = 0L;
    private static final int BUTTON1 = 1;
    private static final int SOURCE_APPLICATION = 1;

    private X11WmHandoff() {}

    /**
     * Whether the reflective X11 bridge is usable. Resolved once: the
     * {@code --add-opens} flag cannot appear after startup.
     */
    static boolean available() {
        return REF.ok;
    }

    /**
     * Ask the window manager to move or resize {@code window} in the given
     * {@code direction}, starting at the root coordinates {@code xRoot yRoot}.
     *
     * @return {@code true} if the WM took over, so the client must not move the
     *         window itself; {@code false} to fall back to a client-side move.
     */
    static boolean moveOrResize(Window window, int xRoot, int yRoot, int direction) {
        final Ref r = REF;
        if (!r.ok || window == null)
            return false;
        try {
            r.awtLock.invoke(null);
            try {
                final Object accessor = r.getComponentAccessor.invoke(null);
                final Object peer = r.getPeer.invoke(accessor, window);
                if (peer == null)
                    return false;
                final long display = (Long) r.getDisplay.invoke(null);
                final long windowId = (Long) r.getWindow.invoke(peer);
                final int screen = ((Long) r.getScreenNumber.invoke(peer)).intValue();
                final long root = (Long) r.getRootWindow.invoke(null, screen);
                final Object atom = r.atomGet.invoke(null, "_NET_WM_MOVERESIZE");
                final long atomValue = (Long) r.atomGetAtom.invoke(atom);
                if (!wmSupports(r, root, atomValue))
                    return false;

                // Let the window manager grab the pointer and keyboard.
                r.ungrabPointer.invoke(null, display, CURRENT_TIME);
                r.ungrabKeyboard.invoke(null, display, CURRENT_TIME);

                final Object ev = r.cmeCtor.newInstance();
                try {
                    r.setType.invoke(ev, CLIENT_MESSAGE);
                    r.setWindow.invoke(ev, windowId);
                    r.setMessageType.invoke(ev, atomValue);
                    r.setFormat.invoke(ev, 32);
                    r.setData.invoke(ev, 0, (long) xRoot);
                    r.setData.invoke(ev, 1, (long) yRoot);
                    r.setData.invoke(ev, 2, (long) direction);
                    r.setData.invoke(ev, 3, (long) BUTTON1);
                    r.setData.invoke(ev, 4, (long) SOURCE_APPLICATION);
                    final long pData = (Long) r.getPData.invoke(ev);
                    r.sendEvent.invoke(null, display, root, false,
                            SUBSTRUCTURE_NOTIFY | SUBSTRUCTURE_REDIRECT, pData);
                    r.flush.invoke(null, display);
                } finally {
                    r.dispose.invoke(ev);
                }
                return true;
            } finally {
                r.awtUnlock.invoke(null);
            }
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether the WM for {@code window}'s screen advertises
     * {@code _NET_WM_MOVERESIZE}. Exposed so a test can see which path a drag
     * takes.
     */
    static boolean wmManages(Window window) {
        final Ref r = REF;
        if (!r.ok || window == null)
            return false;
        try {
            r.awtLock.invoke(null);
            try {
                final Object accessor = r.getComponentAccessor.invoke(null);
                final Object peer = r.getPeer.invoke(accessor, window);
                if (peer == null)
                    return false;
                final int screen = ((Long) r.getScreenNumber.invoke(peer)).intValue();
                final long root = (Long) r.getRootWindow.invoke(null, screen);
                final Object atom = r.atomGet.invoke(null, "_NET_WM_MOVERESIZE");
                final long atomValue = (Long) r.atomGetAtom.invoke(atom);
                return wmSupports(r, root, atomValue);
            } finally {
                r.awtUnlock.invoke(null);
            }
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether the root window advertises {@code atom} in {@code _NET_SUPPORTED}.
     * A bare X server with no WM advertises nothing, so the handoff is skipped.
     */
    private static boolean wmSupports(Ref r, long root, long atom) {
        try {
            final Object supported = r.atomGet.invoke(null, "_NET_SUPPORTED");
            final Object list = r.getAtomListProperty.invoke(supported, root);
            final int n = java.lang.reflect.Array.getLength(list);
            for (int i = 0; i < n; i++) {
                final Object entry = java.lang.reflect.Array.get(list, i);
                if (((Long) r.atomGetAtom.invoke(entry)).longValue() == atom)
                    return true;
            }
            return false;
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The reflected X11 handles, resolved once. {@link #ok} is false if the
     * platform is not X11 or the {@code --add-opens} is missing, so callers can
     * cheaply skip the whole path.
     */
    private static final class Ref {
        final boolean ok;
        final Method awtLock;
        final Method awtUnlock;
        final Method getDisplay;
        final Method getRootWindow;
        final Method atomGet;
        final Method atomGetAtom;
        final Method getAtomListProperty;
        final Method getComponentAccessor;
        final Method getPeer;
        final Method getWindow;
        final Method getScreenNumber;
        final java.lang.reflect.Constructor<?> cmeCtor;
        final Method setType;
        final Method setWindow;
        final Method setMessageType;
        final Method setFormat;
        final Method setData;
        final Method getPData;
        final Method dispose;
        final Method sendEvent;
        final Method ungrabPointer;
        final Method ungrabKeyboard;
        final Method flush;

        Ref() {
            Method aLock = null, aUnlock = null, gDisplay = null, gRoot = null, aGet = null,
                    aGetAtom = null, aListProp = null, gAccessor = null, gPeer = null, gWindow = null,
                    gScreen = null, sType = null, sWindow = null, sMsg = null,
                    sFormat = null, sData = null, gPData = null, disp = null, sEvent = null,
                    uPointer = null, uKeyboard = null, fl = null;
            java.lang.reflect.Constructor<?> ctor = null;
            boolean good = false;
            try {
                final Class<?> toolkit = Class.forName("sun.awt.X11.XToolkit");
                aLock = toolkit.getMethod("awtLock");
                aUnlock = toolkit.getMethod("awtUnlock");
                gDisplay = toolkit.getMethod("getDisplay");

                final Class<?> libUtil = Class.forName("sun.awt.X11.XlibUtil");
                gRoot = libUtil.getMethod("getRootWindow", int.class);

                final Class<?> xatom = Class.forName("sun.awt.X11.XAtom");
                aGet = xatom.getMethod("get", String.class);
                aGetAtom = xatom.getDeclaredMethod("getAtom");
                aGetAtom.setAccessible(true);
                aListProp = xatom.getDeclaredMethod("getAtomListProperty", long.class);
                aListProp.setAccessible(true);

                final Class<?> wrapper = Class.forName("sun.awt.X11.XlibWrapper");
                sEvent = wrapper.getDeclaredMethod("XSendEvent",
                        long.class, long.class, boolean.class, long.class, long.class);
                sEvent.setAccessible(true);
                uPointer = wrapper.getDeclaredMethod("XUngrabPointer", long.class, long.class);
                uPointer.setAccessible(true);
                uKeyboard = wrapper.getDeclaredMethod("XUngrabKeyboard", long.class, long.class);
                uKeyboard.setAccessible(true);
                fl = wrapper.getDeclaredMethod("XFlush", long.class);
                fl.setAccessible(true);

                final Class<?> accessor = Class.forName("sun.awt.AWTAccessor");
                gAccessor = accessor.getMethod("getComponentAccessor");
                gPeer = Class.forName("sun.awt.AWTAccessor$ComponentAccessor")
                        .getMethod("getPeer", Component.class);

                final Class<?> base = Class.forName("sun.awt.X11.XBaseWindow");
                gWindow = base.getMethod("getWindow");
                gScreen = base.getDeclaredMethod("getScreenNumber");
                gScreen.setAccessible(true);

                final Class<?> cme = Class.forName("sun.awt.X11.XClientMessageEvent");
                ctor = cme.getDeclaredConstructor();
                sType = cme.getMethod("set_type", int.class);
                sWindow = cme.getMethod("set_window", long.class);
                sMsg = cme.getMethod("set_message_type", long.class);
                sFormat = cme.getMethod("set_format", int.class);
                sData = cme.getMethod("set_data", int.class, long.class);
                gPData = cme.getMethod("getPData");
                disp = cme.getMethod("dispose");

                good = true;
            }
            catch (Throwable t) {
                // Not X11, or the opens is missing: the bridge is unavailable.
                good = false;
            }
            ok = good;
            awtLock = aLock;
            awtUnlock = aUnlock;
            getDisplay = gDisplay;
            getRootWindow = gRoot;
            atomGet = aGet;
            atomGetAtom = aGetAtom;
            getAtomListProperty = aListProp;
            getComponentAccessor = gAccessor;
            getPeer = gPeer;
            getWindow = gWindow;
            getScreenNumber = gScreen;
            cmeCtor = ctor;
            setType = sType;
            setWindow = sWindow;
            setMessageType = sMsg;
            setFormat = sFormat;
            setData = sData;
            getPData = gPData;
            dispose = disp;
            sendEvent = sEvent;
            ungrabPointer = uPointer;
            ungrabKeyboard = uKeyboard;
            flush = fl;
        }
    }

    private static final Ref REF = new Ref();
}
