-- 2026-10-30：管理页支持「新建/重命名/删除自定义角色」。
--
-- 背景：V9 把 tenant / app_user / role / user_role / resource_grant 对运行时角色
-- finagent_app 收成了只读；V14 只为「账号与授权」开了四种写权限，role 表仍是只读，
-- 于是管理端点建角色会报 permission denied for table role。
--
-- 这里同样只开必要的权限：
--   * role：INSERT / UPDATE / DELETE —— 不支持改 created_at 之类的系统列，也不放开 tenant 表；
--   * role_id_seq：USAGE / SELECT —— 否则 nextval 会让 INSERT 直接失败。
-- 仍然不给 tenant 表任何写权限，也不给 app_user 的 DELETE。

GRANT INSERT, UPDATE, DELETE ON role TO finagent_app;
GRANT USAGE, SELECT ON SEQUENCE role_id_seq TO finagent_app;
