/*
 * CommitOption.java
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

/** How a commit is made, as the commit popup sets it. */
public enum CommitOption {
    /** Replaces the last commit. */
    AMEND,
    /** With AMEND, keeps its message: what is staged is added to it. */
    KEEP_MESSAGE,
    /** With AMEND, changes only its message, leaving what is staged. */
    MESSAGE_ONLY,
    /** Skips the pre-commit and commit-msg hooks. */
    NO_VERIFY,
    /** Adds a Signed-off-by line. */
    SIGNOFF,
    /** Stages every change to a tracked file first. */
    ALL,
    /** Commits even with nothing staged. */
    ALLOW_EMPTY,
    /** Keeps lines starting with #, as a message that has its own does. */
    VERBATIM,
    /** Shows the diff being committed under the message, as git commit -v does. */
    VERBOSE
}
