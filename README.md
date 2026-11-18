# AI 简历优化系统

课程实训项目。Spring Boot 3.5.7 单体应用（后端 + 静态前端同进程）+ 本机 MySQL 5.5.15 + 内网 OpenAI 兼容网关。

- 学生端：简历管理（含**删除 / 批量删除**）、岗位匹配度、AI 分析建议、版本对比、敏感信息提醒、模拟面试题、文件导入（PDF/DOCX/DOC/TXT/图片，扫描件 OCR）、**简历导出（Markdown）**、**API 控制台（自定义 API）**
- 通用：**修改密码**（修改后其他设备的登录立即失效）
- 管理端：用户管理、任务监管、质量与反馈看板、AI 模型设置

## 目录结构

```text
ai-resume-system-repo/
├─ README.md                      本文（入口索引）
├─ LICENSE                        MIT 开源协议
├─ 启动系统.bat                   一键启动（双击即用）
├─ ai-key.local.bat.example       AI 密钥配置模板（复制为 .bat 后填入自己的密钥）
├─ docs/                          全部交付文档与截图
│  ├─ 项目启动说明-AI简历优化系统.md    环境要求、导库、启动、验证、FAQ（先看这份）
│  ├─ 需求分析说明书-AI简历优化系统.md  v0.8 需求基线
│  ├─ 项目开发文档-AI简历优化系统.md    v2.0 进度 / 接口 / AI 厂商配置 / 自定义 API / 测试
│  ├─ 数据库设计说明-AI简历优化系统.md  v1.2 数据字典（11 表）
│  ├─ 数据库设计审查报告-AI简历优化系统.md
│  ├─ 模型评测报告-AI简历优化系统.md
│  └─ screenshots/                 走查截图 01~09
├─ ai-resume-system/
│  ├─ db/          schema.sql、data.sql、5 个迁移脚本（v1.6~v2.0）
│  └─ server/      Spring Boot 工程（src/main/resources/static 为运行版前端）
│      └─ scripts/ e2e / Mock / 真实 AI / 评测 / 页面走查脚本 + fixtures 素材
└─ ai-resume-prototype/            早期设计原型（仅设计基线，不参与部署）
```

## 一键启动（推荐）

双击根目录的 `启动系统.bat`：**自动探测** JDK 17+ / Maven / MySQL 客户端（无需按本机路径改脚本），检查端口与数据表、缺失时自动导库，然后以 AI 模式（`profile=ai`，8080）启动并自动打开浏览器。脚本为 **GBK 编码 + `chcp 936`**，改动后请勿另存为 UTF-8（cmd 会解析错位）。

- AI 密钥、网关地址与数据库口令放在 `ai-key.local.bat`（仅本机使用，已被 `.gitignore` 忽略）
  - 首次使用：复制 `ai-key.local.bat.example` 为 `ai-key.local.bat`，把里面的占位符替换成自己的
- 端口已被占用时会询问「停止旧进程并重新启动」，选 N 则只打开页面

## 快速启动（手动）

```bash
# 0) 准备本机口令（不写入仓库）
export DB_USERNAME='your-db-user'
export DB_PASSWORD='your-db-password'
export AI_API_KEY='your-api-key'     # 可选，不填则走规则模式

# 1) 导库（schema.sql 含 DROP TABLE，会清空已有数据；-p 后按提示输入口令）
cd ai-resume-system/db
mysql -u "$DB_USERNAME" -p --default-character-set=utf8mb4 < schema.sql
mysql -u "$DB_USERNAME" -p --default-character-set=utf8mb4 < data.sql

# 2) 启动（必须显式指定端口，本机存在 SERVER__PORT 环境变量会覆盖配置）
cd ../server
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8080 --spring.profiles.active=ai"

# 3) 打开浏览器
#    学生端 http://localhost:8080/
#    管理端 http://localhost:8080/admin/dashboard.html
```

> 数据库账号口令通过 `DB_USERNAME` / `DB_PASSWORD` 环境变量注入；也可写入
> `ai-resume-system/server/config/application.yml`（该目录已被 `.gitignore` 忽略，
> Spring Boot 启动时会自动加载并覆盖 classpath 配置）。

不配置 `AI_API_KEY` 也能全流程演示，系统会自动回落到规则模式（扫描件 OCR 不可用）。

## AI 模型厂商配置（v1.9）

管理员登录后台 → 「AI 模型设置」卡片，可在页面直接切换厂商并填写密钥，保存后立即生效，无需重启或改配置文件：

- 预置 11 个厂商：OpenAI、Anthropic、Google Gemini、Azure OpenAI、DeepSeek、通义千问、智谱 GLM、Kimi、豆包、文心千帆、自定义 / 私有网关
- 选中厂商自动带出默认接口地址与模型，均可覆盖修改；API-KEY 留空表示沿用已保存密钥
- 保存前自动做连通性测试，失败可「仍然保存」；API-KEY 加密入库，界面只显示掩码
- 自建或校内私有网关请选择「自定义 / 私有网关」，手工填写接口地址与模型名

各厂商获取 API-KEY 指引、参数说明与升级兼容说明见 [项目启动说明 · 第 7 章](docs/项目启动说明-AI简历优化系统.md)。

## 学生端 API 控制台（v2.0）

学生登录后 → 左侧导航「API 控制台」（`http://localhost:8080/student/api-console.html`），可把任意 HTTP 接口保存成可复用配置：

- 填写方法 / 地址 / 请求头 / 查询参数 / 请求体（JSON、表单、纯文本），先在弹窗「测试请求」看到状态码与返回，再保存
- 真实请求由**服务端代发**，规避浏览器跨域；统一处理 1~60 秒超时、失败重试（仅网络异常/429/5xx，≤3 次）、响应截断 64KB
- `Host`、`Content-Length` 等托管头禁止手工设置；`Authorization`、`Cookie`、`api-key` 等**请求头值 AES-256-GCM 加密存库**，界面只显示掩码，编辑时留空即沿用原值
- 保存后可在页面脚本中 `await Api.Custom.invoke('配置ID', { params })` 或 `invokeByName('配置名称')` 调用
- 配置存 `user_api_config` 表，按学生账号隔离；本地另留镜像与待同步队列，断网时可暂存

细节见 [项目启动说明 · 第 8 章](docs/项目启动说明-AI简历优化系统.md)。

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 学生 | `linzhiyuan` | `123456` |
| 管理员 | `admin` | `admin123` |

## 回归测试

```bash
cd ai-resume-system/server/scripts
python e2e_test.py          # 接口级 136 项（含自定义 API 与请求头加密）
node browser_check.js       # 页面走查 12 页，截图 01~09 写入 docs/screenshots
python ai_e2e_test.py       # AI 链路 Mock 9 项
AI_API_KEY='密钥' python ai_real_test.py   # 真实供应商 7 项（可选）
AI_API_KEY='密钥' python model_eval.py     # 模型效果评测（可选）

# v1.9 AI 厂商配置：单元 29 项 + 集成 7 项；v2.0/v2.1 自定义 API：单元 27 项 + 集成 10 项（集成需本机 MySQL）
cd ..
mvn test "-Dtest=AiVendorTest,AiKeyCipherTest,AiPropertiesTest,AiSettingsServiceTest,AiVendorConfigIntegrationTest" "-DfailIfNoTests=false"
mvn test "-Dtest=UserApiConfigServiceTest,UserApiInvokeServiceTest,UserApiConfigIntegrationTest" "-DfailIfNoTests=false"
```

## 环境注意事项

- MySQL 实为 **5.5.15**，驱动锁定 `mysql-connector-java 5.1.49`，导入 SQL 必须带 `--default-character-set=utf8mb4`。
- JDK 25 环境下靠 `maven.compiler.release=17` 编译，运行需 JDK 17+。
- 修改 `src/main/resources/static` 后必须 `mvn compile` 再重启，否则页面不生效。

以上细节与排错见 [项目启动说明](docs/项目启动说明-AI简历优化系统.md)。

## 开源协议

本项目采用 [MIT License](LICENSE) 开源，Copyright (c) 2026 GZXchina。

你可以自由使用、修改、分发本项目（含商业用途），只需保留原始版权声明与协议文本。本项目按「原样」提供，不附带任何担保。

## 说明

本项目为《软件开发综合项目实训》课程作品，仅用于学习与交流。项目中的演示账号、简历数据均为虚构内容，不对应任何真实个人。

