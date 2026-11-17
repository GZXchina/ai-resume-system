-- ============================================================
-- 迁移脚本 v1.8：系统配置表（sys_config），用于管理端选择 AI 模型
-- 执行：mysql -u root -p --default-character-set=utf8mb4 < migration-v1.8-ai-settings.sql
-- 说明：
--   1) 应用启动时会自动 CREATE TABLE IF NOT EXISTS，本脚本用于手工部署；
--   2) 可重复执行；
--   3) 初始不写入任何行：ai.model 为空时使用 application.yml 的 ai.model。
-- ============================================================
USE ai_resume;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS sys_config (
  config_key   VARCHAR(64)  NOT NULL,
  config_value VARCHAR(500) NOT NULL,
  updated_at   DATETIME     NOT NULL,
  PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 校验：应返回 1
SELECT COUNT(*) AS sys_config_table_exists
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'sys_config';
