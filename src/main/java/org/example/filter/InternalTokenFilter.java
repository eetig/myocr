package org.example.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.example.config.OcrProperties;
import org.example.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 调用方口令闸门（2026-10-15 code-review 的 Critical 3）。
 *
 * <p>挡的是「`/api/ocr/recognize` 是通往腾讯云付费接口的免鉴权代理」这件事：
 * 每次调用都会真实计费、单次最长占线程 70 秒，任何人都能拿它放大成资源与费用消耗。
 *
 * <p><b>可开关是刻意的</b>：调用方是已发行的手机 App，强制校验会让线上立刻 401。
 * 因此只在 {@code ocr.internal-token} 配置了值时才校验；留空即完全保持原行为。
 * 启用步骤与影响见 {@link OcrProperties#getInternalToken()}。
 *
 * <p>注意这不是"完整的鉴权"：它是**共享口令**，只能挡住"知道域名就能刷"的调用方。
 * 真正的边界仍应在网络层（Nginx/网关只把该路径开放给需要的来源）。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);

    /** 口令头名：与手机端 {@code src/api/config.js} 的约定一致 */
    public static final String HEADER = "X-Internal-Token";

    private static final String PROTECTED_PREFIX = "/api/ocr";

    private final OcrProperties properties;

    private final ObjectMapper objectMapper;

    public InternalTokenFilter(OcrProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String expected = properties.getInternalToken();
        if (StringUtils.hasText(expected) && request.getRequestURI().startsWith(PROTECTED_PREFIX)) {
            String actual = request.getHeader(HEADER);
            if (!expected.equals(actual)) {
                // 只记"谁没带对"，不记口令本身（日志也是泄露面）
                log.warn("调用方口令校验失败, uri={}, remote={}", request.getRequestURI(),
                        request.getRemoteAddr());
                writeUnauthorized(response);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 响应体与本服务其它错误同形（{@code Result} 的 code/message/data），便于调用方统一处理 */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.error(401, "未授权：缺少或错误的 " + HEADER)));
    }
}
