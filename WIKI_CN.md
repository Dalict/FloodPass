# FloodPass Wiki

[English](https://github.com/Dalict/FloodPass/blob/main/WIKI.md) | 简体中文

## 工作原理

### 1. 自动登录

1. 玩家进服，FloodPass 在 `PlayerJoinEvent` 的 `MONITOR` 优先级监听，此时 Floodgate 身份数据已经就绪
2. 用 `FloodgateApi#isFloodgatePlayer(uuid)` 判断是否真正的基岩连接；Java 玩家不安排任何任务
3. 等待 `login-delay-ticks`（默认 20 tick = 1 秒），让 AuthMe 完成自己的进服处理（limbo 建立、会话恢复）
4. 检查玩家是否已经登录；未登录则按三种情况处理：

| 账号状态 | 自动注册 | FloodPass 的动作 | 玩家看到 |
|---|---|---|---|
| 已注册 | 无关 | `forceLogin` 免密登录 | 自动登录提示 |
| 未注册 | 开启 | `forceRegister` 随机密码注册并登录 | 自动注册提示 |
| 未注册 | 关闭 | 不处理 | AuthMe 正常注册流程 |

**为什么延迟**：AuthMe 在进服时自己也有一堆异步处理。如果 FloodPass 在同一个事件里直接调 `forceLogin`，两个插件的任务可能交错，出现"登录后又被关回 limbo"之类的竞态。延迟 1 秒是最省事的解法，所以不建议把 `login-delay-ticks` 调到 10 以下。

**为什么已登录就跳过**：AuthMe 的会话功能（`settings.sessions.enabled: true`）会在玩家重进时自己完成登录。这时 FloodPass 保持沉默，玩家看到的是 AuthMe 的"会话重连成功"提示，不是 FloodPass 的提示。这是预期行为，不是故障。

### 2. 名字校验绕过

**问题**：AuthMe 的 `allowedNicknameCharacters`（默认 `[a-zA-Z0-9_]*`）不允许点号，而基岩玩家的名字带 Floodgate 前缀。AuthMe 在**预登录阶段**（`AsyncPlayerPreLoginEvent`）就把人踢了，`PlayerJoinEvent` 根本不会触发，自动登录来不及执行。

**做法**：FloodPass 也在 `LOWEST` 优先级监听同一个事件，但注册顺序在 AuthMe 之后（`plugin.yml` 的 `depend` 保证 AuthMe 先启用、先注册监听器）。AuthMe 拒绝后，FloodPass 复核并恢复成允许。

放行必须同时满足四个条件：

1. 结果是 `KICK_OTHER`（AuthMe 名字校验用的结果）
2. UUID 是真正的 Floodgate UUID（由 XUID 派生，Java 客户端伪造不了）
3. 名字确实不符合当前正则
4. 踢出消息能匹配 AuthMe 自己的 `characters_in_name` 模板（或退化为"消息里包含正则原文"）

**为什么不能更简单**：AuthMe 的防机器人拒绝也是 `KICK_OTHER`，而基岩玩家的名字永远违规。如果不看消息文本就一律放行，等于对基岩玩家拆掉防机器人保护。

**为什么不能放宽正则**：把 `allowedNicknameCharacters` 改成 `'\.?[a-zA-Z0-9_]*'` 会让 **Java 玩家也能起带点的名字**，可以冒充基岩玩家。FloodPass 的选择是"正则对 Java 玩家原样生效，只豁免真基岩 UUID"。

**模板匹配怎么工作**：

- 从 `plugins/AuthMe/messages/messages_*.yml` 读取 `characters_in_name`（磁盘优先，AuthMe jar 内置文件兜底）
- 去掉颜色代码；把占位符（`%valid_chars`、`%reg` 等 `%xxx`）当成通配符；其余文字原样转义
- 用渲染后的模板去匹配实际踢出消息

这样官方语言文件、美化版、翻译版、分支版语言文件都能识别；服主删掉占位符也不影响。模板如果只有占位符、没有任何文字，会被直接作废——否则会编译成 `^.*$`，匹配一切消息。

**为什么必须用 LOWEST**：早期版本在 `NORMAL` 恢复，结果 LuckPerms 在 `LOW` 看到"连接被拒"就不加载权限，又在 `MONITOR` 发现"被拒的连接被重新允许"就主动踢人（空消息）。提前到 `LOWEST` 后，LuckPerms 全程看到的是允许，问题消失。

识别失败时，控制台会输出**每会话一次**的警告，包含实际踢出消息、已加载的模板列表和修复建议。

### 3. Premium 同名碰撞防护

**问题**：AuthMe 6.x 的正版验证（`enablePremium` + PacketEvents）会在封包层拦截 `LOGIN_START`。Geyser 发出的包里是裸游戏 ID（例如 `Dalict`），Floodgate 前缀要到后端解密后才加。如果这个裸名恰好是 premium 注册账号，AuthMe 会发起 Mojang 加密挑战；基岩客户端没有 Mojang 会话，永远答不上来。

**做法**：FloodPass 用 PacketEvents 在 `LOWEST` 优先级（先于 AuthMe 的 `NORMAL`）改写包内用户名：

- 只处理渠道上带有 `floodgate-player` 属性的连接——这个属性只在 Floodgate 成功解密并验证 Xbox 签名数据后才写入，Java 连接上永远是 null
- 把用户名改成 `前缀 + 名字`（如 `.Dalict`）
- AuthMe 自己的用户名过滤器 `^[a-zA-Z0-9_]{2,16}$` 看到点号就跳过 premium 验证
- Floodgate 随后用自己验证过的数据构造最终身份，包内用户名只是临时值，所以**不会出现双前缀**

结果：Java 账号继续享受 premium 免密登录，同名基岩玩家经 Xbox 验证正常进服。

**没装 PacketEvents 时**：这个功能自动跳过，插件正常启动（1.0.3 起）。1.0.2 及更早版本会因为类加载失败导致整个插件无法加载，已修复。

### 4. preJoin 对话框自动放行

**问题**：AuthMe 6.x 可以在 Paper/Folia 的**配置阶段**（登录之后、`PlayerJoinEvent` 之前）弹出登录/注册对话框。玩家还在对话框里时，任何插件都无法完成登录；超时后 AuthMe 会以"登录超时"踢人。表现为：基岩玩家卡在密码框很久，然后被踢。

**做法**：FloodPass 在 `AsyncPlayerPreLoginEvent`（`MONITOR`）对已验证的 Floodgate 玩家调用 AuthMe 的公开 API `forceLoginFromProxy(名字)`，等于告诉 AuthMe"这个玩家是可信的自动登录"。AuthMe 在配置阶段检查到这个排队请求后会跳过对话框：

- 已注册玩家在进服处理中完成登录
- 未注册玩家正常进服，随后由 FloodPass 的自动注册接管

**边界**：

- 只对真 Floodgate UUID 生效，Java 玩家的对话框照常显示
- 依赖 AuthMe 6.x 的 API；AuthMe 5.x 没有 preJoin 对话框，该功能不生效
- 开启 preJoin 时，已注册的基岩玩家可能先看到 AuthMe 的"会话重连成功"提示，而不是 FloodPass 的自动登录提示——两者都代表已登录

---

## 安全性

Floodgate 身份只有在基岩客户端用有效的 Xbox Live 令牌完成 Floodgate 加密握手后才会存在。玩家的 UUID 由 XUID 派生，Java 版客户端无法伪造。因此：

- 免密登录只对"微软已经验证过"的玩家开放
- 名字绕过只豁免真正的 Floodgate UUID，正则对 Java 玩家不变
- premium 防护只改写"渠道上已验证"的连接

前提条件：Floodgate 私钥（`key.pem`）不外泄，基岩流量全部经 Geyser 进入，Geyser 的 `validate-bedrock-login` 保持 `true`。

---

## 安装

1. 安装并配置好 **Geyser** 与 **Floodgate**（2.x），确保基岩玩家可以进服
2. 安装 **AuthMe**（AuthMeReloaded，5.2+），按 Java 玩家的需要正常配置
3. 把 `FloodPass-x.x.x.jar` 放进 `plugins/`，重启服务器

用一个基岩账号验证：首次进服应提示"自动注册并登录成功"，再次进服提示"自动登录成功"。

> 如果你用的是内置基岩自动登录的 AuthMe 分支：分支功能默认关闭时，FloodPass 可以正常使用并带来免注册收益；如果开启分支自带的功能，不要再叠加 FloodPass。

---

## 代理网络（Velocity / BungeeCord）

FloodPass 不是代理插件。别放进 Velocity 或 BungeeCord 的 plugins 文件夹，放进去也不会加载。它跟着 AuthMe 走：**AuthMe 在哪台后端，它就装哪台后端。**

| 组件 | 代理端 | 后端（跑 AuthMe 的服务器） |
|---|---|---|
| Geyser | 装 | 不装 |
| Floodgate | 装 | **也要装**（key.pem 和代理端同一把） |
| AuthMe | 不装 | 装 |
| FloodPass | 不装 | 装 |

**为什么后端也要 Floodgate**：代理端的 Floodgate 只负责把基岩身份加密转发过来；真正解开身份、让后端插件认出"这是基岩玩家"的，是后端自己那份 Floodgate。只在前端装 Floodgate，后端插件认不出基岩玩家。

**key.pem**：把代理端 Floodgate 的 `key.pem` 复制一份，放进每个后端 Floodgate 的文件夹，两边文件必须完全一致。

另外两件事：

- Velocity 开 modern forwarding，后端 Paper 的 `forwarding-secret` 要和它对上；BungeeCord 在后端 `spigot.yml` 里开 `bungeecord: true`（ip-forward）
- 使用 AuthMe 的 `/premium` 时，代理端要装官方配套（Velocity 装 `authme-velocity`，BungeeCord 装 `authme-bungee`），后端 AuthMe 配 `Hooks.bungeecord: true` 和同一个 `proxySharedSecret`

装完用基岩客户端连 Geyser 的端口（默认 UDP 19132），进服一秒左右看到自动登录提示就成功了。

---

## 命令与权限

| 命令 | 权限 | 说明 |
|---|---|---|
| `/floodpass` | `floodpass.admin`（默认 OP） | 查看状态（启用、延迟、自动注册） |
| `/floodpass reload` | `floodpass.admin` | 重载配置与语言文件，并自动补全缺失的文件 |

别名：`/fpass`

---

## 配置

配置文件：`plugins/FloodPass/config.yml`

| 选项 | 默认值 | 说明 |
|---|---|---|
| `language` | `en` | 使用 `lang/messages_<语言>.yml`；内置 17 种语言，自定义文件同样有效 |
| `enabled` | `true` | 自动登录总开关 |
| `login-delay-ticks` | `20` | 进服后等待多少 tick 再登录；建议不低于 10 |
| `auto-register.enabled` | `true` | 自动注册首次进服的基岩玩家 |
| `auto-register.password-length` | `32` | 随机注册密码长度（8-64） |
| `bypass-nickname-check` | `true` | 名字前缀绕过 |
| `premium-conflict-guard` | `true` | premium 同名碰撞防护（需 PacketEvents） |
| `bypass-prejoin-dialog` | `true` | 自动放行 AuthMe 的 preJoin 对话框 |
| `debug` | `false` | 控制台记录每次登录决策 |
| `config-version` | `3` | 内部结构版本，不要手动修改 |

### 语言文件

- 位置：`plugins/FloodPass/lang/messages_<语言>.yml`
- 想加新语言：自己创建 `messages_zh_tw.yml`，把 `language` 改成 `zh_tw` 即可
- 缺失的键会按"磁盘 → 内置同语言 → 内置英文"回退
- 消息支持 `&` 颜色代码；设为空字符串等于禁用该消息

### 配置自愈

插件启动或 `/floodpass reload` 时会检查 `config.yml` 的结构：

- 结构已经是最新：什么都不做
- 缺少新键 / 版本号旧 / 类型不对：先备份（`config.yml.backup-时间戳`），再以内置模板为底稿迁移旧值并写回，**注释保留**
- 文件语法损坏：备份为 `config.yml.broken-时间戳`，重置为默认配置

服主手动添加的注释在发生迁移时只保留在备份里，因为回写使用的是内置模板的注释。

---

## 设计说明

- **延迟执行而非进服瞬间强登**：直接调 `forceLogin` 会和 AuthMe 自身的异步进服处理抢时序
- **只用稳定 API 面**：`isRegistered`、`isAuthenticated`、`forceLogin`、`forceRegister` 在 AuthMe 5.2 到 6.x 之间签名一致，一个构建通吃
- **随机密码永不展示**：自动注册的密码没人知道，账号只能通过 Xbox 验证或服主操作触达
- **反射只用在边界**：实体调度器（Folia 兼容）、preJoin API 探测、PacketEvents 隔离加载都有回退，失败不会让插件崩
- **预登录复核而非放宽正则**：只恢复 AuthMe 那一次名字拒绝，正则对 Java 玩家的保护原样保留
- **碰撞防护借力 AuthMe 自己的跳过路径**：不跟 premium 拦截器对抗，只改写"已验证 Floodgate 连接"的包内名字
- **保留注释的配置迁移**：以内置配置的文本模板逐行替换数值，而不是保存解析后的对象

---

## 已知限制

- 基岩账号以带 Floodgate 前缀的名字存储（默认 `.Steve`）。**日后修改 Floodgate 用户名前缀会破坏既有账号的名字映射**，需要迁移 AuthMe 数据；或者不管它，让玩家用随机密码重新注册
- `bypass-nickname-check` 依靠 AuthMe 语言文件里的 `characters_in_name` 模板识别拒绝；若识别失败，控制台会提示踢出文本和修复建议
- Floodgate 的 `username-prefix` 为空时，同名碰撞防护无法区分两种身份，AuthMe 本身也会把两个账号存成同名，该场景不受支持
- AuthMe 的昵称长度上限（`maxNickLength`，默认 20）不在绕过范围内；带前缀的基岩名最长 17，默认值下没问题
- `forceLogin` 会绕过该基岩账号的 AuthMe 两步验证。对 Xbox 已验证身份而言这是预期行为
- 在 AuthMe 6.x 上，基岩玩家可能看到**进服后**的登录弹窗一闪而过；**进服前**的 preJoin 对话框已自动跳过
- preJoin 放行依赖 AuthMe 6.x 的 API；AuthMe 5.x 上该选项不生效（5.x 没有这个对话框）
- 配置自愈保留的是内置模板注释；服主手动添加的注释只在备份里
- 会话恢复、跨服与 BungeeCord 消息不受影响；AuthMe 已通过会话登录时 FloodPass 保持静默
- AuthMe 的 `/premium` 面向 Java 正版账号，普通基岩玩家使用会被安全拒绝；基岩玩家也不需要它

---

## 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 插件加载失败，`NoClassDefFoundError: ...packetevents...` | 1.0.2 及更早版本把 PacketEvents 当成加载期依赖 | 升级到 1.0.3+ |
| 基岩玩家被踢"用户名不符合标准" | AuthMe 名字正则拒绝了前缀名，绕过没有生效 | 升级到 1.0.3+；开 `debug` 看是否有识别警告 |
| 基岩玩家卡在密码框，约 2-5 分钟后"登录超时" | AuthMe 的 preJoin 对话框阻塞了进服 | 升级到 1.0.4+，保持 `bypass-prejoin-dialog: true` |
| LuckPerms 报 `Player connection was re-allowed` 并踢人 | 旧版本在 `NORMAL` 优先级恢复连接 | 升级到 1.0.3+ |
| 玩家能进服，但看不到 FloodPass 的自动登录提示 | AuthMe 的会话功能先登录了玩家 | 正常现象；`debug: true` 会看到 `already authenticated, skipping` |
| geyser 日志说"尝试以 Java 版玩家身份登录" | premium 挑战没有被跳过（PacketEvents 或防护未生效） | 确认装了 PacketEvents，且 `premium-conflict-guard: true` |
| 后端完全没有 Floodgate 前缀名 | Geyser 没有发送 Floodgate 身份 | 检查 Geyser 的 `java.auth-type: floodgate`、`validate-bedrock-login: true`、`key.pem` 和后端 Floodgate |
| Windows 下 `nogui` 控制台没有任何输出 | 从非控制台宿主启动，stdin 句柄无效 | 去掉 `nogui`，或在资源管理器里双击启动脚本 |
| 升级后配置注释消失 | 1.0.4 之前用 Bukkit 的 YAML 保存重写文件 | 升级到 1.0.4+；旧注释在备份里 |

常用检查：

```bash
# FloodPass 启动状态
grep -i floodpass logs/latest.log

# 基岩玩家是否被 Floodgate 识别
grep "Floodgate 玩家" logs/latest.log

# AuthMe 实际登录了谁
grep "\[AuthMe\].*logged in" logs/latest.log

# AuthMe 当前的名字正则
grep allowedNicknameCharacters plugins/AuthMe/config.yml

# AuthMe 的名字校验消息模板
grep -A1 characters_in_name plugins/AuthMe/messages/messages_*.yml
```

---

## 构建

```bash
mvn package
```

产物在 `target/FloodPass-<版本>.jar`。编译目标是 Java 8，可在 Java 8 及以上的服务端运行。

---

## 许可证与反馈

MIT License。

- 源码与问题反馈：https://github.com/Dalict/FloodPass
- Issues：https://github.com/Dalict/FloodPass/issues

---