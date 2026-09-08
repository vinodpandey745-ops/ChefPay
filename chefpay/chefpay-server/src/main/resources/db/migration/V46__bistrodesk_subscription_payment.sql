-- Subscription Renewal and Plan Upgrade requirement: one row per attempted Razorpay renewal/plan-
-- change payment (order creation through verification/failure/cancellation) - see
-- SubscriptionPayment's own javadoc for the exact activation guarantee this table backs.
-- SQLite (default 'dev' profile) uses Hibernate ddl-auto instead, same as every other migration in
-- this project - see V1's header note.

CREATE TABLE subscription_payment (
    id                    CHAR(36)      NOT NULL PRIMARY KEY,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMP     NOT NULL,
    updated_at            TIMESTAMP     NOT NULL,
    branch_id             CHAR(36)      NOT NULL,
    subscription_plan_id  CHAR(36)      NOT NULL,
    purpose               VARCHAR(20)   NOT NULL,
    amount                DECIMAL(12,2) NOT NULL,
    currency              VARCHAR(8)    NOT NULL DEFAULT 'INR',
    payment_method        VARCHAR(20),
    status                VARCHAR(20)   NOT NULL,
    gateway_order_id      VARCHAR(100)  NOT NULL,
    gateway_payment_id    VARCHAR(100),
    initiated_by_id       CHAR(36),
    previous_expiry_date  DATE,
    new_expiry_date       DATE,
    failure_reason        VARCHAR(500),
    CONSTRAINT fk_subpay_branch   FOREIGN KEY (branch_id)            REFERENCES branch(id),
    CONSTRAINT fk_subpay_plan     FOREIGN KEY (subscription_plan_id) REFERENCES subscription_plan(id),
    CONSTRAINT fk_subpay_user     FOREIGN KEY (initiated_by_id)      REFERENCES app_user(id)
);

CREATE INDEX idx_subpay_branch_created ON subscription_payment (branch_id, created_at);
CREATE INDEX idx_subpay_gateway_order ON subscription_payment (gateway_order_id);
