# oauth2-login-flow

[![CI](https://github.com/xMesas/oauth2-login-flow/actions/workflows/ci.yml/badge.svg)](https://github.com/xMesas/oauth2-login-flow/actions/workflows/ci.yml)

The OAuth2 Authorization Code flow, driven completely - real redirect to a real Keycloak, a real dynamically-generated login form, real credentials submitted, a real authorization code exchanged for tokens server-side, and a real authenticated session reaching a protected page. No Selenium, no headless browser: a plain HTTP client with redirects disabled and manually followed one at a time, exactly the sequence a browser actually performs.

## In plain English

This portfolio's `oidc-resource-server` validates JWTs a client already has - it never actually logs anyone in. This project is the missing other half: what happens when a user clicks "Log in," gets redirected to an identity provider's real login page, types a password, and ends up back at the application, authenticated. That whole sequence is usually tested with a browser-automation tool, but it's really just a specific series of HTTP redirects, one real HTML form, and a session cookie - reproducible with nothing more than `java.net.http.HttpClient` if the redirects are followed by hand instead of automatically.

## What actually got measured

The full flow, run against a real Keycloak (`docker compose up`), captured request by request:

```
1. GET  /oauth2/authorization/keycloak
   -> 302, Location: http://localhost:8081/realms/demo/protocol/openid-connect/auth?
      response_type=code&client_id=login-client&scope=openid+profile+email&state=...&
      redirect_uri=http://localhost:8080/login/oauth2/code/keycloak&nonce=...

2. GET  <that Keycloak authorize URL>
   -> 200, a real HTML login form with a dynamically-generated action URL
      (session_code=...&execution=...&client_id=login-client&tab_id=...)

3. POST username=demo-user&password=demo-pw  to that real form action URL
   -> 302, Location: http://localhost:8080/login/oauth2/code/keycloak?
      state=...&code=317eea6c-6ecf-4b3a-...   (a real authorization code)

4. GET  <that callback URL>
   -> the app exchanges the code for tokens server-side -> 302 to /dashboard

5. GET  /dashboard          -> 200, "Welcome, demo-user"
6. GET  /api/me             -> {"username":"demo-user","email":"demo-user@example.com",...}
```

Every one of those is a real HTTP exchange against a real running Keycloak - not a mocked identity provider. The same flow, automated (`OAuth2LoginFlowIT`, Testcontainers), passed for real in CI: `Tests run: 2, Failures: 0` - genuinely confirmed by grepping the run's log, not just trusting the checkmark (see the CI-integrity gotcha in `oidc-resource-server`'s README for why that check matters).

## Approach

- **`SecurityConfig`** wires `spring-boot-starter-oauth2-client`'s `oauth2Login()` with almost no custom code - the whole authorization-code dance (redirect construction, state/nonce generation and validation, PKCE where applicable, token exchange) is Spring Security's own, well-tested machinery.
- **`keycloak/realm-export.json`** defines a *confidential* client (`publicClient: false`, a real client secret, `standardFlowEnabled: true`) - a genuinely different client shape from `oidc-resource-server`'s public, password-grant client, because the Authorization Code flow is what confidential clients with a real login UI actually use.
- **`DashboardController`** exposes a public home page with a login link, a protected HTML dashboard, and a protected JSON endpoint - both reading the authenticated user's claims from the `OAuth2User` principal.
- **`OAuth2LoginFlowIT`** drives the entire flow above with a plain `java.net.http.HttpClient` (`Redirect.NEVER`, a `CookieManager` doing exactly what a browser's cookie jar does) against a real Testcontainers-launched Keycloak - manually following each redirect and extracting Keycloak's real login form action URL with a regex, rather than automating a real browser.

## Architecture decisions

**A real bug, found by actually running the flow, not by reading the code.** The first version of `DashboardController` declared `dashboard(OAuth2User principal, Model model)` with no annotation on the parameter. The whole flow up through the token exchange worked perfectly - but the protected page itself threw a 500: `IllegalStateException: No primary or single unique constructor found for interface org.springframework.security.oauth2.core.user.OAuth2User`. Spring MVC was trying to treat the `OAuth2User` parameter as a `@ModelAttribute` to construct from request parameters (since it had no dedicated resolver registered for it), and failed immediately because you can't instantiate an interface. The fix is `@AuthenticationPrincipal OAuth2User principal` - the annotation tells Spring MVC to resolve this argument from the `SecurityContext` via Spring Security's own argument resolver instead of trying (and failing) to data-bind it. This is exactly the kind of bug that a purely-code-reading review would very plausibly miss, since the code looks entirely reasonable without knowing this specific Spring MVC/Security interaction - only actually running the full login flow surfaced it.

**Redirects are followed manually, one at a time, instead of letting the HTTP client auto-follow them.** `HttpClient.Redirect.NEVER` plus reading the `Location` header explicitly at each step is what makes it possible to intercept step 2 (Keycloak's login page) and extract its real, dynamically-generated form action URL before continuing - an auto-following client would skip straight past the form to whatever the *next* redirect happened to be, with no chance to submit credentials in between.

**A second and third real bug, both found only once CI actually ran the automated test against a real Testcontainers Keycloak** (this local Windows sandbox can't run Testcontainers at all - see the note below - so `OAuth2LoginFlowIT` was never exercised locally before its first push):

1. **The test initially used `RANDOM_PORT`, but the Keycloak realm's registered redirect URI is a static `http://localhost:8080/login/oauth2/code/*`.** A redirect URI is part of a client's registered security configuration, not something negotiated per-request - a random app port made Spring Security construct a `redirect_uri` Keycloak had never seen, and Keycloak correctly rejected the authorization request with `400`. Manual validation never hit this, because it always ran the packaged jar on its real, fixed port 8080. Fixed by switching to `webEnvironment = DEFINED_PORT`.
2. **`java.net.CookieManager` refused to resend Keycloak's own session cookie.** After fixing the port, the flow failed one step later with another `400` and a genuinely helpful Keycloak error page: *"Cookie not found. Please make sure cookies are enabled in your browser."* Keycloak marks its session cookie `Secure` even inside this local, plaintext-HTTP dev realm - and `CookieManager`, correctly per RFC 6265, refuses to send a `Secure` cookie back over a plain `http://` connection. Real browsers (and `curl`, which is how this flow was first validated manually) are more lenient specifically for `localhost`; the JDK's own cookie manager is not. The fix is a small hand-rolled cookie jar (`captureCookies`/`cookieHeader` in the test) that tracks and resends every cookie regardless of its `Secure` flag - correct here specifically because this is a controlled test against a known-safe local Keycloak, not a general-purpose recommendation to ignore the `Secure` flag.

Both were only discoverable by watching the real CI run fail with a real, specific error and reading the real response body - not by inspecting the test code, which looked entirely reasonable on its own.

## Stack

`Java 21` · `Spring Boot 3.4` · `Spring Security` (OAuth2 Client, Authorization Code flow) · `Keycloak 26` · `Thymeleaf` · `Maven` · `JUnit 5` / `AssertJ` / `Testcontainers` / plain `java.net.http.HttpClient` (no Selenium)

## Running it

**Start Keycloak**

```powershell
docker compose up -d
```

**Build and run**

```powershell
.\mvnw.cmd -DskipTests package
java -jar target\oauth2-login-flow.jar
```

**Log in** - open `http://localhost:8080/` in a real browser and click "Log in with Keycloak" (`demo-user` / `demo-pw`), or reproduce the whole flow with `curl` and a cookie jar file (`-c`/`-b cookies.txt`) following the request sequence in "What actually got measured" above.

**Tests**

```powershell
.\mvnw.cmd test -Dtest=OAuth2LoginFlowIT
```

Real Keycloak via Testcontainers, no manual setup - note the explicit `-Dtest=` flag (this project's test class follows the `*IT` naming convention; see the Architecture decisions in `oidc-resource-server`'s README for the CI bug this exact gap caused there). This local Windows sandbox has a documented Testcontainers/Docker-Desktop npipe incompatibility (affects every Testcontainers-based project in this portfolio) - validated manually against a real `docker compose`-launched Keycloak instead (the exact request sequence in "What actually got measured" above), with GitHub Actions running the real Testcontainers proof.

## Status

- [x] Working end to end — the full Authorization Code flow reproduced manually against a real Keycloak, request by request, AND genuinely verified passing in CI (`Tests run: 2, Failures: 0`, grepped from the real log)
- [x] Three real bugs (`@AuthenticationPrincipal` missing, `RANDOM_PORT` vs a statically-registered redirect URI, `CookieManager` refusing a `Secure` cookie over plain HTTP) found by actually running the flow, each documented with its real root cause
- [x] README complete
- [ ] Demo/screenshot added
- [x] Pushed to GitHub

## Notes / next steps

- No logout flow implemented - a complete implementation would add Keycloak's RP-initiated logout (`/protocol/openid-connect/logout`) so the app's session end also ends the user's Keycloak SSO session, not just the local `JSESSIONID`.
- The client secret lives in `application.yml` as a plaintext demo value - a real deployment would pull it from a secrets manager or environment variable, the same pattern this portfolio already uses for `oidc-resource-server`'s Keycloak configuration.
- Pairs naturally with `oidc-resource-server`: a real system would likely have exactly this kind of browser-facing login application issuing tokens that OTHER, API-only services (shaped like `oidc-resource-server`) validate - the two projects together cover both halves of a complete OIDC deployment.
