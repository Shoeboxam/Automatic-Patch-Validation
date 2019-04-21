package edu.utdallas.diff;

import gumtree.spoon.AstComparator;
import gumtree.spoon.diff.Diff;
import gumtree.spoon.diff.operations.Operation;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class DiffVisitor implements PairVisitor {
    @Override
    public void visit(File buggy, File fixed, Label label) {
        final List<Operation> operations = doDiff(buggy, fixed);
        if (!operations.isEmpty()) {
            visitDiff(operations, label);
        }
    }

    private List<Operation> doDiff(final File buggy, final File fixed) {
        final AstComparator ac = new AstComparator();
        try {
            final Diff diff = ac.compare(buggy, fixed);
            return new ArrayList<>(diff.getRootOperations());
        } catch (Exception e) {
            System.out.printf("warning: \'%s\' swallowed.%n", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void visitDiff(final List<Operation> editScript, final Label label) {
//        final String script = editScript.stream()
//                .map(Operation::getAction)
//                .map(Object::getClass)
//                .map(Class::getName)
//                .collect(Collectors.joining(","));
//        System.out.printf("%s, [%s]%n", label, script);
        // TODO: ....
    }
}
