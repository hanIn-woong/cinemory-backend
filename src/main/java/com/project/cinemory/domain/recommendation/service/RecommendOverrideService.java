package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.service.AdminAuditService;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.RecommendOverride;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.resolver.AuditActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 영화의 추천 제외 덮어쓰기 변경 (controller-layer-spec 5-14-K, M3b-recommendation-spec R-5-1).
 *
 * <p>DB 변경과 감사 로그는 <b>한 트랜잭션</b>이다(둘 다 커밋되거나 둘 다 롤백, admin-spec 11-3). 인덱스·캐시 갱신은 <b>커밋 뒤</b>에 한다 —
 * 롤백된 변경이 추천에 새어 나가지 않게. 전체 재적재 없이 해당 영화의 덮어쓰기만 바꾼 스냅샷으로 교체하고(게스트 전역 캐시는 스냅샷이
 * 바뀌어 자동 재계산) 사용자별 추천 캐시를 비운다. 값이 그대로면 기록하지 않는다(멱등).
 */
@Service
public class RecommendOverrideService {

    private final MovieRepository movieRepository;
    private final AdminAuditService adminAuditService;
    private final MovieFeatureIndex featureIndex;
    private final RecommendationCache cache;
    private final TransactionTemplate transaction;

    public RecommendOverrideService(MovieRepository movieRepository, AdminAuditService adminAuditService,
                                    MovieFeatureIndex featureIndex, RecommendationCache cache,
                                    PlatformTransactionManager transactionManager) {
        this.movieRepository = movieRepository;
        this.adminAuditService = adminAuditService;
        this.featureIndex = featureIndex;
        this.cache = cache;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param override {@code null}이면 덮어쓰기를 해제한다
     * @throws BusinessException {@code MOVIE_NOT_FOUND}(404)
     */
    public void change(long movieId, RecommendOverride override, AuditActor actor) {
        transaction.executeWithoutResult(status -> {
            Movie movie = movieRepository.findById(movieId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.MOVIE_NOT_FOUND));
            RecommendOverride before = movie.getRecommendOverride();
            movie.changeRecommendOverride(override);
            if (!Objects.equals(before, override)) {
                // null까지 명시한다 - "처음 지정"(before=null)과 "해제"(after=null)를 로그에서 구별해야 한다
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("before", before == null ? null : before.name());
                detail.put("after", override == null ? null : override.name());
                adminAuditService.record(actor, AdminAction.MOVIE_RECOMMEND_OVERRIDE, AdminTargetType.MOVIE, movieId, detail);
            }
        });
        featureIndex.refreshOverride(movieId, override);
        cache.evictAllResults();
    }
}
