package com.project.cinemory.domain.collection.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CollectionOrderRequest(

        // 전체 순서를 한 번에 보낸다(5-4-A ②). 상한은 5-0 max-page-size와 같은 성격의 방어다.
        @NotEmpty(message = "컬렉션 순서 목록은 비어 있을 수 없습니다.")
        @Size(max = 200, message = "컬렉션은 최대 200개까지 정렬할 수 있습니다.")
        List<Long> collectionIds
) {
}
