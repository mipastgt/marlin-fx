/*
 * Copyright (c) 2007, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.marlin;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Vector;
import static com.sun.marlin.MarlinConst.LOG_UNSAFE_MALLOC;
//import sun.awt.util.ThreadGroupUtils;
//import jdk.internal.misc.Unsafe;
import sun.misc.Unsafe;

/**
 *
 * @author bourgesl
 */
public final class OffHeapArray  {

    // unsafe reference
    public static final Unsafe UNSAFE;
    // size of int / float
    static final int SIZE_INT;

    static {
        Unsafe ref = null;
        try {
            final Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            ref = (Unsafe) field.get(null);
        } catch (Exception e) {
            MarlinUtils.logInfo("Unable to get sun.misc.Unsafe; exit now.");
            System.exit(1);
        }
        UNSAFE   = ref;
        SIZE_INT = Unsafe.ARRAY_INT_INDEX_SCALE;

        // Mimics Java2D Disposer:
        AccessController.doPrivileged(
            (PrivilegedAction<Void>) () -> {
                /*
                 * The thread must be a member of a thread group
                 * which will not get GCed before VM exit.
                 * Make its parent the top-level thread group.
                 */
                final ThreadGroup rootTG
//                    = ThreadGroupUtils.getRootThreadGroup();
                    = MarlinUtils.getRootThreadGroup();
                final Thread t = new Thread(rootTG, new OffHeapDisposer(),
                    "MarlinRenderer Disposer");
                t.setContextClassLoader(null);
                t.setDaemon(true);
                t.setPriority(Thread.MAX_PRIORITY - 2);
                t.start();
                return null;
            }
        );
    }

    /* members */
    public long address;
    long length;
    int  used;

    public OffHeapArray(final Object parent, final long len) {
        // note: may throw OOME:
        this.address = UNSAFE.allocateMemory(len);
        this.length  = len;
        this.used    = 0;
        if (LOG_UNSAFE_MALLOC) {
            MarlinUtils.logInfo(System.currentTimeMillis()
                                + ": OffHeapArray.allocateMemory =   "
                                + len + " to addr = " + this.address);
        }

        // Create the phantom reference to ensure freeing off-heap memory:
        REF_LIST.add(new OffHeapReference(parent, this));
    }

    /*
     * As realloc may change the address, updating address is MANDATORY
     * @param len new array length
     * @throws OutOfMemoryError if the allocation is refused by the system
     */
    void resize(final long len) {
        // note: may throw OOME:
        this.address = UNSAFE.reallocateMemory(address, len);
        this.length  = len;
        if (LOG_UNSAFE_MALLOC) {
            MarlinUtils.logInfo(System.currentTimeMillis()
                                + ": OffHeapArray.reallocateMemory = "
                                + len + " to addr = " + this.address);
        }
    }

    void free() {
        UNSAFE.freeMemory(this.address);
        if (LOG_UNSAFE_MALLOC) {
            MarlinUtils.logInfo(System.currentTimeMillis()
                                + ": OffHeapArray.freeMemory =       "
                                + this.length
                                + " at addr = " + this.address);
        }
    }

    void fill(final byte val) {
        UNSAFE.setMemory(this.address, this.length, val);
    }

    // Custom disposer (replaced by jdk9 Cleaner)

    // Parent reference queue
    private static final ReferenceQueue<Object> REF_QUEUE
        = new ReferenceQueue<Object>();
    // reference list
    private static final Vector<OffHeapReference> REF_LIST
        = new Vector<OffHeapReference>(32);

    static final class OffHeapReference extends PhantomReference<Object> {

        private final OffHeapArray array;

        OffHeapReference(final Object parent, final OffHeapArray edges) {
            super(parent, REF_QUEUE);
            this.array = edges;
        }

        void dispose() {
            // free off-heap blocks
            this.array.free();
        }
    }

    static final class OffHeapDisposer implements Runnable {
        @Override
        public void run() {
            final Thread currentThread = Thread.currentThread();
            OffHeapReference ref;

            // check interrupted:
            for (; !currentThread.isInterrupted();) {
                try {
                    ref = (OffHeapReference)REF_QUEUE.remove();
                    ref.dispose();

                    REF_LIST.remove(ref);

                } catch (InterruptedException ie) {
                    MarlinUtils.logException("OffHeapDisposer interrupted:",
                                             ie);
                }
            }
        }
    }
}