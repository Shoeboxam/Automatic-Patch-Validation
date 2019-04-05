package edu.utdallas.lp;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.util.Objects;

public class BugId {
    private final String subject;
    private final int bugNo;

    public BugId(String subject, int bugNo) {
        this.subject = subject;
        this.bugNo = bugNo;
    }

    public String getSubject() {
        return this.subject;
    }

    public int getBugNo() {
        return this.bugNo;
    }

    public File getLogFile() {
        return FileUtils.getFile("log-reports",
                this.subject,
                String.format("%d.log", this.bugNo));
    }

    public File getOldLogFile() {
        return FileUtils.getFile("log-reports",
                this.subject,
                String.format("%d.log.old", this.bugNo));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BugId bugId1 = (BugId) o;
        return this.bugNo == bugId1.bugNo &&
                Objects.equals(this.subject, bugId1.subject);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.subject, this.bugNo);
    }
}
