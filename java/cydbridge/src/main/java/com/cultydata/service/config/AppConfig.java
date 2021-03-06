package com.cultydata.service.config;


import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.annotation.Transformer;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.integration.transformer.HeaderEnricher;
import org.springframework.integration.transformer.support.ExpressionEvaluatingHeaderValueMessageProcessor;
import org.springframework.integration.transformer.support.HeaderValueMessageProcessor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.StringUtils;

import com.cultydata.service.MqttMessageHandler;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This is the main Application Configuration for Spring Integration
 */
@Configuration
public class AppConfig {

    @Value("${kafka.broker.address}")
    private String brokerAddress;

    @Value("${kafka.zookeeper.connect}")
    private String zookeeperConnect;

    @Value("${kafka.retries}")
    private Integer kafkaRetries;

    @Value("${kafka.batch.size}")
    private Integer kafkaBatchSize;

    @Value("${kafka.linger.ms}")
    private Integer kafkaLingerMs;

    @Value("${kafka.buffer.memory}")
    private Integer kafkaBufferMemory;

    @Value("${kafka.key.serializer}")
    private Class kafkaKeySerializer;

    @Value("${kafka.value.serializer}")
    private Class kafkaValueSerializer;
    
    @Value("${kafka.key.deserializer}")
    private Class kafkaKeyDeserializer;

    @Value("${kafka.value.deserializer}")
    private Class kafkaValueDeserializer;
    
    @Value("${kafka.group.id}")
    private String kafkaGroupId;
    
    @Value("${kafka.enable.auto.commit}")
    private Boolean enableAutoCommit;
    
    @Value("${kafka.auto.commit.interval.ms}")
    private String autoCommitInterval;

    @Value("${kafka.session.timeout.ms}")
    private String sessionTimeout;
    
    @Value("${kafka.listner.concurrency}")
    private Integer listnerConcurrency;
    
    

    @Value("${mqtt.broker.address}")
    private String mqttBrokerAddress;

    @Value("${mqtt.topic}")
    private String[] mqttTopic;

    @Value("${mqtt.clientId}")
    private String mqttClientId;

    @Value("${mqtt.qos}")
    private Integer mqttQos;

    @Value("${mqtt.completionTimeout}")
    private Integer mqttCompletionTimeout;

    @Value("${mqtt.username}")
    private String mqttUsername;

    @Value("${mqtt.password}")
    private String mqttPassword;
    
    @Value("${influxdb.url}")
    private String url;
	
	@Value("${influxdb.username}")
    private String user;
	
	@Value("${influxdb.password}")
    private String pass;
	
	@Value("${influxdb.database}")
    private String db;
	
	@Value("${influxdb.retention-policy}")
    private String retentionPolicy;
	
	@Value("${influxdb.connect-timeout}")
    private String connectTimout;
	
	@Value("${influxdb.read-timeout}")
    private String readtimout;
	
	@Value("${influxdb.write-timeout}")
    private String writeTimout;
    

    /**
     * MQTT Input Channel
     */
    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    /**
     * MQTT Message Enrich Channel
     */
    @Bean MessageChannel mqttMessageEnrichChannel() {
        return new DirectChannel();
    }

    /**
     * Message Producer which connects to MQTT server and consumes the configured mqttTopics
     * And will place it in mqttMessageEnrichChannel
     */
    @Bean
    public MessageProducer inbound() {
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(mqttBrokerAddress, mqttClientId,
                        mqttPahoClientFactory(), mqttTopic);
        adapter.setCompletionTimeout(mqttCompletionTimeout);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(mqttQos);
        adapter.setOutputChannel(mqttMessageEnrichChannel());

        return adapter;
    }

    /**
     * MQTT Client Factory for connecting with MQTT Server with Authentication
     */
    @Bean
    public MqttPahoClientFactory mqttPahoClientFactory() {
        DefaultMqttPahoClientFactory mqttPahoClientFactory = new DefaultMqttPahoClientFactory();
        if (StringUtils.hasText(mqttUsername)) {
            mqttPahoClientFactory.setUserName(mqttUsername);
        }
        if (StringUtils.hasText(mqttPassword)) {
            mqttPahoClientFactory.setPassword(mqttPassword);
        }
        return mqttPahoClientFactory;
    }

    /**
     * This is a Spring Integration Transformer which takes in mqttMessageEnrichChannel coming directly from MQTT Server
     * and enriches the header kafka_topic from mqtt_topic while replacing any '/' with '.' and finally sinks it to mqttInputChannel
     */
    @Bean
    @Transformer(inputChannel="mqttMessageEnrichChannel", outputChannel="mqttInputChannel")
    public HeaderEnricher enrichHeaders() {
        Map<String, HeaderValueMessageProcessor<?>> headersToAdd = new HashMap<String, HeaderValueMessageProcessor<?>>();
        Expression expression = new SpelExpressionParser().parseExpression("headers."+ MqttHeaders.TOPIC +".toString().replaceAll(\'/\', \'.\')");
        headersToAdd.put(KafkaHeaders.TOPIC,
                new ExpressionEvaluatingHeaderValueMessageProcessor<String>(expression, String.class));
        HeaderEnricher enricher = new HeaderEnricher(headersToAdd);
        return enricher;
    }

    /**
     * This is a Spring Integration Transformer which takes in the Header Enriched Messages from mqttInputChannel and
     * Transforms it to a JSON with payload and timestamp properties
     */
    @Transformer(inputChannel = "mqttInputChannel", outputChannel = "mqttTransformedOutputChannel")
    public Message<String> enrichMessage(Message<String> message) throws Exception {
        Map<String, String> jsonMap = new LinkedHashMap<String, String>();
        jsonMap.put("payload", message.getPayload());
        jsonMap.put("timestamp", String.valueOf(new Date().getTime()));
        return MessageBuilder.createMessage(objectMapper().writeValueAsString(jsonMap), message.getHeaders());
    }

    /**
     * This is a Spring Service Activator which will handle the messages available in mqttTransformedOutputChannel with MqttMessageHandler
     */
    @Bean
    @ServiceActivator(inputChannel = "mqttTransformedOutputChannel")
    public MessageHandler handler() {
        return new MqttMessageHandler();
    }

    /**
     * Kafka Template for Kafka Operations
     */
    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<String, String>(producerFactory());
    }
    
    /**
     * Kafka Listner
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConcurrency(this.listnerConcurrency);
        factory.setConsumerFactory(consumerFactory());
        return factory;
    }

    /**
     * Kafka Producer Factory for Kafka Server connection
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.brokerAddress);
        props.put(ProducerConfig.RETRIES_CONFIG, this.kafkaRetries);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, this.kafkaBatchSize);
        props.put(ProducerConfig.LINGER_MS_CONFIG, this.kafkaLingerMs);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, this.kafkaBufferMemory);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, this.kafkaKeySerializer);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, this.kafkaValueSerializer);
        return new DefaultKafkaProducerFactory<String, String>(props);
    }
    
    /**
     * Kafka Consumer Factory
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.brokerAddress);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, this.enableAutoCommit);
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, this.autoCommitInterval);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, this.sessionTimeout);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, this.kafkaKeyDeserializer);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, this.kafkaValueDeserializer);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, this.kafkaGroupId);
        return new DefaultKafkaConsumerFactory<String, String>(props);
    }
    
    /**
     * InfluxDB Factory for influxDB connection
     */
	
    @Bean
    public InfluxDB influxDB() {
	    	InfluxDB influxDB = InfluxDBFactory.connect(this.url, this.user,  this.pass);
	    	influxDB.setDatabase(this.db);
	    	influxDB.setRetentionPolicy(this.retentionPolicy);
	    	influxDB.setLogLevel(InfluxDB.LogLevel.BASIC);
	    	return influxDB;
    }

    /**
     * JSON Object Mapper
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
