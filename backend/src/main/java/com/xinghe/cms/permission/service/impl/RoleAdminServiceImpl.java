package com.xinghe.cms.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.permission.entity.SysRole;
import com.xinghe.cms.permission.mapper.SysRoleMapper;
import com.xinghe.cms.permission.request.RoleCreateRequest;
import com.xinghe.cms.permission.request.RoleStatusRequest;
import com.xinghe.cms.permission.request.RoleUpdateRequest;
import com.xinghe.cms.permission.response.RoleView;
import com.xinghe.cms.permission.service.RoleAdminService;
import com.xinghe.cms.permission.support.ContractWhitespace;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色管理写服务实现。对齐 OpenAPI {@code POST /api/admin/roles}、
 * {@code PUT /api/admin/roles/{id}}、{@code PUT /api/admin/roles/{id}/status}。
 *
 * <p>唯一性由数据库 {@code uk_role_code} 约束保证：直接插入并捕获
 * {@link DuplicateKeyException} 转 {@code BusinessException(CONFLICT)}
 * （协作约定：各写 Service 将已知唯一键冲突转为安全 409，不落入 500 兜底）。
 * 并发创建同一编码时同样由唯一键兜底，无需先查后插的竞态窗口。</p>
 *
 * <p>字段规范化（协作约定 8.1，与查询接口一致）：先校验原始长度，再去除
 * 首尾空白并检查非空，写库使用规范化后的值——保证唯一性按去空白后的编码
 * 判断（库存量 {@code EDITOR} 时，提交 {@code " EDITOR "} 返回 409 而非
 * 创建出第二条记录）。去首尾空白与非空校验采用同一套契约空白规则，统一委托
 * {@link ContractWhitespace}（与 HTTP DTO 必填校验共用同一真源，评审 P2）：
 * 仅含 NBSP 等字符的输入裁剪后为空串返回 400，名称内部空格保留；
 * U+001C–U+001F 等控制符按契约（ECMAScript {@code \s}）是非空白，
 * 首尾出现也原样保留不裁剪。</p>
 *
 * <p>修改与启停遵循契约：修改只改 roleName（编码创建后不可改），启停只改
 * status（不删除 user-role / role-permission 历史关联）；重复设置同一状态
 * 幂等返回 200。时间字段由数据库维护（{@code ON UPDATE}），写后重新查询取回。</p>
 *
 * <p>并发列隔离（评审 P1）：两个写入口各自构造只含自己负责列的最小更新实体，
 * 不复用 {@code selectById} 读出的实体整行回写，避免并发场景下把读到的旧值
 * 写回互相覆盖（改名"复活"已停用角色、启停覆盖并发改名）。启停进一步用
 * 条件更新 {@code WHERE status<>?}（评审 P2）：并发同目标启停请求中后到者
 * 条件不匹配、影响行数为 0，跳过重复写入仍按幂等返回 200。</p>
 *
 * <p>审计（评审 P1/P2）：{@link com.xinghe.cms.log.aspect.AuditLogAspect} 要求
 * {@code @AuditLog} 标在事务边界之内（标在 Controller 上时事务尚未开启，success
 * 日志会在业务提交后才写、写入失败也只记错误不回滚业务）。创建与修改必然产生
 * 状态变更，注解直接标注在 {@code create}/{@code update} 方法上：切面在事务内
 * 进入，success 日志经 {@code beforeCommit} 与业务一起提交，写入失败则业务事务
 * 整体回滚；失败审计由切面在回滚后用独立事务补记 FAILURE。启停存在两条无状态
 * 变更的幂等路径（重复同状态、并发条件更新未命中，均按契约 200 且不写库），
 * 为避免"审计写入失败拖垮本应 200 的幂等请求"，注解标在
 * {@link RoleWriteAuditMarker#roleStatusChanged} 上、仅在条件更新命中（状态确实
 * 变更）后调用。操作编码取 OpenAPI {@code x-audit-operation}。</p>
 */
@Service
public class RoleAdminServiceImpl implements RoleAdminService {

    /** 角色编码上限（OpenAPI RoleCreate.roleCode.maxLength，按 Unicode 码点计）。 */
    private static final int MAX_ROLE_CODE_LENGTH = 64;

    /** 角色名称上限（OpenAPI RoleCreate.roleName.maxLength，按 Unicode 码点计）。 */
    private static final int MAX_ROLE_NAME_LENGTH = 100;

    private final SysRoleMapper sysRoleMapper;

    /** 启停审计锚点（评审 P2）：仅在状态实际变更时经代理调用以记录审计。 */
    private final RoleWriteAuditMarker roleWriteAuditMarker;

    public RoleAdminServiceImpl(SysRoleMapper sysRoleMapper, RoleWriteAuditMarker roleWriteAuditMarker) {
        this.sysRoleMapper = sysRoleMapper;
        this.roleWriteAuditMarker = roleWriteAuditMarker;
    }

    @Override
    @Transactional
    @AuditLog(operation = "role.create")
    public RoleView create(RoleCreateRequest request) {
        // 协作约定 8.1：先校验原始长度，再去空白。OpenAPI maxLength 语义为字符数，
        // 按 Unicode 码点统计，避免 String.length() 把增补字符（emoji）按两个代码单元重复计数
        if (request.roleCode().codePointCount(0, request.roleCode().length()) > MAX_ROLE_CODE_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色编码最长 64 个字符");
        }
        if (request.roleName().codePointCount(0, request.roleName().length()) > MAX_ROLE_NAME_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色名称最长 100 个字符");
        }

        // 再去除首尾空白并检查非空：写库用规范化后的值，roleCode 唯一性
        // 按去空白后的编码判断（" EDITOR " 与已有 EDITOR 撞唯一索引 → 409）。
        // 裁剪与判空使用同一套契约空白规则（评审 P2）：OpenAPI pattern \S 按
        // ECMAScript 空白定义，NBSP（U+00A0）等字符 Java 的 strip() 不认，
        // "只裁普通空白、判空用契约规则"会让 "\u00A0编辑\u00A0" 带着首尾 NBSP
        // 落库——必须用 ContractWhitespace.strip 裁剪（协作约定 8.1 的
        // "去首尾空白"），仅含这类字符的输入裁剪后为空串 → 400。
        // ContractWhitespace 与 HTTP DTO 的 @ContractNotBlank 共用同一真源。
        String roleCode = ContractWhitespace.strip(request.roleCode());
        String roleName = ContractWhitespace.strip(request.roleName());
        if (roleCode.isEmpty() || roleName.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色编码与名称不能为空白");
        }

        SysRole role = new SysRole();
        role.setRoleCode(roleCode);
        role.setRoleName(roleName);
        role.setStatus(1);
        try {
            sysRoleMapper.insert(role);
        } catch (DuplicateKeyException e) {
            // 并发下同一 roleCode 的重复插入也由 uk_role_code 兜底，不泄露约束细节
            throw new BusinessException(ResultCode.CONFLICT, "角色编码已存在");
        }

        // createdAt/updatedAt 由数据库默认值维护（实体侧 NEVER 策略），
        // 插入后重新查询取回真实时间，不向客户端返回伪造时间
        SysRole saved = sysRoleMapper.selectById(role.getId());
        if (saved == null) {
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "角色创建结果读取失败");
        }
        return RoleView.from(saved);
    }

    @Override
    @Transactional
    @AuditLog(operation = "role.update")
    public RoleView update(Long id, RoleUpdateRequest request) {
        // 协作约定 8.1：先校验原始长度，再去空白（码点计数与创建一致）
        if (request.roleName().codePointCount(0, request.roleName().length()) > MAX_ROLE_NAME_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色名称最长 100 个字符");
        }
        String roleName = ContractWhitespace.strip(request.roleName());
        if (roleName.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色名称不能为空白");
        }

        SysRole role = sysRoleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }

        // 评审 P1：只写本入口负责的列。不复用读出的实体整行回写——那会把并发读到的
        // 旧 status 一并 UPDATE 回去（改名读到 status=1 后另一请求先停用，改名就把
        // 停用前的旧状态写回，已停用角色被"复活"、鉴权再次放行其权限）。构造只含
        // id + roleName 的最小更新实体，MyBatis-Plus updateById 默认跳过 null 字段，
        // SET 子句仅 role_name，与启停入口互不覆盖。
        SysRole change = new SysRole();
        change.setId(role.getId());
        change.setRoleName(roleName);
        sysRoleMapper.updateById(change);

        // updatedAt 由数据库 ON UPDATE 维护，重新查询取回真实时间
        SysRole saved = sysRoleMapper.selectById(id);
        if (saved == null) {
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "角色修改结果读取失败");
        }
        return RoleView.from(saved);
    }

    @Override
    @Transactional
    public void setStatus(Long id, RoleStatusRequest request) {
        Integer status = request.status();
        // OpenAPI Status enum [0,1]；类型已由 StrictIntDeserializer 拦截，此处校验取值
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态取值仅支持 0（停用）或 1（启用）");
        }

        SysRole role = sysRoleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }

        // 契约：重复设置同一状态返回 200，幂等不重复写库。
        // 评审 P2：本路径无状态变更，直接返回，不进入审计标记方法（见下方审计说明）
        if (status.equals(role.getStatus())) {
            return;
        }

        // 只改 status（契约明文：不删除 user-role / role-permission 历史关联）。
        // 评审 P1：实体只含 status，SET 子句仅本入口负责的列，不回写并发读到的
        // 旧 roleCode/roleName。
        // 评审 P2：条件更新 UPDATE ... WHERE id=? AND status<>? —— 并发的同目标
        // 启停请求（两个都先读到 status=1，第一个已提交 status=0）中，后到者不再
        // 执行无条件重复写入：WHERE 条件不匹配影响行数为 0，按幂等返回 200
        // （契约"重复同状态 200"）；角色不存在仍由前面的 selectById 判 404。
        // 本迭代无删除角色入口，select 与 update 之间行消失的窗口不存在。
        SysRole change = new SysRole();
        change.setStatus(status);
        int updated = sysRoleMapper.update(change,
                new UpdateWrapper<SysRole>().eq("id", role.getId()).ne("status", status));

        // 评审 P2：影响 0 行 = 并发请求已把状态改到目标值，同样无状态变更，
        // 按幂等返回 200 且不产生审计记录
        if (updated == 0) {
            return;
        }

        // 仅状态实际变更时进入审计锚点（评审 P2）：切面经代理在事务内注册同步器，
        // beforeCommit 随本事务写 role.status / SUCCESS；审计写入失败则事务回滚
        // （此时状态确实变了，回滚 + 报错符合"业务生效但审计缺失"的禁止约束）。
        // 两条无状态变更路径在上面已提前返回，不会产生任何审计记录。
        roleWriteAuditMarker.roleStatusChanged();
    }
}
