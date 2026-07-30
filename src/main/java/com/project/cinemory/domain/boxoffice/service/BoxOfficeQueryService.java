package com.project.cinemory.domain.boxoffice.service;

import com.project.cinemory.domain.boxoffice.dto.BoxOfficeResponse;
import com.project.cinemory.domain.boxoffice.entity.BoxOfficeRecord;
import com.project.cinemory.domain.boxoffice.entity.RankType;
import com.project.cinemory.domain.boxoffice.repository.BoxOfficeRecordRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 박스오피스 조회.
 *
 * <p>공용 데이터이므로 {@code UserAccessPolicy} 적용 대상이 아니며 {@code viewerId}를 받지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoxOfficeQueryService {

    private final BoxOfficeRecordRepository boxOfficeRecordRepository;

    /**
     * @param targetDate null이면 해당 구분의 최신 집계일을 사용한다.
     *                   (수집 배치가 아직 돌지 않은 시각에 클라이언트가 '오늘'을 넘겨 빈 화면을 보는 것을 방지)
     */
    public BoxOfficeResponse getBoxOffice(RankType rankType, LocalDate targetDate) {
        LocalDate resolvedDate = targetDate != null
                ? targetDate
                : boxOfficeRecordRepository.findLatestTargetDate(rankType)
                        .orElseThrow(() -> new BusinessException(ErrorCode.BOX_OFFICE_NOT_FOUND));

        List<BoxOfficeRecord> records =
                boxOfficeRecordRepository.findByTargetDateAndRankTypeOrderByBoxOfficeRankAsc(resolvedDate, rankType);

        if (records.isEmpty()) {
            throw new BusinessException(ErrorCode.BOX_OFFICE_NOT_FOUND);
        }

        return BoxOfficeResponse.from(resolvedDate, rankType, records);
    }
}
