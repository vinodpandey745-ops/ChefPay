package com.chefpay.core.domain;

/** Per-channel ingestion outcome within one {@link EodSession} (AI Backbone Addendum F1.1). */
public enum ChannelIngestionStatus {
    PENDING,
    SUCCESS,
    FAILED,
    /** No aggregator/gateway integration is enabled for this restaurant - not an error, this
     * channel simply has nothing to reconcile today (NFR-9: graceful degradation). */
    NOT_CONFIGURED
}
