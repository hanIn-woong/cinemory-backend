package com.project.cinemory.domain.collection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CollectionUpdateRequest(

        @NotBlank(message = "컬렉션 이름은 필수입니다.")
        @Size(max = 50, message = "컬렉션 이름은 50자를 넘을 수 없습니다.")
        String name,

        @Size(max = 500, message = "설명은 500자를 넘을 수 없습니다.")
        String description
) {
}
