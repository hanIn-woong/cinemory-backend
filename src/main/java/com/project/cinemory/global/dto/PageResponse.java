package com.project.cinemory.global.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * {@code Page}를 그대로 반환하지 않기 위한 자체 페이징 응답 DTO(5-0-D).
 *
 * <p>Spring Data가 {@code PageImpl} 직렬화의 JSON 구조 안정성을 보장하지 않는다고
 * 명시하고 있어 API 계약으로 쓸 수 없고, {@code pageable.sort.*} 등 클라이언트가 쓰지 않는
 * 프레임워크 내부 필드가 응답마다 따라붙는 것도 막는다.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}