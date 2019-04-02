package edu.utdallas.window;

import org.apache.commons.collections4.IteratorUtils;

import java.util.Iterator;

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
    public static final int WINDOW_CAPACITY = 20;

    private final AbstractInstruction[] view;
    private final int center;
    private int size;

    /**
     *
     * @param center the line number in the source code, around which we are constructing a
     *               window.
     */
    public Window(int center) {
        this.view = new AbstractInstruction[WINDOW_CAPACITY];
        this.center = center;
        this.size = 0;
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
        return this.size;
    }

    public void add(AbstractInstruction instruction) {
        if (this.size == WINDOW_CAPACITY) {
            throw new IllegalStateException("window is full!");
        }
        this.view[this.size++] = instruction;
    }

    @Override
    public Iterator<AbstractInstruction> iterator() {
        return IteratorUtils.arrayIterator(this.view);
    }
}
