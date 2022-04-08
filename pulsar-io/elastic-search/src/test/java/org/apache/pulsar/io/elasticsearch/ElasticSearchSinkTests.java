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
package org.apache.pulsar.io.elasticsearch;

import co.elastic.clients.transport.ElasticsearchTransport;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.apache.pulsar.client.api.schema.GenericObject;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.client.api.schema.GenericSchema;
import org.apache.pulsar.client.impl.MessageImpl;
import org.apache.pulsar.client.impl.schema.generic.GenericJsonSchema;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.KeyValueEncodingType;
import org.apache.pulsar.common.schema.SchemaType;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.elasticsearch.client.BulkProcessor;
import org.apache.pulsar.io.elasticsearch.client.RestClient;
import org.apache.pulsar.io.elasticsearch.client.elastic.ElasticSearchJavaRestClient;
import org.apache.pulsar.io.elasticsearch.client.opensearch.OpenSearchHighLevelRestClient;
import org.apache.pulsar.io.elasticsearch.data.UserProfile;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.opensearch.client.RestHighLevelClient;
import org.powermock.reflect.Whitebox;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public abstract class ElasticSearchSinkTests extends ElasticSearchTestBase {

    private static ElasticsearchContainer container;

    public ElasticSearchSinkTests(String elasticImageName) {
        super(elasticImageName);
    }

    @Mock
    protected Record<GenericObject> mockRecord;

    @Mock
    protected SinkContext mockSinkContext;
    protected Map<String, Object> map;
    protected ElasticSearchSink sink;

    static Schema kvSchema;
    static Schema<UserProfile> valueSchema;
    static GenericSchema<GenericRecord> genericSchema;
    static GenericRecord userProfile;

    @BeforeMethod(alwaysRun = true)
    public final void initBeforeClass() {
        if (container != null) {
            return;
        }
        container = createElasticsearchContainer();
        container.start();

        valueSchema = Schema.JSON(UserProfile.class);
        genericSchema = GenericJsonSchema.of(valueSchema.getSchemaInfo());
        userProfile = genericSchema.newRecordBuilder()
                .set("name", "bob")
                .set("userName", "boby")
                .set("email", "bob@bob.com")
                .build();
        kvSchema = Schema.KeyValue(Schema.STRING, genericSchema, KeyValueEncodingType.SEPARATED);

    }

    @AfterClass(alwaysRun = true)
    public static void closeAfterClass() {
        container.close();
        container = null;
    }

    @SuppressWarnings("unchecked")
    @BeforeMethod
    public final void setUp() throws Exception {
        map = new HashMap<String, Object> ();
        map.put("elasticSearchUrl", "http://"+container.getHttpHostAddress());
        map.put("schemaEnable", "true");
        sink = new ElasticSearchSink();

        mockRecord = mock(Record.class);
        mockSinkContext = mock(SinkContext.class);

        when(mockRecord.getKey()).thenAnswer(new Answer<Optional<String>>() {
            long sequenceCounter = 0;
            public Optional<String> answer(InvocationOnMock invocation) throws Throwable {
                return Optional.of( "key-" + sequenceCounter++);
            }});


        when(mockRecord.getValue()).thenAnswer(new Answer<GenericObject>() {
            public GenericObject answer(InvocationOnMock invocation) throws Throwable {
                return new GenericObject() {
                    @Override
                    public SchemaType getSchemaType() {
                        return SchemaType.KEY_VALUE;
                    }

                    @Override
                    public Object getNativeObject() {
                        return new KeyValue<String, GenericObject>((String) userProfile.getField("name"), userProfile);
                    }
                };
            }});

        when(mockRecord.getSchema()).thenAnswer(new Answer<Schema<KeyValue<String,UserProfile>>>() {
            public Schema<KeyValue<String,UserProfile>> answer(InvocationOnMock invocation) throws Throwable {
                return kvSchema;
            }});
    }

    @AfterMethod(alwaysRun = true)
    public final void tearDown() throws Exception {
        if (sink != null)
            sink.close();
    }

    @Test(enabled = true, expectedExceptions = IllegalArgumentException.class)
    public final void invalidIndexNameTest() throws Exception {
        map.put("indexName", "myIndex");
        sink.open(map, mockSinkContext);
    }

    @Test(enabled = true)
    public final void createIndexTest() throws Exception {
        map.put("indexName", "test-index");
        sink.open(map, mockSinkContext);
        send(1);
    }

    @Test(enabled = true)
    public final void singleRecordTest() throws Exception {
        map.put("indexName", "test-index");
        sink.open(map, mockSinkContext);
        send(1);
        verify(mockRecord, times(1)).ack();
    }

    @Test(enabled = true)
    public final void send100Test() throws Exception {
        map.put("indexName", "test-index");
        sink.open(map, mockSinkContext);
        send(100);
        verify(mockRecord, times(100)).ack();
    }

    @Test(enabled = true)
    public final void sendNoSchemaTest() throws Exception {

        when(mockRecord.getMessage()).thenAnswer(new Answer<Optional<Message<String>>>() {
            @Override
            public Optional<Message<String>> answer(InvocationOnMock invocation) throws Throwable {
                final MessageImpl mock = mock(MessageImpl.class);
                when(mock.getData()).thenReturn("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
                return Optional.of(mock);
            }
        });

        when(mockRecord.getKey()).thenAnswer(new Answer<Optional<String>>() {
            public Optional<String> answer(InvocationOnMock invocation) throws Throwable {
                return null;
            }});


        when(mockRecord.getValue()).thenAnswer(new Answer<String>() {
            public String answer(InvocationOnMock invocation) throws Throwable {
                return "hello";
            }});

        when(mockRecord.getSchema()).thenAnswer(new Answer<Schema>() {
            public Schema answer(InvocationOnMock invocation) throws Throwable {
                return Schema.STRING;
            }});

        map.put("indexName", "test-index");
        map.put("schemaEnable", "false");
        sink.open(map, mockSinkContext);
        sink.write(mockRecord);
        verify(mockRecord, times(1)).ack();
    }

    @Test(enabled = true)
    public final void sendKeyIgnoreSingleField() throws Exception {
        final String index = "testkeyignore";
        map.put("indexName", index);
        map.put("keyIgnore", "true");
        map.put("primaryFields", "name");
        sink.open(map, mockSinkContext);
        send(1);
        verify(mockRecord, times(1)).ack();
        assertEquals(sink.getElasticsearchClient().getRestClient().totalHits(index), 1L);

        if (elasticImageName.equals(ELASTICSEARCH_8)) {
            final ElasticSearchJavaRestClient restClient = (ElasticSearchJavaRestClient)
                    sink.getElasticsearchClient().getRestClient();
            assertEquals(restClient.search(index).hits().hits().get(0).id(), "bob");
        } else {
            final OpenSearchHighLevelRestClient restClient = (OpenSearchHighLevelRestClient)
                    sink.getElasticsearchClient().getRestClient();
            assertEquals(restClient.search(index).getHits().getHits()[0].getId(), "bob");
        }
    }

    @Test(enabled = true)
    public final void sendKeyIgnoreMultipleFields() throws Exception {
        final String index = "testkeyignore2";
        map.put("indexName", index);
        map.put("keyIgnore", "true");
        map.put("primaryFields", "name,userName");
        sink.open(map, mockSinkContext);
        send(1);
        verify(mockRecord, times(1)).ack();
        assertEquals(sink.getElasticsearchClient().getRestClient().totalHits(index), 1L);
        if (elasticImageName.equals(ELASTICSEARCH_8)) {
            final ElasticSearchJavaRestClient restClient = (ElasticSearchJavaRestClient)
                    sink.getElasticsearchClient().getRestClient();
            assertEquals(restClient.search(index).hits().hits().get(0).id(), "[\"bob\",\"boby\"]");
        } else {
            final OpenSearchHighLevelRestClient restClient = (OpenSearchHighLevelRestClient)
                    sink.getElasticsearchClient().getRestClient();
            assertEquals(restClient.search(index).getHits().getHits()[0].getId(), "[\"bob\",\"boby\"]");
        }
    }

    protected final void send(int numRecords) throws Exception {
        for (int idx = 0; idx < numRecords; idx++) {
            sink.write(mockRecord);
        }
    }

    static class MockRecordNullValue implements Record<GenericObject> {
        @Override
        public Schema getSchema() {
            return  kvSchema;
        }

        @Override
        public Optional<String> getKey() {
            return Optional.of((String)userProfile.getField("name"));
        }

        @Override
        public GenericObject getValue() {
            return new GenericObject() {
                @Override
                public SchemaType getSchemaType() {
                    return SchemaType.KEY_VALUE;
                }

                @Override
                public Object getNativeObject() {
                    return new KeyValue<>((String)userProfile.getField("name"), null);
                }
            };
        }
    }

    @Test(enabled = true)
    public void testStripNullNodes() throws Exception {
        map.put("stripNulls", true);
        sink.open(map, mockSinkContext);
        GenericRecord genericRecord = genericSchema.newRecordBuilder()
                .set("name", null)
                .set("userName", "boby")
                .set("email", null)
                .build();
        String json = sink.stringifyValue(valueSchema, genericRecord);
        assertEquals(json, "{\"userName\":\"boby\"}");
    }

    @Test(enabled = true)
    public void testKeepNullNodes() throws Exception {
        map.put("stripNulls", false);
        sink.open(map, mockSinkContext);
        GenericRecord genericRecord = genericSchema.newRecordBuilder()
                .set("name", null)
                .set("userName", "boby")
                .set("email", null)
                .build();
        String json = sink.stringifyValue(valueSchema, genericRecord);
        assertEquals(json, "{\"name\":null,\"userName\":\"boby\",\"email\":null}");
    }

    @Test(enabled = true, expectedExceptions = PulsarClientException.InvalidMessageException.class)
    public void testNullValueFailure() throws Exception {
        String index = "testnullvaluefail";
        map.put("indexName", index);
        map.put("keyIgnore", "false");
        map.put("nullValueAction", "FAIL");
        sink.open(map, mockSinkContext);
        MockRecordNullValue mockRecordNullValue = new MockRecordNullValue();
        sink.write(mockRecordNullValue);
    }

    @Test(enabled = true)
    public void testNullValueIgnore() throws Exception {
        testNullValue(ElasticSearchConfig.NullValueAction.IGNORE);
    }

    @Test(enabled = true)
    public void testNullValueDelete() throws Exception {
        testNullValue(ElasticSearchConfig.NullValueAction.DELETE);
    }

    public void testNullValue(ElasticSearchConfig.NullValueAction action) throws Exception {
        String index = "testnullvalue" + action.toString().toLowerCase(Locale.ROOT);
        map.put("indexName", index);
        map.put("keyIgnore", "false");
        map.put("nullValueAction", action.name());
        sink.open(map, mockSinkContext);
        send(1);
        verify(mockRecord, times(1)).ack();
        sink.write(new Record<GenericObject>() {
            @Override
            public Schema<GenericObject> getSchema() {
                return kvSchema;
            }

            @Override
            public Optional<String> getKey() {
                return Optional.of((String)userProfile.getField("name"));
            }

            @Override
            public GenericObject getValue() {
                return new GenericObject() {
                    @Override
                    public SchemaType getSchemaType() {
                        return SchemaType.KEY_VALUE;
                    }

                    @Override
                    public Object getNativeObject() {
                        return new KeyValue<String, GenericRecord>((String)userProfile.getField("name"), userProfile);
                    }
                };
            }
        });
        assertEquals(sink.getElasticsearchClient().getRestClient().totalHits(index), 1L);
        sink.write(new MockRecordNullValue());
        assertEquals(sink.getElasticsearchClient().getRestClient().totalHits(index), action.equals(ElasticSearchConfig.NullValueAction.DELETE) ? 0L : 1L);
        assertNull(sink.getElasticsearchClient().irrecoverableError.get());
    }

    @Test
    public void testCloseClient() throws Exception {
        final ElasticSearchSink sink = new ElasticSearchSink();
        map.put("bulkEnabled", true);
        try {
            sink.open(map, mockSinkContext);
            final ElasticSearchClient elasticSearchClient = spy(sink.getElasticsearchClient());
            final RestClient restClient = spy(elasticSearchClient.getRestClient());
            if (restClient instanceof ElasticSearchJavaRestClient) {
                ElasticSearchJavaRestClient client = (ElasticSearchJavaRestClient) restClient;
                final BulkProcessor bulkProcessor = spy(restClient.getBulkProcessor());
                final ElasticsearchTransport transport = spy(client.getTransport());

                Whitebox.setInternalState(client, "transport", transport);
                Whitebox.setInternalState(client, "bulkProcessor", bulkProcessor);
                Whitebox.setInternalState(elasticSearchClient, "client", restClient);
                Whitebox.setInternalState(sink, "elasticsearchClient", elasticSearchClient);
                sink.close();
                verify(transport).close();
                verify(bulkProcessor).close();
                verify(client).close();
                verify(restClient).close();

            } else {
                OpenSearchHighLevelRestClient client = (OpenSearchHighLevelRestClient) restClient;

                final org.opensearch.action.bulk.BulkProcessor internalBulkProcessor = spy(
                        client.getInternalBulkProcessor());
                final RestHighLevelClient restHighLevelClient = spy(client.getClient());

                Whitebox.setInternalState(client, "client", restHighLevelClient);
                Whitebox.setInternalState(client, "internalBulkProcessor", internalBulkProcessor);
                Whitebox.setInternalState(elasticSearchClient, "client", restClient);
                Whitebox.setInternalState(sink, "elasticsearchClient", elasticSearchClient);
                sink.close();
                verify(internalBulkProcessor).awaitClose(Mockito.anyLong(), Mockito.any(TimeUnit.class));
                verify(client).close();
                verify(restClient).close();
            }
        } finally {
            sink.close();
        }
    }
}
