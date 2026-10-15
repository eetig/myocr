package org.example.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.OcrProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 调用方口令闸门的行为测试（2026-10-15 code-review 的 Critical 3 回归防线）。
 *
 * <p>两条要害：① **没配口令时行为必须与改造前完全一致**（否则本地联调与线上灰度会立刻 401）；
 * ② 配了口令后，缺头/错头必须在**进 controller 之前**被拒。断言的是状态码与响应体形状，
 * 而不是"某句话在源码里"。
 */
class InternalTokenFilterTest {

    private static MockHttpServletResponse run(String configuredToken, String sentToken) throws Exception {
        OcrProperties properties = new OcrProperties();
        properties.setInternalToken(configuredToken);
        InternalTokenFilter filter = new InternalTokenFilter(properties, new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ocr/recognize");
        if (sentToken != null) {
            request.addHeader(InternalTokenFilter.HEADER, sentToken);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("未配置口令 = 不校验（可开关的默认态：本地联调与灰度期行为不变）")
    void skipsWhenTokenNotConfigured() throws Exception {
        assertEquals(200, run(null, null).getStatus());
        assertEquals(200, run("", null).getStatus());
        assertEquals(200, run("   ", null).getStatus());
    }

    @Test
    @DisplayName("配置了口令：缺失或错误 → 401，响应体仍是本服务统一的 Result 形状")
    void rejectsMissingOrWrongToken() throws Exception {
        MockHttpServletResponse missing = run("s3cret", null);
        assertEquals(401, missing.getStatus());
        String body = missing.getContentAsString();
        assertTrue(body.contains("401"), "响应体应带业务码 401：" + body);
        assertTrue(body.contains("未授权"), "响应体应说明未授权：" + body);

        assertEquals(401, run("s3cret", "wrong").getStatus());
    }

    @Test
    @DisplayName("配置了口令且带对 → 放行（过滤器不消费请求）")
    void passesWithCorrectToken() throws Exception {
        assertEquals(200, run("s3cret", "s3cret").getStatus());
    }
}
