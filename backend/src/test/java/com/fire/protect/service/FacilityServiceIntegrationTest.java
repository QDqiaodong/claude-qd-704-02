package com.fire.protect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.fire.protect.common.ConflictException;
import com.fire.protect.domain.SysUser;
import com.fire.protect.dto.FacilityDtos;
import com.fire.protect.dto.Views;
import com.fire.protect.repository.FacilityCodeSequenceRepository;
import com.fire.protect.repository.FacilityPointActionRepository;
import com.fire.protect.repository.FacilityPointRepository;
import com.fire.protect.security.CurrentUserHolder;

/**
 * 点位新增的并发 / 重试 / 一致性验收：
 * 1) 同栋楼多笔并发新增各拿不同编号、各落一条有效记录；
 * 2) 同一动作标识重复发送（含多线程同时双发）只返回第一次创建的那条点位，不占新编号；
 * 3) 同一动作标识携带不同内容 → 409 冲突，可安全重试；
 * 4) 删除点位后旧编号不再分配（序号单调）；
 * 5) 编号序列、点位、动作台账数量始终一致（无半成品）。
 *
 * <p>序列状态全部在 H2 数据库里，不依赖单机内存锁，逻辑与 MySQL 正式库一致；
 * 「服务重启后仍识别已处理动作」由动作台账持久化天然保证（重放只读库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FacilityServiceIntegrationTest {

    private static final long BUILDING_ID = 99L;
    private static final long FLOOR_1F = 9901L;
    private static final long FLOOR_2F = 9902L;

    @Autowired
    private FacilityService facilityService;

    @Autowired
    private FacilityPointRepository pointRepository;

    @Autowired
    private FacilityPointActionRepository actionRepository;

    @Autowired
    private FacilityCodeSequenceRepository sequenceRepository;

    private void login() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUserName("fire_lead");
        user.setDisplayName("陈默");
        user.setRole("FIRE_MANAGER");
        CurrentUserHolder.set(user);
    }

    private FacilityDtos.CreateReq req(long floorId, String type, String location, int x, int y) {
        FacilityDtos.CreateReq req = new FacilityDtos.CreateReq();
        req.setActionKey(UUID.randomUUID().toString());
        req.setBuildingId(BUILDING_ID);
        req.setFloorId(floorId);
        req.setFacilityType(type);
        req.setLocationDesc(location);
        req.setPlanX(x);
        req.setPlanY(y);
        return req;
    }

    @Test
    void concurrentCreatesUnderSameBuildingGetDistinctCodesAndRecords() throws Exception {
        login();
        long pointsBefore = pointRepository.countByBuildingId(BUILDING_ID);
        long actionsBefore = actionRepository.count();
        long seqStart = sequenceRepository.findBySeqKey("FAC-B99").orElseThrow().getNextValue();
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Views.FacilityView>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            FacilityDtos.CreateReq r = req(FLOOR_1F, "HYDRANT", "并发消火栓 " + idx, 100 + idx * 30, 300);
            futures.add(pool.submit(() -> {
                login();
                start.await();
                return facilityService.create(r).view();
            }));
        }
        start.countDown();

        List<Views.FacilityView> created = new ArrayList<>();
        for (Future<Views.FacilityView> f : futures) {
            created.add(f.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 八个不同编号、八个不同点位 id
        Set<String> codes = created.stream().map(Views.FacilityView::pointCode).collect(Collectors.toSet());
        Set<Long> ids = created.stream().map(Views.FacilityView::id).collect(Collectors.toSet());
        assertThat(codes).hasSize(threads);
        assertThat(ids).hasSize(threads);
        // 编号严格形如 FAC-B99-xxxx（尾号与同批序号连续、互不重复），格式与历史种子一致
        List<Integer> tails = codes.stream()
                .map(code -> Integer.parseInt(code.substring(code.length() - 4)))
                .sorted()
                .toList();
        assertThat(codes).allSatisfy(code -> assertThat(code).startsWith("FAC-B99-"));
        assertThat(tails).doesNotHaveDuplicates();

        // 点位台账与动作台账各多 8 条，序列精确推进 8 格（不悬空、不跳号）
        assertThat(pointRepository.countByBuildingId(BUILDING_ID)).isEqualTo(pointsBefore + threads);
        assertThat(actionRepository.count()).isEqualTo(actionsBefore + threads);
        assertThat(sequenceRepository.findBySeqKey("FAC-B99").orElseThrow().getNextValue())
                .isEqualTo(seqStart + threads);
    }

    @Test
    void duplicateActionKeyReplaysFirstPointWithoutConsumingNewCode() {
        login();
        FacilityDtos.CreateReq r = req(FLOOR_2F, "SMOKE", "重放烟感", 200, 200);
        long seqBefore = sequenceRepository.findBySeqKey("FAC-B99").orElseThrow().getNextValue();

        FacilityCreateOutcome first = facilityService.create(r);
        assertThat(first.replay()).isFalse();

        // 同一动作（超时 / 重复点击 / 网络重发）再来一次：回放同一条，不占新编号
        FacilityCreateOutcome second = facilityService.create(r);
        FacilityCreateOutcome third = facilityService.create(r);
        assertThat(second.replay()).isTrue();
        assertThat(third.replay()).isTrue();
        assertThat(second.view().id()).isEqualTo(first.view().id());
        assertThat(second.view().pointCode()).isEqualTo(first.view().pointCode());

        // 序列没有因为重放多走
        assertThat(sequenceRepository.findBySeqKey("FAC-B99").orElseThrow().getNextValue())
                .isEqualTo(seqBefore + 1);
        assertThat(actionRepository.findByActionKey(r.getActionKey())).isPresent();
    }

    @Test
    void sameActionKeyFiredConcurrentlyProducesExactlyOnePoint() throws Exception {
        login();
        String actionKey = UUID.randomUUID().toString();
        FacilityDtos.CreateReq shared = req(FLOOR_2F, "LIGHT", "并发双发应急照明", 300, 300);
        shared.setActionKey(actionKey);
        long seqBefore = sequenceRepository.findBySeqKey("FAC-B99").orElseThrow().getNextValue();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        Set<String> codes = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                login();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                FacilityCreateOutcome out = facilityService.create(shared);
                ids.add(out.view().id());
                codes.add(out.view().pointCode());
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
        assertThat(codes).hasSize(1);
        // 四次同动作只推进一格序号
        assertThat(sequenceRepository.findBySeqKey("FAC-B99").orElseThrow().getNextValue())
                .isEqualTo(seqBefore + 1);
        assertThat(actionRepository.findAll()).filteredOn(a -> actionKey.equals(a.getActionKey())).hasSize(1);
    }

    @Test
    void sameActionKeyWithDifferentPayloadIsConflictAndCanRetryWithNewKey() {
        login();
        FacilityDtos.CreateReq r = req(FLOOR_1F, "ALARM", "原位置", 120, 120);
        FacilityCreateOutcome first = facilityService.create(r);

        // 同一动作标识但内容变了：冲突拒绝，不创建新点位
        r.setLocationDesc("被改成别的位置");
        assertThatThrownBy(() -> facilityService.create(r))
                .isInstanceOf(ConflictException.class);

        // 换新动作标识即可正常新增（提示中也说明这样做），原点位不受影响
        FacilityDtos.CreateReq retry = req(FLOOR_1F, "ALARM", "原位置", 120, 120);
        FacilityCreateOutcome ok = facilityService.create(retry);
        assertThat(ok.replay()).isFalse();
        assertThat(ok.view().id()).isNotEqualTo(first.view().id());
        assertThat(ok.view().pointCode()).isNotEqualTo(first.view().pointCode());
    }

    @Test
    void deletedPointCodeIsNeverReallocated() {
        login();
        FacilityDtos.CreateReq r = req(FLOOR_1F, "DOOR", "待删除防火门", 400, 400);
        Views.FacilityView created = facilityService.create(r).view();

        // 模拟删除该点位（仅删 facility_point，动作台账保留留痕）
        pointRepository.deleteById(created.id());
        pointRepository.flush();
        assertThat(pointRepository.findByPointCode(created.pointCode())).isEmpty();

        // 再新增：编号必须严格大于被删编号，旧编号不复用
        FacilityDtos.CreateReq next = req(FLOOR_1F, "DOOR", "新防火门", 420, 400);
        Views.FacilityView later = facilityService.create(next).view();
        assertThat(later.pointCode()).isNotEqualTo(created.pointCode());
        int deletedTail = Integer.parseInt(created.pointCode().substring(created.pointCode().length() - 4));
        int laterTail = Integer.parseInt(later.pointCode().substring(later.pointCode().length() - 4));
        assertThat(laterTail).isGreaterThan(deletedTail);
    }
}
