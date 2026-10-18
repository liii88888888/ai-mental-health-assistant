# AI 心理健康助手（后端）

一个基于大模型的心理健康陪伴应用后端。用户可以注册登录、和 AI 进行**流式**对话、记录情绪日记，AI 还能通过 **Function Calling** 直接读写真实的业务数据（写日记、查情绪趋势）。

项目重点不在"功能多"，而在几个具体问题的工程解法：

- **同一个 Redis，两种失败策略** —— token 黑名单走 fail-closed（防线），对话缓存走 fail-open（加速器）
- **SSE 流式输出** 的完整链路，以及异步派发下 Spring Security 上下文丢失的排查与修复
- **上下文滑动窗口** + MySQL 持久化的 Cache-Aside 配合
- **Function Calling 的边界**：哪些决策该交给模型，哪些必须用代码兜住

对我来说，这是我做的最满意的项目，我很喜欢，网友们可以自行拉项目到本地

## 功能

| 模块 | 内容 |
|---|---|
| 用户 | 注册 / 登录 / 登出（JWT + Redis 黑名单）、BCrypt 密码存储 |
| AI 对话 | 多会话管理、SSE 流式回复、Redis 滑动窗口上下文、消息持久化 |
| 情绪日记 | 增删改查、一天一篇（唯一索引兜底）、AI 情绪分析（结构化 JSON 输出） |
| AI 工具调用 | 模型自主决定：写今天的日记 / 按日期范围查 / 查最近 N 天 |
| 知识库 | 文章 Cache-Aside 读写的**最小实现**（只用来演示缓存模式，不是重点） |

> 前端是配套的 Vue 3 工程，不在本仓库；本仓库提供完整的 REST + SSE 接口。

---

## 技术栈

| | |
|---|---|
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot 3.4.6、Spring Security 6 |
| AI | Spring AI 1.0.1（DeepSeek 模型） |
| 持久化 | MySQL 8 + MyBatis-Plus 3.5.12 |
| 缓存 | Redis（Lettuce 连接池） |
| 鉴权 | java-jwt 4.5.0（HS256） |

---

## 几个设计决定

### 1. JWT 无状态，但登出需要"收回"

JWT 签发出去就收不回来。所以登录后签发 token，**登出时把它的 sha256 摘要写进 Redis 黑名单**，TTL 设成"这个 token 的剩余寿命"——让黑名单和 token 同生共死，不需要任何定时清理任务。

过滤器的校验顺序：验签 → **查黑名单** → 查用户状态 → 组装 `LoginUser` 放进 `SecurityContext`。

### 2. 同一个 Redis，两种失败策略

| | token 黑名单 | 对话缓存 |
|---|---|---|
| 角色 | **防线** | **加速器** |
| Redis 挂了 | **拒绝服务（fail-closed）** | **降级查库（fail-open）** |
| 为什么 | 查不到就等于"不知道这个 token 是否已作废"，放行 = 被盗 token 畅通无阻 | MySQL 里还有真相，降级只是慢一点 |

判断依据不是"Redis 重不重要"，而是**这个数据消失之后，我该停下还是该退一步继续走**。

### 3. 上下文滑动窗口

- 每个会话在 Redis 里维护一个 List：`chat:history:{sessionId}`
- `RPUSH` 写入 + `LTRIM -20 -1` 只留最近 **20 条（10 轮）** + `EXPIRE 30min`，每次写入续期
- 缓存未命中时回源 MySQL 并回填；写入时**先 MySQL 后 Redis**

为什么不用 String 存大 JSON：那样每次追加都是"读出来 → 反序列化 → 改 → 序列化 → 写回"，一次追加等于一次全量读写。选数据结构的标准是**语义上最贴合业务动作**，而不是存储上最省事。

### 4. Function Calling：模型负责理解，代码负责决策

三个工具：`createDiary`（写今天的日记）、`queryEmotionTrend`（按日期范围查）、`queryRecentEmotion`（查最近 N 天）。

几条硬规矩：

- **`userId` 由构造注入，绝不做工具参数。** 工具入参是模型生成的不可信输入，把用户 ID 交给它等于开后门。
- **写入是"追加"语义，由服务端确定性保证**，不依赖模型每次都记得合并。模型只负责说"用户这次说了什么"。
- **不提供"重写"能力。** 追加判错最多是脏数据，重写判错是数据丢失——代价不对称的操作不交给概率性组件。
- **只写用户明确表达过的字段**，没提到的保持 `null`，不根据语气推测。
- **相对时间由服务端换算**（"最近"→ `queryRecentEmotion(days)`），不让模型自己算日期。

---

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8
- Redis 6+（本地或远程都行）

### 1. 建库

```bash
mysql -uroot -p < sql/schema.sql
```

会创建 `mental_health_assistant` 库和全部 9 张表（脚本只含结构，不含任何数据）。

### 2. 配置

仓库里的 `application.yml` **不含任何密钥**，全部通过环境变量注入。

| 变量 | 说明 | 默认值 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | MySQL 地址 | `localhost` / `3306` / `mental_health_assistant` |
| `DB_USERNAME` / `DB_PASSWORD` | MySQL 账号 | `root` / 空 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis | `localhost` / `6379` / 空 |
| `DEEPSEEK_API_KEY` | DeepSeek 的 key | 占位值 |
| `JWT_SECRET` | 签名密钥，**务必换成长随机串** | 开发占位值 |
| `SERVER_PORT` | 服务端口 | `8080` |

**两种填法，任选一种：**

**A. 环境变量**（推荐）

```bash
export DB_PASSWORD=你的密码
export REDIS_PASSWORD=你的密码
export DEEPSEEK_API_KEY=sk-xxxx
export JWT_SECRET=$(openssl rand -base64 48)
```

IDEA 里在 `Run → Edit Configurations → Environment variables` 填同样的值。

**B. 本地配置文件**

```bash
cp src/main/resources/application-example.yml src/main/resources/application-local.yml
# 填好里面的值，然后启动时加 -Dspring.profiles.active=local
```

`application-local.yml` 已经被 `.gitignore` 排除，不会被提交。

> **没有 DeepSeek key 也能启动。** 注册、登录、日记的增删改查都不依赖 AI；只有 AI 对话和分析会返回"服务暂时不可用"。

### 3. 启动

```bash
mvn spring-boot:run
```

### 4. 验证

```bash
# 注册
curl -X POST http://localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo_user","password":"123456","confirmPassword":"123456","email":"demo@example.com"}'

# 登录拿 token
curl -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo_user","password":"123456"}'
```

拿到 token 后，后续请求带请求头 `token: <你的 token>`。

---

## 接口一览

统一返回 `{ code, message, data }`。`code = "200"` 为成功；**认证类错误码以 `A0` 开头**（`A0230` token 无效/过期/已进黑名单、`A0301` 未授权），前端据此判断是否需要重新登录。

### 用户

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/user/register` | 注册 |
| POST | `/api/user/login` | 登录，返回 JWT |
| POST | `/api/user/logout` | 登出，token 进黑名单 |

### AI 对话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/psychological-chat/session/start` | 新建会话 |
| GET | `/api/psychological-chat/sessions` | 我的会话列表（分页） |
| DELETE | `/api/psychological-chat/sessions/{id}` | 删除会话（消息级联删、缓存一起清） |
| GET | `/api/psychological-chat/sessions/{id}/messages` | 会话消息 |
| POST | `/api/psychological-chat/stream` | **SSE 流式对话** |

SSE 帧格式：

```
data:{"code":"200","message":"操作成功","data":{"content":"你"}}
data:{"code":"200","message":"操作成功","data":{"content":"好"}}
event:done
data:{"code":"200","message":"操作成功"}
```

### 情绪日记

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/emotion-diary/my` | 我的日记（分页） |
| POST | `/api/emotion-diary` | 新增/更新当天日记（覆盖语义，供前端表单用） |
| DELETE | `/api/emotion-diary/my/{id}` | 删除自己的日记 |
| POST | `/api/emotion-diary/{id}/analyze` | 触发 AI 情绪分析 |
| GET | `/api/emotion-diary/admin/page` | 后台列表（**需要管理员**） |
| DELETE | `/api/emotion-diary/admin/{id}` | 后台删除（**需要管理员**） |

> AI 对话里的日记写入走的是**追加语义**（和表单的覆盖语义分开），由 `DiaryTools` 调用 `EmotionDiaryService.appendOrCreate`。

---

## 项目结构

```
src/main/java/com/lqy
├── common/          统一返回体、错误码
├── config/          Security / MyBatis-Plus / JWT 配置
├── controller/      REST + SSE 入口
├── DTO/             请求/响应对象
├── entity/          数据库实体
├── enums/           情绪类型、发送方、用户状态等
├── exception/       业务异常 + 全局异常处理
├── filter/          JWT 认证过滤器
├── mapper/          MyBatis-Plus Mapper
├── security/        LoginUser（放进 SecurityContext 的 principal）
├── service/         业务逻辑
│   ├── convert/     实体 ↔ DTO
│   └── tool/        **Function Calling 的工具定义**
└── util/            JWT、SessionId、SecurityUtil 等

src/test/java/com/lqy          单元测试
sql/schema.sql                 建库脚本（只含结构）
tools/                         调试探针（见下）
docs/面试讲述稿.md              设计取舍与踩坑记录
```

---

## 测试

```bash
# 需要能连上 MySQL 的环境变量
mvn test
```

`EmotionDiaryAppendTests` 覆盖了日记写入最容易出错的地方，**7 个用例**：

- 同一天记两笔：正文和触发因素都追加、都带时间戳、分数取最新、没提到的字段不覆盖
- 内容一变，旧的 AI 分析作废
- 重复提交同一段话不重复追加
- 模型把已有全文一起传回来也不会拼出重复
- `queryRecentEmotion` 的范围边界（含今天、不含更早）
- 指定日期范围只返回区间内的记录
- 查询工具只返回当前用户的数据

> 每个用例都用**独立的测试账号**，不碰任何真实数据。这一点是踩过坑之后才改的——早期版本直接用了 demo 账号，把真实日记删过。

## tools/ 调试探针

一组不依赖前端、直接用 Node 打 HTTP 接口的脚本，用来观测**真实链路**（尤其是 AI 相关、行为不确定的部分）：

| 脚本 | 用途 |
|---|---|
| `chat-probe.js` | 会话管理 + SSE 流式对话 |
| `diary-probe.js` | 日记增删改查 + AI 分析 |
| `article-probe.js` | 知识库 Cache-Aside |
| `fc-probe.js` | Function Calling 基础四步 |
| `fc-overwrite-probe.js` | 同一天多次写入：追加 vs 覆盖 |
| `fc-reliability-probe.js` | 连续多轮记录时工具调用是否稳定 |
| `date-probe.js` | "今天/昨天/这周"的相对时间理解 |
| `DbInspect.java` | 只读地看表结构和跑一条 SQL |

用法大致是 `node tools/xxx-probe.js <用户名> <密码>`，具体看各文件开头的注释。

> 分层原则：**探针观测"模型的表现"，单元测试守护"我的逻辑"**。AI 功能的行为是概率性的，拿它当单测输入只会得到 flaky 的用例。

---

## 已知限制

写在这里是因为知道它们在哪，比假装没有更实在：

1. **流中断会丢掉整轮。** 落库挂在"流正常结束"上，模型超时或网络中断时用户消息也不会落库。改进方向：流开始就存用户消息并标记"进行中"，结束后置为"完成"。
2. **日记内容追加有丢失更新的窗口。** 同一天两笔并发写入时，两边都读到旧值、各自拼接、后写的覆盖前写的。生产环境需要行锁或乐观锁。
3. **AI 工具调用在长会话里偶发失灵。** 模型会模仿历史里的自己而跳过工具调用，已通过"在历史里保留工具执行痕迹"显著改善，但没有完全消除。
4. **`-1` 是通用业务失败码**，语义不够细分；认证类错误单独用 `A0xxx` 区分。
5. **知识库是演示性的最小实现**，不包含富文本、图片上传、审核等。

---

## 安全说明

- 仓库内**不含任何密钥**，全部通过环境变量注入。
- 密码用 BCrypt 存储，登录失败提示不区分"账号不存在"和"密码错误"（防账号枚举），并且两种情况都执行一次 BCrypt 比对（抹平响应时间差）。
- 所有"我的数据"类接口都从登录态取 `userId`，不接受前端传入。
- 后台接口（`/admin/**`）在入口校验管理员角色。


