-- ============================================================
-- 数据库审查问题修复迁移（MySQL 5.5.15）
-- 对应：DR-002/003/004/005/006/007
-- 使用：mysql -uroot -p123456 --default-character-set=utf8mb4 < migration-v1.6-db-review.sql
-- 说明：本脚本只执行一次；执行前请确认无孤儿数据与重复技能。
-- ============================================================

USE ai_resume;
SET NAMES utf8mb4;

-- ---------- 0. 数据规范化：反馈枚举统一大写 ----------
UPDATE analysis_task SET feedback_type = 'USEFUL' WHERE feedback_type = 'useful';
UPDATE analysis_task SET feedback_type = 'USELESS' WHERE feedback_type = 'useless';

-- ---------- 1. 补全种子版本（DR-006：简历 4/5/6 缺版本记录） ----------
INSERT IGNORE INTO resume_version (id, resume_id, version, source_task_id, note, snapshot_json, created_at) VALUES
('v7', 4, 1, NULL, '前端方向初稿', '{"summary":"前端方向本科在读，熟悉 Vue 3 与基础工程化。","skills":["Vue 3","JavaScript","CSS"],"projects":[{"title":"校园活动报名平台","desc":"使用 Vue 3 + Vite 实现活动列表与报名表单。"}]}', '2026-10-05 19:20:00'),
('v8', 4, 2, NULL, '补充 TypeScript 与后台管理页面', '{"summary":"前端方向本科在读，熟悉 Vue 3 与工程化构建。","skills":["Vue 3","JavaScript","TypeScript","Vite","CSS"],"projects":[{"title":"校园活动报名平台","desc":"使用 Vue 3 + Vite 实现活动列表、报名表单与后台管理页面。"}]}', '2026-10-20 11:10:00'),
('v9', 5, 1, NULL, '测试开发方向初稿', '{"summary":"熟悉接口测试与自动化测试框架。","skills":["Python","Pytest","Postman","MySQL","Jenkins"],"projects":[{"title":"接口自动化测试平台","desc":"基于 Pytest 编写 120 条接口用例，接入 Jenkins 持续集成。"}]}', '2026-09-26 10:00:00'),
('v10', 6, 1, NULL, '后端方向初稿', '{"summary":"Java 后端方向，具备 Spring Boot 项目经验。","skills":["Java","Spring Boot","MySQL","Redis"],"projects":[{"title":"校园跑腿系统","desc":"使用 Spring Boot + MySQL 实现订单撮合与状态流转。"}]}', '2026-10-01 14:30:00'),
('v11', 6, 2, NULL, '补充 Redis 分布式锁', '{"summary":"Java 后端方向，关注并发与中间件。","skills":["Java","Spring Boot","MySQL","Redis","Kafka"],"projects":[{"title":"校园跑腿系统","desc":"使用 Spring Boot + MySQL 实现订单撮合与状态流转，接入 Redis 分布式锁。"}]}', '2026-10-21 17:03:00');

-- ---------- 2. 索引调整（DR-003/004） ----------
ALTER TABLE resume_version DROP INDEX idx_version_resume;
ALTER TABLE analysis_task
  DROP INDEX idx_task_user,
  ADD INDEX idx_task_user_created (user_id, created_at),
  ADD INDEX idx_task_job (job_id);

-- ---------- 3. 技能唯一约束（DR-007） ----------
ALTER TABLE resume_skill ADD UNIQUE KEY uk_resume_skill (resume_id, skill);

-- ---------- 4. 外键约束（DR-002） ----------
ALTER TABLE resume
  ADD CONSTRAINT fk_resume_user FOREIGN KEY (user_id) REFERENCES sys_user (id);
ALTER TABLE resume_education
  ADD CONSTRAINT fk_edu_resume FOREIGN KEY (resume_id) REFERENCES resume (id) ON DELETE CASCADE;
ALTER TABLE resume_project
  ADD CONSTRAINT fk_project_resume FOREIGN KEY (resume_id) REFERENCES resume (id) ON DELETE CASCADE;
ALTER TABLE resume_skill
  ADD CONSTRAINT fk_skill_resume FOREIGN KEY (resume_id) REFERENCES resume (id) ON DELETE CASCADE;
ALTER TABLE job
  ADD CONSTRAINT fk_job_user FOREIGN KEY (user_id) REFERENCES sys_user (id);
ALTER TABLE analysis_task
  ADD CONSTRAINT fk_task_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
  ADD CONSTRAINT fk_task_resume FOREIGN KEY (resume_id) REFERENCES resume (id),
  ADD CONSTRAINT fk_task_job FOREIGN KEY (job_id) REFERENCES job (id);
ALTER TABLE resume_version
  ADD CONSTRAINT fk_version_resume FOREIGN KEY (resume_id) REFERENCES resume (id) ON DELETE CASCADE;
-- 说明：resume_version.source_task_id 为历史快照引用，不建外键，避免种子导入顺序与任务清理受限；
--      该字段仅用于展示来源岗位，孤儿值不影响业务。

-- ---------- 5. 枚举/范围触发器（DR-005：5.5 不支持 CHECK） ----------
DROP TRIGGER IF EXISTS trg_user_bi;
DROP TRIGGER IF EXISTS trg_user_bu;
DROP TRIGGER IF EXISTS trg_resume_bi;
DROP TRIGGER IF EXISTS trg_resume_bu;
DROP TRIGGER IF EXISTS trg_task_bi;
DROP TRIGGER IF EXISTS trg_task_bu;

DELIMITER $$

CREATE TRIGGER trg_user_bi BEFORE INSERT ON sys_user FOR EACH ROW
BEGIN
  IF NEW.role NOT IN ('STUDENT', 'ADMIN') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sys_user.role must be STUDENT or ADMIN';
  END IF;
  IF NEW.status NOT IN ('ACTIVE', 'DISABLED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sys_user.status must be ACTIVE or DISABLED';
  END IF;
END$$

CREATE TRIGGER trg_user_bu BEFORE UPDATE ON sys_user FOR EACH ROW
BEGIN
  IF NEW.role NOT IN ('STUDENT', 'ADMIN') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sys_user.role must be STUDENT or ADMIN';
  END IF;
  IF NEW.status NOT IN ('ACTIVE', 'DISABLED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sys_user.status must be ACTIVE or DISABLED';
  END IF;
END$$

CREATE TRIGGER trg_resume_bi BEFORE INSERT ON resume FOR EACH ROW
BEGIN
  IF NEW.completeness < 0 OR NEW.completeness > 100 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'resume.completeness must be between 0 and 100';
  END IF;
  IF NEW.version < 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'resume.version must be >= 1';
  END IF;
END$$

CREATE TRIGGER trg_resume_bu BEFORE UPDATE ON resume FOR EACH ROW
BEGIN
  IF NEW.completeness < 0 OR NEW.completeness > 100 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'resume.completeness must be between 0 and 100';
  END IF;
  IF NEW.version < 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'resume.version must be >= 1';
  END IF;
END$$

CREATE TRIGGER trg_task_bi BEFORE INSERT ON analysis_task FOR EACH ROW
BEGIN
  IF NEW.status NOT IN ('RUNNING', 'SUCCESS', 'FAILED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'analysis_task.status invalid';
  END IF;
  IF NEW.mode NOT IN ('AI', 'RULE') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'analysis_task.mode invalid';
  END IF;
  IF NEW.feedback_type IS NOT NULL AND NEW.feedback_type NOT IN ('USEFUL', 'USELESS') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'analysis_task.feedback_type invalid';
  END IF;
END$$

CREATE TRIGGER trg_task_bu BEFORE UPDATE ON analysis_task FOR EACH ROW
BEGIN
  IF NEW.status NOT IN ('RUNNING', 'SUCCESS', 'FAILED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'analysis_task.status invalid';
  END IF;
  IF NEW.mode NOT IN ('AI', 'RULE') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'analysis_task.mode invalid';
  END IF;
  IF NEW.feedback_type IS NOT NULL AND NEW.feedback_type NOT IN ('USEFUL', 'USELESS') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'analysis_task.feedback_type invalid';
  END IF;
END$$

DELIMITER ;
