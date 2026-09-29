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
     * 错误返回。<b>code 必须由调用方显式给出。</b>
     * <p>
     * 刻意不提供 {@code error(String)} 这种省略错误码的重载：默认值只能取一个，
     * 取 500 会把「你请求的参数不对」（客户端问题，重试一万次也没用）和
     * 「服务端真的挂了」压成同一个码；取 400 又会把真正的服务端故障说成客户端问题。
     * <b>错误码的含义只有调用现场知道，就不该给它默认值。</b>
     */
    public static <T> Result<T> error(int code, String msg) {
        Result<T> result = new Result<>();
        result.code = code;
        result.msg = msg;
        return result;
    }

    public static <T> Result<T> noToken(String msg) {
        Result<T> result = new Result<>();
        result.code = 401;
        result.msg = msg;
        return result;
    }
}
