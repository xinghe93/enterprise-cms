package com.xinghe.cms.user.controller;

import com.xinghe.cms.common.response.ApiResponse;
import com.xinghe.cms.security.CurrentUserProvider;
import com.xinghe.cms.user.dto.LoginRequest;
import com.xinghe.cms.user.service.AuthService;
import com.xinghe.cms.user.vo.LoginResultVO;
import com.xinghe.cms.user.vo.UserVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证入口（OpenAPI /api/auth/*，A 负责）。
 *
 * <p>login 为 PUBLIC；logout、me 为 AUTHENTICATED，身份由 A 的过滤器在 MVC 参数绑定前
 * 校验，不额外要求功能权限，因此不依赖 B 尚未交付的 @RequirePermission 注解。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;

    public AuthController(AuthService authService, CurrentUserProvider currentUserProvider) {
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResultVO> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout(currentUserProvider.requireCurrentToken());
        return ApiResponse.success();
    }

    @GetMapping("/me")
    public ApiResponse<UserVO> me() {
        return ApiResponse.success(authService.currentUser(currentUserProvider.requireCurrentUser().userId()));
    }
}
