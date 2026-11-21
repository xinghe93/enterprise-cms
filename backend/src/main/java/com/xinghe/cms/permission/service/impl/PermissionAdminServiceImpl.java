package com.xinghe.cms.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.permission.entity.SysPermission;
import com.xinghe.cms.permission.mapper.SysPermissionMapper;
import com.xinghe.cms.permission.request.PermissionCreateRequest;
import com.xinghe.cms.permission.request.PermissionStatusRequest;
import com.xinghe.cms.permission.request.PermissionUpdateRequest;
import com.xinghe.cms.permission.response.PermissionView;
import com.xinghe.cms.permission.service.PermissionAdminService;
import com.xinghe.cms.permission.support.ContractWhitespace;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权限项管理写服务实现。对齐 OpenAPI {@code POST /api/admin/permissions}、
 * {@code PUT /api/admin/permissions/{id}}、{@code PUT /api/admin/permissions/{id}/status}。
 *
 * <p>字段规范化（协作约定 8.1，与角色写服务一致）：permissionCode/permissionName
 * 先校验原始长度，再经 {@link ContractWhitespace#strip} 去首尾空白并检查非空，
 * 写库使用规范化后的值——唯一性按去空白后的编码判断（库存量 {@code article:read}
 * 时提交 {@code " article:read "} 返回 409）。path/method 不属于"普通名称/编码"，
 * 不做 trim；其长度按原始值以 Unicode 码点校验。唯一性由数据库
 * {@code uk_permission_code} 约束保证：直接插入并捕获 {@link DuplicateKeyException}
 * 转 {@code BusinessException(CONFLICT)}，并发创建同一编码同样由唯一键兜底。</p>
 *
 * <p>类型条件不变量（协作约定 8.1 第 247 行 / OpenAPI oneOf）：API 的 path 必须
 * 匹配 {@code ^/api/(auth|admin|portal)(/[^\s?#]*)?$}——结构用正则校验，其中
 * "无空白"按契约 ECMAScript {@code \s} 语义用 {@link ContractWhitespace#isWhitespace}
 * 逐字符复核（Java 正则默认 {@code \s} 只认 ASCII，会把 NBSP 等放行）；method
 * 必须是 7 个大写方法之一。MENU/BUTTON 的 method 必须 null。修改时按<b>数据库
 * 已有 permissionType</b> 校验，请求体不含编码与类型（未知字段在绑定阶段 400）。</p>
 *
 * <p>并发列隔离（评审 P1 惯例）：修改只写本入口负责的列（permission_name/path/
 * method），通过 {@link UpdateWrapper} 显式 {@code set} 全部三列——path/method
 * 对 MENU/BUTTON 是合法的 null，必须能写 NULL，不能依赖 {@code updateById}
 * 跳过 null 字段的默认行为。启停在排他锁下读状态（见下），随后的更新只含
 * status 列。</p>
 *
 * <p>并发与锁（评审 P2 结论的适用性分析）：本服务的写事务锁定
 * <b>sys_permission 行</b>（X），审计外键对 sys_user 操作人行加共享锁（S）。
 * 与角色编辑（先角色 X、后操作人 S）并发时操作人锁为 S/S 兼容，不存在锁环；
 * 与 A 模块用户角色分配（先用户 X、后角色 S）无共同资源——因此<b>无需</b>
 * 像"用户 → 角色"锁序那样预锁操作人行。启停在事务开头
 * {@code selectByIdForUpdate} 排他锁定目标权限项行：锁下读到的 status 即为
 * 最新值，"重复同状态"幂等判断没有并发窗口，随后的无条件 UPDATE 必然命中
 * 1 行（角色启停的"条件更新 WHERE status&lt;&gt;?"在此不再需要——那是为了
 * 无锁读下的并发窗口，本实现已用行锁消除窗口）。</p>
 *
 * <p>审计（评审 P1/P2 惯例）：创建与修改必然产生状态变更，{@code @AuditLog}
 * 直接标注在服务方法上——整个方法体在受审计代理内执行，切面在写库前注册
 * 事务同步器，success 随业务提交、写入失败整体回滚并补 FAILURE；启停的幂等
 * 无变更路径必须跳过审计，故状态更新以 {@link Runnable} 传入
 * {@link PermissionWriteAuditMarker#permissionStatusChanged(Runnable)}，切面
 * 进入（注册回调）先于写库，UPDATE 抛错回滚后仍补记 FAILURE。操作编码取
 * OpenAPI {@code x-audit-operation}。</p>
 */
@Service
public class PermissionAdminServiceImpl implements PermissionAdminService {

    /** 权限编码上限（OpenAPI permissionCode.maxLength，按 Unicode 码点计）。 */
    private static final int MAX_CODE_LENGTH = 100;

    /** 权限名称上限（OpenAPI permissionName.maxLength，按 Unicode 码点计）。 */
    private static final int MAX_NAME_LENGTH = 100;

    /** 路径上限（OpenAPI path.maxLength，按 Unicode 码点计）。 */
    private static final int MAX_PATH_LENGTH = 255;

    /** HTTP 方法上限（OpenAPI method.maxLength，按 Unicode 码点计）。 */
    private static final int MAX_METHOD_LENGTH = 16;

    /** 合法的 HTTP 方法枚举（OpenAPI method.enum，大写严格匹配）。 */
    static final Set<String> HTTP_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    /** API 路径结构：三个 API 前缀之一，可带任意后缀路径段（先行结构校验）。 */
    private static final Pattern API_PATH_STRUCTURE =
            Pattern.compile("^/api/(auth|admin|portal)(/.*)?$");

    private final SysPermissionMapper sysPermissionMapper;

    /** 启停审计代理（评审 P2）：状态更新以 Runnable 传入、在代理内执行。 */
    private final PermissionWriteAuditMarker permissionWriteAuditMarker;

    public PermissionAdminServiceImpl(SysPermissionMapper sysPermissionMapper,
            PermissionWriteAuditMarker permissionWriteAuditMarker) {
        this.sysPermissionMapper = sysPermissionMapper;
        this.permissionWriteAuditMarker = permissionWriteAuditMarker;
    }

    @Override
    @Transactional
    @AuditLog(operation = "permission.create")
    public PermissionView create(PermissionCreateRequest request) {
        String permissionCode = normalizeCode(request.permissionCode());
        String permissionName = normalizeName(request.permissionName());
        String permissionType = requireValidType(request.permissionType());
        String path = validateOptionalPath(request.path());
        String method = validateOptionalMethod(request.method(), permissionType);
        // 类型条件不变量（创建与修改同一规则，协作约定 8.1 第 247 行）：
        // API 的 path 必须有效、method 非空；MENU/BUTTON 的 method 必须 null
        validateTypeInvariant(permissionType, path, method);

        SysPermission permission = new SysPermission();
        permission.setPermissionCode(permissionCode);
        permission.setPermissionName(permissionName);
        permission.setPermissionType(permissionType);
        permission.setPath(path);
        permission.setMethod(method);
        permission.setStatus(1);
        try {
            sysPermissionMapper.insert(permission);
        } catch (DuplicateKeyException e) {
            // 并发下同一 permissionCode 的重复插入也由 uk_permission_code 兜底，不泄露约束细节
            throw new BusinessException(ResultCode.CONFLICT, "权限编码已存在");
        }

        // createdAt/updatedAt 由数据库默认值维护（实体侧 NEVER 策略），
        // 插入后重新查询取回真实时间，不向客户端返回伪造时间
        SysPermission saved = sysPermissionMapper.selectById(permission.getId());
        if (saved == null) {
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "权限项创建结果读取失败");
        }
        return PermissionView.from(saved);
    }

    @Override
    @Transactional
    @AuditLog(operation = "permission.update")
    public PermissionView update(long id, PermissionUpdateRequest request) {
        String permissionName = normalizeName(request.permissionName());
        String path = validateOptionalPath(request.path());
        String method = validateOptionalMethod(request.method(), null);
        // 请求体的 method 枚举在此已校验（非 null 时必须是大写方法之一）；
        // "MENU/BUTTON 必须 null" 的条件不变量依赖数据库类型，读出实体后校验。

        SysPermission permission = sysPermissionMapper.selectById(id);
        if (permission == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "权限项不存在");
        }

        // 按数据库已有类型执行创建时同一不变量：编码与 permissionType 不可修改
        validateTypeInvariant(permission.getPermissionType(), path, method);

        // 只写本入口负责的列；path/method 用显式 set 以支持写入 NULL
        // （MENU/BUTTON 的 method 为 null 是合法终态，updateById 会跳过 null）
        sysPermissionMapper.update(null, new UpdateWrapper<SysPermission>()
                .eq("id", permission.getId())
                .set("permission_name", permissionName)
                .set("path", path)
                .set("method", method));

        // updatedAt 由数据库 ON UPDATE 维护，重新查询取回真实时间
        SysPermission saved = sysPermissionMapper.selectById(id);
        if (saved == null) {
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "权限项修改结果读取失败");
        }
        return PermissionView.from(saved);
    }

    @Override
    @Transactional
    public void setStatus(long id, PermissionStatusRequest request) {
        Integer status = request.status();
        // OpenAPI Status enum [0,1]；类型已由 StrictIntDeserializer 拦截，此处校验取值
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态取值仅支持 0（停用）或 1（启用）");
        }

        // 排他锁定目标权限项行（评审 P2 惯例）：锁下读到的 status 即最新值，
        // 幂等判断无并发窗口，随后的无条件 UPDATE 必然命中 1 行；
        // 权限项不存在 404（与角色启停一致）。
        SysPermission permission = sysPermissionMapper.selectByIdForUpdate(id);
        if (permission == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "权限项不存在");
        }

        // 契约：重复设置同一状态返回 200，幂等不写库、不产生审计记录
        // （与 role.status 评审 P2 结论一致）。行锁在提交时释放，幂等路径
        // 仅持锁到方法返回，无写操作。
        if (status.equals(permission.getStatus())) {
            return;
        }

        // 实际状态更新包进受审计代理（评审 P2）：切面在进入时注册事务同步器、
        // 再执行写入——UPDATE 抛错时事务回滚，切面用独立新事务补记
        // permission.status / FAILURE，不漏记失败审计。
        permissionWriteAuditMarker.permissionStatusChanged(() -> {
            SysPermission change = new SysPermission();
            change.setStatus(status);
            int updated = sysPermissionMapper.update(change,
                    new UpdateWrapper<SysPermission>().eq("id", permission.getId()));
            if (updated == 0) {
                // 行锁下不可能发生（无删除入口）；防御性兜底，不静默当作幂等
                throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "权限项状态更新未生效");
            }
        });
    }

    // ------------------------------------------------------------------
    // 字段规范化与校验
    // ------------------------------------------------------------------

    /** 编码：先校验原始长度（码点），再去契约空白，裁剪后非空。 */
    private String normalizeCode(String raw) {
        if (raw.codePointCount(0, raw.length()) > MAX_CODE_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "权限编码最长 100 个字符");
        }
        String code = ContractWhitespace.strip(raw);
        if (code.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "权限编码不能为空白");
        }
        return code;
    }

    /** 名称：先校验原始长度（码点），再去契约空白，裁剪后非空。 */
    private String normalizeName(String raw) {
        if (raw.codePointCount(0, raw.length()) > MAX_NAME_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "权限名称最长 100 个字符");
        }
        String name = ContractWhitespace.strip(raw);
        if (name.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "权限名称不能为空白");
        }
        return name;
    }

    /** 类型：严格枚举 MENU/BUTTON/API（大小写敏感，绑定已保证为字符串）。 */
    private String requireValidType(String permissionType) {
        if (permissionType == null || !PermissionQueryServiceImpl.PERMISSION_TYPES.contains(permissionType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "权限类型只允许 MENU、BUTTON 或 API");
        }
        return permissionType;
    }

    /**
     * path：可空；非空时校验原始长度（码点 ≤255）。API 前缀结构在
     * {@link #validateTypeInvariant} 中结合类型校验，此处只做通用长度约束。
     */
    private String validateOptionalPath(String path) {
        if (path == null) {
            return null;
        }
        if (path.codePointCount(0, path.length()) > MAX_PATH_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "路径最长 255 个字符");
        }
        return path;
    }

    /**
     * method：可空；非空时校验原始长度（码点 ≤16）与枚举（大写方法）。
     * {@code expectedType} 非 null 表示创建请求（结合类型执行 MENU/BUTTON
     * method 必须 null 的不变量）；null 表示修改请求（按数据库类型延后校验）。
     */
    private String validateOptionalMethod(String method, String expectedType) {
        if (method == null) {
            return null;
        }
        if (method.codePointCount(0, method.length()) > MAX_METHOD_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "HTTP 方法最长 16 个字符");
        }
        if (!HTTP_METHODS.contains(method)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "HTTP 方法必须是 GET、POST、PUT、PATCH、DELETE、HEAD、OPTIONS 之一");
        }
        if (expectedType != null && !"API".equals(expectedType)) {
            // OpenAPI oneOf：MENU/BUTTON 的 method 必须 null
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "MENU/BUTTON 类型权限项的 method 必须为空");
        }
        return method;
    }

    /**
     * 按数据库已有类型执行条件不变量（协作约定 8.1 第 247 行）：API 的 path
     * 必须为 {@code /api/auth|admin|portal} 下不含查询串/片段/空白的路径
     * （可含 {@code id} 模板）且 method 非空；MENU/BUTTON 的 method 必须 null。
     */
    private void validateTypeInvariant(String permissionType, String path, String method) {
        if ("API".equals(permissionType)) {
            if (path == null || !isValidApiPath(path)) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "API 类型权限项的路径必须在 /api/auth、/api/admin 或 /api/portal 下，"
                                + "且不含查询串、片段或空白");
            }
            if (method == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "API 类型权限项的 method 不能为空");
            }
        } else if (!"MENU".equals(permissionType) && !"BUTTON".equals(permissionType)) {
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "未知的权限类型");
        }
        // MENU/BUTTON：method 必须 null（修改请求在 validateOptionalMethod(x, null)
        // 中已做枚举校验，这里补类型条件）；path 为菜单路由，契约未约束格式
        if (!"API".equals(permissionType) && method != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "MENU/BUTTON 类型权限项的 method 必须为空");
        }
    }

    /**
     * API 路径校验：结构 {@code ^/api/(auth|admin|portal)(/.*)?$} + 逐字符
     * 复核"无空白、无 ?、无 #"。空白按契约 ECMAScript {@code \s} 语义
     * （{@link ContractWhitespace#isWhitespace}），Java 正则默认 {@code \s}
     * 只认 ASCII 会放行 NBSP 等字符，故不能用单一正则完成。
     */
    static boolean isValidApiPath(String path) {
        if (!API_PATH_STRUCTURE.matcher(path).matches()) {
            return false;
        }
        for (int i = 0; i < path.length(); ) {
            int codePoint = path.codePointAt(i);
            if (ContractWhitespace.isWhitespace(codePoint) || codePoint == '?' || codePoint == '#') {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }
}
