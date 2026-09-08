-- Web Modernization Round 15: genuine Reservation booking records (see Reservation.java's javadoc
-- for why this is a standalone table rather than reusing TableStatus.RESERVED).
-- SQLite (default 'dev' profile) uses Hibernate ddl-auto instead, same as every other migration in
-- this project - see V1's header note.

CREATE TABLE reservation (
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    version        BIGINT       NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    customer_name  VARCHAR(255) NOT NULL,
    customer_phone VARCHAR(255),
    party_size     INTEGER      NOT NULL,
    reserved_for   TIMESTAMP    NOT NULL,
    table_id       CHAR(36),
    notes          VARCHAR(1000),
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    CONSTRAINT fk_reservation_table FOREIGN KEY (table_id) REFERENCES restaurant_table (id)
);

CREATE INDEX idx_reservation_reserved_for ON reservation (reserved_for);
