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
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 내 기록 내보내기 (settings-spec ST-5). 두 경로의 접두어가 달라(/api/users/me, /api/export) 클래스에 {@code @RequestMapping}을 두지 않는다.
 *
 * <p>다운로드는 <b>인증 헤더가 없다</b> — 앱이 브라우저로 여는 링크라서 토큰이 인증이다. 그래서 {@code SecurityConfig}
 * 화이트리스트에 이 GET 한 경로만 올라가 있다. 토큰이 URL에 있으므로 Nginx가 접근 로그를 끄고, 응답은
 * {@code Referrer-Policy: no-referrer}·{@code Cache-Control: no-store}를 단다.
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
    public ResponseEntity<StreamingResponseBody> download(@RequestParam(required = false) String token) {
        return tokenService.consume(token)
                .map(this::zipResponse)
                .orElseGet(ExportController::gone);
    }

    private ResponseEntity<StreamingResponseBody> zipResponse(Long userId) {
        // 데이터는 응답을 열기 전에 읽는다 — 읽기 전용 트랜잭션은 여기서 끝나고, 쓰기만 스트리밍한다
        ExportData data = exportService.load(userId);
        String filename = "cinemory-export-" + LocalDate.now(clock).format(FILE_DATE) + ".zip";
        StreamingResponseBody body = out -> exportService.writeZip(data, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /**
     * 410도 {@code StreamingResponseBody}로 쓴다 — 핸들러가 {@code ResponseEntity<?>}를 돌려주면 Spring이 본문을 일반 객체로 보고
     * 메시지 컨버터를 찾다가 실패(500)한다. 반환 타입을 {@code ResponseEntity<StreamingResponseBody>}로 고정하는 이유다.
     */
    private static ResponseEntity<StreamingResponseBody> gone() {
        return ResponseEntity.status(HttpStatus.GONE)
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .body(out -> out.write(GONE_HTML.getBytes(StandardCharsets.UTF_8)));
    }
}
