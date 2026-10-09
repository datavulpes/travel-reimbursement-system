-- AI 留痕补三列：失败原因、输入/输出 token
-- 配套：entity/AiLog.java、ai/AiLogService.java、ai/RemoteAiService.java（读 usage）
--
-- 为什么加：没有失败原因时，失败那一行除了 fallback=1 什么都没有，
-- 运行状况页只能说「失败了」，说不出「为什么失败」——而失败原因正是判断要不要改 prompt 的依据。
-- token 只在厂商真的返回 usage 时写，拿不到就留 NULL：估算出来的数字进了看板就是假数据。
--
-- 幂等：三列都存在时直接跳过，可反复执行。

USE travel_server;

SET @db = 'travel_server';

-- error_message
SET @exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ai_log' AND COLUMN_NAME = 'error_message'
);
SET @sql = IF(@exists = 0,
  'ALTER TABLE ai_log ADD COLUMN error_message VARCHAR(500) NULL COMMENT ''失败原因：超时 / HTTP 非 200 / 输出无法解析 / 校验不过被丢弃；成功为 NULL'' AFTER cost_ms',
  'SELECT ''error_message 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- request_tokens
SET @exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ai_log' AND COLUMN_NAME = 'request_tokens'
);
SET @sql = IF(@exists = 0,
  'ALTER TABLE ai_log ADD COLUMN request_tokens INT NULL COMMENT ''输入 token，取自响应 usage；厂商没返回为 NULL，不估算'' AFTER error_message',
  'SELECT ''request_tokens 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- response_tokens
SET @exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ai_log' AND COLUMN_NAME = 'response_tokens'
);
SET @sql = IF(@exists = 0,
  'ALTER TABLE ai_log ADD COLUMN response_tokens INT NULL COMMENT ''输出 token，取自响应 usage；厂商没返回为 NULL，不估算'' AFTER request_tokens',
  'SELECT ''response_tokens 已存在，跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ai_log'
ORDER BY ORDINAL_POSITION;
