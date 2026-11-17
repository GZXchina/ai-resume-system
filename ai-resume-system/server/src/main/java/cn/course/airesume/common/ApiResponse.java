package cn.course.airesume.common;

/** 统一返回体：{ code: 0, data } 成功；{ code: 1, message } 失败（与前端约定一致）。 */
public class ApiResponse<T> {
    private int code;
    private T data;
    private String message;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 0;
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> fail(String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 1;
        r.message = message;
        return r;
    }

    public int getCode() { return code; }
    public T getData() { return data; }
    public String getMessage() { return message; }
}
