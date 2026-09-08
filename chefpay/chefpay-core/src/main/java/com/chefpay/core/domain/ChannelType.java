package com.chefpay.core.domain;

/** Which kind of settlement channel a {@link ChannelIngestion} row tracks (AI Backbone Addendum F1.1). */
public enum ChannelType {
    POS,
    PAYMENT_GATEWAY,
    AGGREGATOR
}
