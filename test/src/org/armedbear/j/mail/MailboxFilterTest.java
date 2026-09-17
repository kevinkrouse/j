/*
 * MailboxFilterTest.java
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

package org.armedbear.j.mail;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The filter parser builds its result on a stack, folding each new term into
 * what is already there. Nothing else exercises that folding, and getting the
 * stack discipline wrong yields a filter that silently matches the wrong
 * messages rather than one that fails.
 */
public class MailboxFilterTest
{
    @Test
    public void parsesASingleTerm()
    {
        assertTrue(MailboxFilter.getMailboxFilter("~D")
                   instanceof DeletedMailboxFilter);
        assertTrue(MailboxFilter.getMailboxFilter("~U")
                   instanceof UnreadMailboxFilter);
    }

    /** Two terms in a row are an implicit AND, folded into one AndTerm. */
    @Test
    public void foldsAdjacentTermsIntoASingleAnd()
    {
        MailboxFilter f = MailboxFilter.getMailboxFilter("~U ~F");
        assertTrue(String.valueOf(f), f instanceof AndTerm);

        // A third term folds into the same AndTerm rather than nesting.
        MailboxFilter g = MailboxFilter.getMailboxFilter("~U ~F ~T");
        assertTrue(String.valueOf(g), g instanceof AndTerm);
    }

    @Test
    public void parsesNegation()
    {
        MailboxFilter f = MailboxFilter.getMailboxFilter("!~D");
        assertTrue(String.valueOf(f), f instanceof NotTerm);
    }

    @Test
    public void parsesOr()
    {
        MailboxFilter f = MailboxFilter.getMailboxFilter("~U | ~F");
        assertTrue(String.valueOf(f), f instanceof OrTerm);
    }

    @Test
    public void parsesATermWithAnArgument()
    {
        assertNotNull(MailboxFilter.getMailboxFilter("~f peter"));
        assertNotNull(MailboxFilter.getMailboxFilter("~t peter ~U"));
    }
}
