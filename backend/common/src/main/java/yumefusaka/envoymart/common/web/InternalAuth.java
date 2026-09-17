package yumefusaka.envoymart.common.web;

import java.nio.charset.StandardCharsets;

/**
 * 服务间调用凭证 —— 网关与下游共用同一份定义。
 * <p>
 * 客户端的 JWT 解决的是「你是谁」，这份令牌解决的是「这个身份声明是不是网关给的」。
 * 两者分开而不是复用 {@code JWT_SECRET}：一个泄漏了另一个还能顶住，而且职责清楚——
 * 前者面向用户、会过期、要能撤销；后者面向进程、由环境变量注入、只在启动时读一次。
 */
public final class InternalAuth {

    /** 凭证头名称 —— 由网关在转发时注入，下游据此判断身份声明是否可信 */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    /** 至少 32 字节：与 JWT_SECRET 同级，太短的共享密钥可以被暴力枚举 */
    private static final int MIN_BYTES = 32;

    private InternalAuth() {
    }

    /**
     * 启动时校验令牌，<b>缺失或过短直接拒绝启动</b>。
     * <p>
     * 不设默认值：写在仓库里的默认令牌等于没有防护，任何读过源码的人都能伪造成网关。
     * 网关与下游都调用它——网关是注入方，拿不到令牌就无法把身份传下去；
     * 下游是校验方，拿不到令牌就无法判断来的身份是真是假。两边缺一，链路都不成立。
     */
    public static String requireValid(String token) {
        int length = token == null ? 0 : token.getBytes(StandardCharsets.UTF_8).length;
        if (length < MIN_BYTES) {
            throw new IllegalStateException(
                    "INTERNAL_TOKEN 未配置或长度不足：至少需要 " + MIN_BYTES + " 字节，当前 " + length + " 字节。"
                            + "它用于校验服务间调用的身份头，缺失时下游无法区分"
                            + "「网关转发的身份」与「调用方自己伪造的身份」。"
                            + "请通过环境变量 INTERNAL_TOKEN 提供，例如 `openssl rand -hex 32`。");
        }
        return token;
    }
}
