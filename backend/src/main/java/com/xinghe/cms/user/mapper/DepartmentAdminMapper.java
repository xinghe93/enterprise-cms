package com.xinghe.cms.user.mapper;

import com.xinghe.cms.user.mapper.row.DepartmentRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 部门管理（A 模块）的读写映射。
 *
 * <p>只写 {@code sys_department}；子部门 / 员工引用检查用只读 COUNT，不级联删除。
 * 树与祖先链在 Service 中基于 {@link #findAll()} 一次性构建，避免 N+1 查询，
 * 也不依赖数据库特定的递归语法。</p>
 */
@Mapper
public interface DepartmentAdminMapper {

    /** 一次取回全部节点（含停用），用于树排序、祖先链和后代判定。 */
    List<DepartmentRow> findAll();

    /**
     * 写事务内按 id 升序锁定整棵部门树，供「校验层级 → 写入」基于受保护的最新状态。
     * 固定排序使并发移动以相同顺序加锁，避免两个根互换父节点时各自基于过期快照通过校验而成环。
     */
    List<DepartmentRow> findAllForUpdate();

    DepartmentRow findById(@Param("id") Long id);

    /** 写事务内加行锁，保证「校验 → 写入」之间目标节点不被并发删除。 */
    DepartmentRow findByIdForUpdate(@Param("id") Long id);

    /** 创建后按唯一 deptCode 回读，让 createdAt/updatedAt 完全取自数据库。 */
    DepartmentRow findByDeptCode(@Param("deptCode") String deptCode);

    /** status 由建表默认值 1 生成，不接受客户端指定。 */
    int insert(@Param("parentId") Long parentId, @Param("deptCode") String deptCode,
            @Param("deptName") String deptName, @Param("sortOrder") int sortOrder);

    /** 不修改 deptCode / status，也不级联子节点。 */
    int updateNode(@Param("id") Long id, @Param("parentId") Long parentId,
            @Param("deptName") String deptName, @Param("sortOrder") int sortOrder);

    int updateStatus(@Param("id") Long id, @Param("status") int status);

    int deleteById(@Param("id") Long id);

    /** 是否存在子部门；含停用节点。 */
    int countChildren(@Param("id") Long id);

    /** 是否存在员工引用；含停用员工，契约 5 要求一并阻止删除。 */
    int countUsers(@Param("id") Long id);
}
