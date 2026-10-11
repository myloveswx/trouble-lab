# 🔬 Trouble Lab — 生产故障演练实验室

一个 Spring Boot 3.3 应用，内置 **11 种生产环境常见故障场景**，用于系统性练习线上问题排查能力。

> 每触发一个场景，先自己观察现象、动手取证，再看本文档对应章节的「排查思路」对答案。

---

## 快速开始

```
双击 start.cmd   （JDK21 + -Xmx512m + MaxMetaspaceSize=128m，端口 8091）
```

或手动启动：

```bash
D:\workspace\.toolchain\jdk-21.0.12.1+1\bin\java.exe -Xms256m -Xmx512m -XX:MaxMetaspaceSize=128m -jar target\trouble-lab-1.0.0.jar --server.port=8091
```

- 状态面板：`GET http://localhost:8091/issue/status`（堆使用/线程数/运行中的故障任务）
- **Swagger 文档**：`http://localhost:8091/swagger-ui/index.html`（所有故障场景可视化触发，点一下就打故障 🎯）
- 触发故障：`GET http://localhost:8091/issue/<场景名>`
- 停止可控故障：`GET http://localhost:8091/issue/reset`（OOM/死锁无法恢复，需重启进程）

```bash
# 触发示例（GET，直接浏览器打开链接即可）
curl http://localhost:8091/issue/cpu-loop
curl http://localhost:8091/issue/status
curl http://localhost:8091/issue/reset
```

## 场景总览

| # | 端点 | 故障 | 现象 | 可否 reset |
|---|------|------|------|-----------|
| 1 | `GET /issue/cpu-loop` | 死循环吃满 1 核 | 单核 100% | ✅ |
| 2 | `GET /issue/cpu-multi` | 多线程 CPU 飙满 | 进程 CPU 接近 100% | ✅ |
| 3 | `GET /issue/oom-heap` | 堆内存溢出 | 请求 500，日志 OutOfMemoryError | ❌（可反复触发） |
| 4 | `GET /issue/leak` | 缓慢内存泄漏 | 堆使用持续上涨，几分钟后 OOM | ✅ |
| 5 | `GET /issue/metaspace` | 元空间溢出 | Metaspace 持续上涨 | ✅ |
| 6 | `GET /issue/deadlock` | 线程死锁 | 两个线程永久 BLOCKED | ❌（需重启） |
| 7 | `GET /issue/thread-flood` | 线程数暴涨 | 线程数 +3000，资源耗尽 | ✅（已建线程无法回收） |
| 8 | `GET /issue/offheap` | 堆外内存增长 | 堆正常但进程 RSS 上涨 | ✅ |
| 9 | `GET /issue/slow?seconds=60` | 慢接口占满线程池 | 整个服务假死 | —（等待返回） |
| 10 | `GET /issue/gc-pressure` | 频繁 GC | CPU 中 GC 占比高、STW 频繁 | ✅ |
| 11 | `GET /issue/log-flood` | 日志狂写 | 磁盘被快速写满（上限 500MB） | ✅ |

---

## 排查工具箱（JDK 自带）

先用 `jps -l` 找到进程 PID，下面统一用 `<pid>` 代指。

| 工具 | 用途 | 常用命令 |
|------|------|---------|
| `jps` | 找 Java 进程 | `jps -l` |
| `jstack` | 线程栈快照（CPU 高/死锁/假死） | `jstack <pid> > stack.txt` |
| `jmap` | 堆快照/对象直方图（内存问题） | `jmap -histo:live <pid>`、`jmap -dump:live,format=b,file=heap.hprof <pid>` |
| `jstat` | GC 统计（GC 问题） | `jstat -gcutil <pid> 1000 10`（每秒 1 次共 10 次） |
| `jcmd` | 万能诊断口 | `jcmd <pid> VM.flags`、`jcmd <pid> Thread.print`、`jcmd <pid> GC.class_stats` |
| 任务管理器/procexp | 找吃 CPU 的线程 TID | 详情页 → 线程 → 按 CPU 排序 |

> 分析 hprof 可用 Eclipse MAT、VisualVM 或 IDEA 内置 Profiler。进阶神器：Arthas（`dashboard` / `thread -n 3` / `thread -b` / `profiler start`）。

---

## 各场景排查思路（先自己动手，再看答案）

### 1️⃣ CPU 飙满（单线程 / 多线程）

**现象**：进程 CPU 高（单核 100% 或整机接近满载），接口变慢。

**定位步骤**：
1. `jps -l` 找到 PID；
2. 任务管理器 → 详情 → 该进程 → 线程标签按 CPU 排序，记下最高线程的 **TID**（十进制）；
3. TID 转十六进制（如 12345 → 0x3039）；
4. `jstack <pid> > stack.txt`，在栈文件里搜 `0x3039`，定位到具体类与行号 —— 本项目是 `IssueController` 的 `cpuLoop` 方法；
5. 多线程版没有单一线程，改为在 stack.txt 中统计 `fault-cpu-multi-*` 线程的 RUNNABLE 数量即可确认是「自己代码在空转」。

**结论套路**：高 CPU + 线程 RUNNABLE + 栈停在业务代码 → 死循环/高频计算/正则回溯/序列化风暴。

### 2️⃣ 堆内存溢出（Java heap space）

**现象**：请求 500，控制台 `java.lang.OutOfMemoryError: Java heap space`。

**定位步骤**：
1. `jstat -gcutil <pid> 1000` 观察触发前后 Old 区使用率（Old 打满、FGC 无效回收是典型特征）；
2. `jmap -histo:live <pid> | head -20` 看 `[B`（byte 数组）实例占用是否异常；
3. 生产环境标准动作：加 JVM 参数 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs/`，OOM 时自动落 hprof，用 MAT 看 **Leak Suspects** 报告。

**结论套路**：OOM 发生在哪个线程、堆里什么对象最多、谁引用着它（GC Roots 链路）。

### 3️⃣ 缓慢内存泄漏（最难的一种）

**现象**：不报错、不重启就没事；堆使用量随时间**只涨不跌**，几天/几小时后 OOM 或频繁 Full GC。

**定位步骤**：
1. `jstat -gcutil <pid> 1000 30`：Old 区使用率单调上涨、FGC 后不回落 = 疑似泄漏；
2. 间隔 1 分钟连续两次 `jmap -histo:live <pid> | head -20`，对比哪些类的实例数/字节数在涨 —— 本项目是 `byte[]`；
3. `jmap -dump:live,format=b,file=heap.hprof <pid>` + MAT OQL 查 `byte[]` 的引用链，最终会指到 `IssueController` 的静态字段 `leakMap`；
4. Arthas 替代方案：`heapdump` + MAT，或 `vmtool --action getInstances --className java.util.concurrent.ConcurrentHashMap`。

**结论套路**：静态集合、ThreadLocal 没 remove、监听器/回调未注销、连接未关闭 —— 四大泄漏源头。

### 4️⃣ 元空间溢出（Metaspace）

**现象**：类数量暴涨，`OutOfMemoryError: Metaspace`（建议先给进程加上 `-XX:MaxMetaspaceSize=128m` 更容易复现）。

**定位步骤**：
1. `jstat -gcutil <pid>` 观察 MC（Metaspace Capacity）持续上涨；
2. `jstack` 看是否有线程在生成类/反射/CGLIB 相关栈；
3. `jcmd <pid> GC.class_stats | more`（需 `-XX:+UnlockDiagnosticVMOptions`）或 `jmap -clstats <pid>` 看哪个 ClassLoader 加载了海量类 —— 动态代理/CGLIB/脚本引擎（Groovy、JSP 热部署）是惯犯。

### 5️⃣ 线程死锁

**现象**：部分业务永远卡住不返回，CPU/内存都正常 —— 最容易被忽视。

**定位步骤**：
1. `jstack <pid> > stack.txt`；
2. 文件末尾 JDK 会**直接给出死锁报告**：`Found one Java-level deadlock`，并画出「线程 A 等待线程 B 持有的锁」的循环等待图；
3. 也可用 `jcmd <pid> Thread.print`；Arthas 一键排查：`thread -b`（直接找出阻塞其他线程最多的线程）。

**结论套路**：多个 BLOCKED 线程互相等待 `0x0000...` 锁对象 → 检查锁获取顺序是否一致、是否该用 `tryLock` 带超时。

### 6️⃣ 线程数暴涨

**现象**：进程线程数几千，内存涨、创建新请求失败或系统整体变卡。

**定位步骤**：
1. 任务管理器/procexp 看线程数，或 `jstack <pid> | findstr /c:"\"" | find /c /v ""` 统计线程总数；
2. `jstack <pid> > stack.txt`，观察线程名的分布（本项目全是 `flood-*`）——真实事故里常见 `pool-*-thread-*` 暴涨，说明线程池在无界创建（`Executors.newCachedThreadPool()`、`@Async` 默认配置）；
3. 看这些线程在干嘛：WAITING (sleeping)？还是在抢锁/等 IO？

**结论套路**：线程池参数无界 / 每请求 new Thread / 递归创建线程。

### 7️⃣ 堆外内存增长

**现象**：**堆内存正常，但进程物理内存（RSS）持续上涨**，最终被系统 OOM kill 或 Native OOM —— 最迷惑人的一种。

**定位步骤**：
1. 先用 `jstat -gcutil` 确认堆没涨 → 排除堆问题；
2. 任务管理器看进程「工作集」远大于 `-Xmx` → 堆外嫌疑；
3. JVM 参数加 `-XX:NativeMemoryTracking=summary` 后：`jcmd <pid> VM.native_memory summary`，重点看 Internal / Other 区块是否上涨（DirectByteBuffer 计入 Internal）；
4. 常见元凶：NIO DirectBuffer、Netty 池化内存、压缩/解压流、JNI 库、Metaspace/线程栈（每个线程默认 1MB 栈）。

### 8️⃣ 慢接口拖垮线程池（服务假死）

**现象**：某接口慢（等下游），之后**整个应用所有接口都无响应**，但 CPU 很低。

**定位步骤**：
1. `jstack <pid> > stack.txt`；
2. 搜 `http-nio-8091-exec`，会看到 Tomcat 200 个工作线程几乎全部 `TIMED_WAITING (sleeping/parking)` 在慢调用处；
3. 结论：工作线程被慢请求占满，新请求在 accept 队列排队 → 表现为全服务假死；
4. 治理：下游超时（connect/read timeout）、熔断降级、线程池隔离、异步化（WebClient/虚拟线程）。

**结论套路**：低 CPU + 全接口超时 + 栈里大量线程在等同一个下游 → 典型线程池被拖垮。

### 9️⃣ GC 压力（频繁 GC）

**现象**：CPU 有一定占用但不高，接口偶发抖动；GC 日志刷屏。

**定位步骤**：
1. `jstat -gcutil <pid> 1000 20`：YGC 每秒多次、Eden 区锯齿极陡；
2. 加 `-Xlog:gc*` 看 GC 原因与耗时；
3. `jmap -histo:live | head` 看是什么对象在高速分配（本项目 `byte[]`）；
4. 治理：对象复用/缓冲、调大 Young 区、排查大对象分配（大 JSON、大集合拷贝）。

### 🔟 日志狂写（磁盘 IO / 磁盘满）

**现象**：磁盘被快速吃满（本项目封顶 500MB，看 `logs/flood.log` 体积），严重时整机卡顿、其他服务写日志失败。

**定位步骤**：
1. 找到疯涨的文件：排查日志目录文件大小增速；
2. `jstack` 找写文件的线程栈；
3. 治理：日志限流/采样（重复告警合并）、异步 Appender + 队列上限、磁盘告警、logrotate。

---

## 练习路线建议

1. **入门**：cpu-loop → deadlock → oom-heap（现象明显，练工具手感）
2. **进阶**：leak → metaspace → thread-flood（需要对比快照、看趋势）
3. **高手**：offheap → slow → gc-pressure（现象迷惑，考综合判断）
4. 每次练习输出三件套：**现象描述 → 取证过程（贴 jstack/jstat 关键输出）→ 根因与修复方案**
5. 想练 Arthas：`curl -O https://arthas.aliyun.com/arthas-boot.jar` 后 `java -jar arthas-boot.jar` 附着本进程

## 注意事项

- 所有场景都只影响**本进程**：OOM 只杀触发线程、日志狂写封顶 500MB、堆外每 512MB 清理一次，放心演练；
- `deadlock` 与 `thread-flood` 的线程 reset 后无法回收，彻底恢复请重启进程；
- 请勿在生产环境运行本项目 😄
