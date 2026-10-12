package yumefusaka.envoymart.orderservice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import yumefusaka.envoymart.common.operation.OperationContext;
import yumefusaka.envoymart.common.operation.OperationReceiptReply;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.orderservice.service.OperationReceiptService;
import java.util.Set;

@RestController
@RequestMapping("/orders/internal/operation-receipts")
public class OperationReceiptController {
    private static final Set<String> TOOLS = Set.of("cart_add", "cart_update", "cart_remove", "cart_checkout", "order_cancel", "after_sale_apply", "ticket_create");
    private final OperationReceiptService receipts;
    public OperationReceiptController(OperationReceiptService receipts) { this.receipts = receipts; }
    @GetMapping("/{operationId}")
    public ResponseEntity<Result<Object>> find(@RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String userId,
            @PathVariable("operationId") String operationId, @RequestParam("toolName") String toolName,
            @RequestParam("parametersHash") String parametersHash) {
        if (!TOOLS.contains(toolName)) throw new IllegalArgumentException("订单服务不支持该写工具");
        var operation = OperationContext.expected(userId, operationId, toolName, parametersHash);
        return receipts.replay(operation, Object.class)
                .map(reply -> reply.response()).orElseGet(() -> OperationReceiptReply.notFound(operation));
    }
}
