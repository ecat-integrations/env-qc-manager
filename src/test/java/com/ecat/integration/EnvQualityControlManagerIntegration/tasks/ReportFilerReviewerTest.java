package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportFilerReviewerTest {

    @Test
    void filerUsesLoginUsername_reviewerStaysEmpty() {
        ReportGenerator g = new ReportGenerator() {
            @Override
            protected String currentLoginUsername() {
                return "alice";
            }
        };
        QcmReport report = new QcmReport();
        QcmRecord rec = new QcmRecord();
        rec.setCreatedBy("admin");
        g.applyReportFilerAndEmptyReviewer(report, rec);
        assertEquals("alice", report.getFiler());
        assertEquals("", report.getReviewer());
    }

    @Test
    void filerFallsBackToRecordCreatorWhenNoLogin() {
        ReportGenerator g = new ReportGenerator() {
            @Override
            protected String currentLoginUsername() {
                return "";
            }
        };
        QcmReport report = new QcmReport();
        QcmRecord rec = new QcmRecord();
        rec.setCreatedBy("bob");
        g.applyReportFilerAndEmptyReviewer(report, rec);
        assertEquals("bob", report.getFiler());
        assertEquals("", report.getReviewer());
    }

    @Test
    void filerAndReviewerEmptyWhenNoLoginAndNoCreator() {
        ReportGenerator g = new ReportGenerator() {
            @Override
            protected String currentLoginUsername() {
                return "";
            }
        };
        QcmReport report = new QcmReport();
        g.applyReportFilerAndEmptyReviewer(report);
        assertEquals("", report.getFiler());
        assertEquals("", report.getReviewer());
    }
}
