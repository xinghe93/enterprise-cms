package com.xinghe.cms.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 部门启停请求（OpenAPI: StatusUpdate）。
 *
 * <p>员工侧的 {@code UserStatusUpdateRequest} 在 {@code feature/user-account-api} 分支上，
 * 本分支从 dev 开出、不能引用尚未合入的类，因此这里保留一份同语义 DTO；两个 PR 都合入后
 * 可在后续分支统一为公共 StatusUpdate，不影响当前两个 PR 的可合并性。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonDeserialize(using = DepartmentStatusUpdateRequestDeserializer.class)
public record DepartmentStatusUpdateRequest(int status) {

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;
}
