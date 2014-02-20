package com.hpcloud.messaging.kafka;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.javaapi.producer.Producer;
import kafka.message.MessageAndMetadata;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

import com.codahale.metrics.health.HealthCheck;
import com.google.common.base.Joiner;

/**
 * Kafka health check implementation. Currently assumes that one and only one message is queued in
 * the topic.
 * 
 * TODO re-work the implementation to scan the topic for the single health check message that was
 * sent, and consume only that message.
 * 
 * @author Jonathan Halterman
 */
public class KafkaHealthCheck extends HealthCheck {
  private final KafkaConfiguration config;
  private final String healthTopic;

  public KafkaHealthCheck(KafkaConfiguration config, String healthTopic) {
    this.config = config;
    this.healthTopic = healthTopic;
  }

  @Override
  protected Result check() throws Exception {
    Producer<String, String> producer = null;
    ConsumerConnector consumer = null;
    ExecutorService executor = null;

    try {
      producer = createProducer();
      consumer = createConsumer();

      // Send
      KeyedMessage<String, String> keyedMessage = new KeyedMessage<>(healthTopic, null, "test");
      producer.send(keyedMessage);

      // Receive
      Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
      topicCountMap.put(healthTopic, Integer.valueOf(1));
      final ConsumerIterator<byte[], byte[]> streamIterator = consumer.createMessageStreams(
          topicCountMap)
          .get(healthTopic)
          .get(0)
          .iterator();

      final Thread mainThread = Thread.currentThread();
      executor = Executors.newSingleThreadExecutor();
      executor.execute(new Runnable() {
        @Override
        public void run() {
          while (streamIterator.hasNext()) {
            MessageAndMetadata<byte[], byte[]> data = streamIterator.next();
            String msg = new String(data.message());
            System.out.println("Received " + msg);
            if (msg.equals("test")) {
              mainThread.interrupt();
              return;
            }
          }
        }
      });

      try {
        Thread.sleep(5000);
      } catch (InterruptedException ignore) {
      }

      return Result.healthy();
    } catch (Exception e) {
      return Result.unhealthy(e);
    } finally {
      if (executor != null)
        executor.shutdownNow();
      if (producer != null)
        producer.close();
      if (consumer != null) {
        consumer.commitOffsets();
        consumer.shutdown();
      }
    }
  }

  Producer<String, String> createProducer() {
    Properties props = new Properties();
    props.put("metadata.broker.list", Joiner.on(',').join(config.brokerUris));
    props.put("serializer.class", "kafka.serializer.StringEncoder");
    props.put("request.required.acks", "1");
    ProducerConfig config = new ProducerConfig(props);
    return new Producer<>(config);
  }

  ConsumerConnector createConsumer() {
    Properties props = new Properties();
    props.put("zookeeper.connect", Joiner.on(',').join(config.zookeeperUris));
    props.put("group.id", "test");
    props.put("auto.offset.reset", "largest");
    ConsumerConfig config = new ConsumerConfig(props);
    return Consumer.createJavaConsumerConnector(config);
  }
}
