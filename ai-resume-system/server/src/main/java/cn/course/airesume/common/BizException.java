package cn.course.airesume.common;

/** 业务异常：message 直接面向用户展示（指出具体字段/原因）。 */
public class BizException extends RuntimeException {
    public BizException(String message) {
        super(message);
    }
}
