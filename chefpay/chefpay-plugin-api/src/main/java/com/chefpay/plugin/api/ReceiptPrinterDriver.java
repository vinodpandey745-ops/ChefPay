package com.chefpay.plugin.api;

import com.chefpay.plugin.api.dto.BillDto;

/**
 * Extension point for driving a physical (thermal/dot-matrix) or virtual receipt
 * printer. If no ReceiptPrinterDriver is enabled, ChefPay falls back to rendering
 * the receipt to PDF and showing a system print dialog / saving to disk.
 */
public interface ReceiptPrinterDriver extends ChefPayPlugin {

    void printReceipt(BillDto bill);
}
