package com.lab.web;

import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 故障触发入口。
 * 每个场景对应一个 HTTP 端点，可反复演练；非致命场景可通过 /issue/reset 停止。
 */
@RestController
@RequestMapping("/issue")
public class IssueController {

    /** 运行中的故障线程注册表：场景名 -> 线程列表 */
    private final Map<String, List<Thread>> runningTasks = new ConcurrentHashMap<>();

    /** 各任务的停止标志：场景名 -> 标志列表（与线程一一对应） */
    private final Map<String, List<java.util.concurrent.atomic.AtomicBoolean>> stopFlags = new ConcurrentHashMap<>();

    /** 内存泄漏演示用的静态 Map（模拟"只进不出"的缓存） */
    private static final Map<String, byte[]> leakMap = new ConcurrentHashMap<>();
    private static final AtomicLong leakCounter = new AtomicLong();

    /** 死锁演示用的两把锁 */
    private static final Object LOCK_A = new Object();
    private static final Object LOCK_B = new Object();

    /** 日志狂写目标文件 */
    private static final Path FLOOD_FILE = Path.of("logs", "flood.log");

    // ==================== 场景清单 ====================

    @GetMapping("/status")
    public Map<String, Object> status() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heapUsedMB", (rt.totalMemory() - rt.freeMemory()) / 1048576);
        m.put("heapMaxMB", rt.maxMemory() / 1048576);
        m.put("threadCount", Thread.activeCount());
        m.put("runningFaultTasks", new TreeMap<>(snapshotRunning()));
        return m;
    }

    /**
     * 场景 1：单线程死循环，吃满一个核。
     * 排查点：top 看到某线程 100% CPU -> jstack 找到对应 RUNNABLE 线程栈。
     */
    @GetMapping("/cpu-loop")
    public ResponseEntity<String> cpuLoop() {
        return startTask("cpu-loop", stopFlag -> {
            long x = 0;
            while (!stopFlag.get()) {
                // 纯计算死循环：Math.sqrt 防止 JIT 直接优化掉
                x += (long) Math.sqrt(x + 1);
                if (x < 0) x = 0;
            }
        });
    }

    /**
     * 场景 2：多线程死循环，CPU 飙满（按核数 * 2 开线程）。
     * 排查点：整个进程 CPU 接近 100%，jstack 导出后统计 RUNNABLE 线程。
     */
    @GetMapping("/cpu-multi")
    public ResponseEntity<String> cpuMulti(@RequestParam(defaultValue = "0") int threads) {
        int n = threads > 0 ? threads : Runtime.getRuntime().availableProcessors() * 2;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            startTaskQuiet("cpu-multi-" + i, stopFlag -> {
                long x = 0;
                while (!stopFlag.get()) {
                    x += (long) Math.sqrt(x + 1);
                    if (x < 0) x = 0;
                }
            });
            sb.append("cpu-multi-").append(i).append(' ');
        }
        return ResponseEntity.ok("已启动 " + n + " 个 CPU 满载线程: " + sb + "\n用 /issue/reset 停止");
    }

    /**
     * 场景 3：堆内存溢出（java.lang.OutOfMemoryError: Java heap space）。
     * 一次请求内疯狂往 List 塞大对象，直接把堆撑爆。
     * OOM 只杀死当前请求线程，进程仍存活，可继续触发。
     */
    @GetMapping("/oom-heap")
    public ResponseEntity<String> oomHeap() {
        try {
            List<byte[]> hold = new ArrayList<>();
            long added = 0;
            while (true) {
                hold.add(new byte[10 * 1024 * 1024]); // 每次塞 10MB
                added += 10;
            }
        } catch (OutOfMemoryError e) {
            return ResponseEntity.status(500).body("触发堆 OOM: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage() + "\n观察 JVM 日志与 GC 行为\n");
        }
    }

    /**
     * 场景 4：缓慢内存泄漏（模拟缓存只进不出）。
     * 每秒往静态 Map 塞 1MB 数据且从不清理，几分钟后必然 OOM。
     * 排查点：jmap -histo:live 反复对比，byte[] 实例数持续上涨。
     */
    @GetMapping("/leak")
    public ResponseEntity<String> leak() {
        return startTask("leak", stopFlag -> {
            while (!stopFlag.get()) {
                leakMap.put("leak-" + leakCounter.incrementAndGet(), new byte[1024 * 1024]);
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        });
    }

    /**
     * 场景 5：元空间溢出（OutOfMemoryError: Metaspace）。
     * 用 CGLIB 不带缓存的疯狂动态生成类。建议 JVM 加 -XX:MaxMetaspaceSize=128m 后演练。
     * 排查点：jstat -gcmetacapacity 或 jcmd GC.class_stats 看类数量暴涨。
     */
    @GetMapping("/metaspace")
    public ResponseEntity<String> metaspace() {
        return startTask("metaspace", stopFlag -> {
            long count = 0;
            while (!stopFlag.get()) {
                Enhancer enhancer = new Enhancer();
                enhancer.setSuperclass(MetaspaceVictim.class);
                enhancer.setUseCache(false); // 关键：不使用缓存，每次都生成新类
                enhancer.setCallback((MethodInterceptor) (obj, method, args, proxy) -> proxy.invokeSuper(obj, args));
                enhancer.create();
                count++;
            }
        });
    }

    /** CGLIB 增强的目标类 */
    public static class MetaspaceVictim {
        public String hello() { return "hello"; }
    }

    /**
     * 场景 6：线程死锁。
     * 线程 A 先锁 LOCK_A 再要 LOCK_B；线程 B 相反 -> 互相等待。
     * 排查点：jstack 直接输出 "Found one Java-level deadlock"。
     */
    @GetMapping("/deadlock")
    public ResponseEntity<String> deadlock() {
        Thread t1 = new Thread(() -> {
            synchronized (LOCK_A) {
                sleep(500); // 保证对方先拿到另一把锁
                synchronized (LOCK_B) { }
            }
        }, "deadlock-thread-A");
        Thread t2 = new Thread(() -> {
            synchronized (LOCK_B) {
                sleep(500);
                synchronized (LOCK_A) { }
            }
        }, "deadlock-thread-B");
        register("deadlock", t1, new java.util.concurrent.atomic.AtomicBoolean());
        register("deadlock", t2, new java.util.concurrent.atomic.AtomicBoolean());
        t1.start();
        t2.start();
        return ResponseEntity.ok("死锁线程已启动: deadlock-thread-A / deadlock-thread-B\n"
                + "用 jstack <pid> 可直接看到死锁报告；/issue/reset 只会移出注册表（线程本身无法被唤醒）\n");
    }

    /**
     * 场景 7：线程数暴涨（unable to create new native thread）。
     * 启动 N 个永久睡眠的线程（默认 3000），耗尽线程资源。
     * 排查点：jstack 文件巨大、线程名高度重复；ps/任务管理器看线程数飙升。
     */
    @GetMapping("/thread-flood")
    public ResponseEntity<String> threadFlood(@RequestParam(defaultValue = "3000") int count) {
        return startTask("thread-flood", stopFlag -> {
            int created = 0;
            try {
                for (int i = 0; i < count && !stopFlag.get(); i++) {
                    Thread t = new Thread(() -> sleep(Long.MAX_VALUE), "flood-" + i);
                    t.setDaemon(true);
                    t.start();
                    created++;
                }
            } catch (Throwable e) {
                System.err.println("[thread-flood] 创建到第 " + created + " 个线程时失败: " + e);
            }
            // 结束后保持本线程存活，让注册表里能看到该任务
            while (!stopFlag.get()) sleep(1000);
        });
    }

    /**
     * 场景 8：堆外内存持续增长（DirectByteBuffer）。
     * 堆看着正常但 RSS 持续上涨，直到 OOM killer / Native OOM。
     * 排查点：NMT（-XX:NativeMemoryTracking=summary）、pmap 对比。
     */
    @GetMapping("/offheap")
    public ResponseEntity<String> offheap() {
        return startTask("offheap", stopFlag -> {
            List<ByteBuffer> keep = new ArrayList<>();
            long total = 0;
            while (!stopFlag.get()) {
                keep.add(ByteBuffer.allocateDirect(1024 * 1024)); // 每秒 1MB 堆外
                total++;
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                // 保留引用但每 512MB 清一次，防止无限吃光内存导致整机卡死
                if (keep.size() >= 512) { keep.clear(); System.gc(); }
            }
        });
    }

    /**
     * 场景 9：慢接口拖垮线程池。
     * 本次请求睡眠 60 秒（模拟下游超慢），Tomcat 工作线程(默认200)被逐渐占满，
     * 后续所有接口无响应 -> 表现为"整个服务假死"。
     * 排查点：jstack 中大量 http-nio-*-exec 线程 PARKED 在 sleep/park。
     */
    @GetMapping("/slow")
    public ResponseEntity<String> slow(@RequestParam(defaultValue = "60") int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ResponseEntity.ok("慢接口终于返回了\n");
    }

    /**
     * 场景 10：GC 压力（频繁 Young/Full GC）。
     * 高速分配 1MB 大对象且不保留 -> 内存使用率锯齿、GC 日志刷屏、CPU 被 GC 吃掉一部分。
     * 排查点：jstat -gcutil <pid> 1000 观察 YGC/FGC 频率。
     */
    @GetMapping("/gc-pressure")
    public ResponseEntity<String> gcPressure() {
        return startTask("gc-pressure", stopFlag -> {
            while (!stopFlag.get()) {
                byte[] junk = new byte[1024 * 1024];
                junk[0] = 1; // 防止被完全优化
            }
        });
    }

    /**
     * 场景 11：日志狂写（磁盘 IO 打满 / 磁盘被写满）。
     * 每次写约 1KB 一行到 logs/flood.log，写满约 500MB 自动停，可随时 reset。
     */
    @GetMapping("/log-flood")
    public ResponseEntity<String> logFlood() {
        return startTask("log-flood", stopFlag -> {
            long written = 0;
            try {
                Files.createDirectories(FLOOD_FILE.getParent());
                while (!stopFlag.get() && written < 500L * 1024 * 1024) {
                    String line = "2026-10-11 00:00:00 ERROR c.lab.flood - simulate massive error logging, "
                            + "padding padding padding padding padding padding padding padding padding\n";
                    Files.writeString(FLOOD_FILE, line, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    written += line.getBytes(StandardCharsets.UTF_8).length;
                    if (written % (1024 * 1024) < 128) {
                        try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                    }
                }
            } catch (IOException e) {
                System.err.println("[log-flood] 写入失败: " + e);
            }
        });
    }

    /**
     * 停止所有可控故障场景（中断注册表里的线程并清空）。
     * 注意：OOM、死锁无法"恢复"，死锁线程会永久挂起（可重启进程）。
     */
    @GetMapping("/reset")
    public ResponseEntity<String> reset() {
        StringBuilder sb = new StringBuilder();
        stopFlags.forEach((name, flags) -> flags.forEach(f -> f.set(true)));
        runningTasks.forEach((name, threads) -> {
            threads.forEach(Thread::interrupt);
            sb.append(name).append('(').append(threads.size()).append(") ");
        });
        int n = runningTasks.size();
        runningTasks.clear();
        stopFlags.clear();
        return ResponseEntity.ok("已停止 " + n + " 组故障任务: " + sb + "\n提示: 死锁线程 interrupt 不会恢复，彻底清理请重启进程\n");
    }

    // ==================== 工具方法 ====================

    private interface FaultLoop {
        void run(java.util.concurrent.atomic.AtomicBoolean stopFlag) throws Exception;
    }

    private ResponseEntity<String> startTask(String name, FaultLoop loop) {
        boolean ok = startTaskQuiet(name, loop);
        if (!ok) {
            return ResponseEntity.ok("场景 [" + name + "] 已在运行中，先 /issue/reset 或查看 /issue/status\n");
        }
        return ResponseEntity.ok("故障场景 [" + name + "] 已启动。\n"
                + "排查练习: 1) 观察现象  2) jps/jcmd 定位进程  3) jstack/jmap/jstat 取证  4) 得出结论\n"
                + "停止: GET /issue/reset ；状态: GET /issue/status\n");
    }

    private synchronized boolean startTaskQuiet(String name, FaultLoop loop) {
        List<Thread> existing = runningTasks.get(name);
        if (existing != null && !existing.isEmpty()
                && existing.stream().anyMatch(t -> t.isAlive() && !t.isInterrupted())) {
            return false;
        }
        java.util.concurrent.atomic.AtomicBoolean stopFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                loop.run(stopFlag);
            } catch (OutOfMemoryError e) {
                System.err.println("[" + name + "] OutOfMemoryError: " + e.getMessage());
            } catch (Throwable e) {
                System.err.println("[" + name + "] 异常退出: " + e);
            } finally {
                List<Thread> list = runningTasks.get(name);
                if (list != null) list.remove(Thread.currentThread());
                List<java.util.concurrent.atomic.AtomicBoolean> flags = stopFlags.get(name);
                if (flags != null) flags.remove(stopFlag);
            }
        }, "fault-" + name);
        t.setDaemon(true);
        register(name, t, stopFlag);
        t.start();
        return true;
    }

    private void register(String name, Thread t, java.util.concurrent.atomic.AtomicBoolean stopFlag) {
        runningTasks.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>()).add(t);
        stopFlags.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>()).add(stopFlag);
    }

    private Map<String, Integer> snapshotRunning() {
        Map<String, Integer> m = new TreeMap<>();
        runningTasks.forEach((k, v) -> {
            long alive = v.stream().filter(Thread::isAlive).count();
            if (alive > 0) m.put(k, (int) alive);
        });
        return m;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
