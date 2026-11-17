-- ============================================================
-- 迁移脚本 v1.7：登录凭证持久化（auth_token）
-- 适用：已按 v1.6 初始化的 ai_resume 库，无需重建
-- 执行：mysql -u root -p123456 --default-character-set=utf8mb4 < migration-v1.7-auth-token.sql
-- 说明：
--   1) 应用启动时会自动执行 CREATE TABLE IF NOT EXISTS，本脚本用于手工部署或提前建表；
--   2) 本脚本可重复执行（IF NOT EXISTS + 索引已存在时忽略报错）；
--   3) 外键指向 sys_user(id)，删除用户自动级联清理凭证。
-- ============================================================
USE ai_resume;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS auth_token (
  token        VARCHAR(64) NOT NULL,
  user_id      BIGINT      NOT NULL,
  created_at   DATETIME    NOT NULL,
  expires_at   DATETIME    NOT NULL,
  last_seen_at DATETIME    NOT NULL,
  PRIMARY KEY (token),
  KEY idx_token_user (user_id),
  KEY idx_token_expires (expires_at),
  CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 校验：应返回 1
SELECT COUNT(*) AS auth_token_table_exists
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'auth_token';
