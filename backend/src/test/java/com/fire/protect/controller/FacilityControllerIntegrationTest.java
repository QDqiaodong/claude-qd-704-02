package com.fire.protect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 新增点位接口的 HTTP 层验收：
 * 缺 actionKey 拒绝（400）；正常创建返回后端实际编号；同动作重发回放同一编号（200）；
 * 同动作不同内容 409 且提示可安全重试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FacilityControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper om;

    private MvcResult create(String userId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/facilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body))
                        .header("X-User-Id", userId))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return om.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void createEndpointEnforcesActionKeyReplayAndConflict() throws Exception {
        String actionKey = UUID.randomUUID().toString();

        // 1) 缺 actionKey：400，不生成点位
        Map<String, Object> noKey = Map.of(
                "buildingId", 99, "floorId", 9901, "facilityType", "PUMP",
                "locationDesc", "无动作标识", "planX", 500, "planY", 340);
        mockMvc.perform(post("/api/facilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(noKey))
                        .header("X-User-Id", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));

        // 2) 正常创建：ok=true，编号为后端分配的 FAC-B99-xxxx
        Map<String, Object> body = Map.of(
                "actionKey", actionKey,
                "buildingId", 99, "floorId", 9902, "facilityType", "PUMP",
                "locationDesc", "接口测试消防泵", "planX", 510, "planY", 350);
        MvcResult first = create("1", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstJson = json(first);
        assertThat(firstJson.get("ok").asBoolean()).isTrue();
        String code = firstJson.get("data").get("pointCode").asText();
        long pointId = firstJson.get("data").get("id").asLong();
        assertThat(code).startsWith("FAC-B99-");
        assertThat(firstJson.get("message").asText()).contains(code);

        // 3) 同动作重发（模拟超时后重试）：200 回放同一 id / 编号，不新建
        MvcResult replay = create("1", body);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayJson = json(replay);
        assertThat(replayJson.get("ok").asBoolean()).isTrue();
        assertThat(replayJson.get("data").get("id").asLong()).isEqualTo(pointId);
        assertThat(replayJson.get("data").get("pointCode").asText()).isEqualTo(code);
        assertThat(replayJson.get("message").asText()).contains("未重复占用编号");

        // 4) 同动作不同内容：409 冲突，提示换新动作标识（可安全重试）
        Map<String, Object> tampered = Map.of(
                "actionKey", actionKey,
                "buildingId", 99, "floorId", 9902, "facilityType", "PUMP",
                "locationDesc", "内容被改过", "planX", 510, "planY", 350);
        mockMvc.perform(post("/api/facilities")
                        .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                        .content(om.writeValueAsString(tampered))
                        .header("X-User-Id", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false));
    }
}
