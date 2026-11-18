# AI 简历优化系统 · 项目启动说明

> 版本：v1.2　日期：2026-11-15
> 适用对象：首次在本机部署/演示的同学、答辩评委、后续接手开发的同学
> 对应代码版本：开发文档 v2.0（需求 v0.8、数据字典 v1.1）
> 本文所有命令与环境版本均在本机实测，非示例值。

---

## 1. 一句话说明

这是一个 Spring Boot 单体应用（后端 + 前端静态页同进程），数据存本机 MySQL，AI 能力调用内网 OpenAI 兼容网关；**启动只有三步：导库 → 起服务 → 开浏览器**。

---

## 2. 环境要求（本机实测版本）

| 组件 | 本机版本 | 说明 |
| --- | --- | --- |
| JDK | **17.0.12**（`<JDK17_HOME>`） | 项目按 Java 17 字节码编译，低于 17 无法运行 |
| Maven | 3.9.11 | 构建与启动 |
| MySQL | **5.5.15**（`C:/Program Files/MySQL/MySQL Server 5.5`） | 驱动锁定 `mysql-connector-java 5.1.49`，8.x 驱动不兼容 5.5 |
| Python | 3.13.14 | 仅测试脚本需要（`e2e_test.py` 等，只用标准库） |
| Node.js | 22.22.2 | 仅页面走查脚本需要（`browser_check.js`） |
| Microsoft Edge | 已安装（x86 版） | 仅走查脚本需要（headless + CDP，无需额外驱动） |

> 除 Python/Node 外，运行系统本身只需要 JDK 17 + Maven + MySQL。

---

## 3. 首次启动（约 5 分钟）

### 3.1 进入项目

```bash
cd "<PROJECT_ROOT>/ai-resume-system"
```

### 3.2 初始化数据库

> ⚠️ `schema.sql` 开头会 `DROP TABLE`，**会清空已有数据**。库中已有演示数据时请勿重复执行，除非确认要重置。

```bash
cd db
MYSQL="C:/Program Files/MySQL/MySQL Server 5.5/bin/mysql.exe"

# 1) 建库建表 + 触发器（必须用 mysql 客户端整体执行，脚本含 DELIMITER）
"$MYSQL" -uroot -p --default-character-set=utf8mb4 < schema.sql

# 2) 导入演示种子数据（7 个账号、6 份简历、8 个岗位、9 条分析任务）
"$MYSQL" -uroot -p --default-character-set=utf8mb4 < data.sql

# 3) 校验：应输出 11（张表）
"$MYSQL" -uroot -p -e "use ai_resume; show tables;"
```

**关于五个迁移脚本**：`migration-v1.6-db-review.sql`、`migration-v1.7-auth-token.sql`、`migration-v1.8-ai-settings.sql`、`migration-v1.9-ai-vendor.sql`、`migration-v2.0-custom-api.sql` 只用于**升级旧库**。`schema.sql` 已包含全部新表与新约束，全新初始化时**不需要**执行迁移脚本。v1.9 脚本用于补齐 `sys_config` 表（存储 AI 厂商 / 地址 / 加密密钥 / 模型），v2.0 脚本用于补齐 `user_api_config` 表（学生自定义 API 配置），脚本本身均幂等，可重复执行。

> 应用启动时会自动 `CREATE TABLE IF NOT EXISTS auth_token / sys_config / user_api_config`，因此即使漏跑迁移，服务也能启动；但仍建议按上述顺序初始化，避免后续排查困扰。

### 3.3 配置 AI 密钥（可选但建议）

系统有两种运行方式：

| 模式 | 条件 | 效果 |
| --- | --- | --- |
| 规则模式（默认） | 不配置任何密钥 | 关键词交集打分 + 模板建议，功能全流程可用 |
| AI 模式 | 配置 `AI_API_KEY` 并用 `ai` profile 启动 | 大模型打分与建议；扫描件 OCR 也必须在此模式 |

密钥**只允许通过环境变量传入**，项目文件中不保存任何密钥：

```bash
# Windows Git Bash
export AI_API_KEY='你的密钥'
```

### 3.4 启动后端

```bash
cd "<PROJECT_ROOT>/ai-resume-system/server"

# AI 模式（推荐）：密钥直接跟在命令前，仅对该次进程生效
AI_API_KEY='你的密钥' mvn spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8080 --spring.profiles.active=ai"
```

> 没配密钥也可以照此启动：`ai.enabled=true` 但缺少密钥时，`AiProperties.isUsable()` 为 false，系统自动回落规则模式，全流程仍可演示，只是不调用大模型、扫描件 OCR 不可用。

> ⚠️ **必须显式写 `--server.port=8080`**。本机环境变量 `SERVER__PORT` 存在（实测值 58517），Spring Boot 会优先采用它，不显式指定就会起在错误端口。

等日志出现 `Started ResumeApplication` 即可。

### 3.5 打开浏览器

| 入口 | 地址 |
| --- | --- |
| 学生端 | http://localhost:8080/ |
| 管理端 | http://localhost:8080/admin/dashboard.html |

---

## 4. 日常启动（已初始化过）

### 4.1 一键启动（推荐，双击即用）

双击根目录 `启动系统.bat`，脚本按 5 步执行，每步都有明确输出：

| 步骤 | 内容 | 失败时的表现 |
| --- | --- | --- |
| 0/5 | 读取 `ai-key.local.bat`（密钥、库口令、可选工具链路径） | 文件不存在则跳过，走规则模式 |
| 1/5 | 工具链检查：**自动探测** JDK 17+、Maven、MySQL 客户端 | 打印「未找到 JDK 17 及以上版本」等具体提示并退出 |
| 2/5 | AI 密钥状态 | 无密钥时提示将回落规则模式，不影响启动 |
| 3/5 | 8080 端口检查 | 已占用时询问「重启 / 只打开页面」，15 秒无操作默认重启 |
| 4/5 | 数据库检查 | 库不存在时自动执行 `schema.sql` + `data.sql` 初始化 |
| 5/5 | 启动服务并自动打开浏览器 | 启动失败会打印 Maven 输出与退出码 |

工具链探测顺序：`ai-key.local.bat` 中的 `JAVA_HOME`/`MAVEN_HOME`（需真实存在且 JDK ≥ 17）→ 常见安装目录 → PATH。因此**换电脑或环境重置后无需改脚本**；若自动探测不到，再在 `ai-key.local.bat` 中显式指定路径。

### 4.2 手动启动

```bash
cd "<PROJECT_ROOT>/ai-resume-system/server"
AI_API_KEY='你的密钥' mvn spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8080 --spring.profiles.active=ai"
```

不想每次都等 Maven 编译，可用已打包的 jar（启动更快，但**改代码后必须重新打包**）：

```bash
mvn -q -DskipTests package
java -jar target/ai-resume-server-1.0.0.jar --server.port=8080 --spring.profiles.active=ai
```

---

## 5. 登录账号

| 角色 | 账号 | 密码 | 可见范围 |
| --- | --- | --- | --- |
| 学生 | `linzhiyuan` | `123456` | 本人简历/岗位/分析记录/版本 |
| 管理员 | `admin` | `admin123` | 用户管理、任务监管、质量看板、AI 模型设置 |

数据库里还有 `e2e+时间戳` 形式的测试账号（自动化测试产生）和一份「敏感信息示例 · 演示用」简历（用于演示脱敏提醒，含虚构敏感数据）。

**登录凭证已持久化**：服务重启后不需要重新登录（除非 Token 超过 12 小时）。

---

## 6. 关键配置项

配置文件：`server/src/main/resources/application.yml`（默认）与 `application-ai.yml`（AI 模式）。

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | 8080 | 必须命令行显式覆盖（见 3.4） |
| `spring.datasource.url` | `jdbc:mysql://localhost:3306/ai_resume` | 本机 MySQL |
| `spring.datasource.username/password` | `${DB_USERNAME:root}` / `${DB_PASSWORD:}` | 一律由环境变量注入，仓库内不含明文；本地可写入 `server/config/application.yml` |
| `ai.enabled` | `false` | `ai` profile 下为 `true` |
| `ai.endpoint` | `http://ai-gateway.example.com/v1` | 内网 OpenAI 兼容网关 |
| `ai.model` | `cn:deepseek-v4.1-flash` | 分析模型；可在管理端在线切换（存 `sys_config`，优先于此处） |
| `ai.vendor` | 无（按地址推断） | 厂商 id；管理端保存后写入 `sys_config`，优先于按地址反查 |
| `ai.secret-key` | 空 | API-KEY 加密主密钥（Base64 或口令）；留空则用本机文件 `~/.ai-resume/ai-secret.key` |
| `ai.ocr-enabled` | `true` | 扫描件/图片 OCR 开关 |
| `ai.ocr-model` | `global:deepseek-v4.1-flash` | OCR 用的多模态模型，需网关支持图片输入 |
| `ai.ocr-max-pages` | `3` | 扫描件最多识别页数（代码内钳制 1~20） |
| `ai.ocr-dpi` | `150` | 扫描件渲染 DPI（代码内钳制 96~300） |
| `ai.timeout-seconds` | `30` | 单次分析超时，超时判任务失败 |
| `demo.rule-delay-ms` | `1200` | 规则模式的演示延迟，设为 0 关闭 |
| `auth.token-ttl-hours` | `12` | 登录凭证滑动有效期 |

环境变量优先级：`AI_ENDPOINT`、`AI_MODEL`、`AI_API_KEY`、`AI_TIMEOUT_SECONDS`、`AI_OCR_MODEL`、`AI_OCR_MAX_PAGES`、`AI_OCR_DPI`、`AUTH_TOKEN_TTL_HOURS`。

### 6.5 扫描件识别的能力与限制

| 项目 | 说明 |
| --- | --- |
| 支持格式 | PNG / JPG 图片，以及无文字层的 PDF。按文件头判定真实格式，JPG 以 `image/jpeg` 发送、PNG 以 `image/png` 发送 |
| 触发条件 | PDF 整份不足 20 字，或所有页都低于 50 字且总长不足 200 字时按扫描件处理；其余情况走本地文字抽取，个别空白页只在提示中说明 |
| 页数 | 最多识别 `ai.ocr-max-pages` 页（默认 3，代码钳制上限 20）。**超出页数不会导入**，结果中会提示「共 N 页，仅识别前 K 页」 |
| 图片规格 | 单边超过 2400px 先等比缩小；PNG 超过 3MB 自动转 JPEG；仍超限才降采样，降采样会在提示中说明可能影响准确率 |
| 部分失败 | 逐页独立识别，某页超时或失败不影响其余页，提示中标注失败页号 |
| 重试 | 网关返回 429 或 5xx 时自动重试 1 次（间隔 300ms）；超时与 4xx 不重试 |
| 耗时 | 每页一次模型调用，单页最长 `ai.timeout-seconds`（30 秒）；3 页最坏约 90 秒，前端上传超时上限 180 秒并显示已用时长 |
| 未启用时 | 关闭开关或未配置密钥时返回明确原因，不静默失败，也不阻断手动填写 |
| 已知不足 | 多栏/表格版式可能错行；手写体与低分辨率扫描件识别率下降；暂不支持 HEIC/WEBP/BMP/TIFF 与本地离线 OCR |

---

## 7. AI 模型厂商配置（v1.9 新增）

管理端「AI 模型设置」卡片支持在线选择厂商、修改接口地址 / API-KEY / 模型，保存后**立即生效**，无需重启服务。配置保存在数据库 `sys_config`，项目文件中不保存任何密钥。

### 7.1 配置入口

1. 管理员登录 `admin / admin123`，打开 http://localhost:8080/admin/dashboard.html
2. 找到「AI 模型设置」卡片
3. 选择厂商 → 确认接口地址 → 填写 API-KEY（留空表示不修改）→ 确认模型 → 「测试连接」（可选）→「保存配置」

### 7.2 预置厂商与默认参数

| 厂商 | 接口地址默认值 | 默认模型 | API-KEY 格式 | 获取地址 |
| --- | --- | --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` | `sk-` 开头，16~200 位 | https://platform.openai.com/api-keys |
| Anthropic Claude | `https://api.anthropic.com/v1` | `claude-3-5-sonnet-latest` | `sk-ant-` 开头 | https://console.anthropic.com/settings/keys |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-2.0-flash` | `AIza` 开头 | https://aistudio.google.com/app/apikey |
| Azure OpenAI | 空（必须自填部署级地址） | 空（填部署名） | 16~200 位字母/数字/`_`/`-` | https://portal.azure.com |
| DeepSeek 深度求索 | `https://api.deepseek.com/v1` | `deepseek-chat` | `sk-` 开头 | https://platform.deepseek.com/api_keys |
| 阿里云通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` | `sk-` 开头 | https://bailian.console.aliyun.com |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` | `id.secret` 两段式 | https://bigmodel.cn/usercenter/apikeys |
| 月之暗面 Kimi | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` | `sk-` 开头 | https://platform.moonshot.cn/console/api-keys |
| 字节豆包（火山方舟） | `https://ark.cn-beijing.volces.com/api/v3` | `doubao-pro-32k` | 8~200 位可见字符 | https://console.volcengine.com/ark |
| 百度文心（千帆） | `https://qianfan.baidubce.com/v2` | `ernie-4.5-turbo-128k` | 8~200 位可见字符 | https://console.bce.baidu.com/iam |
| 自定义 / 私有网关 | 空（必须自填） | 空（可留空用网关默认） | 8~200 位可见字符 | 由网关提供方给出 |

说明：

- 「默认地址」「默认模型」只是选中厂商时自动填入的初始值，均可修改；Azure OpenAI 和自定义网关没有内置地址，必须自行填写。
- 「API-KEY 格式」用于前端实时校验与后端保存校验，只判断格式是否像，真实有效性由连通性测试保证。
- 除 `AIza`、`sk-ant-` 等特殊前缀外，其余厂商按通用规则校验（字母、数字与 `_ - . : + = /`，8~200 位）。

### 7.3 各厂商获取 API-KEY 指引

- **OpenAI**：登录 platform.openai.com → API keys → Create new secret key → 复制 `sk-` 开头的密钥（只显示一次）。
- **Anthropic Claude**：console.anthropic.com → Settings → API Keys → Create Key；本系统通过其 OpenAI 兼容端点调用。
- **Google Gemini**：aistudio.google.com/app/apikey → Create API key，复制 `AIza` 开头的密钥；使用 Gemini 的 OpenAI 兼容端点。
- **Azure OpenAI**：在 Azure 门户创建 OpenAI 资源 →「密钥和终结点」取 Key；接口地址必须填部署级 `https://{资源名}.openai.azure.com/openai/deployments/{部署名}`，模型名填部署名。
- **DeepSeek**：platform.deepseek.com/api_keys → 创建 API key，`sk-` 开头。
- **阿里云通义千问**：百炼控制台 → API-KEY 管理 → 创建；接口地址保持 `compatible-mode` 兼容模式地址。
- **智谱 GLM**：bigmodel.cn 用户中心 → API Keys；密钥形如 `id.secret`，两段用点连接。
- **月之暗面 Kimi**：platform.moonshot.cn/console/api-keys → 新建 API Key，`sk-` 开头。
- **字节豆包（火山方舟）**：console.volcengine.com/ark → 开通模型 → 创建 API-KEY；模型名填接入点 ID（`ep-` 开头）或模型 ID。
- **百度文心（千帆）**：console.bce.baidu.com → IAM → API-KEY 列表 → 创建；使用千帆 v2 的 OpenAI 兼容接口。
- **自定义 / 私有网关**：使用网关方给的地址、模型名与密钥。自建或校内私有网关请选择此项，手工填写网关方提供的地址、模型名与密钥（密钥格式自定义，不一定符合 OpenAI 的 `sk-` 前缀）。

### 7.4 配置步骤（含厂商切换）

1. **选择厂商**：自动填入该厂商的默认接口地址与默认模型；已保存的密钥不会被清空（输入框留空即沿用）。
2. **覆盖地址（可选）**：直接修改地址输入框即可，支持反向代理、私有网关等任意 `http/https` 地址。
3. **填写 API-KEY（可选）**：留空表示「不修改已保存的密钥」；输入新值会替换并加密存储。已保存时输入框显示掩码（如 `sk-****abcd`），接口不会回传明文。
4. **确认模型**：可填任意网关支持的模型 ID；留空则使用厂商默认模型。
5. **测试连接**：不落库，仅用当前输入探测，返回耗时、可用模型数量或失败原因。
6. **保存配置**：保存前自动执行一次连通性测试；测试失败会明确提示原因，确认配置无误可点「仍然保存」强制写入。
7. **即时生效**：保存成功后写入运行期配置，下一次分析任务直接使用新厂商 / 地址 / 密钥 / 模型，无需重启。

### 7.5 保存接口字段说明

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `vendor` | string | 厂商 id（见 7.2）；为空时沿用当前厂商 |
| `endpoint` | string | 接口地址；为空时取该厂商默认值 |
| `apiKey` | string | 新密钥；留空表示保持原密钥不变 |
| `model` | string | 模型 ID；为空时取厂商默认模型 |
| `clearApiKey` | boolean | 清除已存密钥并回落到环境变量密钥 |
| `force` | boolean | 跳过连通性测试强制保存（界面「仍然保存」） |
| `skipTest` | boolean | 同上，供脚本调用使用 |

### 7.6 校验与连通性规则

- **接口地址**：必须 `http://` 或 `https://` 开头、含主机名、不含空格换行、最长 300 字符。
- **API-KEY**：不含空白、最长 200 字符，并符合所选厂商格式（见 7.2）。
- **模型**：2~80 字符，仅允许字母、数字与 `: . _ / -`。
- **连通性测试**：默认 `GET {endpoint}/models`（10 秒超时），2xx 视为成功并统计模型数量；返回 404/405 视为「地址可达但不提供模型列表」；401/403/5xx 判失败。Azure OpenAI 与字节豆包不支持模型列表探测，改为地址可达性探测。

### 7.7 历史配置兼容（升级说明）

- 旧库只有 `ai.model`（v1.7）、没有 `ai.vendor`：服务启动时按生效接口地址反查预置厂商并自动补写 `ai.vendor`；地址匹配不到时记为 `custom`，配置页显示「自定义 / 私有网关」。
- 旧版密钥只存在于环境变量或 `application-ai.yml`：配置页显示「密钥来源：配置文件」，不会写入数据库；在页面填写并保存后才转为加密入库。
- 旧版「只更新模型」的请求（body 仅含 `model`）继续可用，不会影响厂商、地址与密钥。
- 升级旧库执行 `db/migration-v1.9-ai-vendor.sql` 即可；该脚本幂等，全新库无需执行。

### 7.8 安全说明

- API-KEY 使用 AES-256-GCM 加密后存入 `sys_config`，密文格式 `enc:v1:base64(iv|cipher|tag)`，数据库中不存在明文。
- 主密钥优先级：配置项 `ai.secret-key` → 本机文件 `~/.ai-resume/ai-secret.key`（首次启动自动生成）→ 进程内临时密钥（仅本次运行有效）。
- 接口只返回掩码（前 3 位 + `****` + 后 4 位）；模型清单由服务端代理获取，密钥不出后端。
- 更换主密钥或删除密钥文件后，旧密文无法解密，启动日志会告警并回落到环境变量密钥；在配置页重新填写 API-KEY 即可恢复。

---

## 8. 学生端自定义 API（v2.0 新增）

学生端新增「API 控制台」：把任意 HTTP 接口保存成可复用的配置项，在线测试后在前端脚本中直接调用。真实请求由**服务端代发**，不受浏览器跨域（CORS）限制，并由统一请求层处理超时、重试与错误提示。

### 8.1 入口与界面

1. 学生账号登录（`linzhiyuan / 123456`）。
2. 左侧导航最下方点击「API 控制台」，或直接访问 http://localhost:8080/student/api-console.html
3. 页面结构：

| 区域 | 内容 |
| --- | --- |
| 顶部按钮 | 「同步本地配置」（角标为待同步条数）、「新建 API」 |
| 工具栏 | 关键字搜索、分组筛选、标签筛选、配置总数 |
| 左侧列表 | 名称、分组、标签、请求方法与地址；操作：测试 / 编辑 / 复制 / 删除 |
| 右侧「测试与调用」 | 显示当前选中配置，一键发起真实调用，展示状态码、耗时、请求与响应 |
| 右侧「在代码中调用」 | 自动生成当前配置的 `Api.Custom` 调用示例 |

### 8.2 配置步骤

1. 点「新建 API」打开配置弹窗。
2. **基本信息**：填写 API 名称（必填）、选择请求方法、填写请求地址（必填），可选填分组与标签。
3. **请求头**：点「添加请求头」，逐行填写名称与值，可单独启用 / 停用。
4. **查询参数**：点「添加参数」，填写参数名、类型、当前值、默认值，可单独启用 / 停用。
5. **请求体**：按目标接口要求选择「无请求体 / JSON / 表单 / 纯文本」，再填写内容。
6. **测试请求**：点弹窗底部「测试请求」，不保存即可发起真实请求，结果直接显示在弹窗内。
7. **保存配置**：确认返回无误后点「保存配置」，列表立即出现，可再次调用。

> 建议先测试再保存：地址写错、服务未启动、返回 401/500 等问题都会在测试结果里直接给出状态码与原因。
>
> 示例：新建一个 `GET http://localhost:8080/index.html`，测试应返回 `200`；再新建 `POST http://localhost:8080/api/auth/login`、请求体类型 JSON、内容 `{"account":"linzhiyuan","password":"123456"}`，测试应返回 `200`。

### 8.3 字段说明

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| API 名称 | 字符串 | 是 | — | 1~60 字；同一学生下不能重名 |
| 请求方法 | 枚举 | 是 | GET | GET / POST / PUT / PATCH / DELETE / HEAD / OPTIONS |
| 请求地址 | 字符串 | 是 | — | 必须以 `http://` 或 `https://` 开头，含主机名，不含空格，最长 500 字符 |
| 分组 | 字符串 | 否 | 默认分组 | 最长 40 字，用于列表筛选；输入框支持选择已有分组 |
| 标签 | 字符串数组 | 否 | 空 | 逗号分隔（中英文逗号均可）；最多 10 个，单个最长 20 字，总长 ≤200 |
| 超时（毫秒） | 整数 | 否 | 15000 | 范围 1000~60000，超出自动收敛到边界值 |
| 失败重试次数 | 整数 | 否 | 0 | 范围 0~3，仅对网络异常、429、5xx 生效 |
| 启用 | 布尔 | 否 | 是 | 停用后仍可编辑与测试，但 `invoke` 会被拒绝 |
| 请求头 | 数组 | 否 | 空 | 最多 20 个；名称仅允许字母、数字、连字符（≤40），值 ≤500 且不能换行 |
| 查询参数 | 数组 | 否 | 空 | 最多 30 个；参数名 ≤60，仅允许字母、数字、下划线、点、中划线 |
| 请求体类型 | 枚举 | 否 | 无 | `none` / `json` / `form` / `text` |
| 请求体内容 | 字符串 | 否 | 空 | 最长 20000 字；`none` 时不发送 |

请求头与查询参数的每一行都带「启用」开关，停用的项不会拼进真实请求，便于临时对比排查。

### 8.4 参数类型与默认值

每个查询参数由「参数名 / 类型 / 当前值 / 默认值 / 启用」组成，取值规则：

1. **当前值非空**时使用当前值；
2. **当前值为空**时回落到默认值；
3. 当前值与默认值都为空时不拼接该参数。

| 类型 | 允许的值 | 示例 |
| --- | --- | --- |
| `string` | 任意文本，调用时自动 URL 编码 | `林知远` |
| `number` | 数字（整数或小数） | `1`、`2.5` |
| `boolean` | 只能是 `true` 或 `false` | `true` |
| `array` | 合法 JSON 数组 | `["Java","MySQL"]` |
| `object` | 合法 JSON 对象 | `{"page":1,"size":10}` |

类型在保存与测试前由后端校验，例如 `number` 类型填入 `abc` 会直接提示「参数 page 的类型为 number，值必须是数字」，不会发起请求。

### 8.5 请求体类型

| 类型 | 填写方式 | 发送行为 |
| --- | --- | --- |
| 无（`none`） | 不填 | 不发送请求体，输入框禁用 |
| JSON（`json`） | 标准 JSON 文本 | 保存 / 测试前校验 JSON；自动加 `Content-Type: application/json; charset=UTF-8` |
| 表单（`form`） | 每行一个 `key=value` | 自动 URL 编码并拼接为 `a=1&b=2`，`Content-Type: application/x-www-form-urlencoded` |
| 纯文本（`text`） | 任意文本 | 原样发送，`Content-Type: text/plain; charset=UTF-8` |

若请求头里已手工填写 `Content-Type`，则不会被自动值覆盖；未填写 `Accept` 时自动补 `Accept: application/json, */*`。

### 8.6 测试结果说明

测试（以及右侧「发起调用」）会展示：

| 字段 | 说明 |
| --- | --- |
| 状态码 | 目标接口返回的 HTTP 状态码与短语，如 `200 OK`、`404 Not Found` |
| 耗时 | 服务端从发出请求到读完响应的毫秒数 |
| 尝试次数 | 实际请求次数，含自动重试 |
| 请求 | 最终请求行（方法 + 完整 URL，含拼接后的查询串）、请求头、请求体 |
| 响应 | 响应头与响应体；JSON 会自动格式化，超过 64KB 标记「已截断」 |
| 失败原因 | 超时、域名无法解析、无法连接、证书失败等中文说明 |

注意：目标接口返回 4xx/5xx **不算系统错误**。接口仍返回 `code:0`，用 `data.ok=false` 与 `data.status` 表达目标接口的结果；只有参数校验失败、未登录、网络层异常才返回 `code:1`。

### 8.7 在页面脚本中调用

页面已加载 `api.js` 时，可直接调用已保存的配置：

```js
// 按 id 调用；params / headers / body 均为可选覆盖项
const res = await Api.Custom.invoke('12', {
  params: { page: 2 },
  headers: { 'X-Request-Id': 'demo-001' }
});
if (res.code === 0 && res.data.ok) {
  console.log(res.data.status, res.data.durationMs + 'ms');
  console.log(res.data.body);
} else {
  console.warn(res.message || (res.data && res.data.message));
}

// 按名称调用，业务代码更直观
const same = await Api.Custom.invokeByName('简历解析接口');
```

返回结构：

| 字段 | 说明 |
| --- | --- |
| `code` | 0 = 请求已执行；1 = 参数校验、登录失效或网络层失败 |
| `data.ok` | 目标返回 2xx/3xx 时为 `true` |
| `data.status` | 目标 HTTP 状态码；网络失败为 0 |
| `data.durationMs` | 服务端耗时（毫秒） |
| `data.attempts` | 实际尝试次数 |
| `data.body` | 响应体（最多 64KB） |
| `data.bodyTruncated` | 响应是否被截断 |
| `data.message` | 失败原因（超时 / 不可达 / 证书等） |
| `message` | `code=1` 时的中文错误信息 |

其它管理方法：`Api.Custom.list()`、`get(id)`、`save(payload)`、`remove(id)`、`duplicate(id)`、`test(payload)`、`syncPending()`。

### 8.8 本地缓存与云端同步

- **云端为准**：配置保存在数据库 `user_api_config`，按学生账号隔离，换浏览器、清缓存都不会丢。
- **本机镜像**：每次无筛选加载列表或保存成功后，会把配置写入 `localStorage.ars_custom_apis`；云端不可用时页面自动改用镜像数据，并在顶部提示「已切换到本机缓存」。
- **离线待同步**：保存或删除时若网络中断，操作会写入 `localStorage.ars_custom_apis_pending`，顶部「同步本地配置」按钮显示待同步数量；恢复网络后点一次即可推送到云端。
- 每个学生最多保存 **100 条**配置，达到上限时需先删除不再使用的配置。

### 8.9 校验、超时与重试规则

- **地址**：`http` / `https` + 主机名 + 无空白，最长 500 字符。
- **请求头**：名称仅字母、数字、连字符；`Host`、`Content-Length`、`Connection`、`Expect`、`Upgrade`、`Transfer-Encoding` 由系统管理，禁止手工设置（避免请求走私与协议异常）。
- **超时**：连接超时固定 5 秒；读取超时按配置的 `timeoutMs`（1000~60000 毫秒）。
- **重试**：仅对网络异常、`429` 与 `5xx` 重试，最多 3 次，间隔 200 / 400 / 600 毫秒；`4xx` 不重试，避免重复提交。前端请求层在代理请求本身超时或断网时还会自动再试 1 次。
- **响应截断**：最多读取并展示 64KB，超出部分丢弃并在结果里标记。
- **敏感信息**：`Authorization`、`api-key`、`Cookie` 等请求/响应头在结果中自动掩码，只显示前 4 位与后 4 位；配置列表与编辑弹窗同样只显示掩码。

### 8.10 数据存储与升级

`user_api_config` 主要字段：

| 字段 | 说明 |
| --- | --- |
| `user_id` | 归属学生，外键关联 `sys_user`，删除用户时级联删除 |
| `name` / `method` / `url` | 名称、请求方法、请求地址 |
| `headers_json` / `params_json` | 请求头、查询参数（JSON 数组）；请求头的值以 `enc:v1:` 密文保存 |
| `body_type` / `body_json` | 请求体类型与内容 |
| `group_name` / `tags` | 分组、逗号分隔的标签 |
| `timeout_ms` / `retries` / `enabled` | 超时、重试、启用状态 |
| `created_at` / `updated_at` | 创建与更新时间 |

升级旧库执行 `db/migration-v2.0-custom-api.sql`（幂等）；不执行也能启动，因为应用会 `CREATE TABLE IF NOT EXISTS` 自动补建。

**请求头加密（v2.1）**

- 保存时请求头的值用 AES-256-GCM 加密后写入 `headers_json`，数据库中形如 `{"name":"Authorization","value":"enc:v1:..."}`，名称保持明文便于列表展示。
- 界面与接口只返回掩码（如 `Bear****5678`）并标注「已加密」；编辑时**留空表示沿用原值**，填写新值才会覆盖；要删除某个请求头直接删除该行。
- 调用与测试前由服务端解密，发送给目标服务的仍是原文；历史版本保存的明文请求头会在下次启动时自动加密（幂等，日志打印迁移条数）。
- 主密钥沿用 AI 密钥机制：`ai.secret-key` 配置 → 本机 `~/.ai-resume/ai-secret.key`（首次自动生成）→ 临时密钥。**更换或删除密钥文件后，旧密文无法解密**，需要重新填写请求头值。

---

## 9. 启动后的验证

### 9.1 快速冒烟（1 分钟）

```bash
# 1) 首页可访问
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/          # 期望 200

# 2) 登录拿 Token
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"account":"linzhiyuan","password":"123456"}' | head -c 200

# 3) 用返回的 token 查本人信息
curl -s http://localhost:8080/api/auth/me -H "X-Token: <上一步的token>"
```

### 9.2 完整回归

```bash
cd "<PROJECT_ROOT>/ai-resume-system/server/scripts"

python e2e_test.py                              # 接口级 136 项（含并发、Token、DOC/OCR、模型设置、自定义 API、请求头加密）
node browser_check.js                           # 页面走查 12 页，截图 01~09 写入 docs/screenshots
python ai_e2e_test.py                           # Mock AI 9 项（自动拉起 8081 应用 + 8099 Mock，用完自动关）
AI_API_KEY='密钥' python ai_real_test.py        # 真实模型 7 项（8081 独立实例）
AI_API_KEY='密钥' python model_eval.py          # 模型评测（一致性/建议质量/多模型对比）
```

> `e2e_test.py` 每次用时间戳生成新账号，可反复执行；其中 OCR 用例在非 AI 模式下会自动 SKIP 并打印原因，不会误判失败。

### 9.3 v1.9 AI 厂商配置自动化测试

```bash
cd "<PROJECT_ROOT>/ai-resume-system/server"
"mvn" test \
  "-Dtest=AiVendorTest,AiKeyCipherTest,AiPropertiesTest,AiSettingsServiceTest,AiVendorConfigIntegrationTest" \
  "-DfailIfNoTests=false"
```

| 测试类 | 用例数 | 覆盖内容 |
| --- | --- | --- |
| `AiVendorTest` | 7 | 11 个预置厂商元数据、默认地址/模型、厂商查找与按地址反查、密钥正则 |
| `AiKeyCipherTest` | 5 | AES-GCM 加解密往返、随机 IV、历史明文兼容、密文损坏、密钥掩码 |
| `AiPropertiesTest` | 5 | 运行期覆盖优先级、空值回落、可用性判断、厂商反查 |
| `AiSettingsServiceTest` | 12 | 厂商/地址/密钥/模型校验、加密落库、留空沿用、清除密钥、连通性测试 |
| `AiVendorConfigIntegrationTest` | 7 | 真实 MySQL：厂商切换、自定义网关、异常场景、不可达地址、历史数据迁移 |

> 集成测试会完整备份并在结束后还原 `sys_config` 中原有的 AI 配置，不会破坏本机已保存的密钥。首次运行需要联网下载 `maven-surefire-plugin`。

### 9.4 v2.0 / v2.1 自定义 API 自动化测试（名称/调用/加密）

```bash
cd "<PROJECT_ROOT>/ai-resume-system/server"
"mvn" test \
  "-Dtest=UserApiConfigServiceTest,UserApiInvokeServiceTest,UserApiConfigIntegrationTest" \
  "-DfailIfNoTests=false"
```

| 测试类 | 用例数 | 覆盖内容 |
| --- | --- | --- |
| `UserApiConfigServiceTest` | 20 | 名称/方法/地址校验、请求头与参数类型、JSON 请求体、标签去重、超时与重试边界、同名与数量上限、归属校验、复制命名、调用参数组装；请求头加密、掩码回显、keep 沿用、历史明文迁移 |
| `UserApiInvokeServiceTest` | 7 | 查询参数拼接与编码、POST 请求体、500 重试、5xx 不重试、超时、连接拒绝、64KB 截断、敏感头掩码 |
| `UserApiConfigIntegrationTest` | 10 | 真实 MySQL + 本机 HttpServer：创建、分组/标签过滤、更新、复制、跨用户隔离、真实调用、草稿测试、异常校验、删除；库内密文与敏感头解密调用 |

> 集成测试数据统一以 `IT-API-` 开头，开始与结束自动清理，不影响演示数据与其它账号配置。

---

## 10. 常见问题（均为实际踩过的坑）

**Q1：页面能打开，但点任何功能都提示"登录状态已失效"**
Token 表被清空或服务用了不同数据库。确认 `ai_resume` 库存在 `auth_token` 表；重新登录即可。

**Q2：改了 `static/*.html`，刷新页面没变化**
Spring Boot 从 `target/classes` 读取静态资源。改前端后必须 `mvn -q -DskipTests compile` 并重启服务，页面才会更新。

**Q3：启动后访问 8080 打不开，但日志显示启动成功**
多半起在了 `SERVER__PORT`（58517）上。启动参数里补上 `--server.port=8080`。

**Q4：上传扫描件/图片提示"需要视觉模型"**
当前是规则模式。用 `--spring.profiles.active=ai` 且配置 `AI_API_KEY` 启动；OCR 依赖网关的多模态模型。

**Q5：分析任务失败，原因是"AI 服务返回 HTTP 400 Invalid HTTP request received."**
网关不支持 h2c 升级。`AiClient` 已强制 HTTP/1.1，若仍出现，检查是否用了旧 jar，重新 `mvn package`。

**Q6：分析任务失败，原因是"格式不符合约定"**
模型返回内容不符合约定 JSON（偶发，尤其切换模型后）。重试一次即可；若持续出现，改用管理端切换回 `cn:deepseek-v4.1-flash`。

**Q7：`mvn` 报 `invalid target release: 17` 或 class file 版本错误**
`JAVA_HOME` 指向的 JDK 低于 17。本项目实测使用 `<JDK17_HOME>`。

**Q8：导入 SQL 后中文变问号**
执行 mysql 命令时漏了 `--default-character-set=utf8mb4`。

**Q9：`schema.sql` 执行中断，报 DELIMITER 语法错误**
用了分段执行的 GUI 工具。请用命令行 mysql 客户端整体执行（脚本内含触发器，需要 `DELIMITER`）。

**Q10：走查脚本截图全是"已阻止此页面"**
本机 Edge 企业策略禁止 headless 访问 `file://`。走查脚本走的是 `http://localhost:8080`，正常不受影响；如需自行生成图片素材，用 `python -m http.server` 托管后走 http。

**Q11：保存 AI 厂商配置时提示"连通性测试未通过"**
先点「测试连接」查看具体原因：HTTP 401/403 多为密钥无效或额度不足；"无法连接"多为接口地址写错、网关不可达或本机无外网。确认配置无误（例如内网网关临时不可达）可点「仍然保存」强制写入。

**Q12：管理端厂商显示为"自定义 / 私有网关"，原来的厂商识别不到了**
旧版本只保存了接口地址与模型，没有厂商字段。只要地址与某个预置厂商的默认地址一致，启动时会自动补写厂商；课程内网网关（`http://ai-gateway.example.com/v1`）等非预置地址按设计显示为「自定义 / 私有网关」，不影响分析功能。

**Q13：换电脑后管理端提示"密钥未配置"，数据库里明明有密文**
API-KEY 加密主密钥默认保存在本机 `~/.ai-resume/ai-secret.key`，换机后密文无法解密，启动日志会告警并回落到环境变量密钥。在配置页重新填写一次 API-KEY 即可；如需多机共用，请通过 `ai.secret-key` 显式指定同一主密钥。

**Q14：自定义 API 测试结果里 `status=0`、提示"无法连接到目标服务器"**
说明请求没有到达目标接口，与配置格式无关。依次检查：地址是否写错端口 / 路径、目标服务是否已启动、本机是否能访问该域名（内网地址需在内网环境）、是否被防火墙拦截。若是超时，适当调大「超时（毫秒）」。

**Q15：保存自定义 API 时提示"已暂存到本地，联网后可点「同步本地配置」重试"**
说明后端暂时不可达，配置没有写入数据库，但已存进浏览器本地队列，不会丢失。恢复网络后点页面顶部「同步本地配置」，角标清零即表示已全部推送；期间刷新页面仍可从缓存看到配置。

**Q16：自定义 API 页面显示"已切换到本机缓存"**
后端不可用或登录状态异常时，列表自动回落到本机镜像，只读可用、写入会进入待同步队列。先确认服务已启动（`http://localhost:8080/` 可访问）并重新登录，再刷新页面即可恢复云端数据。

**Q17：双击 `启动系统.bat` 直接退出，提示"未找到 JDK 17：C:\Users\...\DevEnv\jdk-17.0.12\bin\java.exe"**
`ai-key.local.bat` 里写死的 `JAVA_HOME`/`MAVEN_HOME` 已失效（换了电脑、重装系统或清理过 `Documents\DevEnv` 都会这样）。v2.4 起脚本会**自动探测**工具链：先把 `ai-key.local.bat` 里那两行改成真实路径，或直接删掉/注释掉，让脚本自己按「常见安装目录 → PATH」去找 JDK 17+、Maven、`mysql.exe`。判断标准只有一个：`%JAVA_HOME%\bin\java.exe` 存在且版本 ≥ 17。

**Q18：启动脚本报 `'学生端：http:' is not recognized as an internal or external command`**
批处理文件被存成了 UTF-8。`cmd.exe` 按字节推进读取位置、按当前代码页解码，UTF-8 中文会让解析错位，连 `echo` 都被吃掉。本仓库脚本统一为 **GBK（ANSI/936）+ `chcp 936`**，**不要用 VS Code 的"UTF-8"保存或另存为**；改动后如需转换：`iconv -f UTF-8 -t GBK 启动系统.bat > tmp && mv tmp 启动系统.bat`。

**Q19：控制台里 Spring Boot 的中文日志是乱码（如"AI 閰嶇疆宸插姞杞斤細"）**
控制台是 GBK（936），而 Logback 默认以 UTF-8 写控制台。`启动系统.bat` 已给应用传 `--logging.charset.console=GBK`；手动启动时可自行追加该参数。注意这**只影响控制台输出编码**，不影响接口、数据库与文件读写（均为 UTF-8）。

**Q20：请求了不存在的路径/接口，返回 404 还是 200？**
v2.4 起语义已规范：路径不存在 → **404**（`{"code":1,"message":"请求的资源不存在：xxx"}`）；HTTP 方法不支持（例如对不提供删除的分析记录发 `DELETE`）→ **405**；未登录 → **401**；业务校验失败仍为 200 + `code:1`。

---

## 11. 停止与数据重置

```bash
# 停止服务（Windows）
netstat -ano | grep "0.0.0.0:8080"          # 找到最后一列 PID
taskkill //PID <PID> //F

# 重置数据（会清空全部业务数据）
cd "<PROJECT_ROOT>/ai-resume-system/db"
"C:/Program Files/MySQL/MySQL Server 5.5/bin/mysql.exe" -uroot -p \
  --default-character-set=utf8mb4 < schema.sql
"C:/Program Files/MySQL/MySQL Server 5.5/bin/mysql.exe" -uroot -p \
  --default-character-set=utf8mb4 < data.sql
```

清理自动化测试产生的账号（可选，注意按外键顺序）：

```sql
-- 先删子表再删用户
DELETE FROM analysis_task WHERE user_id IN (SELECT id FROM sys_user WHERE account LIKE 'e2e%');
DELETE FROM auth_token    WHERE user_id IN (SELECT id FROM sys_user WHERE account LIKE 'e2e%');
DELETE FROM resume_version WHERE resume_id IN (SELECT id FROM resume WHERE user_id IN (SELECT id FROM sys_user WHERE account LIKE 'e2e%'));
DELETE FROM resume_education WHERE resume_id IN (SELECT id FROM resume WHERE user_id IN (SELECT id FROM sys_user WHERE account LIKE 'e2e%'));
DELETE FROM resume_project   WHERE resume_id IN (SELECT id FROM resume WHERE user_id IN (SELECT id FROM sys_user WHERE account LIKE 'e2e%'));
DELETE FROM resume_skill     WHERE resume_id IN (SELECT id FROM resume WHERE user_id IN (SELECT id FROM sys_user WHERE account LIKE 'e2e%'));
DELETE FROM resume  WHERE user_id IN (SELECT id FROM sys_user WHERE account LIKE 'e2e%');
DELETE FROM job     WHERE user_id IN (SELECT id FROM sys_user WHERE account LIKE 'e2e%');
DELETE FROM sys_user WHERE account LIKE 'e2e%';
```

---

## 12. 目录速览

```text
软件实训课设/
├─ README.md                          入口索引（启动三步、账号、文档导航）
├─ docs/
│  ├─ 项目启动说明-AI简历优化系统.md      ← 本文
│  ├─ 需求分析说明书-AI简历优化系统.md    v0.8
│  ├─ 项目开发文档-AI简历优化系统.md      v2.0（进度/接口/AI 厂商配置/自定义 API/测试）
│  ├─ 数据库设计说明-AI简历优化系统.md    v1.1（数据字典；v2.0 新增第 11 张表）
│  ├─ 数据库设计审查报告-AI简历优化系统.md（DR-001~010 与复核记录）
│  ├─ 模型评测报告-AI简历优化系统.md      v1.0（一致性/质量/多模型对比）
│  ├─ AI简历优化系统-答辩说明文档.md      答辩讲解稿与演示流程
│  └─ screenshots/                     走查截图 01~09
├─ AI简历优化系统-答辩PPT.pptx          答辩 PPT（成品）
├─ AI简历优化系统-答辩PPT/              PPT 源工程（slidep，路径已固化勿移动）
├─ ai-resume-system/
│  ├─ db/           schema.sql、data.sql、5 个迁移脚本
│  └─ server/       Spring Boot 工程（含 static 前端）
│      └─ scripts/  e2e / Mock / 真实 AI / 评测 / 走查脚本 + fixtures 素材
└─ ai-resume-prototype/  早期设计原型（仅作设计基线，不参与部署）
```

---

## 13. 交付自检清单

| 检查项 | 期望 |
| --- | --- |
| `http://localhost:8080/` | 返回 200，显示登录页 |
| 学生 `linzhiyuan/123456` 登录 | 进入工作台，统计卡有数据 |
| 管理员 `admin/admin123` 登录 | 后台概览，质量看板与模型设置可见 |
| `python e2e_test.py` | 136/136 通过（规则模式；AI 模式下 OCR 与模型清单用例不跳过） |
| `node browser_check.js` | 12 页 0 个 JS 异常，截图 9 张（含 API 控制台） |
| 管理端「AI 模型设置」 | 厂商下拉显示 11 项，切换后自动带出默认地址/模型，密钥显示掩码 |
| `mvn test`（v1.9 五个测试类） | 单元 29 + 集成 7 全部通过 |
| 学生端「API 控制台」 | 新建 / 测试 / 保存 / 复制 / 删除正常，测试结果含状态码与耗时 |
| `mvn test`（v2.0 三个测试类） | 单元 21 + 集成 9 全部通过 |
| 重启服务后用旧会话刷新页面 | 仍是登录状态（Token 已持久化） |
