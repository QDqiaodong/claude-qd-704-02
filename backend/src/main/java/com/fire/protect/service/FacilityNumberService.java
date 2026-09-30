package com.fire.protect.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.fire.protect.common.ConflictException;
import com.fire.protect.domain.Building;
import com.fire.protect.domain.FacilityPointSequence;
import com.fire.protect.domain.FireDict;
import com.fire.protect.repository.BuildingRepository;
import com.fire.protect.repository.FacilityPointRepository;
import com.fire.protect.repository.FacilityPointSequenceRepository;

/**
 * 点位编号分配器（模块二专用）。
 *
 * <p>编号格式保持历史口径：{@code FAC-<建筑编号>-<四位序号>}，如 FAC-B02-0032。
 * 序号由 {@code facility_point_sequence} 流水表在数据库行锁内原子递增分配：
 * <ul>
 *   <li>同建筑并发新增：在 InnoDB 行锁上排队，各拿各的号，绝不撞号；</li>
 *   <li>多应用实例：锁在数据库里，进程内没有任何锁/缓存；</li>
 *   <li>删除点位：流水只进不退，旧编号不会被重新分配；</li>
 *   <li>分配与点位保存处于同一事务：保存失败时推进一并回滚，不留空号半成品
 *       （正常提交后产生的尾号空缺属于已消耗序号，同样不会回收）。</li>
 * </ul>
 */
@Service
public class FacilityNumberService {

    private final FacilityPointSequenceRepository sequenceRepository;
    private final FacilityPointRepository facilityPointRepository;
    private final BuildingRepository buildingRepository;

    public FacilityNumberService(FacilityPointSequenceRepository sequenceRepository,
                                 FacilityPointRepository facilityPointRepository,
                                 BuildingRepository buildingRepository) {
        this.sequenceRepository = sequenceRepository;
        this.facilityPointRepository = facilityPointRepository;
        this.buildingRepository = buildingRepository;
    }

    /**
     * 分配下一个点位编号。调用方必须处于事务中，行锁与序号推进在事务提交前一直有效。
     *
     * @return 形如 FAC-B02-0032 的完整编号
     */
    public String nextPointCode(Long buildingId) {
        FacilityPointSequence sequence = loadOrInit(buildingId);
        long seq = sequence.getNextSeq();
        sequence.setNextSeq(seq + 1);
        sequence.setUpdatedAt(LocalDateTime.now());
        // 锁内立刻 flush：推进随事务提交生效，锁随提交释放
        sequenceRepository.saveAndFlush(sequence);
        return codePrefix(buildingId) + String.format("%04d", seq);
    }

    /** 建筑编号（取不到时沿用历史的 DEF 兜底） */
    public String codePrefix(Long buildingId) {
        String buildingCode = "DEF";
        if (buildingId != null) {
            Building building = buildingRepository.findById(buildingId).orElse(null);
            if (building != null) {
                buildingCode = building.getCode();
            }
        }
        return FireDict.FACILITY_CODE_PREFIX + "-" + buildingCode + "-";
    }

    /**
     * 锁定该建筑的流水行；首次使用（建筑在编号机制上线前就存在、且本实例第一次取号）
     * 时按既有编号最大尾号惰性建基线。
     */
    private FacilityPointSequence loadOrInit(Long buildingId) {
        return sequenceRepository.findForUpdateByBuildingId(buildingId)
                .orElseGet(() -> initialize(buildingId));
    }

    /**
     * 惰性初始化：基线 = 该建筑既有编号的最大尾号 + 1（与历史「最大尾号 +1」口径一致），
     * 没有任何点位时从 1 开始。唯一约束兜底并发初始化（两个实例同时首取）：
     * 抢输的一方锁胜者建出的行继续取号；胜者若回滚则有限次重试，绝不返回空。
     */
    private FacilityPointSequence initialize(Long buildingId) {
        DataIntegrityViolationException collision = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<FacilityPointSequence> locked = sequenceRepository.findForUpdateByBuildingId(buildingId);
            if (locked.isPresent()) {
                return locked.get();
            }
            long max = 0L;
            if (buildingId != null) {
                for (String code : facilityPointRepository.findAllByBuildingId(buildingId,
                        org.springframework.data.domain.Sort.by("id"))
                        .stream().map(p -> p.getPointCode()).toList()) {
                    max = Math.max(max, tailSequence(code));
                }
            }
            FacilityPointSequence sequence = new FacilityPointSequence();
            sequence.setBuildingId(buildingId);
            sequence.setNextSeq(max + 1);
            sequence.setUpdatedAt(LocalDateTime.now());
            try {
                sequenceRepository.saveAndFlush(sequence);
                return sequence;
            } catch (DataIntegrityViolationException e) {
                // 另一实例抢先建了基线：下一轮锁它的行继续取号
                collision = e;
            }
        }
        throw new ConflictException(
                "点位编号流水初始化冲突，请用同一动作标识稍后重试（本次未生成点位与编号）", collision);
    }

    /** 取编号尾部四位序号，非数字（导入的历史数据）按 0 计；与历史口径保持一致 */
    private long tailSequence(String code) {
        if (code == null || code.length() < 4) {
            return 0L;
        }
        try {
            return Long.parseLong(code.substring(code.length() - 4));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
