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

Every one of those is a real HTTP exchange against a real running Keycloak - not a mocked identity provider.

## Approach

- **`SecurityConfig`** wires `spring-boot-starter-oauth2-client`'s `oauth2Login()` with almost no custom code - the whole authorization-code dance (redirect construction, state/nonce generation and validation, PKCE where applicable, token exchange) is Spring Security's own, well-tested machinery.
- **`keycloak/realm-export.json`** defines a *confidential* client (`publicClient: false`, a real client secret, `standardFlowEnabled: true`) - a genuinely different client shape from `oidc-resource-server`'s public, password-grant client, because the Authorization Code flow is what confidential clients with a real login UI actually use.
- **`DashboardController`** exposes a public home page with a login link, a protected HTML dashboard, and a protected JSON endpoint - both reading the authenticated user's claims from the `OAuth2User` principal.
- **`OAuth2LoginFlowIT`** drives the entire flow above with a plain `java.net.http.HttpClient` (`Redirect.NEVER`, a `CookieManager` doing exactly what a browser's cookie jar does) against a real Testcontainers-launched Keycloak - manually following each redirect and extracting Keycloak's real login form action URL with a regex, rather than automating a real browser.

## Architecture decisions

**A real bug, found by actually running the flow, not by reading the code.** The first version of `DashboardController` declared `dashboard(OAuth2User principal, Model model)` with no annotation on the parameter. The whole flow up through the token exchange worked perfectly - but the protected page itself threw a 500: `IllegalStateException: No primary or single unique constructor found for interface org.springframework.security.oauth2.core.user.OAuth2User`. Spring MVC was trying to treat the `OAuth2User` parameter as a `@ModelAttribute` to construct from request parameters (since it had no dedicated resolver registered for it), and failed immediately because you can't instantiate an interface. The fix is `@AuthenticationPrincipal OAuth2User principal` - the annotation tells Spring MVC to resolve this argument from the `SecurityContext` via Spring Security's own argument resolver instead of trying (and failing) to data-bind it. This is exactly the kind of bug that a purely-code-reading review would very plausibly miss, since the code looks entirely reasonable without knowing this specific Spring MVC/Security interaction - only actually running the full login flow surfaced it.

**Redirects are followed manually, one at a time, instead of letting the HTTP client auto-follow them.** `HttpClient.Redirect.NEVER` plus reading the `Location` header explicitly at each step is what makes it possible to intercept step 2 (Keycloak's login page) and extract its real, dynamically-generated form action URL before continuing - an auto-following client would skip straight past the form to whatever the *next* redirect happened to be, with no chance to submit credentials in between.

**A `CookieManager` with `CookiePolicy.ACCEPT_ALL`, not a manually-threaded cookie header.** The flow genuinely needs two different cookie jars in spirit - the app's own session cookie (`JSESSIONID`) and Keycloak's own session cookie (`KEYCLOAK_IDENTITY`) - and a `CookieManager` handles per-host cookie scoping automatically and correctly, exactly like a real browser would, rather than requiring this test to manually track and re-attach the right cookie to the right request.

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

- [x] Working end to end — the full Authorization Code flow reproduced manually against a real Keycloak, request by request, including finding and fixing a real 500 error
- [x] A real bug (`@AuthenticationPrincipal` missing) found by actually running the flow, documented with its real root cause
- [x] README complete
- [ ] Demo/screenshot added
- [x] Pushed to GitHub

## Notes / next steps

- No logout flow implemented - a complete implementation would add Keycloak's RP-initiated logout (`/protocol/openid-connect/logout`) so the app's session end also ends the user's Keycloak SSO session, not just the local `JSESSIONID`.
- The client secret lives in `application.yml` as a plaintext demo value - a real deployment would pull it from a secrets manager or environment variable, the same pattern this portfolio already uses for `oidc-resource-server`'s Keycloak configuration.
- Pairs naturally with `oidc-resource-server`: a real system would likely have exactly this kind of browser-facing login application issuing tokens that OTHER, API-only services (shaped like `oidc-resource-server`) validate - the two projects together cover both halves of a complete OIDC deployment.
