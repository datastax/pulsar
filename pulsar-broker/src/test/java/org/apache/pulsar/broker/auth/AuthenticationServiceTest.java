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
package org.apache.pulsar.broker.auth;

import static org.apache.pulsar.broker.web.AuthenticationFilter.AuthenticatedDataAttributeName;
import static org.apache.pulsar.broker.web.AuthenticationFilter.AuthenticatedRoleAttributeName;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Set;

import javax.naming.AuthenticationException;
import javax.servlet.http.HttpServletRequest;

import javax.servlet.http.HttpServletResponse;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.authentication.AuthenticationDataHttps;
import org.apache.pulsar.broker.authentication.AuthenticationDataSource;
import org.apache.pulsar.broker.authentication.AuthenticationProvider;
import org.apache.pulsar.broker.authentication.AuthenticationService;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AuthenticationServiceTest {

    private static final String s_authentication_success = "authenticated";

    @Test(timeOut = 10000)
    public void testAuthenticationHttp() throws Exception {
        ServiceConfiguration config = new ServiceConfiguration();
        Set<String> providersClassNames = Sets.newHashSet(MockAuthenticationProvider.class.getName());
        config.setAuthenticationProviders(providersClassNames);
        config.setAuthenticationEnabled(true);
        AuthenticationService service = new AuthenticationService(config);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(request.getRemotePort()).thenReturn(8080);
        when(request.getHeader("X-Pulsar-Auth-Method-Name")).thenReturn("auth");
        String result = service.authenticateHttpRequest(request);
        assertEquals(result, s_authentication_success);
        service.close();
    }

    @Test(timeOut = 10000)
    public void testAuthenticationHttpWithMultipleProviders() throws Exception {
        ServiceConfiguration config = new ServiceConfiguration();
        Set<String> providersClassNames = Sets.newHashSet(MockAuthenticationProvider.class.getName(), MockAuthenticationProviderWithDifferentName.class.getName());
        config.setAuthenticationProviders(providersClassNames);
        config.setAuthenticationEnabled(true);
        AuthenticationService service = new AuthenticationService(config);
        HttpServletRequest requestDefaultAuthProvider = mock(HttpServletRequest.class);
        when(requestDefaultAuthProvider.getRemoteAddr()).thenReturn("192.168.1.1");
        when(requestDefaultAuthProvider.getRemotePort()).thenReturn(8080);
        when(requestDefaultAuthProvider.getHeader("X-Pulsar-Auth-Method-Name")).thenReturn("auth");
        String resultDefaultAuthProvider = service.authenticateHttpRequest(requestDefaultAuthProvider);
        assertEquals(resultDefaultAuthProvider, s_authentication_success);

        HttpServletRequest requestCustomAuthProvider = mock(HttpServletRequest.class);
        when(requestCustomAuthProvider.getRemoteAddr()).thenReturn("192.168.1.1");
        when(requestCustomAuthProvider.getRemotePort()).thenReturn(8080);
        when(requestCustomAuthProvider.getHeader("X-Pulsar-Auth-Method-Name")).thenReturn("customAuthProvider");
        String resultCustomAuthProvider = service.authenticateHttpRequest(requestCustomAuthProvider);
        assertEquals(resultCustomAuthProvider, s_authentication_success);

        HttpServletRequest requestUnsupportedAuthProvider = mock(HttpServletRequest.class);
        when(requestUnsupportedAuthProvider.getRemoteAddr()).thenReturn("192.168.1.1");
        when(requestUnsupportedAuthProvider.getRemotePort()).thenReturn(8080);
        when(requestUnsupportedAuthProvider.getHeader("X-Pulsar-Auth-Method-Name")).thenReturn("unsupportedAuthProvider");
        Assert.assertThrows(() -> service.authenticateHttpRequest(requestUnsupportedAuthProvider));

        service.close();
    }

    @Test(timeOut = 10000)
    public void testAuthenticationHttpRequestResponse() throws Exception {
        ServiceConfiguration config = new ServiceConfiguration();
        Set<String> providersClassNames = Sets.newHashSet(MockAuthenticationProvider.class.getName());
        config.setAuthenticationProviders(providersClassNames);
        config.setAuthenticationEnabled(true);
        AuthenticationService service = new AuthenticationService(config);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(request.getRemotePort()).thenReturn(8080);
        when(request.getHeader("X-Pulsar-Auth-Method-Name")).thenReturn("auth");
        boolean doFilter = service.authenticateHttpRequest(request, (HttpServletResponse) null);
        assertTrue(doFilter, "Authentication should have succeeded");
        verify(request).setAttribute(AuthenticatedRoleAttributeName, s_authentication_success);
        verify(request).setAttribute(eq(AuthenticatedDataAttributeName), any(AuthenticationDataHttps.class));
        service.close();
    }

    @Test(timeOut = 10000)
    public void testAuthenticationHttpRequestResponseWithMultipleProviders() throws Exception {
        boolean doFilter;
        ServiceConfiguration config = new ServiceConfiguration();
        Set<String> providersClassNames = Sets.newHashSet(MockAuthenticationProvider.class.getName(),
                MockAuthenticationProviderWithDifferentName.class.getName());
        config.setAuthenticationProviders(providersClassNames);
        config.setAuthenticationEnabled(true);
        AuthenticationService service = new AuthenticationService(config);
        HttpServletRequest requestDefaultAuthProvider = mock(HttpServletRequest.class);
        when(requestDefaultAuthProvider.getRemoteAddr()).thenReturn("192.168.1.1");
        when(requestDefaultAuthProvider.getRemotePort()).thenReturn(8080);
        when(requestDefaultAuthProvider.getHeader("X-Pulsar-Auth-Method-Name")).thenReturn("auth");
        doFilter = service.authenticateHttpRequest(requestDefaultAuthProvider, (HttpServletResponse) null);
        assertTrue(doFilter, "Authentication should have succeeded");
        verify(requestDefaultAuthProvider).setAttribute(AuthenticatedRoleAttributeName, s_authentication_success);

        HttpServletRequest requestCustomAuthProvider = mock(HttpServletRequest.class);
        when(requestCustomAuthProvider.getRemoteAddr()).thenReturn("192.168.1.1");
        when(requestCustomAuthProvider.getRemotePort()).thenReturn(8080);
        when(requestCustomAuthProvider.getHeader("X-Pulsar-Auth-Method-Name")).thenReturn("customAuthProvider");
        doFilter = service.authenticateHttpRequest(requestCustomAuthProvider, (HttpServletResponse) null);
        assertTrue(doFilter, "Authentication should have succeeded");
        verify(requestCustomAuthProvider).setAttribute(AuthenticatedRoleAttributeName, s_authentication_success);

        HttpServletRequest requestUnsupportedAuthProvider = mock(HttpServletRequest.class);
        when(requestUnsupportedAuthProvider.getRemoteAddr()).thenReturn("192.168.1.1");
        when(requestUnsupportedAuthProvider.getRemotePort()).thenReturn(8080);
        when(requestUnsupportedAuthProvider.getHeader("X-Pulsar-Auth-Method-Name")).thenReturn("unsupportedAuthProvider");
        Assert.assertThrows(() ->
                service.authenticateHttpRequest(requestUnsupportedAuthProvider, (HttpServletResponse) null));

        service.close();
    }

    @Test(timeOut = 10000)
    public void testAuthenticationHttpRequestResponseWithAnonymousRole() throws Exception {
        boolean doFilter;
        String anonRole = "anon";
        ServiceConfiguration config = new ServiceConfiguration();
        Set<String> providersClassNames = Sets.newHashSet(MockAuthenticationProviderAlwaysFail.class.getName());
        config.setAuthenticationProviders(providersClassNames);
        config.setAuthenticationEnabled(true);
        config.setAnonymousUserRole(anonRole);
        AuthenticationService service = new AuthenticationService(config);

        // No auth header specified
        HttpServletRequest requestCustomAuthProvider = mock(HttpServletRequest.class);
        when(requestCustomAuthProvider.getRemoteAddr()).thenReturn("192.168.1.1");
        when(requestCustomAuthProvider.getRemotePort()).thenReturn(8080);
        doFilter = service.authenticateHttpRequest(requestCustomAuthProvider, (HttpServletResponse) null);
        assertTrue(doFilter, "Authentication should have succeeded");
        verify(requestCustomAuthProvider).setAttribute(AuthenticatedRoleAttributeName, anonRole);

        service.close();
    }

    public static class MockAuthenticationProvider implements AuthenticationProvider {

        @Override
        public void close() throws IOException {
        }

        @Override
        public void initialize(ServiceConfiguration config) throws IOException {
        }

        @Override
        public String getAuthMethodName() {
            return "auth";
        }

        @Override
        public String authenticate(AuthenticationDataSource authData) throws AuthenticationException {
            return s_authentication_success;
        }
    }

    public static class MockAuthenticationProviderWithDifferentName implements AuthenticationProvider {

        @Override
        public void close() throws IOException {
        }

        @Override
        public void initialize(ServiceConfiguration config) throws IOException {
        }

        @Override
        public String getAuthMethodName() {
            return "customAuthProvider";
        }

        @Override
        public String authenticate(AuthenticationDataSource authData) throws AuthenticationException {
            return s_authentication_success;
        }
    }

    public static class MockAuthenticationProviderAlwaysFail implements AuthenticationProvider {

        @Override
        public void close() throws IOException {
        }

        @Override
        public void initialize(ServiceConfiguration config) throws IOException {
        }

        @Override
        public String getAuthMethodName() {
            return "auth";
        }

        @Override
        public String authenticate(AuthenticationDataSource authData) throws AuthenticationException {
            throw new AuthenticationException("I failed");
        }
    }
}
