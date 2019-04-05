package edu.utdallas.lp;

import java.util.Objects;

public class Row {
    private final String fileName;
    private final int lineNumber;
    private final String classJavaName;
    private final String mutatorName;
    private final String mutatorDescription;

    public Row(String fileName,
               int lineNumber,
               String classJavaName,
               String mutatorName,
               String mutatorDescription) {
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.classJavaName = classJavaName;
        this.mutatorName = mutatorName;
        this.mutatorDescription = mutatorDescription;
    }

    public String getFileName() {
        return this.fileName;
    }

    public int getLineNumber() {
        return this.lineNumber;
    }

    public String getClassJavaName() {
        return this.classJavaName;
    }

    public String getMutatorName() {
        return this.mutatorName;
    }

    public String getMutatorDescription() {
        return this.mutatorDescription;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Row row = (Row) o;
        return this.lineNumber == row.lineNumber &&
                Objects.equals(this.fileName, row.fileName) &&
                Objects.equals(this.mutatorName, row.mutatorName) &&
                Objects.equals(this.mutatorDescription, row.mutatorDescription);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.fileName,
                this.lineNumber,
                this.mutatorName,
                this.mutatorDescription);
    }
}
