package demo;

import java.util.List;

/** A sample. */
public class Sample extends Base implements Runnable {
    private static final int MASK = 0XFF | 0x0f;
    private final String name = "a \"quoted\" string";
    private char c = '\'';

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
}
