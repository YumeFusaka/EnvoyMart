package yumefusaka.envoymart.promotionservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassWriter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import yumefusaka.envoymart.common.operation.OperationContext;
import yumefusaka.envoymart.common.operation.OperationReceiptMetadata;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.promotionservice.service.OperationReceiptService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OperationReceiptControllerBindingTest {
    @Test
    void receiptQueryBindsWithoutCompilerParameterMetadata() throws Exception {
        var receipts = mock(OperationReceiptService.class);
        String hash = "a".repeat(64);
        var expected = OperationContext.expected("u1", "operation:1", "coupon_receive", hash);
        when(receipts.replay(expected, Object.class)).thenReturn(Optional.empty());
        Class<?> type = withoutParameterMetadata();
        assertFalse(type.getDeclaredMethod("find", String.class, String.class, String.class, String.class)
                .getParameters()[1].isNamePresent());
        Object controller = type.getConstructor(OperationReceiptService.class).newInstance(receipts);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/coupons/internal/operation-receipts/operation:1")
                        .header(IdentityHeaderInterceptor.USER_ID_HEADER, "u1")
                        .param("toolName", "coupon_receive").param("parametersHash", hash))
                .andExpect(status().isOk())
                .andExpect(header().string(OperationReceiptMetadata.OPERATION_ID_HEADER, "operation:1"))
                .andExpect(header().string(OperationReceiptMetadata.PARAMETERS_HASH_HEADER, hash))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":404")));
        verify(receipts).replay(expected, Object.class);
    }

    /** 剥离构建器可选调试元数据，固定已复现的HTTP契约回归。 */
    private Class<?> withoutParameterMetadata() throws Exception {
        String name = OperationReceiptController.class.getName();
        byte[] original;
        try (var source = getClass().getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            original = source.readAllBytes();
        }
        var rewritten = new ClassWriter(0);
        new ClassReader(original).accept(rewritten, ClassReader.SKIP_DEBUG);
        byte[] bytes = rewritten.toByteArray();
        class ControllerLoader extends ClassLoader {
            ControllerLoader() { super(OperationReceiptControllerBindingTest.class.getClassLoader()); }
            Class<?> load() { return defineClass(name, bytes, 0, bytes.length); }
        }
        return new ControllerLoader().load();
    }
}
