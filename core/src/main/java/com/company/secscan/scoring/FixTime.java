package com.company.secscan.scoring;

import com.company.secscan.finding.RawFinding;
import java.util.Map;

/**
 * Derives the remediation-effort category for a finding and resolves it to minutes against the tunable
 * {@code fix_time_config} map (SPEC "Fix time"). Pure: the config map is passed in by the caller (loaded from
 * the DB config table), so this is unit-testable with no Spring.
 *
 * <p>SCA: a <em>major</em> upgrade (installed→fixed major version increases) costs more than a patch/minor one;
 * with no fixed version the magnitude is unknown and we default to the smaller patch/minor category (documented
 * — a null fixed version often means no fix is published yet, so we do not inflate remediation estimates).
 * Secret → rotation. SAST/IaC/EOL categories are defined for M6 and unused now.
 */
public final class FixTime {

    public static final String SCA_PATCH_MINOR = "SCA_PATCH_MINOR";
    public static final String SCA_MAJOR = "SCA_MAJOR";
    public static final String SECRET_ROTATION = "SECRET_ROTATION";
    public static final String SAST_INJECTION = "SAST_INJECTION";
    public static final String IAC_MISCONFIG = "IAC_MISCONFIG";
    public static final String EOL_UPGRADE = "EOL_UPGRADE";
    public static final String DEFAULT = "DEFAULT";

    private static final int DEFAULT_MINUTES = 20;

    private FixTime() {
    }

    /** The fix-time category for a finding, from its type and (for SCA) the installed→fixed semver magnitude. */
    public static String category(String type, String installedVersion, String fixedVersion) {
        return switch (type == null ? "" : type) {
            case RawFinding.TYPE_SCA -> isMajorUpgrade(installedVersion, fixedVersion) ? SCA_MAJOR : SCA_PATCH_MINOR;
            case RawFinding.TYPE_SECRET -> SECRET_ROTATION;
            case RawFinding.TYPE_IAC -> IAC_MISCONFIG;
            case RawFinding.TYPE_EOL -> EOL_UPGRADE;
            default -> DEFAULT; // SAST is refined per rule category in M6
        };
    }

    /** Minutes for a category from the config map, falling back to DEFAULT then a hard 20 if neither is present. */
    public static int minutes(String category, Map<String, Integer> config) {
        if (config != null) {
            Integer m = config.get(category);
            if (m != null) {
                return m;
            }
            Integer def = config.get(DEFAULT);
            if (def != null) {
                return def;
            }
        }
        return DEFAULT_MINUTES;
    }

    /** Convenience: category + minutes for a finding in one call. */
    public static int minutesFor(String type, String installedVersion, String fixedVersion,
                                 Map<String, Integer> config) {
        return minutes(category(type, installedVersion, fixedVersion), config);
    }

    /**
     * True when {@code fixedVersion}'s major component is strictly greater than {@code installedVersion}'s. A
     * null/blank/unparseable fixed version is NOT a major upgrade (defaults to patch/minor, documented).
     */
    static boolean isMajorUpgrade(String installedVersion, String fixedVersion) {
        int installedMajor = major(installedVersion);
        int fixedMajor = major(fixedVersion);
        if (installedMajor < 0 || fixedMajor < 0) {
            return false;
        }
        return fixedMajor > installedMajor;
    }

    /** Leading major version component (digits before the first '.'/'-'/'+'), or -1 when unparseable. */
    static int major(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        String v = version.trim();
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(v.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
