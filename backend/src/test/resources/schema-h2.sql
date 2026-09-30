-- H2（MySQL 兼容模式）专用：集成测试建表脚本。
-- 点位模块的表结构与 MySQL 正式脚本（schema.sql）保持一致：同样有点位唯一编号、
-- facility_point_sequence 行锁取号流水、facility_create_request 动作幂等台账。
SET MODE MySQL;

CREATE TABLE sys_user (
    id           BIGINT       NOT NULL,
    user_name    VARCHAR(32)  NOT NULL,
    display_name VARCHAR(32)  NOT NULL,
    role         VARCHAR(24)  NOT NULL,
    phone        VARCHAR(20),
    created_at   TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (user_name)
);

INSERT INTO sys_user (id, user_name, display_name, role, phone, created_at) VALUES
    (1, 'fire_lead', '陈默', 'FIRE_MANAGER', '13900000001', '2026-01-08 09:00:00'),
    (2, 'site_zhou', '周大勇', 'SITE_OWNER', '13900000002', '2026-01-08 09:00:00'),
    (3, 'site_wu', '吴迪', 'SITE_OWNER', '13900000003', '2026-01-08 09:00:00');

CREATE TABLE building (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(16), name VARCHAR(64), address VARCHAR(128),
    building_type VARCHAR(16), floors_above INT, floors_below INT,
    area_sqm DECIMAL(10,2), fire_manager VARCHAR(32), contact_phone VARCHAR(20),
    created_at TIMESTAMP, updated_at TIMESTAMP
);

CREATE TABLE building_floor (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    building_id BIGINT NOT NULL,
    floor_code  VARCHAR(8) NOT NULL,
    floor_name  VARCHAR(32) NOT NULL,
    sort_no     INT NOT NULL DEFAULT 0,
    area_sqm    DECIMAL(10,2),
    room_count  INT,
    exit_count  INT,
    created_at  TIMESTAMP
);

CREATE TABLE facility_point (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    building_id      BIGINT NOT NULL,
    floor_id         BIGINT,
    fp_no            VARCHAR(32) NOT NULL,
    facility_type    VARCHAR(16) NOT NULL,
    spec             VARCHAR(64),
    place_desc       VARCHAR(128),
    installed_on     DATE,
    check_cycle_days INT,
    last_check_date  DATE,
    next_check_date  DATE,
    status           VARCHAR(16) NOT NULL,
    active_disable_permit_id BIGINT,
    plan_x           INT,
    plan_y           INT,
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP,
    UNIQUE (fp_no)
);
CREATE INDEX idx_point_building ON facility_point (building_id);

-- 点位编号流水：与 MySQL 版结构一致，服务端对该行 SELECT ... FOR UPDATE 取号
CREATE TABLE facility_point_sequence (
    building_id BIGINT NOT NULL PRIMARY KEY,
    next_seq    BIGINT NOT NULL,
    updated_at  TIMESTAMP
);

-- 新增点位动作幂等台账：与 MySQL 版结构一致（point_id 唯一，提交后必非空）
CREATE TABLE facility_create_request (
    action_key          VARCHAR(64) NOT NULL PRIMARY KEY,
    building_id         BIGINT NOT NULL,
    floor_id            BIGINT NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    point_id            BIGINT,
    point_code          VARCHAR(32),
    created_by          VARCHAR(32),
    created_at          TIMESTAMP NOT NULL,
    UNIQUE (point_id)
);
CREATE INDEX idx_fcr_building ON facility_create_request (building_id);

-- 点位视图组装时会关联的停用许可（toView 只取编号/名称，这里建空表即可）
CREATE TABLE disable_permit (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    permit_no             VARCHAR(24) NOT NULL UNIQUE,
    building_id           BIGINT NOT NULL,
    reason                VARCHAR(255) NOT NULL,
    plan_start            DATE NOT NULL,
    plan_end              DATE NOT NULL,
    site_owner_id         BIGINT NOT NULL,
    site_owner_name       VARCHAR(32) NOT NULL,
    created_by            VARCHAR(32) NOT NULL,
    status                VARCHAR(24) NOT NULL,
    scope_locked          TINYINT NOT NULL DEFAULT 0,
    approved_at           TIMESTAMP,
    approved_by           VARCHAR(32),
    reject_reason         VARCHAR(255),
    closed_at             TIMESTAMP,
    closed_by             VARCHAR(32),
    revoked_at            TIMESTAMP,
    revoked_by            VARCHAR(32),
    revoke_reason         VARCHAR(255),
    reset_started_at      TIMESTAMP,
    last_verify_at        TIMESTAMP,
    last_verify_by        VARCHAR(32),
    last_verify_fail_reason VARCHAR(255),
    created_at            TIMESTAMP,
    updated_at            TIMESTAMP,
    version               BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE drill_session (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    drill_no       VARCHAR(24)  NOT NULL UNIQUE,
    title          VARCHAR(128) NOT NULL,
    building_id    BIGINT,
    scenario       VARCHAR(500),
    organizer      VARCHAR(32)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    ready_at       TIMESTAMP NULL,
    started_at     TIMESTAMP NULL,
    paused_at      TIMESTAMP NULL,
    finished_at    TIMESTAMP NULL,
    finished_by    VARCHAR(32),
    canceled_at    TIMESTAMP NULL,
    canceled_by    VARCHAR(32),
    cancel_reason  VARCHAR(255),
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP NOT NULL,
    version        BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_drill_status ON drill_session (status);

CREATE TABLE drill_post (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    post_code VARCHAR(32) NOT NULL,
    post_name VARCHAR(64) NOT NULL,
    assignee VARCHAR(32),
    contact VARCHAR(32),
    sort_no INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (session_id, post_code)
);

CREATE TABLE drill_card (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    seq_no INT NOT NULL,
    title VARCHAR(128) NOT NULL,
    content VARCHAR(500) NOT NULL,
    scene_hint VARCHAR(500),
    target_post_id BIGINT,
    target_post_code VARCHAR(32),
    target_post_name VARCHAR(64),
    time_limit_seconds INT NOT NULL DEFAULT 60,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    issued_at TIMESTAMP NULL,
    issued_by VARCHAR(32),
    deadline TIMESTAMP NULL,
    remaining_frozen_seconds INT,
    confirmed_at TIMESTAMP NULL,
    confirmed_by VARCHAR(32),
    actual_action VARCHAR(500),
    confirm_remark VARCHAR(500),
    timed_out_at TIMESTAMP NULL,
    skipped_at TIMESTAMP NULL,
    skipped_by VARCHAR(32),
    skip_reason VARCHAR(500),
    disposition VARCHAR(500),
    disposition_at TIMESTAMP NULL,
    disposition_by VARCHAR(32),
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_card_session ON drill_card (session_id, seq_no);

CREATE TABLE drill_card_dependency (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    card_id BIGINT NOT NULL,
    prereq_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (card_id, prereq_id)
);

CREATE TABLE drill_response_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    card_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    result VARCHAR(16) NOT NULL,
    actual_action VARCHAR(500) NOT NULL,
    remark VARCHAR(500),
    operator VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE drill_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    target_type VARCHAR(16),
    target_id BIGINT,
    action_key VARCHAR(64),
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    operator VARCHAR(32) NOT NULL,
    note VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    UNIQUE (session_id, action_key)
);

CREATE TABLE drill_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_by VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE drill_template_post (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL,
    post_code VARCHAR(32) NOT NULL,
    post_name VARCHAR(64) NOT NULL,
    sort_no INT NOT NULL DEFAULT 0
);

CREATE TABLE drill_template_card (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL,
    seq_no INT NOT NULL,
    title VARCHAR(128) NOT NULL,
    content VARCHAR(500) NOT NULL,
    scene_hint VARCHAR(500),
    target_post_code VARCHAR(32),
    time_limit_seconds INT NOT NULL DEFAULT 60,
    prereq_seqs VARCHAR(128)
);
