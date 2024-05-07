package org.apache.pulsar.shell;

import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.testng.annotations.Test;

import java.util.concurrent.CompletableFuture;

public class MyTest {

    @Test
    public void testName() throws Exception {
        try (var client = PulsarClient.builder()
                .serviceUrl("pulsar://localhost:6500")
                .build();) {
            CompletableFuture.runAsync(() -> {
                try {

                    try (Producer<byte[]> producer = client.newProducer().topic("tt")
                            .create();) {
                        producer.send("test".getBytes());
                    }

                } catch (Throwable re) {
                    System.err.println(re);
                }

            });

            boolean ack = true;
            int ackCount = 0;
            try (var consumer = client.newConsumer()
                    .topic("tt")
                    .subscriptionName("s")
                    .subscribe();) {
                while (true) {
                    Message<byte[]> message = consumer.receive();
                    if (ack) {
                        consumer.acknowledge(message);
                        ackCount++;
                    }
                    ack = !ack;
                    if (ackCount % 1000 == 0) {
                        System.out.println("acked " + ackCount);
                    }
                }
            }


        }


    }
}
