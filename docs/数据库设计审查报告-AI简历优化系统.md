# 数据库设计审查报告 · AI 简历优化系统

- **审查日期**：2026-10-24
- **复核日期**：2026-10-25（v1.6，按 MySQL 5.5.15 适配并验证）
- **审查类型**：专项评审（数据库设计与性能）
- **审查对象**：`ai-resume-system/db/schema.sql`、`data.sql`（演示种子数据）、`migration-v1.6-db-review.sql`
- **需求基线**：《需求分析说明书-AI简历优化系统》v0.1（功能实现后已升至 v0.6）
- **交叉验证材料**：后端 DAO/Service 实际 SQL（`AnalysisDao.java`、`VersionDao.java`、`ResumeDao.java`、`ResumeService.java`、`AnalysisService.java`）
- **可验证范围**：2026-10-25 复核时已在真实 MySQL 5.5.15 实例执行迁移，并完成端到端与并发验证

---

## 一、评审基线

```text
评审对象：schema.sql + data.sql（当前工作区版本）
评审范围：数据库专项（表结构 / 关联关系 / 索引 / 扩展性 / 性能），并核对其直接上下游（需求业务规则、应用层查询）
需求基线：需求分析说明书 v0.1（BR-ANALYSIS-02、BR-VERSION-01、BR-USER-02 等）
材料状态：完整（建表脚本、种子数据、应用代码均可用）
可验证范围：仅静态检查与代码走查，未执行 SQL / 未做并发验证
```

## 二、总体结论：**已完成修复（复核通过）**

核心业务闭环（简历录入 → JD 录入 → 分析 → 采纳 → 版本留存）的数据模型完整、可行。审查发现的 2 个重要问题已关闭；3 个一般问题已按 MySQL 5.5 能力修复；5 个建议中 2 个已关闭、1 个判定不适用、2 个按建议暂缓。

| 等级 | 数量 | 编号 | 复核状态 |
| --- | --- | --- | --- |
| 阻断 | 0 | — | — |
| 重要 | 2 | DR-001、DR-002 | ✅ 已关闭 |
| 一般 | 3 | DR-003、DR-004、DR-005 | ✅ 已关闭（DR-005 改用触发器） |
| 建议 | 5 | DR-006 ~ DR-010 | DR-006/007 关闭；DR-009 不适用；DR-008/010 暂缓 |

---

## 三、问题清单

### DR-001 【重要】同一简历并发发起分析缺少数据库层兜底（BR-ANALYSIS-02 冲突窗口）

- **位置/证据**：[AnalysisService.java#L66](file:///<PROJECT_ROOT>/ai-resume-system/server/src/main/java/cn/course/airesume/analysis/AnalysisService.java#L66) 先调 `existsRunning(resumeId)` 检查，[#L83](file:///<PROJECT_ROOT>/ai-resume-system/server/src/main/java/cn/course/airesume/analysis/AnalysisService.java#L83) 再 INSERT；两步之间非原子。schema.sql 的 `analysis_task` 表无任何唯一约束防止同一 `resume_id` 出现两条 `RUNNING` 记录。
- **冲突依据**：需求 BR-ANALYSIS-02 规定"同一简历同一时刻只允许 1 个分析中任务"，验收条件要求"任务总数不增加"。检查-再插入模式下，两个并发请求可同时通过检查，产生 2 条 RUNNING 任务，需求被绕过且应用层无法事后发现。
- **影响范围**：发起分析接口；后续 AI 回调/超时处理会对同简历双任务重复执行，统计口径失真。
- **修改建议**（二选一，推荐 A）：
  - **A（应用层锁）**：发起分析改为事务内 `SELECT id FROM resume WHERE id=? FOR UPDATE` 锁定简历行后再检查并插入，串行化同一简历的发起请求；
  - **B（数据库约束）**：MySQL 8 增加生成列 + 唯一索引：`running_key BIGINT GENERATED ALWAYS AS (CASE WHEN status='RUNNING' THEN resume_id END) VIRTUAL` + `UNIQUE KEY uk_running (running_key)`（非 RUNNING 时为 NULL，不参与唯一性），INSERT 冲突时捕获 `DuplicateKeyException` 返回"已有进行中任务"。
- **验证方式**：并发两次调用发起分析接口，断言仅产生 1 条 RUNNING 记录。

### DR-002 【重要】全库无外键约束，引用完整性完全依赖应用层

- **位置/证据**：[schema.sql](file:///<PROJECT_ROOT>/ai-resume-system/db/schema.sql) 全文无 `FOREIGN KEY`。涉及 8 处引用：`resume.user_id`、`job.user_id`、`resume_education/project/skill.resume_id`、`analysis_task.user_id/resume_id/job_id`、`resume_version.resume_id/source_task_id`。
- **影响范围**：
  - 已确认应用层**不存在简历删除功能**（全代码仅删除子模块行用于整体替换，见 `ResumeDao.replaceSubModules`），因此当前孤儿数据风险有限；
  - 但 `analysis_task` / `resume_version` 对 `resume` 的引用无数据库保护，种子数据手工维护、后续若增加删除或清理功能，极易产生孤儿版本/孤儿任务；
  - 原型/演示阶段直接改库无任何保护。
- **修改建议**：本期至少为**只增不改的引用**补外键：`resume_version.resume_id → resume.id ON DELETE CASCADE`、三个子模块表 `resume_id → resume.id ON DELETE CASCADE`；`analysis_task` 的引用若担心外键锁影响可保留应用层保证，但需在设计文档"数据字典"中明确记录"该选择为有意为之，引用完整性由 Service 层 @Transactional 保证"。文件头的 `SET FOREIGN_KEY_CHECKS` 切换（第 14-23 行）在无外键前提下无实际作用，可一并删除。
- **复核（2026-10-25）**：已补 9 个外键；`SET FOREIGN_KEY_CHECKS` 保留用于重建表；`source_task_id` 按豁免理由不建 FK，详见第七章。
- **待确认**：需求文档 BR-VERSION-02"版本记录只读【待确认】"——若确认不可删除，外键 CASCADE 不会与业务冲突。

### DR-003 【一般】resume_version 存在冗余索引 idx_version_resume

- **位置/证据**：[schema.sql#L128-L129](file:///<PROJECT_ROOT>/ai-resume-system/db/schema.sql#L128-L129)：`uk_resume_version (resume_id, version)` 与 `idx_version_resume (resume_id)` 并存。
- **影响**：唯一索引的最左前缀已完全覆盖 `WHERE resume_id=?` 查询（`listByResume`、`findByVersion`、`maxVersion`），第二个索引纯属冗余，只增加每次写版本时的索引维护成本。
- **修改建议**：`DROP KEY idx_version_resume`，保留 `uk_resume_version` 即可。

### DR-004 【一般】analysis_task 索引与高频查询不匹配

- **位置/证据**：现有索引为 `idx_task_user(user_id)`、`idx_task_resume(resume_id)`、`idx_task_status(status)`。实际高频查询（`AnalysisDao.listByUser`）为 `WHERE user_id=? [AND status=?] ORDER BY created_at DESC`——排序需 filesort；而 `idx_task_status` 单列选择性极低（仅 3 个状态值），几乎无使用价值。
- **影响**：数据量小（课程演示）暂无感知；随分析任务累积（3-6 个月万级），"我的分析记录"列表（最高频页面）排序退化。
- **修改建议**：`idx_task_user` 改为 `idx_task_user_created (user_id, created_at)`；`idx_task_status` 可保留给管理端 `countByStatus`/`avgScore` 统计，或与状态筛选组合为 `(status, created_at)`，二选一即可，不必同时加。

### DR-005 【一般】状态/枚举字段无 CHECK 约束，且大小写约定不一致

- **位置/证据**：`sys_user.role/status`、`analysis_task.status/mode/feedback_type` 均为裸 `VARCHAR` 无约束；data.sql 中 `feedback_type` 使用小写 `'useful'/'useless'`（第 70、74 行），与其他枚举全大写（`STUDENT`/`SUCCESS`/`AI`/`RULE`）风格不一致。
- **影响**：脏值可直接入库，状态机仅靠代码维护；大小写混用增加代码中字符串比较出错概率。`resume.completeness`（应为 0-100）同样无范围约束。
- **修改建议**：MySQL 8 支持 CHECK，建议补充：
  ```sql
  CHECK (status IN ('RUNNING','SUCCESS','FAILED'))
  CHECK (mode IN ('AI','RULE'))
  CHECK (completeness BETWEEN 0 AND 100)
  ```
  并在数据字典中统一枚举大小写约定（建议全部大写：`USEFUL`/`USELESS`，需同步修改应用代码与种子数据）。
- **复核（2026-10-25）**：实际为 MySQL 5.5，CHECK 会被忽略；已改用 6 个触发器实现同等校验，并统一 `feedback_type` 为大写、API 层保持小写。

### DR-006 【建议】resume.version 与 resume_version 最大版本号为双写一致性风险

- **位置/证据**：`resume.version`（列表展示用）应恒等于 `MAX(resume_version.version)`。应用采纳流程已在同一 `@Transactional` 内更新（`ResumeService.java#L111-L123`，已确认），种子数据亦核对一致（简历 1→v3、简历 2→v5、简历 3→v6 均匹配）。
- **修改建议**：无需改表；在数据字典中标注 `resume.version` 为派生冗余字段及其同步规则，防止后续维护者单独更新其一。

### DR-007 【建议】resume_skill 缺少 (resume_id, skill) 唯一约束

- **位置/证据**：同一简历可重复录入同一技能；规则引擎按 JD 关键词与技能清单匹配时重复项不影响正确性，但展示与统计会重复计数。
- **修改建议**：`UNIQUE KEY uk_resume_skill (resume_id, skill)`，由应用层捕获冲突提示"技能已存在"。属产品决定项，若允许重复则维持现状并记录。

### DR-008 【建议】字符串主键对 InnoDB 聚簇索引不友好

- **位置/证据**：`analysis_task.id`、`resume_version.id` 为 `VARCHAR(40)` 随机串主键（应用生成 16 位 UUID 截断），聚簇索引插入随机、页分裂频繁，且子表引用宽度放大。
- **影响**：课程项目数据量（千~万级）下影响可忽略；分析任务表是全库唯一高频写入表，为未来唯一隐患点。
- **修改建议**：本期不改（迁移成本中等、收益低）；若后续扩展为真实多用户场景，改为 `BIGINT AUTO_INCREMENT` 主键 + `task_no VARCHAR(40)` 业务号。

### DR-009 【建议】排序规则 utf8mb4_general_ci 非 MySQL 8 推荐

- **位置/证据**：[schema.sql#L6](file:///<PROJECT_ROOT>/ai-resume-system/db/schema.sql#L6) 使用 `utf8mb4_general_ci`。
- **影响**：对部分字符（如全半角、特殊重音字符）相等判断不够准确；`MySQL 8` 默认推荐 `utf8mb4_0900_ai_ci`。课程场景实际影响极小。
- **修改建议**：新建库时改为 `utf8mb4_0900_ai_ci`；存量库不强制迁移。
- **复核（2026-10-25）**：实际数据库为 MySQL 5.5.15，无 `utf8mb4_0900_ai_ci`；本项判定**不适用**，保持 `utf8mb4_general_ci`。

### DR-010 【建议】子表缺少审计时间字段

- **位置/证据**：`resume_education/project/skill` 采用"整体删除重建"策略（`replaceSubModules`），无 `created_at/updated_at`；`resume_version` 仅 `created_at`（合理，版本不可变）。
- **影响**：审计追溯能力弱，但本期需求无此要求。
- **修改建议**：本期可不加；若后续版本对比需要"模块级修改时间"再补充。

---

## 四、通过项（正面确认）

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| 账号唯一性 | 通过 | `uk_user_account(account)`，防重复注册 |
| 版本号并发保护 | 通过 | `uk_resume_version(resume_id, version)` + 应用捕获 `DuplicateKeyException`（`VersionDao.tryInsert`），BR-VERSION-01 的并发保护有数据库兜底 |
| 历史展示快照（合理反范式） | 通过 | `analysis_task.resume_name/job_title` 冗余存储发起时快照，简历/岗位改名不影响历史记录展示，属有依据的反范式设计 |
| 敏感信息脱敏 | 通过 | `phone` 存储掩码值（`138****6621`），原始手机号不入库，符合"日志与数据不记录完整手机号"要求 |
| 口令存储 | 通过 | `password_hash VARCHAR(255)`，格式 `iter:salt:hash`，无明文 |
| 字段长度 | 通过 | 抽查 `account(50)/password_hash(255)/jd(TEXT)/keywords(500)/snapshot_json(LONGTEXT)` 均满足数据与 foreseeable 增长 |
| 数值类型 | 通过 | `score INT`、`duration DECIMAL(6,1)`（上限 99999.9 秒）、`version INT`、`completeness INT` 与业务量级匹配 |
| 版本快照设计 | 通过 | `snapshot_json LONGTEXT` 独立于主表，主表只存当前态，版本只增不改，与"版本留存可回溯"需求吻合 |
| 种子数据与约束一致性 | 通过 | version 一致性、外键引用值、状态值均与 schema 吻合；用户 4（DISABLED）覆盖禁用状态演示 |

## 五、未来 3-6 个月扩展性评估

| 可预见扩展（来自需求"选做"与待确认项） | 结构可行性 | 结论 |
| --- | --- | --- |
| 岗位匹配度排序 | `analysis_task` 已有 `score/job_id`，排序查询可覆盖 | 无需改表 |
| 多简历对比 | 版本快照 JSON 可跨简历读取对比 | 无需改表 |
| PDF/Word 上传解析（选做） | `resume` 增加 `file_name/file_path` 列即可，子模块结构不受影响 | 平滑 ALTER，无阻断 |
| 模拟面试问题生成（选做） | 新增 `interview_question(task_id, ...)` 表引用 `analysis_task` | 平滑新增，无阻断 |
| 就业指导教师角色（待确认） | `sys_user.role VARCHAR(20)` 可扩展 `'TEACHER'` | 结构无阻碍，数据权限需应用层设计 |
| 数据量增长（3-6 个月万级任务） | 单表按 user_id 查询，补 DR-004 组合索引后无瓶颈 | 风险可控 |
| 版本快照无限增长 | 每版本 LONGTEXT 快照只增不删（BR-VERSION-02），高频采纳用户 6 个月可达数十 MB | 3-6 个月可控；建议在后续版本增加版本数上限或归档策略（纳入设计文档"待确认"） |

## 六、问题汇总表

| 编号 | 位置 | 问题 | 等级 | 状态 | 最小修正 |
| --- | --- | --- | --- | --- | --- |
| DR-001 | AnalysisService + analysis_task | 并发发起分析无数据库兜底 | 重要 | ✅ 已关闭 | 事务内 `SELECT ... FOR UPDATE` 锁定简历行 |
| DR-002 | schema.sql 全表 | 无外键约束 | 重要 | ✅ 已关闭 | 补 9 个 FK；`source_task_id` 记录豁免理由 |
| DR-003 | resume_version | 冗余索引 idx_version_resume | 一般 | ✅ 已关闭 | 已 DROP KEY |
| DR-004 | analysis_task | 缺 (user_id, created_at) 组合索引 | 一般 | ✅ 已关闭 | 已替换为 idx_task_user_created，并补 idx_task_job |
| DR-005 | 多表 | 枚举无 CHECK、大小写不一致 | 一般 | ✅ 已关闭 | 5.5 无 CHECK → 6 个触发器；feedback_type 统一大写 |
| DR-006 | resume.version | 派生字段双写一致性 | 建议 | ✅ 已关闭 | 补数据字典；修复简历 4/5/6 缺失版本快照 |
| DR-007 | resume_skill | 技能可重复录入 | 建议 | ✅ 已关闭 | 加 `uk_resume_skill` + 应用层去重 |
| DR-008 | VARCHAR 主键 | 聚簇索引不友好 | 建议 | ⏸ 暂缓 | 本期不改，已记录取舍 |
| DR-009 | utf8mb4_general_ci | 非 8.0 推荐排序规则 | 建议 | ✅ 不适用 | 实际为 MySQL 5.5，无 `0900_ai_ci`，保持现状 |
| DR-010 | 子表 | 缺审计时间字段 | 建议 | ⏸ 暂缓 | 本期不加，已记录取舍 |

## 七、复核记录（2026-10-25）

### 7.1 修复内容

| 编号 | 修复方式 | 涉及文件 |
| --- | --- | --- |
| DR-001 | `AnalysisService.create` 使用 `TransactionTemplate`，事务内 `resumeService.requireOwnForUpdate` → `existsRunning` → INSERT；提交后再触发异步执行 | `AnalysisService.java`、`ResumeService.java`、`ResumeDao.java` |
| DR-002 | 新增 9 个 FK：`resume.user_id`、`job.user_id`、`analysis_task.user_id/resume_id/job_id`（RESTRICT）；三个子模块与 `resume_version.resume_id`（CASCADE）；`source_task_id` 不建 FK 并在数据字典记录理由 | `schema.sql`、`migration-v1.6-db-review.sql`、`数据库设计说明` |
| DR-003 | 删除 `idx_version_resume`，保留 `uk_resume_version` 最左前缀 | 同上 |
| DR-004 | `idx_task_user` → `idx_task_user_created(user_id, created_at)`；新增 `idx_task_job(job_id)` 供 FK/查询使用 | 同上 |
| DR-005 | 5.5 不支持 CHECK，改为 6 个 BEFORE INSERT/UPDATE 触发器；`feedback_type` 数据库统一 `USEFUL/USELESS`，API 层保持小写映射 | `schema.sql`、迁移脚本、`AnalysisService.java`、`AdminService.java`、`data.sql` |
| DR-006 | 新增《数据库设计说明》数据字典；补简历 4/5/6 缺失的 v7~v11 版本快照 | `数据库设计说明-AI简历优化系统.md`、`data.sql`、迁移脚本 |
| DR-007 | 新增 `uk_resume_skill(resume_id, skill)`；`ResumeForm` 与采纳版本路径按大小写不敏感去重 | `schema.sql`、`ResumeForm.java`、`ResumeService.java` |
| DR-008 | 按建议暂缓：字符串主键保留，已在数据字典记录未来演进方案 | 数据字典 |
| DR-009 | 判定不适用：实际数据库为 MySQL 5.5.15，无 `utf8mb4_0900_ai_ci`；保持 `utf8mb4_general_ci` | 本报告、数据字典 |
| DR-010 | 按建议暂缓：子表审计字段本期不加 | 数据字典 |

### 7.2 实际验证

- **建库脚本**：在临时库 `ai_resume_tmp` 完整执行新 `schema.sql` + `data.sql`，7 用户 / 11 版本 / 反馈全大写，6 个触发器创建成功。
- **约束验证**：
  - 插入 `status='BROKEN'` → `ERROR 1644 analysis_task.status invalid`；
  - 插入重复技能 `(1,'Java')` → `ERROR 1062 Duplicate entry '1-Java' for key 'uk_resume_skill'`；
  - `information_schema` 确认 9 个外键、`idx_task_user_created`、`idx_task_job`、唯一键与 6 个触发器均已生效。
- **迁移验证**：`migration-v1.6-db-review.sql` 在现有 `ai_resume` 库执行成功；10 条 lowercase 反馈已转大写，简历 1~6 的 `resume.version` 与 `MAX(resume_version.version)` 全部一致。
- **并发验证**：`e2e_test.py` 新增两个线程同时发起同一简历分析，结果 `codes == [0, 1]`，且同一简历进行中任务 ≤ 1。
- **回归**：e2e **86/86**、真实 AI **7/7**、Mock AI **9/9** 通过；技能去重用例通过。
- **数据字典**：新增《数据库设计说明-AI简历优化系统.md》，覆盖 8 张表字段、外键/索引/触发器、枚举映射与已知取舍。

### 7.3 MySQL 5.5 适配说明

- **CHECK**：5.5 解析但忽略，不能作为约束，改用触发器；触发器通过 `SIGNAL SQLSTATE '45000'` 拒绝非法值。
- **生成列/函数索引**：5.5 不支持，DR-001 放弃报告中的方案 B，采用方案 A（`FOR UPDATE`）。
- **排序规则**：5.5 无 `utf8mb4_0900_ai_ci`，DR-009 判定不适用。
- **触发器部署**：`schema.sql` / 迁移脚本需通过 mysql 客户端整体执行，`DELIMITER` 才能生效。

## 八、验证声明

- 本报告原审查结论基于静态检查；2026-10-25 复核已完成真实 MySQL 5.5.15 实例上的迁移与约束验证。
- DR-001 已做真实并发请求验证（两线程同时发起，仅一个成功）。
- 种子数据一致性已在数据库运行时校验（版本号差异查询返回 0 行）。
- DR-008/010 为明确暂缓项，不是遗漏；DR-009 为环境不适用项，均已在数据字典记录。

---

*复核产出：本报告 + 《数据库设计说明-AI简历优化系统.md》+ 迁移脚本。状态流转：待处理 → 已关闭 / 暂缓 / 不适用。*
