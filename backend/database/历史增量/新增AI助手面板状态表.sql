-- =====================================================================
--  AI 助手面板状态表 —— 对已有库执行即可，可重复执行，不删库不清数据
--
--  做什么：建 ai_panel_state 表，存「AI 助手侧边栏」每人每页的最后一次问答。
--
--  为什么单开一张表，而不复用 field_draft：
--        field_draft 的 content 是 VARCHAR(200)，语义也是「一句话理由没写完」，
--        装不下一问一答（问答正文 + 依据列表），硬塞会被截断。
--        这里要存的是「某个人在某个页面上，助手当时停在哪个问答上」，
--        形状是 (user_id, page_key) → 一份内容，跟单据无关，也不参与任何统计。
--
--  生命周期：退出助手时写一条（同键覆盖），下次进同一页面原样恢复；
--        内容清空按删除处理。重新登录仍在该行上，所以能跨登录保留。
--
--  执行方式（任选其一）：
--        mysql -uroot -p<DB_PASSWORD> travel_server < 新增AI助手面板状态表.sql
--        或 source 本文件
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS travel_server
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;
USE travel_server;

-- ---------------------------------------------------------------------
-- AI 助手面板状态：一人一页一行，退出助手即覆盖
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_panel_state (
  id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  page_key   VARCHAR(40) NOT NULL COMMENT '页面键，与前端路由名逐字一致：empApply / empList / empInvoice / empExpense',
  user_id    BIGINT      NOT NULL COMMENT '归属人，指向 sys_user.id；只认本人这份，避免 A 看到 B 的问答',
  content    TEXT        NULL     COMMENT '面板当前内容（JSON：最近一次问答与依据）；留空即删行',
  updated_at DATETIME    NULL     COMMENT '最后一次保存时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_page_user (page_key, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI助手面板状态：每人每页一份，退出后暂存，下次进来原样恢复';

SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------
-- 自检：应看到 ai_panel_state 一行
-- ---------------------------------------------------------------------
SELECT table_name, table_comment
FROM information_schema.tables
WHERE table_schema = 'travel_server' AND table_name = 'ai_panel_state';
