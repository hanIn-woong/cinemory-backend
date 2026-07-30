package com.project.cinemory.domain.wish.service;

import com.project.cinemory.domain.movie.dto.CountryResponse;
import com.project.cinemory.domain.movie.dto.GenreResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieCountryRepository;
import com.project.cinemory.domain.movie.repository.MovieGenreRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.wish.dto.WishListItemResponse;
import com.project.cinemory.domain.wish.dto.WishToggleResponse;
import com.project.cinemory.domain.wish.entity.WishMovie;
import com.project.cinemory.domain.wish.repository.WishMovieRepository;
import com.project.cinemory.global.access.UserAccessPolicy;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WishMovieService {

    private final WishMovieRepository wishMovieRepository;
    private final MovieRepository movieRepository;
    private final UserRepository userRepository;
    private final MovieGenreRepository movieGenreRepository;
    private final MovieCountryRepository movieCountryRepository;
    private final UserAccessPolicy userAccessPolicy;

    @Transactional
    public WishToggleResponse toggleWish(Long userId, Long movieId) {
        Movie movie = movieRepository.findById(movieId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOVIE_NOT_FOUND));

        return wishMovieRepository.findByUserIdAndMovieId(userId, movieId)
                .map(existing -> {
                    wishMovieRepository.delete(existing);
                    return new WishToggleResponse(false);
                })
                .orElseGet(() -> {
                    wishMovieRepository.save(WishMovie.of(userRepository.getReferenceById(userId), movie));
                    return new WishToggleResponse(true);
                });
    }

    public boolean isWished(Long userId, Long movieId) {
        return wishMovieRepository.existsByUserIdAndMovieId(userId, movieId);
    }

    /** 위시리스트 — 타인의 프로필에서도 호출되므로 공개범위 검증이 선행된다. */
    public Page<WishListItemResponse> getUserWishList(Long viewerId, Long targetUserId, Pageable pageable) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);

        Page<WishMovie> wishPage = wishMovieRepository.findByUserIdOrderByIdDesc(targetUserId, pageable);
        List<Long> movieIds = wishPage.getContent().stream()
                .map(wishMovie -> wishMovie.getMovie().getId())
                .toList();

        Map<Long, List<GenreResponse>> genresByMovieId = movieGenreRepository.findByMovieIdIn(movieIds).stream()
                .collect(Collectors.groupingBy(
                        movieGenre -> movieGenre.getMovie().getId(),
                        Collectors.mapping(movieGenre -> GenreResponse.from(movieGenre.getGenre()), Collectors.toList())
                ));

        Map<Long, List<CountryResponse>> countriesByMovieId = movieCountryRepository.findByMovieIdIn(movieIds).stream()
                .collect(Collectors.groupingBy(
                        movieCountry -> movieCountry.getMovie().getId(),
                        Collectors.mapping(movieCountry -> CountryResponse.from(movieCountry.getCountry()), Collectors.toList())
                ));

        return wishPage.map(wishMovie -> WishListItemResponse.from(
                wishMovie,
                genresByMovieId.getOrDefault(wishMovie.getMovie().getId(), List.of()),
                countriesByMovieId.getOrDefault(wishMovie.getMovie().getId(), List.of())
        ));
    }
}
