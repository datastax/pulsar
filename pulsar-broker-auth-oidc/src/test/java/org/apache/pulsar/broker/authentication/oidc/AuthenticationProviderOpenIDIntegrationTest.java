/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.authentication.oidc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.impl.DefaultJwtBuilder;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import javax.naming.AuthenticationException;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.authentication.AuthenticationDataCommand;
import org.apache.pulsar.broker.authentication.AuthenticationState;
import org.apache.pulsar.common.api.AuthData;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * An integration test relying on WireMock to simulate an OpenID Connect provider.
 */
public class AuthenticationProviderOpenIDIntegrationTest {

    AuthenticationProviderOpenID provider;
    PrivateKey privateKey;

    // These are the kid values for JWKs in the /keys endpoint
    String validJwk = "valid";
    String invalidJwk = "invalid";

    // The valid issuer
    String issuer;
    String issuerWithTrailingSlash;
    // This issuer is configured to return an issuer in the openid-configuration
    // that does not match the issuer on the token
    String issuerThatFails;
    String issuerK8s;
    WireMockServer server;

    @BeforeClass
    void beforeClass() throws IOException {

        // Port matches the port supplied in the fakeKubeConfig.yaml resource, which makes the k8s integration
        // tests work correctly.
        server = new WireMockServer(wireMockConfig().port(0));
        server.start();
        issuer = server.baseUrl();
        issuerWithTrailingSlash = issuer + "/trailing-slash/";
        issuerThatFails = issuer + "/fail";
        issuerK8s = issuer + "/k8s";

        // Set up a correct openid-configuration
        server.stubFor(
                get(urlEqualTo("/.well-known/openid-configuration"))
                        .willReturn(aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"issuer\": \"%s\",\"jwks_uri\": \"%s/keys\"}"
                                        .replace("%s", server.baseUrl()))));

        // Set up a correct openid-configuration that the k8s integration test can use
        // NOTE: integration tests revealed that the k8s client adds a trailing slash to the openid-configuration
        // endpoint.
        // NOTE: the jwks_uri is ignored, so we supply one that would fail here to ensure that we are not implicitly
        // relying on the jwks_uri.
        server.stubFor(
                get(urlEqualTo("/k8s/.well-known/openid-configuration/"))
                        .willReturn(aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody(String.format("{\"issuer\": \"%s\",\"jwks_uri\": \"%s/no/keys/hosted/here\"}",
                                        issuer, issuer))));

        // Set up a correct openid-configuration that has a trailing slash in the issuers URL. This is a
        // behavior observed by Auth0. In this case, the token's iss claim also has a trailing slash.
        // The server should normalize the URL and call the Authorization Server without the double slash.
        // NOTE: the spec does not indicate that the jwks_uri must have the same prefix as the issuer, and that
        // is used here to simplify the testing.
        server.stubFor(
                get(urlEqualTo("/trailing-slash/.well-known/openid-configuration"))
                        .willReturn(aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody(String.format("{\"issuer\": \"%s\",\"jwks_uri\": \"%s/keys\"}",
                                        issuerWithTrailingSlash, issuer))));

        // Set up an incorrect openid-configuration where issuer does not match
        server.stubFor(
                get(urlEqualTo("/fail/.well-known/openid-configuration"))
                        .willReturn(aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody(String.format("{\"issuer\": \"https://wrong-issuer.com\",\"jwks_uri\": "
                                        + "\"%s/keys\"}", server.baseUrl()))));

        // Create the token key pair
        KeyPair keyPair = Keys.keyPairFor(SignatureAlgorithm.RS256);
        privateKey = keyPair.getPrivate();
        RSAPublicKey rsaPublicKey = (RSAPublicKey) keyPair.getPublic();
        String n = Base64.getUrlEncoder().encodeToString(rsaPublicKey.getModulus().toByteArray());
        String e = Base64.getUrlEncoder().encodeToString(rsaPublicKey.getPublicExponent().toByteArray());

        // Set up JWKS endpoint with a valid and an invalid public key
        // The url matches are for both the normal and the k8s endpoints
        server.stubFor(
                get(urlMatching( "/keys|/k8s/openid/v1/jwks/"))
                        .willReturn(aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody(String.format("{\"keys\":[{\"kid\":\"%s\",\"kty\":\"RSA\",\"alg\":\"RS256\","
                                        + "\"n\":\"%s\",\"e\":\"%s\"},{\"kid\":\"%s\",\"kty\":\"RSA\",\"n\":"
                                        + "\"invalid-key\",\"e\":\"AQAB\"}]}", validJwk, n, e, invalidJwk))));

        ServiceConfiguration conf = new ServiceConfiguration();
        conf.setAuthenticationEnabled(true);
        conf.setAuthenticationProviders(Collections.singleton(AuthenticationProviderOpenID.class.getName()));
        Properties props = conf.getProperties();
        props.setProperty(AuthenticationProviderOpenID.REQUIRE_HTTPS, "false");
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_AUDIENCES, "allowed-audience");
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_TOKEN_ISSUERS, issuer + "," + issuerWithTrailingSlash
                + "," + issuerThatFails);

        // Create the fake kube config file. This file is configured via the env vars and is written to the
        // target directory so maven clean will remove it.
        byte[] template = Files.readAllBytes(
                FileSystems.getDefault().getPath(System.getenv("KUBECONFIG_TEMPLATE")));
        String kubeConfig = new String(template).replace("${WIRE_MOCK_PORT}", String.valueOf(server.port()));
        Files.write(FileSystems.getDefault().getPath(System.getenv("KUBECONFIG")), kubeConfig.getBytes());

        provider = new AuthenticationProviderOpenID();
        provider.initialize(conf);
    }

    @AfterClass
    void afterClass() {
        server.stop();
    }

    @Test
    public void testTokenWithValidJWK() throws Exception {
        String role = "superuser";
        String token = generateToken(validJwk, issuer, role, "allowed-audience", 0L, 0L, 10000L);
        assertEquals(role, provider.authenticateAsync(new AuthenticationDataCommand(token)).get());
    }

    @Test
    public void testTokenWithTrailingSlashAndValidJWK() throws Exception {
        String role = "superuser";
        String token = generateToken(validJwk, issuer + "/trailing-slash/", role, "allowed-audience", 0L, 0L, 10000L);
        assertEquals(role, provider.authenticateAsync(new AuthenticationDataCommand(token)).get());
    }

    @Test
    public void testTokenWithInvalidJWK() throws Exception {
        String role = "superuser";
        String token = generateToken(invalidJwk, issuer, role, "allowed-audience",0L, 0L, 10000L);
        try {
            provider.authenticateAsync(new AuthenticationDataCommand(token)).get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AuthenticationException, "Found exception: " + e.getCause());
        }
    }

    @Test
    public void testAuthorizationServerReturnsIncorrectIssuerInOpenidConnectConfiguration() throws Exception {
        String role = "superuser";
        String token = generateToken(validJwk, issuerThatFails, role, "allowed-audience", 0L, 0L, 10000L);
        try {
            provider.authenticateAsync(new AuthenticationDataCommand(token)).get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AuthenticationException, "Found exception: " + e.getCause());
        }
    }

    @Test
    public void testTokenWithInvalidAudience() throws Exception {
        String role = "superuser";
        String token = generateToken(validJwk, issuer, role, "invalid-audience", 0L, 0L, 10000L);
        try {
            provider.authenticateAsync(new AuthenticationDataCommand(token)).get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AuthenticationException, "Found exception: " + e.getCause());
        }
    }

    @Test
    public void testTokenWithInvalidIssuer() throws Exception {
        String role = "superuser";
        String token = generateToken(validJwk, "https://not-an-allowed-issuer.com", role, "allowed-audience", 0L, 0L, 10000L);
        try {
            provider.authenticateAsync(new AuthenticationDataCommand(token)).get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AuthenticationException, "Found exception: " + e.getCause());
        }
    }

    @Test
    public void testKubernetesApiServerAsDiscoverTrustedIssuerSuccess() throws Exception {
        ServiceConfiguration conf = new ServiceConfiguration();
        conf.setAuthenticationEnabled(true);
        conf.setAuthenticationProviders(Collections.singleton(AuthenticationProviderOpenID.class.getName()));
        Properties props = conf.getProperties();
        props.setProperty(AuthenticationProviderOpenID.REQUIRE_HTTPS, "false");
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_AUDIENCES, "allowed-audience");
        props.setProperty(AuthenticationProviderOpenID.FALLBACK_DISCOVERY_MODE, "KUBERNETES_DISCOVER_TRUSTED_ISSUER");
        // Test requires that k8sIssuer is not in the allowed token issuers
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_TOKEN_ISSUERS, "");

        AuthenticationProviderOpenID provider = new AuthenticationProviderOpenID();
        provider.initialize(conf);

        String role = "superuser";
        // We use the normal issuer on the token because the /k8s endpoint is configured via the kube config file
        // made as part of the test setup. The kube client then gets the issuer from the /k8s endpoint and discovers
        // this issuer.
        String token = generateToken(validJwk, issuer, role, "allowed-audience", 0L, 0L, 10000L);
        assertEquals(role, provider.authenticateAsync(new AuthenticationDataCommand(token)).get());

        // Ensure that a subsequent token with a different issuer still fails due to invalid issuer exception
        String token2 = generateToken(validJwk, "http://not-the-k8s-issuer", role, "allowed-audience", 0L, 0L, 10000L);
        try {
            provider.authenticateAsync(new AuthenticationDataCommand(token2)).get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AuthenticationException, "Found exception: " + e.getCause());
            assertTrue(e.getCause().getMessage().contains("Issuer not allowed"),
                    "Unexpected error message: " + e.getMessage());
        }
    }

    @Test
    public void testKubernetesApiServerAsDiscoverTrustedIssuerFailsDueToMismatchedIssuerClaim() throws Exception {
        ServiceConfiguration conf = new ServiceConfiguration();
        conf.setAuthenticationEnabled(true);
        conf.setAuthenticationProviders(Collections.singleton(AuthenticationProviderOpenID.class.getName()));
        Properties props = conf.getProperties();
        props.setProperty(AuthenticationProviderOpenID.REQUIRE_HTTPS, "false");
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_AUDIENCES, "allowed-audience");
        props.setProperty(AuthenticationProviderOpenID.FALLBACK_DISCOVERY_MODE, "KUBERNETES_DISCOVER_TRUSTED_ISSUER");
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_TOKEN_ISSUERS, "");

        AuthenticationProviderOpenID provider = new AuthenticationProviderOpenID();
        provider.initialize(conf);

        String role = "superuser";
        String token = generateToken(validJwk, "http://not-the-k8s-issuer", role, "allowed-audience", 0L, 0L, 10000L);
        try {
            provider.authenticateAsync(new AuthenticationDataCommand(token)).get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AuthenticationException, "Found exception: " + e.getCause());
        }
    }


    @Test
    public void testKubernetesApiServerAsDiscoverPublicKeySuccess() throws Exception {
        ServiceConfiguration conf = new ServiceConfiguration();
        conf.setAuthenticationEnabled(true);
        conf.setAuthenticationProviders(Collections.singleton(AuthenticationProviderOpenID.class.getName()));
        Properties props = conf.getProperties();
        props.setProperty(AuthenticationProviderOpenID.REQUIRE_HTTPS, "false");
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_AUDIENCES, "allowed-audience");
        props.setProperty(AuthenticationProviderOpenID.FALLBACK_DISCOVERY_MODE, "KUBERNETES_DISCOVER_PUBLIC_KEYS");
        // Test requires that k8sIssuer is not in the allowed token issuers
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_TOKEN_ISSUERS, "");

        AuthenticationProviderOpenID provider = new AuthenticationProviderOpenID();
        provider.initialize(conf);

        String role = "superuser";
        String token = generateToken(validJwk, issuer, role, "allowed-audience", 0L, 0L, 10000L);
        assertEquals(role, provider.authenticateAsync(new AuthenticationDataCommand(token)).get());

        // Ensure that a subsequent token with a different issuer still fails due to invalid issuer exception
        String token2 = generateToken(validJwk, "http://not-the-k8s-issuer", role, "allowed-audience", 0L, 0L, 10000L);
        try {
            provider.authenticateAsync(new AuthenticationDataCommand(token2)).get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AuthenticationException, "Found exception: " + e.getCause());
            assertTrue(e.getCause().getMessage().contains("Issuer not allowed"),
                    "Unexpected error message: " + e.getMessage());
        }
    }

    @Test
    public void testKubernetesApiServerAsDiscoverPublicKeyFailsDueToMismatchedIssuerClaim() throws Exception {
        ServiceConfiguration conf = new ServiceConfiguration();
        conf.setAuthenticationEnabled(true);
        conf.setAuthenticationProviders(Collections.singleton(AuthenticationProviderOpenID.class.getName()));
        Properties props = conf.getProperties();
        props.setProperty(AuthenticationProviderOpenID.REQUIRE_HTTPS, "false");
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_AUDIENCES, "allowed-audience");
        props.setProperty(AuthenticationProviderOpenID.FALLBACK_DISCOVERY_MODE, "KUBERNETES_DISCOVER_PUBLIC_KEYS");
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_TOKEN_ISSUERS, "");

        AuthenticationProviderOpenID provider = new AuthenticationProviderOpenID();
        provider.initialize(conf);

        String role = "superuser";
        String token = generateToken(validJwk, "http://not-the-k8s-issuer", role, "allowed-audience", 0L, 0L, 10000L);
        try {
            provider.authenticateAsync(new AuthenticationDataCommand(token)).get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AuthenticationException, "Found exception: " + e.getCause());
        }
    }

    @Test
    public void testAuthenticationStateOpenIDForValidToken() throws Exception {
        String role = "superuser";
        String token = generateToken(validJwk, issuer, role, "allowed-audience", 0L, 0L, 10000L);
        AuthenticationState state = provider.newAuthState(null, null, null);
        AuthData result = state.authenticateAsync(AuthData.of(token.getBytes())).get();
        assertNull(result);
        assertEquals(state.getAuthRole(), role);
        assertEquals(state.getAuthDataSource().getCommandData(), token);
        assertFalse(state.isExpired());
    }

    @Test
    public void testAuthenticationStateOpenIDForExpiredToken() throws Exception {
        String role = "superuser";
        String token = generateToken(validJwk, issuer, role, "allowed-audience", 0L, 0L, -10000L);
        AuthenticationState state = provider.newAuthState(null, null, null);
        try {
            state.authenticateAsync(AuthData.of(token.getBytes())).get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AuthenticationException, "Found exception: " + e.getCause());
        }
    }

    @Test
    public void testAuthenticationStateOpenIDForValidTokenWithNoExp() throws Exception {
        String role = "superuser";
        String token = generateToken(validJwk, issuer, role, "allowed-audience", 0L, 0L, null);
        AuthenticationState state = provider.newAuthState(null, null, null);
        try {
            state.authenticateAsync(AuthData.of(token.getBytes())).get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AuthenticationException, "Found exception: " + e.getCause());
        }
    }

    @Test
    public void testAuthenticationStateOpenIDForTokenExpiration() throws Exception {
        ServiceConfiguration conf = new ServiceConfiguration();
        conf.setAuthenticationEnabled(true);
        conf.setAuthenticationProviders(Collections.singleton(AuthenticationProviderOpenID.class.getName()));
        Properties props = conf.getProperties();
        props.setProperty(AuthenticationProviderOpenID.REQUIRE_HTTPS, "false");
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_AUDIENCES, "allowed-audience");
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_TOKEN_ISSUERS, issuer);
        // Use the leeway to allow the token to pass validation and then fail expiration
        props.setProperty(AuthenticationProviderOpenID.ACCEPTED_TIME_LEEWAY_SECONDS, "10");
        AuthenticationProviderOpenID provider = new AuthenticationProviderOpenID();
        provider.initialize(conf);

        String role = "superuser";
        String token = generateToken(validJwk, issuer, role, "allowed-audience", 0L, 0L, 0L);
        AuthenticationState state = provider.newAuthState(null, null, null);
        AuthData result = state.authenticateAsync(AuthData.of(token.getBytes())).get();
        assertNull(result);
        assertEquals(state.getAuthRole(), role);
        assertEquals(state.getAuthDataSource().getCommandData(), token);
        assertTrue(state.isExpired());
    }

    @Test
    void ensureRoleClaimForNonSubClaimReturnsRole() throws Exception {
        AuthenticationProviderOpenID provider = new AuthenticationProviderOpenID();
        Properties props = new Properties();
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_TOKEN_ISSUERS, issuer);
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_AUDIENCES, "allowed-audience");
        props.setProperty(AuthenticationProviderOpenID.ROLE_CLAIM, "test");
        props.setProperty(AuthenticationProviderOpenID.REQUIRE_HTTPS, "false");
        ServiceConfiguration config = new ServiceConfiguration();
        config.setProperties(props);
        provider.initialize(config);

        // Build a JWT with a custom claim
        HashMap<String, Object> claims = new HashMap();
        claims.put("test", "my-role");
        String token = generateToken(validJwk, issuer, "not-my-role", "allowed-audience", 0L,
                0L, 10000L, claims);
        assertEquals(provider.authenticateAsync(new AuthenticationDataCommand(token)).get(), "my-role");
    }

    @Test
    void ensureRoleClaimForNonSubClaimFailsWhenClaimIsMissing() throws Exception {
        AuthenticationProviderOpenID provider = new AuthenticationProviderOpenID();
        Properties props = new Properties();
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_TOKEN_ISSUERS, issuer);
        props.setProperty(AuthenticationProviderOpenID.ALLOWED_AUDIENCES, "allowed-audience");
        props.setProperty(AuthenticationProviderOpenID.ROLE_CLAIM, "test");
        props.setProperty(AuthenticationProviderOpenID.REQUIRE_HTTPS, "false");
        ServiceConfiguration config = new ServiceConfiguration();
        config.setProperties(props);
        provider.initialize(config);

        // Build a JWT without the "test" claim, which should cause the authentication to fail
        String token = generateToken(validJwk, issuer, "not-my-role", "allowed-audience", 0L,
                0L, 10000L);
        try {
            provider.authenticateAsync(new AuthenticationDataCommand(token)).get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof AuthenticationException, "Found exception: " + e.getCause());
        }
    }

    // This test is somewhat counterintuitive. We allow the state object to change roles, but then we fail it
    // in the ServerCnx handling of the state object. As such, it is essential that the state object allow
    // the role to change.
    @Test
    public void testAuthenticationStateOpenIDAllowsRoleChange() throws Exception {
        String role1 = "superuser";
        String token1 = generateToken(validJwk, issuer, role1, "allowed-audience", 0L, 0L, 10000L);
        String role2 = "otheruser";
        String token2 = generateToken(validJwk, issuer, role2, "allowed-audience", 0L, 0L, 10000L);
        AuthenticationState state = provider.newAuthState(null, null, null);
        AuthData result1 = state.authenticateAsync(AuthData.of(token1.getBytes())).get();
        assertNull(result1);
        assertEquals(state.getAuthRole(), role1);
        assertEquals(state.getAuthDataSource().getCommandData(), token1);
        assertFalse(state.isExpired());

        AuthData result2 = state.authenticateAsync(AuthData.of(token2.getBytes())).get();
        assertNull(result2);
        assertEquals(state.getAuthRole(), role2);
        assertEquals(state.getAuthDataSource().getCommandData(), token2);
        assertFalse(state.isExpired());
    }

    private String generateToken(String kid, String issuer, String subject, String audience,
                                 Long iatOffset, Long nbfOffset, Long expOffset) {
        return generateToken(kid, issuer, subject, audience, iatOffset, nbfOffset, expOffset, new HashMap<>());
    }

    private String generateToken(String kid, String issuer, String subject, String audience,
                                 Long iatOffset, Long nbfOffset, Long expOffset, HashMap<String, Object> extraClaims) {
        long now = System.currentTimeMillis();
        DefaultJwtBuilder defaultJwtBuilder = new DefaultJwtBuilder();
        defaultJwtBuilder.setHeaderParam("kid", kid);
        defaultJwtBuilder.setHeaderParam("typ", "JWT");
        defaultJwtBuilder.setHeaderParam("alg", "RS256");
        defaultJwtBuilder.setIssuer(issuer);
        defaultJwtBuilder.setSubject(subject);
        defaultJwtBuilder.setAudience(audience);
        defaultJwtBuilder.setIssuedAt(iatOffset != null ? new Date(now + iatOffset) : null);
        defaultJwtBuilder.setNotBefore(nbfOffset != null ? new Date(now + nbfOffset) : null);
        defaultJwtBuilder.setExpiration(expOffset != null ? new Date(now + expOffset) : null);
        defaultJwtBuilder.addClaims(extraClaims);
        defaultJwtBuilder.signWith(privateKey);
        return defaultJwtBuilder.compact();
    }

}
