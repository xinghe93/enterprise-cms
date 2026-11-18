package com.xinghe.cms.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.user.dto.DepartmentCreateRequest;
import com.xinghe.cms.user.dto.DepartmentUpdateRequest;
import com.xinghe.cms.user.mapper.DepartmentAdminMapper;
import com.xinghe.cms.user.mapper.row.DepartmentRow;
import com.xinghe.cms.user.vo.DepartmentVO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 部门管理服务的树排序、祖先链、防环、删除边界与启停幂等回归。 */
@ExtendWith(MockitoExtension.class)
class DepartmentAdminServiceTests {

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 2, 16, 0, 0);

    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private DepartmentAdminMapper mapper;

    private DepartmentAdminService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new DepartmentAdminService(transactionManager, mapper,
                new DepartmentAuditedActions());
    }

    private DepartmentRow row(long id, Long parentId, int sort, int status) {
        return new DepartmentRow(id, parentId, "CODE" + id, "部门" + id,
                sort, status, TIME, TIME);
    }

    private List<DepartmentRow> tree() {
        return List.of(
                row(1L, null, 10, 0),   // 停用根：筛选时必须保留且保持真实 status=0
                row(2L, 1L, 1, 1),
                row(3L, 1L, 1, 1),
                row(4L, 2L, 0, 1));
    }

    @Test
    void statusFilterKeepsNecessaryAncestorsWithRealStatus() {
        when(mapper.findAll()).thenReturn(tree());

        List<DepartmentVO> result = service.getTree(1);

        assertEquals(1, result.size());
        assertEquals("1", result.get(0).id());
        assertEquals(0, result.get(0).status(), "停用祖先不能被伪装成启用");
        assertEquals(List.of("2", "3"),
                result.get(0).children().stream().map(DepartmentVO::id).toList());
        DepartmentVO child2 = result.get(0).children().get(0);
        assertEquals(List.of("4"), child2.children().stream().map(DepartmentVO::id).toList());
        assertEquals(List.of(), child2.children().get(0).children());
        assertEquals(List.of(), result.get(0).children().get(1).children());
    }

    @Test
    void noStatusFilterReturnsAllNodesAndEmptyTreeReturnsEmptyList() {
        when(mapper.findAll()).thenReturn(tree()).thenReturn(List.of());

        assertEquals(4, countNodes(service.getTree(null)));
        assertEquals(List.of(), service.getTree(0));
    }

    private int countNodes(List<DepartmentVO> nodes) {
        int total = nodes.size();
        for (DepartmentVO node : nodes) {
            total += countNodes(node.children());
        }
        return total;
    }

    @Test
    void createUsesDefaultsAndDatabaseTimestamps() {
        when(mapper.insert(null, "DEV", "研发部门", 0)).thenReturn(1);
        when(mapper.findByDeptCode("DEV")).thenReturn(row(5L, null, 0, 1));

        DepartmentVO result = service.create(
                new DepartmentCreateRequest(null, "DEV", "研发部门", 0));

        assertEquals("5", result.id());
        assertNull(result.parentId());
        assertEquals(1, result.status());
        assertEquals(0, result.sortOrder());
        assertEquals(List.of(), result.children());
        assertEquals("2026-11-02T16:00:00.000", result.createdAt());
        verify(transactionManager).commit(any());
    }

    @Test
    void createRejectsMissingParentWith404() {
        when(mapper.findAllForUpdate()).thenReturn(tree());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create(new DepartmentCreateRequest(99L, "DEV", "研发部门", 0)));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        verify(mapper, never()).insert(eq(99L), any(), any(), anyInt());
        verify(transactionManager).rollback(any());
    }

    @Test
    void createRejectsDisabledAncestorWith409() {
        when(mapper.findAllForUpdate()).thenReturn(tree());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create(new DepartmentCreateRequest(2L, "DEV", "研发部门", 0)));

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        verify(mapper, never()).insert(any(), any(), any(), anyInt());
    }

    @Test
    void duplicateDeptCodeMapsTo409() {
        when(mapper.insert(any(), any(), any(), anyInt()))
                .thenThrow(new DuplicateKeyException("uk_department_code"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create(new DepartmentCreateRequest(null, "DEV", "研发部门", 0)));

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        verify(transactionManager).rollback(any());
    }

    @Test
    void updateRejectsSelfAndDescendantAsParent() {
        // 每次调用都返回最新快照：连续 thenReturn 的重复行为是 Mockito 细节，
        // 依赖它会在循环第三次调用时悄悄返回过期/空值（DeepSeek 审查）。
        when(mapper.findAllForUpdate()).thenAnswer(invocation -> tree());

        for (Long parent : List.of(1L, 2L, 4L)) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.update(1L, new DepartmentUpdateRequest(parent, "研发部门", 0)));
            assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        }
        verify(mapper, never()).updateNode(any(), any(), any(), anyInt());
        verify(mapper, org.mockito.Mockito.atLeastOnce()).findAllForUpdate();
    }

    @Test
    void updateUsesLockedHierarchySnapshot() {
        // 校验与写入必须基于 findAllForUpdate 的受保护快照，不能退回归普通快照读。
        when(mapper.findAllForUpdate()).thenAnswer(invocation -> tree());
        when(mapper.updateNode(2L, 1L, "研发部门", 5)).thenReturn(1);

        service.update(2L, new DepartmentUpdateRequest(1L, "研发部门", 5));

        verify(mapper, org.mockito.Mockito.times(2)).findAllForUpdate();
        verify(mapper, never()).findAll();
    }

    @Test
    void updateMapsForeignKeyConflictTo409() {
        // 父节点存在且祖先链启用，校验通过后才可能在 updateNode 写入时遇到外键冲突。
        List<DepartmentRow> enabledTree = List.of(
                row(1L, null, 10, 1), row(2L, 1L, 1, 1), row(3L, 1L, 1, 1));
        when(mapper.findAllForUpdate()).thenReturn(enabledTree);
        when(mapper.updateNode(2L, 3L, "研发部门", 5))
                .thenThrow(new DataIntegrityViolationException("fk parent_id"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.update(2L, new DepartmentUpdateRequest(3L, "研发部门", 5)));

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        verify(transactionManager).rollback(any());
    }

    /** 并发移动成环回归：second 必须基于 first 提交后的最新层级校验，而不是过期快照。 */
    @Test
    void concurrentRootSwapDetectsCycleOnLockedSnapshot() {
        // 初始：1、2 都是根。first 把 1 挂到 2 下并提交后，层级变为 1.parent=2。
        List<DepartmentRow> afterFirst = List.of(
                row(1L, 2L, 10, 1), row(2L, null, 1, 1));
        // 第二次移动基于 first 提交后的最新层级校验。
        when(mapper.findAllForUpdate()).thenReturn(afterFirst);

        // second 要把 2 挂到 1 下；基于 afterFirst，1 是 2 的后代 → 409，不能形成环。
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.update(2L, new DepartmentUpdateRequest(1L, "研发部门", 0)));

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        verify(mapper, never()).updateNode(2L, 1L, "研发部门", 0);
    }

    @Test
    void updateKeepsUnchangedDisabledParentAndReturnsFullSubtree() {
        when(mapper.findAllForUpdate()).thenAnswer(invocation -> tree());
        when(mapper.updateNode(2L, 1L, "研发部门", 5)).thenReturn(1);

        DepartmentVO result = service.update(2L, new DepartmentUpdateRequest(1L, "研发部门", 5));

        assertEquals("2", result.id());
        assertEquals("1", result.parentId());
        assertEquals(List.of("4"), result.children().stream().map(DepartmentVO::id).toList());
        verify(mapper).updateNode(2L, 1L, "研发部门", 5);
    }

    @Test
    void updateMissingDepartmentReturns404() {
        when(mapper.findAllForUpdate()).thenReturn(tree());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.update(9L, new DepartmentUpdateRequest(null, "研发部门", 0)));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
    }

    @Test
    void deleteRejectsChildrenAndUsersIncludingDisabledOnes() {
        when(mapper.findByIdForUpdate(1L)).thenReturn(row(1L, null, 10, 1));
        when(mapper.countChildren(1L)).thenReturn(1).thenReturn(0);
        when(mapper.countUsers(1L)).thenReturn(1);

        assertThrows(BusinessException.class, () -> service.delete(1L));
        BusinessException exception = assertThrows(BusinessException.class, () -> service.delete(1L));
        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        verify(mapper, never()).deleteById(any());
    }

    @Test
    void deleteEmptyDepartmentSucceeds() {
        when(mapper.findByIdForUpdate(3L)).thenReturn(row(3L, 1L, 1, 1));
        when(mapper.countChildren(3L)).thenReturn(0);
        when(mapper.countUsers(3L)).thenReturn(0);
        when(mapper.deleteById(3L)).thenReturn(1);

        service.delete(3L);

        verify(mapper).deleteById(3L);
        verify(transactionManager).commit(any());
    }

    @Test
    void deleteMissingDepartmentReturns404() {
        when(mapper.findByIdForUpdate(9L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.delete(9L));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
    }

    @Test
    void sameStatusIsNoOp() {
        when(mapper.findByIdForUpdate(1L)).thenReturn(row(1L, null, 10, 0));

        service.changeStatus(1L, 0);

        verify(mapper, never()).updateStatus(any(), anyInt());
    }

    @Test
    void changeStatusOnlyTouchesCurrentNode() {
        when(mapper.findByIdForUpdate(1L)).thenReturn(row(1L, null, 10, 1));
        when(mapper.updateStatus(1L, 0)).thenReturn(1);

        service.changeStatus(1L, 0);

        verify(mapper).updateStatus(1L, 0);
        verify(mapper, never()).findAll();
    }

    @Test
    void changeStatusMissingDepartmentReturns404() {
        when(mapper.findByIdForUpdate(9L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.changeStatus(9L, 0));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        verify(mapper, never()).updateStatus(any(), anyInt());
    }
}
