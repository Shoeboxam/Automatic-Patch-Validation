package edu.utdallas.lp;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.io.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public final class LogParser {
    private static LogParser instance = null;
    public static final String ROW_HEADER = "[1-9]+\\.";
    private final Map<BugId, int[]> correctPatches;
    private final List<BugId> allBugs;

    private LogParser() {
        this.correctPatches = new HashMap<>();
        this.allBugs = new LinkedList<>();
    }

    public static synchronized LogParser v() {
        if (instance == null) {
            instance = new LogParser();
        }
        return instance;
    }

    private GroundTruthLabel getLabel(final BugId bugId, final int row) {
        for (final int r : correctPatches.get(bugId)) {
            if (r == row) {
                return GroundTruthLabel.CORRECT;
            }
        }
        return GroundTruthLabel.INCORRECT;
    }

    private void processRow(final BugId bugId,
                            final int row,
                            final BufferedReader br) throws Exception {
        final GroundTruthLabel label = getLabel(bugId, row);
        final String classJavaName = Commons.getValue(br.readLine());
        br.readLine(); // method name
        br.readLine(); // method descriptor
        final String fileName = Commons.getValue(br.readLine());
        final int lineNumber = Integer.parseInt(Commons.getValue(br.readLine()));
        final String mutatorName = Commons.getValue(br.readLine());
        final String mutatorDescription = Commons.getValue(br.readLine());
        final Row theRow = new Row(fileName,
                lineNumber,
                classJavaName,
                mutatorName,
                mutatorDescription);
        final String dumpFileName = PatchPool.v().dumpFileName(bugId, theRow);
        final String classInternalName = classJavaName.replace('.', '/');
        final File unmutatedClassFile = FileUtils.getFile("pool",
                "original",
                bugId.getSubject(),
                String.valueOf(bugId.getBugNo()),
                classInternalName + ".class");
    }

    private void loadCorrectPatches(final File csvFile) throws Exception {
        try (final Reader csvReader = new FileReader(csvFile);
             final CSVParser parser = new CSVParser(csvReader, CSVFormat.DEFAULT)) {
            parser.forEach(record -> {
                final String subject = record.get(0).trim();
                final int bugNo = Integer.parseInt(record.get(1).trim());
                final int[] correctPatches = Arrays.stream(record.get(2).trim().split(","))
                        .mapToInt(Integer::parseInt)
                        .toArray();
                final BugId bugId = new BugId(subject, bugNo);
                this.correctPatches.put(bugId, correctPatches);
            });
        }
    }

    private void loadAllBugs(final File csvFile) throws Exception {
        try (final Reader csvReader = new FileReader(csvFile);
             final CSVParser parser = new CSVParser(csvReader, CSVFormat.DEFAULT)) {
            parser.forEach(record -> {
                final String subject = record.get(0).trim();
                final int bugNo = Integer.parseInt(record.get(1).trim());
                final BugId bugId = new BugId(subject, bugNo);
                this.allBugs.add(bugId);
            });
        }
    }

    private void processBug(final BugId bugId) {
        final File logFile = bugId.getLogFile();
        try (final BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.matches(ROW_HEADER)) {
                    final int rowNo = Integer.parseInt(line.substring(0, line.length() - 1));
                    LogParser.v().processRow(bugId, rowNo, br);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void processBugs() {
        this.allBugs.stream().forEach(LogParser.this::processBug);
    }

    public static void main(String[] args) throws Exception {
        final File allBugsFile = new File(args[0]);
        final File correctPatchesFile = new File(args[1]);
        LogParser.v().loadAllBugs(allBugsFile);
        LogParser.v().loadCorrectPatches(correctPatchesFile);
        LogParser.v().processBugs();
    }
}
