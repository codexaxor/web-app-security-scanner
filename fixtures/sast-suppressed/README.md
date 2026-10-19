# fixtures/sast-suppressed

Proves D-037 for SAST: a CSRF-disabled finding that tries to hide itself via an inline
`// nosemgrep: spring-csrf-disabled` comment and a `.semgrepignore` excluding the file. SecScan runs Opengrep
with `--disable-nosem` and `--x-ignore-semgrepignore-files`, so both are ignored and `spring-csrf-disabled`
still fires. Used by `OpengrepConfigInjectionIT` (live-opengrep). **Not real code.**
