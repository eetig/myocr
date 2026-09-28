package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 单据识别服务。
 *
 * <p>职责：图片 → 结构化数据（单据类型 / 单据号 / 日期 / 行项目，每字段带置信度）。
 * 不落库、不依赖存储，图片字节由调用方传入。
 */
@EnableDiscoveryClient
@SpringBootApplication
public class MyOcrApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyOcrApplication.class, args);
    }
}
