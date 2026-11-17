# AI 简历优化系统 · 项目开发文档

> 版本：v2.3（第十二阶段：简历删除 / 导出、修改密码）　日期：2026-11-17
> 说明：本文档实时记录开发进度、任务状态、技术问题与解决方案，与代码实际状态保持一致。
> 部署与启动：见《项目启动说明-AI简历优化系统.md》（环境要求、导库、启动命令、账号、AI 厂商配置、自定义 API 指引、验证命令与 FAQ）。
> 需求基线：《需求分析说明书-AI简历优化系统.md》v0.8；数据基线：ai-resume-system/db/schema.sql + data.sql + migration-v1.6/v1.7/v1.8/v1.9/v2.0。
> **当前状态：需求范围内功能全部完成（含 v2.0 扩展项）；e2e 136/136（规则模式）、单元 56/56、集成 17/17；AI 厂商可在管理端切换；学生可把任意 HTTP 接口配成可复用自定义 API，服务端代发并统一处理超时/重试/截断/脱敏，请求头值 AES-256-GCM 加密存储。**
> **v2.2（2026-11-17）**：按全项目审查结论修复 OCR / 导入链路 8 个缺陷（P51~P58），新增 `ResumeImportServiceTest`、`ResumeOcrServiceTest` 共 16 项单元测试（本机 JDK 17 直编运行 16/16 通过）。上述 e2e / 集成数字为 v2.1 结果，v2.2 新增的 2 项 e2e 用例需在具备 MySQL 与 AI 密钥的环境重跑确认。
> **v2.3（2026-11-17）**：新增 FR-RESUME-06（简历删除 / 批量删除、Markdown 与纯文本导出）与 FR-USER-03（修改密码，改后吊销其余会话）；新增 `ResumeServiceTest` 9 项、`AuthControllerPasswordTest` 5 项单元测试（与 v2.2 合计 **30 项，本机直跑 30/30 通过**）；e2e 新增 13 项用例（待具备 MySQL 的环境执行确认）。

## 0. 本阶段（v2.0 / v2.1）目标与功能范围

**阶段目标**：在既有「简历 + 岗位 + AI 分析」主线之外，为学生提供一个可自行配置的接口控制台——把常用 HTTP 接口保存为配置项，页面在线测试通过后即可在学生端脚本中按 ID 或名称调用；请求由服务端代发，规避浏览器跨域，并由统一请求层承担超时、重试、响应截断与敏感信息掩码。

**范围内**：

| 编号 | 事项 | 关键交付 |
| --- | --- | --- |
| EXT-API-01 | 配置管理 | 新增 `user_api_config` 表（11 表）；名称/方法/地址/分组/标签/请求头/查询参数/请求体/超时/重试/启停全量 CRUD；复制自动命名；按用户隔离 |
| EXT-API-02 | 在线测试与调用 | `POST /api/user-apis/test`（不落库草稿测试）、`POST /api/user-apis/{id}/invoke`（调用已存配置，可覆盖参数/头/体）；返回状态码、耗时、尝试次数、请求回显与响应体 |
| EXT-API-03 | 调用治理 | 连接 5s + 读取 1~60s 超时；仅网络异常/429/5xx 重试（≤3 次，间隔递增）；响应体截断 64KB；`Host/Content-Length/Connection` 等托管头拒绝；`Authorization/Cookie/api-key` 回显掩码 |
| EXT-API-04 | 控制台页面 | 学生端新增 `student/api-console.html`（导航第 8 项）：列表 + 搜索/分组/标签筛选、配置弹窗、测试请求、调用结果、代码示例 |
| EXT-API-05 | 前端兜底 | `static/api.js` 增加 `Api.Custom`：云端优先、本地镜像回落、传输失败入待同步队列、`invokeByName` 按名称调用 |
| EXT-API-06 | 请求头加密（v2.1） | 请求头值 AES-256-GCM 加密落库（`enc:v1:` 密文，复用 `AiKeyCipher`）；敏感头回显掩码 + `masked/hasValue`；`keep=true` 沿用原值；历史明文启动自动加密 |
| 质量保障 | 测试与文档 | e2e 102→**136**；单元/集成 **73/73**（新增 3 个测试类 37 项）；走查 11→**12 页**、截图 01~09；启动说明新增第 8 章；README/数据库设计/本文同步 |

**范围外**：配置共享与团队协作、定时任务与批量调用、密钥托管与轮换（请求头已加密存储，但主密钥随本机文件管理）、WebSocket/GraphQL、调用历史落库。

**完成判定**：学生可新建→测试→保存→调用→删除闭环；跨账号不可见不可改；停用配置拒绝调用；404 不重试、5xx 重试、超时中断、大响应截断均可在页面看到结果；请求头在库内为密文、界面只显示掩码且「留空沿用」不丢值；e2e **136/136** 与走查 12 页 0 异常。

## 0.1 上一阶段（v1.9）目标与功能范围（已完成）

**阶段目标**：把 AI 分析从「只能在线切换模型、地址与密钥固化在部署配置」升级为「管理员在页面完成厂商选择、地址与密钥配置」，同时保证密钥不落明文、配置即时生效、历史数据零迁移。

**范围内**：

| 编号 | 事项 | 关键交付 |
| --- | --- | --- |
| FR-ADMIN-03（扩展） | 厂商选择与配置 | 11 个预置厂商注册表；厂商下拉 + 默认地址/默认模型自动带出；地址、密钥、模型均可覆盖 |
| 安全 | API-KEY 加密存储 | AES-256-GCM，`enc:v1:` 密文落库；接口只返回掩码；主密钥三级回落 |
| 校验 | 实时校验 + 连通性测试 | 密钥按厂商正则校验；地址做 URI/主机/长度校验；保存前自动探测 `/models`，失败可 force 强制保存 |
| 兼容 | 历史数据迁移 | 启动时按 endpoint 反查厂商并补写 `ai.vendor`；仅传 `model` 的旧请求继续走 `updateModel` |
| 文档/测试 | 使用与开发文档、测试 | 启动说明新增第 7 章；开发文档补充模块结构/接口/存储格式；单元 29 + 集成 7 |

**范围外**：配置多租户隔离（当前为全局单份）、密钥托管与自动轮换、厂商余额/配额查询、Azure 多部署自动发现。

**完成判定**：切换厂商后无需重启即可完成分析；数据库中不存在明文密钥；旧库升级后厂商正确回显；`mvn test` 五个测试类全部通过。

## 0.2 上上阶段（v1.7）目标与功能范围（已完成）

**阶段目标**：v1.6 关闭数据库审查问题后，剩余待办集中在三项能力缺口与一项新需求——模型效果从未评测、`.doc` 与扫描件无法导入、登录凭证重启失效；同时按使用方要求新增「模型选择与自定义模型」。

**范围内**：

| 编号 | 事项 | 关键交付 |
| --- | --- | --- |
| TOKEN-01 | Token 持久化 | 新增 `auth_token` 表（FK CASCADE + 过期索引）；`TokenService` 改为 DB 持久化 + 30 秒本地缓存 + 5 分钟续期节流；**服务重启后凭证仍有效** |
| FR-RESUME-04（扩展） | `.doc` 支持 | 引入 POI `HWPFDocument`；OLE2 文件头校验；扩展名与文件头不符时自动纠错；加密/损坏文件明确报错 |
| FR-RESUME-05 | 扫描件与图片 OCR | PDF 无文字层时按 DPI 逐页渲染 → 多模态模型 OCR；支持 PNG/JPG 图片简历；未配置视觉模型时给出可操作提示 |
| FR-ADMIN-03 | 模型选择与自定义模型 | `GET /api/admin/ai/models`（服务端代理网关、缓存 10 分钟、密钥不出后端）、`GET/PUT /api/admin/ai/settings`；`sys_config` 持久化；后台下拉选择 + 自定义 ID |
| 评测 | 模型效果评测 | `model_eval.py`：评分一致性、建议质量 6 项客观指标、多模型对比；产出《模型评测报告》 |
| 质量保障 | 回归 | e2e 86→**102** 项；走查 12 页并新增截图 08；Mock 9/9、真实 7/7 |

**范围外**：人工金标准评分与专家盲评、`.doc` 中表格/图片内容提取、OCR 版面还原（只取文本）、Token 多实例强一致（缓存最多滞后 30 秒）。

**完成判定**：重启后旧 Token 仍有效；`.doc`、图片、扫描件均能导入并正确识别；模型可切换且重启后保持；评测报告的每个数字可从 JSON 复算；e2e 102/102。

**上一阶段（v1.6）回顾**：数据库审查 DR-001~DR-007 关闭，DR-008/010 暂缓、DR-009 判定不适用。

## 1. 技术架构（已确认并落地）

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 前端 | 原型既有静态页（HTML + 原生 JS） | 复用 ai-resume-prototype 页面与 UI；**运行版本以 `server/src/main/resources/static` 为准**，`static/api.js` 为 fetch 客户端；`ai-resume-prototype/api.js` 保留本地桩仅作设计原型，不参与部署 |
| 后端 | Spring Boot 3.5.7 + JdbcTemplate | 单体应用，托管前端静态资源于同一进程（默认端口 8080） |
| 数据库 | MySQL 5.5.15（InnoDB / utf8mb4_general_ci） | **11 张表**（`auth_token`、`sys_config`、v2.0 新增 `user_api_config`）；10 个外键、3 个唯一键、6 个枚举触发器；见 db/schema.sql 与《数据库设计说明》 |
| 认证 | Token（**v1.7 起持久化到 `auth_token` 表**，12 小时滑动过期，30 秒本地缓存 + 5 分钟续期节流）+ X-Token 请求头 | 密码 PBKDF2-HmacSHA256（120000 次迭代）加密存储；重启后凭证仍有效；禁用账号即删库踢下线 |
| AI 能力 | 真实 OpenAI 兼容网关（课程环境 `http://ai-gateway.example.com/v1`），**厂商 / 地址 / 密钥 / 模型均可在管理端在线配置（v1.9）**，默认 `cn:deepseek-v4.1-flash`，超时 30 秒，强制 HTTP/1.1；扫描件 OCR 使用多模态模型 `global:deepseek-v4.1-flash` | 未启用或缺少密钥 → 规则模式；已启用但不可达 → 运行期降级并标注；超时/HTTP 错误/格式异常 → 失败可重试；密钥优先取管理端加密配置，其次 `AI_API_KEY` / 配置文件；模型清单由服务端代理获取 |
| 自定义 API（v2.0） | 服务端 `java.net.http.HttpClient`（HTTP/1.1）代发 | 配置存 `user_api_config`（按 user_id 隔离）；`/api/user-apis/test` 草稿测试不落库，`/{id}/invoke` 调用已存配置；连接 5s、读取 1~60s、失败重试 ≤3 次（仅网络异常/429/5xx）、响应截断 64KB、托管头拦截与敏感头掩码 |
| 分析口径 | RuleEngine + ResumeTextService | 规则分析、岗位匹配度排序、多简历对比共用同一份简历文本与关键词口径，避免多处结果不一致 |
| 文件导入 | PDFBox 2.0.30 + JDK zip/SAX | PDF 抽文本；DOCX 直接读 `word/document.xml`（不引入 POI）；TXT 兼容 UTF-8/GBK；仅解析为草稿，不落盘 |

**环境约束（实测记录，下次开发必读）**

- 本机 MySQL 3306 端口实际为 **5.5.15**（并非 8.0；8.0 目录下的 mysql.exe 仅作客户端使用）。
  - JDBC 驱动锁定 mysql-connector-java **5.1.49**（Connector/J 8.x 不支持 5.5 服务端）。
  - 导入 SQL 必须加 `--default-character-set=utf8mb4`，否则中文列报 `Data too long`。
- 本机 JDK 为 **25**，Spring Boot 3.5 内嵌 ASM 不识别 class file 69。
  - 解决：`maven.compiler.release=17`，编译产物对齐 JDK 17 字节码，运行时仍用 JDK 25（已实测可运行）。
  - 本阶段实测 `mvn` 默认使用 DevEnv 中的 JDK 17，同样可直接编译运行。
- 本机 shell 环境变量 **`SERVER__PORT=64042`** 会覆盖 application.yml 的 server.port。
  - 解决：启动时显式传 `--server.port=8080`（命令行参数优先级高于环境变量）。
- 本阶段开始时本机数据库 **ai_resume 不存在**（环境重置），已按 schema.sql + data.sql 重新初始化：
  7 用户 / 6 简历 / 8 岗位 / 9 任务，演示账号与 v1.0 一致。

## 2. 模块进度总览

| 模块 | 对应需求 | 状态 | 验证情况 |
| --- | --- | --- | --- |
| 数据库初始化 | schema/data.sql | ✅ 已完成 | 7 用户 / 6 简历 / 8 岗位 / 9 任务；版本 v1~v11 与简历版本号一致 |
| 数据库审查修复 | DR-001~DR-010 | ✅ 已完成 | 并发锁、9 个 FK、索引调整、6 个触发器、技能唯一键、数据字典；DR-008/010 暂缓、DR-009 不适用 |
| 认证与用户 | FR-USER-01/02 | ✅ 已完成 | 注册/登录/登出/me、唯一性、长度校验、禁用拒绝登录 |
| 简历管理 | FR-RESUME-01/02 | ✅ 已完成 | 新建/编辑/列表/详情、子模块整存整取、版本 1、完整度评分 |
| 岗位管理 | FR-JOB-01 | ✅ 已完成 | CRUD、归属校验、长度校验 |
| 分析任务 | FR-ANALYSIS-01~04 | ✅ 已完成 | 异步执行、进行中冲突拒绝、失败重试、规则降级、反馈覆盖式 |
| 版本管理 | FR-VERSION-01/02 | ✅ 已完成 | 采纳生成新版本（max+1、唯一键防并发）、列表、对比 |
| 管理端 | FR-ADMIN-01/02、FR-STAT-01 | ✅ 已完成 | 启禁用（禁自身拒绝、禁用即踢下线）、任务只读元信息、统计一致 |
| 质量与反馈看板 | FR-STAT-02 | ✅ 已完成 | 反馈有用率/覆盖率、方式分布与均分、降级次数、Top 8 缺失关键词、系统状态真实化 |
| 前端接入后端 | — | ✅ 已完成（v1.1） | `static/api.js` 为 fetch 客户端；401 清会话跳登录 |
| 岗位匹配度排序 | FR-JOB-02（选做） | ✅ 已完成 | 覆盖率评分、倒序排列、命中/缺失关键词、越权拒绝 |
| 多简历对比 | FR-JOB-03（选做） | ✅ 已完成 | 同岗位 2~3 份简历对比、最匹配/最完整标记、数量与归属校验 |
| 敏感信息脱敏提醒 | FR-RESUME-03（选做） | ✅ 已完成 | 手机号/身份证号/银行卡号命中、只返回脱敏结果、不阻断保存 |
| 简历文件导入 | FR-RESUME-04（选做） | ✅ 已完成 | PDF/DOCX/TXT 解析为草稿、预览确认后填表、5MB/类型/文件头校验 |
| 模拟面试题生成 | FR-INTERVIEW-01（选做） | ✅ 已完成 | 成功任务生成 10 题、失败/他人任务拒绝 |
| 端到端验证 | 全部 AC + DR | ✅ 已完成 | `e2e_test.py` **102/102** 通过（v1.6 为 86，本阶段新增 16 项） |
| AI 链路联调（Mock） | Q-06（部分） | ✅ 已完成 | `ai_e2e_test.py` **9/9**：成功/格式异常/超时/HTTP 500/不可达降级 |
| AI 运行期降级 | BR-ANALYSIS-06 | ✅ 已完成 | 服务不可达 → `mode=rule` + `reason` 标注；成功页展示降级提示 |
| 浏览器走查 | 非功能要求 | ✅ 已完成 | Edge headless 走查学生 8 页 + 管理员 3 页，无 JS 异常；**8 张**关键页面截图留档 |
| 真实 AI 供应商联调 | Q-06 | ✅ 已完成 | `ai_real_test.py` **7/7**；密钥仅环境变量注入 |
| Token 持久化 | TOKEN-01 | ✅ 已完成 | `auth_token` 表 + 缓存；**实测重启后旧 Token 仍可访问 `/api/auth/me`** |
| `.doc` 导入 | FR-RESUME-04（扩展） | ✅ 已完成 | POI HWPF；实测 24KB 真实 `.doc`，姓名/意向/手机/5 项技能全部识别 |
| 扫描件与图片 OCR | FR-RESUME-05 | ✅ 已完成 | 图片 2.0s、扫描件 PDF 2.9s；姓名/意向/手机/技能全部识别 |
| 模型选择与自定义模型 | FR-ADMIN-03 | ✅ 已完成 | 网关 84 个模型可选 + 自定义 ID；`sys_config` 持久化；学生 403 |
| AI 厂商选择与配置 | FR-ADMIN-03（扩展） | ✅ 已完成（v1.9） | 11 个厂商、默认地址/模型自动带出、密钥加密入库 + 掩码、保存前连通性测试、即时生效 |
| AI 厂商配置测试 | — | ✅ 已完成（v1.9） | 单元 29/29（厂商表/加解密/配置解析/服务校验）+ 集成 7/7（真实 MySQL） |
| 模型效果评测 | — | ✅ 已完成 | 一致性极差 10、建议质量 92.3~99.3、4 模型对比；见《模型评测报告》 |
| AI 配置健壮性 | — | ✅ 已完成 | 启用但缺地址/密钥自动走规则；后台系统状态显示「已启用/未配置密钥」 |
| 自定义 API 配置 | EXT-API-01（v2.0） | ✅ 已完成 | 新增 `user_api_config`（11 表/10 外键）；名称唯一、分组/标签筛选、复制自动命名、按账号隔离、上限 100 |
| 请求头加密 | EXT-API-06（v2.1） | ✅ 已完成 | AES-256-GCM 落库（`enc:v1:`）、敏感头掩码回显、`keep` 沿用、历史明文启动迁移 |
| 自定义 API 调用 | EXT-API-02/03（v2.0） | ✅ 已完成 | 草稿测试与已存调用；404 不重试、5xx 重试 3 次、超时中断、64KB 截断、托管头拦截、敏感头掩码 |
| API 控制台页面 | EXT-API-04（v2.0） | ✅ 已完成 | `student/api-console.html` 列表/筛选/弹窗/测试/调用/代码示例；走查 12 页 0 异常，截图 09 |
| 前端离线兜底 | EXT-API-05（v2.0） | ✅ 已完成 | `Api.Custom`：本地镜像回落、待同步队列、`invokeByName`；401 仍统一跳登录 |
| 选做剩余项 | — | ✅ 无 | 需求所列 5 项选做均已完成 |

## 3. 接口清单（与前端约定：{code:0,data} / {code:1,message}）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/auth/register | 注册（默认学生角色） |
| POST | /api/auth/login | 登录，返回 {id,account,name,role,token} |
| POST | /api/auth/logout | 登出 |
| GET | /api/auth/me | 当前用户 |
| GET/POST/PUT | /api/resumes[/{id}] | 简历列表/详情/新建/更新（详情含 sensitiveWarnings） |
| POST | /api/resumes/import | **新增**：multipart 上传 PDF/DOCX/TXT，解析为简历草稿（不直接入库） |
| GET | /api/resumes/{id}/versions | 版本列表 |
| GET | /api/resumes/{id}/versions/compare?a=&b= | 版本对比 |
| GET/POST/PUT | /api/jobs[/{id}] | 岗位列表/详情/新建/更新 |
| GET | /api/jobs/match?resumeId=&q= | 按本人简历的 JD 关键词覆盖率倒序返回岗位及匹配明细（FR-JOB-02） |
| GET | /api/jobs/{id}/resumes/compare?resumeIds= | **新增**：同一岗位下 2~3 份本人简历对比（FR-JOB-03） |
| POST | /api/analyses | 发起分析 {resumeId, jobId, demo?}（demo.mode=rule 强制规则；demo.fail=true 演示失败） |
| GET | /api/analyses?status=&q= | 本人分析记录 |
| GET | /api/analyses/{id} | 分析结果详情 |
| POST | /api/analyses/{id}/feedback | 建议反馈（覆盖式） |
| POST | /api/analyses/{id}/versions | 采纳建议生成新版本 |
| POST | /api/analyses/{id}/interview | **新增**：生成模拟面试题（仅本人成功的分析任务） |
| GET | /api/admin/users?q= | 用户列表（仅学生） |
| PATCH | /api/admin/users/{id}/status | 启用/禁用 |
| GET | /api/admin/analyses?status=&q= | 任务监管（仅元信息，BR-ADMIN-01） |
| GET | /api/admin/stats | 基础统计（含近 7 天趋势） |
| GET | /api/admin/quality | **新增**：质量与反馈统计（FR-STAT-02），仅管理员 |
| GET | /api/admin/ai/models?refresh= | **新增**：代理网关 `/models` 返回模型清单（84 个，含多模态/积分标记），缓存 10 分钟（FR-ADMIN-03） |
| GET/PUT | /api/admin/ai/settings | **v1.9 扩展**：GET 增加 `vendor/vendorName/defaultEndpoint/apiKeyMasked/apiKeySource`；PUT 双模式（厂商配置 / 仅模型） |
| GET | /api/admin/ai/vendors | **v1.9 新增**：预置厂商清单（11 项，不含密钥），供配置页下拉 |
| POST | /api/admin/ai/test | **v1.9 新增**：连通性测试（不落库），返回 `ok/latencyMs/message/vendor/endpoint/modelCount` |
| POST | /api/resumes/import | 扩展：新增 `.doc`（POI HWPF）、`.png/.jpg/.jpeg`（OCR）；扫描件 PDF 自动回退 OCR |
| GET | /api/user-apis?q=&group=&tag= | **v2.0 新增**：本人自定义 API 列表，返回 `items/total/groups/tags/limit` |
| GET/POST/PUT/DELETE | /api/user-apis[/{id}] | **v2.0 新增**：配置详情/新建/更新/删除；名称同账号唯一，越权返回「无权访问该配置」 |
| POST | /api/user-apis/{id}/duplicate | **v2.0 新增**：复制配置，名称自动追加「副本」并编号 |
| POST | /api/user-apis/test | **v2.0 新增**：按当前表单直接发请求（不落库） |
| POST | /api/user-apis/{id}/invoke | **v2.0 新增**：调用已保存配置，body 可传 `params/headers/body` 覆盖项 |

**AI 模式失败语义（BR-ANALYSIS-03/04/05/06）**

| 场景 | 判定 | 任务结果 |
| --- | --- | --- |
| `ai.enabled=false` | 未启用 | `mode=rule`，成功 |
| `enabled=true` 但缺 endpoint/api-key（`isUsable=false`） | 配置不完整 | `mode=rule`，成功（后台标注「未配置密钥」） |
| 已启用但地址不可达/域名错误/连接超时 | AI 不可用 | 自动降级：`mode=rule`、成功、`reason` 记录原因 |
| 响应超过 `ai.timeout-seconds` | 超时 | 失败，可重试 |
| 返回 HTTP 非 2xx | 接口失败 | 失败，可重试；`reason` 保留响应摘要（≤300 字） |
| 返回内容不是约定 JSON | 格式异常 | 失败，可重试 |

> 内网网关不支持 h2c 升级，`AiClient` 已强制 `HTTP/1.1`；否则网关返回 `400 Invalid HTTP request received.`。

### 3.1 新增返回结构

```jsonc
// GET /api/jobs/match?resumeId=1
{ "code": 0, "data": { "resumeId": "1", "jobs": [{
  "id": "3", "title": "后端开发实习生", "company": "美团", "jd": "...",
  "keywords": ["Java", "Spring", "MySQL"], "keywordSource": "manual",
  "matchScore": 67, "matched": ["Java", "Spring"], "missing": ["MySQL"], "keywordCount": 6,
  "updatedAt": "2026-10-19 11:08"
}] } }

// GET /api/resumes/{id}（新增字段，其余不变）
"sensitiveWarnings": [
  { "type": "身份证号", "field": "个人简介", "masked": "110101********1234",
    "advice": "简历中不建议出现完整身份证号，建议删除或只保留后四位。" }
]

// POST /api/analyses/a1/interview
{ "code": 0, "data": { "taskId": "a1", "jobTitle": "Java 后端开发实习生 · 字节跳动",
  "generatedAt": "2026-10-25 14:40", "mode": "rule",
  "questions": [{ "category": "项目经历", "question": "...", "tip": "..." }] } }
```

### 3.2 前端 API 客户端约定（static/api.js）

- 统一 `BASE='/api'`，页面位于 `/student/*.html`、`/admin/*.html` 时使用绝对路径。
- 登录成功写入 `localStorage.ars_session`；每次请求自动带 `X-Token`。
- HTTP 401：清除会话并 `location.replace('/index.html')`；403 返回业务错误信息。
- 网络异常/超时（40s）返回中文提示，不抛出未捕获异常。
- 页面只依赖 `Api.Auth/Resume/Job/Analysis/Version/Admin`，新增 `Api.Job.match`、`Api.Analysis.interview`、`Api.Job.compare`、`Api.Resume.importFile`。
- `importFile` 使用 multipart/form-data，不设置 Content-Type（由浏览器生成 boundary），其余接口仍为 JSON。

### 3.3 第三阶段新增返回结构

```jsonc
// POST /api/resumes/import（multipart: file=简历.pdf/.docx/.txt）
{ "code": 0, "data": {
  "fileName": "我的简历.docx", "fileType": "docx", "textLength": 354,
  "personName": "林知远", "target": "Java 后端开发实习生",
  "phone": "138****5678", "email": "student@example.com",
  "summary": "软件工程本科在读，熟悉 Java、Spring Boot 与 MySQL。",
  "skills": ["Spring Boot", "Redis", "MySQL", "Java"],
  "projects": [{ "title": "校园二手交易平台", "role": "", "period": "2025.03 - 2025.09", "desc": "负责订单模块…" }],
  "warnings": ["导入结果为草稿：请逐项核对姓名、求职意向、联系方式后再保存", "教育经历不会自动识别，请在表单中补充"],
  "textPreview": "林知远\n求职意向：…"
} }

// GET /api/jobs/1/resumes/compare?resumeIds=1,2
{ "code": 0, "data": {
  "job": { "id": "1", "title": "Java 后端开发实习生", "company": "字节跳动",
           "keywordCount": 7, "keywordSource": "manual" },
  "items": [{
    "resumeId": "1", "name": "后端开发实习 · 主简历", "personName": "林知远",
    "target": "Java 后端开发实习生", "version": 3, "completeness": 92,
    "skillCount": 10, "projectCount": 2, "matchScore": 57,
    "matched": ["Java", "Spring Boot", "MySQL"], "missing": ["消息队列", "分布式", "高并发"],
    "keywordCount": 7, "bestMatch": true, "bestCompleteness": true
  }],
  "bestMatchResumeId": "1", "bestCompletenessResumeId": "1",
  "notes": ["匹配度 = 岗位 JD 关键词在该简历中的覆盖率，仅用于横向参考"]
} }
```

### 3.4 质量统计返回结构

```jsonc
// GET /api/admin/quality（仅管理员；数值与任务列表同库同口径）
{ "code": 0, "data": {
  "feedback": { "successTotal": 27, "useful": 1, "useless": 9, "none": 17,
                "total": 10, "usefulRate": 10, "coverage": 37 },
  "modes": [
    { "mode": "ai", "count": 8, "avgScore": 79, "share": 30 },
    { "mode": "rule", "count": 19, "avgScore": 67, "share": 70 }
  ],
  "fallbackCount": 2,
  "topMissing": [{ "keyword": "消息队列", "count": 15 }, { "keyword": "Redis", "count": 14 }],
  "system": { "aiEnabled": false, "aiModel": "deepseek-chat", "dbVersion": "5.5.15",
              "storage": "本机 MySQL", "tokenStore": "内存 Token（重启后需重新登录）" }
} }
```

统计口径：
- `feedback.total = useful + useless`，`none = successTotal - total`；`usefulRate` 以「已反馈」为分母，`coverage` 以「成功任务」为分母；
- `modes` 仅统计成功任务，`count` 之和等于 `successTotal`，`share` 按成功任务计算；
- `fallbackCount` 统计 `mode=RULE` 且 `reason` 含「降级」的任务；
- `topMissing` 对成功任务的 `missing_keywords` 拆分聚合，取前 8。

### 3.5 AI 厂商配置（v1.9）

#### 3.5.1 模块结构

| 文件 | 职责 |
| --- | --- |
| `analysis/AiVendor.java` | 预置厂商注册表：`id / name / defaultEndpoint / defaultModel / keyPattern / keyHint / keyUrl / openaiCompatible / probeModels / requiresCustomEndpoint / note`；提供 `all()`、`find(id)`、`list()`、`detectByEndpoint(endpoint)`、`matchesKey(id,key)` |
| `analysis/AiKeyCipher.java` | API-KEY 加解密组件：AES-256-GCM、12 字节 IV、密文前缀 `enc:v1:`；主密钥三级回落；`isEncrypted/encrypt/decrypt/mask` |
| `analysis/AiProperties.java` | 部署默认值（yml/环境变量）+ 运行期覆盖字段 `runtimeVendor/runtimeEndpoint/runtimeApiKey/runtimeModel`；统一由 `resolveVendor/resolveEndpoint/resolveApiKey/resolveModel` 取值（运行期非空优先） |
| `analysis/AiSettingsService.java` | 配置读写与校验：`settings()`、`vendors()`、`updateSettings()`、`updateModel()`、`testConnection()`、`models()`；启动时 `init()` 做历史配置迁移 |
| `analysis/AiClient.java` | 分析与 OCR 的实际调用方，全部改用 `props.resolve*()`，因此页面保存后下一次调用即生效 |
| `admin/AdminController.java` | 暴露 4 个接口：`GET /api/admin/ai/vendors`、`POST /api/admin/ai/test`、`GET/PUT /api/admin/ai/settings` |
| `static/api.js` | 前端客户端：`Admin.aiVendors()`、`Admin.testAiSettings(payload)`、`Admin.saveAiSettings(payload)` |
| `static/admin/dashboard.html` | 「AI 模型设置」卡片：厂商下拉、地址、密钥、模型、自定义模型、测试连接、保存、刷新、恢复默认 |
| `db/migration-v1.9-ai-vendor.sql` | 幂等补建 `sys_config`（复用 v1.8 表），并输出 3 段校验 SELECT |

数据流：

```text
配置页选择厂商 → 前端带出默认地址/模型 → 用户可覆盖 → 测试连接（POST /ai/test，不落库）
   → 保存（PUT /ai/settings）→ 服务端校验 → 连通性探测 → sys_config 落库（密钥加密）
   → 写 AiProperties 运行期字段 → 下一次 POST /api/analyses 直接使用新配置
```

#### 3.5.2 接口定义

**GET /api/admin/ai/vendors**（仅管理员）

```jsonc
{ "code": 0, "data": { "vendors": [{
  "id": "openai", "name": "OpenAI",
  "defaultEndpoint": "https://api.openai.com/v1", "defaultModel": "gpt-4o-mini",
  "keyPattern": "sk-...", "keyHint": "sk- 开头，16~200 位", "keyUrl": "https://platform.openai.com/api-keys",
  "requiresCustomEndpoint": false, "note": ""
}] } }
```

不返回任何密钥；`keyPattern` 仅用于前端实时提示。

**GET /api/admin/ai/settings**（v1.9 扩展，旧字段保持不变）

```jsonc
{ "code": 0, "data": {
  "aiEnabled": true, "aiUsable": true, "model": "cn:deepseek-v4.1-flash", "models": ["..."],
  "vendor": "custom", "vendorName": "自定义 / 私有网关",
  "endpoint": "http://ai-gateway.example.com/v1",
  "defaultEndpoint": "", "defaultModel": "",
  "apiKeyMasked": "wbk****GZGs", "apiKeySource": "database"
} }
```

`apiKeySource` 取值：`database`（密文入库）、`env`（环境变量/配置文件）、`none`。

**POST /api/admin/ai/test**（不落库，仅探测当前输入）

```jsonc
// 请求
{ "vendor": "deepseek", "endpoint": "https://api.deepseek.com/v1", "apiKey": "sk-xxx", "model": "deepseek-chat" }
// 响应
{ "code": 0, "data": { "ok": true, "latencyMs": 312, "message": "连接成功，可用模型 12 个",
  "vendor": "deepseek", "endpoint": "https://api.deepseek.com/v1", "modelCount": 12 } }
```

`apiKey` 留空时使用已保存密钥（或环境变量）；`endpoint` 留空时取厂商默认值。

**PUT /api/admin/ai/settings**（双模式，按 body 判定）

| 判定条件 | 走的逻辑 | 说明 |
| --- | --- | --- |
| body 含 `vendor` / `endpoint` / `apiKey` / `clearApiKey` 任一 | `updateSettings` | v1.9 完整配置；保存前连通性测试，失败需 `force=true` |
| 仅含 `model` | `updateModel` | v1.7 旧逻辑，只改模型，不动厂商/地址/密钥 |

请求字段见《项目启动说明》7.5；返回 `{code:0,data:{vendor,endpoint,model,apiKeyMasked,apiKeySource}}`。

#### 3.5.3 数据存储格式

存储表（v1.8 起存在，v1.9 复用同一张表，不新增表）：

```sql
CREATE TABLE IF NOT EXISTS sys_config (
  config_key   VARCHAR(64)  NOT NULL,
  config_value VARCHAR(500) NOT NULL,
  updated_at   DATETIME     NOT NULL,
  PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

| config_key | 示例值 | 说明 |
| --- | --- | --- |
| `ai.vendor` | `openai` | 厂商 id，对应 `AiVendor` 注册表；缺失时按地址反查 |
| `ai.endpoint` | `https://api.openai.com/v1` | 实际调用的 base URL（不含 `/chat/completions`） |
| `ai.api-key-enc` | `enc:v1:YzNk...` | AES-256-GCM 密文，**库中无明文** |
| `ai.model` | `gpt-4o-mini` | 模型 id，沿用 v1.8 语义 |

密文格式：`enc:v1:` + Base64(`12 字节 IV` + `密文` + `16 字节 GCM Tag`)，AAD 为空，每次加密随机 IV，同一明文多次加密结果不同。

主密钥来源优先级：

1. 配置项 `ai.secret-key`（Base64 或口令，直接作为密钥材料）；
2. 本机文件 `~/.ai-resume/ai-secret.key`（首次加密时自动生成并落盘）；
3. 进程内随机密钥（仅本次运行有效，重启后旧密文不可解，日志告警）。

取值优先级（运行期）：`sys_config` → `application-ai.yml` → 环境变量（endpoint/model 反向为环境变量可覆盖 yml）。API-KEY 实际取值：管理端密文解密值 → `AI_API_KEY` → 配置文件；`clearApiKey=true` 会删除密文并回落到环境变量。

#### 3.5.4 校验与连通性

| 对象 | 规则 | 失败提示 |
| --- | --- | --- |
| 接口地址 | 非空、`http://` 或 `https://` 开头、可解析为 URI 且含 host、不含空白字符、长度 ≤ 300 | 「接口地址不合法，需以 http:// 或 https:// 开头」 |
| API-KEY | 非空时：不含空白、长度 ≤ 200，且匹配厂商 `keyPattern`（特殊前缀厂商如 `sk-ant-`、`AIza`；其余为通用可见字符集 8~200 位） | 「API-KEY 格式与所选厂商不匹配」 |
| 模型 | 2~80 字符，仅 `字母/数字/: . _ / -`；为空时取厂商默认模型 | 「模型 ID 不合法」 |
| 厂商 | 必须存在于注册表；`requiresCustomEndpoint=true`（Azure OpenAI、自定义）时地址必填 | 「该厂商需要填写接口地址」 |

连通性探测实现：

- 默认 `GET {endpoint}/models`，10 秒超时，请求头 `Authorization: Bearer {key}`；2xx 视为成功并返回 `modelCount`；
- 404 / 405 视为「地址可达但不提供模型列表」，`ok=true`、`modelCount=0`；
- 401 / 403 / 5xx / 连接失败 / 超时 → `ok=false`，`message` 保留可读原因（≤200 字）；
- `probeModels=false` 的厂商（不支持模型列表，如 Azure OpenAI、字节豆包）改为地址可达性探测；
- 保存时默认先探测；`force=true` 或 `skipTest=true` 跳过探测直接写入（供界面「仍然保存」与自动化脚本使用）。

#### 3.5.5 生效与兼容

- **即时生效**：保存成功后写入 `AiProperties` 的 volatile 运行期字段，`AiClient` 每次调用都读 `resolve*()`，无需重启；分析并发进行中的任务使用启动时快照，新任务使用新配置。
- **密钥留空**：`apiKey` 为空表示「不修改」，保留原密文；界面只显示掩码，接口不回传明文。
- **历史迁移**：启动时若 `ai.vendor` 缺失，按当前生效 endpoint 反查预置厂商并补写；匹配不到写 `custom`。仅当运行期厂商为空时执行，避免覆盖页面刚保存的配置。
- **旧请求兼容**：`PUT /ai/settings` 仅含 `model` 时仍走 `updateModel`，行为与 v1.7 一致；`GET /ai/settings` 保留全部旧字段。
- **旧部署兼容**：未在页面配置过的环境继续用 yml/环境变量，`aiUsable` 判定与降级语义（BR-ANALYSIS-03~06）不变。

### 3.6 简历删除 / 导出 / 修改密码（v2.3）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/resumes/batch-delete` | body `{"ids":["1","2"]}`；单份删除也走此接口。返回 `{deleted, skipped, deletedTasks}` |
| GET | `/api/resumes/{id}/export?format=md\|txt` | 返回 `{fileName, format, content}`，由前端生成 Blob 下载（不额外开免鉴权通道） |
| POST | `/api/auth/password` | body `{oldPassword, newPassword}`；成功后吊销该用户全部旧凭证并返回 `{token}` |

```jsonc
// POST /api/resumes/batch-delete
{ "code": 0, "data": { "deleted": 2, "skipped": 0, "deletedTasks": 5 } }

// GET /api/resumes/10/export?format=md
{ "code": 0, "data": {
  "fileName": "林知远.md", "format": "md",
  "content": "# 林知远\n\n> 求职意向：Java 后端开发实习生\n\n…"
} }
```

**删除的约束链**：`analysis_task.resume_id` 是 RESTRICT 外键，删除简历前必须先清该简历的分析任务；`resume_version`、`resume_education/project/skill` 由外键 CASCADE 自动清除。删除语句带 `user_id` 条件，非本人简历计入 `skipped` 且不执行任何写操作。

### 3.7 自定义 API（v2.0）

**配置字段**

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `name` | string | 必填，≤60 字；同一账号内唯一（服务层校验） |
| `method` | enum | GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS，默认 GET |
| `url` | string | 必填，`http://`/`https://` 开头、含 host、不含空白、≤500 字 |
| `headers` | array | ≤20 行，每行 `{name,value,enabled,keep?}`；名称 `[A-Za-z0-9-]{1,40}`；值 ≤500 且不含换行；**值以 AES-256-GCM 密文落库**；`host/content-length/connection/expect/upgrade/transfer-encoding` 拒绝 |
| `params` | array | ≤30 行，每行 `{name,type,value,defaultValue,enabled,description}`；`type ∈ string/number/boolean/array/object`，按类型校验取值 |
| `bodyType` / `body` | enum/string | `none/json/form/text`；JSON 需可解析，form 按 `key=value` 逐行转 URL 编码，正文 ≤20000 字 |
| `group` / `tags` | string/array | 分组 ≤40 字（空→默认分组）；标签 ≤10 个、单个 ≤20 字、总长 ≤200，逗号分隔去重 |
| `timeoutMs` / `retries` | int | 1000~60000（越界收敛），0~3 |
| `enabled` | bool | 停用后允许编辑与测试，`invoke` 返回「该配置已停用」 |

**调用返回结构**

```json
{"code":0,"data":{
  "ok":true,"method":"GET","url":"…?q=Java","status":200,"statusText":"OK",
  "durationMs":3,"attempts":1,
  "requestHeaders":{"Accept":"application/json, */*"},
  "requestBody":"","responseHeaders":{…},
  "body":"…","bodyTruncated":false,"bodyBytes":300
}}
```

- 目标返回 4xx/5xx **不视为接口错误**：仍返回 `code:0`，由 `data.ok=false` 与 `data.status` 表达，避免前端把业务失败当成系统异常；
- 网络层失败同样 `code:0`、`data.status=0`，`data.message` 给中文原因（超时/域名解析/连接拒绝/证书）；
- 重试只作用于网络异常、429、5xx，最多 3 次，间隔 200/400/600ms；4xx 直接返回；
- 响应体最多保留 64KB（`bodyTruncated=true`），统计大小时最多读取 1MB，防止异常响应拖住线程；
- `Authorization/Proxy-Authorization/X-Api-Key/Api-Key/Cookie/Set-Cookie` 在请求与响应回显中均掩码为「前 4 + **** + 后 4」（≤8 位直接 `****`）。

**请求头加密与回显（v2.1）**

| 场景 | 行为 |
| --- | --- |
| 落库 | 加密后的 `headers_json` 形如 `[{"name":"Authorization","value":"enc:v1:…","enabled":true}]`；名称明文（列表要展示），值密文；数据库内不存在明文 |
| 回显 | 配置接口返回 `headers[].value`：敏感头为掩码、`masked=true`、`hasValue=true`；普通头解密后原样返回、`masked=false` |
| 编辑保存 | 前端对 `masked=true` 的行留空并提交 `keep=true`；服务端按名称取原密文解密后重新加密（新 IV），值不丢失；未留空则使用新值；原配置无该头时提示重新填写 |
| 测试请求 | `POST /test` 携带 `id` 时会按该配置解析 `keep`，因此「编辑页直接测试」也能带上已保存的敏感头 |
| 调用 | `fromRow` 解密后再交给 `UserApiInvokeService`，真实请求头仍是明文（仅传输层） |
| 历史数据 | 启动时 `encryptLegacyHeaders()` 扫描全表，把非空且未加密的值加密写回，幂等；失败只告警不阻塞启动 |
| 主密钥 | 复用 `AiKeyCipher`：`ai.secret-key` → `~/.ai-resume/ai-secret.key`（首次自动生成）→ 进程内临时密钥；更换密钥文件后旧密文无法解密，需重新填写 |

**已知边界**：不做内网地址黑名单（课程演示需要访问 `localhost` 与内网网关，故允许私网地址）；生产环境应补 SSRF 防护（解析后校验 IP、禁止链路本地与元数据地址）；不落调用历史，排障依赖页面即时结果与后端 debug 日志；请求头加密沿用本机密钥文件，多实例部署需共享同一把主密钥。

## 4. 技术问题与解决方案记录

| # | 问题 | 解决方案 | 状态 |
| --- | --- | --- | --- |
| P1 | data.sql 导入报 `ERROR 1406 Data too long for column 'name'` | mysql 客户端默认字符集非 utf8mb4。加 `--default-character-set=utf8mb4` 重新导入 | ✅ 已解决 |
| P2 | 种子密码哈希格式未知 | 反推验证：`120000:base64(salt):base64(hash)` = PBKDF2-HmacSHA256/120000/32B | ✅ 已解决 |
| P3 | JDK 25 与 Spring Boot 3.5 ASM 兼容风险 | 编译目标锁定 release 17（字节码 61） | ✅ 已解决 |
| P4 | MySQL 5.5 服务端与 Connector/J 8.x 不兼容 | 驱动锁定 5.1.49 | ✅ 已解决 |
| P5 | `@Async` 自调用不生效 | 异步逻辑抽到独立 Bean `AnalysisRunner` | ✅ 已解决 |
| P6 | 被禁用账号已签发的 Token 仍可用 | 禁用时 `revokeByUser`；拦截器每次请求复核状态 | ✅ 已解决 |
| P7 | 启动报 `Port 64042 was already in use` | 显式传 `--server.port=8080` | ✅ 已解决 |
| P8 | 发起分析报「系统繁忙」 | `AnalysisDao.insert` 占位符错位，修正为 9 个 `?` + `0` | ✅ 已解决 |
| P9 | e2e 测试二次运行报「账号已存在」 | 测试账号按时间戳生成 | ✅ 已解决 |
| P10 | **v1.0 文档记载「前端已接入」，实际 `static/api.js` 仍是本地桩（依赖 `Store.db`、无 fetch），且页面已移除 store.js，导致所有页面接口调用报错** | 按后端契约重写 `static/api.js` 为 fetch 客户端；补充 401 跳转、超时与网络异常处理；e2e 增加静态资源断言防回归 | ✅ 已解决（本阶段核心修复） |
| P11 | 环境重置导致 `ai_resume` 库不存在，服务启动即失败 | 按 schema.sql + data.sql 重新初始化，并在本文档第 7 节固化命令 | ✅ 已解决 |
| P12 | 浏览器截图时页面入场动画未结束，工作台只截到部分卡片 | CDP 走查脚本在断言通过后等待 1.4s 再截图 | ✅ 已解决 |
| P13 | 岗位匹配度在 JD 无可识别关键词时为 0，易被误解为「完全不匹配」 | 接口返回 `keywordCount`，前端显示「JD 无可识别关键词」而非 0 分条 | ✅ 已解决 |
| P14 | 身份证号与 18 位银行卡号正则冲突 | 先按省级代码 + 出生日期判定身份证，其余 16~19 位判定银行卡 | ✅ 已解决 |
| P15 | 导入解析把第二个项目标题并入上一段描述，并在结尾重复添加同一项目 | 重写项目分段：短行且非日期/非句子视为新项目标题；`flushProject` 后清空标题，避免重复 | ✅ 已解决 |
| P16 | 技能同时命中 `Spring Boot` 与 `Spring`（同一词多处出现时只遮蔽首处） | 循环遮蔽全部命中位置，并按「短词被长词包含则丢弃」过滤 | ✅ 已解决 |
| P17 | 大文件/伪造扩展名上传风险 | 容器层 6MB、业务层 5MB；扩展名白名单 + 文件头校验（%PDF / PK）；DOCX 解压上限 8MB、XML 禁用 DTD | ✅ 已解决 |
| P18 | 扫描版 PDF 无文字、加密 PDF 无法解析；中文 PDF 抽取效果取决于字体是否嵌入 ToUnicode | 明确提示「未提取到有效文字/已加密」；仅解析前 10 页；导入结果一律要求人工核对 | ✅ 已解决 |
| P19 | 多简历对比出现同分时「最匹配」标记只有一个，易被误解 | 排序按匹配度 → 完整度；页面同时展示完整度最高标记，并在说明中注明两项指标视角不同 | ✅ 已解决 |
| P20 | AI 模式从未真正跑通：此前只有「未启用→规则」路径，客户端代码未经 HTTP 验证 | 新增本地 Mock OpenAI 服务与 `ai_e2e_test.py`，覆盖成功/格式异常/超时/HTTP 500/不可达 5 条路径 | ✅ 已解决（9/9） |
| P21 | `ai.enabled=true` 但服务未启动时，任务直接失败，与 BR-ANALYSIS-04「AI 不可用时降级」不一致 | `AiClient` 对连接失败/域名错误/连接超时标记 `unavailable`；`AnalysisRunner` 捕获后按规则模式完成并写 `reason` | ✅ 已解决 |
| P22 | 降级成功后结果页只显示「规则模式」，学生看不到原因 | 成功结果页新增 `reason` 提示条；管理端任务列表沿用同一字段 | ✅ 已解决 |
| P23 | 后台「系统状态」卡为硬编码：AI 永远显示正常、存储写「本地浏览器」、页脚称「原型演示数据」 | 新增 `quality.system` 返回真实 AI 开关/模型、MySQL 版本与 Token 策略；后台动态渲染并删除过期文案 | ✅ 已解决 |
| P24 | 质量指标分母不一致易误读（有用率按已反馈、覆盖率按成功任务；方式分布按成功任务，而顶部规则占比按全部任务） | 卡片文案标注分母；文档 3.4 固化口径；e2e 断言 `modes.count` 之和 = 成功任务数 | ✅ 已解决 |
| P25 | 真实网关调用返回 `400 Invalid HTTP request received.`，而相同 body 用 curl 正常 | JDK HttpClient 默认尝试 h2c 升级，网关不兼容；固定 `HttpClient.Version.HTTP_1_1` 后成功 | ✅ 已解决 |
| P26 | `ai.enabled=true` 但未注入密钥时，任务必然 401/失败，且后台仍显示「已启用」 | 新增 `AiProperties.isUsable()`（启用+地址+密钥）；不完整时直接规则模式；后台区分「已启用/未配置密钥」 | ✅ 已解决 |
| P27 | 主流程 e2e 原先依赖「默认规则模式」，服务启用 AI 后断言失败 | e2e 创建任务统一传 `demo.mode=rule`，测试与 AI 开关解耦；AI 路径由 Mock/真实脚本覆盖 | ✅ 已解决 |
| P28 | 审查建议用 CHECK 约束枚举，但 MySQL 5.5 解析后忽略 CHECK | 改为 6 个 BEFORE INSERT/UPDATE 触发器（`SIGNAL SQLSTATE '45000'`），实测非法状态被拒 | ✅ 已解决 |
| P29 | 加事务锁后，`@Async` 可能在事务提交前启动、读不到任务；lambda 捕获非 final 变量编译失败 | 用 `TransactionTemplate` 提交后再 `runner.run`；`analysisMode/failDemo` 复制为 final 局部变量 | ✅ 已解决 |
| P30 | `feedback_type` 数据库小写、其他枚举大写；改大写后 API 契约会变 | 数据库统一 `USEFUL/USELESS`，Service 层双向映射，API 仍返回 `useful/useless` | ✅ 已解决 |
| P31 | 种子数据简历 4/5/6 有 `resume.version` 但无版本记录 | 补 v7~v11 快照并加入迁移；运行时校验版本差异查询返回 0 行 | ✅ 已解决 |
| P32 | 改完 `static/*.html` 后页面不生效，误以为代码没写对 | `spring-boot:run` 从 `target/classes` 读静态资源；改前端必须重新编译并重启（已写入第 7 章命令注释） | ✅ 已解决 |
| P33 | 生成 OCR 素材时，CDP 截到的不是简历而是「已阻止此页面」 | 企业策略禁止 headless 访问 `file://`；改用 `python -m http.server` 托管后截 `http://127.0.0.1`；`make_shot.js` 同时支持 http URL | ✅ 已解决 |
| P34 | 想用 PowerShell `System.Drawing` 画测试图片，被安全策略拦截 | 改为「HTML + Edge CDP 截图」生成图片，不依赖 `Add-Type` | ✅ 已解决 |
| P35 | `.doc` 只有伪造文件头的负向用例，正向路径无法验证 | 系统装有 Word，用 COM `SaveAs(wdFormatDocument=0)` 生成真实 97-2003 二进制文档，并固化到 `scripts/fixtures/sample.doc` | ✅ 已解决 |
| P36 | 评测指标 `keywordCover` 按「全部 JD 关键词」计算，高匹配用例失真（case1 恒为 0.4） | 改为按「期望缺失关键词」（简历原文未出现的关键词）计算；同时把建议原文写入 JSON，后续调指标可离线复算，不必重调模型 | ✅ 已解决 |
| P37 | 模型下拉与自定义输入同处 `grid-2`，自定义隐藏时下拉只占半宽、右侧大片留白 | 改为单列 `stack`，下拉占满整行，自定义字段按需追加在下方 | ✅ 已解决 |
| P38 | 走查截图里下拉显示成 `Auto`，与系统状态中的当前模型不一致 | 走查脚本切换自定义后未恢复原值；改为先记录初始 `value`，截图前原样回填 | ✅ 已解决 |
| P39 | 连通性测试请求内网网关报 `400 Invalid HTTP request received.`（与 P25 同源） | 探测请求同样固定 `HttpClient.Version.HTTP_1_1`，与 `AiClient` 保持一致 | ✅ 已解决 |
| P40 | `mvn -o test` 报 surefire 插件未下载 | 首次执行需联网，Maven 自动拉取 `maven-surefire-plugin 3.5.4` 后才可离线运行 | ✅ 已解决 |
| P41 | PowerShell 下 `mvn test -Dtest=A,B ...` 参数被拆词、`-Dspring.main.banner-mode=off` 被当作 goal | 给 `-Dtest=...`、`-DfailIfNoTests=false` 整体加引号；去掉 banner 参数 | ✅ 已解决 |
| P42 | 集成测试 `legacyConfigMigratesVendor` 期望 `moonshot` 实际 `custom`，`legacyModelUpdateKeepsVendorConfig` 期望非空实际 `null` | 前序用例已把 `AiProperties` 运行期厂商设为 `custom`，`init()` 只在运行期厂商为空时迁移，造成测试间状态污染；用 `@BeforeAll` 备份 `sys_config`、用例前清空 runtime 字段模拟全新启动、`@AfterAll` 还原 | ✅ 已解决 |
| P43 | 厂商下拉只切换不保存时，地址/模型输入框仍残留上一厂商的值 | 切换时统一调用 `applyVendor(vendor)` 覆盖地址与模型输入框，自定义厂商清空待填，已存密钥输入框保持空（沿用语义） | ✅ 已解决 |
| P44 | `UserApiConfigDao.insert` 用独立的 `SELECT LAST_INSERT_ID()` 取主键，连接池可能换连接拿到 0，偶发「保存失败」 | 改为 `GeneratedKeyHolder` + `RETURN_GENERATED_KEYS`，主键与 INSERT 同一条语句返回；e2e 连续新建 6 个配置全部拿到正确 ID | ✅ 已解决 |
| P45 | API 控制台列表 4 列在 1440 宽下溢出，操作列被裁切并出现横向滚动条 | 更新时间并入名称副行（「分组 · 更新 MM-DD HH:mm」），次级操作（编辑/复制/删除）改图标按钮 + `title`，列数降为 3，表宽自适应 | ✅ 已解决 |
| P46 | 规则模式（未配置密钥）下 `browser_check.js` 硬等模型下拉出现 >5 个选项，走查在管理端超时失败 | 断言改为「加载完成或回落」：出现「未启用/未配置密钥/读取失败/自定义模型」任一即继续，回落分支不计失败 | ✅ 已解决 |
| P47 | 新增 e2e 用例「他人配置不可读取」误用配置属主 token 发起请求，断言恒失败 | 改用另一测试账号的 token，并补充「他人列表不含该配置」；131/131 通过 | ✅ 已解决 |
| P48 | 环境重置后「敏感信息示例 · 演示用」简历缺失，走查跳过截图 03 | 按文档说明手工重建（手机号/身份证/银行卡三条虚构数据），确认 `sensitiveWarnings` 返回 3 条脱敏结果；该数据仍不属于种子数据，重置后需再建 | ✅ 已处理 |
| P49 | 请求头加密后，编辑页把回显的掩码当成新值提交，保存一次就把密文覆盖成「Bea****5678」 | 接口增加 `masked/hasValue`，前端对掩码行留空并提交 `keep=true`；服务端按名称取原密文解密后重新加密（新 IV），未留空才用新值；原配置没有该头时明确提示 | ✅ 已解决 |
| P50 | 老库中已有明文请求头，与密文混存，直接加密会漏掉历史数据 | 解密方法对无前缀值原样返回；启动时 `encryptLegacyHeaders()` 全表扫描并加密写回（幂等、失败仅告警）；实测手工插入的明文行在重启后变为 `enc:v1:` 密文 | ✅ 已解决 |
| P51 | JPG 图片简历会被当作 PNG 发送（`data:image/png;base64,` 硬编码），网关严格校验时拒绝 | `AiClient.ocrImage` 增加 mime 参数；导入服务按**文件头**判定真实格式再传 `image/jpeg`/`image/png`；补 JPG 单元测试与 e2e 用例 | ✅ 已解决 |
| P52 | 直接上传的图片不做任何归一化，5MB 原图 base64 后约 6.7MB，易触发网关 413 或超时 | 新增 `prepare()`：单边 >2400px 等比缩小、PNG >3MB 转 JPEG、仍超限才降采样并给出提示；图片与 PDF 页共用同一套归一化 | ✅ 已解决 |
| P53 | 扫描件判定只看「整份是否 ≥20 字」，带页眉文字的扫描件会被误判为文字版从而跳过 OCR，导入出空白简历 | 改为逐页统计：全部页低于 50 字且总长不足 200 字才判为扫描件；混合文档走文字版并对空白页给出提示 | ✅ 已解决 |
| P54 | 页数上限直接 `Math.min` 截断，用户不知道后面的页被丢弃 | `OcrResult` 携带 notes，返回「共 N 页，仅识别前 K 页，后续页面未导入」；导入服务合并进 `warnings` | ✅ 已解决 |
| P55 | 扫描件逐页识别时任一页异常即整体失败，已成功的页也被丢弃 | 逐页 try-catch，单页失败记录页号后继续；全页失败才抛错并带上首个错误原因 | ✅ 已解决 |
| P56 | TXT 带 UTF-8 BOM 时首行残留 `U+FEFF`，姓名识别结果带不可见字符 | `decodeText` 解码后剥离 BOM；补单元测试与 e2e 用例 | ✅ 已解决 |
| P57 | 网关偶发 429/5xx 时一次失败即终止（自定义 API 有重试，AI 调用没有，策略不一致） | `postChat` 对 429/5xx 重试 1 次（间隔 300ms），超时与 4xx 不重试，避免突破 30 秒阈值 | ✅ 已解决 |
| P58 | `ai.ocr-dpi`、`ai.ocr-max-pages` 只有下限没有上限，配置成 1200 DPI / 500 页会 OOM 或长时间占用线程 | DPI 钳制 96~300、页数钳制 1~20；补「配置越界被钳制」单元测试 | ✅ 已解决 |

## 5. 测试记录（2026-10-25）

### 5.1 端到端自动化测试

- 命令：`cd ai-resume-system/server/scripts && python e2e_test.py`
- 结果：**102/102 通过**（v1.0 44 → v1.1 58 → v1.2 77 → v1.4 83 → v1.6 86 → v1.7 102）
- 运行环境：主服务以 `ai` profile 启动（真实 AI 已启用），e2e 统一传 `demo.mode=rule`，因此结果与 AI 开关无关、可重复。
- 本阶段新增覆盖（16 项）：
  - TOKEN-01：管理端系统信息返回 `tokenActive ≥ 1`，证明登录凭证已落库；
  - FR-RESUME-04 扩展：真实 `.doc`（fixtures/sample.doc）解析姓名/意向/手机号/技能；损坏 DOC 被拒绝；
  - FR-RESUME-05：图片简历 OCR（姓名/手机/技能）、扫描件 PDF OCR、伪造 PNG 拒绝、不支持扩展名拒绝；OCR 未启用时自动 SKIP 而非误报失败；
  - FR-ADMIN-03：学生 403、读取设置、自定义模型保存、立即生效且持久化、非法 ID 拒绝、恢复默认、模型清单来自网关。
- 测试素材：`scripts/fixtures/sample.doc`（24KB，Word 97-2003）、`resume_photo.png`（14KB）、`scan_resume.pdf`（26KB），由 Word COM 与 CDP 截图生成后固化，运行 e2e 不依赖 Office。
- 历史覆盖保持：DR-001 并发、DR-007 技能去重、FR-STAT-02（6 项）、FR-JOB-03、FR-RESUME-04 基础格式、FR-RESUME-03、FR-INTERVIEW-01、前端接入与原有验收条件。

### 5.2 浏览器走查

- 脚本：`cd ai-resume-system/server/scripts && node browser_check.js`（Edge headless + CDP，无额外依赖）
- 覆盖链路：学生登录 → 工作台 → 岗位匹配度 → 敏感提醒 → 分析结果/模拟面试 → 文件导入 → 多简历对比 → **API 控制台（新建→测试→保存→调用→清理）** → **管理员质量看板、系统状态与 AI 模型设置（下拉清单或规则模式回落、自定义模式切换）**
- 页面扫描：学生端 resumes / resume-edit / jobs / analysis / records / versions / compare / **api-console** + 管理端 dashboard / users / tasks，共 **12 页**；`Runtime.exceptionThrown` 计数为 **0**
- 管理端断言：质量卡 4 张渲染完成、Top 缺失关键词 8 个、系统状态包含「本机 MySQL 5.5.15」与凭证存储说明；AI 模式下模型下拉 **85 个选项**（84 模型 + 自定义）、`badge=已连接网关`，规则模式下回落「自定义模型…」+「未启用」提示（v2.0 起两种分支都算通过）
- API 控制台断言：测试请求返回 `状态码 200 OK`、保存后列表出现该配置、行内「测试」可发起真实调用、右侧生成含 `invokeByName` 的示例代码、结束后自动删除走查配置
- 截图：`docs/screenshots/01-工作台.png` ~ `09-API控制台.png`
- 说明：敏感信息环节按名称查找「敏感信息示例」简历；若该演示数据不存在则跳过该项，其余检查照常执行（本次已按其文档手工重建后截取 03）。

### 5.3 AI 链路联调（Mock）

- 脚本：`cd ai-resume-system/server/scripts && python ai_e2e_test.py`（可加 `--skip-build` 复用已打包 jar）
- 机制：脚本自动打包 jar，启动 8081 应用与 8099 Mock OpenAI 服务；使用独立测试账号；结束后关闭进程并保留 `target/ai_e2e_server.log`
- 结果：**9/9 通过**
  - AI-01/02：正常返回 → 任务成功、`mode=ai`、分数 88、缺失 Kafka、建议 2 条；
  - AI-03：返回非 JSON → 失败且原因含「格式」；
  - AI-04/05：Mock 延迟 5 秒 + 超时阈值 2 秒 → 失败且原因含「超时」；
  - AI-06：HTTP 500 → 失败且原因含 `HTTP 500`；
  - AI-07/08：停止 Mock 后 → 任务成功、`mode=rule`、`reason` 含「降级」、分数为规则分。
- 边界：Mock 只验证 HTTP 契约、超时与状态流转，不代表真实模型效果；真实供应商验证见 5.4。

### 5.4 真实 AI 供应商联调

- 配置：`application-ai.yml`，endpoint `http://ai-gateway.example.com/v1`，模型 `cn:deepseek-v4.1-flash`；密钥仅从环境变量 `AI_API_KEY` 读取。
- 启动（AI 模式）：
  ```bash
  AI_API_KEY=<your-key> mvn spring-boot:run \
    -Dspring-boot.run.arguments="--server.port=8080 --spring.profiles.active=ai"
  ```
- 验证脚本：`AI_API_KEY=<your-key> python ai_real_test.py`（自动打包、启动 8081、注册独立账号、创建简历/岗位、发起分析并轮询）
- 结果：**7/7 通过**
  - 任务成功、`mode=ai`、未触发降级；
  - 返回匹配度 60~62、缺失 `Redis`/`消息队列`、4~5 条结构化建议；
  - 模型耗时约 4.6~5.3 秒，低于 30 秒阈值；HTTP 错误原因可读。
- 主服务已用 `ai` profile 验证真实分析：`mode=ai`、60 分、4 条建议、5.3 秒成功（复跑 62 分/5 条/4.8 秒）。
- 安全：密钥未写入任何项目文件（已全仓检索确认）；文档与配置只保留环境变量名。

### 5.5 模型效果评测（v1.7）

- 脚本：`cd ai-resume-system/server/scripts && AI_API_KEY=<key> python model_eval.py`（`--runs N` 调采样次数、`--models` 指定模型、`--list` 只列清单）
- 机制：直连网关但**复用生产 prompt**（脚本启动时读取 `AiClient.java` 校验 6 处关键片段，不一致直接退出）；3 个用例覆盖高/部分/跨领域匹配；结果写入 `scripts/eval_results/*.json`（含建议原文，可离线复算）
- 完整报告：《模型评测报告-AI简历优化系统.md》，要点：
  - **评分一致性**：`cn:deepseek-v4.1-flash` × case1 × 3 次 = 72/62/66，均值 66.7、σ=5.03、极差 **10**（阈值 ≤10 通过）；
  - **建议质量**：glm-5.3-flash 99.3 > deepseek-v4.1-flash 99.0 > kimi-k2.5 92.3（kimi 漏「CSS 动画」、case1 输出 6 条超限）；
  - **多模型对比**：glm 均分 42.7 / 5.50s / 478 tokens；deepseek 40.0 / 5.81s / 955 tokens；kimi 36.0 / 4.70s / 596 tokens；hy4-preview 探测超时（>60s）不可用；
  - **关键结论**：跨模型分数不可比（case1 上 glm 78 vs kimi 58，差 20 分），切换模型会改变分数基线；建议默认模型改为 `cn:glm-5.3-flash`（tokens 约省 50%、质量分最高）。
- 指标局限：自动化代理指标接近饱和（99~100），不能替代人工评分；样本量 3×3 仅够判断趋势。

### 5.6 v1.9 单元与集成测试

- 命令（在 `ai-resume-system/server` 下）：
  ```bash
  mvn test "-Dtest=AiVendorTest,AiKeyCipherTest,AiPropertiesTest,AiSettingsServiceTest,AiVendorConfigIntegrationTest" "-DfailIfNoTests=false"
  ```
- 结果：**单元 29/29、集成 7/7 全部通过**（2026-11-15 实测）。

| 测试类 | 类型 | 用例数 | 覆盖点 |
| --- | --- | --- | --- |
| `AiVendorTest` | 单元 | 7 | 11 个厂商注册表完整性、id 唯一、默认地址/模型非空、`find` 未命中返回空、按地址反查（含结尾 `/`、大小写、未知地址→custom）、密钥格式校验（sk-/AIza/sk-ant/id.secret/通用） |
| `AiKeyCipherTest` | 单元 | 5 | 加密后以 `enc:v1:` 开头且不含明文、同明文两次密文不同、解密还原、`isEncrypted` 判定、掩码只留前 3 后 4 |
| `AiPropertiesTest` | 单元 | 5 | 运行期字段优先于 yml、清空后回落、`resolveApiKey` 优先级、`isUsable` 判定（缺地址/缺密钥/未启用） |
| `AiSettingsServiceTest` | 单元（Mockito） | 12 | 厂商清单不含密钥、地址非法/密钥格式不符/模型非法/需自定义地址未填的拒绝、密钥留空沿用、`clearApiKey` 清除、`force` 跳过探测、探测 2xx/401/连接失败、仅传 model 走旧逻辑 |
| `AiVendorConfigIntegrationTest` | 集成（真实 MySQL） | 7 | 保存后落库为密文、读取回显掩码、厂商切换覆盖地址与模型、历史 `ai.model` 迁移补写 `ai.vendor`、仅改模型不影响厂商、清除密钥回落、非法配置拒绝 |

- 集成测试隔离：`@BeforeAll` 备份 `sys_config` 全部行，`@AfterAll` 还原；每个用例前清空 `AiProperties` 运行期字段并删除 `ai.*` 配置，模拟「全新启动」，避免用例间状态污染（见 P42）。

### 5.7 v2.0 / v2.1 自定义 API 测试记录（2026-11-16）

**端到端**：`cd ai-resume-system/server/scripts && python e2e_test.py` → **136/136 通过**（规则模式；AI 模式下 OCR 4 项与模型清单 1 项不再跳过，约 141 项）。

- 新增覆盖按前缀分组（共 39 项：CUSTOM 37 + 前端 2）：
  - `CUSTOM-API-01`（20 项）：页面可访问与 api.js 能力、未登录 401、列表与上限、新建、字段落库、重名拒绝、6 类校验拒绝（方法/协议/托管头/number 参数/JSON 体/标签上限）、关键字/分组/标签过滤、更新、复制命名、删除清理；
  - `CUSTOM-API-02`（13 项）：草稿测试状态码与耗时、中文参数 UTF-8 拼接、草稿不落库、调用已存配置（attempts=1）、默认值参与拼接、敏感头掩码、404 不重试、500 重试 3 次后成功、1000ms 超时中断、80KB 响应截断、停用配置拒绝调用；
  - `CUSTOM-API-03`（4 项）：他人配置不可读/改/删、列表按账号隔离。
- 目标服务：脚本内置 `MockTarget`（127.0.0.1:8099）提供 `/echo`、`/flaky`、`/slow`、`/big`、404 五条路由；端口占用时相关用例 SKIP 并打印原因，不误判失败。
- 清理：用例创建的配置在末尾统一删除，并按 ID 校验列表已无残留，可重复执行。

**单元与集成**：`mvn test "-Dtest=UserApiConfigServiceTest,UserApiInvokeServiceTest,UserApiConfigIntegrationTest" "-DfailIfNoTests=false"` → **37/37 通过**（单元 27 + 集成 10）。

| 测试类 | 类型 | 用例数 | 覆盖点 |
| --- | --- | --- | --- |
| `UserApiConfigServiceTest` | 单元（Mockito） | 20 | 名称/方法/地址校验、请求头与参数类型、JSON 请求体、标签去重、超时与重试边界、同名与数量上限、归属校验、复制命名、调用参数组装；**请求头加密落库、掩码回显、keep 沿用、历史明文兼容与启动迁移** |
| `UserApiInvokeServiceTest` | 单元（本机 HttpServer） | 7 | 查询参数拼接与编码、POST 请求体、500 重试、4xx 不重试、超时、连接拒绝、64KB 截断、敏感头掩码 |
| `UserApiConfigIntegrationTest` | 集成（真实 MySQL + HttpServer） | 10 | 创建、分组/标签过滤、更新、复制、跨用户隔离、真实调用、草稿测试、异常校验、删除；**库内密文与敏感头解密调用** |

- 集成测试数据统一 `IT-API-` 前缀，开始与结束自动清理，不影响演示账号与其它配置。
- 累计：单元 29（v1.9）+ 27（v2.0/v2.1）= **56**；集成 7（v1.9）+ 10（v2.0/v2.1）= **17**。

## 6. 待处理事项（下次开发入口）

- [x] 模型效果评测：`model_eval.py` 已产出评分一致性、建议质量、多模型对比基线，见 5.5 与《模型评测报告》。
- [x] Token 持久化：`auth_token` 表 + 缓存，实测重启后旧 Token 仍有效；禁用账号即删库踢下线。
- [x] `.doc` 导入：POI HWPF，真实 24KB `.doc` 识别姓名/意向/手机/5 项技能。
- [x] 扫描件与图片 OCR：图片 2.0s、扫描件 PDF 2.9s；未配置视觉模型时明确提示。
- [x] 模型选择与自定义模型（FR-ADMIN-03）：84 个网关模型 + 自定义 ID，`sys_config` 持久化。
- [x] AI 运行期降级：服务不可达时自动切规则模式并标注原因，已通过 AI-07/08。
- [x] 质量与反馈统计（FR-STAT-02）：后台看板已上线，已纳入 136/136 回归。
- [x] 数据库审查问题：DR-001~DR-007 关闭，DR-008/010 暂缓，DR-009 不适用；详见审查报告第七章。
- [x] 选做项：岗位匹配度排序、多简历对比、多格式简历导入、扫描件 OCR、模拟面试题、敏感信息提醒——全部完成。
- [x] AI 厂商选择与配置（v1.9，FR-ADMIN-03 扩展）：11 个预置厂商 + 自定义地址 + 加密密钥 + 保存前连通性测试 + 即时生效；历史配置启动自动迁移。
- [x] AI 厂商配置测试（v1.9）：单元 29/29、集成 7/7，覆盖厂商切换、自定义配置、异常场景。
- [x] 文档同步（v1.9）：启动说明新增第 7 章「AI 模型厂商配置」；本文新增 3.5 模块结构与 5.6 测试记录；README 增补配置入口。
- [x] 学生端自定义 API（v2.0，EXT-API-01~05）：配置 CRUD + 在线测试 + 服务端代发调用 + 控制台页面 + 前端离线兜底；e2e 新增 39 项、单元/集成 37 项、走查扩至 12 页并新增截图 09。
- [x] 请求头值加密（v2.1，EXT-API-06）：`enc:v1:` 密文落库、敏感头掩码回显、`keep` 沿用、历史明文启动迁移；实测库内无明文，编辑保存后调用仍能带原凭据。
- [ ] 待确认项跟踪（不阻塞当前版本）：
  - **自定义 API 的需求编号与验收口径**：需求分析说明书 v0.8 未收录该功能，本文暂记 `EXT-API-01~05`；是否纳入正式需求、是否需要调用历史留存，待教师/项目负责人确认后在需求文档补编号与 AC；
  - BR-RESUME-01 必填口径：实现与原型一致（教育、项目各至少 1 条），需求文档标注【待确认】；
  - BR-JOB-01 JD 长度下限：实现取 10 字（与原型一致），需求文档写 50 字，待与教师确认；
  - 默认模型是否切换为 `cn:glm-5.3-flash`（评测建议，需教师/项目负责人确认）；
  - 选做功能的业务口径（匹配度是否计入正式成绩、面试题是否需保存历史）待教师确认；
  - 导入与 OCR 精度要求、原始文件留存策略待确认（当前只解析不落盘，结果需人工核对）。
- [ ] 已知能力边界：`.doc` 内表格/图片不提取；OCR 有版面丢失风险且最多 3 页；Token 缓存多实例最多滞后 30 秒；评测缺人工金标准。

## 7. 下次继续开发指引

```bash
# 1) 初始化数据库（仅首次或环境重置后执行）
cd ai-resume-system/db
"C:/Program Files/MySQL/MySQL Server 5.5/bin/mysql.exe" -uroot -p --default-character-set=utf8mb4 < schema.sql
"C:/Program Files/MySQL/MySQL Server 5.5/bin/mysql.exe" -uroot -p --default-character-set=utf8mb4 ai_resume < data.sql
# 已有库升级（按版本顺序各执行一次）：migration-v1.6-db-review.sql → migration-v1.7-auth-token.sql
#   → migration-v1.8-ai-settings.sql → migration-v1.9-ai-vendor.sql → migration-v2.0-custom-api.sql（均幂等，全新库无需执行）

# 2) 启动后端（同时托管前端页面）
cd ai-resume-system/server
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
# 或：mvn -DskipTests package 后
#    java -jar target/ai-resume-server-1.0.0.jar --server.port=8080

# 2.1) 以真实 AI 模式启动（密钥只从环境变量读取）
AI_API_KEY=<your-key> mvn spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8080 --spring.profiles.active=ai"

# 3) 浏览器访问 http://localhost:8080
#    学生：linzhiyuan / 123456；管理员：admin / admin123

# 4) 回归测试
cd ai-resume-system/server/scripts
python e2e_test.py          # 主流程接口级 136 项（含并发、Token、DOC/OCR、模型设置、自定义 API）
node browser_check.js       # 页面级走查 12 页（含文件导入/多简历对比/API 控制台/模型设置，截图 01~09）
python ai_e2e_test.py       # AI 链路联调 9 项（自动启动 8081 应用 + 8099 Mock AI，结束自动关闭）
AI_API_KEY=<your-key> python ai_real_test.py   # 真实供应商验证 7 项（8081 + 真实模型）
AI_API_KEY=<your-key> python model_eval.py     # 模型效果评测（一致性 + 建议质量 + 多模型对比）

# 4.1) v1.9 AI 厂商配置：单元 29 项 + 集成 7 项（集成需本机 MySQL）
cd ai-resume-system/server
mvn test "-Dtest=AiVendorTest,AiKeyCipherTest,AiPropertiesTest,AiSettingsServiceTest,AiVendorConfigIntegrationTest" "-DfailIfNoTests=false"

# 4.2) v2.0 自定义 API：单元 21 项 + 集成 9 项（集成需本机 MySQL）
mvn test "-Dtest=UserApiConfigServiceTest,UserApiInvokeServiceTest,UserApiConfigIntegrationTest" "-DfailIfNoTests=false"

# 5) 修改后端或 static 资源后必须重新编译（服务从 target/classes 读取静态文件）
cd ai-resume-system/server && mvn -q -DskipTests compile
```

代码结构：
```text
ai-resume-system/
  db/                    schema.sql、data.sql、migration-v1.6-db-review.sql、migration-v1.7-auth-token.sql、migration-v1.8-ai-settings.sql、migration-v1.9-ai-vendor.sql、migration-v2.0-custom-api.sql
  server/
    pom.xml              Spring Boot 3.5.7 / release 17 / mysql-connector 5.1.49 / pdfbox 2.0.30 / poi-scratchpad 5.2.5
    src/main/resources/
      application.yml    数据源、Token TTL、AI 默认配置、OCR 配置、multipart 限制（密钥走环境变量）
      application-ai.yml AI 模式 profile（真实 endpoint/model、OCR 模型，密钥仅读 AI_API_KEY）
      static/            前端页面（api.js 为 fetch 实现，含 importFile/compare/aiModels/aiVendors/testAiSettings/saveAiSettings/Custom）
        student/compare.html               多简历对比页（FR-JOB-03）
        student/api-console.html           自定义 API 控制台（v2.0：列表/筛选/弹窗/测试/调用/代码示例）
        admin/dashboard.html               概览 + 质量看板 + AI 模型设置（厂商下拉/地址/密钥/模型，FR-STAT-02/FR-ADMIN-03）
    src/main/java/cn/course/airesume/
      auth/ config/ common/ user/ resume/ job/ analysis/ admin/ customapi/
        auth/TokenService.java              DB 持久化凭证 + 30s 缓存 + 续期节流（TOKEN-01）
        resume/ResumeTextService.java       简历文本视图（分析、匹配、对比共用）
        resume/SensitiveInfoScanner.java    敏感信息扫描（FR-RESUME-03）
        resume/ResumeImportService.java     PDF/DOCX/DOC/TXT/图片导入解析（FR-RESUME-04/05）
        resume/ResumeOcrService.java        扫描件与图片 OCR（FR-RESUME-05）
        job/JobMatchService.java            岗位匹配度排序 + 多简历对比（FR-JOB-02/03）
        analysis/InterviewService.java      模拟面试题生成（FR-INTERVIEW-01）
        analysis/AiVendor.java              v1.9 预置厂商注册表（11 项 + 密钥格式 + 按地址反查）
        analysis/AiKeyCipher.java           v1.9 API-KEY AES-256-GCM 加解密与掩码
        analysis/AiProperties.java          AI 配置（yml/环境变量 + 运行期覆盖，resolve* 统一取值）
        analysis/AiSettingsService.java     v1.9 配置读写/校验/连通性测试/启动迁移（FR-ADMIN-03）
        analysis/AiClient.java              分析 + 多模态 OCR 调用（统一 HTTP/1.1 与错误语义）
        admin/AdminController.java          管理端接口（含 /ai/vendors、/ai/test、/ai/settings）
        admin/AdminService.java             用户/任务/统计 + 质量反馈看板（FR-STAT-01/02）
        customapi/UserApiConfigService.java  自定义 API 校验/持久化/调用编排（v2.0）
        customapi/UserApiConfigDao.java      配置读写 + 启动自愈建表（v2.0）
        customapi/UserApiInvokeService.java  服务端代发：超时/重试/截断/脱敏（v2.0）
        customapi/UserApiController.java     /api/user-apis 接口（v2.0）
    src/test/java/cn/course/airesume/
      analysis/
        AiVendorTest.java                   单元 7 项：厂商注册表
        AiKeyCipherTest.java                单元 5 项：加解密与掩码
        AiPropertiesTest.java               单元 5 项：配置解析与优先级
        AiSettingsServiceTest.java          单元 12 项：校验/探测/兼容（Mockito）
        AiVendorConfigIntegrationTest.java  集成 7 项：真实 MySQL 读写与迁移隔离
      customapi/
        UserApiConfigServiceTest.java       单元 14 项：校验/上限/归属/复制/参数组装
        UserApiInvokeServiceTest.java       单元 7 项：拼接/重试/超时/截断/掩码
        UserApiConfigIntegrationTest.java   集成 9 项：真实 MySQL + HttpServer
    scripts/e2e_test.py  端到端验收测试（136 项，含内置 8099 Mock 目标服务）
    scripts/browser_check.js  浏览器走查（Edge headless + CDP，含文件上传、API 控制台与模型设置）
    scripts/ai_e2e_test.py    AI 链路联调（自动拉起 8081 应用与 Mock）
    scripts/ai_real_test.py   真实供应商验证（需 AI_API_KEY，7 项）
    scripts/model_eval.py     模型效果评测（一致性/质量/多模型对比）
    scripts/mock_ai_server.py 本地 Mock OpenAI 服务（ok/bad/slow/http500）
    scripts/make_shot.js      生成 OCR 测试图片（CDP 截图）
    scripts/fixtures/         e2e 素材：sample.doc、resume_photo.png、scan_resume.pdf
    scripts/eval_results/     评测原始数据（JSON，含建议原文）
```

### 7.1 演示数据补充

- 演示账号 `linzhiyuan` 下新增一份《敏感信息示例 · 演示用》简历（含虚构手机号、身份证号、银行卡号），用于功能演示；
  该数据不属于 schema/data.sql 种子，环境重置后需通过界面重新创建。2026-11-16 已按此说明重建（含 3 条虚构敏感数据，`sensitiveWarnings` 返回手机号/身份证/银行卡三条脱敏结果），主键随库自增，脚本按名称查找，不依赖固定 ID。
- 自动化测试创建的账号（`e2e+时间戳`）与数据会保留在库中，可按需清理：
  `DELETE FROM sys_user WHERE account LIKE 'e2e%';`（关联数据按业务外键约定手工清理）。
- 文件导入与简历对比均为即时计算：不保存原始文件、不写入对比记录；导入结果需学生在表单确认后才会入库。
- 走查截图 05/06 使用的导入样例由 `browser_check.js` 自动生成于系统临时目录，不进入仓库。
- AI 密钥来源（v1.9）：优先取管理端「AI 模型设置」保存的加密密钥（`sys_config.ai.api-key-enc`），其次 `AI_API_KEY` 环境变量，再次配置文件；`application-ai.yml`、脚本与文档均不保存明文密钥。
- 数据库迁移脚本只执行一次（按 v1.6 → v1.9 顺序）；`schema.sql` 含触发器，必须通过 mysql 客户端整体执行（`DELIMITER` 生效）。
- 数据库字段/约束/枚举映射见《数据库设计说明-AI简历优化系统.md》。

## 8. 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-10-24 | 创建文档；完成后端全部模块编码；数据库初始化完成 |
| 2026-10-24 | 文档记载「前端接入真实后端」，但实际 static/api.js 仍为本地桩（见 P10，2026-10-25 已修复）；修复 P7/P8/P9；e2e 44/44 通过；阶段性封版 |
| 2026-10-25 | **v1.1**：修复 P10（api.js 本地桩→fetch，页面真正联通后端）；实现 FR-JOB-02 / FR-RESUME-03 / FR-INTERVIEW-01；e2e 扩至 58/58；新增浏览器走查脚本与 4 张截图；环境重置后重新初始化数据库；同步更新需求说明书至 v0.2 |
| 2026-10-25 | **v1.2**：实现 FR-RESUME-04（PDF/DOCX/TXT 导入解析，P15~P18）与 FR-JOB-03（多简历对比，P19）；新增 `student/compare.html` 与导航入口；e2e 扩至 **77/77**；浏览器走查扩至 11 页并新增导入/对比截图；需求说明书升级 v0.3；需求所列选做功能全部完成 |
| 2026-10-25 | **v1.3**：补齐 AI 运行期降级（P21）与成功页降级提示（P22）；新增 Mock OpenAI 服务与 `ai_e2e_test.py`（P20），AI 链路 **9/9** 通过；主流程 77/77 无回归；需求说明书升级 v0.4，明确 AI 失败语义 |
| 2026-10-25 | **v1.4**：实现 FR-STAT-02 质量与反馈统计（反馈有用率/覆盖率、方式分布、降级次数、Top 缺失关键词）；后台系统状态改为真实 AI/MySQL 信息（P23），固化统计口径（P24）；e2e 扩至 **83/83**；走查新增管理端看板与第 7 张截图；需求说明书升级 v0.5 |
| 2026-10-25 | **v1.5**：接入真实 OpenAI 兼容网关，新增 `application-ai.yml` 与 `ai_real_test.py`；修复 HTTP/2 网关 400（P25）、缺密钥误启用（P26）、AI 环境下 e2e 不确定性（P27）；真实 AI **7/7**、Mock 9/9、e2e 83/83；需求说明书升级 v0.6，关闭 Q-06 |
| 2026-10-25 | **v1.6**：关闭数据库审查 DR-001~DR-007（并发 FOR UPDATE、9 个 FK、索引调整、6 个触发器、技能唯一键、数据字典与种子补全）；DR-008/010 暂缓、DR-009 不适用；e2e 扩至 **86/86**，真实 AI 7/7、Mock 9/9；新增迁移脚本与《数据库设计说明》 |
| 2026-10-25 | **v1.7**：完成三项遗留（Token 持久化 TOKEN-01、`.doc` 导入、扫描件/图片 OCR FR-RESUME-05）与新增模型选择（FR-ADMIN-03）；新增 `auth_token`/`sys_config` 表与两份迁移脚本；引入 POI 5.2.5；新增 `model_eval.py` 与《模型评测报告》（一致性极差 10、建议质量 92.3~99.3、4 模型对比）；e2e 扩至 **102/102**，真实 AI 7/7、Mock 9/9、走查 11 页 0 异常；记录 P32~P38 |
| 2026-11-15 | **v1.9**：AI 分析新增厂商选择与在线配置（FR-ADMIN-03 扩展）——11 个预置厂商 + 自定义 / 私有网关；选中自动带出默认地址与模型且可覆盖；API-KEY AES-256-GCM 加密入库、接口只回掩码；地址/密钥/模型实时校验，保存前自动连通性测试（失败可强制保存）；保存后写运行期属性即时生效；历史配置启动按地址反查补写 `ai.vendor`，旧「仅改模型」请求保持兼容；新增 `migration-v1.9-ai-vendor.sql`；单元 **29/29**、集成 **7/7**；同步更新启动说明（第 7 章）与 README；记录 P39~P43 |
| 2026-11-16 | **v2.1**：自定义 API 请求头值改为 AES-256-GCM 加密存储（`enc:v1:` 密文，复用 `AiKeyCipher` 与本机密钥文件）；敏感头回显只给掩码并附 `masked/hasValue`，前端以 `keep=true` 沿用原值，避免掩码覆盖密文；启动时自动加密历史明文（幂等），实测明文行重启后变为密文；e2e **136/136**（新增 5 项加密用例）、单元 56/56、集成 17/17；记录 P49~P50 |
| 2026-11-16 | **v2.0**：学生端新增「API 控制台」与自定义 API（EXT-API-01~05）——`user_api_config` 表（11 表/10 外键）、配置 CRUD + 分组/标签筛选 + 复制、草稿测试与已存调用、服务端代发（超时/重试/64KB 截断/托管头拦截/敏感头掩码）、`student/api-console.html` 页面、`Api.Custom` 离线兜底与 `invokeByName`；修复插入取主键改 `GeneratedKeyHolder`（P44）、列表列宽溢出（P45）、走查在规则模式误失败（P46）、新增用例归属断言错误（P47）、演示简历缺失（P48）；e2e **131/131**（新增 34 项）、单元 **50/50**、集成 **16/16**、走查 **12 页** 0 异常并新增截图 09 |
| 2026-11-17 | **v2.2**：按全项目审查结论修复 OCR 与导入链路的 6 个缺陷（P51~P56）——JPG 误按 PNG 发送、直接上传图片缺归一化、扫描件判定阈值过低、页数截断无提示、单页失败导致整体失败、TXT BOM 未剥离；补充 429/5xx 单次重试（P57）、OCR 报文字段与前端超时/耗时显示、DPI 与页数钳制；新增 `ResumeImportServiceTest`/`ResumeOcrServiceTest` 共 **16 项**单元测试（本机 JDK17 直编运行 16/16 通过，无需 AI 密钥）；e2e 新增 BOM 与 JPG 两项用例（待具备 MySQL + 密钥的环境执行确认）；启动说明补充「扫描件识别的能力与限制」 |
| 2026-11-17 | **v2.3**：新增 FR-RESUME-06 简历删除（单份 / 批量 ≤50）与导出（Markdown、纯文本），删除前先清 `analysis_task` 以满足 RESTRICT 外键，删除语句带 `user_id` 防越权；新增 FR-USER-03 修改密码，改后吊销该用户全部旧凭证并返回新 token；学生端简历卡片新增导出 / 删除按钮与批量选择，导航用户区新增改密入口（`app.js` 统一注入，全部页面生效）；补充 `ResumeServiceTest`（9 项）、`AuthControllerPasswordTest`（5 项），累计 **30 项单元测试本机直跑 30/30**；e2e 新增 13 项（导出/删除 6 项、改密 7 项）；需求说明书升级 v0.10，开发文档新增 3.6 节接口说明 |
