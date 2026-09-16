-- 把 V1~V3 建的表授权给应用角色。
-- 新增表后如果忘了 GRANT，启动时 Hibernate validate 就会报错，不会静默出问题。
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO growth_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO growth_app;
