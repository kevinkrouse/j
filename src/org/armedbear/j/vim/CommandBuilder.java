/*
 * CommandBuilder.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The command being typed: its count so far, and the keys after it.
 *
 * Kept apart from the trie because a count is not part of any binding.
 * {@code 3} is not the start of {@code 3dd}; it is a number in front of a
 * command, and {@code 30dd} means thirty, not three then a zero motion.
 */
public final class CommandBuilder {
    private final StringBuilder count = new StringBuilder();
    private final List<String> keys = new ArrayList<>();

    /** The operator waiting for a motion, in d{motion}. */
    private VimCommand operator;
    /** The count typed before the operator; 0 when there was none. */
    private int operatorCount;

    /**
     * Takes a key as a count digit if that is what it is.
     *
     * {@code 0} is a motion -- to the first column -- except when a count is
     * already being typed, where it is a digit like any other. That single
     * rule is why counts cannot simply live in the key map.
     *
     * @return true if the key was consumed as part of a count
     */
    public boolean acceptCountDigit(String key) {
        if (!keys.isEmpty() || key.length() != 1)
            return false;
        final char c = key.charAt(0);
        if (c < '0' || c > '9')
            return false;
        if (c == '0' && count.length() == 0)
            return false;
        count.append(c);
        return true;
    }

    public void pushKey(String key) {
        keys.add(key);
    }

    /** Puts back the key just pushed. */
    public void dropLastKey() {
        if (!keys.isEmpty())
            keys.remove(keys.size() - 1);
    }

    public List<String> getKeys() {
        return Collections.unmodifiableList(keys);
    }

    public boolean isEmpty() {
        return keys.isEmpty() && count.length() == 0 && operator == null;
    }

    /** True if the user actually typed a count. */
    public boolean hasCount() {
        return count.length() > 0;
    }

    /** The count, or 1 when none was given. */
    public int getCount() {
        if (count.length() == 0)
            return 1;
        try {
            return Integer.parseInt(count.toString());
        }
        catch (NumberFormatException e) {
            // A count long enough to overflow is a typo; vim clamps too.
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Records an operator and clears the way for its motion's own count.
     *
     * Both counts are kept because vim multiplies them: 2d3w deletes six
     * words, not two and not three.
     */
    public void setOperator(VimCommand operator) {
        this.operator = operator;
        this.operatorCount = hasCount() ? getCount() : 0;
        count.setLength(0);
        keys.clear();
    }

    public VimCommand getOperator() {
        return operator;
    }

    public boolean hasOperator() {
        return operator != null;
    }

    /**
     * The count for the motion, with the operator's folded in.
     */
    public int getEffectiveCount() {
        final int motionCount = getCount();
        return operatorCount == 0 ? motionCount : operatorCount * motionCount;
    }

    public boolean hasEffectiveCount() {
        return hasCount() || operatorCount != 0;
    }

    /**
     * Drops the keys typed so far, keeping the count and any operator.
     *
     * For a binding that stands for other keys: those keys are still the same
     * command, with the same count in front of it.
     */
    public void clearKeys() {
        count.setLength(0);
        keys.clear();
    }

    public void reset() {
        clearKeys();
        operator = null;
        operatorCount = 0;
    }

    /** What to show while the command is incomplete, as vim does. */
    public String getPendingText() {
        final StringBuilder sb = new StringBuilder();
        if (operatorCount != 0)
            sb.append(operatorCount);
        if (operator != null)
            sb.append(operator.getKeys());
        sb.append(count);
        for (String key : keys)
            sb.append(key);
        return sb.toString();
    }
}
