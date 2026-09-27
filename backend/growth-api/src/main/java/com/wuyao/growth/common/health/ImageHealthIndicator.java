package com.wuyao.growth.common.health;

import com.wuyao.growth.common.gateway.AiGateway;
import com.wuyao.growth.common.gateway.ModelAlias;
import com.wuyao.growth.common.task.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 图片服务健康检查
 */
@Component
@RequiredArgsConstructor
public class ImageHealthIndicator implements HealthIndicator {
    private final TaskRepository taskRepo;
    private final AiGateway gateway;

    @Override
    public Health health() {
        Health.Builder builder = new Health.Builder();

        try {
            // 检查队列积压
            long pending = taskRepo.countByStatusAndQueue("PENDING", "IMAGE");
            long running = taskRepo.countByStatusAndQueue("RUNNING", "IMAGE");

            builder.withDetail("queue_pending", pending);
            builder.withDetail("queue_running", running);

            if (pending > 500) {
                return builder.down()
                    .withDetail("message", "任务队列积压严重")
                    .build();
            }

            // 检查图片模型配置
            boolean configured = gateway.configured(ModelAlias.IMAGE_PRIMARY);
            builder.withDetail("image_model_configured", configured);

            if (!configured) {
                return builder.down()
                    .withDetail("message", "图片模型未配置")
                    .build();
            }

            return builder.up().build();

        } catch (Exception e) {
            return builder.down()
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
