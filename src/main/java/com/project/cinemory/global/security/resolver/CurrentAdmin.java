package com.project.cinemory.global.security.resolver;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 관리자 Controller 파라미터({@link AuditActor})에 인증된 관리자와 요청 IP를 주입받는다(admin-spec 11-3).
 *
 * <pre>
 * public ResponseEntity&lt;?&gt; getUser(@PathVariable Long userId, @CurrentAdmin AuditActor actor)
 * </pre>
 *
 * <p>스펙은 어노테이션을 {@code @AuditActor}로 적었지만 행위자 레코드가 같은 이름({@code AuditActor})이라 한 패키지에 둘 수 없어
 * 어노테이션을 {@code @CurrentAdmin}으로 했다. {@code /api/admin/**}은 {@code hasRole('ADMIN')}이 먼저 막으므로 여기서는
 * 인증 주체가 항상 있고, 없으면 {@code UNAUTHORIZED}(코딩 실수 이중 방어)다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentAdmin {
}
