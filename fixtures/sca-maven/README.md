# Fixture: sca-maven

Synthetic Maven project. `commons-text:1.9` carries **CVE-2022-42889** (Text4Shell).
Used by `ScaLiveScanIT` (the one `@Tag("live-osv")` end-to-end test) and as the source of
`app/src/test/resources/samples/osv/maven.json`.
