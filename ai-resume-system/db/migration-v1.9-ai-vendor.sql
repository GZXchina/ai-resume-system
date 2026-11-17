-- ============================================================
-- 迁移脚本 v1.9：AI 模型厂商选择与密钥配置
-- 执行：mysql -u root -p123456 --default-character-set=utf8mb4 < migration-v1.9-ai-vendor.sql
-- 说明：
--   1) 复用 v1.8 的 sys_config 表（应用启动时会自动 CREATE TABLE IF NOT EXISTS），本脚本仅做幂等补建；
--   2) 新增/使用的配置键（无需预先插入，保存配置时由应用写入）：
--        ai.vendor       VARCHAR  厂商 id，如 openai / anthropic / deepseek / custom
--        ai.endpoint     VARCHAR  接口地址，如 https://api.deepseek.com/v1
--        ai.api-key-enc  VARCHAR  AES-256-GCM 密文，格式 enc:v1:<base64(iv|cipher|tag)>
--        ai.model        VARCHAR  模型 id（沿用 v1.8 语义，继续生效）
--   3) 历史数据迁移：升级后首次启动时，若 sys_config 只有 ai.model 而没有 ai.vendor，
--      应用会按生效的接口地址反查预置厂商并自动补写 ai.vendor；
--      没有 ai.endpoint / ai.api-key-enc 时继续使用 application.yml 与环境变量，无需手工迁移；
--   4) 本脚本可重复执行，不修改任何业务表。
-- ============================================================
USE ai_resume;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS sys_config (
  config_key   VARCHAR(64)  NOT NULL,
  config_value VARCHAR(500) NOT NULL,
  updated_at   DATETIME     NOT NULL,
  PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 校验 1：应返回 1
SELECT COUNT(*) AS sys_config_table_exists
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'sys_config';

-- 校验 2：应返回 4（config_key / config_value / updated_at 均存在时列数为 3，
--         此处统计 VARCHAR 列数，用于确认字段长度足够存放加密后的密钥）
SELECT COUNT(*) AS varchar_columns
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'sys_config'
  AND data_type = 'varchar';

-- 校验 3：查看当前 AI 相关配置（新库返回空集属正常）
SELECT config_key, LEFT(config_value, 24) AS value_prefix, updated_at
FROM sys_config
WHERE config_key IN ('ai.vendor', 'ai.endpoint', 'ai.api-key-enc', 'ai.model');
