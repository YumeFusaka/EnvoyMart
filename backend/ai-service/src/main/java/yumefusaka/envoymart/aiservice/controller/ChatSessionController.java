package yumefusaka.envoymart.aiservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yumefusaka.envoymart.agent.memory.ShortTermMemoryStore;
import yumefusaka.envoymart.aiservice.memory.ChatHistoryStore;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 会话管理接口 —— 对话界面侧栏的「列、读、删」。
 * <p>
 * <b>身份只认网关注入的头</b>：{@code X-User-Id} 由网关从签了名的 Token 里取，
 * 且会先剥掉客户端自带的同名头（见 {@code IdentityHeaderInterceptor}）。
 * 所有查询按该 userId 做键命名空间隔离，跨用户的会话在这里不可能被读到 ——
 * 不需要一个「再比对一次 owner」的分支，那种分支忘写一次就是越权。
 * <p>
 * <b>为什么删除连短期记忆窗口一起清</b>：窗口（{@code stm:}）存的是这段会话的
 * 上下文消息，删了会话却留着窗口，等于给未来某个复用同一 sessionId 的请求
 * 留了一段它不该看见的上下文。删除的语义是「这段对话不存在了」，要清就清干净。
 * 长期记忆（画像、情节）不动 —— 那是跨会话的，不属于被删的那段对话。
 */
@Slf4j
@RestController
@RequestMapping("/ai/sessions")
public class ChatSessionController {

    /**
     * 客户端生成的会话号形如 {@code alice-1759280000000}。
     * 它不是安全凭据（键命名空间才是），但会拼进 Redis 键，收一个格式受限的短串
     * 能防住「超长键名」与「包含冒号的键名互相污染」这两类运维事故。
     */
    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");

    private static final int SESSION_LIST_LIMIT = 50;
    private static final int MESSAGE_LIST_LIMIT = 200;

    private final ChatHistoryStore history;
    private final ShortTermMemoryStore shortTermMemory;

    public ChatSessionController(ChatHistoryStore history, ShortTermMemoryStore shortTermMemory) {
        this.history = history;
        this.shortTermMemory = shortTermMemory;
    }

    @GetMapping
    public Result<List<ChatHistoryStore.SessionSummary>> sessions(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId) {
        return Result.success(history.listSessions(userId, SESSION_LIST_LIMIT));
    }

    @GetMapping("/{sessionId}/messages")
    public Result<List<ChatHistoryStore.StoredMessage>> messages(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("sessionId") String sessionId) {
        if (!SESSION_ID.matcher(sessionId).matches()) {
            return Result.error(400, "会话标识不合法");
        }
        return Result.success(history.loadMessages(userId, sessionId, MESSAGE_LIST_LIMIT));
    }

    /**
     * 删除会话。删不到必须如实报 404 而不是回成功 ——
     * 回成功的话，用户刷新后看到会话还在，会以为界面在骗他（而确实是）。
     * <p>
     * 上面两处 {@code @PathVariable} 都<b>显式写了名字</b>，不是啰嗦：省略名字时
     * Spring 只能反射字节码里的参数名，而那个名字来自编译器的 {@code -parameters} 标志 ——
     * 它只由某一条构建路径提供。同一个 {@code target/classes} 目录里可能同时有两个
     * 构建器（命令行 Maven 带该标志，IDE 的自动构建不带），谁最后写谁说了算：
     * 表现是**接口整条失效**且与请求无关 —— 报「Name for argument of type
     * [java.lang.String] not specified」，而这句话听着像参数写错了，实际是
     * 那个类文件里根本没有名字。这个类是全仓最后两处省略名字的地方（其余全部显式命名），
     * 收掉之后运行期不再依赖任何编译标志。判据在构建产物里就能看见：
     * {@code javap -v} 出来还有没有 {@code MethodParameters}。
     */
    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("sessionId") String sessionId) {
        if (!SESSION_ID.matcher(sessionId).matches()) {
            return Result.error(400, "会话标识不合法");
        }
        if (!history.delete(userId, sessionId)) {
            return Result.error(404, "会话不存在或已被删除");
        }
        // 窗口键是 scoped 过的（`stm:{userId}|{sessionId}`），不能拿裸 sessionId 去清 ——
        // 那样清的是一个从未存在过的键，接口照样回 200，实际这段上下文还留着
        shortTermMemory.clear(ShortTermMemoryStore.scoped(userId, sessionId));
        log.info("[History] 会话已删除 user={} session={}", userId, sessionId);
        return Result.success();
    }
}
