package com.lab.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 文档信息配置。
 * 文档地址：http://localhost:8091/swagger-ui/index.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI troubleLabOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Trouble Lab 生产故障演练实验室")
                .description("内置 11 种生产常见故障场景（CPU 飙满 / OOM / 泄漏 / 死锁 / 线程暴涨 / 堆外内存 / 慢接口 / GC 压力 / 日志狂写），"
                        + "每个端点即一个可反复触发的故障，配合 README 排查手册练习 jstack / jmap / jstat / Arthas 取证。")
                .version("1.0.0"));
    }
}
