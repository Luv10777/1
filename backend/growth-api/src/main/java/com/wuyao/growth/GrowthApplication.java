package com.wuyao.growth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 梧曜星枢 · 后端主干。
 *
 * 同一个 jar，两条启动命令：
 *   api    : java -jar growth-api.jar --growth.worker.enabled=false
 *   worker : java -jar growth-api.jar --growth.worker.enabled=true  --server.port=8090
 *
 * 两个进程不互相调接口，只通过 tasks 表协作。
 */
@SpringBootApplication
@EnableScheduling
public class GrowthApplication {
    public static void main(String[] args) {
        SpringApplication.run(GrowthApplication.class, args);
    }
}
