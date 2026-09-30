package com.fire.protect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fire.protect.domain.Building;
import com.fire.protect.domain.BuildingFloor;
import com.fire.protect.domain.FacilityPointSequence;
import com.fire.protect.repository.BuildingFloorRepository;
import com.fire.protect.repository.BuildingRepository;
import com.fire.protect.repository.FacilityPointSequenceRepository;

/**
 * 点位新增 HTTP 层验收：动作标识必填、编号只认后端返回、同动作重发 200 回放且不新增。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FacilityControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private BuildingFloorRepository buildingFloorRepository;
    @Autowired
    private FacilityPointSequenceRepository sequenceRepository;

    private Long buildingId;
    private Long floorId;
    private String buildingCode;

    @BeforeEach
    void setUp() {
        buildingCode = "H" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        Building b = new Building();
        b.setCode(buildingCode);
        b.setName("HTTP 测试建筑 " + UUID.randomUUID());
        b.setBuildingType("OFFICE");
        b.setFloorsAbove(1);
        b.setFloorsBelow(0);
        b.setCreatedAt(LocalDateTime.now());
        buildingId = buildingRepository.save(b).getId();

        BuildingFloor f = new BuildingFloor();
        f.setBuildingId(buildingId);
        f.setFloorCode("1F");
        f.setFloorName("一层");
        f.setSortNo(1);
        f.setCreatedAt(LocalDateTime.now());
        floorId = buildingFloorRepository.save(f).getId();

        FacilityPointSequence seq = new FacilityPointSequence();
        seq.setBuildingId(buildingId);
        seq.setNextSeq(1L);
        seq.setUpdatedAt(LocalDateTime.now());
        sequenceRepository.save(seq);
    }

    private JsonNode data(ResultActions ra) throws Exception {
        byte[] bytes = ra.andReturn().getResponse().getContentAsByteArray();
        return om.readTree(bytes).get("data");
    }

    private ResultActions create(Object body) throws Exception {
        return mockMvc.perform(post("/api/facilities")
                .contentType(MediaType.APPLICATION_JSON_UTF8)
                .content(om.writeValueAsString(body)));
    }

    private Map<String, Object> body(String actionKey, String type) {
        return Map.of(
                "actionKey", actionKey,
                "buildingId", buildingId,
                "floorId", floorId,
                "facilityType", type,
                "locationDesc", "一层东侧",
                "planX", 500,
                "planY", 340);
    }

    @Test
    void actionKeyRequiredAndReplayReturnsSamePoint() throws Exception {
        String key = "http-fac-" + UUID.randomUUID();
        ResultActions first = create(body(key, "HYDRANT"));
        first.andExpect(status().isOk());
        JsonNode firstData = data(first);
        String firstCode = firstData.get("pointCode").asText();
        long firstId = firstData.get("id").asLong();
        // 编号必须是后端分配的本建筑首个续号
        assertThat(firstCode).isEqualTo("FAC-" + buildingCode + "-0001");

        // 同动作标识重发：200 + 同一条点位，不报错、不占新号
        ResultActions replay = create(body(key, "HYDRANT"));
        replay.andExpect(status().isOk());
        JsonNode replayData = data(replay);
        assertThat(replayData.get("id").asLong()).isEqualTo(firstId);
        assertThat(replayData.get("pointCode").asText()).isEqualTo(firstCode);

        // 缺 actionKey：400，且不建档
        create(Map.of(
                "buildingId", buildingId,
                "floorId", floorId,
                "facilityType", "PUMP",
                "planX", 100,
                "planY", 100)).andExpect(status().isBadRequest());

        // 同动作标识但内容变了：400，不能把另一条点位记到同一动作上
        create(body(key, "PUMP")).andExpect(status().isBadRequest());
    }
}
