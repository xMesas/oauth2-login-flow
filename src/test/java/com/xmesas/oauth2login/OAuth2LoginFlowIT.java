package com.xmesas.oauth2login;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real OAuth2 Authorization Code flow with no browser and no Selenium: a plain
 * java.net.http.HttpClient with redirects disabled (followed manually, one at a time) and a
 * CookieManager tracking session state exactly like a browser would - against a real
 * Keycloak (Testcontainers), submitting a real username/password to Keycloak's own,
 * dynamically-generated login form HTML, and following the real authorization code all the
 * way back to the app's protected dashboard.
 */
// A FIXED port (matching application.yml's server.port: 8080), not RANDOM_PORT: the
// Keycloak realm's registered redirect URI is statically "http://localhost:8080/login/
// oauth2/code/*" (redirect URIs are part of the client's security configuration, not
// something dynamically discovered at request time) - a random port here would make
// Spring Security construct a redirect_uri that doesn't match what's registered, and
// Keycloak correctly rejects the authorization request with 400. Found by CI actually
// running this test for the first time (Testcontainers can't run in this project's local
// Windows sandbox - see the README) - manual validation against the packaged jar always
// used the app's real, fixed port 8080, so it never hit this mismatch.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Testcontainers
class OAuth2LoginFlowIT {

    @Container
    static GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
            .withExposedPorts(8080)
            .withEnv("KEYCLOAK_ADMIN", "admin")
            .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("realm-export.json"),
                    "/opt/keycloak/data/import/realm-export.json")
            .withCommand("start-dev", "--import-realm")
            .waitingFor(Wait.forHttp("/realms/demo/.well-known/openid-configuration").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void oidcProps(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.keycloak.issuer-uri",
                () -> "http://%s:%d/realms/demo".formatted(keycloak.getHost(), keycloak.getMappedPort(8080)));
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    void fullAuthorizationCodeLoginFlowReachesTheProtectedDashboard() throws Exception {
        String appBase = "http://localhost:8080";

        // Step 1: app initiates login -> redirects to Keycloak's real authorization endpoint
        HttpResponse<Void> step1 = get(appBase + "/oauth2/authorization/keycloak");
        assertThat(step1.statusCode()).isEqualTo(302);
        String authorizeUrl = location(step1);
        assertThat(authorizeUrl).contains("/realms/demo/protocol/openid-connect/auth");

        // Step 2: fetch Keycloak's real, dynamically-generated login form
        HttpResponse<String> loginPage = getBody(authorizeUrl);
        assertThat(loginPage.statusCode()).isEqualTo(200);
        String formAction = extractFormAction(loginPage.body());
        System.out.println("DEBUG formAction=" + formAction);

        // Step 3: submit real credentials to that real form action URL
        HttpResponse<String> loginSubmit = postFormDebug(formAction, "username=demo-user&password=demo-pw");
        System.out.println("DEBUG loginSubmit.status=" + loginSubmit.statusCode());
        System.out.println("DEBUG loginSubmit.body=" + loginSubmit.body());
        assertThat(loginSubmit.statusCode()).isEqualTo(302);
        String callbackUrl = location(loginSubmit);
        assertThat(callbackUrl).contains("code=");

        // Step 4: the app exchanges the real authorization code for tokens server-side and
        // establishes an authenticated session
        HttpResponse<Void> callback = get(callbackUrl);
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(location(callback)).isEqualTo(appBase + "/dashboard");

        // Step 5: the now-authenticated session reaches the protected HTML dashboard
        HttpResponse<String> dashboard = getBody(appBase + "/dashboard");
        assertThat(dashboard.statusCode()).isEqualTo(200);
        assertThat(dashboard.body()).contains("Welcome").contains("demo-user");

        // Step 6: the same session against the JSON endpoint
        HttpResponse<String> me = getBody(appBase + "/api/me");
        assertThat(me.body()).contains("\"username\":\"demo-user\"").contains("demo-user@example.com");
    }

    @Test
    void theProtectedDashboardRedirectsToLoginWithNoSession() throws Exception {
        HttpClient freshClient = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        HttpResponse<Void> response = freshClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:8080/dashboard")).GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(location(response)).contains("/oauth2/authorization/keycloak");
    }

    private HttpResponse<Void> get(String url) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private HttpResponse<String> getBody(String url) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<Void> postForm(String url, String formBody) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private HttpResponse<String> postFormDebug(String url, String formBody) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String location(HttpResponse<?> response) {
        return response.headers().firstValue("Location")
                .orElseThrow(() -> new AssertionError("No Location header in response: " + response));
    }

    private String extractFormAction(String html) {
        Matcher matcher = Pattern.compile("action=\"([^\"]*)\"").matcher(html);
        if (!matcher.find()) {
            throw new AssertionError("No form action found in Keycloak login page");
        }
        return matcher.group(1).replace("&amp;", "&");
    }
}
