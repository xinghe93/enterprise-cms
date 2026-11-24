package com.xinghe.cms.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xinghe.cms.common.contract.CurrentUser;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.log.annotation.AuditLog;
import com.xinghe.cms.permission.entity.SysPermission;
import com.xinghe.cms.permission.mapper.OperatorRowLockMapper;
import com.xinghe.cms.permission.mapper.SysPermissionMapper;
import com.xinghe.cms.permission.request.PermissionCreateRequest;
import com.xinghe.cms.permission.request.PermissionStatusRequest;
import com.xinghe.cms.permission.request.PermissionUpdateRequest;
import com.xinghe.cms.permission.response.PermissionView;
import com.xinghe.cms.permission.service.PermissionAdminService;
import com.xinghe.cms.permission.support.ContractWhitespace;
import com.xinghe.cms.security.CurrentUserProvider;
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
 * <b>事务开头必须先取操作人共享锁</b>（{@link #lockOperatorFirst}）：三个写事务
 * 并发时会形成锁环——T1（本服务修改/启停）持权限行 P 锁、事务末尾写审计时等
 * 操作人行 U 的 S 锁；T2（角色权限替换）持角色 R 锁、等待对 P 行的 S 锁；
 * T3（A 模块用户角色分配）持 U 行 X 锁、等待对 R 行的 S 锁。InnoDB 锁队列中
 * 排在 X 之后等待的 S 请求会被阻塞（防写饥饿），T1 末尾才请求的 U(S) 被 T3
 * 排队阻塞，而 T3 又在等 T2、T2 在等 T1 → P→U→R→P 循环等待，死锁 1213 /
 * HTTP 500。修复：所有写事务<b>在触碰权限行之前</b>先对操作人行取 S 锁
 * （{@link OperatorRowLockMapper#selectOperatorForShare}），统一"用户 → 权限"
 * 顺序打破锁环——此后三个事务都在 U 行上排队（S/S 兼容、S 与 A 的 X 互斥），
 * 任何交错均无环。</p>
 *
 * <p>操作人锁只用 S 不用 X：角色改名/启停先 {@code UPDATE sys_role} 持角色 X 锁、
 * 事务末尾审计外键对同一操作人行加 S 锁；若本服务用 {@code FOR UPDATE}（X）先
 * 锁操作人再等权限行，X 与 S 互斥会与角色编辑形成新锁环。S/S 与审计外键检查
 * 兼容、S 与 A 模块 {@code findStatusForUpdate}（X）互斥，既消除锁环又保持
 * "用户 → 权限"串行（详见 {@link OperatorRowLockMapper} 类 JavaDoc 的锁兼容
 * 分析）。无认证上下文（内部调用、单测）时跳过——与 {@code AuditLogAspect}
 * 把 {@code user_id} 记为 null、外键不检查父表的行为一致。启停在事务开头
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

    /** 操作人用户行共享锁（评审 P2）：统一"用户 → 权限"取锁顺序。 */
    private final OperatorRowLockMapper operatorRowLockMapper;

    /** 当前请求身份（A 提供）：操作人 ID 只取已验证身份，不接受请求参数。 */
    private final CurrentUserProvider currentUserProvider;

    public PermissionAdminServiceImpl(SysPermissionMapper sysPermissionMapper,
            OperatorRowLockMapper operatorRowLockMapper,
            PermissionWriteAuditMarker permissionWriteAuditMarker,
            CurrentUserProvider currentUserProvider) {
        this.sysPermissionMapper = sysPermissionMapper;
        this.operatorRowLockMapper = operatorRowLockMapper;
        this.permissionWriteAuditMarker = permissionWriteAuditMarker;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    @Transactional
    @AuditLog(operation = "permission.create")
    public PermissionView create(PermissionCreateRequest request) {
        lockOperatorFirst();
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
        lockOperatorFirst();
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

        // 评审 P2：参数校验通过后、锁定权限行之前，先共享锁操作人用户行
        //（统一"用户 → 权限"顺序，详见类 JavaDoc 的三事务锁环分析）
        lockOperatorFirst();

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

    /**
     * 评审 P2：在触碰权限行（读 / 写 / 锁）之前先 {@code FOR SHARE} 共享锁定
     * 操作人用户行，统一"用户 → 权限"取锁顺序，打破"权限修改/启停 × 角色
     * 权限替换 × 用户角色分配"三事务并发下的 P→U→R→P 锁环；且与角色编辑的
     * 审计外键 S 锁兼容（详见类 JavaDoc 与 {@link OperatorRowLockMapper} 的
     * 锁兼容分析）。
     *
     * <p>操作人只取 A 的 {@code CurrentUserProvider}（已验证身份），不接受请求
     * 参数。无认证上下文（内部调用、单元测试）时 {@code requireCurrentUser()}
     * 抛 UNAUTHORIZED，此处跳过——与 {@code AuditLogAspect#resolveUserId} 一致：
     * 此时审计 {@code user_id} 为 {@code null}、外键不检查父表，本来就不会产生
     * 隐式加锁，跳过既不破坏顺序也不隐藏已认证请求的锁。</p>
     */
    private void lockOperatorFirst() {
        try {
            CurrentUser current = currentUserProvider.requireCurrentUser();
            if (current != null && current.userId() != null) {
                operatorRowLockMapper.selectOperatorForShare(current.userId());
            }
        } catch (BusinessException exception) {
            // 与 AuditLogAspect 一致：无认证上下文不伪造身份，也不加这把锁。
        }
    }
}
