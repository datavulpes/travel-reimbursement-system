-- 出差类型选「其他」时的补充说明
--
-- 背景：出差类型六个取值里，前五个审批人一看就知道这趟是干什么的，
-- 唯独「其他」两个字本身不携带任何信息。以前选了「其他」就没有下文，
-- 审批人只看到「其他」，费效分析里这一组也只能算成「类型不明」。
--
-- 非必填：真有说不清归类的事由（内部借调、临时支援），
-- 逼员工硬写会写出「其他事项」这种等于没写的废话，还多一次提交失败。
--
-- 执行方式：mysql -uroot -p travel_server < 新增出差类型补充说明.sql

ALTER TABLE trip_apply
  ADD COLUMN trip_type_note VARCHAR(200) NULL COMMENT '出差类型为「其他」时的补充说明（非必填）'
  AFTER trip_type;

-- 校验：
--   SHOW COLUMNS FROM trip_apply LIKE 'trip_type_note';
--   SELECT apply_no, trip_type, trip_type_note FROM trip_apply WHERE trip_type = '其他';
