/*
 * RegexTagger.java
 *
 * Copyright (C) 2000-2003 Peter Graves
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tags each line that one of its patterns finds, matched against the line
 * trimmed, naming the tag by the pattern's first group.
 */
public final class RegexTagger extends Tagger {
    private final BiFunction<String, Line, LocalTag> tag;
    private final List<Pattern> patterns;

    public RegexTagger(SystemBuffer buffer, Pattern... patterns) {
        this(buffer, LocalTag::new, patterns);
    }

    public RegexTagger(SystemBuffer buffer, BiFunction<String, Line, LocalTag> tag, Pattern... patterns) {
        super(buffer);
        this.tag = tag;
        this.patterns = List.of(patterns);
    }

    @Override
    public void run() {
        List<LocalTag> tags = new ArrayList<>();
        for (Line line = buffer.getFirstLine(); line != null; line = line.next()) {
            String s = line.trim();
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(s);
                if (matcher.find()) {
                    tags.add(tag.apply(matcher.group(1), line));
                    break;
                }
            }
        }
        buffer.setTags(tags);
    }
}
