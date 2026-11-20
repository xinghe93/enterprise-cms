package com.xinghe.cms.user.service;

import com.xinghe.cms.common.contract.RoleRef;
import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.permission.service.RoleDirectoryService;
import com.xinghe.cms.security.PasswordHasher;
import com.xinghe.cms.user.dto.UserCreateRequest;
import com.xinghe.cms.user.dto.UserListQuery;
import com.xinghe.cms.user.dto.UserRolesUpdateRequest;
import com.xinghe.cms.user.dto.UserUpdateRequest;
import com.xinghe.cms.user.mapper.UserAdminMapper;
import com.xinghe.cms.user.mapper.row.UserProfileRow;
import com.xinghe.cms.user.mapper.row.UserRoleRow;
import com.xinghe.cms.user.vo.RoleBriefVO;
import com.xinghe.cms.user.vo.UserPageVO;
import com.xinghe.cms.user.vo.UserVO;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 员工管理（A 模块）：分页、详情、创建、修改、角色全量替换。
 *
 * <p>启停单独放在 {@link UserStatusService}，因为它还承担「事务提交后撤销会话」的职责；
 * 本服务的写操作不触碰 Redis：B 的 {@code AuthorizationQueryService} 按请求实时查询
 * 授权，dev 上没有跨请求权限缓存，因此角色替换提交后立即生效，无需刷缓存或踢会话。</p>
 *
 * <p>所有写操作使用编程式 {@link TransactionTemplate}：返回时事务已提交或回滚，
 * 失败整体回滚；角色校验与关联替换在同一事务内完成，B 的
 * {@link RoleDirectoryService#lockAssignableRoles(List)} 要求 MANDATORY 事务语义。
 * 写操作的成功审计随业务事务提交：事务回调内经代理调用
 * {@link UserAuditedActions} 的 {@code @AuditLog} 方法执行业务动作（切面进入时事务
 * 已开启，SUCCESS 在 beforeCommit 写入业务事务，FAILURE 在回滚后用独立新事务补记——
 * PR #39 审查 P2）；只读查询不审计。</p>
 */
@Service
public class UserAdminService {

    private static final DateTimeFormatter BUSINESS_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final int ENABLED = 1;

    private final TransactionTemplate transactions;
    private final UserAdminMapper mapper;
    private final PasswordHasher passwordHasher;
    private final RoleDirectoryService roleDirectory;
    private final UserAuditedActions auditedActions;

    public UserAdminService(PlatformTransactionManager transactionManager, UserAdminMapper mapper,
            PasswordHasher passwordHasher, RoleDirectoryService roleDirectory,
            UserAuditedActions auditedActions) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
        this.passwordHasher = passwordHasher;
        this.roleDirectory = roleDirectory;
        this.auditedActions = auditedActions;
    }

    /**
     * 员工分页：默认 updatedAt DESC / id DESC；departmentId 含本部门与全部后代；
     * status 省略为全部；keyword 按 username / realName 字面包含。
     */
    public UserPageVO listUsers(UserListQuery query) {
        String keywordPattern = likePattern(query.keyword());
        List<Long> departmentIds = query.departmentId() == null
                ? null
                : mapper.findDescendantDepartmentIds(query.departmentId());
        // 合法但不存在的部门：契约 8.2 要求 200 空分页，不是 404，也不是「不做筛选」。
        if (departmentIds != null && departmentIds.isEmpty()) {
            return emptyPage(query);
        }
        long total = mapper.countUsers(keywordPattern, departmentIds, query.status());
        if (total == 0) {
            return emptyPage(query);
        }
        long offset;
        try {
            offset = Math.multiplyExact(query.current() - 1, query.size());
        } catch (ArithmeticException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页偏移量超出允许范围");
        }
        List<UserProfileRow> rows = mapper.findUsersPage(
                keywordPattern, departmentIds, query.status(), offset, query.size());
        Map<Long, List<RoleBriefVO>> rolesByUser =
                loadRoles(rows.stream().map(UserProfileRow::id).toList());
        List<UserVO> records = rows.stream()
                .map(row -> toUserVO(row, rolesByUser.getOrDefault(row.id(), List.of())))
                .toList();
        return new UserPageVO(records, total, query.current(), query.size());
    }

    public UserVO getUser(long userId) {
        UserProfileRow row = mapper.findProfileById(userId);
        if (row == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "员工不存在");
        }
        return toUserVO(row, loadRoles(List.of(userId)).getOrDefault(userId, List.of()));
    }

    /** 创建默认启用、无角色；部门引用不存在 404，目标或祖先停用 409，用户名重复 409。 */
    public UserVO createUser(UserCreateRequest request) {
        return transactions.execute(transaction -> auditedActions.createUser(() -> doCreateUser(request)));
    }

    private UserVO doCreateUser(UserCreateRequest request) {
        if (request.departmentId() != null) {
            requireEnabledDepartmentChain(request.departmentId());
        }
        String passwordHash = passwordHasher.hash(request.password());
        try {
            mapper.insertUser(request.username(), passwordHash, request.realName(),
                    request.departmentId());
        } catch (DuplicateKeyException exception) {
            // 契约 8.1：唯一键冲突转 409，不能被兜底异常处理器变成 500。
            throw new BusinessException(ResultCode.CONFLICT, "登录账号已存在");
        } catch (DataIntegrityViolationException exception) {
            // 已知外键引用冲突（并发删除部门）转 409；不把所有 SQL 异常一概当冲突。
            // sys_user 只有 fk_user_department 一个外键，其余完整性约束（长度/非空/
            // CHECK status）都已在前置严格校验中排除，因此能到这里的基本是并发部门变更。
            throw new BusinessException(ResultCode.CONFLICT, "部门已变更，请重试");
        }
        // 时间由数据库生成后回读，不接受客户端伪造时间；新建账号 roles 恒为 []。
        // 按 username 回读安全：sys_user 的 uk_user_username 唯一键与该查询使用同一列、
        // 同一排序规则（utf8mb4_0900_ai_ci）。该排序规则大小写不敏感——大小写变体在
        // 插入时就会命中唯一键转 409，不会留下可被本回读误命中的其他行；因此能走到
        // 回读的行必然是刚插入的那一行。
        UserProfileRow created = mapper.findProfileByUsername(request.username());
        if (created == null) {
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "创建结果不可读");
        }
        return toUserVO(created, List.of());
    }

    /**
     * 完整 PUT：只改姓名与部门；departmentId 为 null 清除部门。
     * 契约 5：原部门引用不变时可保留已停用部门；换到新部门才检查目标及整条祖先链。
     */
    public UserVO updateUser(long userId, UserUpdateRequest request) {
        return transactions.execute(transaction ->
                auditedActions.updateUser(() -> doUpdateUser(userId, request)));
    }

    private UserVO doUpdateUser(long userId, UserUpdateRequest request) {
        UserProfileRow current = mapper.findProfileForUpdate(userId);
        if (current == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "员工不存在");
        }
        if (request.departmentId() != null
                && !Objects.equals(request.departmentId(), current.departmentId())) {
            requireEnabledDepartmentChain(request.departmentId());
        }
        int rows;
        try {
            rows = mapper.updateProfile(userId, request.realName(), request.departmentId());
        } catch (DataIntegrityViolationException exception) {
            // 校验通过后目标部门仍可能被并发删除：与创建入口一样转 409，
            // 不能落到通用 500。sys_user 只有 fk_user_department 一个外键。
            throw new BusinessException(ResultCode.CONFLICT, "部门已变更，请重试");
        }
        // rows == 0 是 MySQL 的正常语义：值未变化时 affected rows 为 0，按幂等成功处理。
        // findProfileForUpdate 的行锁从本事务开始持续持有，并发删除会阻塞到本事务提交，
        // 因此 0 行不可能是「被并发删除」，无需再重查区分。
        UserProfileRow updated = mapper.findProfileById(userId);
        if (updated == null) {
            throw new BusinessException(ResultCode.CONFLICT, "员工资料已变更，请重试");
        }
        return toUserVO(updated, loadRoles(List.of(userId)).getOrDefault(userId, List.of()));
    }

    /**
     * 全量替换角色：事务内先锁定并校验用户，再调用 B 的共享服务锁定启用角色，
     * 最后整体替换 {@code sys_user_role}；集合相同不重复插关联（契约 8.1）。
     */
    public void replaceRoles(long userId, UserRolesUpdateRequest request) {
        transactions.execute(transaction -> {
            auditedActions.replaceRoles(() -> doReplaceRoles(userId, request));
            return null;
        });
    }

    private void doReplaceRoles(long userId, UserRolesUpdateRequest request) {
        // FOR UPDATE 锁定 sys_user 行直到事务结束：并发的角色全量替换会在这一行上串行化，
        // 因此后续「读现有集合 → 删除 → 插入」基于同一最新快照，不会相互覆盖。
        if (mapper.findStatusForUpdate(userId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "员工不存在");
        }
        List<Long> target = request.roleIds().stream().distinct().sorted().toList();
        // 即使集合未变也要校验：既有停用角色不能借幂等语义继续保留。
        List<RoleRef> assignable = roleDirectory.lockAssignableRoles(target);
        if (assignable.size() != target.size()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }
        // findRoleIdsByUserId 按 role_id ASC 返回（见 Mapper XML），与 sorted target 同序。
        List<Long> current = mapper.findRoleIdsByUserId(userId);
        if (!current.equals(target)) {
            mapper.deleteUserRoles(userId);
            if (!target.isEmpty()) {
                mapper.insertUserRoles(userId, target);
            }
        }
    }

    private void requireEnabledDepartmentChain(Long departmentId) {
        List<Integer> chain = mapper.findDepartmentChainStatuses(departmentId);
        if (chain == null || chain.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "部门不存在");
        }
        if (chain.stream().anyMatch(status -> status == null || status != ENABLED)) {
            throw new BusinessException(ResultCode.CONFLICT, "部门或上级部门已停用");
        }
    }

    private Map<Long, List<RoleBriefVO>> loadRoles(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<RoleBriefVO>> result = new LinkedHashMap<>();
        for (UserRoleRow row : mapper.findRolesByUserIds(userIds)) {
            result.computeIfAbsent(row.userId(), ignored -> new ArrayList<>())
                    .add(new RoleBriefVO(toId(row.roleId()), row.roleCode(),
                            row.roleName(), row.status()));
        }
        return result;
    }

    private UserPageVO emptyPage(UserListQuery query) {
        return new UserPageVO(List.of(), 0, query.current(), query.size());
    }

    /** 契约 8.2：% _ ! 作为普通字符；转义后由 SQL 的 ESCAPE '!' 解释。 */
    private String likePattern(String keyword) {
        if (keyword == null) {
            return null;
        }
        // 原始长度已在 Controller 校验；这里再次 trim，保证 Service 被直接调用时语义一致。
        String normalized = keyword.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return "%" + normalized.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    private UserVO toUserVO(UserProfileRow row, List<RoleBriefVO> roles) {
        return new UserVO(
                toId(row.id()),
                row.username(),
                row.realName(),
                toId(row.departmentId()),
                row.departmentName(),
                row.status(),
                roles,
                toBusinessTime(row.createdAt()),
                toBusinessTime(row.updatedAt()));
    }

    private String toId(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private String toBusinessTime(LocalDateTime value) {
        return value == null ? null : value.format(BUSINESS_TIME);
    }
}
