-- ============================================================
-- AI 简历优化系统 · 数据库结构（MySQL 5.5.15）
-- 执行：mysql -u root -p --default-character-set=utf8mb4 < schema.sql
-- 说明：
--   1) 本机实际为 MySQL 5.5，不支持 CHECK（会解析但忽略）与生成列，
--      因此枚举/范围校验使用 BEFORE INSERT/UPDATE 触发器实现；
--   2) 外键使用 InnoDB 原生约束；删除用户/简历/岗位默认拒绝，避免历史任务被误删；
--   3) resume_version.resume_id 级联删除，子模块表随简历级联删除。
-- ============================================================

CREATE DATABASE IF NOT EXISTS ai_resume DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE ai_resume;
SET NAMES utf8mb4;

-- 重建时临时关闭外键检查（仅建表脚本使用）
SET FOREIGN_KEY_CHECKS = 0;
DROP TRIGGER IF EXISTS trg_user_bi;
DROP TRIGGER IF EXISTS trg_user_bu;
DROP TRIGGER IF EXISTS trg_resume_bi;
DROP TRIGGER IF EXISTS trg_resume_bu;
DROP TRIGGER IF EXISTS trg_task_bi;
DROP TRIGGER IF EXISTS trg_task_bu;
DROP TABLE IF EXISTS auth_token;
DROP TABLE IF EXISTS sys_config;
DROP TABLE IF EXISTS user_api_config;
DROP TABLE IF EXISTS resume_version;
DROP TABLE IF EXISTS analysis_task;
DROP TABLE IF EXISTS resume_skill;
DROP TABLE IF EXISTS resume_project;
DROP TABLE IF EXISTS resume_education;
DROP TABLE IF EXISTS resume;
DROP TABLE IF EXISTS job;
DROP TABLE IF EXISTS sys_user;
SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE sys_user (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  account       VARCHAR(50)  NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  name          VARCHAR(50)  NOT NULL,
  role          VARCHAR(20)  NOT NULL DEFAULT 'STUDENT',
  status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME     NOT NULL,
  UNIQUE KEY uk_user_account (account)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 登录凭证持久化（v1.7）：服务重启后仍有效；账号被禁用时按 user_id 批量删除
-- expires_at 为滑动过期时间（默认 12 小时），last_seen_at 仅用于展示与排障
CREATE TABLE auth_token (
  token        VARCHAR(64) NOT NULL,
  user_id      BIGINT      NOT NULL,
  created_at   DATETIME    NOT NULL,
  expires_at   DATETIME    NOT NULL,
  last_seen_at DATETIME    NOT NULL,
  PRIMARY KEY (token),
  KEY idx_token_user (user_id),
  KEY idx_token_expires (expires_at),
  CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 系统级配置（v1.7）：键值对，当前用于保存管理端选择的 AI 模型（ai.model）
CREATE TABLE sys_config (
  config_key   VARCHAR(64)  NOT NULL,
  config_value VARCHAR(500) NOT NULL,
  updated_at   DATETIME     NOT NULL,
  PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resume (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT       NOT NULL,
  name         VARCHAR(50)  NOT NULL,
  person_name  VARCHAR(20)  NOT NULL,
  target       VARCHAR(50)  NOT NULL,
  phone        VARCHAR(20)  NULL,
  email        VARCHAR(60)  NULL,
  summary      TEXT         NULL,
  version      INT          NOT NULL DEFAULT 1,
  completeness INT          NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL,
  updated_at   DATETIME     NOT NULL,
  KEY idx_resume_user (user_id),
  CONSTRAINT fk_resume_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resume_education (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  resume_id BIGINT      NOT NULL,
  school    VARCHAR(80) NULL,
  major     VARCHAR(80) NULL,
  degree    VARCHAR(20) NULL,
  period    VARCHAR(40) NULL,
  gpa       VARCHAR(20) NULL,
  sort_no   INT         NOT NULL DEFAULT 0,
  KEY idx_edu_resume (resume_id),
  CONSTRAINT fk_edu_resume FOREIGN KEY (resume_id) REFERENCES resume (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resume_project (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  resume_id   BIGINT       NOT NULL,
  title       VARCHAR(80)  NULL,
  role        VARCHAR(40)  NULL,
  period      VARCHAR(40)  NULL,
  description TEXT         NULL,
  sort_no     INT          NOT NULL DEFAULT 0,
  KEY idx_project_resume (resume_id),
  CONSTRAINT fk_project_resume FOREIGN KEY (resume_id) REFERENCES resume (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resume_skill (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  resume_id BIGINT       NOT NULL,
  skill     VARCHAR(50)  NOT NULL,
  sort_no   INT          NOT NULL DEFAULT 0,
  KEY idx_skill_resume (resume_id),
  UNIQUE KEY uk_resume_skill (resume_id, skill),
  CONSTRAINT fk_skill_resume FOREIGN KEY (resume_id) REFERENCES resume (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE job (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT       NOT NULL,
  title      VARCHAR(50)  NOT NULL,
  company    VARCHAR(50)  NOT NULL,
  jd         TEXT         NOT NULL,
  keywords   VARCHAR(500) NULL,
  created_at DATETIME     NOT NULL,
  updated_at DATETIME     NOT NULL,
  KEY idx_job_user (user_id),
  CONSTRAINT fk_job_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 学生自定义 API 配置（v2.0）：headers/params 以 JSON 文本存储，tags 为逗号分隔
CREATE TABLE user_api_config (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT       NOT NULL,
  name         VARCHAR(60)  NOT NULL,
  method       VARCHAR(10)  NOT NULL DEFAULT 'GET',
  url          VARCHAR(500) NOT NULL,
  headers_json TEXT         NULL,
  params_json  TEXT         NULL,
  body_type    VARCHAR(20)  NOT NULL DEFAULT 'none',
  body_json    TEXT         NULL,
  group_name   VARCHAR(40)  NOT NULL DEFAULT '默认分组',
  tags         VARCHAR(200) NOT NULL DEFAULT '',
  timeout_ms   INT          NOT NULL DEFAULT 15000,
  retries      INT          NOT NULL DEFAULT 0,
  enabled      TINYINT      NOT NULL DEFAULT 1,
  created_at   DATETIME     NOT NULL,
  updated_at   DATETIME     NOT NULL,
  KEY idx_uac_user (user_id),
  CONSTRAINT fk_uac_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE analysis_task (
  id                 VARCHAR(40)  PRIMARY KEY,
  user_id            BIGINT       NOT NULL,
  resume_id          BIGINT       NOT NULL,
  job_id             BIGINT       NOT NULL,
  resume_name        VARCHAR(80)  NULL,
  job_title          VARCHAR(120) NULL,
  status             VARCHAR(20)  NOT NULL,
  mode               VARCHAR(10)  NOT NULL,
  score              INT          NULL,
  duration           DECIMAL(6,1) NOT NULL DEFAULT 0,
  missing_keywords   TEXT         NULL,
  suggestions_json   LONGTEXT     NULL,
  reason             VARCHAR(500) NULL,
  feedback_type      VARCHAR(20)  NULL,
  feedback_note      VARCHAR(200) NULL,
  feedback_at        DATETIME     NULL,
  demo_fail          TINYINT      NOT NULL DEFAULT 0,
  created_at         DATETIME     NOT NULL,
  updated_at         DATETIME     NOT NULL,
  KEY idx_task_user_created (user_id, created_at),
  KEY idx_task_resume (resume_id),
  KEY idx_task_job (job_id),
  KEY idx_task_status (status),
  CONSTRAINT fk_task_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
  CONSTRAINT fk_task_resume FOREIGN KEY (resume_id) REFERENCES resume (id),
  CONSTRAINT fk_task_job FOREIGN KEY (job_id) REFERENCES job (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resume_version (
  id             VARCHAR(40)  PRIMARY KEY,
  resume_id      BIGINT       NOT NULL,
  version        INT          NOT NULL,
  source_task_id VARCHAR(40)  NULL,
  note           VARCHAR(200) NOT NULL,
  snapshot_json  LONGTEXT     NOT NULL,
  created_at     DATETIME     NOT NULL,
  UNIQUE KEY uk_resume_version (resume_id, version),
  CONSTRAINT fk_version_resume FOREIGN KEY (resume_id) REFERENCES resume (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- MySQL 5.5 不支持 CHECK：用触发器校验枚举与范围
-- 注意：脚本需通过 mysql 客户端整体执行，DELIMITER 才能生效
-- ------------------------------------------------------------
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
