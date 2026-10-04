/*
 * VcsLegacyExtension.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j.vcs.legacy;

import org.armedbear.j.Version;
import org.armedbear.j.extension.Extension;
import org.armedbear.j.extension.ExtensionContext;
import org.armedbear.j.vcs.cvs.CVS;
import org.armedbear.j.vcs.darcs.Darcs;
import org.armedbear.j.vcs.p4.P4;

/** CVS, Perforce and darcs commands. Their backends are services of their own. */
public final class VcsLegacyExtension implements Extension {
    @Override
    public String getName() {
        return "vcs-legacy";
    }

    @Override
    public String getVersion() {
        return Version.getVersion();
    }

    @Override
    public void initialize(ExtensionContext context) {
        context.registerCommand("cvs", CVS.class, "cvs");
        context.registerCommand("cvsAdd", CVS.class, "add");
        context.registerCommand("cvsCommit", CVS.class, "commit");
        context.registerCommand("cvsDiff", CVS.class, "diff");
        context.registerCommand("cvsDiffDir", CVS.class, "diffDir");
        context.registerCommand("cvsLog", CVS.class, "log");
        context.registerCommand("darcs", Darcs.class, "darcs");
        context.registerCommand("p4", P4.class, "p4");
        context.registerCommand("p4Add", P4.class, "add");
        context.registerCommand("p4Change", P4.class, "change");
        context.registerCommand("p4Diff", P4.class, "diff");
        context.registerCommand("p4DiffDir", P4.class, "diffDir");
        context.registerCommand("p4Edit", P4.class, "edit");
        context.registerCommand("p4Log", P4.class, "log");
        context.registerCommand("p4Revert", P4.class, "revert");
        context.registerCommand("p4Submit", P4.class, "submit");
    }
}
