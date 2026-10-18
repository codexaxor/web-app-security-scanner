# Fixture: sca-private-registry

Declares private/internal package feeds alongside public ones. Used to prove M4b private-registry detection
(D-032, slice 3): the resolver WARNS (`PRIVATE_REGISTRY`, **host only**) and NEVER fetches from or stores
credentials for a private feed.

- `build.gradle` repositories: `mavenCentral()` + `google()` (PUBLIC, no warning); a private Nexus
  (`nexus.corp.example`) and a credential-bearing Artifactory URL
  (`https://svcuser:s3cr3tT0ken@artifactory.corp.example/...`) → two `PRIVATE_REGISTRY` warnings carrying only
  the host (`artifactory.corp.example`), never `svcuser`/`s3cr3tT0ken`.
- `nuget.config` `<packageSources>`: `api.nuget.org` (PUBLIC, no warning) + `nuget.corp.example` (PRIVATE →
  `PRIVATE_REGISTRY`, host only). The `<packageSourceCredentials>` (`svc-account` / `p@ssw0rd-TOPSECRET`) are
  NEVER read or emitted.

Everything is synthetic; the credentials are obviously fake and never leave the container.
