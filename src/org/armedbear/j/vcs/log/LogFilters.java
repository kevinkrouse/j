/*
 * LogFilters.java
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

package org.armedbear.j.vcs.log;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.armedbear.j.Editor;
import org.armedbear.j.transients.Transient;
import org.armedbear.j.transients.Transient.Binding;
import org.armedbear.j.transients.Transient.Chooser;
import org.armedbear.j.transients.Transient.Command;
import org.armedbear.j.transients.Transient.Field;
import org.armedbear.j.transients.Transient.Group;
import org.armedbear.j.transients.Transient.Option;
import org.armedbear.j.transients.Transient.Pick;
import org.armedbear.j.transients.TransientArgs;
import org.armedbear.j.vcs.VcsRepository;

/**
 * The log's filters, as options: bound to an open log's query, they are
 * the chips of its filter bar and the items of its filter popup, which
 * change the same values; unbound, they are the status buffer's log
 * popup's arguments, which the log it opens starts from.
 */
final class LogFilters {
    private LogFilters() {}

    /** A new query from one, a value and the log's repository. */
    private interface With {
        LogQuery apply(LogQuery query, String value, VcsRepository repository);
    }

    // A filter: its option, how a query has its value, and how one gets it.
    private record Filter(Option option, Function<LogQuery, String> get, With with) {
        Filter(
                String key,
                String name,
                String description,
                Chooser chooser,
                Function<LogQuery, String> get,
                With with) {
            this(new Option(key, name, description, chooser, binding(get, with)), get, with);
        }
    }

    private static final List<Filter> FILTERS = List.of(
        new Filter("-b",
                "<revision>",
                "branch",
                new Pick(LogFilters::refNames),
                q -> q.revisions().isEmpty() ? null : q.revisions().get(0),
                (q, v, r) -> q.withRevision(v)),
        new Filter("-a",
                "--author",
                "author",
                new Pick(LogFilters::authors),
                LogQuery::author,
                (q, v, r) -> q.withAuthor(v)),
        new Filter("-p", "-- path", "path", new Pick(LogFilters::paths), LogQuery::path, LogFilters::withPath),
        new Filter("-d", "--since", "since", new Field(), LogQuery::since, (q, v, r) -> q.withSince(v)),
        new Filter("-g", "--grep", "message", new Field(), LogQuery::grep, (q, v, r) -> q.withGrep(v)),
        new Filter("-s", "-S", "change", new Field(), LogQuery::pickaxe, (q, v, r) -> q.withPickaxe(v)));

    /** Bound to the log in the editor. */
    static final List<Option> ALL = FILTERS.stream().map(Filter::option).toList();

    static final Option BRANCH = ALL.get(0);
    static final Option AUTHOR = ALL.get(1);
    static final Option PATH = ALL.get(2);
    static final Option GREP = ALL.get(4);

    /** The same, unbound: arguments kept by the popup they are in. */
    static final List<Option> ARGUMENTS =
            ALL.stream().map(o -> new Option(o.key(), o.name(), o.description(), o.chooser(), null)).toList();

    /** The filter described as name ("author"), or null. */
    static Option named(String name) {
        for (Option o : ALL)
            if (o.description().equals(name))
                return o;
        return null;
    }

    /** The popup of an open log's filters, with x to clear them all. */
    static Transient popup() {
        return new Transient("vcs.log.filters",
                "Log filters",
                "git.html#log-filters",
                List.of(
                    new Group("Filter", List.copyOf(ALL)),
                    Group.of("Clear", new Command("x", "vcsLogClearFilters", "every filter", true))));
    }

    /**
     * The id the log popup of a status buffer keeps its arguments under:
     * one per repository, so that filters set for one aren't another's.
     */
    static String openingId(VcsRepository repository) {
        return repository == null ? "vcs.log.open" : "vcs.log.open:" + repository.root();
    }

    /** The popup that opens a log of repository, its filters set first, as Neogit's does. */
    static Transient openingPopup(VcsRepository repository) {
        return new Transient(openingId(repository),
                "Log",
                "git.html#log-filters",
                List.of(
                    new Group("Filter", List.copyOf(ARGUMENTS)),
                    Group.of("Log", Command.of("l", "vcsLog", "log"), Command.of("q", "vcsLogQuick", "quick log"))));
    }

    /** query narrowed by the opening popup's arguments set in args; a file's log keeps its path. */
    static LogQuery withArguments(LogQuery query, TransientArgs args, VcsRepository repository) {
        LogQuery q = query;
        for (Filter f : FILTERS) {
            final String value = blank(args.get(f.option().name()));
            if (value != null && !(f.option() == PATH && q.path() != null))
                q = f.with().apply(q, value, repository);
        }
        return q;
    }

    // A file's history follows its renames; a directory's can't.
    private static LogQuery withPath(LogQuery q, String path, VcsRepository repository) {
        final boolean file =
                path != null && !path.endsWith("/") && Files.isRegularFile(repository.root().resolve(path));
        return q.withPath(path, file);
    }

    // An option's value read from the log's query, and set by a new one.
    private static Binding<String> binding(Function<LogQuery, String> get, With with) {
        return new Binding<>() {
            @Override
            public String get(Editor editor) {
                final LogBuffer log = LogBuffers.owner(editor.getBuffer());
                return log == null ? null : get.apply(log.getQuery());
            }

            @Override
            public void set(Editor editor, String value) {
                final LogBuffer log = LogBuffers.owner(editor.getBuffer());
                if (log != null)
                    log.setQuery(with.apply(log.getQuery(), blank(value), log.getRepository()));
            }
        };
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    // The names of the log's repository, or of the one a log would open from editor.
    private static LogNames names(Editor editor) {
        final VcsRepository r = LogCommands.repositoryOf(editor);
        return r == null ? null : LogNames.of(r);
    }

    // Local branches, then remotes, then tags.
    private static List<String> refNames(Editor editor) {
        final LogNames names = names(editor);
        final List<String> out = new ArrayList<>();
        if (names == null)
            return out;
        for (LogRef.Kind kind : List.of(LogRef.Kind.BRANCH, LogRef.Kind.REMOTE, LogRef.Kind.TAG))
            for (LogRef r : names.refs())
                if (r.kind() == kind)
                    out.add(r.name());
        return out;
    }

    private static List<String> authors(Editor editor) {
        final LogNames names = names(editor);
        return names == null ? List.of() : names.authors();
    }

    private static List<String> paths(Editor editor) {
        final LogNames names = names(editor);
        return names == null ? List.of() : names.paths();
    }
}
