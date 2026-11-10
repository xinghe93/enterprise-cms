package com.xinghe.cms.user.service;

import com.xinghe.cms.common.exception.BusinessException;
import com.xinghe.cms.common.response.ResultCode;
import com.xinghe.cms.security.AuthSessionService;
import com.xinghe.cms.security.PasswordHasher;
import com.xinghe.cms.user.dto.LoginRequest;
import com.xinghe.cms.user.mapper.UserAuthMapper;
import com.xinghe.cms.user.mapper.row.CurrentUserStatusRow;
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

    /**
     * 恒定耗时校验用的占位哈希：格式合法、迭代次数与盐/摘要长度和真实哈希一致，
     * 但不对应任何账号，也不可能与真实密码匹配。账号不存在或停用时同样跑满一次
     * PBKDF2，避免攻击者通过响应时间差枚举账号。全零摘要不是任何凭据。
     */
    private static final String TIMING_GUARD_HASH =
            "pbkdf2-sha256$210000$AAAAAAAAAAAAAAAAAAAAAA$"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private final UserAuthMapper userMapper;
    private final PasswordHasher passwordHasher;
    private final AuthSessionService sessions;

    public AuthService(UserAuthMapper userMapper, PasswordHasher passwordHasher,
            AuthSessionService sessions) {
        this.userMapper = userMapper;
        this.passwordHasher = passwordHasher;
        this.sessions = sessions;
    }

    /**
     * 登录。此处不开启只读事务：会话签发是 Redis 写，不受 JDBC 事务管理，
     * 放在 readOnly 事务内只会造成语义误导。所有数据库读取都在签发前完成，
     * 任一步失败都会直接抛出异常，不会留下孤儿 Token。
     *
     * <p>登录顺序（密码校验通过后才碰会话状态）：
     * ① {@code ensureEpoch} 原子取得或初始化一个<strong>确定的非空纪元</strong>；
     * ② 复查账号状态；③ 带上该纪元原子签发。</p>
     *
     * <p>并发首次登录：① 用单段 Lua 完成「读取或创建」，多个并发请求拿到的是同一个
     * 纪元，随后各自签发独立 Token，不会出现「都读到空纪元、只有第一个能成功」而让
     * 正确的账号密码返回 401。</p>
     *
     * <p>并发停用防线（契约 3.1「禁止按旧的启用快照建立可用会话」）：停用按契约先在
     * 数据库事务里提交 status=0、提交后才刷新会话纪元，因此
     * ① 停用提交发生在取得纪元之前时，复查状态会看到已停用；
     * ② 停用提交发生在复查之后时，签发脚本按已取得的旧纪元比对必然失败并返回 null。
     * 两条路径都不会留下「重新启用后仍可用」的 Token，也不换用新纪元重试。</p>
     *
     * <p>纪元键在取得之后丢失时，签发脚本读不到纪元直接拒绝，不会按「首次登录」重建，
     * 已撤销的旧 Token 也不会复活。</p>
     */
    public LoginResultVO login(LoginRequest request) {
        String username = request.username().trim();
        UserCredentialsRow credentials = userMapper.findCredentialsByUsername(username);
        boolean enabled = credentials != null && Integer.valueOf(1).equals(credentials.status());
        String expectedHash = enabled ? credentials.passwordHash() : TIMING_GUARD_HASH;
        boolean passwordMatched = passwordHasher.matches(request.password(), expectedHash);
        if (!enabled || !passwordMatched) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, LOGIN_FAILURE_MESSAGE);
        }
        Long userId = credentials.id();
        // 密码校验通过后、复查状态前：原子取得或初始化同一个随机纪元。
        String expectedEpoch = sessions.ensureEpoch(userId);
        CurrentUserStatusRow status = userMapper.findStatusById(userId);
        if (status == null || status.status() == null || status.status() != 1) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, LOGIN_FAILURE_MESSAGE);
        }
        UserVO user = toUserVO(userId);
        String token = sessions.issue(userId, expectedEpoch);
        if (token == null) {
            // 复查之后发生撤销（或纪元键丢失）：不能换用新纪元重试，否则会签出在账号
            // 重新启用后仍然可用的 Token。统一按登录失败处理，不泄露原因。
            throw new BusinessException(ResultCode.UNAUTHORIZED, LOGIN_FAILURE_MESSAGE);
        }
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
