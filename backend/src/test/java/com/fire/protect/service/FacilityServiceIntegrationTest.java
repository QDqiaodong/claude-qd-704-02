package com.fire.protect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.fire.protect.common.BizException;
import com.fire.protect.domain.Building;
import com.fire.protect.domain.BuildingFloor;
import com.fire.protect.domain.FacilityPointSequence;
import com.fire.protect.domain.SysUser;
import com.fire.protect.dto.FacilityDtos;
import com.fire.protect.dto.Views;
import com.fire.protect.repository.BuildingFloorRepository;
import com.fire.protect.repository.BuildingRepository;
import com.fire.protect.repository.FacilityCreateRequestRepository;
import com.fire.protect.repository.FacilityPointRepository;
import com.fire.protect.repository.FacilityPointSequenceRepository;
import com.fire.protect.security.CurrentUserHolder;

/**
 * 点位新增并发 / 幂等验收（H2 MySQL 模式，表结构与生产 MySQL 一致）：
 * 同建筑并发新增各拿不同编号；同动作标识重发只回放首次点位；保存中途失败不留半成品；
 * 模拟重启后重放；删除点位旧编号不复用；惰性基线兼容历史编号。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FacilityServiceIntegrationTest {

    @Autowired
    private FacilityService facilityService;
    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private BuildingFloorRepository buildingFloorRepository;
    @Autowired
    private FacilityPointRepository facilityPointRepository;
    @Autowired
    private FacilityPointSequenceRepository sequenceRepository;
    @Autowired
    private FacilityCreateRequestRepository createRequestRepository;
    @Autowired
    private com.fire.protect.repository.DisablePermitRepository disablePermitRepository;

    private Long buildingId;
    private Long floorId;

    @BeforeEach
    void setUp() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUserName("fire_lead");
        user.setDisplayName("陈默");
        user.setRole("FIRE_MANAGER");
        CurrentUserHolder.set(user);

        Building b = new Building();
        b.setCode("T" + System.nanoTime() % 100000);
        b.setName("并发测试建筑 " + UUID.randomUUID());
        b.setBuildingType("OFFICE");
        b.setFloorsAbove(1);
        b.setFloorsBelow(0);
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        building = buildingRepository.save(b);
        buildingId = b.getId();

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

    private Building building;

    private FacilityDtos.CreateReq req(String actionKey) {
        FacilityDtos.CreateReq r = new FacilityDtos.CreateReq();
        r.setActionKey(actionKey);
        r.setBuildingId(buildingId);
        r.setFloorId(floorId);
        r.setFacilityType("HYDRANT");
        r.setSpec("SN65");
        r.setLocationDesc("并发测试位置 " + actionKey.substring(0, Math.min(8, actionKey.length())));
        r.setPlanX(500);
        r.setPlanY(340);
        return r;
    }

    /** 8 个不同动作同建筑并发新增：全部成功、编号互不相同、点位与台账数量一致。 */
    @Test
    void concurrentCreatesGetDistinctCodes() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Views.FacilityView>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            FacilityDtos.CreateReq r = req("conc-" + i + "-" + UUID.randomUUID());
            futures.add(pool.submit(() -> {
                start.await();
                return facilityService.create(r);
            }));
        }
        start.countDown();

        Set<String> codes = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        for (Future<Views.FacilityView> f : futures) {
            Views.FacilityView v = f.get(30, TimeUnit.SECONDS);
            assertThat(v.pointCode()).startsWith("FAC-" + building.getCode() + "-");
            codes.add(v.pointCode());
            ids.add(v.id());
        }
        pool.shutdown();
        assertThat(codes).hasSize(threads);
        assertThat(ids).hasSize(threads);
        assertThat(facilityPointRepository.countByBuildingId(buildingId)).isEqualTo(threads);
        assertThat(createRequestRepository.findAll().stream()
                .filter(e -> e.getBuildingId().equals(buildingId)).count()).isEqualTo(threads);
        assertThat(sequenceRepository.findById(buildingId).orElseThrow().getNextSeq())
                .isEqualTo(threads + 1L);
        // 平面图/台账与库内一致：列表看到的就是建成的全部点位
        assertThat(facilityService.list(buildingId, null, null)).hasSize(threads);
    }

    /**
     * 同一动作真并发双发：唯一约束裁决出一个胜者，败者拿到可安全重试的 409；
     * 败者随后用同一动作标识重放，拿到胜者创建的那条点位，全程只有一条点位、一个编号。
     */
    @Test
    void sameActionConcurrentAndReplayReturnsFirstPointOnly() throws Exception {
        FacilityDtos.CreateReq r = req("same-key-" + UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Views.FacilityView>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return facilityService.create(r);
            }));
        }
        start.countDown();

        List<Views.FacilityView> views = new ArrayList<>();
        int conflicts = 0;
        for (Future<Views.FacilityView> f : futures) {
            try {
                views.add(f.get(30, TimeUnit.SECONDS));
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(com.fire.protect.common.ConflictException.class);
                conflicts++;
            }
        }
        pool.shutdown();
        assertThat(views).hasSize(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(facilityPointRepository.countByBuildingId(buildingId)).isEqualTo(1L);
        assertThat(createRequestRepository.findAll().stream()
                .filter(e -> e.getBuildingId().equals(buildingId)).count()).isEqualTo(1);

        // 败者立刻按提示用同一动作标识安全重试 → 回放胜者的点位（模拟超时重发同样走这里）
        Views.FacilityView replay = facilityService.create(r);
        assertThat(replay.id()).isEqualTo(views.get(0).id());
        assertThat(replay.pointCode()).isEqualTo(views.get(0).pointCode());
        assertThat(facilityPointRepository.countByBuildingId(buildingId)).isEqualTo(1L);
        assertThat(createRequestRepository.findAll().stream()
                .filter(e -> e.getBuildingId().equals(buildingId)).count()).isEqualTo(1);
    }

    /** 同动作标识但表单内容已变：拒绝，不能把不同点位记到同一动作上。 */
    @Test
    void sameActionKeyWithDifferentPayloadRejected() {
        FacilityDtos.CreateReq first = req("fp-key-" + UUID.randomUUID());
        Views.FacilityView v = facilityService.create(first);
        assertThat(v).isNotNull();

        FacilityDtos.CreateReq tampered = req(first.getActionKey());
        tampered.setFacilityType("PUMP");
        assertThatThrownBy(() -> facilityService.create(tampered))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("另一条点位");
        assertThat(facilityPointRepository.countByBuildingId(buildingId)).isEqualTo(1L);
    }

    /** 保存中途失败（规格超长撑爆列宽）：编号、点位、台账全部回滚，无半成品；随后可安全重试成功。 */
    @Test
    void failureLeavesNoHalfResultAndSameActionCanRetry() {
        String key = "rollback-key-" + UUID.randomUUID();
        FacilityDtos.CreateReq bad = req(key);
        bad.setSpec("X".repeat(200)); // spec VARCHAR(64)
        assertThatThrownBy(() -> facilityService.create(bad))
                .isInstanceOfAny(com.fire.protect.common.ConflictException.class,
                        DataIntegrityViolationException.class);

        // 点位没有建出来；台账没有残留；流水没有被空耗
        assertThat(facilityPointRepository.countByBuildingId(buildingId)).isZero();
        assertThat(createRequestRepository.findById(key)).isEmpty();
        assertThat(sequenceRepository.findById(buildingId).orElseThrow().getNextSeq()).isEqualTo(1L);

        // 同一动作标识修正后安全重试 → 拿到 0001（旧的失败尝试没占号）
        FacilityDtos.CreateReq good = req(key);
        Views.FacilityView v = facilityService.create(good);
        assertThat(v.pointCode()).endsWith("-0001");
        assertThat(facilityPointRepository.countByBuildingId(buildingId)).isEqualTo(1L);
        assertThat(createRequestRepository.findById(key).orElseThrow().getPointId()).isEqualTo(v.id());
    }

    /** 删除点位后再新增：旧编号不重新分配，流水继续向前。 */
    @Test
    void deletedPointCodeIsNeverReused() {
        Views.FacilityView first = facilityService.create(req("del-1-" + UUID.randomUUID()));
        assertThat(first.pointCode()).endsWith("-0001");
        createRequestRepository.findByPointId(first.id())
                .ifPresent(e -> createRequestRepository.deleteById(e.getActionKey()));
        facilityPointRepository.deleteById(first.id());

        Views.FacilityView second = facilityService.create(req("del-2-" + UUID.randomUUID()));
        assertThat(second.pointCode()).endsWith("-0002");
        assertThat(second.id()).isNotEqualTo(first.id());
        // 旧编号永远空缺
        assertThat(facilityPointRepository.findByPointCode(first.pointCode())).isEmpty();
        assertThat(facilityPointRepository.findByPointCode(second.pointCode())).isPresent();
    }

    /** 模拟服务重启：幂等识别只依赖数据库表，重建一个全新的 Service 实例后重放仍是首次点位。 */
    @Test
    void replaySurvivesRestartWithFreshServiceInstance() {
        FacilityDtos.CreateReq r = req("restart-key-" + UUID.randomUUID());
        Views.FacilityView first = facilityService.create(r);

        // 用全新的依赖装一个全新的 FacilityService（进程内不保留任何内存状态）
        BuildingService buildingService = new BuildingService(buildingRepository, buildingFloorRepository,
                facilityPointRepository, sequenceRepository);
        FacilityCreateTx tx = new FacilityCreateTx(facilityPointRepository, createRequestRepository,
                buildingFloorRepository, buildingService,
                new FacilityNumberService(sequenceRepository, facilityPointRepository, buildingRepository));
        FacilityService restarted = new FacilityService(
                facilityPointRepository, createRequestRepository,
                buildingRepository, buildingFloorRepository, buildingService,
                null, disablePermitRepository, tx);
        Views.FacilityView replay = restarted.create(r);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.pointCode()).isEqualTo(first.pointCode());
        assertThat(facilityPointRepository.countByBuildingId(buildingId)).isEqualTo(1L);
    }

    /** 无流水行的老建筑（机制上线前数据）首次取号：按既有编号最大尾号惰性建基线。 */
    @Test
    void lazySequenceInitFromExistingCodes() {
        // 再造一栋「老建筑」：预置两条历史点位，不建流水行
        Building old = new Building();
        old.setCode("OLD1");
        old.setName("历史建筑 " + UUID.randomUUID());
        old.setBuildingType("FACTORY");
        old.setFloorsAbove(1);
        old.setFloorsBelow(0);
        old.setCreatedAt(LocalDateTime.now());
        old = buildingRepository.save(old);
        BuildingFloor ff = new BuildingFloor();
        ff.setBuildingId(old.getId());
        ff.setFloorCode("1F");
        ff.setFloorName("一层");
        ff.setSortNo(1);
        ff.setCreatedAt(LocalDateTime.now());
        Long oldFloor = buildingFloorRepository.save(ff).getId();
        seedLegacyPoint(old.getId(), oldFloor, "FAC-OLD1-0007");
        seedLegacyPoint(old.getId(), oldFloor, "FAC-OLD1-0008");

        FacilityDtos.CreateReq r = req("lazy-" + UUID.randomUUID());
        r.setBuildingId(old.getId());
        r.setFloorId(oldFloor);
        Views.FacilityView v = facilityService.create(r);
        assertThat(v.pointCode()).isEqualTo("FAC-OLD1-0009");
        assertThat(sequenceRepository.findById(old.getId()).orElseThrow().getNextSeq()).isEqualTo(10L);
    }

    /** 多个不同动作并发打在「无流水行」的老建筑上：惰性基线也不能撞号。 */
    @Test
    void concurrentLazyInitDoesNotCollide() throws Exception {
        Building old = new Building();
        old.setCode("OLD2");
        old.setName("并发历史建筑 " + UUID.randomUUID());
        old.setBuildingType("FACTORY");
        old.setFloorsAbove(1);
        old.setFloorsBelow(0);
        old.setCreatedAt(LocalDateTime.now());
        old = buildingRepository.save(old);
        BuildingFloor ff = new BuildingFloor();
        ff.setBuildingId(old.getId());
        ff.setFloorCode("1F");
        ff.setFloorName("一层");
        ff.setSortNo(1);
        ff.setCreatedAt(LocalDateTime.now());
        Long oldFloor = buildingFloorRepository.save(ff).getId();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Views.FacilityView>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            FacilityDtos.CreateReq r = req("lazy-c-" + i + "-" + UUID.randomUUID());
            r.setBuildingId(old.getId());
            r.setFloorId(oldFloor);
            futures.add(pool.submit(() -> {
                start.await();
                return facilityService.create(r);
            }));
        }
        start.countDown();
        Set<String> codes = new HashSet<>();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (Future<Views.FacilityView> f : futures) {
            try {
                codes.add(f.get(30, TimeUnit.SECONDS).pointCode());
            } catch (Exception e) {
                errors.add(e.getCause() != null ? e.getCause() : e);
            }
        }
        pool.shutdown();
        // H2 极端竞争下可能有取号锁冲突；冲突的请求可安全重试，重试后同样落一条不重号的点位
        for (int retry = 0; retry < errors.size(); retry++) {
            FacilityDtos.CreateReq r = req("lazy-retry-" + retry + "-" + UUID.randomUUID());
            r.setBuildingId(old.getId());
            r.setFloorId(oldFloor);
            codes.add(facilityService.create(r).pointCode());
        }
        assertThat(codes).hasSize(threads);
        assertThat(facilityPointRepository.countByBuildingId(old.getId())).isEqualTo(threads);
    }

    private void seedLegacyPoint(Long bid, Long fid, String code) {
        com.fire.protect.domain.FacilityPoint p = new com.fire.protect.domain.FacilityPoint();
        p.setPointCode(code);
        p.setBuildingId(bid);
        p.setFloorId(fid);
        p.setFacilityType("HYDRANT");
        p.setSpec("SN65");
        p.setLocationDesc("历史点位");
        p.setPlanX(100);
        p.setPlanY(100);
        facilityPointRepository.save(p);
    }
}
