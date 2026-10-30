package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.user.dto.FavoriteMovieResponse;
import com.project.cinemory.domain.user.dto.ProfileCoverResponse;
import com.project.cinemory.domain.user.dto.UserResponse;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserFavoriteMovieRepository;
import com.project.cinemory.global.infra.media.ProfileImageUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 프로필 응답의 공통 조립 (profile-spec 3-4) — 인생 영화·커버를 {@code UserResponse}와 {@code UserProfileResponse}가 같은 방식으로 채운다.
 * 호출자의 트랜잭션 안에서 돈다(커버 영화는 지연 로딩).
 */
@Component
@RequiredArgsConstructor
public class ProfileViewAssembler {

    private final UserFavoriteMovieRepository favoriteMovieRepository;
    private final ProfileImageUrlResolver profileImageUrlResolver;

    /** 본인용 {@code UserResponse} — 사진 URL 변환·인생 영화(1쿼리)·커버 포함. */
    public UserResponse userResponse(User user) {
        return UserResponse.from(user, profileImageUrlResolver.resolve(user.getProfileImage()),
                favoriteMovies(user.getId()), cover(user));
    }

    /** position 순, 최대 3편. 영화는 fetch join으로 한 번에 읽는다. */
    public List<FavoriteMovieResponse> favoriteMovies(Long userId) {
        return favoriteMovieRepository.findByUserIdOrderByPosition(userId).stream()
                .map(favorite -> FavoriteMovieResponse.from(favorite.getMovie()))
                .toList();
    }

    /**
     * 커버 응답. 없으면 {@code null}(앱 기본 그라데이션). 영화·업로드가 모두 채워진 비정상 상태(관리 SQL)면 영화를 우선한다 —
     * DB가 막지 못하는 불변식이라(V38) 읽는 쪽이 한 방향으로 정한다.
     */
    public ProfileCoverResponse cover(User user) {
        if (user.hasMovieCover()) {
            var movie = user.getCoverMovie();
            return ProfileCoverResponse.movie(movie.getId(), movie.displayTitle(), movie.getBackdropPath());
        }
        if (user.hasImageCover()) {
            return ProfileCoverResponse.image(profileImageUrlResolver.resolve(user.getCoverImage()));
        }
        return null;
    }
}
