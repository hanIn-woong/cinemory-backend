package com.project.cinemory.global.security.resolver;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.AuthUserPrincipal;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link AuthUser}가 붙은 파라미터에 인증 주체의 {@code userId}를 주입한다.
 *
 * <p><b>반드시 {@code global/config/WebConfig}에 등록해야 한다.</b> 등록을 빠뜨리면 예외 없이
 * 조용히 {@code null}이 주입돼(또는 요청 파라미터로 오인돼) 원인 추적이 까다롭다.
 */
@Component
public class AuthUserArgumentResolver implements HandlerMethodArgumentResolver {

    /**
     * 타입까지 조건에 넣지 않고 어노테이션 유무만 본다.
     *
     * <p>타입 불일치 시 {@code false}를 반환하면 Spring이 해당 파라미터를 요청 파라미터 등으로
     * 해석하려 들어 엉뚱한 곳에서 실패한다. 여기서 받아 {@link #resolveArgument}가 명시적으로
     * 거부하는 편이 원인이 훨씬 빨리 드러난다.
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AuthUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        if (!Long.class.equals(parameter.getParameterType())) {
            // 클라이언트 잘못이 아니라 코딩 실수이므로 400이 아니라 500으로 터뜨린다.
            // 특히 primitive long은 null을 담을 수 없어 비로그인 계약과 양립하지 않는다.
            throw new IllegalStateException(
                    "@AuthUser는 Long 타입 파라미터에만 사용할 수 있습니다. 실제 타입: "
                            + parameter.getParameterType().getName());
        }

        Long userId = currentUserId();

        AuthUser annotation = parameter.getParameterAnnotation(AuthUser.class);
        if (userId == null && annotation != null && annotation.required()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 비로그인이면 {@code null}.
     *
     * <p><b>{@code authentication != null}로 판정하면 안 된다.</b>
     * {@code AnonymousAuthenticationFilter}가 익명 요청에도
     * {@code AnonymousAuthenticationToken}(principal = {@code "anonymousUser"} String)을 채우기
     * 때문에 그 조건은 항상 참이다. principal의 <b>타입</b>으로 판정해야 한다.
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthUserPrincipal principal)) {
            return null;
        }
        return principal.userId();
    }
}
