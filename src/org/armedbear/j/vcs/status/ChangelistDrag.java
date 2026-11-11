/*
 * ChangelistDrag.java
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

package org.armedbear.j.vcs.status;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.InvalidDnDOperationException;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import org.armedbear.j.vcs.VcsRepository;
import org.armedbear.j.vcs.changelists.RepositoryLists;
import org.armedbear.j.vcs.selection.Selections;
import org.armedbear.j.vcs.selection.Target;
import org.armedbear.j.vcs.status.ChangelistCommands.Destination;

/** An active drag's captured selection and JVM-local transfer token. */
final class ChangelistDrag implements Transferable {
    static final DataFlavor FLAVOR =
            new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=java.lang.Object", "VCS changelist drag");

    private final Object token = new Object();
    private final RepositoryStatus model;
    private final String from;
    private final List<Target> targets;

    private ChangelistDrag(RepositoryStatus model, String from, List<Target> targets) {
        this.model = model;
        this.from = from;
        this.targets = List.copyOf(targets);
    }

    static ChangelistDrag capture(RepositoryStatus model, String from, List<Target> targets) {
        if (model == null || from == null || ChangelistCommands.moveWhy(model, targets) != null)
            return null;
        final RepositoryLists lists = RepositoryLists.shown(targets.get(0).repository());
        if (lists == null || ChangelistCommands.hasUnreadMember(lists.lists(), targets))
            return null;
        return new ChangelistDrag(model, from, targets);
    }

    static ChangelistDrag capture(
            RepositoryStatus model,
            VcsRepository repository,
            String section,
            String from,
            Target.Member member) {
        if (model == null || from == null || member == null)
            return null;
        return capture(
            model,
            from,
            new Selections.Builder(model.picks()::ids).member(repository, section, member).build());
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[] { FLAVOR };
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!isDataFlavorSupported(flavor))
            throw new UnsupportedFlavorException(flavor);
        return token;
    }

    private boolean matches(Transferable transfer) {
        if (transfer == null || !transfer.isDataFlavorSupported(FLAVOR))
            return false;
        try {
            return transfer.getTransferData(FLAVOR) == token;
        }
        catch (UnsupportedFlavorException | IOException | InvalidDnDOperationException e) {
            return false;
        }
    }

    boolean canDrop(RepositoryStatus current, Destination to, Transferable transfer) {
        if (model != current
                || model.isDisposed()
                || to.repository() != targets.get(0).repository()
                || from.equals(to.name())
                || !matches(transfer))
            return false;
        final RepositoryLists lists = RepositoryLists.shown(to.repository());
        return lists != null && to.missingFrom(lists.lists()) == null;
    }

    boolean drop(RepositoryStatus current, Destination to, Transferable transfer, Consumer<String> said) {
        if (!canDrop(current, to, transfer))
            return false;
        ChangelistCommands.moveTargets(model, targets, to, said);
        return true;
    }
}
