package com.lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 生产故障演练实验室
 * 模拟多种线上故障场景，用于练习排查能力：
 * CPU 飙满、堆 OOM、内存泄漏、元空间溢出、死锁、线程暴涨、
 * 堆外内存、慢接口拖垮线程池、GC 压力、日志狂写
 */
@SpringBootApplication
public class TroubleLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(TroubleLabApplication.class, args);
    }
}
