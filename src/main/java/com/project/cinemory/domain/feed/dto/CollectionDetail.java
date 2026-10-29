package com.project.cinemory.domain.feed.dto;

import java.util.List;

/** 컬렉션의 <b>지금 상태</b> — 이름·편수·미리보기 포스터(최대 4장). */
public record CollectionDetail(Long collectionId, String name, long movieCount, List<String> previewPosters) {
}
