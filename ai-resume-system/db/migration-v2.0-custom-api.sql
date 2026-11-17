-- ============================================================
-- 迁移脚本 v2.0：学生端自定义 API 配置
-- 执行：mysql -u root -p123456 --default-character-set=utf8mb4 < migration-v2.0-custom-api.sql
-- 说明：
--   1) 新建 user_api_config 表，保存学生配置的自定义 API（请求头/参数/请求体/分组/标签）；
--   2) 应用启动时也会自动 CREATE TABLE IF NOT EXISTS，本脚本用于显式初始化与检查；
--   3) 脚本幂等，可重复执行，不修改任何既有表与数据。
-- ============================================================
USE ai_resume;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS user_api_config (
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

-- 校验 1：应返回 1
SELECT COUNT(*) AS user_api_config_exists
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'user_api_config';

-- 校验 2：应返回 15（字段数）
SELECT COUNT(*) AS column_count
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'user_api_config';

-- 校验 3：查看当前配置数量（新库为 0）
SELECT COUNT(*) AS total_configs FROM user_api_config;
