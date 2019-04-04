package edu.utdallas.window;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a window of instructions of size at most <code>WINDOW_CAPACITY</code>.
 * The instructions in a window are centered around a line number in the source code;
 * from the center, we pick <code>WINDOW_CAPACITY</code>/2 instructions before and
 * <code>WINDOW_CAPACITY</code>/2 instructions after to ensure a window of window of
 * proper size.
 *
 * @author ali
 */
public class Window implements Iterable<AbstractInstruction> {
    public static final int WINDOW_CAPACITY;

    static {
        final int windowCapacity =
                Integer.parseInt(System.getProperty("window.capacity", "21"));
        if (windowCapacity <= 1 || windowCapacity % 2 == 0) {
            throw new RuntimeException("window capacity must be an odd number greater than 1");
        }
        WINDOW_CAPACITY = windowCapacity;
    }

    private final List<AbstractInstruction> view;
    private final int center;

    /**
     *
     * @param center the line number in the source code, around which we are constructing a
     *               window.
     */
    public Window(int center) {
        this.view = new ArrayList<>(WINDOW_CAPACITY);
        this.center = center;
    }

    /**
     * Returns the line number at which the center of the window is located.
     * @return the (source code level) line number at which the center of window is located.
     */
    public int getCenter() {
        return this.center;
    }

    /**
     * Returns the actual size of the window
     * @return current size of the window (<= <code>WINDOW_CAPACITY</code>).
     */
    public int getSize() {
        return this.view.size();
    }

    public void add(AbstractInstruction instruction) {
        if (this.view.size() == WINDOW_CAPACITY) {
            throw new IllegalStateException("window is full!");
        }
        this.view.add(instruction);
    }

    @Override
    public Iterator<AbstractInstruction> iterator() {
        return this.view.iterator();
    }

    @Override
    public String toString() {
        final String contents;
        contents = this.view.stream()
                .map(AbstractInstruction::toString)
                .collect(Collectors.joining(","));
        return String.format("[%s]", contents);
    }
}
