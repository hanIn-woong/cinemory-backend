package com.project.cinemory.domain.export.controller;

import com.project.cinemory.domain.export.dto.ExportLinkResponse;
import com.project.cinemory.domain.export.service.ExportRateLimiter;
import com.project.cinemory.domain.export.service.ExportService;
import com.project.cinemory.domain.export.service.ExportService.ExportData;
import com.project.cinemory.domain.export.service.ExportTokenService;
import com.project.cinemory.domain.export.service.ExportTokenService.IssuedToken;
import com.project.cinemory.global.security.resolver.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 내 기록 내보내기 (settings-spec ST-5). 두 경로의 접두어가 달라(/api/users/me, /api/export) 클래스에 {@code @RequestMapping}을 두지 않는다.
 *
 * <p>다운로드는 <b>인증 헤더가 없다</b> — 앱이 브라우저로 여는 링크라서 토큰이 인증이다. 그래서 {@code SecurityConfig}
 * 화이트리스트에 이 GET 한 경로만 올라가 있다. 토큰이 URL에 있으므로 Nginx가 접근 로그를 끄고, 응답은
 * {@code Referrer-Policy: no-referrer}·{@code Cache-Control: no-store}를 단다.
 *
 * <p><b>다운로드는 {@code StreamingResponseBody}(비동기)가 아니라 요청 스레드가 응답 출력 스트림에 직접 쓴다.</b> 비동기로 두면 요청 스레드가
 * 필터 체인을 빠져나가며 Spring Security의 {@code HeaderWriterFilter}가 헤더를 쓰는 동안 별도 스레드가 본문·헤더를 써서
 * 응답 헤더 맵을 동시에 건드린다 — 테스트에서 {@code ConcurrentModificationException}으로 간헐 실패했다. ZIP은 수천 행 수준의
 * 작은 파일이라 요청 스레드를 점유해도 문제가 없고, 쓰기는 여전히 {@code ZipOutputStream}으로 흘려보낸다.
 */
@RestController
@RequiredArgsConstructor
public class ExportController {

    public static final String DOWNLOAD_PATH = "/api/export/download";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** 브라우저에 열리는 응답이라 JSON이 아니다. 실패 사유(없음·만료·사용됨)는 구분하지 않는다. */
    private static final String GONE_HTML = """
            <!doctype html>
            <html lang="ko">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex">
            <title>CineMory</title>
            <style>body{font-family:sans-serif;margin:0;padding:48px 24px;text-align:center;color:#222}</style>
            </head>
            <body>
            <p>링크가 만료됐어요. 앱에서 다시 시도해 주세요.</p>
            </body>
            </html>
            """;

    private final ExportRateLimiter rateLimiter;
    private final ExportTokenService tokenService;
    private final ExportService exportService;
    private final Clock clock;

    @Operation(summary = "내 기록 내보내기 링크 발급 (1회용 · 10분 · 1분에 1회)",
            description = "응답의 downloadUrl을 브라우저로 열면 ZIP(CSV 4개)을 받는다. 같은 사용자 1분에 1회 — 초과 시 429 + Retry-After.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/users/me/export")
    public ResponseEntity<ExportLinkResponse> issueLink(@AuthUser(required = true) Long userId) {
        rateLimiter.acquire(userId);
        IssuedToken issued = tokenService.issue(userId);
        // forward-headers-strategy: native 라 운영에서는 Nginx가 넘긴 https 호스트로 만들어진다
        String url = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(DOWNLOAD_PATH).queryParam("token", issued.token()).build().toUriString();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ExportLinkResponse(url, issued.expiresAt()));
    }

    @Operation(summary = "내보내기 ZIP 다운로드 (인증 없음 — 토큰이 인증, 1회용)",
            description = "성공 200 application/zip. 토큰이 없거나 만료됐거나 이미 쓰였으면 구분 없이 410 + 짧은 HTML.")
    @GetMapping(DOWNLOAD_PATH)
    public void download(@RequestParam(required = false) String token, HttpServletResponse response) throws IOException {
        Optional<Long> userId = tokenService.consume(token);
        if (userId.isEmpty()) {
            gone(response);
            return;
        }

        // 데이터는 응답을 열기 전에 읽는다 — 읽기 전용 트랜잭션은 여기서 끝나고, 쓰기만 스트리밍한다
        ExportData data = exportService.load(userId.get());
        String filename = "cinemory-export-" + LocalDate.now(clock).format(FILE_DATE) + ".zip";
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString());
        privateHeaders(response);
        exportService.writeZip(data, response.getOutputStream());
    }

    private static void gone(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_GONE);
        response.setContentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8).toString());
        privateHeaders(response);
        response.getOutputStream().write(GONE_HTML.getBytes(StandardCharsets.UTF_8));
    }

    /** 토큰이 URL에 있는 응답이라 캐시·리퍼러로 새지 않게 한다(성공·실패 공통). */
    private static void privateHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }
}
