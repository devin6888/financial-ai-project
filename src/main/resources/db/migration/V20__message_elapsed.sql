-- 历史消息的「用时」：原来只有前端自己按墙钟算，刷新一次就没了。
-- 服务端在落库时把 ChatTrace.totalMs()（QU + 检索 + 上下文 + 生成）一起存下来，
-- 历史回放时前端可以直接用；旧行为 NULL，前端按「没有就不显示」处理。
ALTER TABLE conversation_message ADD COLUMN IF NOT EXISTS elapsed_ms integer;

COMMENT ON COLUMN conversation_message.elapsed_ms IS '本轮服务端总耗时（毫秒）= ChatTrace.totalMs()；历史消息靠它显示「用时」';
