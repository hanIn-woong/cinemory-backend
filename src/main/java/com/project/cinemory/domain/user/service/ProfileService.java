package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.user.dto.ProfileUpdateRequest;
import com.project.cinemory.domain.user.dto.UserResponse;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.entity.UserFavoriteMovie;
import com.project.cinemory.domain.user.repository.UserFavoriteMovieRepository;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.exception.ErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 프로필 통합 저장 {@code PUT /api/users/me/profile} (profile-spec PR-1 · 3-1) — 전체 치환.
 * 이미지(사진·업로드 커버)는 별도 업로드 흐름이라 여기서 다루지 않는다. 모든 검증을 끝낸 뒤에 상태를 바꾼다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileService {

    static final int MAX_FAVORITES = 3;

    private final UserRepository userRepository;
    private final MovieRepository movieRepository;
    private final UserFavoriteMovieRepository favoriteMovieRepository;
    private final ImageUploadService imageUploadService;
    private final ProfileViewAssembler profileViewAssembler;

    @Transactional
    public UserResponse updateProfile(Long userId, ProfileUpdateRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 1. 닉네임은 현재 값과 다를 때만 규칙을 적용한다 — 규칙 도입 전의 20자 초과 닉네임 사용자가 다른 필드를 저장할 수 있어야 한다(PT-5)
        String nickname = request.nickname().strip().equals(user.getNickname())
                ? user.getNickname()
                : NicknamePolicy.validate(request.nickname());
        // 2. 자기소개
        String bio = BioPolicy.normalize(request.bio());
        // 3. 인생 영화 — 0~3개, 중복 없음, 모두 존재
        List<Long> favoriteIds = request.favoriteMovieIds();
        validateFavoriteIds(favoriteIds);
        Map<Long, Movie> moviesById = movieRepository.findAllById(favoriteIds).stream()
                .collect(Collectors.toMap(Movie::getId, Function.identity()));
        if (moviesById.size() != favoriteIds.size()) {
            throw new BusinessException(ErrorCode.MOVIE_NOT_FOUND);
        }
        // 4. 커버 영화 — null이거나 위 목록 안에 있고 배경 이미지가 있는 영화
        Movie coverMovie = resolveCoverMovie(request.coverMovieId(), moviesById);

        // 5. 필드 갱신
        user.updateProfile(nickname, bio, request.privacySetting());

        // 6. 커버 — 영화로 바꾸면 업로드 커버는 해제되고 이전 키는 커밋 뒤 삭제된다. null이면 영화 커버만 해제한다(업로드 커버는 건드리지 않는다)
        if (coverMovie != null) {
            String oldImage = user.getCoverImage();
            user.changeCoverToMovie(coverMovie);
            imageUploadService.publishReplacedIfOurs(userId, oldImage, null);
        } else if (user.hasMovieCover()) {
            user.clearCover();
        }

        // 7. 인생 영화 교체 — 벌크 삭제가 먼저 즉시 실행돼야 한다. 엔티티 delete()+save()는 INSERT가 먼저 flush되어 UNIQUE를 위반한다
        favoriteMovieRepository.deleteAllByUserId(userId);
        List<UserFavoriteMovie> favorites = new ArrayList<>(favoriteIds.size());
        for (int i = 0; i < favoriteIds.size(); i++) {
            favorites.add(UserFavoriteMovie.of(user, moviesById.get(favoriteIds.get(i)), i + 1));
        }
        favoriteMovieRepository.saveAll(favorites);

        // 8. 응답
        return profileViewAssembler.userResponse(user);
    }

    private static void validateFavoriteIds(List<Long> ids) {
        if (ids.size() > MAX_FAVORITES) {
            throw invalid("favoriteMovieIds", "인생 영화는 " + MAX_FAVORITES + "편까지 고를 수 있습니다.");
        }
        if (ids.stream().anyMatch(id -> id == null)) {
            throw invalid("favoriteMovieIds", "영화 id가 비어 있습니다.");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw invalid("favoriteMovieIds", "같은 영화를 두 번 고를 수 없습니다.");
        }
    }

    private static Movie resolveCoverMovie(Long coverMovieId, Map<Long, Movie> favorites) {
        if (coverMovieId == null) {
            return null;
        }
        Movie movie = favorites.get(coverMovieId);
        if (movie == null || movie.getBackdropPath() == null || movie.getBackdropPath().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_COVER_MOVIE);
        }
        return movie;
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_INPUT_VALUE, List.of(new ErrorResponse.FieldError(field, reason)));
    }
}
