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
package org.apache.pulsar.proxy.server;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import lombok.Cleanup;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.websocket.data.ProducerMessage;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class ProxyServiceStarterTest extends MockedPulsarServiceBaseTest {

    public static final String[] ARGS = new String[]{"-c", "./src/test/resources/proxy.conf"};

    protected ProxyServiceStarter serviceStarter;
    protected String serviceUrl;

    @Override
    @BeforeClass
    protected void setup() throws Exception {
        internalSetup();
        serviceStarter = new ProxyServiceStarter(ARGS, null, true);
        serviceStarter.getConfig().setBrokerServiceURL(pulsar.getBrokerServiceUrl());
        serviceStarter.getConfig().setBrokerWebServiceURL(pulsar.getWebServiceAddress());
        serviceStarter.getConfig().setWebServicePort(Optional.of(0));
        serviceStarter.getConfig().setServicePort(Optional.of(0));
        serviceStarter.getConfig().setWebSocketServiceEnabled(true);
        serviceStarter.getConfig().setBrokerProxyAllowedTargetPorts("*");
        serviceStarter.start();
        serviceUrl = serviceStarter.getProxyService().getServiceUrl();
    }

    @Override
    @AfterClass(alwaysRun = true)
    protected void cleanup() throws Exception {
        internalCleanup();
        serviceStarter.close();
    }

    private String computeWsBasePath() {
        return String.format("ws://localhost:%d/ws", serviceStarter.getServer().getListenPortHTTP().get());
    }


    @Test
    public void testProducer() throws Exception {
        @Cleanup
        PulsarClient client = PulsarClient.builder().serviceUrl(serviceUrl)
                .build();

        @Cleanup
        Producer<byte[]> producer = client.newProducer()
                .topic("persistent://sample/test/local/websocket-topic")
                .create();

        for (int i = 0; i < 10; i++) {
            producer.send("test".getBytes());
        }
    }

    @Test
    public void testProduceAndConsumeMessageWithWebsocket() throws Exception {
        HttpClient producerClient = new HttpClient();
        WebSocketClient producerWebSocketClient = new WebSocketClient(producerClient);
        producerWebSocketClient.start();
        MyWebSocket producerSocket = new MyWebSocket();
        String produceUri = computeWsBasePath() + "/producer/persistent/sample/test/local/websocket-topic";
        Future<Session> producerSession = producerWebSocketClient.connect(producerSocket, URI.create(produceUri));

        ProducerMessage produceRequest = new ProducerMessage();
        produceRequest.setContext("context");
        produceRequest.setPayload(Base64.getEncoder().encodeToString("my payload".getBytes()));

        HttpClient consumerClient = new HttpClient();
        WebSocketClient consumerWebSocketClient = new WebSocketClient(consumerClient);
        consumerWebSocketClient.start();
        MyWebSocket consumerSocket = new MyWebSocket();
        String consumeUri = computeWsBasePath() + "/consumer/persistent/sample/test/local/websocket-topic/my-sub";
        Future<Session> consumerSession = consumerWebSocketClient.connect(consumerSocket, URI.create(consumeUri));
        consumerSession.get().getRemote().sendPing(ByteBuffer.wrap("ping".getBytes()));
        producerSession.get().getRemote().sendString(ObjectMapperFactory.getMapper().writer().writeValueAsString(produceRequest));
        assertTrue(consumerSocket.getResponse().contains("ping"));
        ProducerMessage message = ObjectMapperFactory.getMapper().reader().readValue(consumerSocket.getResponse(), ProducerMessage.class);
        assertEquals(new String(Base64.getDecoder().decode(message.getPayload())), "my payload");
    }

    @WebSocket
    public static class MyWebSocket extends WebSocketAdapter implements WebSocketPingPongListener {

        ArrayBlockingQueue<String> incomingMessages = new ArrayBlockingQueue<>(10);

        @Override
        public void onWebSocketText(String message) {
            incomingMessages.add(message);
        }

        @Override
        public void onWebSocketClose(int i, String s) {
        }

        @Override
        public void onWebSocketConnect(Session session) {
        }

        @Override
        public void onWebSocketError(Throwable throwable) {
        }

        @Override
        public void onWebSocketPing(ByteBuffer payload) {
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload) {
            incomingMessages.add(BufferUtil.toDetailString(payload));
        }

        public String getResponse() throws InterruptedException {
            return incomingMessages.take();
        }
    }

    @Test
    public void testProxyClientAuthentication() throws Exception {
        final Consumer<ProxyConfiguration> initConfig = (proxyConfig) -> {
            proxyConfig.setBrokerServiceURL(pulsar.getBrokerServiceUrl());
            proxyConfig.setBrokerWebServiceURL(pulsar.getWebServiceAddress());
            proxyConfig.setWebServicePort(Optional.of(0));
            proxyConfig.setServicePort(Optional.of(0));
            proxyConfig.setWebSocketServiceEnabled(true);
            proxyConfig.setBrokerProxyAllowedTargetPorts("*");
            proxyConfig.setClusterName(configClusterName);
        };



        ProxyServiceStarter serviceStarter = new ProxyServiceStarter(ARGS, null, true);
        initConfig.accept(serviceStarter.getConfig());
        // ProxyServiceStarter will throw an exception when Authentication#start is failed
        serviceStarter.getConfig().setBrokerClientAuthenticationPlugin(ExceptionAuthentication1.class.getName());
        try {
            serviceStarter.start();
            fail("ProxyServiceStarter should throw an exception when Authentication#start is failed");
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("ExceptionAuthentication1#start"));
            assertTrue(serviceStarter.getProxyClientAuthentication() instanceof ExceptionAuthentication1);
        }

        serviceStarter = new ProxyServiceStarter(ARGS, null, true);
        initConfig.accept(serviceStarter.getConfig());
        // ProxyServiceStarter will throw an exception when Authentication#start and Authentication#close are failed
        serviceStarter.getConfig().setBrokerClientAuthenticationPlugin(ExceptionAuthentication2.class.getName());
        try {
            serviceStarter.start();
            fail("ProxyServiceStarter should throw an exception when Authentication#start and Authentication#close are failed");
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("ExceptionAuthentication2#start"));
            assertTrue(serviceStarter.getProxyClientAuthentication() instanceof ExceptionAuthentication2);
        }
    }

    public static class ExceptionAuthentication1 implements Authentication {

        @Override
        public String getAuthMethodName() {
            return "org.apache.pulsar.proxy.server.ProxyConfigurationTest.ExceptionAuthentication1";
        }

        @Override
        public void configure(Map<String, String> authParams) {
            // no-op
        }

        @Override
        public void start() throws PulsarClientException {
            throw new PulsarClientException("ExceptionAuthentication1#start");
        }

        @Override
        public void close() throws IOException {
            // no-op
        }
    }

    public static class ExceptionAuthentication2 implements Authentication {

        @Override
        public String getAuthMethodName() {
            return "org.apache.pulsar.proxy.server.ProxyConfigurationTest.ExceptionAuthentication2";
        }

        @Override
        public void configure(Map<String, String> authParams) {
            // no-op
        }

        @Override
        public void start() throws PulsarClientException {
            throw new PulsarClientException("ExceptionAuthentication2#start");
        }

        @Override
        public void close() throws IOException {
            throw new IOException("ExceptionAuthentication2#close");
        }
    }

}
