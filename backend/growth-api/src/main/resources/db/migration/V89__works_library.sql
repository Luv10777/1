-- V89 · 作品库文件夹
--
-- 生成完的图片和视频不再自动进作品库，用户点「保存到作品库」才登记一条 library_works，可选放进某个文件夹。
-- 作品库按人分：每个账号只看到自己保存的作品和自己建的文件夹。文件夹只有一层。
-- library_works 只记「哪件作品在谁的作品库、放在哪个文件夹」，作品本身仍是 image_items / video_workflows。
-- 移出作品库或删除文件夹只删这里的记录，不动生成记录和文件。
CREATE TABLE work_folders (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    owner_id BIGINT NOT NULL REFERENCES users(id),
    name VARCHAR(30) NOT NULL CHECK (length(btrim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_work_folder_name UNIQUE (tenant_id, owner_id, name)
);
CREATE TABLE library_works (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    owner_id BIGINT NOT NULL REFERENCES users(id),
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('IMAGE', 'VIDEO')),
    -- IMAGE 指 image_items.id，VIDEO 指 video_workflows.id
    source_id BIGINT NOT NULL,
    folder_id BIGINT REFERENCES work_folders(id),
    saved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_library_work_source UNIQUE (tenant_id, kind, source_id)
);
CREATE INDEX idx_library_works_owner ON library_works (tenant_id, owner_id, saved_at DESC, id DESC);
CREATE INDEX idx_library_works_folder ON library_works (folder_id);

ALTER TABLE work_folders ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_folders FORCE ROW LEVEL SECURITY;
CREATE POLICY work_folder_tenant_isolation ON work_folders
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
ALTER TABLE library_works ENABLE ROW LEVEL SECURITY;
ALTER TABLE library_works FORCE ROW LEVEL SECURITY;
CREATE POLICY library_work_tenant_isolation ON library_works
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
GRANT SELECT, INSERT, UPDATE, DELETE ON work_folders, library_works TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE work_folders_id_seq, library_works_id_seq TO growth_app;

-- 上线前已经生成的作品原来都自动出现在作品库里，这里全部登记到各自创建人的根目录，打开作品库看到的和以前一样。
-- 范围与旧的作品列表一致：创建人已知、已完成且有成片文件。逐租户设置上下文，不关闭 FORCE RLS。
DO $$
DECLARE
    tenant_record RECORD;
    previous_tenant TEXT := current_setting('app.tenant_id', true);
BEGIN
    FOR tenant_record IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
        INSERT INTO library_works (tenant_id, owner_id, kind, source_id, saved_at)
        SELECT i.tenant_id, c.created_by, 'IMAGE', i.id, i.created_at
          FROM image_items i JOIN image_creations c ON c.id = i.creation_id
         WHERE i.tenant_id = tenant_record.id AND c.created_by IS NOT NULL
           AND i.status = 'SUCCEEDED' AND i.output_key IS NOT NULL;
        INSERT INTO library_works (tenant_id, owner_id, kind, source_id, saved_at)
        SELECT w.tenant_id, w.created_by, 'VIDEO', w.id, w.created_at
          FROM video_workflows w
         WHERE w.tenant_id = tenant_record.id AND w.created_by IS NOT NULL
           AND w.status = 'SUCCEEDED' AND w.output_storage_key IS NOT NULL;
    END LOOP;
    PERFORM set_config('app.tenant_id', COALESCE(previous_tenant, ''), true);
END
$$;
