package com.company.secscan.scan;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SAST language coverage (M8.15 S5, D-104). Given the languages our static detector reports for a repository
 * (GitLab-style display names from {@code lang_detect.py}), returns the ones that are SAST-eligible CODE
 * languages for which Opengrep has NO rule pack — the {@code SAST_LANGUAGE_UNCOVERED} coverage gap we surface
 * rather than hide (D-030). Pure logic, no Spring; the worker turns the result into a scan warning.
 *
 * <p>Three buckets, together covering every name {@code lang_detect.py} can emit:
 * <ul>
 *   <li>{@link #COVERED}: an Opengrep pack exists and produces findings — java, csharp, javascript, typescript,
 *       python, go, php, ruby, kotlin, scala.</li>
 *   <li>{@link #UNCOVERED_ELIGIBLE}: executable code worth SAST but with no Opengrep pack — reported. Vue and
 *       Svelte are here, NOT covered: on Opengrep 1.31.0 the javascript/typescript rules TARGET {@code .vue}/
 *       {@code .svelte} files (they appear in {@code paths.scanned}) but produce ZERO findings even for an
 *       identical top-level {@code eval()}/{@code document.write()} that fires three JS rules in a {@code .js}
 *       file — so their {@code <script>} code is effectively unanalyzed (M8.15 approved change #7, measured).</li>
 *   <li>{@link #NOT_SAST}: markup/style/template/data and the shell/IaC languages the spec excludes
 *       (HTML/CSS/…, Shell, Dockerfile, HCL) — never reported (HCL/Dockerfile are covered by the IaC scanner;
 *       Shell has no pack and would be noise on nearly every repo).</li>
 * </ul>
 * An unrecognised language (none of the three — e.g. a name a future detector adds) is NOT reported, so a new
 * detector name can never manufacture a false "uncovered" banner; add it to the right bucket deliberately.
 */
public final class SastLanguageCoverage {

    /** Languages with an Opengrep rule pack (findings proven in the recall fixture). */
    public static final Set<String> COVERED = Set.of(
            "Java", "C#", "JavaScript", "TypeScript", "Python", "Go", "PHP", "Ruby", "Kotlin", "Scala");

    /** SAST-eligible CODE languages with no Opengrep pack — the reported gap. */
    public static final Set<String> UNCOVERED_ELIGIBLE = Set.of(
            "Groovy", "Rust", "C", "C++", "Swift", "PowerShell", "Vue", "Svelte");

    /** Markup/style/template/data + shell/IaC: never a SAST gap (D-104 / spec). */
    public static final Set<String> NOT_SAST = Set.of(
            "HTML", "CSS", "SCSS", "Sass", "Less", "FreeMarker", "Shell", "Dockerfile", "HCL");

    private SastLanguageCoverage() {
    }

    /**
     * The detected languages that are SAST-eligible but have no Opengrep pack, in detection order, de-duplicated.
     * Empty when every detected language is covered or non-SAST (so the worker raises no warning).
     */
    public static List<String> uncovered(List<String> detected) {
        if (detected == null || detected.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String lang : detected) {
            if (lang != null && UNCOVERED_ELIGIBLE.contains(lang)) {
                out.add(lang);
            }
        }
        return List.copyOf(out);
    }
}
