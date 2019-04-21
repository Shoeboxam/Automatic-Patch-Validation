package edu.utdallas.diff;

import java.io.File;

public class Driver {
    public static void main(String[] args) throws Exception {
        final File basePath = new File(args[0]);
        Lister.v().list(basePath, new DiffVisitor());
    }
}
