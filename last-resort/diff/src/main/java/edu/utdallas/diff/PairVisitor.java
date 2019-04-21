package edu.utdallas.diff;

import java.io.File;

public interface PairVisitor {
    void visit(File buggy, File fixed, Label label);
}
