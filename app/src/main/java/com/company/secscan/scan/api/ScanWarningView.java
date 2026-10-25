package com.company.secscan.scan.api;

import com.company.secscan.scan.ScanWarning;
import java.time.Instant;
import java.util.UUID;

/** A scan-health warning as shown in the Repositories warnings drawer (OD-14 item 2). */
public record ScanWarningView(UUID id, UUID scanId, String code, String message, String filePath,
                              Instant createdAt) {

    public static ScanWarningView of(ScanWarning w) {
        return new ScanWarningView(w.getId(), w.getScanId(), w.getCode(), w.getMessage(), w.getFilePath(),
                w.getCreatedAt());
    }
}
