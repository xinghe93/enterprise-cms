package com.xinghe.cms.user.service;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.security.AuthSessionService;
import com.xinghe.cms.security.PasswordHasher;
import com.xinghe.cms.user.dto.LoginRequest;
import com.xinghe.cms.user.mapper.UserAuthMapper;
import com.xinghe.cms.user.mapper.row.RoleBriefRow;
import com.xinghe.cms.user.mapper.row.UserCredentialsRow;
import com.xinghe.cms.user.mapper.row.UserProfileRow;
import com.xinghe.cms.user.vo.LoginResultVO;
import com.xinghe.cms.user.vo.RoleBriefVO;
import com.xinghe.cms.user.vo.UserVO;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录、退出与当前身份（A 的首批交付）。
 *
 * <p>登录失败统一 401「用户名或密码错误」，不区分账号不存在、密码错误或停用，
 * 也不因账号或数据库故障伪造成功。有效角色/权限由 B 的授权快照另行计算，
 * 本服务返回的 User.roles 只用于管理回显。</p>
 */
@Service
public class AuthService {

    private static final String LOGIN_FAILURE_MESSAGE = "用户名或密码错误";
    private static final DateTimeFormatter BUSINESS_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final UserAuthMapper userMapper;
    private final PasswordHasher passwordHasher;
    private final AuthSessionService sessions;

    public AuthService(UserAuthMapper userMapper, PasswordHasher passwordHasher,
            AuthSessionService sessions) {
        this.userMapper = userMapper;
        this.passwordHasher = passwordHasher;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    public LoginResultVO login(LoginRequest request) {
        String username = request.username().trim();
        UserCredentialsRow credentials = userMapper.findCredentialsByUsername(username);
        if (credentials == null || !Integer.valueOf(1).equals(credentials.status())
                || !passwordHasher.matches(request.password(), credentials.passwordHash())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, LOGIN_FAILURE_MESSAGE);
        }
        // 先完成全部数据库读取，再签发会话，避免数据库失败时留下孤儿 Token。
        UserVO user = toUserVO(credentials.id());
        String token = sessions.issue(credentials.id());
        return new LoginResultVO(token, "Bearer", AuthSessionService.TTL_SECONDS, user);
    }

    /** 只撤销当前会话；其他登录保持有效。 */
    public void logout(String token) {
        sessions.revoke(token);
    }

    @Transactional(readOnly = true)
    public UserVO currentUser(Long userId) {
        return toUserVO(userId);
    }

    private UserVO toUserVO(Long userId) {
        UserProfileRow profile = userMapper.findProfileById(userId);
        if (profile == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        List<RoleBriefVO> roles = userMapper.findRolesByUserId(userId).stream()
                .sorted(Comparator.comparing(RoleBriefRow::id,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toRoleBrief)
                .toList();
        return new UserVO(
                toId(profile.id()),
                profile.username(),
                profile.realName(),
                toId(profile.departmentId()),
                profile.departmentName(),
                profile.status(),
                roles,
                toBusinessTime(profile.createdAt()),
                toBusinessTime(profile.updatedAt()));
    }

    private RoleBriefVO toRoleBrief(RoleBriefRow row) {
        return new RoleBriefVO(toId(row.id()), row.roleCode(), row.roleName(), row.status());
    }

    private String toId(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private String toBusinessTime(LocalDateTime value) {
        return value == null ? null : value.format(BUSINESS_TIME);
    }
}
