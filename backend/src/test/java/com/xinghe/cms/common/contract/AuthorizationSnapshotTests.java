package com.xinghe.cms.common.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 授权快照契约测试：构造时防御性复制、返回集合不可修改、不允许 null 集合。
 */
class AuthorizationSnapshotTests {

    @Test
    void 构造时复制入参集合_外部修改不影响快照() {
        Set<String> roles = new HashSet<>(Set.of("EDITOR"));
        Set<String> permissions = new HashSet<>(Set.of("article:update"));

        AuthorizationSnapshot snapshot = new AuthorizationSnapshot(roles, permissions);
        roles.add("ADMIN");
        permissions.add("article:publish");

        assertEquals(Set.of("EDITOR"), snapshot.enabledRoleCodes());
        assertEquals(Set.of("article:update"), snapshot.permissionCodes());
    }

    @Test
    void 返回的集合不可修改() {
        AuthorizationSnapshot snapshot =
                new AuthorizationSnapshot(Set.of("EDITOR"), Set.of("article:update"));

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.enabledRoleCodes().add("ADMIN"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.permissionCodes().clear());
    }

    @Test
    void 不接受null集合() {
        assertThrows(NullPointerException.class,
                () -> new AuthorizationSnapshot(null, Set.of()));
        assertThrows(NullPointerException.class,
                () -> new AuthorizationSnapshot(Set.of(), null));
    }

    @Test
    void 允许空集合_表示用户无有效角色或权限() {
        AuthorizationSnapshot snapshot = new AuthorizationSnapshot(Set.of(), Set.of());

        assertTrue(snapshot.enabledRoleCodes().isEmpty());
        assertTrue(snapshot.permissionCodes().isEmpty());
    }

    @Test
    void 集合按值相等_两个相同内容的快照相等() {
        AuthorizationSnapshot first = new AuthorizationSnapshot(Set.of("ADMIN"), Set.of("role:list"));
        AuthorizationSnapshot second = new AuthorizationSnapshot(new HashSet<>(Set.of("ADMIN")), Set.of("role:list"));

        assertNotSame(first.enabledRoleCodes(), second.enabledRoleCodes());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}
