-- Phase 2: Subscription / Licensing. Entirely new subsystem - nothing here existed before (see
-- Gap Analysis). One Subscription row per install (per the confirmed "one deployment per
-- restaurant business" hosting model), not one per "organization" in a shared multi-tenant sense -
-- enforced by the UNIQUE constraint on subscription.restaurant_id below.

CREATE TABLE subscription_plan (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    duration_days INT NOT NULL,
    price DECIMAL(12,2) NOT NULL DEFAULT 0,
    gst_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    max_branches INT,
    max_terminals INT,
    max_users INT,
    is_trial BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE feature (
    id CHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_feature_code UNIQUE (code)
);

CREATE TABLE plan_feature (
    subscription_plan_id CHAR(36) NOT NULL,
    feature_id CHAR(36) NOT NULL,
    PRIMARY KEY (subscription_plan_id, feature_id),
    CONSTRAINT fk_pf_plan    FOREIGN KEY (subscription_plan_id) REFERENCES subscription_plan(id),
    CONSTRAINT fk_pf_feature FOREIGN KEY (feature_id)            REFERENCES feature(id)
);

CREATE TABLE subscription (
    id CHAR(36) PRIMARY KEY,
    restaurant_id CHAR(36) NOT NULL,
    subscription_plan_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    expiry_date DATE NOT NULL,
    grace_period_days INT NOT NULL DEFAULT 3,
    warning_thresholds_days VARCHAR(100) NOT NULL DEFAULT '30,15,7,3,1',
    support_phone VARCHAR(32),
    last_offline_validated_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_sub_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant(id),
    CONSTRAINT fk_sub_plan       FOREIGN KEY (subscription_plan_id) REFERENCES subscription_plan(id),
    CONSTRAINT uq_subscription_restaurant UNIQUE (restaurant_id)
);
