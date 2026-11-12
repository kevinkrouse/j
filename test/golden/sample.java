package demo;

import java.util.List;

/** A sample. */
public class Sample extends Base implements Runnable {
    private static final int MASK = 0XFF | 0x0f;
    private final String name = "a \"quoted\" string";
    private char c = '\'';
    private final Hashtable<SystemBuffer, View> views = new Hashtable<SystemBuffer, View>();

    // A line comment.
    public Sample(int count) {
        super(count);
        for (int i = 0; i < count; i++) {
            if (i % 2 == 0)
                run();
            else {
                long big = 123L + 4.5e3;
            }
        }
    }

    @Override
    public void run() {
        /* a block
           comment */
        switch (name.length()) {
        case 1:
            break;
        default:
            List.of(1, 2);
        }
    }

    @java.lang.Override
    @SuppressWarnings("unchecked")
    public void modern() {
        var total = 0;
        Runnable r = () -> System.out.println("hi");
        Runnable r2 = this::run;
        int binary = 0b1010_1010;
        long million = 1_000_000L;
        float pi = 3.14f;
        double hexFloat = 0x1.8p1;
        String block = """
            line one
            line "two" with {braces}
            """;
        double small = 1.e5 + 1. + 2.f;
        String escaped = "a" + """
            esc \""" still inside
            """;
        List<@NonNull String> names = List.of();
        Thread.yield();
        int permits = record.size();
        int kind = switch (total) {
            default -> {
                yield permits;
            }
        };
    }

    record Point(int x, int y) {
        Point {
            if (x < 0)
                throw new IllegalArgumentException();
        }
        int sum() {
            return x + y;
        }
    }

    enum Color {
        RED, GREEN;
        String label() {
            return "x";
        }
    }

    sealed interface Shape permits Circle {
    }
}
