package yumefusaka.envoymart.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AiChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldAnswerWithKnowledgeAndRecommendations() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "demo-session",
                                  "message": "推荐适合学生党的百元内耳机"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reply").isNotEmpty())
                .andExpect(jsonPath("$.data.knowledge[0].title").isNotEmpty())
                .andExpect(jsonPath("$.data.recommendedProducts[0].name").isNotEmpty());
    }

    @Test
    void shouldCallOrderToolForLogisticsQuestion() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(post("/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId": 1,
                                  "quantity": 1
                                }
                                """))
                .andExpect(status().isOk());

        MvcResult orderResult = mockMvc.perform(post("/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "recipientName": "Alice",
                                  "recipientPhone": "13800000000",
                                  "address": "Shanghai Pudong Expo Avenue 1000"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode order = objectMapper.readTree(orderResult.getResponse().getContentAsString()).path("data");
        long orderId = order.path("id").asLong();

        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "demo-session-2",
                                  "contextOrderId": %d,
                                  "message": "帮我查一下这个订单物流到哪了"
                                }
                                """.formatted(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.toolCalls[0].tool").value("logistics_lookup"))
                .andExpect(jsonPath("$.data.reply").isNotEmpty());
    }

    private String loginAndGetToken() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "alice",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(loginResult.getResponse().getContentAsString()).path("data").path("token").asText();
    }
}
