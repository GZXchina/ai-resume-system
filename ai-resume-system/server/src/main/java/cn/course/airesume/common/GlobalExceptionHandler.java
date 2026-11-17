package cn.course.airesume.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> unknown(Exception e) {
        // 未知异常只记日志，不把堆栈细节暴露给前端
        log.error("未处理异常", e);
        return ApiResponse.fail("系统繁忙，请稍后重试");
    }
}
