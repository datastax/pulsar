/*
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
package org.apache.pulsar.broker.systopic;

import static org.apache.pulsar.broker.service.SystemTopicBasedTopicPoliciesService.getEventKey;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.Sets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import lombok.Cleanup;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedLedger;
import org.apache.bookkeeper.mledger.impl.EntryImpl;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.Subscription;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.broker.service.persistent.SystemTopic;
import org.apache.pulsar.broker.service.plugin.EntryFilter;
import org.apache.pulsar.broker.service.plugin.FilterContext;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.api.proto.MessageMetadata;
import org.apache.pulsar.common.events.ActionType;
import org.apache.pulsar.common.events.EventType;
import org.apache.pulsar.common.events.PulsarEvent;
import org.apache.pulsar.common.events.TopicPoliciesEvent;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.SystemTopicNames;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.EntryFilters;
import org.apache.pulsar.common.policies.data.SchemaCompatibilityStrategy;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.common.policies.data.TopicPolicies;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test(groups = "broker")
public class NamespaceEventsSystemTopicServiceTest extends MockedPulsarServiceBaseTest {

    private static final Logger log = LoggerFactory.getLogger(NamespaceEventsSystemTopicServiceTest.class);

    private static final String NAMESPACE1 = "system-topic/namespace-1";
    private static final String NAMESPACE2 = "system-topic/namespace-2";
    private static final String NAMESPACE3 = "system-topic/namespace-3";

    private NamespaceEventsSystemTopicFactory systemTopicFactory;

    @BeforeClass
    @Override
    protected void setup() throws Exception {
        resetConfig();
        //this.conf.setEntryFilterNames(List.of("jms"));
        this.conf.setEntryFiltersDirectory("/Users/andreyyegorov/src/pulsar-jms/pulsar-jms-filters/target");
        super.internalSetup();
        prepareData();
    }

    @AfterClass(alwaysRun = true)
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    public void checkEntryFilter() throws Exception {
        final String normalTopic = "persistent://" + NAMESPACE1 + "/normal_topic";

        admin.topics().createPartitionedTopic(normalTopic, 3);
        BrokerService brokerService = pulsar.getBrokerService();
        List<EntryFilter> filters = brokerService.getEntryFilterProvider().getBrokerEntryFilters();

        FilterContext filterContext = new FilterContext();
        Consumer cons = mock(Consumer.class);
        filterContext.setConsumer(cons);
        when(cons.getMetadata()).thenReturn(Map.of(
                "jms.filtering", "true",
                "jms.selector", "param < 1"));

        Subscription sub = mock(Subscription.class);
        when(sub.getName()).thenReturn("sub");
        when(sub.getTopicName()).thenReturn(normalTopic);
        when(sub.getSubscriptionProperties()).thenReturn(Map.of(
                "jms.filtering", "true",
                "jms.selector", "param < 1"));
        Topic topic = mock(Topic.class);
        when(topic.getName()).thenReturn(normalTopic);
        when(topic.getBrokerService()).thenReturn(brokerService);
        when(sub.getTopic()).thenReturn(topic);

        filterContext.setSubscription(sub);
        MessageMetadata meta = mock(org.apache.pulsar.common.api.proto.MessageMetadata.class);
        filterContext.setMsgMetadata(meta);
        Entry entry = EntryImpl.create(1, 1, "test".getBytes());
        List<EntryFilter> filters0 = brokerService.getEntryFilterProvider().loadEntryFilters(List.of("jms"));
        try
        {
            for (int i = 0; i < 10; i++) {
                if (!filters.isEmpty()) {
                    filters.get(0).filterEntry(entry, filterContext);
                }

                filters0.get(0).filterEntry(entry, filterContext);

                {
                    List<EntryFilter> filters2 = brokerService.getEntryFilterProvider().loadEntryFilters(List.of("jms"));
                    filters2.get(0).filterEntry(entry, filterContext);
                    filters2.get(0).close();
                }
                List<EntryFilter> filters3 = brokerService.getEntryFilterProvider().loadEntryFilters(List.of("jms"));
                // don't use it, just load

                //brokerService.getEntryFilterProvider().close();
                for (int j = 0; j < 10; j++) {
                    System.gc();
                    System.runFinalization();
                    Thread.sleep(1000);
                }

                List<EntryFilter> filters4 = brokerService.getEntryFilterProvider().loadEntryFilters(List.of("jms"));

                filters3.get(0).close();

                filters4.get(0).filterEntry(entry, filterContext);
                filters4.get(0).close();
            }
        } finally {
            filters0.get(0).close();
            if (!filters.isEmpty()) {
                filters.get(0).close();
            }
            entry.release();
        }
    }


    @Test
    public void testSchemaCompatibility() throws Exception {
        TopicPoliciesSystemTopicClient systemTopicClientForNamespace1 = systemTopicFactory
                .createTopicPoliciesSystemTopicClient(NamespaceName.get(NAMESPACE1));
        String topicName = systemTopicClientForNamespace1.getTopicName().toString();
        @Cleanup
        Reader<byte[]> reader = pulsarClient.newReader(Schema.BYTES)
                .topic(topicName)
                .startMessageId(MessageId.earliest)
                .create();

        PersistentTopic topic =
                (PersistentTopic) pulsar.getBrokerService()
                        .getTopic(topicName, false)
                        .join().get();

        Assert.assertEquals(SchemaCompatibilityStrategy.ALWAYS_COMPATIBLE, topic.getSchemaCompatibilityStrategy());
    }

    @Test
    public void testSystemTopicSchemaCompatibility() throws Exception {
        TopicPoliciesSystemTopicClient systemTopicClientForNamespace1 = systemTopicFactory
                .createTopicPoliciesSystemTopicClient(NamespaceName.get(NAMESPACE1));
        String topicName = systemTopicClientForNamespace1.getTopicName().toString();
        SystemTopic topic = new SystemTopic(topicName, mock(ManagedLedger.class), pulsar.getBrokerService());

        Assert.assertEquals(SchemaCompatibilityStrategy.ALWAYS_COMPATIBLE, topic.getSchemaCompatibilityStrategy());
    }

    @Test
    public void testSendAndReceiveNamespaceEvents() throws Exception {
        TopicPoliciesSystemTopicClient systemTopicClientForNamespace1 = systemTopicFactory
                .createTopicPoliciesSystemTopicClient(NamespaceName.get(NAMESPACE1));
        TopicPolicies policies = TopicPolicies.builder()
            .maxProducerPerTopic(10)
            .build();
        PulsarEvent event = PulsarEvent.builder()
            .eventType(EventType.TOPIC_POLICY)
            .actionType(ActionType.INSERT)
            .topicPoliciesEvent(TopicPoliciesEvent.builder()
                .domain("persistent")
                .tenant("system-topic")
                .namespace(NamespaceName.get(NAMESPACE1).getLocalName())
                .topic("my-topic")
                .policies(policies)
                .build())
            .build();
        systemTopicClientForNamespace1.newWriter().write(getEventKey(event), event);
        SystemTopicClient.Reader reader = systemTopicClientForNamespace1.newReader();
        Message<PulsarEvent> received = reader.readNext();
        log.info("Receive pulsar event from system topic : {}", received.getValue());

        // test event send and receive
        Assert.assertEquals(received.getValue(), event);
        Assert.assertEquals(systemTopicClientForNamespace1.getWriters().size(), 1);
        Assert.assertEquals(systemTopicClientForNamespace1.getReaders().size(), 1);

        // test new reader read
        SystemTopicClient.Reader reader1 = systemTopicClientForNamespace1.newReader();
        Message<PulsarEvent> received1 = reader1.readNext();
        log.info("Receive pulsar event from system topic : {}", received1.getValue());
        Assert.assertEquals(received1.getValue(), event);

        // test writers and readers
        Assert.assertEquals(systemTopicClientForNamespace1.getReaders().size(), 2);
        SystemTopicClient.Writer writer = systemTopicClientForNamespace1.newWriter();
        Assert.assertEquals(systemTopicClientForNamespace1.getWriters().size(), 2);
        writer.close();
        reader.close();
        Assert.assertEquals(systemTopicClientForNamespace1.getWriters().size(), 1);
        Assert.assertEquals(systemTopicClientForNamespace1.getReaders().size(), 1);
        systemTopicClientForNamespace1.close();
        Assert.assertEquals(systemTopicClientForNamespace1.getWriters().size(), 0);
        Assert.assertEquals(systemTopicClientForNamespace1.getReaders().size(), 0);
    }

    @Test(timeOut = 30000)
    public void checkSystemTopic() throws PulsarAdminException {
        final String systemTopic = "persistent://" + NAMESPACE1 + "/" + SystemTopicNames.NAMESPACE_EVENTS_LOCAL_NAME;
        final String normalTopic = "persistent://" + NAMESPACE1 + "/normal_topic";
        admin.topics().createPartitionedTopic(normalTopic, 3);
        TopicName systemTopicName = TopicName.get(systemTopic);
        TopicName normalTopicName = TopicName.get(normalTopic);
        BrokerService brokerService = pulsar.getBrokerService();
        Assert.assertEquals(brokerService.isSystemTopic(systemTopicName), true);
        Assert.assertEquals(brokerService.isSystemTopic(normalTopicName), false);
    }

    private void prepareData() throws PulsarAdminException {
        admin.clusters().createCluster("test", ClusterData.builder().serviceUrl(pulsar.getWebServiceAddress()).build());
        admin.tenants().createTenant("system-topic",
            new TenantInfoImpl(new HashSet<>(), Sets.newHashSet("test")));
        admin.namespaces().createNamespace(NAMESPACE1);
        admin.namespaces().createNamespace(NAMESPACE2);
        admin.namespaces().createNamespace(NAMESPACE3);
        systemTopicFactory = new NamespaceEventsSystemTopicFactory(pulsarClient);
    }
}
