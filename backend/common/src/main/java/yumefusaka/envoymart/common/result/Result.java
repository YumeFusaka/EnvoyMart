package yumefusaka.envoymart.common.result;

import lombok.Data;

import java.io.Serializable;

@Data
public class Result<T> implements Serializable {

    private Integer code;
    private String msg;
    private T data;

    public static <T> Result<T> success() {
        Result<T> result = new Result<>();
        result.code = 200;
        return result;
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.code = 200;
        result.data = data;
        return result;
    }

    /**
     * 业务错误。<b>code 要如实反映错误性质</b>：一律返回 500 会让调用方分不清
     * 「你请求的路径/参数不对」（客户端问题，重试无用）和「服务端真的挂了」。
     */
    public static <T> Result<T> error(int code, String msg) {
        Result<T> result = new Result<>();
        result.code = code;
        result.msg = msg;
        return result;
    }

    public static <T> Result<T> error(String msg) {
        return error(500, msg);
    }

    public static <T> Result<T> noToken(String msg) {
        Result<T> result = new Result<>();
        result.code = 401;
        result.msg = msg;
        return result;
    }
}
