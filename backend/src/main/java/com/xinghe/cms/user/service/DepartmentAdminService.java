package com.xinghe.cms.user.service;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.user.dto.DepartmentCreateRequest;
import com.xinghe.cms.user.dto.DepartmentUpdateRequest;
import com.xinghe.cms.user.mapper.DepartmentAdminMapper;
import com.xinghe.cms.user.mapper.row.DepartmentRow;
import com.xinghe.cms.user.vo.DepartmentVO;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 部门管理（A 模块）：树、创建、修改、删除空部门、启停。
 *
 * <p>契约《接口与协作约定》5 / 8.2：树各层按 sortOrder ASC、id ASC；status 筛选时
 * 保留必要祖先且祖先保留真实状态；部门/分类启停只改当前节点，不级联子部门或员工；
 * 删除前检查子部门与全部员工引用（含停用员工）；移动到自身或后代父节点返回 409；
 * 原父节点不变时可保留停用引用，换父节点才检查目标及整条祖先链。</p>
 *
 * <p>树、祖先链、后代集合都基于一次 {@code findAll()} 在内存中构建：避免 N+1 查询，
 * 也避免依赖特定数据库的递归 CTE 语法。写操作使用编程式事务，返回时已提交或回滚。</p>
 *
 * <p><strong>层级写的并发一致性</strong>：create/update 在写事务内先调用
 * {@code findAllForUpdate()} 按 id 升序锁定整棵部门树，防环/父链校验与写入基于同一份
 * 受保护的最新快照。只锁当前节点会让两个根部门互换父节点的并发移动各自通过校验，
 * 提交后形成环使子树从响应中消失；固定加锁顺序同时避免死锁。</p>
 *
 * <p><strong>审计</strong>：4 个写动作在事务内经代理调用 {@link DepartmentAuditedActions}，
 * 使 D 的 {@code @AuditLog} 切面看到已开启的事务，SUCCESS 随业务一起提交、写入失败
 * 一并回滚（PR #40 审查 P2）；只读的树查询不审计。</p>
 */
@Service
public class DepartmentAdminService {

    private static final DateTimeFormatter BUSINESS_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final int ENABLED = 1;
    private static final Comparator<DepartmentRow> NODE_ORDER =
            Comparator.comparing(DepartmentRow::sortOrder,
                            Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(DepartmentRow::id);

    private final TransactionTemplate transactions;
    private final DepartmentAdminMapper mapper;
    private final DepartmentAuditedActions auditedActions;
    private final UserWriteLocks userWriteLocks;

    public DepartmentAdminService(PlatformTransactionManager transactionManager,
            DepartmentAdminMapper mapper, DepartmentAuditedActions auditedActions, UserWriteLocks userWriteLocks) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
        this.auditedActions = auditedActions;
        this.userWriteLocks = userWriteLocks;
    }

    /** status 为 null 时返回全部状态；否则返回匹配节点 + 必要祖先（祖先保持真实 status）。 */
    public List<DepartmentVO> getTree(Integer status) {
        List<DepartmentRow> all = mapper.findAll();
        if (all.isEmpty()) {
            return List.of();
        }
        Set<Long> keepIds = status == null ? null : selectWithAncestors(all, status);
        return buildForest(all, keepIds, null);
    }

    /** 创建默认启用、children=[]；新父部门及祖先必须启用；deptCode 重复 409。 */
    public DepartmentVO create(DepartmentCreateRequest request) {
        return transactions.execute(transaction ->
                auditedActions.create(() -> doCreate(request)));
    }

    /** 实际创建动作：在事务内、审计切面内执行。 */
    private DepartmentVO doCreate(DepartmentCreateRequest request) {
        userWriteLocks.lockOperatorForShare();
        // 父链校验必须基于加锁的当前读，避免并发删除/停用父部门后仍按旧快照写入。
        // 根节点无父链可校验，不必锁树。
        if (request.parentId() != null) {
            requireEnabledChain(mapper.findAllForUpdate(), request.parentId());
        }
        try {
            mapper.insert(request.parentId(), request.deptCode(),
                    request.deptName(), request.sortOrder());
        } catch (DuplicateKeyException exception) {
            // 契约 8.1：唯一键冲突转 409，不能落到兜底 500。
            throw new BusinessException(ResultCode.CONFLICT, "部门编码已存在");
        } catch (DataIntegrityViolationException exception) {
            // 并发删除父部门造成的已知外键引用冲突；不把所有 SQL 异常一概当冲突。
            throw new BusinessException(ResultCode.CONFLICT, "父部门已变更，请重试");
        }
        DepartmentRow created = mapper.findByDeptCode(request.deptCode());
        if (created == null) {
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "创建结果不可读");
        }
        return leaf(created);
    }

    /** 完整 PUT：不修改 deptCode / status；返回目标节点及实际完整子树（全部状态）。 */
    public DepartmentVO update(long id, DepartmentUpdateRequest request) {
        return transactions.execute(transaction ->
                auditedActions.update(() -> doUpdate(id, request)));
    }

    /** 实际修改动作：在事务内、审计切面内执行。 */
    private DepartmentVO doUpdate(long id, DepartmentUpdateRequest request) {
        userWriteLocks.lockOperatorForShare();
        // 先按 id 升序锁住整棵树，后续的防环/父链校验与 updateNode 都基于同一份受保护快照。
        // 只锁当前节点会让两个根互换父节点的并发移动各自通过校验，提交后形成环。
        List<DepartmentRow> all = mapper.findAllForUpdate();
        DepartmentRow locked = all.stream()
                .filter(row -> Objects.equals(row.id(), id))
                .findFirst()
                .orElse(null);
        if (locked == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "部门不存在");
        }
        boolean parentChanged = !Objects.equals(request.parentId(), locked.parentId());
        if (parentChanged && request.parentId() != null) {
            if (collectSubtree(all, id).contains(request.parentId())) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "父部门不能是目标部门自身或其后代");
            }
            requireEnabledChain(all, request.parentId());
        }
        try {
            mapper.updateNode(id, request.parentId(),
                    request.deptName(), request.sortOrder());
        } catch (DataIntegrityViolationException exception) {
            // 校验通过后父部门仍可能被未走本服务的路径删除，外键冲突按契约转 409。
            throw new BusinessException(ResultCode.CONFLICT, "父部门已变更，请重试");
        }
        // 整树已在事务内锁定，当前节点必然存在且写入成功；回读当前读得到真实 updatedAt。
        List<DepartmentRow> updated = mapper.findAllForUpdate();
        DepartmentRow after = updated.stream()
                .filter(row -> Objects.equals(row.id(), id))
                .findFirst()
                .orElse(null);
        if (after == null) {
            throw new BusinessException(ResultCode.CONFLICT, "部门已变更，请重试");
        }
        List<DepartmentVO> forest = buildForest(updated, collectSubtree(updated, id), id);
        if (forest.isEmpty()) {
            throw new BusinessException(ResultCode.CONFLICT, "部门已变更，请重试");
        }
        // 契约 8.1：不能用空 children 表示「未加载」，必须返回真实完整子树。
        return forest.get(0);
    }

    /** 只删除空部门：存在子部门或任何员工引用（含停用员工）返回 409，不级联删除。 */
    public void delete(long id) {
        transactions.execute(transaction -> {
            auditedActions.delete(() -> doDelete(id));
            return null;
        });
    }

    /** 实际删除动作：在事务内、审计切面内执行。 */
    private void doDelete(long id) {
        userWriteLocks.lockOperatorForShare();
        if (mapper.findByIdForUpdate(id) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "部门不存在");
        }
        if (mapper.countChildren(id) > 0 || mapper.countUsers(id) > 0) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "部门存在子部门或员工引用，无法删除");
        }
        if (mapper.deleteById(id) != 1) {
            throw new BusinessException(ResultCode.CONFLICT, "部门已变更，请重试");
        }
    }

    /** 只改本节点 status，不级联子节点、员工或员工登录态；重复同状态不写库。 */
    public void changeStatus(long id, int status) {
        transactions.execute(transaction -> {
            auditedActions.changeStatus(() -> doChangeStatus(id, status));
            return null;
        });
    }

    /** 实际启停动作：在事务内、审计切面内执行。 */
    private void doChangeStatus(long id, int status) {
        userWriteLocks.lockOperatorForShare();
        DepartmentRow locked = mapper.findByIdForUpdate(id);
        if (locked == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "部门不存在");
        }
        if (Objects.equals(locked.status(), status)) {
            return;
        }
        int rows = mapper.updateStatus(id, status);
        if (rows == 0 && mapper.findByIdForUpdate(id) == null) {
            throw new BusinessException(ResultCode.CONFLICT, "部门已变更，请重试");
        }
    }

    /** 按 status 命中的节点 + 其整条祖先链；祖先 status 原样保留，不做伪装。 */
    private Set<Long> selectWithAncestors(List<DepartmentRow> all, Integer status) {
        Map<Long, DepartmentRow> byId = index(all);
        Set<Long> keep = new HashSet<>();
        for (DepartmentRow row : all) {
            if (!Objects.equals(status, row.status())) {
                continue;
            }
            keep.add(row.id());
            Long parentId = row.parentId();
            Set<Long> seen = new HashSet<>();
            while (parentId != null && seen.add(parentId)) {
                DepartmentRow parent = byId.get(parentId);
                if (parent == null) {
                    break;
                }
                keep.add(parent.id());
                parentId = parent.parentId();
            }
        }
        return keep;
    }

    private List<DepartmentVO> buildForest(List<DepartmentRow> all, Set<Long> keepIds,
            Long rootId) {
        Map<Long, DepartmentRow> byId = index(all);
        Map<Long, List<DepartmentRow>> childrenByParent = new HashMap<>();
        for (DepartmentRow row : all) {
            if (keepIds != null && !keepIds.contains(row.id())) {
                continue;
            }
            childrenByParent.computeIfAbsent(row.parentId(), ignored -> new ArrayList<>())
                    .add(row);
        }
        childrenByParent.values().forEach(list -> list.sort(NODE_ORDER));
        // 根节点同样受 keepIds 约束，避免指定 rootId 时绕过筛选/子树范围。
        boolean rootAllowed = byId.containsKey(rootId)
                && (keepIds == null || keepIds.contains(rootId));
        List<DepartmentRow> roots = rootId != null
                ? (rootAllowed ? List.of(byId.get(rootId)) : List.of())
                : childrenByParent.getOrDefault(null, List.of());
        return roots.stream().map(row -> toVO(row, childrenByParent, new HashSet<>())).toList();
    }

    /**
     * 递归组装子树。{@code path} 是<strong>当前祖先路径</strong>（不是全局已访问集合）：
     * 只在递归进入时加入、退出时移除，因此兄弟节点之间互不干扰，只有祖先链上再次
     * 出现同一 ID 才是真环（DeepSeek 审查：全局 visited 会把兄弟间的重复引用误判成环）。
     */
    private DepartmentVO toVO(DepartmentRow row, Map<Long, List<DepartmentRow>> childrenByParent,
            Set<Long> path) {
        if (!path.add(row.id())) {
            // 脏数据成环时不能无限递归；按业务冲突处理，不暴露 SQL/栈信息。
            throw new BusinessException(ResultCode.CONFLICT, "部门层级存在环");
        }
        try {
            List<DepartmentVO> children = childrenByParent
                    .getOrDefault(row.id(), List.of()).stream()
                    .map(child -> toVO(child, childrenByParent, path))
                    .toList();
            return new DepartmentVO(
                    toId(row.id()), toId(row.parentId()), row.deptCode(), row.deptName(),
                    row.sortOrder(), row.status(), children,
                    toBusinessTime(row.createdAt()), toBusinessTime(row.updatedAt()));
        } finally {
            path.remove(row.id());
        }
    }

    private DepartmentVO leaf(DepartmentRow row) {
        return new DepartmentVO(
                toId(row.id()), toId(row.parentId()), row.deptCode(), row.deptName(),
                row.sortOrder(), row.status(), List.of(),
                toBusinessTime(row.createdAt()), toBusinessTime(row.updatedAt()));
    }

    /** 从根节点向下收集「自身 + 全部后代」，用于防环与更新响应的完整子树。 */
    private Set<Long> collectSubtree(List<DepartmentRow> all, long rootId) {
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (DepartmentRow row : all) {
            childrenByParent.computeIfAbsent(row.parentId(), ignored -> new ArrayList<>())
                    .add(row.id());
        }
        Set<Long> result = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        result.add(rootId);
        queue.add(rootId);
        while (!queue.isEmpty()) {
            for (Long child : childrenByParent.getOrDefault(queue.poll(), List.of())) {
                if (result.add(child)) {
                    queue.add(child);
                }
            }
        }
        return result;
    }

    /** 新父节点及其整条祖先链必须存在且启用；原引用不变时不调用本方法。 */
    private void requireEnabledChain(List<DepartmentRow> all, Long parentId) {
        Map<Long, DepartmentRow> byId = index(all);
        Long current = parentId;
        Set<Long> seen = new HashSet<>();
        while (current != null) {
            if (!seen.add(current)) {
                throw new BusinessException(ResultCode.CONFLICT, "部门层级存在环");
            }
            DepartmentRow row = byId.get(current);
            if (row == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "部门不存在");
            }
            if (row.status() == null || row.status() != ENABLED) {
                throw new BusinessException(ResultCode.CONFLICT, "部门或上级部门已停用");
            }
            current = row.parentId();
        }
    }

    private Map<Long, DepartmentRow> index(List<DepartmentRow> rows) {
        Map<Long, DepartmentRow> byId = new HashMap<>();
        for (DepartmentRow row : rows) {
            byId.put(row.id(), row);
        }
        return byId;
    }

    private String toId(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private String toBusinessTime(LocalDateTime value) {
        return value == null ? null : value.format(BUSINESS_TIME);
    }
}
