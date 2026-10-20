# fixtures/sast-custom

Synthetic sources exercising the 7 custom Spring/.NET Opengrep rules (M6, D-053). Each rule has a `positive/`
file that must fire it and a `negative/` counterpart that must stay silent. **Not real code.**

| Rule (`opengrep-rules.custom.<id>`) | positive | negative |
| --- | --- | --- |
| spring-csrf-disabled | positive/SecurityConfig.java | negative/SecurityConfigSafe.java |
| spring-permitall-anyrequest | positive/SecurityConfig.java | negative/SecurityConfigSafe.java |
| spring-cross-origin-wildcard | positive/SecurityConfig.java | negative/SecurityConfigSafe.java |
| spring-spel-injection | positive/Eval.java | negative/EvalSafe.java |
| spring-actuator-exposed | positive/application.properties | negative/application-prod.properties |
| hardcoded-connection-string | positive/appsettings.json | negative/appsettings.Development.json |
| aspnet-allowanonymous-admin | positive/AdminController.cs | negative/HomeController.cs |

Used by `CustomSastRulesIT` (live-opengrep).
