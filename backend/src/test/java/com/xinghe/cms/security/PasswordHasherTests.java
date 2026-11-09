package com.xinghe.cms.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTests {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashUsesDeclaredFormatAndVerifiesOriginalPassword() {
        String encoded = hasher.hash("Str0ng-Passw0rd");
        assertTrue(encoded.startsWith("pbkdf2-sha256$210000$"));
        assertTrue(encoded.length() < 255, "哈希编码必须能存入 password_hash VARCHAR(255)");
        assertTrue(hasher.matches("Str0ng-Passw0rd", encoded));
    }

    @Test
    void wrongPasswordAndTamperedEncodingNeverMatch() {
        String encoded = hasher.hash("correct horse");
        assertFalse(hasher.matches("correct horsf", encoded));
        assertFalse(hasher.matches("correct horse ", encoded), "密码不 trim");
        assertFalse(hasher.matches(" correct horse", encoded), "密码不 trim");
        assertFalse(hasher.matches("correct horse", encoded + "x"));
        assertFalse(hasher.matches("correct horse", "not-a-hash"));
        assertFalse(hasher.matches(null, encoded));
        assertFalse(hasher.matches("correct horse", null));
    }

    @Test
    void eachHashUsesFreshSalt() {
        assertNotEquals(hasher.hash("same-password"), hasher.hash("same-password"));
    }

    @Test
    void longAndUnicodePasswordsAreNotTruncated() {
        String longPassword = "a".repeat(127) + "B";
        String encoded = hasher.hash(longPassword);
        assertTrue(hasher.matches(longPassword, encoded));
        assertFalse(hasher.matches("a".repeat(128), encoded), "最后一个字符不同必须校验失败");

        String unicode = "口令🔐Pässwörd";
        assertTrue(hasher.matches(unicode, hasher.hash(unicode)));
    }

    @Test
    void oversizedIterationCountIsRejectedBeforeDoingExpensiveWork() {
        String encoded = hasher.hash("pw");
        assertFalse(hasher.matches("pw", encoded.replace("$210000$", "$999999999$")),
                "库中哈希被篡改成超大迭代次数时不得触发 CPU 拒绝服务");
        String[] parts = encoded.split("\\$", -1);
        assertFalse(hasher.matches("pw", parts[0] + "$" + parts[1] + "$" + parts[2] + "$AAAA"),
                "摘要长度不符合 256 位格式时必须拒绝");
    }
}
