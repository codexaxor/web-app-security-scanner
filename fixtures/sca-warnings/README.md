# Fixture: sca-warnings

Synthetic repo that triggers scan-health warnings (no CVEs needed):
- `infra/importer/build.gradle` — a nested Gradle module with **no** `gradle.lockfile` -> MISSING_GRADLE_LOCKFILE.
- `src/App/App.csproj` — a .NET project with **no** `packages.lock.json` -> MISSING_NUGET_LOCKFILE, and a
  non-concrete `Serilog` version `$(SerilogVersion)` -> UNRESOLVED_VERSION.

Used by `ScaWarningsIT` (real container, offline). Mixed ecosystems + a nested module in one tree.
