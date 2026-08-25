# ADR-0001：组织树与联邦租户边界

## 状态

已接受（Sandbox 契约，生产实现待验证）。

## 决策

同一法人且统一管理的连锁在一个 Tenant 内使用通用 `OrganizationNode` 树；独立法人、加盟商或数据权属不同的主体默认独立 Tenant，通过版本化 `FederationAgreement` 和最小 Scope 授权协作。Brand、MerchantLegalEntity、Store 与组织节点分离。

## 原因

这样既支持总部→区域→门店策略继承和汇总，也不会为了报表把独立数据权属强行合并。撤销联邦授权只撤销访问，不删除加盟商的独立业务数据。

## 影响

服务端必须实现 RBAC+ABAC、组织范围查询、联邦 Scope、撤销传播和跨组织审计。当前只有内存函数，不能宣称生产隔离已完成。
