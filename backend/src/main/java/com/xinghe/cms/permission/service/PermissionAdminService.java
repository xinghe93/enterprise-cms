package com.xinghe.cms.permission.service;

import com.xinghe.cms.permission.request.PermissionCreateRequest;
import com.xinghe.cms.permission.request.PermissionStatusRequest;
import com.xinghe.cms.permission.request.PermissionUpdateRequest;
import com.xinghe.cms.permission.response.PermissionView;

/**
 * 权限项管理写服务（OpenAPI {@code POST /api/admin/permissions}、
 * {@code PUT /api/admin/permissions/{id}}、{@code PUT /api/admin/permissions/{id}/status}）。
 *
 * <p>共同约束（协作约定 8.1 / OpenAPI 描述）：</p>
 * <ul>
 *   <li>编码创建后不可改，唯一键冲突转 409（不落入 500 兜底）；</li>
 *   <li>创建默认 status=1；修改为完整 PUT，name/path/method 都必填，
 *       编码与 permissionType 不修改，按<b>数据库已有类型</b>执行条件不变量：
 *       API 的 path 为 {@code /api/auth|admin|portal} 下不含查询串/片段/空白
 *       的路径（可含 {@code id} 模板）且 method 为声明的大写方法，MENU/BUTTON
 *       的 method 必须 null；</li>
 *   <li>启停只改状态、保留角色关联；重复同状态幂等 200 且不写库不记审计；
 *       提交后有效权限立即按新状态计算——首期无跨请求权限缓存
 *       （《跨模块服务接口 V1.0》第 51 行每请求实时计算），无对象可失效；</li>
 *   <li>审计操作编码取 OpenAPI {@code x-audit-operation}：
 *       {@code permission.create} / {@code permission.update} /
 *       {@code permission.status}。创建与修改必然产生状态变更，
 *       {@code @AuditLog} 直接标注在服务方法上（切面在事务内进入、写库前注册
 *       回调，写入失败整体回滚并补 FAILURE）；启停存在幂等无变更路径，审计
 *       挂在 {@code PermissionWriteAuditMarker#permissionStatusChanged(Runnable)}
 *       受审计代理上，实际写入以 Runnable 传入、在代理内部执行。</li>
 * </ul>
 */
public interface PermissionAdminService {

    /** 创建权限项：默认启用，返回数据库生成时间后的完整权限项；重复编码 409。 */
    PermissionView create(PermissionCreateRequest request);

    /** 修改权限项（完整 PUT）：不改编码与类型，返回修改后的完整权限项；不存在 404。 */
    PermissionView update(long id, PermissionUpdateRequest request);

    /** 启停权限项：只改状态、保留角色关联；重复同状态幂等 200；不存在 404。 */
    void setStatus(long id, PermissionStatusRequest request);
}
