package com.fire.protect.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fire.protect.domain.FacilityCodeSequence;

public interface FacilityCodeSequenceRepository extends JpaRepository<FacilityCodeSequence, Long> {

    Optional<FacilityCodeSequence> findBySeqKey(String seqKey);

    boolean existsBySeqKey(String seqKey);

    /**
     * 原子自增序列值：{@code UPDATE ... SET next_value = next_value + 1}。
     *
     * <p>不依赖 SELECT ... FOR UPDATE（H2 对两个加锁读的互斥语义与 InnoDB 不一致），
     * 而是直接以「行级写锁」串行化同键并发：MySQL InnoDB 与 H2 都保证对同一行的
     * 并发 UPDATE 互斥，后到的事务阻塞到先到者提交。写锁在事务提交前一直持有，
     * 因此本事务随后 {@link #findNextValueBySeqKey} 读回的值不会被插队。
     *
     * @return 受影响行数（1=序列行存在并已自增）
     */
    @Modifying
    @Query("update FacilityCodeSequence s set s.nextValue = s.nextValue + 1, s.updatedAt = :now "
            + "where s.seqKey = :seqKey")
    int incrementNextValue(@Param("seqKey") String seqKey, @Param("now") LocalDateTime now);

    /** 读自增后的当前序列值（标量查询，绕过实体一级缓存，直接取库内最新值）。 */
    @Query("select s.nextValue from FacilityCodeSequence s where s.seqKey = :seqKey")
    long findNextValueBySeqKey(@Param("seqKey") String seqKey);
}
