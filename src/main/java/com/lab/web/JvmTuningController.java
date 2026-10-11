package com.lab.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ============================================================================
 * JVM 优化案例模块
 * ============================================================================
 * 与 /issue 的「故障触发」不同，本模块聚焦【配置不当导致的性能问题】，
 * 每个案例对应一类生产上调优过的真实问题，注释里给出完整分析链路：
 *   生产故事 → 现象 → 根因分析 → JVM 参数方案 → 代码层方案
 *
 * 通用排查前提：先拿到"体检报告"再动手调优 ——
 *   1. jcmd <pid> VM.flags                       —— 当前生效的 JVM 参数
 *   2. jstat -gcutil <pid> 1000                   —— GC 频率与各区使用率
 *   3. -Xlog:gc*                                  —— GC 原因与耗时（调优的眼睛）
 *   4. jmap -heap <pid>                           —— 堆分代配置
 * ============================================================================
 */
@Tag(name = "JVM 优化案例", description = "配置不当类性能问题的演示与调优分析：新生代压力 / 过早晋升 / System.gc() 陷阱 / 当前 JVM 配置体检")
@RestController
@RequestMapping("/jvm")
public class JvmTuningController {

    /** 各演示线程的停止标志：名字 -> flags */
    private final Map<String, List<AtomicBoolean>> stopFlags = new ConcurrentHashMap<>();

    // =========================================================================
    // 案例 0：JVM 配置体检 —— 一切优化的起点
    // =========================================================================

    /**
     * 【案例 0：先体检再调优】
     *
     * 生产故事：很多"调优"是拍脑袋抄参数，连当前 JVM 用什么 GC 器、堆多大都不知道。
     * 调优第一定律：先量化现状，再谈优化，否则是玄学。
     *
     * 分析要点：
     * - GC 器是什么？(JDK8 默认 Parallel，JDK9+ 默认 G1) —— 决定了参数体系
     * - 堆多大、Xms 是否等于 Xmx？(不等会产生堆伸缩抖动)
     * - 显式参数里有没有历史遗留的坑？(如 G1 下误配 CMS 参数)
     */
    @Operation(summary = "案例0：当前 JVM 配置体检",
            description = "返回 GC 器、堆配置、显式 JVM 参数、GC 次数 —— 调优前先看这个，量化现状")
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        m.put("gcCollectors", ManagementFactory.getGarbageCollectorMXBeans()
                .stream().map(GarbageCollectorMXBean::getName).toList());
        Map<String, Object> heap = new LinkedHashMap<>();
        heap.put("maxMB", rt.maxMemory() / 1048576);
        heap.put("currentMB", rt.totalMemory() / 1048576);
        heap.put("usedMB", (rt.totalMemory() - rt.freeMemory()) / 1048576);
        m.put("heap", heap);
        m.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        m.put("javaVersion", System.getProperty("java.version"));
        return m;
    }

    // =========================================================================
    // 案例 1：新生代过小 —— 频繁 Young GC
    // =========================================================================

    /**
     * 【案例 1：新生代太小，短命对象把 YGC 逼疯】
     *
     * 生产故事：一个订单服务 -Xmx4g 但被"好心人"设了 -Xmn256m（或 SurvivorRatio 调得离谱）。
     * 服务每秒创建几万个请求对象/DTO/日志字符串 —— 这些都是"朝生夕死"的对象，
     * 本应在 Eden 里分配、YGC 时直接回收，成本极低。
     * 但新生代太小 → Eden 几秒钟就满 → YGC 频率飙升到每秒多次 → 每次 STW 虽短（几 ms），
     * 但架不住频率高：CPU 被 GC 吃掉一大块，接口 P99 毛刺。
     *
     * 现象（jstat -gcutil <pid> 1000）：
     *   YGC 列每秒 +3~5 次，FGC 不多，但接口毛刺明显。
     *   -Xlog:gc* 显示每次 YGC 都在 Eden 满（Allocation Failure）。
     *
     * 根因分析：
     *   短命对象是分代假设的受益者 —— "绝大多数对象朝生夕死"。
     *   Eden 越大，YGC 间隔越长（单次 YGC 耗时和存活对象数相关、和 Eden 大小弱相关），
     *   所以"大 Eden + 低频 YGC"优于"小 Eden + 高频 YGC"。
     *
     * JVM 参数方案：
     *   - 堆富余时让 JVM 自己分（G1 自适应），别手动 -Xmn
     *   - G1 场景用 -XX:MaxGCPauseMillis=50 让它自动扩新生代
     *   - Parallel 场景 -XX:NewRatio=2（新生代占 1/3）是常见起点
     *   - 提高吞吐可用 -XX:GCTimeRatio
     *
     * 代码层方案：
     *   - 减少无谓分配：日志字符串拼接改占位符、复用 buffer、对象池（慎用）
     *   - 大小合适的批量处理，避免一请求一整页 List 的放大分配
     */
    @Operation(summary = "案例1：新生代压力（短命对象高速分配）",
            description = "高速分配立即可丢的对象模拟新生代压力。配合 jstat -gcutil 观察 YGC 频率；想复现'新生代过小'可用 -Xmn32m 重启后触发本场景对比")
    @GetMapping("/young-churn")
    public ResponseEntity<String> youngChurn() {
        return start("young-churn", stop -> {
            while (!stop.get()) {
                // 短命对象：分配后立刻失去引用，只在下一次 YGC 前存活 —— 模拟请求 DTO / 临时集合
                for (int i = 0; i < 5000; i++) {
                    byte[] shortLived = new byte[512];
                    shortLived[0] = 1;
                }
                Thread.sleep(5); // 模拟请求间隔：分配速率约 400MB/s
            }
        });
    }

    // =========================================================================
    // 案例 2：过早晋升 / 大对象 —— 老年代被短命对象污染
    // =========================================================================

    /**
     * 【案例 2：对象过早晋升，Full GC 提前报到】
     *
     * 生产故事：报表服务突然 FGC 频繁。jstat 观察：Old 区使用率锯齿式上涨，
     * FGC 每小时几十次，且 FGC 后 Old 能回收掉大部分 —— 说明老年代里装的是"本不该来的短命对象"。
     *
     * 根因分析（为什么短命对象会进老年代）：
     *   1. Survivor 太小：YGC 时存活对象装不下 To-Survivor → 直接晋升（promotion failed）
     *   2. 动态年龄判断：Survivor 中同龄对象总和超过 Survivor 一半，该龄及以上全部提前晋升
     *   3. 大对象：超过 -XX:PretenureSizeThreshold 直接在老年代分配（避免在新生代来回复制）
     *      —— G1 下超过 region 一半即为 Humongous 对象，直接进老年代且可能引发连续空间问题
     *   本案例演示第 3 种：每轮分配 4MB 大对象（G1 默认 region 2MB 的一半以上）
     *
     * 现象：
     *   -Xlog:gc* 出现 "humongous allocation" / Old 区被大 byte[] 占据
     *   FGC 前老年代增长率与业务对象大小不成比例（存的是临时大对象）
     *
     * JVM 参数方案：
     *   - G1：-XX:G1HeapRegionSize=16m（让"大对象"不再 Humongous）
     *   - 增大新生代/Survivor：-XX:SurvivorRatio=8 → 调小数值等于放大 Survivor
     *   - Parallel：-XX:PretenureSizeThreshold=8m 以下的长寿命阈值要结合实际对象画像
     *
     * 代码层方案（优先级更高）：
     *   - 拆分大对象：一次 50MB 的 List 查询改分页/流式处理（根本不产生大对象）
     *   - 大 buffer 复用（池化），避免频繁申请释放
     *   - 导出/下载类业务改流式响应，别在内存里攒完整文件
     */
    @Operation(summary = "案例2：过早晋升与大对象（污染老年代）",
            description = "每轮分配 4MB 中期存活对象模拟大对象/过早晋升；用 jstat 观察老年代增长与 FGC 触发，-Xlog:gc* 可看到 humongous allocation")
    @GetMapping("/premature-promotion")
    public ResponseEntity<String> prematurePromotion() {
        return start("premature-promotion", stop -> {
            List<byte[]> survivors = new ArrayList<>();
            while (!stop.get()) {
                // 4MB 大对象 + 约 30 秒存活期：G1 下超过 region 一半 → Humongous → 直接进老年代
                survivors.add(new byte[4 * 1024 * 1024]);
                // 只保留最近 ~120 个（约 480MB），模拟"用完即可丢但活得比一次 YGC 长"的对象
                if (survivors.size() > 120) {
                    survivors.subList(0, 60).clear();
                }
                Thread.sleep(250); // 分配速率约 16MB/s
            }
        });
    }

    // =========================================================================
    // 案例 3：System.gc() 陷阱 —— 第三方库里的隐形 Full GC
    // =========================================================================

    /**
     * 【案例 3：代码里藏着的 System.gc()】
     *
     * 生产故事：服务每隔几分钟一次"莫名的 Full GC"，自家代码确认没人调 gc。
     * 最后在依赖里揪出来：老版本 NIO 的 DirectByteBuffer 分配失败时会 System.gc() 兜底重试；
     * RMI 的分布式 GC 默认每小时也会调；某些序列化/缓存库"贴心"地在操作后主动 GC。
     *
     * 现象：
     *   -Xlog:gc* 显示 FGC 的 cause 是 System.gc()，而不是 Ergonomics/heap dump 等正常原因；
     *   GC 线程栈（jstack 里 "VM Thread"）能看到调用来源（-Xlog:gc* + heap dump 可定位类）。
     *
     * 根因分析：
     *   System.gc() 默认触发一次完整的 stop-the-world Full GC，
     *   在 G1/ZGC 时代这是"降级打击"：把精心调好的低延迟 GC 一次性打回解放前。
     *
     * JVM 参数方案（一行解决，生产必配）：
     *   -XX:+DisableExplicitGC   —— 让所有 System.gc() 调用变成空操作
     *   ⚠️ 注意副作用：如果 NIO 堆外内存依赖 System.gc() 回收 DirectByteBuffer，
     *   禁用后堆外可能堆积 → 必须同时配 -XX:MaxDirectMemorySize=512m 设上限兜底，
     *   或者升级 JDK（新版本 DirectByteBuffer 已改用 Cleaner 机制，不依赖 System.gc()）
     *
     * 代码层方案：
     *   - 升级触发 System.gc() 的依赖版本
     *   - 扫描依赖 jar：grep -r "System.gc\|Runtime.getRuntime().gc" （或 arthas watch）
     */
    @Operation(summary = "案例3：System.gc() 陷阱（第三方库隐形 Full GC）",
            description = "每秒显式触发 System.gc() 模拟依赖库里的隐形 FGC；用 -Xlog:gc* 观察 cause=System.gc，再配 -XX:+DisableExplicitGC 体验一键根治")
    @GetMapping("/system-gc-trap")
    public ResponseEntity<String> systemGcTrap() {
        return start("system-gc-trap", stop -> {
            while (!stop.get()) {
                // 模拟依赖库里的兜底 GC 调用：每次都是一次完整 STW Full GC
                System.gc();
                Thread.sleep(1000);
            }
        });
    }

    /** 停止本模块所有演示线程 */
    @Operation(summary = "停止所有 JVM 优化演示场景")
    @GetMapping("/stop")
    public ResponseEntity<String> stop() {
        int n = 0;
        for (List<AtomicBoolean> flags : stopFlags.values()) {
            flags.forEach(f -> f.set(true));
            n += flags.size();
        }
        stopFlags.clear();
        return ResponseEntity.ok("已停止 " + n + " 个 JVM 演示任务\n");
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private interface TuningLoop {
        void run(AtomicBoolean stop) throws Exception;
    }

    private synchronized ResponseEntity<String> start(String name, TuningLoop loop) {
        List<AtomicBoolean> existing = stopFlags.get(name);
        if (existing != null && !existing.isEmpty()
                && existing.stream().anyMatch(f -> !f.get())) {
            return ResponseEntity.ok("演示 [" + name + "] 已在运行，先 GET /jvm/stop\n");
        }
        AtomicBoolean stop = new AtomicBoolean(false);
        stopFlags.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>()).add(stop);
        Thread t = new Thread(() -> {
            try {
                loop.run(stop);
            } catch (Throwable e) {
                System.err.println("[" + name + "] 异常退出: " + e);
            } finally {
                List<AtomicBoolean> flags = stopFlags.get(name);
                if (flags != null) flags.remove(stop);
            }
        }, "jvm-demo-" + name);
        t.setDaemon(true);
        t.start();
        return ResponseEntity.ok("JVM 演示 [" + name + "] 已启动\n"
                + "观察: jstat -gcutil <pid> 1000  |  GC日志: -Xlog:gc*\n"
                + "停止: GET /jvm/stop  |  配置体检: GET /jvm/info\n"
                + "详细分析见 JvmTuningController.java 源码注释\n");
    }
}
