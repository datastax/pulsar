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
package org.apache.pulsar.broker.service.persistent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.pulsar.common.protocol.Commands.serializeMetadataAndPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.SucceededFuture;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.bookkeeper.common.util.OrderedExecutor;
import org.apache.bookkeeper.mledger.AsyncCallbacks;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.EntryImpl;
import org.apache.bookkeeper.mledger.impl.ManagedCursorImpl;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.bookkeeper.mledger.impl.ManagedLedgerImpl;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.EntryAndMetadata;
import org.apache.pulsar.broker.service.EntryBatchIndexesAcks;
import org.apache.pulsar.broker.service.EntryBatchSizes;
import org.apache.pulsar.broker.service.RedeliveryTracker;
import org.apache.pulsar.broker.service.StickyKeyConsumerSelector;
import org.apache.pulsar.broker.service.TransportCnx;
import org.apache.pulsar.broker.service.plugin.EntryFilterProvider;
import org.apache.pulsar.common.api.proto.KeySharedMeta;
import org.apache.pulsar.common.api.proto.KeySharedMode;
import org.apache.pulsar.common.api.proto.MessageMetadata;
import org.apache.pulsar.common.policies.data.HierarchyTopicPolicies;
import org.apache.pulsar.common.protocol.Commands;
import org.apache.pulsar.common.protocol.Markers;
import org.apache.pulsar.common.util.collections.ConcurrentOpenLongPairRangeSet;
import org.apache.pulsar.common.util.collections.LongPairRangeSet;
import org.awaitility.Awaitility;
import org.mockito.ArgumentCaptor;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

@Test(groups = "broker")
public class PersistentStickyKeyDispatcherMultipleConsumersTest {

    private PulsarService pulsarMock;
    private BrokerService brokerMock;
    private ManagedLedgerImpl ledgerMock;
    private ManagedCursorImpl cursorMock;
    private Consumer consumerMock;
    private PersistentTopic topicMock;
    private PersistentSubscription subscriptionMock;
    private ServiceConfiguration configMock;
    private ChannelPromise channelMock;
    private Future<Void> succeededFuture;
    private OrderedExecutor orderedExecutor;

    private PersistentStickyKeyDispatcherMultipleConsumers persistentDispatcher;

    final String topicName = "persistent://public/default/testTopic";
    final String subscriptionName = "testSubscription";
    private AtomicInteger consumerMockAvailablePermits;

    @BeforeMethod
    public void setup() throws Exception {
        configMock = mock(ServiceConfiguration.class);
        doReturn(true).when(configMock).isSubscriptionRedeliveryTrackerEnabled();
        doReturn(100).when(configMock).getDispatcherMaxReadBatchSize();
        doReturn(true).when(configMock).isSubscriptionKeySharedUseConsistentHashing();
        doReturn(1).when(configMock).getSubscriptionKeySharedConsistentHashingReplicaPoints();
        doReturn(true).when(configMock).isDispatcherDispatchMessagesInSubscriptionThread();
        doReturn(false).when(configMock).isAllowOverrideEntryFilters();

        pulsarMock = mock(PulsarService.class);
        doReturn(configMock).when(pulsarMock).getConfiguration();

        EntryFilterProvider mockEntryFilterProvider = mock(EntryFilterProvider.class);
        when(mockEntryFilterProvider.getBrokerEntryFilters()).thenReturn(Collections.emptyList());

        brokerMock = mock(BrokerService.class);
        doReturn(pulsarMock).when(brokerMock).pulsar();
        when(brokerMock.getEntryFilterProvider()).thenReturn(mockEntryFilterProvider);

        HierarchyTopicPolicies topicPolicies = new HierarchyTopicPolicies();
        topicPolicies.getMaxConsumersPerSubscription().updateBrokerValue(0);

        orderedExecutor = OrderedExecutor.newBuilder().build();
        doReturn(orderedExecutor).when(brokerMock).getTopicOrderedExecutor();

        EventLoopGroup eventLoopGroup = mock(EventLoopGroup.class);
        doReturn(eventLoopGroup).when(brokerMock).executor();
        doAnswer(invocation -> {
            orderedExecutor.execute(invocation.getArgument(0, Runnable.class));
            return null;
        }).when(eventLoopGroup).execute(any(Runnable.class));

        topicMock = mock(PersistentTopic.class);
        doReturn(brokerMock).when(topicMock).getBrokerService();
        doReturn(topicName).when(topicMock).getName();
        doReturn(topicPolicies).when(topicMock).getHierarchyTopicPolicies();

        ledgerMock = mock(ManagedLedgerImpl.class);
        doAnswer((invocationOnMock -> {
            final Position position = invocationOnMock.getArgument(0);
            if (position.getEntryId() > 0) {
                return PositionImpl.get(position.getLedgerId(), position.getEntryId() - 1);
            } else {
                fail("Undefined behavior on mock");
                return PositionImpl.EARLIEST;
            }
        })).when(ledgerMock).getPreviousPosition(any(PositionImpl.class));
        doAnswer((invocationOnMock -> {
            final Position position = invocationOnMock.getArgument(0);
            return PositionImpl.get(position.getLedgerId(), position.getEntryId() < 0 ? 0 : position.getEntryId() + 1);
        })).when(ledgerMock).getNextValidPosition(any(PositionImpl.class));
        doAnswer((invocationOnMock -> {
            final Range<PositionImpl> range = invocationOnMock.getArgument(0);
            Position fromPosition = range.lowerEndpoint();
            boolean fromIncluded = range.lowerBoundType() == BoundType.CLOSED;
            Position toPosition = range.upperEndpoint();
            boolean toIncluded = range.upperBoundType() == BoundType.CLOSED;

            long count = 0;

            if (fromPosition.getLedgerId() == toPosition.getLedgerId()) {
                // If the 2 positions are in the same ledger
                count = toPosition.getEntryId() - fromPosition.getEntryId() - 1;
                count += fromIncluded ? 1 : 0;
                count += toIncluded ? 1 : 0;
            } else {
                fail("Undefined behavior on mock");
            }
            return count;
        })).when(ledgerMock).getNumberOfEntries(any());

        cursorMock = mock(ManagedCursorImpl.class);
        doReturn(null).when(cursorMock).getLastIndividualDeletedRange();
        doReturn(subscriptionName).when(cursorMock).getName();
        doReturn(ledgerMock).when(cursorMock).getManagedLedger();

        consumerMock = createMockConsumer();
        channelMock = mock(ChannelPromise.class);
        doReturn(channelMock).when(consumerMock).sendMessages(
                anyList(),
                any(EntryBatchSizes.class),
                any(EntryBatchIndexesAcks.class),
                anyInt(),
                anyLong(),
                anyLong(),
                any(RedeliveryTracker.class)
        );

        EventExecutor eventExecutor = mock(EventExecutor.class);
        doAnswer(invocation -> {
            orderedExecutor.execute(invocation.getArgument(0, Runnable.class));
            return null;
        }).when(eventExecutor).execute(any(Runnable.class));
        doReturn(false).when(eventExecutor).inEventLoop();
        succeededFuture = new SucceededFuture<>(eventExecutor, null);
        doReturn("consumer1").when(consumerMock).consumerName();
        consumerMockAvailablePermits = new AtomicInteger(1000);
        doAnswer(invocation -> consumerMockAvailablePermits.get()).when(consumerMock).getAvailablePermits();
        doReturn(true).when(consumerMock).isWritable();
        mockSendMessages(consumerMock, null);

        subscriptionMock = mock(PersistentSubscription.class);
        when(subscriptionMock.getTopic()).thenReturn(topicMock);
        persistentDispatcher = new PersistentStickyKeyDispatcherMultipleConsumers(
                topicMock, cursorMock, subscriptionMock, configMock,
                new KeySharedMeta().setKeySharedMode(KeySharedMode.AUTO_SPLIT));
    }

    private void mockSendMessages(Consumer consumerMock, java.util.function.Consumer<List<Entry>> entryConsumer) {
        doAnswer(invocation -> {
            List<Entry> entries = invocation.getArgument(0);
            if (entryConsumer != null) {
                entryConsumer.accept(entries);
            }
            entries.stream().filter(Objects::nonNull).forEach(Entry::release);
            return succeededFuture;
        }).when(consumerMock).sendMessages(
                anyList(),
                any(EntryBatchSizes.class),
                any(EntryBatchIndexesAcks.class),
                anyInt(),
                anyLong(),
                anyLong(),
                any(RedeliveryTracker.class)
        );
    }

    protected static Consumer createMockConsumer() {
        Consumer consumerMock = mock(Consumer.class);
        TransportCnx transportCnx = mock(TransportCnx.class);
        doReturn(transportCnx).when(consumerMock).cnx();
        doReturn(true).when(transportCnx).isActive();
        doReturn(100).when(consumerMock).getMaxUnackedMessages();
        doReturn(1).when(consumerMock).getAvgMessagesPerEntry();
        return consumerMock;
    }

    @AfterMethod(alwaysRun = true)
    public void cleanup() {
        if (persistentDispatcher != null && !persistentDispatcher.isClosed()) {
            persistentDispatcher.close();
        }
        if (orderedExecutor != null) {
            orderedExecutor.shutdownNow();
            orderedExecutor = null;
        }
    }

    @Test(timeOut = 10000)
    public void testAddConsumerWhenClosed() throws Exception {
        persistentDispatcher.close().get();
        Consumer consumer = createMockConsumer();
        persistentDispatcher.addConsumer(consumer).join();
        verify(consumer, times(1)).disconnect();
        assertEquals(0, persistentDispatcher.getConsumers().size());
        assertTrue(persistentDispatcher.getSelector().getConsumerKeyHashRanges().isEmpty());
    }

    @Test
    public void testSortRecentlyJoinedConsumersIfNeeded() throws Exception {
        PersistentStickyKeyDispatcherMultipleConsumers persistentDispatcher =
                new PersistentStickyKeyDispatcherMultipleConsumers(
                topicMock, cursorMock, subscriptionMock, configMock,
                new KeySharedMeta().setKeySharedMode(KeySharedMode.AUTO_SPLIT));

        Consumer consumer0 = createMockConsumer();
        when(consumer0.consumerName()).thenReturn("c0-1");
        Consumer consumer1 = createMockConsumer();
        when(consumer1.consumerName()).thenReturn("c1");
        Consumer consumer2 = createMockConsumer();
        when(consumer2.consumerName()).thenReturn("c2");
        Consumer consumer3 = createMockConsumer();
        when(consumer3.consumerName()).thenReturn("c3");
        Consumer consumer4 = createMockConsumer();
        when(consumer4.consumerName()).thenReturn("c4");
        Consumer consumer5 = createMockConsumer();
        when(consumer5.consumerName()).thenReturn("c5");
        Consumer consumer6 = createMockConsumer();
        when(consumer6.consumerName()).thenReturn("c6");

        when(cursorMock.getNumberOfEntriesSinceFirstNotAckedMessage()).thenReturn(100L);
        when(cursorMock.getMarkDeletedPosition()).thenReturn(PositionImpl.get(-1, -1));

        when(cursorMock.getReadPosition()).thenReturn(PositionImpl.get(0, 0));
        persistentDispatcher.addConsumer(consumer0).join();

        when(cursorMock.getReadPosition()).thenReturn(PositionImpl.get(4, 1));
        persistentDispatcher.addConsumer(consumer1).join();

        when(cursorMock.getReadPosition()).thenReturn(PositionImpl.get(5, 2));
        persistentDispatcher.addConsumer(consumer2).join();

        when(cursorMock.getReadPosition()).thenReturn(PositionImpl.get(5, 1));
        persistentDispatcher.addConsumer(consumer3).join();

        when(cursorMock.getReadPosition()).thenReturn(PositionImpl.get(5, 3));
        persistentDispatcher.addConsumer(consumer4).join();

        when(cursorMock.getReadPosition()).thenReturn(PositionImpl.get(4, 2));
        persistentDispatcher.addConsumer(consumer5).join();

        when(cursorMock.getReadPosition()).thenReturn(PositionImpl.get(6, 1));
        persistentDispatcher.addConsumer(consumer6).join();

        assertEquals(persistentDispatcher.getRecentlyJoinedConsumers().size(), 6);

        Iterator<Map.Entry<Consumer, PositionImpl>> itr
                = persistentDispatcher.getRecentlyJoinedConsumers().entrySet().iterator();

        Map.Entry<Consumer, PositionImpl> entry1 = itr.next();
        assertEquals(entry1.getValue(), PositionImpl.get(4, 1));
        assertEquals(entry1.getKey(), consumer1);

        Map.Entry<Consumer, PositionImpl> entry2 = itr.next();
        assertEquals(entry2.getValue(), PositionImpl.get(4, 2));
        assertEquals(entry2.getKey(), consumer5);

        Map.Entry<Consumer, PositionImpl> entry3 = itr.next();
        assertEquals(entry3.getValue(), PositionImpl.get(5, 1));
        assertEquals(entry3.getKey(), consumer3);

        Map.Entry<Consumer, PositionImpl> entry4 = itr.next();
        assertEquals(entry4.getValue(), PositionImpl.get(5, 2));
        assertEquals(entry4.getKey(), consumer2);

        Map.Entry<Consumer, PositionImpl> entry5 = itr.next();
        assertEquals(entry5.getValue(), PositionImpl.get(5, 3));
        assertEquals(entry5.getKey(), consumer4);

        Map.Entry<Consumer, PositionImpl> entry6 = itr.next();
        assertEquals(entry6.getValue(), PositionImpl.get(6, 1));
        assertEquals(entry6.getKey(), consumer6);

        // cleanup.
        persistentDispatcher.close();
    }

    @Test
    public void testSendMarkerMessage() {
        try {
            persistentDispatcher.addConsumer(consumerMock).join();
            persistentDispatcher.consumerFlow(consumerMock, 1000);
        } catch (Exception e) {
            fail("Failed to add mock consumer", e);
        }

        List<Entry> entries = new ArrayList<>();
        ByteBuf markerMessage = Markers.newReplicatedSubscriptionsSnapshotRequest("testSnapshotId", "testSourceCluster");
        entries.add(EntryImpl.create(1, 1, markerMessage));
        markerMessage.release();
        entries.add(createEntry(1, 2, "message1", 1));
        entries.add(createEntry(1, 3, "message2", 2));
        entries.add(createEntry(1, 4, "message3", 3));
        entries.add(createEntry(1, 5, "message4", 4));
        entries.add(createEntry(1, 6, "message5", 5));

        try {
            persistentDispatcher.readEntriesComplete(copyEntries(entries),
                    PersistentStickyKeyDispatcherMultipleConsumers.ReadType.Normal);
        } catch (Exception e) {
            fail("Failed to readEntriesComplete.", e);
        }

        Awaitility.await().untilAsserted(() -> {
            ArgumentCaptor<Integer> totalMessagesCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(consumerMock, times(1)).sendMessages(
                    anyList(),
                    any(EntryBatchSizes.class),
                    any(EntryBatchIndexesAcks.class),
                    totalMessagesCaptor.capture(),
                    anyLong(),
                    anyLong(),
                    any(RedeliveryTracker.class)
            );

            List<Integer> allTotalMessagesCaptor = totalMessagesCaptor.getAllValues();
            Assert.assertEquals(allTotalMessagesCaptor.get(0).intValue(), 5);
        });

        entries.forEach(Entry::release);
    }

    private static List<Entry> copyEntries(List<Entry> entries) {
        return entries.stream().map(entry -> EntryImpl.create((EntryImpl) entry))
                .collect(Collectors.toList());
    }

    @Test(timeOut = 10000)
    public void testSendMessage() {
        KeySharedMeta keySharedMeta = new KeySharedMeta().setKeySharedMode(KeySharedMode.STICKY);
        PersistentStickyKeyDispatcherMultipleConsumers
                persistentDispatcher = new PersistentStickyKeyDispatcherMultipleConsumers(
                topicMock, cursorMock, subscriptionMock, configMock, keySharedMeta);
        try {
            keySharedMeta.addHashRange()
                    .setStart(0)
                    .setEnd(9);

            Consumer consumerMock = createMockConsumer();
            doReturn(keySharedMeta).when(consumerMock).getKeySharedMeta();
            mockSendMessages(consumerMock, null);
            persistentDispatcher.addConsumer(consumerMock).join();
            persistentDispatcher.consumerFlow(consumerMock, 1000);
        } catch (Exception e) {
            fail("Failed to add mock consumer", e);
        }

        List<Entry> entries = new ArrayList<>();
        entries.add(createEntry(1, 1, "message1", 1));
        entries.add(createEntry(1, 2, "message2", 2));

        try {
            //Should success,see issue #8960
            persistentDispatcher.readEntriesComplete(copyEntries(entries),
                    PersistentStickyKeyDispatcherMultipleConsumers.ReadType.Normal);
        } catch (Exception e) {
            fail("Failed to readEntriesComplete.", e);
        }

        entries.forEach(Entry::release);
    }

    @Test
    public void testSkipRedeliverTemporally() throws InterruptedException {
        // add first consumer
        persistentDispatcher.addConsumer(consumerMock).join();
        // add slow consumer
        final Consumer slowConsumerMock = createMockConsumer();
        doReturn("consumer2").when(slowConsumerMock).consumerName();
        AtomicInteger slowConsumerAvailablePermits = new AtomicInteger(0);
        doAnswer(invocation -> {
            return slowConsumerAvailablePermits.get();
        }).when(slowConsumerMock).getAvailablePermits();
        persistentDispatcher.addConsumer(slowConsumerMock).join();

        StickyKeyConsumerSelector selector = persistentDispatcher.getSelector();
        String keyForConsumer = generateKeyForConsumer(selector, consumerMock);
        String keyForSlowConsumer = generateKeyForConsumer(selector, slowConsumerMock);

        Set<Position> alreadySent = new ConcurrentSkipListSet<>();

        final List<Entry> allEntries = new ArrayList<>();
        allEntries.add(createEntry(1, 1, "message1", 1, keyForSlowConsumer));
        allEntries.add(createEntry(1, 2, "message2", 2, keyForSlowConsumer));
        allEntries.add(createEntry(1, 3, "message3", 3, keyForConsumer));

        // add first entry to redeliver initially
        final List<Entry> redeliverEntries = new ArrayList<>();
        redeliverEntries.add(allEntries.get(0));

        try {
            Field totalAvailablePermitsField =
                    PersistentDispatcherMultipleConsumers.class.getDeclaredField("totalAvailablePermits");
            totalAvailablePermitsField.setAccessible(true);
            totalAvailablePermitsField.set(persistentDispatcher, 1000);
        } catch (Exception e) {
            fail("Failed to set to field", e);
        }

        // Mock Cursor#asyncReplayEntries
        doAnswer(invocationOnMock -> {
            Set<Position> positionsArg = invocationOnMock.getArgument(0);
            Set<Position> positions = new TreeSet<>(positionsArg);
            List<Entry> entries = allEntries.stream()
                    .filter(entry -> entry.getLedgerId() != -1 && positions.contains(entry.getPosition()))
                    .toList();
            AsyncCallbacks.ReadEntriesCallback callback = invocationOnMock.getArgument(1);
            Object ctx = invocationOnMock.getArgument(2);
            callback.readEntriesComplete(copyEntries(entries), ctx);
            return Collections.emptySet();
        }).when(cursorMock).asyncReplayEntries(anySet(), any(), any(), anyBoolean());

        doAnswer(invocationOnMock -> {
            int maxEntries = invocationOnMock.getArgument(0);
            AsyncCallbacks.ReadEntriesCallback callback = invocationOnMock.getArgument(2);
            List<Entry> entries = allEntries.stream()
                    .filter(entry -> entry.getLedgerId() != -1 && !alreadySent.contains(entry.getPosition()))
                    .limit(maxEntries)
                    .toList();
            Object ctx = invocationOnMock.getArgument(3);
            callback.readEntriesComplete(copyEntries(entries), ctx);
            return null;
        }).when(cursorMock).asyncReadEntriesOrWait(anyInt(), anyLong(), any(), any(), any());

        doReturn(true).when(slowConsumerMock).isWritable();
        CountDownLatch message3Sent = new CountDownLatch(1);
        mockSendMessages(consumerMock, entries -> {
            entries.forEach(entry -> {
                alreadySent.add(entry.getPosition());
            });
            boolean message3Found = entries.stream()
                    .anyMatch(entry -> entry.getLedgerId() == 1 && entry.getEntryId() == 3);
            if (message3Found) {
                message3Sent.countDown();
            }
        });
        CountDownLatch slowConsumerMessagesSent = new CountDownLatch(2);
        mockSendMessages(slowConsumerMock, entries -> {
            entries.forEach(entry -> {
                alreadySent.add(entry.getPosition());
                slowConsumerMessagesSent.countDown();
            });
        });

        // add entries to redeliver
        redeliverEntries.forEach(entry -> {
            // calculate hash
            EntryAndMetadata entryAndMetadata = EntryAndMetadata.create(entry);
            int stickyKeyHash = StickyKeyConsumerSelector.makeStickyKeyHash(entryAndMetadata.getStickyKey());
            // add to redeliver
            persistentDispatcher.addMessageToReplay(entry.getLedgerId(), entry.getEntryId(), stickyKeyHash);
        });

        // trigger readMoreEntries, will handle redelivery logic and skip slow consumer
        persistentDispatcher.readMoreEntriesAsync();

        assertTrue(message3Sent.await(5, TimeUnit.SECONDS));

        // verify that slow consumer messages are not sent before message3 to "consumer"
        assertEquals(slowConsumerMessagesSent.getCount(), 2);

        // set permits to 2
        slowConsumerAvailablePermits.set(2);

        // now wait for slow consumer messages since there are permits
        assertTrue(slowConsumerMessagesSent.await(5, TimeUnit.SECONDS));

        allEntries.forEach(Entry::release);
    }

    @Test(timeOut = 30000)
    public void testMessageRedelivery() throws Exception {
        final List<Position> actualEntriesToConsumer1 = new CopyOnWriteArrayList<>();
        final List<Position> actualEntriesToConsumer2 = new CopyOnWriteArrayList<>();

        final List<Position> expectedEntriesToConsumer1 = new CopyOnWriteArrayList<>();
        final List<Position> expectedEntriesToConsumer2 = new CopyOnWriteArrayList<>();

        final CountDownLatch remainingEntriesNum = new CountDownLatch(3);

        final Consumer consumer1 = createMockConsumer();
        doReturn("consumer1").when(consumer1).consumerName();
        // Change availablePermits of consumer1 to 0 and then back to normal
        when(consumer1.getAvailablePermits()).thenReturn(0).thenReturn(10);
        doReturn(true).when(consumer1).isWritable();
        mockSendMessages(consumer1, entries -> {
            for (Entry entry : entries) {
                actualEntriesToConsumer1.add(entry.getPosition());
                remainingEntriesNum.countDown();
            }
        });

        final Consumer consumer2 = createMockConsumer();
        doReturn("consumer2").when(consumer2).consumerName();
        when(consumer2.getAvailablePermits()).thenReturn(10);
        doReturn(true).when(consumer2).isWritable();
        mockSendMessages(consumer2, entries -> {
            for (Entry entry : entries) {
                actualEntriesToConsumer2.add(entry.getPosition());
                remainingEntriesNum.countDown();
            }
        });

        persistentDispatcher.addConsumer(consumer1).join();
        persistentDispatcher.addConsumer(consumer2).join();

        final Field totalAvailablePermitsField = PersistentDispatcherMultipleConsumers.class
                .getDeclaredField("totalAvailablePermits");
        totalAvailablePermitsField.setAccessible(true);
        totalAvailablePermitsField.set(persistentDispatcher, 1000);

        StickyKeyConsumerSelector selector = persistentDispatcher.getSelector();

        String keyForConsumer1 = generateKeyForConsumer(selector, consumer1);
        String keyForConsumer2 = generateKeyForConsumer(selector, consumer2);

        // Messages with key1 are routed to consumer1 and messages with key2 are routed to consumer2
        final List<Entry> allEntries = new ArrayList<>();
        allEntries.add(createEntry(1, 1, "message1", 1, keyForConsumer1));
        allEntries.add(createEntry(1, 2, "message2", 2, keyForConsumer1));
        allEntries.add(createEntry(1, 3, "message3", 3, keyForConsumer2));

        // add first entry to redeliver initially
        final List<Entry> redeliverEntries = new ArrayList<>();
        redeliverEntries.add(allEntries.get(0)); // message1

        expectedEntriesToConsumer1.add(allEntries.get(0).getPosition());
        expectedEntriesToConsumer1.add(allEntries.get(1).getPosition());
        expectedEntriesToConsumer2.add(allEntries.get(2).getPosition());

        // Mock Cursor#asyncReplayEntries
        doAnswer(invocationOnMock -> {
            Set<Position> positionsArg = invocationOnMock.getArgument(0);
            Set<Position> positions = new TreeSet<>(positionsArg);
            Set<Position> alreadyReceived = new TreeSet<>();
            alreadyReceived.addAll(actualEntriesToConsumer1);
            alreadyReceived.addAll(actualEntriesToConsumer2);
            List<Entry> entries = allEntries.stream().filter(entry -> entry.getLedgerId() != -1
                            && positions.contains(entry.getPosition())
                            && !alreadyReceived.contains(entry.getPosition()))
                    .collect(Collectors.toList());
            AsyncCallbacks.ReadEntriesCallback callback = invocationOnMock.getArgument(1);
            Object ctx = invocationOnMock.getArgument(2);
            callback.readEntriesComplete(copyEntries(entries), ctx);
            return alreadyReceived;
        }).when(cursorMock).asyncReplayEntries(anySet(), any(), any(), anyBoolean());

        // Mock Cursor#asyncReadEntriesOrWait
        doAnswer(invocationOnMock -> {
            int maxEntries = invocationOnMock.getArgument(0);
            Set<Position> alreadyReceived = new TreeSet<>();
            alreadyReceived.addAll(actualEntriesToConsumer1);
            alreadyReceived.addAll(actualEntriesToConsumer2);
            List<Entry> entries = allEntries.stream()
                    .filter(entry -> entry.getLedgerId() != -1 && !alreadyReceived.contains(entry.getPosition()))
                    .limit(maxEntries)
                    .collect(Collectors.toList());
            AsyncCallbacks.ReadEntriesCallback callback = invocationOnMock.getArgument(2);
            Object ctx = invocationOnMock.getArgument(3);
            callback.readEntriesComplete(copyEntries(entries), ctx);
            return null;
        }).when(cursorMock).asyncReadEntriesOrWait(anyInt(), anyLong(), any(), any(), any());

        // add entries to redeliver
        redeliverEntries.forEach(entry -> {
            // calculate hash
            EntryAndMetadata entryAndMetadata = EntryAndMetadata.create(entry);
            int stickyKeyHash = StickyKeyConsumerSelector.makeStickyKeyHash(entryAndMetadata.getStickyKey());
            // add to redeliver
            persistentDispatcher.addMessageToReplay(entry.getLedgerId(), entry.getEntryId(), stickyKeyHash);
        });

        // trigger logic to read entries, includes redelivery logic
        persistentDispatcher.readMoreEntriesAsync();

        assertTrue(remainingEntriesNum.await(5, TimeUnit.SECONDS));

        assertThat(actualEntriesToConsumer1).containsExactlyElementsOf(expectedEntriesToConsumer1);
        assertThat(actualEntriesToConsumer2).containsExactlyElementsOf(expectedEntriesToConsumer2);

        allEntries.forEach(entry -> entry.release());
    }

    private String generateKeyForConsumer(StickyKeyConsumerSelector selector, Consumer consumer) {
        int i = 0;
        while (!Thread.currentThread().isInterrupted()) {
            String key = "key" + i++;
            Consumer selectedConsumer = selector.select(key.getBytes(UTF_8));
            if (selectedConsumer == consumer) {
                return key;
            }
        }
        return null;
    }

    @DataProvider(name = "initializeLastSentPosition")
    private Object[][] initialLastSentPositionProvider() {
        return new Object[][] { { false }, { true } };
    }

    @Test(dataProvider = "initializeLastSentPosition")
    public void testLastSentPositionAndIndividuallySentPositions(final boolean initializeLastSentPosition) throws Exception {
        final Position initialLastSentPosition = PositionImpl.get(1, 10);
        final LongPairRangeSet<PositionImpl> expectedIndividuallySentPositions
                = new ConcurrentOpenLongPairRangeSet<>(4096, PositionImpl::get);

        final Field lastSentPositionField = PersistentStickyKeyDispatcherMultipleConsumers.class
                .getDeclaredField("lastSentPosition");
        lastSentPositionField.setAccessible(true);
        final LongPairRangeSet<PositionImpl> individuallySentPositions = persistentDispatcher.getIndividuallySentPositionsField();
        final Supplier<Throwable> clearPosition = () -> {
            try {
                lastSentPositionField.set(persistentDispatcher, initializeLastSentPosition ? initialLastSentPosition : null);
                individuallySentPositions.clear();
                expectedIndividuallySentPositions.clear();
            } catch (Throwable e) {
                return e;
            }
            return null;
        };
        if (!initializeLastSentPosition) {
            doReturn(initialLastSentPosition).when(cursorMock).getMarkDeletedPosition();
            doAnswer(invocationOnMock -> {
                // skip copy operation
                return initialLastSentPosition;
            }).when(cursorMock).processIndividuallyDeletedMessagesAndGetMarkDeletedPosition(any());
        }

        // Assume the range sequence is [1:0, 1:19], [2:0, 2:19], ..., [10:0, 10:19]
        doAnswer((invocationOnMock -> {
            final Position position = invocationOnMock.getArgument(0);
            if (position.getEntryId() > 0) {
                return PositionImpl.get(position.getLedgerId(), position.getEntryId() - 1);
            } else if (position.getLedgerId() > 0) {
                return PositionImpl.get(position.getLedgerId() - 1, 19);
            } else {
                throw new NullPointerException();
            }
        })).when(ledgerMock).getPreviousPosition(any(PositionImpl.class));
        doAnswer((invocationOnMock -> {
            final Position position = invocationOnMock.getArgument(0);
            if (position.getEntryId() < 19) {
                return PositionImpl.get(position.getLedgerId(), position.getEntryId() + 1);
            } else {
                return PositionImpl.get(position.getLedgerId() + 1, 0);
            }
        })).when(ledgerMock).getNextValidPosition(any(PositionImpl.class));
        doReturn(PositionImpl.get(10, 19)).when(ledgerMock).getLastConfirmedEntry();
        doAnswer((invocationOnMock -> {
            final Range<PositionImpl> range = invocationOnMock.getArgument(0);
            Position fromPosition = range.lowerEndpoint();
            boolean fromIncluded = range.lowerBoundType() == BoundType.CLOSED;
            Position toPosition = range.upperEndpoint();
            boolean toIncluded = range.upperBoundType() == BoundType.CLOSED;

            if (fromPosition.getLedgerId() == toPosition.getLedgerId()) {
                // If the 2 positions are in the same ledger
                long count = toPosition.getEntryId() - fromPosition.getEntryId() - 1;
                count += fromIncluded ? 1 : 0;
                count += toIncluded ? 1 : 0;
                return count;
            } else {
                long count = 0;
                // If the from & to are pointing to different ledgers, then we need to :
                // 1. Add the entries in the ledger pointed by toPosition
                count += toPosition.getEntryId();
                count += toIncluded ? 1 : 0;

                // 2. Add the entries in the ledger pointed by fromPosition
                count += 20 - (fromPosition.getEntryId() + 1);
                count += fromIncluded ? 1 : 0;

                // 3. Add the whole ledgers entries in between
                for (long i = fromPosition.getLedgerId() + 1; i < toPosition.getLedgerId(); i++) {
                    count += 20;
                }

                return count;
            }
        })).when(ledgerMock).getNumberOfEntries(any());
        assertEquals(ledgerMock.getNextValidPosition(PositionImpl.get(1, 0)), PositionImpl.get(1, 1));
        assertEquals(ledgerMock.getNextValidPosition(PositionImpl.get(1, 19)), PositionImpl.get(2, 0));
        assertEquals(ledgerMock.getPreviousPosition(PositionImpl.get(2, 0)), PositionImpl.get(1, 19));
        assertThrows(NullPointerException.class, () -> ledgerMock.getPreviousPosition(PositionImpl.get(0, 0)));
        assertEquals(ledgerMock.getNumberOfEntries(Range.openClosed(
                PositionImpl.get(1, 0), PositionImpl.get(1, 0))), 0);
        assertEquals(ledgerMock.getNumberOfEntries(Range.openClosed(
                PositionImpl.get(1, -1), PositionImpl.get(1, 9))), 10);
        assertEquals(ledgerMock.getNumberOfEntries(Range.openClosed(
                PositionImpl.get(1, 19), PositionImpl.get(2, -1))), 0);
        assertEquals(ledgerMock.getNumberOfEntries(Range.openClosed(
                PositionImpl.get(1, 19), PositionImpl.get(2, 9))), 10);
        assertEquals(ledgerMock.getNumberOfEntries(Range.openClosed(
                PositionImpl.get(1, -1), PositionImpl.get(3, 19))), 60);

        // Add a consumer
        final Consumer consumer1 = mock(Consumer.class);
        doReturn("consumer1").when(consumer1).consumerName();
        when(consumer1.getAvailablePermits()).thenReturn(1000);
        doReturn(true).when(consumer1).isWritable();
        doReturn(channelMock).when(consumer1).sendMessages(anyList(), any(EntryBatchSizes.class),
                any(EntryBatchIndexesAcks.class), anyInt(), anyLong(), anyLong(), any(RedeliveryTracker.class));
        persistentDispatcher.addConsumer(consumer1);

        /*
         On single ledger
         */

        // Expected individuallySentPositions (isp): [(1:-1, 1:8]] (init) -> [(1:-1, 1:9]] (update) -> [] (remove)
        // Expected lastSentPosition (lsp): 1:10 (init) -> 1:10 (remove)
        // upper bound and the new entry are less than initial last sent position
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, -1, 1, 8);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(1, 9, createMessage("test", 1))), true);
        assertTrue(individuallySentPositions.isEmpty());
        assertEquals(persistentDispatcher.getLastSentPosition(), initialLastSentPosition.toString());

        // isp: [(1:-1, 1:9]] -> [(1:-1, 1:10]] -> []
        // lsp: 1:10 -> 1:10
        // upper bound is less than initial last sent position
        // upper bound and the new entry are less than or equal to initial last sent position
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, -1, 1, 9);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(1, 10, createMessage("test", 1))), true);
        assertTrue(individuallySentPositions.isEmpty());
        assertEquals(persistentDispatcher.getLastSentPosition(), initialLastSentPosition.toString());

        // isp: [(1:-1, 1:2], (1:3, 1:4], (1:5, 1:6]] -> [(1:-1, 1:2], (1:3, 1:4], (1:5, 1:6], (1:9, 1:10]] -> []
        // lsp: 1:10 -> 1:10
        // upper bound and the new entry are less than or equal to initial last sent position
        // individually sent positions has multiple ranges
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, -1, 1, 2);
        individuallySentPositions.addOpenClosed(1, 3, 1, 4);
        individuallySentPositions.addOpenClosed(1, 5, 1, 6);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(1, 10, createMessage("test", 1))), true);
        assertTrue(individuallySentPositions.isEmpty());
        assertEquals(persistentDispatcher.getLastSentPosition(), initialLastSentPosition.toString());

        // isp: [(1:-1, 1:10]] -> [(1:-1, 1:11]] -> []
        // lsp: 1:10 -> 1:11
        // upper bound is less than or equal to initial last sent position
        // the new entry is next position of initial last sent position
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, -1, 1, 10);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(1, 11, createMessage("test", 1))), true);
        assertTrue(individuallySentPositions.isEmpty());
        assertEquals(persistentDispatcher.getLastSentPosition(), PositionImpl.get(1, 11).toString());

        // isp: [(1:-1, 1:9]] -> [(1:-1, 1:9], (1:10, 1:11]] -> []
        // lsp: 1:10 -> 1:11
        // upper bound is less than initial last sent position
        // the new entry is next position of initial last sent position
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, -1, 1, 9);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(1, 11, createMessage("test", 1))), true);
        assertTrue(individuallySentPositions.isEmpty());
        assertEquals(persistentDispatcher.getLastSentPosition(), PositionImpl.get(1, 11).toString());

        // isp: [(1:11, 1:15]] -> [(1:10, 1:15]] -> []
        // lsp: 1:10 -> 1:15
        // upper bound is greater than initial last sent position
        // the range doesn't contain next position of initial last sent position
        // the new entry is next position of initial last sent position
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, 11, 1, 15);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(1, 11, createMessage("test", 1))), true);
        assertTrue(individuallySentPositions.isEmpty());
        assertEquals(persistentDispatcher.getLastSentPosition(), PositionImpl.get(1, 15).toString());

        // isp: [(1:11, 1:15]] -> [(1:10, 1:16]] -> []
        // lsp: 1:10 -> 1:16
        // upper bound is greater than initial last sent position
        // the range doesn't contain next position of initial last sent position
        // the new entries contain next position of initial last sent position
        // first of the new entries is less than initial last sent position
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, 11, 1, 15);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(1, 9, createMessage("test", 1)),
                        EntryImpl.create(1, 11, createMessage("test", 2)),
                        EntryImpl.create(1, 16, createMessage("test", 3))), true);
        assertTrue(individuallySentPositions.isEmpty());
        assertEquals(persistentDispatcher.getLastSentPosition(), PositionImpl.get(1, 16).toString());

        // isp: [(1:11, 1:15]] -> [(1:11, 1:15]] -> [(1:11, 1:15]]
        // lsp: 1:10 -> 1:10
        // upper bound is greater than initial last sent position
        // the range doesn't contain next position of initial last sent position
        // the new entry isn't  next position of initial last sent position
        // the range contains the new entry
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, 11, 1, 15);
        expectedIndividuallySentPositions.addOpenClosed(1, 11, 1, 15);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(1, 15, createMessage("test", 1))), true);
        assertEquals(individuallySentPositions.toString(), expectedIndividuallySentPositions.toString());
        assertEquals(persistentDispatcher.getLastSentPosition(), initialLastSentPosition.toString());

        // isp: [(1:11, 1:15]] -> [(1:11, 1:16]] -> [(1:11, 1:16]]
        // lsp: 1:10 -> 1:10
        // upper bound is greater than initial last sent position
        // the range doesn't contain next position of initial last sent position
        // the new entry isn't next position of initial last sent position
        // the range doesn't contain the new entry
        // the new entry is next position of upper bound
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, 11, 1, 15);
        expectedIndividuallySentPositions.addOpenClosed(1, 11, 1, 16);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(1, 16, createMessage("test", 1))), true);
        assertEquals(individuallySentPositions.toString(), expectedIndividuallySentPositions.toString());
        assertEquals(persistentDispatcher.getLastSentPosition(), initialLastSentPosition.toString());

        // isp: [(1:11, 1:15]] -> [(1:11, 1:15], (1:16, 1:17]] -> [(1:11, 1:15], (1:16, 1:17]]
        // lsp: 1:10 -> 1:10
        // upper bound is greater than initial last sent position
        // the range doesn't contain next position of initial last sent position
        // the new entry isn't next position of initial last sent position
        // the range doesn't contain the new entry
        // the new entry isn't next position of upper bound
        // the new entry is same ledger
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, 11, 1, 15);
        expectedIndividuallySentPositions.addOpenClosed(1, 11, 1, 15);
        expectedIndividuallySentPositions.addOpenClosed(1, 16, 1, 17);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(1, 17, createMessage("test", 1))), true);
        assertEquals(individuallySentPositions.toString(), expectedIndividuallySentPositions.toString());
        assertEquals(persistentDispatcher.getLastSentPosition(), initialLastSentPosition.toString());

        /*
        On multiple contiguous ledgers
         */

        // isp: [(1:11, 1:18]] -> [(1:11, 1:18], (2:-1, 2:0]] -> [(1:11, 1:18], (2:-1, 2:0]]
        // lsp: 1:10 -> 1:10
        // upper bound is greater than initial last sent position
        // the range doesn't contain next position of initial last sent position
        // the new entry isn't next position of initial last sent position
        // the range doesn't contain the new entry
        // the new entry isn't next position of upper bound
        // the new entry isn't same ledger
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, 11, 1, 18);
        expectedIndividuallySentPositions.addOpenClosed(1, 11, 1, 18);
        expectedIndividuallySentPositions.addOpenClosed(2, -1, 2, 0);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(2, 0, createMessage("test", 1))), true);
        assertEquals(individuallySentPositions.toString(), expectedIndividuallySentPositions.toString());
        assertEquals(persistentDispatcher.getLastSentPosition(), initialLastSentPosition.toString());

        // isp: [(1:11, 1:19], (2:-1, 2:0]] -> [(1:10, 1:19], (2:-1, 2:0]] -> []
        // lsp: 1:10 -> 2:0
        // upper bound is greater than initial last sent position
        // the range doesn't contain next position of initial last sent position
        // the new entry is next position of initial last sent position
        // the new entry isn't same ledger
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, 11, 1, 19);
        individuallySentPositions.addOpenClosed(2, -1, 2, 0);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(1, 11, createMessage("test", 1))), true);
        assertTrue(individuallySentPositions.isEmpty());
        assertEquals(persistentDispatcher.getLastSentPosition(), PositionImpl.get(2, 0).toString());

        // isp: [(1:11, 1:19], (2:-1, 2:19], (3:-1, 3:0]] -> [(1:10, 1:19], (2:-1, 2:19], (3:-1, 3:0]] -> []
        // lsp: 1:10 -> 3:0
        // upper bound is greater than initial last sent position
        // the range doesn't contain next position of initial last sent position
        // the new entry is next position of initial last sent position
        // the new entry isn't same ledger
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, 11, 1, 19);
        individuallySentPositions.addOpenClosed(2, -1, 2, 19);
        individuallySentPositions.addOpenClosed(3, -1, 3, 0);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(1, 11, createMessage("test", 1))), true);
        assertTrue(individuallySentPositions.isEmpty());
        assertEquals(persistentDispatcher.getLastSentPosition(), PositionImpl.get(3, 0).toString());

        // isp: [(1:11, 1:19], (2:-1, 2:0]] -> [(1:11, 1:19], (2:-1, 2:1]] -> [(1:11, 1:19], (2:-1, 2:1]]
        // lsp: 1:10 -> 1:10
        // upper bound is greater than initial last sent position
        // the range doesn't contain next position of initial last sent position
        // the new entry isn't next position of initial last sent position
        // the new entry isn't same ledger
        assertNull(clearPosition.get());
        individuallySentPositions.addOpenClosed(1, 11, 1, 19);
        individuallySentPositions.addOpenClosed(2, -1, 2, 0);
        expectedIndividuallySentPositions.addOpenClosed(1, 11, 1, 19);
        expectedIndividuallySentPositions.addOpenClosed(2, -1, 2, 1);
        persistentDispatcher.sendMessagesToConsumers(PersistentDispatcherMultipleConsumers.ReadType.Normal,
                Arrays.asList(EntryImpl.create(2, 1, createMessage("test", 1))), true);
        assertEquals(individuallySentPositions.toString(), expectedIndividuallySentPositions.toString());
        assertEquals(persistentDispatcher.getLastSentPosition(), initialLastSentPosition.toString());
    }

    private ByteBuf createMessage(String message, int sequenceId) {
        return createMessage(message, sequenceId, "testKey");
    }

    private EntryImpl createEntry(long ledgerId, long entryId, String message, long sequenceId) {
        return createEntry(ledgerId, entryId, message, sequenceId, "testKey");
    }

    private EntryImpl createEntry(long ledgerId, long entryId, String message, long sequenceId, String key) {
        ByteBuf data = createMessage(message, sequenceId, key);
        EntryImpl entry = EntryImpl.create(ledgerId, entryId, data);
        data.release();
        return entry;
    }

    private ByteBuf createMessage(String message, long sequenceId, String key) {
        MessageMetadata messageMetadata = new MessageMetadata()
                .setSequenceId(sequenceId)
                .setProducerName("testProducer")
                .setPartitionKey(key)
                .setPartitionKeyB64Encoded(false)
                .setPublishTime(System.currentTimeMillis());
        ByteBuf payload = Unpooled.copiedBuffer(message.getBytes(UTF_8));
        ByteBuf byteBuf = serializeMetadataAndPayload(Commands.ChecksumType.Crc32c, messageMetadata, payload);
        payload.release();
        return byteBuf;
    }
}
