# AI 简历优化系统 · 数据库设计说明与数据字典

> 版本：v1.3（user_api_config 请求头加密）　日期：2026-11-16
> 数据库：MySQL **5.5.15**（InnoDB / utf8mb4 / utf8mb4_general_ci）
> 对应脚本：`ai-resume-system/db/schema.sql`、`data.sql`、`migration-v1.6-db-review.sql` ~ `migration-v2.0-custom-api.sql`
> 关联审查：《数据库设计审查报告-AI简历优化系统.md》DR-001 ~ DR-010
> 变更：v1.1 增加 2 张表（`auth_token` 登录凭证、`sys_config` 系统配置）的数据字典与约束说明，表总数 8 → 10；v1.2 增加 `user_api_config`（学生自定义 API 配置）数据字典与取舍说明，表总数 10 → 11；v1.3 明确 `headers_json` 的值为 AES-256-GCM 密文并补充密钥管理取舍。

## 1. 设计约定

| 项目 | 约定 |
| --- | --- |
| 主键 | 业务表 `BIGINT AUTO_INCREMENT`；`analysis_task`、`resume_version` 使用 `VARCHAR(40)` 业务号（见 §5 已知取舍） |
| 时间 | `DATETIME`；创建/更新时间由应用写入 `NOW()`；展示格式统一 `yyyy-MM-dd HH:mm` |
| 枚举 | 数据库统一大写：`STUDENT/ADMIN`、`ACTIVE/DISABLED`、`RUNNING/SUCCESS/FAILED`、`AI/RULE`、`USEFUL/USELESS`；对外 API 使用小写（`student/admin`、`analyzing/success/failed`、`ai/rule`、`useful/useless`），由 Service 层转换 |
| 枚举校验 | MySQL 5.5 会解析但**忽略 CHECK**，因此用 BEFORE INSERT/UPDATE 触发器实现（§4） |
| 字符集 | 全库 `utf8mb4`；5.5 无 `utf8mb4_0900_ai_ci`，使用 `utf8mb4_general_ci` |
| 外键 | InnoDB 原生外键；用户/简历/岗位默认拒绝删除，子模块与版本随简历级联删除 |
| 派生字段 | `resume.version` 必须等于该简历 `MAX(resume_version.version)`；由 `ResumeService` 在同一事务内维护 |
| 敏感数据 | 联系电话按掩码存储（如 `138****6621`）；不存储身份证/银行卡等完整敏感信息 |

## 2. 表清单与关系

| 表 | 作用 | 主要关系 |
| --- | --- | --- |
| `sys_user` | 学生/管理员账号 | 被 `resume.user_id`、`job.user_id`、`analysis_task.user_id` 引用 |
| `resume` | 简历主表（当前态） | 1:N `resume_education/project/skill`、`resume_version`、`analysis_task` |
| `resume_education` | 教育经历 | N:1 `resume`（CASCADE） |
| `resume_project` | 项目/实习经历 | N:1 `resume`（CASCADE） |
| `resume_skill` | 技能清单 | N:1 `resume`（CASCADE），`(resume_id, skill)` 唯一 |
| `job` | 岗位与 JD | N:1 `sys_user`；1:N `analysis_task` |
| `analysis_task` | 分析任务与结果 | N:1 `sys_user/resume/job`（RESTRICT） |
| `resume_version` | 版本快照（只增不改） | N:1 `resume`（CASCADE）；`(resume_id, version)` 唯一 |
| `auth_token` | 登录凭证（v1.7） | N:1 `sys_user`（CASCADE），过期行由应用清理 |
| `sys_config` | 系统级键值配置（v1.7） | 无外键；存 `ai.model`/`ai.vendor`/`ai.endpoint`/`ai.api-key-enc` 等 |
| `user_api_config` | 学生自定义 API 配置（v2.0） | N:1 `sys_user`（CASCADE）；按 `user_id` 隔离 |

## 3. 数据字典

### 3.1 sys_user（账号）

| 字段 | 类型 | 空 | 默认 | 约束/取值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| id | BIGINT | N | 自增 | PK | 用户 ID |
| account | VARCHAR(50) | N | — | UK `uk_user_account` | 登录账号，4~20 位字母/数字（应用校验） |
| password_hash | VARCHAR(255) | N | — | — | `iterations:base64(salt):base64(hash)`，PBKDF2-HmacSHA256 |
| name | VARCHAR(50) | N | — | — | 昵称 |
| role | VARCHAR(20) | N | STUDENT | 触发器：STUDENT/ADMIN | 角色 |
| status | VARCHAR(20) | N | ACTIVE | 触发器：ACTIVE/DISABLED | 账号状态；禁用即注销 Token |
| created_at | DATETIME | N | — | — | 创建时间 |

### 3.2 resume（简历主表）

| 字段 | 类型 | 空 | 默认 | 约束/取值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| id | BIGINT | N | 自增 | PK | 简历 ID |
| user_id | BIGINT | N | — | FK `fk_resume_user` → sys_user(id) | 归属学生 |
| name | VARCHAR(50) | N | — | — | 简历名称 |
| person_name | VARCHAR(20) | N | — | — | 姓名 |
| target | VARCHAR(50) | N | — | — | 求职意向 |
| phone | VARCHAR(20) | Y | NULL | — | 联系电话（建议掩码） |
| email | VARCHAR(60) | Y | NULL | — | 邮箱 |
| summary | TEXT | Y | NULL | — | 个人简介 |
| version | INT | N | 1 | 触发器：≥1；派生字段 | 当前版本号 = MAX(resume_version.version) |
| completeness | INT | N | 0 | 触发器：0~100 | 完整度评分 |
| created_at | DATETIME | N | — | — | 创建时间 |
| updated_at | DATETIME | N | — | — | 更新时间（列表排序字段） |

索引：`idx_resume_user(user_id)`。

### 3.3 resume_education（教育经历）

| 字段 | 类型 | 空 | 默认 | 约束/取值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| id | BIGINT | N | 自增 | PK | — |
| resume_id | BIGINT | N | — | FK `fk_edu_resume` → resume(id) ON DELETE CASCADE | 所属简历 |
| school | VARCHAR(80) | Y | NULL | — | 学校 |
| major | VARCHAR(80) | Y | NULL | — | 专业 |
| degree | VARCHAR(20) | Y | NULL | — | 学历 |
| period | VARCHAR(40) | Y | NULL | — | 起止时间 |
| gpa | VARCHAR(20) | Y | NULL | — | GPA/成绩 |
| sort_no | INT | N | 0 | — | 展示顺序 |

### 3.4 resume_project（项目/实习经历）

| 字段 | 类型 | 空 | 默认 | 约束/取值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| id | BIGINT | N | 自增 | PK | — |
| resume_id | BIGINT | N | — | FK `fk_project_resume` → resume(id) ON DELETE CASCADE | 所属简历 |
| title | VARCHAR(80) | Y | NULL | — | 项目名称 |
| role | VARCHAR(40) | Y | NULL | — | 担任角色 |
| period | VARCHAR(40) | Y | NULL | — | 起止时间 |
| description | TEXT | Y | NULL | — | 经历描述（≤1000 字，应用校验） |
| sort_no | INT | N | 0 | — | 展示顺序 |

### 3.5 resume_skill（技能）

| 字段 | 类型 | 空 | 默认 | 约束/取值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| id | BIGINT | N | 自增 | PK | — |
| resume_id | BIGINT | N | — | FK `fk_skill_resume` → resume(id) ON DELETE CASCADE | 所属简历 |
| skill | VARCHAR(50) | N | — | UK `uk_resume_skill(resume_id, skill)` | 技能名；应用层按大小写不敏感去重 |
| sort_no | INT | N | 0 | — | 展示顺序 |

### 3.6 job（岗位与 JD）

| 字段 | 类型 | 空 | 默认 | 约束/取值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| id | BIGINT | N | 自增 | PK | — |
| user_id | BIGINT | N | — | FK `fk_job_user` → sys_user(id) | 归属学生 |
| title | VARCHAR(50) | N | — | — | 岗位名称 |
| company | VARCHAR(50) | N | — | — | 公司名称（应用要求必填） |
| jd | TEXT | N | — | — | JD 原文（10~5000 字，应用校验） |
| keywords | VARCHAR(500) | Y | NULL | — | 逗号分隔关键词；空时从 JD 抽取英文技术词 |
| created_at | DATETIME | N | — | — | 创建时间 |
| updated_at | DATETIME | N | — | — | 更新时间 |

### 3.7 analysis_task（分析任务）

| 字段 | 类型 | 空 | 默认 | 约束/取值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| id | VARCHAR(40) | N | — | PK | 业务号（`a` + 16 位 UUID） |
| user_id | BIGINT | N | — | FK `fk_task_user` → sys_user(id) | 发起人 |
| resume_id | BIGINT | N | — | FK `fk_task_resume` → resume(id) | 简历 |
| job_id | BIGINT | N | — | FK `fk_task_job` → job(id) | 岗位 |
| resume_name | VARCHAR(80) | Y | NULL | — | 发起时简历名快照 |
| job_title | VARCHAR(120) | Y | NULL | — | 发起时岗位/公司快照 |
| status | VARCHAR(20) | N | — | 触发器：RUNNING/SUCCESS/FAILED | 任务状态 |
| mode | VARCHAR(10) | N | — | 触发器：AI/RULE | 分析方式 |
| score | INT | Y | NULL | 建议 0~100（应用保证） | 匹配度 |
| duration | DECIMAL(6,1) | N | 0 | — | 耗时（秒） |
| missing_keywords | TEXT | Y | NULL | — | 缺失关键词，逗号分隔 |
| suggestions_json | LONGTEXT | Y | NULL | JSON 数组 | 分模块建议 |
| reason | VARCHAR(500) | Y | NULL | — | 失败原因或降级说明 |
| feedback_type | VARCHAR(20) | Y | NULL | 触发器：USEFUL/USELESS/NULL | 反馈类型（对外小写） |
| feedback_note | VARCHAR(200) | Y | NULL | — | 反馈备注 |
| feedback_at | DATETIME | Y | NULL | — | 反馈时间 |
| demo_fail | TINYINT | N | 0 | 0/1 | 演示失败标记（异步执行时清零） |
| created_at | DATETIME | N | — | — | 创建时间 |
| updated_at | DATETIME | N | — | — | 更新时间 |

索引：`idx_task_user_created(user_id, created_at)`、`idx_task_resume(resume_id)`、`idx_task_job(job_id)`、`idx_task_status(status)`。

### 3.8 resume_version（版本快照）

| 字段 | 类型 | 空 | 默认 | 约束/取值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| id | VARCHAR(40) | N | — | PK | 业务号（`v` + 16 位 UUID） |
| resume_id | BIGINT | N | — | FK `fk_version_resume` → resume(id) ON DELETE CASCADE | 所属简历 |
| version | INT | N | — | UK `uk_resume_version(resume_id, version)` | 版本号，从 1 递增 |
| source_task_id | VARCHAR(40) | Y | NULL | 无 FK（见 §5） | 来源分析任务 ID |
| note | VARCHAR(200) | N | — | — | 修改说明 |
| snapshot_json | LONGTEXT | N | — | JSON | 摘要/技能/项目快照 |
| created_at | DATETIME | N | — | — | 创建时间 |

### 3.9 auth_token（登录凭证，v1.7）

| 字段 | 类型 | 空 | 默认 | 约束/取值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| token | VARCHAR(64) | N | — | PK | 32 位 UUID（去横线） |
| user_id | BIGINT | N | — | FK `fk_token_user` → sys_user(id) ON DELETE CASCADE | 所属账号 |
| created_at | DATETIME | N | — | — | 签发时间 |
| expires_at | DATETIME | N | — | 索引 `idx_token_expires` | 过期时间；每次访问滑动续期（默认 12h，5 分钟节流写库） |
| last_seen_at | DATETIME | N | — | — | 最后访问时间，用于排障（不参与鉴权） |

> 设计说明：内存缓存 30 秒（减少鉴权查库），因此跨实例场景下删除凭证最多滞后 30 秒；单实例下禁用账号即清库 + 清缓存，立即踢下线（BR-USER-04）。启动时与登录时各清理一次过期行，避免表膨胀。

### 3.10 sys_config（系统配置，v1.7）

| 字段 | 类型 | 空 | 默认 | 约束/取值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| config_key | VARCHAR(64) | N | — | PK | 配置键：`ai.model`、`ai.vendor`、`ai.endpoint`、`ai.api-key-enc` |
| config_value | VARCHAR(500) | N | — | — | 配置值（模型 ID，2~80 字符，应用层校验） |
| updated_at | DATETIME | N | — | — | 最后修改时间 |

> 设计说明：`ai.model` 缺失时回落到 `application.yml` 的 `ai.model`，保证「不配置也能跑」；保存为空即删除该行。`ai.api-key-enc` 只存 AES-256-GCM 密文（`enc:v1:` 前缀），不存明文密钥。

### 3.11 user_api_config（学生自定义 API 配置，v2.0）

| 字段 | 类型 | 空 | 默认 | 约束/取值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| id | BIGINT | N | 自增 | PK | 配置 ID |
| user_id | BIGINT | N | — | FK `fk_uac_user` → sys_user(id) ON DELETE CASCADE；索引 `idx_uac_user` | 归属学生 |
| name | VARCHAR(60) | N | — | 应用层同账号唯一 | API 名称 |
| method | VARCHAR(10) | N | GET | 应用层枚举 GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS | 请求方法 |
| url | VARCHAR(500) | N | — | 应用层校验 http(s)、含 host、无空白 | 请求地址 |
| headers_json | TEXT | Y | NULL | JSON 数组 `[{name,value,enabled}]`；`value` 为 `enc:v1:` 密文 | 请求头，≤20 行；名称明文便于展示，值加密（v1.3） |
| params_json | TEXT | Y | NULL | JSON 数组 `[{name,type,value,defaultValue,enabled,description}]` | 查询参数，≤30 行 |
| body_type | VARCHAR(20) | N | none | 应用层枚举 none/json/form/text | 请求体类型 |
| body_json | TEXT | Y | NULL | ≤20000 字；json 需可解析，form 为编码后串 | 请求体内容 |
| group_name | VARCHAR(40) | N | 默认分组 | — | 分组，用于筛选 |
| tags | VARCHAR(200) | N | ''（空串） | 逗号分隔，≤10 个 | 标签，`FIND_IN_SET` 过滤 |
| timeout_ms | INT | N | 15000 | 应用层收敛 1000~60000 | 读取超时（毫秒） |
| retries | INT | N | 0 | 应用层 0~3 | 失败重试次数 |
| enabled | TINYINT | N | 1 | 0/1 | 停用后 invoke 拒绝 |
| created_at | DATETIME | N | — | — | 创建时间 |
| updated_at | DATETIME | N | — | 排序键 | 更新时间 |

> 设计说明：`headers_json/params_json` 用 TEXT 存 JSON 而非拆子表——配置整体读写、无跨行查询需求，拆表反而增加事务与映射成本；标签用逗号串 + `FIND_IN_SET`，避免 `LIKE` 误命中（如「前端」命中「前端开发」）。`timeout_ms/retries` 在应用层收敛到合法区间，数据库不设 CHECK（5.5 会忽略）。表由 `UserApiConfigDao` 在启动时 `CREATE TABLE IF NOT EXISTS` 自愈补建。

## 4. 约束与触发器（MySQL 5.5 适配）

| 类型 | 内容 |
| --- | --- |
| 外键 | `resume.user_id`、`job.user_id`、`analysis_task.user_id/resume_id/job_id` 为 RESTRICT；`resume_education/project/skill.resume_id`、`resume_version.resume_id`、`auth_token.user_id`、`user_api_config.user_id` 为 CASCADE（共 10 个） |
| 唯一 | `sys_user.account`、`resume_version(resume_id, version)`、`resume_skill(resume_id, skill)`、`auth_token.token`（主键） |
| 索引 | `analysis_task(user_id, created_at)` 支撑“我的记录”排序；`auth_token(user_id)` 支撑按用户踢下线、`auth_token(expires_at)` 支撑过期清理；`user_api_config(user_id)` 支撑按账号列配置；`resume_version` 不再保留冗余单列索引 |
| 触发器 | `trg_user_bi/bu`：role/status；`trg_resume_bi/bu`：completeness 0~100、version ≥1；`trg_task_bi/bu`：status/mode/feedback_type |
| 并发 | 发起分析在事务内 `SELECT ... FROM resume WHERE id=? FOR UPDATE`，串行化同一简历的并发请求（BR-ANALYSIS-02） |

## 5. 已知取舍与后续演进

| 项 | 说明 |
| --- | --- |
| 字符串主键 | `analysis_task/resume_version` 使用随机业务号，万级数据下页分裂影响可忽略；未来多租户场景可改 `BIGINT` 主键 + 业务号列 |
| 子表审计字段 | 子模块采用“整体删除重建”，无 `created_at/updated_at`；如后续需要模块级追溯再补 |
| `source_task_id` 无外键 | 版本快照需在任务清理后仍可追溯，且种子数据先写版本后写任务；孤儿值仅影响来源展示 |
| 排序规则 | MySQL 5.5 无 `utf8mb4_0900_ai_ci`，保持 `utf8mb4_general_ci` |
| 版本快照增长 | 版本只增不删；后续可增加版本数上限或归档策略 |
| 凭证表增长 | 每次登录插入一行，靠「启动清理 + 登录时清理该用户过期行」控制；如需审计可改为标记失效而非删除 |
| 凭证缓存 | 内存缓存 30 秒，多实例下删除感知有延迟；单实例（当前部署）无此问题 |
| 配置表通用性 | `sys_config` 为通用键值表，存模型、厂商、地址与**加密后**的 API-KEY（`enc:v1:` 密文，v1.9 起）；明文密钥不落库 |
| 自定义 API 唯一性 | `user_api_config` 未建 `(user_id, name)` 唯一键：同名拦截在 Service 层完成，避免升级脚本在已有重复数据上失败；如需强约束可在清理重复后追加 |
| 自定义 API 凭据 | 请求头值复用 `AiKeyCipher`（AES-256-GCM）加密存库，主密钥取 `ai.secret-key` 或本机 `~/.ai-resume/ai-secret.key`；更换密钥后旧密文不可解，需重新填写；多实例部署需共享同一主密钥 |

## 6. 种子数据一致性

- 简历 1~6 的 `resume.version` 均等于 `MAX(resume_version.version)`（v1~v11 共 11 条版本记录）。
- 反馈类型在 `data.sql` 中统一为大写 `USEFUL/USELESS`。
- 修改种子数据后需复核：
  ```sql
  SELECT r.id, r.version, COALESCE(MAX(v.version), 0) AS maxv
  FROM resume r LEFT JOIN resume_version v ON v.resume_id = r.id
  GROUP BY r.id, r.version
  HAVING r.version <> COALESCE(MAX(v.version), 0);
  ```
