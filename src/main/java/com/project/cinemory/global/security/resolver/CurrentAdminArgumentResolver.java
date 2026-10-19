package com.project.cinemory.global.security.resolver;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.AuthUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentAdmin}이 붙은 {@link AuditActor} 파라미터에 관리자 ID와 요청 IP를 주입한다.
 *
 * <p><b>반드시 {@code global/config/WebConfig}에 등록해야 한다</b> — {@link AuthUserArgumentResolver}와 같은 이유로, 빠뜨리면
 * 예외 없이 요청 파라미터로 오인된다.
 */
@Component
public class CurrentAdminArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAdmin.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        if (!AuditActor.class.equals(parameter.getParameterType())) {
            throw new IllegalStateException("@CurrentAdmin은 AuditActor 타입 파라미터에만 사용할 수 있습니다. 실제 타입: "
                    + parameter.getParameterType().getName());
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthUserPrincipal principal)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return new AuditActor(principal.userId(), request == null ? null : request.getRemoteAddr());
    }
}
