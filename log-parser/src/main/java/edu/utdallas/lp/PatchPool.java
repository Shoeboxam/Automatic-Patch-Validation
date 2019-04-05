package edu.utdallas.lp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public final class PatchPool {
    private static PatchPool instance = null;

    private PatchPool() {

    }

    public static synchronized PatchPool v() {
        if (instance == null) {
            instance = new PatchPool();
        }
        return instance;
    }

    private String processRow(final Row row, final BufferedReader br) throws Exception {
        final String mutatorName = Commons.getValue(br.readLine());
        final String mutatorDescription = Commons.getValue(br.readLine());
        final String fileName = Commons.getValue(br.readLine());
        final int lineNumber = Integer.parseInt(Commons.getValue(br.readLine()));
        br.readLine(); // rank
        br.readLine(); // total rank
        final String dump = Commons.getValue(br.readLine());
        br.readLine(); // separator
        if (mutatorName.equals(row.getMutatorName()) &&
                mutatorDescription.equals(row.getMutatorDescription()) &&
                fileName.equals(row.getFileName()) &&
                lineNumber == row.getLineNumber()) {
            return dump;
        }
        return null;
    }

    public String dumpFileName(final BugId bugId, final Row row) throws Exception {
        final File logFile = bugId.getOldLogFile();
        try (final BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.matches(LogParser.ROW_HEADER)) {
                    final int rowNo = Integer.parseInt(line.substring(0, line.length() - 1));
                    final String dumpFileName = processRow(row, br);
                    if (dumpFileName != null) {
                        return dumpFileName;
                    }
                }
            }
        }
        return null;
    }
}
