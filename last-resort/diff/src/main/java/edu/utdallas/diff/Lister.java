package edu.utdallas.diff;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class Lister {
    private static final String[] JAVA_SOURCE_FILES = new String[] {"java"};
    private static Lister instance = null;

    private Lister() {

    }

    public static synchronized Lister v() {
        if (instance == null) {
            instance = new Lister();
        }
        return instance;
    }

    private Map<String, Label> getLabels(final File csv) throws IOException {
        final Map<String, Label> labelsMap = new HashMap<>();
        try (final Reader csvReader = new FileReader(csv);
             final CSVParser csvParser = new CSVParser(csvReader, CSVFormat.DEFAULT)) {
            csvParser.forEach(record -> {
                final String key = String.format("%s_%s_%s.java",
                        record.get(0),
                        record.get(1),
                        record.get(2));
                final Label label = Label.valueOf(record.get(5));
                labelsMap.put(key, label);
            });
        }
        return labelsMap;
    }

    public void list(final File basePath, final PairVisitor pairVisitor) throws IOException {
        // loading csv file that contains information about CapGen labels
        final File cgInfoFile = FileUtils.getFile(basePath, "cg-info.csv");
        final Map<String, Label> labelsMap = getLabels(cgInfoFile);
        // visit CapGen generated transformations
        final File cgPool = FileUtils.getFile(basePath, "cg-src-pool");
        listWorkhorse(cgPool, labelsMap::get, pairVisitor);
        // visit GitHub patches
        final File githubPool = FileUtils.getFile(basePath, "github-patches");
        listWorkhorse(githubPool, __ -> Label.CORRECT, pairVisitor);
    }

    private void listWorkhorse(final File pool,
                               final Function<String, Label> classifier,
                               final PairVisitor pairVisitor) {
        FileUtils.listFiles(pool, JAVA_SOURCE_FILES, true).stream()
                .collect(Collectors.groupingBy(File::getName)).values().stream()
                .map(FilePair::new)
                .forEach(filePair -> {
                    final Label label = classifier.apply(filePair.buggy.getName());
                    pairVisitor.visit(filePair.buggy, filePair.fixed, label);
                });
    }

    private final static class FilePair {
        private static final String BUGGY =
                File.separatorChar + "original" + File.separatorChar;
        private static final String FIXED =
                File.separatorChar + "mutated" + File.separatorChar;
        final File buggy;
        final File fixed;

        public FilePair(List<File> files) {
            if (files.size() != 2) {
                throw new IllegalArgumentException();
            }
            final File file0 = files.get(0);
            final File file1 = files.get(1);
            if (file0.getAbsolutePath().contains(BUGGY)) {
                this.buggy = file0;
                this.fixed = file1;
            } else if (file0.getAbsolutePath().contains(FIXED)) {
                this.buggy = file1;
                this.fixed = file0;
            } else {
                throw new IllegalArgumentException();
            }
        }
    }
}
