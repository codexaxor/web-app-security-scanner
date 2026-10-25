package com.company.secscan.scan;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.scan.api.ScanDetailView;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.scan.api.ScanWarningView;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Start Scan and read scan status. Full feed/dashboard screens are M8. */
@RestController
@RequestMapping("/api/v1")
public class ScanController {

    private final ScanService service;

    public ScanController(ScanService service) {
        this.service = service;
    }

    /** Queues a manual scan (or returns the one already in flight for this repository). */
    @PostMapping("/repositories/{repositoryId}/scans")
    public ResponseEntity<ScanView> start(@PathVariable UUID repositoryId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.startManual(repositoryId));
    }

    @GetMapping("/repositories/{repositoryId}/scans")
    public PagedResponse<ScanView> list(
            @PathVariable UUID repositoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listForRepository(repositoryId, page, size);
    }

    @GetMapping("/scans/{scanId}")
    public ScanDetailView get(@PathVariable UUID scanId) {
        return service.get(scanId);
    }

    /** All scans in the workspace, newest first (OD-14 item 2). */
    @GetMapping("/scans")
    public PagedResponse<ScanView> listAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listAll(page, size);
    }

    /** One scan's scan-health warnings (OD-14 item 2). */
    @GetMapping("/scans/{scanId}/warnings")
    public PagedResponse<ScanWarningView> warnings(
            @PathVariable UUID scanId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listWarnings(scanId, page, size);
    }
}
