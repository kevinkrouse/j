package org.armedbear.j.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * User: kevink
 * Date: 7/11/12
 */
public class Sets {
    @SafeVarargs
    public static <E> Set<E> newHashSet(E... values) {
        int capacity = Math.max(2 * values.length, 11);
        HashSet<E> set = new HashSet<>(capacity);
        Collections.addAll(set, values);
        return set;
    }
}
