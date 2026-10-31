-- 阶段 18 ST18 需求 11：管理端要能建用户、分配角色、增删文档授权。
--
-- 背景：V9 把身份五表（tenant / app_user / role / user_role / resource_grant）对运行时角色
-- finagent_app 收成了**只读**，理由是「那五张表没有 RLS，一旦 SQL 注入就能读走全租户的
-- api_token_hash」。那个理由今天仍然成立，所以这次**不是**把五张表整体开成读写，而是
-- 只开管理端点真正需要的四种写权限：
--
--   app_user       INSERT, UPDATE   —— 建账号、改显示名/启停；**不给 DELETE**（删用户会打断
--                                     会话、文档 owner、授权的外键链，审计上也不该物理删）
--   user_role      INSERT, DELETE   —— 换角色 = 先删后插
--   resource_grant INSERT, DELETE   —— 授予 / 撤销文档级授权
--
-- 仍然不开：role / tenant 的任何写权限（角色是系统内置的，租户更不该由 API 改），
-- app_user 的 DELETE，以及任何 UPDATE 到 api_token_hash / password_hash 的专用通道
-- （改密码要走用户自己的找回流程，不是管理员替他设）。

GRANT INSERT, UPDATE ON app_user TO finagent_app;
GRANT INSERT, DELETE ON user_role TO finagent_app;
GRANT INSERT, DELETE ON resource_grant TO finagent_app;

-- 自增主键：没有 sequence 权限，INSERT 会在 nextval 上失败（V8 的 ALL SEQUENCES 早于这两张表之后没再补）
GRANT USAGE, SELECT ON SEQUENCE app_user_id_seq TO finagent_app;
GRANT USAGE, SELECT ON SEQUENCE resource_grant_id_seq TO finagent_app;

COMMENT ON TABLE app_user IS '用户主体；api_token_hash 是 SHA-256(token)，不存明文。运行时角色只有 SELECT + INSERT/UPDATE（V14 起，管理端点用）';
