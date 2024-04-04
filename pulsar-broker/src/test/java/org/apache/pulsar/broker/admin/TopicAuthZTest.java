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

package org.apache.pulsar.broker.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.Cleanup;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.broker.authentication.AuthenticationProviderToken;
import org.apache.pulsar.broker.authentication.utils.AuthTokenUtils;
import org.apache.pulsar.broker.authorization.PulsarAuthorizationProvider;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.impl.auth.AuthenticationToken;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.testng.Assert;
import org.testng.annotations.*;

import javax.crypto.SecretKey;
import java.util.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Test(groups = "broker-admin")
public class TopicAuthZTest extends MockedPulsarServiceBaseTest {

    private PulsarAdmin superUserAdmin;
    private PulsarAdmin tenantManagerAdmin;
    @Getter
    private PulsarService pulsarService;
    private PulsarAdmin serviceInternalAdmin;

    private static final SecretKey SECRET_KEY = AuthTokenUtils.createSecretKey(SignatureAlgorithm.HS256);
    private static final String TENANT_ADMIN_SUBJECT =  UUID.randomUUID().toString();
    private static final String TENANT_ADMIN_TOKEN = Jwts.builder()
            .claim("sub", TENANT_ADMIN_SUBJECT).signWith(SECRET_KEY).compact();

    protected static final String SUPER_USER_SUBJECT = "super-user";

    protected static final String SUPER_USER_TOKEN = Jwts.builder()
                .claim("sub", SUPER_USER_SUBJECT).signWith(SECRET_KEY).compact();
    private static final String BROKER_INTERNAL_CLIENT_SUBJECT = "broker_internal";
    private static final String BROKER_INTERNAL_CLIENT_TOKEN = Jwts.builder()
                .claim("sub", BROKER_INTERNAL_CLIENT_SUBJECT).signWith(SECRET_KEY).compact();

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_TENANT = "public";
    private static final String DEFAULT_NAMESPACE = "public/default";
    private static final String TEST_CLUSTER_NAME = "test-standalone";

    {
        conf.setClusterName(TEST_CLUSTER_NAME);
        conf.setBrokerShutdownTimeoutMs(0L);
        conf.setBrokerServicePort(Optional.of(0));
        conf.setBrokerServicePortTls(Optional.of(0));
        conf.setAdvertisedAddress("localhost");
        conf.setWebServicePort(Optional.of(0));
        conf.setWebServicePortTls(Optional.of(0));
        conf.setNumExecutorThreadPoolSize(5);
        conf.setExposeBundlesMetricsInPrometheus(true);
    }

    @Override
    @BeforeClass
    protected void setup() throws Exception {
        // enable auth&auth and use JWT at broker
        conf.setAuthenticationEnabled(true);
        conf.setAuthorizationEnabled(true);
        conf.getProperties().setProperty("tokenSecretKey", "data:;base64,"
                + Base64.getEncoder().encodeToString(SECRET_KEY.getEncoded()));
        Set<String> superUserRoles = new HashSet<>();
        superUserRoles.add("admin");
        conf.setSuperUserRoles(superUserRoles);
        Set<String> providers = new HashSet<>();
        providers.add(AuthenticationProviderToken.class.getName());
        conf.setAuthenticationProviders(providers);
        conf.setTopicLevelPoliciesEnabled(true);
        conf.setSystemTopicEnabled(true);
        configureTokenAuthentication();
        configureDefaultAuthorization();
        super.internalSetup();
        this.pulsarService = getPulsar();
        this.serviceInternalAdmin = pulsarService.getAdminClient();
        setupDefaultTenantAndNamespace();
        this.superUserAdmin = PulsarAdmin.builder()
                .serviceHttpUrl(getPulsarService().getWebServiceAddress())
                .authentication(new AuthenticationToken(SUPER_USER_TOKEN))
                .build();
        final TenantInfo tenantInfo = superUserAdmin.tenants().getTenantInfo("public");
        tenantInfo.getAdminRoles().add(TENANT_ADMIN_SUBJECT);
        superUserAdmin.tenants().updateTenant("public", tenantInfo);
        this.tenantManagerAdmin = PulsarAdmin.builder()
                .serviceHttpUrl(getPulsarService().getWebServiceAddress())
                .authentication(new AuthenticationToken(TENANT_ADMIN_TOKEN))
                .build();
    }

    protected void setupDefaultTenantAndNamespace() throws Exception {
        if (!serviceInternalAdmin.clusters().getClusters().contains(TEST_CLUSTER_NAME)) {
            serviceInternalAdmin.clusters().createCluster(TEST_CLUSTER_NAME,
                    ClusterData.builder().serviceUrl(pulsarService.getWebServiceAddress()).build());
        }
        if (!serviceInternalAdmin.tenants().getTenants().contains(DEFAULT_TENANT)) {
            serviceInternalAdmin.tenants().createTenant(DEFAULT_TENANT, TenantInfo.builder().allowedClusters(
                    Sets.newHashSet(TEST_CLUSTER_NAME)).build());
        }
        if (!serviceInternalAdmin.namespaces().getNamespaces(DEFAULT_TENANT).contains(DEFAULT_NAMESPACE)) {
            serviceInternalAdmin.namespaces().createNamespace(DEFAULT_NAMESPACE);
        }
    }

    @SneakyThrows
    protected void configureTokenAuthentication() {
        conf.setAuthenticationEnabled(true);
        Set<String> authenticationProviders = new HashSet<>();
        authenticationProviders.add(AuthenticationProviderToken.class.getName());
        conf.setAuthenticationProviders(authenticationProviders);
        // internal client
        conf.setBrokerClientAuthenticationPlugin(AuthenticationToken.class.getName());
        final Map<String, String> brokerClientAuthParams = new HashMap<>();
        brokerClientAuthParams.put("token", BROKER_INTERNAL_CLIENT_TOKEN);
        final String brokerClientAuthParamStr = MAPPER.writeValueAsString(brokerClientAuthParams);
        conf.setBrokerClientAuthenticationParameters(brokerClientAuthParamStr);

        Properties properties = conf.getProperties();
        if (properties == null) {
            properties = new Properties();
            conf.setProperties(properties);
        }
        properties.put("tokenSecretKey", AuthTokenUtils.encodeKeyBase64(SECRET_KEY));

    }

    protected void configureDefaultAuthorization() {
        conf.setAuthorizationEnabled(true);
        conf.setAuthorizationProvider(PulsarAuthorizationProvider.class.getName());
        Set<String> superUserRoles = new HashSet<>();
        superUserRoles.add(SUPER_USER_SUBJECT);
        superUserRoles.add(BROKER_INTERNAL_CLIENT_SUBJECT);
        conf.setSuperUserRoles(superUserRoles);
    }

    @Override
    @AfterClass
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }


    @SneakyThrows
    @Test
    public void testUnloadAndCompactAndTrim() {
        final String random = UUID.randomUUID().toString();
        final String topic = "persistent://public/default/" + random;
        final String subject =  UUID.randomUUID().toString();
        final String token = Jwts.builder()
                .claim("sub", subject).signWith(SECRET_KEY).compact();
        superUserAdmin.topics().createPartitionedTopic(topic, 2);

        @Cleanup
        final PulsarAdmin subAdmin = PulsarAdmin.builder()
                .serviceHttpUrl(getPulsarService().getWebServiceAddress())
                .authentication(new AuthenticationToken(token))
                .build();
        // test superuser
        superUserAdmin.topics().unload(topic);
        superUserAdmin.topics().triggerCompaction(topic);
//        superUserAdmin.topics().trimTopic(TopicName.get(topic).getPartition(0).getLocalName());
        superUserAdmin.topicPolicies().getSchemaCompatibilityStrategy(topic, false);

        // test tenant manager
        tenantManagerAdmin.topics().unload(topic);
        tenantManagerAdmin.topics().triggerCompaction(topic);
//        tenantManagerAdmin.topics().trimTopic(TopicName.get(topic).getPartition(0).getLocalName());
        tenantManagerAdmin.topicPolicies().getSchemaCompatibilityStrategy(topic, false);

        // test nobody
        Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                () -> subAdmin.topics().unload(topic));

        Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                () -> subAdmin.topics().triggerCompaction(topic));

//        Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
//                () -> subAdmin.topics().trimTopic(TopicName.get(topic).getPartition(0).getLocalName()));

        Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                () -> subAdmin.topicPolicies().getSchemaCompatibilityStrategy(topic, false));

        // Test only super/admin can do the operation, other auth are not permitted.
        for (AuthAction action : AuthAction.values()) {
            Set<AuthAction> actions = new HashSet<>();
            actions.add(action);
            superUserAdmin.topics().grantPermission(topic, subject, actions);

            Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                    () -> subAdmin.topics().unload(topic));

            Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                    () -> subAdmin.topics().triggerCompaction(topic));

//            Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
//                    () -> subAdmin.topics().trimTopic(topic));

            Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                    () -> subAdmin.topicPolicies().getSchemaCompatibilityStrategy(topic, false));

            superUserAdmin.topics().revokePermissions(topic, subject);
        }
        superUserAdmin.topics().deletePartitionedTopic(topic, true);
    }

    @Test
    @SneakyThrows
    public void testGetManagedLedgerInfo() {
        final String random = UUID.randomUUID().toString();
        final String topic = "persistent://public/default/" + random;
        final String subject =  UUID.randomUUID().toString();
        final String token = Jwts.builder()
                .claim("sub", subject).signWith(SECRET_KEY).compact();
        superUserAdmin.topics().createPartitionedTopic(topic, 2);

        @Cleanup
        final PulsarAdmin subAdmin = PulsarAdmin.builder()
                .serviceHttpUrl(getPulsarService().getWebServiceAddress())
                .authentication(new AuthenticationToken(token))
                .build();
        // test superuser
        superUserAdmin.topics().getInternalInfo(topic);

        // test tenant manager
        tenantManagerAdmin.topics().getInternalInfo(topic);

        // test nobody
        Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                () -> subAdmin.topics().getInternalInfo(topic));

        for (AuthAction action : AuthAction.values()) {
            Set<AuthAction> actions = new HashSet<>();
            actions.add(action);
            superUserAdmin.topics().grantPermission(topic, subject, actions);
            if (action == AuthAction.produce || action == AuthAction.consume) {
                subAdmin.topics().getInternalInfo(topic);
            } else {
                Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                        () -> subAdmin.topics().getInternalInfo(topic));
            }
            superUserAdmin.topics().revokePermissions(topic, subject);
        }
        superUserAdmin.topics().deletePartitionedTopic(topic, true);
    }

    @Test
    @SneakyThrows
    public void testGetPartitionedStatsAndInternalStats() {
        final String random = UUID.randomUUID().toString();
        final String topic = "persistent://public/default/" + random;
        final String subject =  UUID.randomUUID().toString();
        final String token = Jwts.builder()
                .claim("sub", subject).signWith(SECRET_KEY).compact();
        superUserAdmin.topics().createPartitionedTopic(topic, 2);

        @Cleanup
        final PulsarAdmin subAdmin = PulsarAdmin.builder()
                .serviceHttpUrl(getPulsarService().getWebServiceAddress())
                .authentication(new AuthenticationToken(token))
                .build();
        // test superuser
        superUserAdmin.topics().getPartitionedStats(topic, false);
        superUserAdmin.topics().getPartitionedInternalStats(topic);

        // test tenant manager
        tenantManagerAdmin.topics().getPartitionedStats(topic, false);
        tenantManagerAdmin.topics().getPartitionedInternalStats(topic);

        // test nobody
        Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                () -> subAdmin.topics().getPartitionedStats(topic, false));

        Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                () -> subAdmin.topics().getPartitionedInternalStats(topic));

        for (AuthAction action : AuthAction.values()) {
            Set<AuthAction> actions = new HashSet<>();
            actions.add(action);
            superUserAdmin.topics().grantPermission(topic, subject, actions);
            if (action == AuthAction.produce || action == AuthAction.consume) {
                subAdmin.topics().getPartitionedStats(topic, false);
                subAdmin.topics().getPartitionedInternalStats(topic);
            } else {
                Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                        () -> subAdmin.topics().getPartitionedStats(topic, false));

                Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                        () -> subAdmin.topics().getPartitionedInternalStats(topic));
            }
            superUserAdmin.topics().revokePermissions(topic, subject);
        }
        superUserAdmin.topics().deletePartitionedTopic(topic, true);
    }

    @Test
    @SneakyThrows
    public void testCreateSubscriptionAndUpdateSubscriptionPropertiesAndAnalyzeSubscriptionBacklog() {
        final String random = UUID.randomUUID().toString();
        final String topic = "persistent://public/default/" + random;
        final String subject =  UUID.randomUUID().toString();
        final String token = Jwts.builder()
                .claim("sub", subject).signWith(SECRET_KEY).compact();
        superUserAdmin.topics().createPartitionedTopic(topic, 2);
        AtomicInteger suffix = new AtomicInteger(1);
        @Cleanup
        final PulsarAdmin subAdmin = PulsarAdmin.builder()
                .serviceHttpUrl(getPulsarService().getWebServiceAddress())
                .authentication(new AuthenticationToken(token))
                .build();
        //
        superUserAdmin.topics().createSubscription(topic, "test-sub" + suffix.incrementAndGet(), MessageId.earliest);

        // test tenant manager
        tenantManagerAdmin.topics().createSubscription(topic, "test-sub" + suffix.incrementAndGet(), MessageId.earliest);

        // test nobody
        Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                () -> subAdmin.topics().createSubscription(topic, "test-sub" + suffix.incrementAndGet(), MessageId.earliest));

        for (AuthAction action : AuthAction.values()) {
            Set<AuthAction> actions = new HashSet<>();
            actions.add(action);
            superUserAdmin.topics().grantPermission(topic, subject, actions);
            if (action == AuthAction.consume) {
                subAdmin.topics().createSubscription(topic, "test-sub" + suffix.incrementAndGet(), MessageId.earliest);
            } else {
                Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                        () -> subAdmin.topics().createSubscription(topic, "test-sub" + suffix.incrementAndGet(), MessageId.earliest));
            }
            superUserAdmin.topics().revokePermissions(topic, subject);
        }
        // test UpdateSubscriptionProperties
        Map<String, String> properties = new HashMap<>();
        superUserAdmin.topics().createSubscription(topic, "test-sub", MessageId.earliest);
        // test superuser
        superUserAdmin.topics().updateSubscriptionProperties(topic, "test-sub" , properties);
        superUserAdmin.topics().getSubscriptionProperties(topic, "test-sub");
        superUserAdmin.topics().analyzeSubscriptionBacklog(TopicName.get(topic).getPartition(0).getLocalName(), "test-sub", Optional.empty());

        // test tenant manager
        tenantManagerAdmin.topics().updateSubscriptionProperties(topic, "test-sub" , properties);
        tenantManagerAdmin.topics().getSubscriptionProperties(topic, "test-sub");
        tenantManagerAdmin.topics().analyzeSubscriptionBacklog(TopicName.get(topic).getPartition(0).getLocalName(), "test-sub", Optional.empty());

        // test nobody
        Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                () -> subAdmin.topics().updateSubscriptionProperties(topic, "test-sub", properties));

        Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                () -> subAdmin.topics().getSubscriptionProperties(topic, "test-sub"));

        Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                () -> subAdmin.topics().analyzeSubscriptionBacklog(TopicName.get(topic).getPartition(0).getLocalName(), "test-sub", Optional.empty()));

        for (AuthAction action : AuthAction.values()) {
            Set<AuthAction> actions = new HashSet<>();
            actions.add(action);
            superUserAdmin.topics().grantPermission(topic, subject, actions);
            if (action == AuthAction.consume) {
                subAdmin.topics().updateSubscriptionProperties(topic, "test-sub", properties);
                subAdmin.topics().getSubscriptionProperties(topic, "test-sub");
                subAdmin.topics().analyzeSubscriptionBacklog(TopicName.get(topic).getPartition(0).getLocalName(), "test-sub", Optional.empty());
            } else {
                Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                        () -> subAdmin.topics().updateSubscriptionProperties(topic, "test-sub", properties));

                Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                        () -> subAdmin.topics().getSubscriptionProperties(topic, "test-sub"));

                Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                        () -> subAdmin.topics().analyzeSubscriptionBacklog(TopicName.get(topic).getPartition(0).getLocalName(), "test-sub", Optional.empty()));
            }
            superUserAdmin.topics().revokePermissions(topic, subject);
        }
        superUserAdmin.topics().deletePartitionedTopic(topic, true);
    }

    @Test
    @SneakyThrows
    public void testCreateMissingPartition() {
        final String random = UUID.randomUUID().toString();
        final String topic = "persistent://public/default/" + random;
        final String subject =  UUID.randomUUID().toString();
        final String token = Jwts.builder()
                .claim("sub", subject).signWith(SECRET_KEY).compact();
        superUserAdmin.topics().createPartitionedTopic(topic, 2);
        AtomicInteger suffix = new AtomicInteger(1);
        @Cleanup
        final PulsarAdmin subAdmin = PulsarAdmin.builder()
                .serviceHttpUrl(getPulsarService().getWebServiceAddress())
                .authentication(new AuthenticationToken(token))
                .build();
        //
        superUserAdmin.topics().createMissedPartitions(topic);

        // test tenant manager
        tenantManagerAdmin.topics().createMissedPartitions(topic);

        Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                () -> subAdmin.topics().createMissedPartitions(topic));

        for (AuthAction action : AuthAction.values()) {
            Set<AuthAction> actions = new HashSet<>();
            actions.add(action);
            superUserAdmin.topics().grantPermission(topic, subject, actions);
            Assert.assertThrows(PulsarAdminException.NotAuthorizedException.class,
                    () -> subAdmin.topics().createMissedPartitions(topic));
            superUserAdmin.topics().revokePermissions(topic, subject);
        }
        superUserAdmin.topics().deletePartitionedTopic(topic, true);
    }
}
