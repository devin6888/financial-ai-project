-- 阶段 21：文档归属与授权口径调整
--
-- 三件事，全是为了一条更简单的规则：
--   可读 = 我是这份文档的属主 OR 有一条「文档级」授权给到我（或我的某个角色）
--
-- 1) 每份文档必须有属主（上传者）。旧模型靠 document.visibility 区分「共享/私有」，
--    新模型里归属只有一条事实：owner_user_id。visibility 列删除。
-- 2) resource_grant 只存「文档级」授权。界面上仍然可以「按整家上市公司授权」，
--    但那只是请求侧的便捷写法 —— 落库前展开成 N 条文档级授权（见 AdminController）。
--    这样任何一条「能看」都能对应到一行可删的数据，撤销精确、解释清楚。
-- 3) 补唯一索引 + 幂等：旧 createGrant 没有唯一约束，同一 (主体, 资源) 可以重复插。

-- ---------- 1) 属主回填 ----------
-- 存量文档由 V11 已经回填成 demo；这里再兜一层，避免任何历史数据漏网。
UPDATE document d
   SET owner_user_id = (SELECT min(u.id) FROM app_user u
                         WHERE u.tenant_id = d.tenant_id AND u.status = 'ACTIVE')
 WHERE d.owner_user_id IS NULL;

-- 回填不出来的（租户里一个 ACTIVE 用户都没有）必须让迁移**显式失败**：
-- 悄悄留 NULL 会让后面的 NOT NULL 约束报一个看不出原因的错误。
DO $$
DECLARE
    missing bigint;
BEGIN
    SELECT count(*) INTO missing FROM document WHERE owner_user_id IS NULL;
    IF missing > 0 THEN
        RAISE EXCEPTION '有 % 份文档找不到属主：所属租户没有 ACTIVE 用户，请先建用户或手工指定 owner_user_id', missing;
    END IF;
END $$;

ALTER TABLE document ALTER COLUMN owner_user_id SET NOT NULL;
COMMENT ON COLUMN document.owner_user_id IS '文档属主（上传者）。默认只有属主可读；其他人读要靠 resource_grant 的文档级授权';

-- ---------- 2) 去重（旧 createGrant 无幂等保护） ----------
DELETE FROM resource_grant a
 USING resource_grant b
 WHERE a.id > b.id
   AND a.tenant_id = b.tenant_id
   AND a.subject_type = b.subject_type
   AND a.subject_id = b.subject_id
   AND a.resource_type = b.resource_type
   AND a.resource_id = b.resource_id;

-- ---------- 3) 存量「公司级」授权展开成文档级 ----------
-- 只对**当前已存在**的文档展开（快照语义）：以后新上传的文档默认私有，要再授权。
INSERT INTO resource_grant (tenant_id, subject_type, subject_id, resource_type, resource_id,
                            permission, granted_by, expires_at)
SELECT g.tenant_id, g.subject_type, g.subject_id, 'DOCUMENT', d.id,
       g.permission, g.granted_by, g.expires_at
  FROM resource_grant g
  JOIN document d ON d.tenant_id = g.tenant_id AND d.company_id = g.resource_id
 WHERE g.resource_type = 'COMPANY'
   AND NOT EXISTS (
        SELECT 1 FROM resource_grant x
         WHERE x.tenant_id = g.tenant_id
           AND x.subject_type = g.subject_type
           AND x.subject_id = g.subject_id
           AND x.resource_type = 'DOCUMENT'
           AND x.resource_id = d.id);

-- ---------- 4) 不再允许落库「公司级」行 ----------
DELETE FROM resource_grant WHERE resource_type = 'COMPANY';

ALTER TABLE resource_grant DROP CONSTRAINT IF EXISTS chk_grant_resource;
ALTER TABLE resource_grant ADD CONSTRAINT chk_grant_resource
    CHECK (resource_type IN ('DOCUMENT', 'SECTION'));
COMMENT ON CONSTRAINT chk_grant_resource ON resource_grant IS
    '只允许文档级（DOCUMENT）与章节级（SECTION，预留）。「整家上市公司」是请求侧写法，落库前已展开';

-- ---------- 5) 幂等约束 ----------
CREATE UNIQUE INDEX IF NOT EXISTS uq_grant_subject_resource
    ON resource_grant (tenant_id, subject_type, subject_id, resource_type, resource_id);

-- ---------- 6) 删除 visibility ----------
ALTER TABLE document DROP CONSTRAINT IF EXISTS chk_document_visibility;
ALTER TABLE document DROP COLUMN IF EXISTS visibility;
