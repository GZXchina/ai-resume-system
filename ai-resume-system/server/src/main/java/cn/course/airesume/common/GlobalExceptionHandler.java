package cn.course.airesume.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> biz(BizException e) {
        return ApiResponse.fail(e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> badBody(HttpMessageNotReadableException e) {
        return ApiResponse.fail("请求体格式不正确");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> tooLarge(MaxUploadSizeExceededException e) {
        return ApiResponse.fail("文件不能超过 5MB");
    }

    /**
     * HTTP 方法不支持（如对 /api/analyses/{id} 发 DELETE）：返回 405，而不是兜底成 500，
     * 便于前端与测试区分「接口不存在/方法不对」与「服务端异常」。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("不支持的请求方法：{} {}", e.getMethod(), e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail("不支持的请求方法：" + e.getMethod()));
    }

    /** 静态资源/接口路径不存在：返回 404，而不是 200 + 错误体。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("请求的资源不存在：" + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> unknown(Exception e) {
        // 未知异常只记日志，不把堆栈细节暴露给前端
        log.error("未处理异常", e);
        return ApiResponse.fail("系统繁忙，请稍后重试");
    }
}
