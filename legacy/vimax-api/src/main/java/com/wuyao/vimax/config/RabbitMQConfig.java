package com.wuyao.vimax.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置
 *
 * Phase 1.9: 异步状态机、重试、幂等和死信
 */
@Configuration
public class RabbitMQConfig {

    // 队列名称
    public static final String GENERATION_TASK_QUEUE = "generation.task.queue";
    public static final String GENERATION_TASK_DLQ = "generation.task.dlq";

    // 交换机名称
    public static final String GENERATION_TASK_EXCHANGE = "generation.task.exchange";
    public static final String GENERATION_TASK_DLX = "generation.task.dlx";

    // 路由键
    public static final String GENERATION_TASK_ROUTING_KEY = "generation.task";
    public static final String GENERATION_TASK_DLQ_ROUTING_KEY = "generation.task.dlq";

    /**
     * 主队列（带死信配置）
     */
    @Bean
    public Queue generationTaskQueue() {
        return QueueBuilder.durable(GENERATION_TASK_QUEUE)
            .withArgument("x-dead-letter-exchange", GENERATION_TASK_DLX)
            .withArgument("x-dead-letter-routing-key", GENERATION_TASK_DLQ_ROUTING_KEY)
            .withArgument("x-message-ttl", 3600000) // 1小时消息TTL
            .build();
    }

    /**
     * 死信队列
     */
    @Bean
    public Queue generationTaskDLQ() {
        return QueueBuilder.durable(GENERATION_TASK_DLQ).build();
    }

    /**
     * 主交换机
     */
    @Bean
    public DirectExchange generationTaskExchange() {
        return new DirectExchange(GENERATION_TASK_EXCHANGE);
    }

    /**
     * 死信交换机
     */
    @Bean
    public DirectExchange generationTaskDLX() {
        return new DirectExchange(GENERATION_TASK_DLX);
    }

    /**
     * 主队列绑定
     */
    @Bean
    public Binding generationTaskBinding(Queue generationTaskQueue, DirectExchange generationTaskExchange) {
        return BindingBuilder.bind(generationTaskQueue)
            .to(generationTaskExchange)
            .with(GENERATION_TASK_ROUTING_KEY);
    }

    /**
     * 死信队列绑定
     */
    @Bean
    public Binding generationTaskDLQBinding(Queue generationTaskDLQ, DirectExchange generationTaskDLX) {
        return BindingBuilder.bind(generationTaskDLQ)
            .to(generationTaskDLX)
            .with(GENERATION_TASK_DLQ_ROUTING_KEY);
    }

    /**
     * JSON 消息转换器
     */
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate 配置
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    /**
     * 消费者容器工厂（支持重试）
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConcurrentConsumers(3);
        factory.setMaxConcurrentConsumers(10);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false); // 失败后不重新入队，进入死信

        return factory;
    }
}
