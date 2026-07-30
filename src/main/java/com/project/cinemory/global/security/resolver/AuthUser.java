package com.project.cinemory.global.security.resolver;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증된 호출자의 {@code userId}를 Controller 파라미터로 주입받는다.
 * 비로그인 요청이면 {@code null}이 들어온다.
 *
 * <pre>
 * public ResponseEntity&lt;?&gt; getUserRecords(@AuthUser Long viewerId, @PathVariable Long userId)
 * </pre>
 *
 * <p><b>{@code @AuthenticationPrincipal}을 쓰지 않는 이유</b> — 익명 요청의 principal은
 * {@code "anonymousUser"}라는 <b>String</b>이다. 전용 타입으로 받으면 타입 불일치로 null이
 * 주입되긴 하지만 이는 {@code errorOnInvalidType=false} 기본값에 기댄 "우연히 동작하는" 코드다.
 * 4-6에서 null을 정상 입력으로 설계한 이상, null 가능성을 의도적으로 표현하는 전용 어노테이션이 낫다.
 *
 * <p><b>{@code required} 기본값이 {@code false}인 이유</b> (S-9 C-2) —
 * {@code authenticated()} 경로는 {@code AuthorizationFilter}가 이미 익명 요청을 막으므로
 * Controller에 null이 도달할 수 없다. 반면 {@code permitAll} 조회 엔드포인트는 다수이고
 * 전부 null을 정상 입력으로 받는다. 기본값이 {@code true}면 흔한 쪽이 매번 예외 표기를
 * 지게 되는 역전이 생긴다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthUser {

    /** {@code true}인데 비로그인이면 {@code UNAUTHORIZED}(401). */
    boolean required() default false;
}
