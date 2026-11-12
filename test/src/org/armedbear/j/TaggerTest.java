/*
 * TaggerTest.java
 *
 * Copyright (C) 2026 Kevin Krouse
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package org.armedbear.j;

import static org.armedbear.j.Constants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Taggers run as the tag file builder runs them: on a SystemBuffer, which has no mode. */
public class TaggerTest {
    @TempDir
    Path dir;

    private List<String> tags(int modeId, String name, String text) throws Exception {
        Path p = Files.writeString(dir.resolve(name), text);
        SystemBuffer buffer = new SystemBuffer(File.getInstance(p.toString()));
        buffer.load();
        Editor.getModeList().getMode(modeId).getTagger(buffer).run();
        List<String> names = new ArrayList<>();
        for (LocalTag tag : buffer.getTags())
            names.add(tag.getName());
        return names;
    }

    // Name and type, to tell a field from a method.
    private List<String> typedTags(String text) throws Exception {
        Path p = Files.writeString(dir.resolve("a.java"), text);
        SystemBuffer buffer = new SystemBuffer(File.getInstance(p.toString()));
        buffer.load();
        Editor.getModeList().getMode(JAVA_MODE).getTagger(buffer).run();
        List<String> names = new ArrayList<>();
        for (LocalTag tag : buffer.getTags())
            names.add(tag.getName() + ":" + tag.getType());
        return names;
    }

    // Name, type and visibility, to tell a public member from a private one.
    private List<String> visibleTags(String text) throws Exception {
        Path p = Files.writeString(dir.resolve("a.java"), text);
        SystemBuffer buffer = new SystemBuffer(File.getInstance(p.toString()));
        buffer.load();
        Editor.getModeList().getMode(JAVA_MODE).getTagger(buffer).run();
        List<String> names = new ArrayList<>();
        for (LocalTag tag : buffer.getTags())
            names.add(tag.getName() + (tag.isPublic() ? ":pub" : "") + (tag.isPrivate() ? ":priv" : ""));
        return names;
    }

    @Test
    public void python() throws Exception {
        assertEquals(List.of("class A", "f"), tags(PYTHON_MODE, "a.py", "class A:\n    def f(self):\n        pass\n"));
    }

    @Test
    public void ruby() throws Exception {
        assertEquals(List.of("class A", "f"), tags(RUBY_MODE, "a.rb", "class A\n  def f\n  end\nend\n"));
    }

    @Test
    public void perlTagsDefinitionsNotDeclarations() throws Exception {
        assertEquals(List.of("g", "P::h"), tags(PERL_MODE, "a.pl", "sub f;\nsub g {\n}\nsub P::h {\n}\n"));
    }

    @Test
    public void javaGenericsDoNotTagTypeArguments() throws Exception {
        // The View bug: the comma in the second <...> used to flip the field
        // initializer off, and the constructor's '(' made View a method.
        assertEquals(
            List.of("class C:2", "C.views:4"),
            typedTags(
                "class C {\n" + "    Hashtable<SystemBuffer, View> views = new Hashtable<SystemBuffer, View>();\n"
                        + "}\n"));
    }

    @Test
    public void javaRecord() throws Exception {
        // The header's components are not fields; the body's method is.
        assertEquals(
            List.of("class C:2", "record Point:2", "Point.sum:3"),
            typedTags("class C {\n" + "    record Point(int x, int y) { int sum() { return x + y; } }\n" + "}\n"));
    }

    @Test
    public void javaEnum() throws Exception {
        // Constants are TAG_CONSTANT (8); methods after the ';' are methods.
        assertEquals(
            List.of("class C:2", "enum Color:2", "Color.RED:8", "Color.GREEN:8", "Color.name:3"),
            typedTags("class C {\n" + "    enum Color { RED, GREEN; String name() { return \"x\"; } }\n" + "}\n"));
    }

    @Test
    public void javaEnumWithConstantArguments() throws Exception {
        assertEquals(
            List.of("class C:2", "enum Big:2", "Big.A:8", "Big.B:8", "Big.v:4", "Big.Big:3"),
            typedTags("class C {\n" + "    enum Big { A(1), B(2); int v; Big(int v) { this.v = v; } }\n" + "}\n"));
    }

    @Test
    public void javaRecordCompactConstructor() throws Exception {
        // A compact constructor's block is skipped whole: its statements are
        // not members, and its braces do not unbalance the class stack, so
        // the member after the record stays under the outer class.
        assertEquals(
            List.of("class C:2", "record R:2", "C.f:4"),
            typedTags(
                "class C {\n" + "    record R(int x) { R { if (x < 0) throw new Error(); } }\n" + "    int f;\n"
                        + "}\n"));
    }

    @Test
    public void javaInstanceInitializer() throws Exception {
        // A bare initializer block is skipped whole, the same way.
        assertEquals(
            List.of("class C:2", "C.f:4"),
            typedTags("class C {\n" + "    { x = 1; }\n" + "    int f;\n" + "}\n"));
    }

    @Test
    public void javaSealedPermitsAddsNoTags() throws Exception {
        assertEquals(List.of("class Shape:2"), typedTags("sealed class Shape permits Circle, Square { }\n"));
    }

    @Test
    public void javaComparisonsAreNotGenerics() throws Exception {
        // A '<' not following an identifier is a comparison, not a generic;
        // and a tight one that runs to a ';' before closing is a comparison
        // too. These sit at class level so the tagger actually scans them:
        // inside a method body skipBrace() would swallow the whole thing.
        assertEquals(
            List.of("class C:2", "C.b:4", "C.f:4"),
            typedTags("class C {\n" + "    boolean b = a<c && d;\n" + "    int f;\n" + "}\n"));
    }

    @Test
    public void javaEnumWithConstantBodies() throws Exception {
        // A constant's body, with its own method, is skipped whole; the
        // abstract method after the ';' is the enum's.
        assertEquals(
            List.of("enum Op:2", "Op.PLUS:8", "Op.MINUS:8", "Op.apply:3"),
            typedTags(
                "enum Op {\n" + "    PLUS { int apply(int a) { return a; } },\n"
                        + "    MINUS { int apply(int a) { return -a; } };\n" + "    abstract int apply(int a);\n"
                        + "}\n"));
    }

    @Test
    public void javaCompactConstructorModifiersEndWithIt() throws Exception {
        // "public R { }" is public; the members after it are not.
        assertEquals(
            List.of("record R:pub", "R.count", "R.sum:priv"),
            visibleTags(
                "public record R(int x) {\n" + "    public R { }\n" + "    static int count;\n"
                        + "    private int sum() { return 1; }\n" + "}\n"));
    }

    @Test
    public void javaScriptBlocksKeepTheirFunctions() throws Exception {
        // Only a Java class body's bare blocks are skipped.
        assertEquals(
            List.of("helper", "catch"),
            tags(JAVASCRIPT_MODE, "a.js", "try {\n  function helper() {}\n} catch (e) {}\n"));
        assertEquals(List.of("x", "g"), tags(JAVASCRIPT_MODE, "b.js", "x = 1;\n{\n  function g() {}\n}\n"));
    }

    @Test
    public void javaScriptHasNoRecords() throws Exception {
        assertEquals(List.of("h"), tags(JAVASCRIPT_MODE, "a.js", "record\nfunction h() {}\n"));
    }

    @Test
    public void javaImplementsThenPermits() throws Exception {
        // The permitted subclasses are not interfaces S implements.
        assertEquals(List.of("class S:2", "I:6"), typedTags("sealed class S implements I permits A, B { }\n"));
    }

    @Test
    public void javaShiftsAreNotGenerics() throws Exception {
        assertEquals(
            List.of("class C:2", "C.A:4", "C.B:4", "C.lt:4", "C.gt:4", "C.f:4"),
            typedTags(
                "class C {\n" + "    int A = 1<<4, B = 1>>2;\n" + "    boolean lt = a<b, gt = c>d;\n" + "    int f;\n"
                        + "}\n"));
    }

    @Test
    public void javaMethodTypeParametersAreNotFields() throws Exception {
        assertEquals(
            List.of("class C:2", "C.m:3", "C.id:3", "C.bare:3", "C.e:4", "C.e2:4", "C.f:4"),
            typedTags(
                "class C {\n" + "    public static <K, V> Map<K, V> m() { return null; }\n"
                        + "    @SuppressWarnings(\"x\") <T> T id(T t) { return t; }\n"
                        + "    <T, U> void bare(T t) {}\n"
                        + "    List<String> e = Collections.<String, X>emptyList(), e2;\n" + "    int f;\n" + "}\n"));
    }

    @Test
    public void javaEnumConstantAnnotationsAreNotConstants() throws Exception {
        assertEquals(
            List.of("enum E:2", "E.A:8", "E.B:8"),
            typedTags("enum E {\n    @Deprecated A,\n    @JsonProperty(\"b\") B;\n}\n"));
    }

    @Test
    public void javaEnumImplements() throws Exception {
        assertEquals(List.of("enum E:2", "I:6", "J:6", "E.A:8"), typedTags("enum E implements I, J { A; }\n"));
    }

    @Test
    public void javaTextBlocksHideTheirBracesAndQuotes() throws Exception {
        // A '{' or a lone '"' inside a text block doesn't unbalance the
        // method's braces, so the members after it are still tagged.
        assertEquals(
            List.of("class C:2", "C.m:3", "C.f:4", "C.g:3"),
            typedTags(
                "class C {\n" + "    void m() {\n" + "        String s = \"\"\"\n"
                        + "            \"{\" say \"hi \\\"\"\" still\n" + "            \"\"\";\n" + "    }\n"
                        + "    int f;\n" + "    void g() {}\n" + "}\n"));
    }
}
