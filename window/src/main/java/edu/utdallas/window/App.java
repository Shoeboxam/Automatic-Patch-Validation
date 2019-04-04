package edu.utdallas.window;

import java.io.File;

/**
 * @author ali
 */
public class App {
    public static void main(String[] args) throws Exception {
        final File classFile = new File(args[0]);
        final int lineNumber = Integer.parseInt(args[1]);
        final Window window = new Window(lineNumber);
        WindowPopulator.populate(classFile, window);
        System.out.println(window);
    }
}
