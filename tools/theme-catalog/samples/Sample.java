package org.example.shapes;

import java.util.List;

/**
 * Circles, largest first.
 */
public final class Sample {
    private static final double SCALE = 1.5;

    record Circle(String name, double r) {
        double area() {
            return Math.PI * r * r * SCALE;
        }
    }

    public static void main(String[] args) {
        var all = List.of(new Circle("small", 2),
                          new Circle("large", 10));
        for (Circle c : all) {
            if (c.area() > 100) // big enough
                System.out.printf("%s: %.2f%n",
                                  c.name(), c.area());
        }
    }
}
