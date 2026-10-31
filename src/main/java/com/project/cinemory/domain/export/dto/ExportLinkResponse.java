package com.project.cinemory.domain.export.dto;

import java.time.LocalDateTime;

/** 내보내기 1회용 다운로드 링크(settings-spec ST-5). {@code expiresAt}은 서버 시계(KST 로컬) 기준이다. */
public record ExportLinkResponse(String downloadUrl, LocalDateTime expiresAt) {
}
