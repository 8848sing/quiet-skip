# 静跳 1.9.0 验证报告

[English](verification-1.9.0.md) | [简体中文](verification-1.9.0.zh-CN.md)

验证日期：2026-11-06

## 构建产物

- 包名：`com.jingjump.app`；版本 `1.9.0`；versionCode `18`
- minSdk `26` / targetSdk `35` / compileSdk `35`
- APK 大小：`935,581` 字节（1.8.4 为 `931,678`，+3,903 字节，来自 TCP 中继与热路径改写；测试代码不进 APK）
- APK SHA-256：`56CBA90371923B4B59E8FB41DD6B0C4EB26B0B4799EAA5287DCE97E9B4246DC4`
- 源码包 SHA-256：`481B01F39E4FDCC81533EBEDE577170080732AD04C5C28EB266570325DEA2A1A`
- 签名证书 SHA-256：`4440FC1AA0D6BA29D6BDE33233EC9A65DE4CD3457371D01F72641969DACFA3A8`（与 1.7.0 起全部版本一致，可覆盖安装）
- 签名方案：v2 + v3（与 1.8.4 产物完全一致，v1 未启用，minSdk 26 无需 v1）；单签名者；zipalign 4 字节对齐通过；badging 复核 `1.9.0 (18)`

## 构建环境

JDK Temurin `17.0.10+7`；Gradle `8.10.2`；AGP `8.7.3`；SDK Platform `35`；Build Tools `34.0.0`；Robolectric `4.14.1`（API 28/35）。

> 环境备注：本机 Gradle wrapper 无法下载分发包（JDK 信任链 + 非 ASCII 用户目录），因此直接使用本机 Gradle 8.10.2 安装目录执行；依赖解析走网络一次后进入缓存。构建脚本本身未改动。

## 自动化测试

```text
OK (113 tests)
```

1.8.4 的 87 项全部保留通过，本版新增 26 项：

| 测试类 | 数量 | 覆盖点 |
| --- | --- | --- |
| `TcpDnsRelayTest` | 6 | 握手（SYN→SYN-ACK）、带长度前缀的查询与应答、3000 字节应答分 3 段且序号连续、重传只 ACK 不重复回源、无握手的数据包回 RST、16 条连接上限后拒绝新握手、FIN 回 FIN-ACK 并回收连接 |
| `DnsPacketHardeningTest` | 5 | `buildTcpPacket` 伪首部校验和正确、截断应答保留问题段并置 TC=1、`ageTtls` 对每条记录扣减 TTL、缓存命中按已存时长返回剩余 TTL、超长应答不进入缓存 |
| `MatchingFastPathTest` | 9 | 域名规范化快路径与 IDN 慢路径结果一致（含大小写、尾点、超长、国际域名）、启发式 `scoreNormalized` 与 `score` 逐条一致、安全业务域不被学习、`compact` 清理空格/全角空格/零宽字符、视图 ID 规范化、compact 重载与字符串重载等价、安全策略预压缩标记词 |
| `RuleStoreCacheTest` | 4 | 保存后立即可读、文档变化后缓存失效、未知包返回默认规则（含 B 站默认防误触）、偏好被清空后回落默认 |
| `SettingsCounterTest` | 2 | 批量计数刷新前后读数精确、重置同时清空未落盘部分 |

## 本版发现并修复的缺陷

1. **TCP DNS 不可用（1.8.0 起的文档错误）**
   - 服务从未回 SYN-ACK：SYN 被静默丢弃，客户端重试 SYN 直到超时；而 `DnsPacket.buildTcpReply()` 用客户端序号当自身序号，即使走到也构造不出合法报文。
   - 后果：上游应答被截断（TC=1）时，系统解析器改用 TCP 重查必然失败，用户看到的是"网页一直转圈"。
   - 修复：新增 `TcpDnsRelay`，完整处理握手、序号、按序回注与 RST 回退；同时删除 `buildReply()` 里的 TCP 分支，避免再出现"看似支持"的死代码。

2. **超长应答回 SERVFAIL**
   - 旧逻辑：`upstream.length > 1472` 直接回 SERVFAIL。SERVFAIL 的含义是"服务器故障"，客户端会据此认为解析器不可用，而不是改用 TCP。
   - 修复：按 RFC 1035 回"仅含问题段 + TC=1"的截断应答；IPv6 的载荷上限也从 1472 修正为 1452（原值对 IPv6 会生成 1508 字节的越界报文）。

3. **单个畸形包会终止整个 DNS 过滤**
   - 旧逻辑：`readTunnel()` 的 `catch (Throwable)` 包住整个读循环，任一包解析抛异常即置错误状态并 `shutdown()`。
   - 修复：逐包隔离，坏包丢弃、其余继续。

4. **"广告证据"窗口自我续期**
   - 旧逻辑：缓存证据被并入 `adEvidence` 后又触发 `evidenceUntil = now + 5s`，等于应用在前台期间永久保持"有广告证据"，通用关闭按钮逻辑长期处于武装状态。
   - 修复：只有本次扫描真正观察到的证据才延长窗口（强证据 8s、一般证据 5s）；缓存证据只消耗、不续期。

5. **上游响应校验过严导致误判失败**
   - 旧逻辑：复用端口上收到第一个不匹配 ID 的迟到包就 `return null`（随后 SERVFAIL 或换服务器）。
   - 修复：套接字复用时改为"丢弃不匹配包、继续等到超时"，并保留 ID + QR 校验。

## 热路径实测

用同一份真实规则库（`full_blocklist.txt`，105,236 条）与 48,000 条典型按钮文字，在 JVM 17 上对比 1.8.4 实现与 1.9.0 实现（预热后第 3 轮，单线程）：

| 项目 | 1.8.4 | 1.9.0 | 结果一致性 |
| --- | --- | --- | --- |
| 域名规范化（105,236 条） | 90 ms | 5 ms | 校验和完全相同 |
| 按钮文字压缩（48,000 条） | 14 ms | 1 ms | 输出长度完全相同 |
| 广告域名启发式（105,236 条） | 196 ms | 62 ms | 评分总和完全相同（8,172） |

说明：

- 域名规范化只在"已是合法小写 ASCII"时走快路径（DNS 查询与内置规则库的绝大多数条目都属于这种情况），国际化域名仍走 IDN 慢路径，行为不变。
- 无障碍侧的三项改动（节点属性记忆化、每节点只压缩一次、不可见节点不入候选表）无法在无设备环境下量化，只能保证语义等价：判定条件、评分与候选顺序均未改变。

## 发行物核验

- badging：`1.9.0 (18)`；证书指纹与历史版本一致；zipalign 校验通过；apksigner verify 通过（v2 + v3）。
- Lint（`:app:lintRelease`）：`No issues found.`
- 源码包包含 `TcpDnsRelay`、`DnsPacketHardeningTest`、`TcpDnsRelayTest`、`MatchingFastPathTest`、`RuleStoreCacheTest`、`SettingsCounterTest` 与全部既有源码/测试。
- **源码包独立构建复现**：把源码包解压到空目录、只补 `local.properties`，`:app:assembleRelease` 与 113 项测试全部通过，产物 927,957 字节，与工作区构建的未签名 APK 同字节数。
- **dex 复核**：`classes.dex` 含 `TcpDnsRelay`、`buildTcpPacket`、`buildTruncatedResponse`、`ageTtls`、`upstreamTcpQuery`、`looksLikeSkipIdNormalized`、`compactMarkers`、`isCommentSensitiveContextCompact`，不含已被替换的 `buildTcpReply`。
- 规则资产与 1.8.3/1.8.4 同版本（`full_blocklist.txt` 2,119,318 字节，未改动）。

## 真机环境

`adb devices` 无连接设备，未做真机验证。真机重点：

1. 开启 DNS 过滤后正常浏览网页、看视频，确认没有新增的解析失败（重点验证 TCP 中继）。
2. 打开一个会写大量统计请求的应用，观察首页"已拦截"计数是否正常增长、界面是否仍然流畅（验证计数批量落盘）。
3. 打开"应用规则"页，确认应用列表出现速度（后台加载）与滚动流畅度（规则缓存）。
4. 在 B 站视频详情页停留一段时间，确认不会因为"广告证据"长期残留而误点普通控件（验证证据窗口修复）。

## 已知边界

- 与 1.8.3/1.8.4 相同（HTTPDNS、固定 IP、共用主域、加密 DNS 仅严格模式部分缓解、已缓存素材），无新增边界。
- 新增边界：TCP 中继最多 16 条并发连接、单连接 10 秒空闲回收；超出时回 RST（失败更快，但不会成功）。DNS over HTTPS/TLS 仍然只有严格模式的部分缓解。